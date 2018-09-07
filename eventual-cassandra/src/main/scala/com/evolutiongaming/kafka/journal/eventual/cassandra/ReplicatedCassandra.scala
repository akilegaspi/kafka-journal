package com.evolutiongaming.kafka.journal.eventual.cassandra

import java.time.Instant

import akka.actor.ActorSystem
import com.evolutiongaming.cassandra.Session
import com.evolutiongaming.concurrent.async.Async
import com.evolutiongaming.kafka.journal.AsyncHelper._
import com.evolutiongaming.kafka.journal.FlatMap._
import com.evolutiongaming.kafka.journal._
import com.evolutiongaming.kafka.journal.eventual._
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.Topic

import scala.annotation.tailrec
import scala.compat.Platform
import scala.concurrent.ExecutionContext


// TODO create collection that is optimised for ordered sequence and seqNr
// TODO redesign EventualDbCassandra so it can hold stat and called recursively
// TODO add logs to ReplicatedCassandra
object ReplicatedCassandra {

  def apply(session: Session, config: EventualCassandraConfig)
    (implicit system: ActorSystem, ec: ExecutionContext): ReplicatedJournal[Async] = {

    val statements = for {
      tables <- CreateSchema(config.schema, session)
      prepareAndExecute = PrepareAndExecute(session, config.retries)
      statements <- Statements(tables, prepareAndExecute)
    } yield {
      statements
    }
    val journal = apply(statements, config.segmentSize)

    val actorLog = ActorLog(system, ReplicatedCassandra.getClass)
    val log = Log(actorLog)
    apply(journal, log)
  }

  def apply[F[_] : FlatMap](journal: ReplicatedJournal[F], log: Log[F]): ReplicatedJournal[F] = {

    def duration[T](func: => F[T]): F[(T, Long)] = {
      val start = Platform.currentTime
      for {
        result <- func
        duration = Platform.currentTime - start
      } yield (result, duration)
    }

    new ReplicatedJournal[F] {

      def topics() = {
        for {
          result <- duration { journal.topics() }
          (topics, duration) = result
          _ <- log.debug(s"topics in ${ duration }ms, topics: ${ topics.mkString(",") }")
        } yield topics
      }

      def pointers(topic: Topic) = {
        for {
          result <- duration { journal.pointers(topic) }
          (pointers, duration) = result
          _ <- log.debug(s"pointers in ${ duration }ms, topic: $topic, pointers: $pointers")
        } yield pointers
      }

      def append(key: Key, timestamp: Instant, events: Nel[ReplicatedEvent], deleteTo: Option[SeqNr]) = {
        for {
          result <- duration { journal.append(key, timestamp, events, deleteTo) }
          (_, duration) = result
          _ <- log.debug(s"append in ${ duration }ms, key: $key, deleteTo: $deleteTo, events: ${ events.mkString(",") }")
        } yield ()
      }

      def delete(key: Key, timestamp: Instant, deleteTo: SeqNr, bound: Boolean) = {
        for {
          result <- duration { journal.delete(key, timestamp, deleteTo, bound) }
          (_, duration) = result
          _ <- log.debug(s"delete in ${ duration }ms, key: $key, deleteTo: $deleteTo, bound: $bound")
        } yield ()
      }

      def save(topic: Topic, pointers: TopicPointers) = {
        for {
          result <- duration { journal.save(topic, pointers) }
          (_, duration) = result
          _ <- log.debug(s"save in ${ duration }ms, topic: $topic, pointers: $pointers")
        } yield ()
      }

      override def toString = journal.toString
    }
  }


  def apply(
    statements: Async[Statements],
    segmentSize: Int): ReplicatedJournal[Async] = {

    def deleteRecords(
      statements: Statements,
      key: Key,
      timestamp: Instant,
      seqNr: SeqNr,
      deleteTo: SeqNr,
      metadata: Metadata,
      bound: Boolean) = {

      def segment(seqNr: SeqNr) = SegmentNr(seqNr, metadata.segmentSize)

      def delete(from: SeqNr) = {

        def delete(deleteTo: SeqNr) = {
          for {
            _ <- statements.updateMetadata(key, Some(deleteTo), timestamp)
            _ <- Async.foldUnit {
              for {
                segment <- segment(from) to segment(deleteTo) // TODO maybe add ability to create Seq[Segment] out of SeqRange ?
              } yield {
                statements.deleteRecords(key, segment, deleteTo)
              }
            }
          } yield {}
        }

        if (bound) delete(deleteTo)
        else for {
          last <- LastSeqNr(key, from, metadata, statements.selectLastRecord)
          result <- last match {
            case None       => Async.unit
            case Some(last) => delete(deleteTo min last)
          }
        } yield {
          result
        }
      }

      metadata.deleteTo match {
        case None            => delete(SeqNr.Min)
        case Some(deletedTo) =>
          if (deletedTo >= deleteTo) Async.unit
          else deletedTo.next match {
            case None       => Async.unit
            case Some(from) => delete(from)
          }
      }
    }

    new ReplicatedJournal[Async] {

      def topics() = {
        for {
          statements <- statements
          topics <- statements.selectTopics()
        } yield topics.sorted
      }


      def append(key: Key, timestamp: Instant, events: Nel[ReplicatedEvent], deleteTo: Option[SeqNr]) = {

        def append(statements: Statements, metadata: Option[Metadata]) = {

          def lastSeqNr = events.foldLeft(SeqNr.Min) { (seqNr, event) => seqNr max event.seqNr }

          def append(segmentSize: Int) = {

            @tailrec
            def loop(
              events: List[ReplicatedEvent],
              s: Option[(Segment, Nel[ReplicatedEvent])], // TODO not tuple
              result: Async[Unit]): Async[Unit] = {

              def execute(segment: Segment, events: Nel[ReplicatedEvent]) = {
                val next = statements.insertRecords(key, segment.nr, events)
                for {
                  _ <- result
                  _ <- next
                } yield {}
              }

              events match {
                case head :: tail =>
                  val seqNr = head.event.seqNr
                  s match {
                    case Some((segment, batch)) => segment.next(seqNr) match {
                      case None =>
                        loop(tail, Some((segment, head :: batch)), result)

                      case Some(next) =>
                        loop(tail, Some((next, Nel(head))), execute(segment, batch))
                    }
                    case None                   =>
                      loop(tail, Some((Segment(seqNr, segmentSize), Nel(head))), result)
                  }

                case Nil =>
                  s.fold(result) { case (segment, batch) => execute(segment, batch) }
              }
            }

            loop(events.toList, None, Async.unit)
          }

          def updateMetadata(metadata: Metadata) = {
            // TODO do not update deleteTo
            for {
              _ <- statements.updateMetadata(key, metadata.deleteTo, timestamp)
            } yield metadata
          }

          def insertMetadata() = {
            val metadata = Metadata(segmentSize = segmentSize, seqNr = lastSeqNr, deleteTo = deleteTo)
            for {
              _ <- statements.insertMetadata(key, metadata, timestamp)
            } yield metadata
          }

          def delete(metadata: Metadata, deleteTo: SeqNr) = deleteRecords(
            statements = statements,
            key = key,
            timestamp = timestamp,
            seqNr = lastSeqNr,
            deleteTo = deleteTo,
            metadata = metadata,
            bound = true)

          for {
            metadata <- metadata match {
              case None           => insertMetadata()
              case Some(metadata) => for {
                _ <- deleteTo match {
                  case None           => updateMetadata(metadata)
                  case Some(deleteTo) => delete(metadata, deleteTo)
                }
              } yield metadata
            }
            _ <- append(metadata.segmentSize)
          } yield {}
        }

        for {
          statements <- statements
          metadata <- statements.selectMetadata(key)
          result <- append(statements, metadata)
        } yield result
      }


      def delete(key: Key, timestamp: Instant, deleteTo: SeqNr, bound: Boolean) = {

        def delete(statements: Statements, metadata: Option[Metadata]) = {

          def delete(metadata: Metadata) = deleteRecords(
            statements = statements,
            key = key,
            timestamp = timestamp,
            seqNr = deleteTo,
            deleteTo = deleteTo,
            metadata = metadata,
            bound = bound)

          def insertMetadata() = {
            val metadata = Metadata(
              segmentSize = segmentSize,
              seqNr = deleteTo,
              deleteTo = Some(deleteTo))
            statements.insertMetadata(key, metadata, timestamp)
          }

          metadata match {
            case Some(metadata) => delete(metadata)
            case None if bound  => insertMetadata()
            case None           => Async.unit
          }
        }

        for {
          statements <- statements
          metadata <- statements.selectMetadata(key)
          result <- delete(statements, metadata)
        } yield result
      }


      def save(topic: Topic, topicPointers: TopicPointers): Async[Unit] = {
        val pointers = topicPointers.values
        if (pointers.isEmpty) Async.unit
        else {

          // TODO topic is a partition key, should I batch by partition ?
          val timestamp = Instant.now() // TODO pass as argument

          def savePointers(statements: Statements) = {
            val asyncs = for {
              (partition, offset) <- pointers
            } yield {
              val insert = PointerInsert(
                topic = topic,
                partition = partition,
                offset = offset,
                updated = timestamp,
                created = timestamp)
              statements.insertPointer(insert)
            }

            Async.foldUnit(asyncs)
          }

          for {
            statements <- statements
            _ <- savePointers(statements)
          } yield {}
        }
      }

      def pointers(topic: Topic) = {
        for {
          statements <- statements
          topicPointers <- statements.selectPointers(topic)
        } yield topicPointers
      }
    }
  }


  final case class Statements(
    insertRecords: JournalStatement.InsertRecords.Type,
    selectLastRecord: JournalStatement.SelectLastRecord.Type,
    deleteRecords: JournalStatement.DeleteRecords.Type,
    insertMetadata: MetadataStatement.Insert.Type,
    selectMetadata: MetadataStatement.Select.Type,
    updateMetadata: MetadataStatement.Update.Type,
    insertPointer: PointerStatement.Insert.Type,
    selectPointers: PointerStatement.SelectPointers.Type,
    selectTopics: PointerStatement.SelectTopics.Type)

  object Statements {

    def apply(tables: Tables, prepareAndExecute: PrepareAndExecute): Async[Statements] = {

      val insertRecords = JournalStatement.InsertRecords(tables.journal, prepareAndExecute)
      val selectLastRecord = JournalStatement.SelectLastRecord(tables.journal, prepareAndExecute)
      val deleteRecords = JournalStatement.DeleteRecords(tables.journal, prepareAndExecute)
      val insertMetadata = MetadataStatement.Insert(tables.metadata, prepareAndExecute)
      val selectMetadata = MetadataStatement.Select(tables.metadata, prepareAndExecute)
      val updateMetadata = MetadataStatement.Update(tables.metadata, prepareAndExecute)
      val insertPointer = PointerStatement.Insert(tables.pointer, prepareAndExecute)
      val selectPointers = PointerStatement.SelectPointers(tables.pointer, prepareAndExecute)
      val selectTopics = PointerStatement.SelectTopics(tables.pointer, prepareAndExecute)

      for {
        insertRecords <- insertRecords
        selectLastRecord <- selectLastRecord
        deleteRecords <- deleteRecords
        insertMetadata <- insertMetadata
        selectMetadata <- selectMetadata
        updateMetadata <- updateMetadata
        insertPointer <- insertPointer
        selectPointers <- selectPointers
        selectTopics <- selectTopics
      } yield {
        Statements(
          insertRecords = insertRecords,
          selectLastRecord = selectLastRecord,
          deleteRecords = deleteRecords,
          insertMetadata = insertMetadata,
          selectMetadata = selectMetadata,
          updateMetadata = updateMetadata,
          insertPointer = insertPointer,
          selectPointers = selectPointers,
          selectTopics = selectTopics)
      }
    }
  }
}