package com.evolutiongaming.kafka.journal

import java.time.Instant
import java.util.UUID

import akka.actor.ActorSystem
import cats._
import cats.effect.Clock
import cats.implicits._
import com.evolutiongaming.concurrent.async.Async
import com.evolutiongaming.concurrent.async.AsyncConverters._
import com.evolutiongaming.kafka.journal.AsyncHelper._
import com.evolutiongaming.kafka.journal.EventsSerializer._
import com.evolutiongaming.kafka.journal.FoldWhile._
import com.evolutiongaming.kafka.journal.FoldWhileHelper._
import com.evolutiongaming.kafka.journal.eventual.EventualJournal
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.safeakka.actor.ActorLog
import com.evolutiongaming.skafka.producer.Producer
import com.evolutiongaming.skafka.{Bytes => _, _}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait Journal[F[_]] {

  def append(key: Key, events: Nel[Event], timestamp: Instant): F[PartitionOffset]

  def read[S](key: Key, from: SeqNr, s: S)(f: Fold[S, Event]): F[S]

  def pointer(key: Key): F[Option[SeqNr]]

  // TODO return Pointer and Test it
  def delete(key: Key, to: SeqNr, timestamp: Instant): F[Option[PartitionOffset]]
}

object Journal {

  def empty[F[_] : Applicative]: Journal[F] = new Journal[F] {

    def append(key: Key, events: Nel[Event], timestamp: Instant) = Applicative[F].pure(PartitionOffset.Empty)

    def read[S](key: Key, from: SeqNr, s: S)(f: Fold[S, Event]) = Applicative[F].pure(s)

    def pointer(key: Key) = Applicative[F].pure(none)

    def delete(key: Key, to: SeqNr, timestamp: Instant) = Applicative[F].pure(none)
  }


  def apply[F[_] : FlatMap : Clock : Log](journal: Journal[F]): Journal[F] = new Journal[F] {

    def append(key: Key, events: Nel[Event], timestamp: Instant) = {
      for {
        rl     <- Latency { journal.append(key, events, timestamp) }
        (r, l)  = rl
        _      <- Log[F].debug {
          val first = events.head.seqNr
          val last = events.last.seqNr
          val seqNr = if (first == last) s"seqNr: $first" else s"seqNrs: $first..$last"
          s"$key append in ${ l }ms, $seqNr, timestamp: $timestamp, result: $r"
        }
      } yield r
    }

    def read[S](key: Key, from: SeqNr, s: S)(f: Fold[S, Event]) = {
      for {
        rl     <- Latency { journal.read(key, from, s)(f) }
        (r, l)  = rl
        _      <- Log[F].debug(s"$key read in ${ l }ms, from: $from, state: $s, r: $r")
      } yield r
    }

    def pointer(key: Key) = {
      for {
        rl     <- Latency { journal.pointer(key) }
        (r, l)  = rl
        _      <- Log[F].debug(s"$key lastSeqNr in ${ l }ms, result: $r")
      } yield r
    }

    def delete(key: Key, to: SeqNr, timestamp: Instant) = {
      for {
        rl     <- Latency { journal.delete(key, to, timestamp) }
        (r, l)  = rl
        _      <- Log[F].debug(s"$key delete in ${ l }ms, to: $to, timestamp: $timestamp, r: $r")
      } yield r
    }
  }


  def apply[F[_] : Clock, E](
    journal: Journal[F],
    metrics: Metrics[F])(implicit monadError: MonadError[F, E]): Journal[F] = {

    def latency[A](name: String, topic: Topic)(f: => F[A]/*TODO*/): F[(A, Long)] = {
      Latency {
        f.handleErrorWith { e =>
          for {
            _ <- metrics.failure(name, topic)
            a <- e.raiseError[F, A]
          } yield a
        }
      }
    }

    new Journal[F] {

      def append(key: Key, events: Nel[Event], timestamp: Instant) = {
        for {
          rl     <- latency("append", key.topic) { journal.append(key, events, timestamp) }
          (r, l)  = rl
          _      <- metrics.append(topic = key.topic, latency = l, events = events.size)
        } yield r
      }

      def read[S](key: Key, from: SeqNr, s: S)(f: Fold[S, Event]) = {
        val ff: Fold[(S, Int), Event] = {
          case ((s, n), e) => f(s, e).map { s => (s, n + 1) }
        }
        for {
          rl           <- latency("read", key.topic) { journal.read(key, from, (s, 0))(ff) }
          ((r, es), l)  = rl
          _            <- metrics.read(topic = key.topic, latency = l, events = es)
        } yield r
      }

      def pointer(key: Key) = {
        for {
          rl     <- latency("pointer", key.topic) { journal.pointer(key) }
          (r, l)  = rl
          _      <- metrics.pointer(key.topic, l)
        } yield r
      }

      def delete(key: Key, to: SeqNr, timestamp: Instant) = {
        for {
          rl     <- latency("delete", key.topic) { journal.delete(key, to, timestamp) }
          (r, l)  = rl
          _      <- metrics.delete(key.topic, l)
        } yield r
      }
    }
  }


  def apply(
    producer: Producer[Future],
    origin: Option[Origin],
    topicConsumer: TopicConsumer,
    eventual: EventualJournal[Async],
    pollTimeout: FiniteDuration,
    closeTimeout: FiniteDuration,
    headCache: HeadCache[Async])(implicit
    system: ActorSystem,
    ec: ExecutionContext): Journal[Async] = {

    val actorLog = ActorLog(system, Journal.getClass)

    implicit val log = Log[Async](actorLog)

    val journal = apply(actorLog, origin, producer, topicConsumer, eventual, pollTimeout, closeTimeout, headCache)
    Journal(journal)
  }

  def apply(
    log: ActorLog, // TODO remove
    origin: Option[Origin],
    producer: Producer[Future],
    topicConsumer: TopicConsumer,
    eventual: EventualJournal[Async],
    pollTimeout: FiniteDuration,
    closeTimeout: FiniteDuration,
    headCache: HeadCache[Async])(implicit
    ec: ExecutionContext): Journal[Async] = {

    val withReadActions = WithReadActions(topicConsumer, pollTimeout, closeTimeout, log)

    val writeAction = AppendAction(producer)

    apply(log, origin, eventual, withReadActions, writeAction, headCache)
  }


  // TODO too many arguments, add config?
  def apply(
    log: ActorLog,
    origin: Option[Origin],
    eventual: EventualJournal[Async],
    withReadActions: WithReadActions[Async],
    appendAction: AppendAction[Async],
    headCache: HeadCache[Async]): Journal[Async] = {

    def readActions(key: Key, from: SeqNr) = {
      val marker = {
        val id = UUID.randomUUID().toString // TODO
        val action = Action.Mark(key, Instant.now(), origin, id)
        for {
          partitionOffset <- appendAction(action)
        } yield {
          log.debug(s"$key mark, id: $id, offset $partitionOffset")
          Marker(id, partitionOffset)
        }
      }
      val pointers = eventual.pointers(key.topic)
      for {
        marker <- marker
        result <- if (marker.offset == Offset.Min) {
          (Some(JournalInfo.Empty), FoldActions.empty[Async]).async
        } else {
          for {
            result   <- headCache(key, partition = marker.partition, offset = Offset.Min max marker.offset - 1)
            pointers <- pointers
            offset    = pointers.values.get(marker.partition)
          } yield {
            val info = for {
              result <- result
            } yield {
              def info = result.deleteTo.fold[JournalInfo](JournalInfo.Empty)(JournalInfo.Deleted(_))

              result.seqNr.fold(info)(JournalInfo.NonEmpty(_, result.deleteTo))
            }
            val foldActions = FoldActions(key, from, marker, offset, withReadActions)
            (info, foldActions)
          }
        }
      } yield {
        result
      }
    }

    new Journal[Async] {

      def append(key: Key, events: Nel[Event], timestamp: Instant) = {
        val action = Action.Append(key, timestamp, origin, events)
        appendAction(action)
      }

      // TODO add optimisation for ranges
      def read[S](key: Key, from: SeqNr, s: S)(f: Fold[S, Event]) = {

        def replicatedSeqNr(from: SeqNr) = {
          val ss: (S, Option[SeqNr], Option[Offset]) = (s, Some(from), None)
          eventual.read(key, from, ss) { case ((s, _, _), replicated) =>
            val event = replicated.event
            val switch = f(s, event)
            switch.map { s =>
              val offset = replicated.partitionOffset.offset
              val from = event.seqNr.next
              (s, from, Some(offset))
            }
          }
        }

        def replicated(from: SeqNr) = {
          for {
            s <- eventual.read(key, from, s) { (s, replicated) => f(s, replicated.event) }
          } yield s.s
        }

        def onNonEmpty(deleteTo: Option[SeqNr], readActions: FoldActions[Async]) = {

          def events(from: SeqNr, offset: Option[Offset], s: S) = {
            readActions(offset, s) { case (s, action) =>
              action match {
                case action: Action.Append =>
                  if (action.range.to < from) s.continue
                  else {
                    val events = EventsFromPayload(action.payload, action.payloadType)
                    events.foldWhile(s) { case (s, event) =>
                      if (event.seqNr >= from) f(s, event) else s.continue
                    }
                  }

                case action: Action.Delete => s.continue
              }
            }
          }


          val fromFixed = deleteTo.fold(from) { deleteTo => from max deleteTo.next }

          for {
            switch           <- replicatedSeqNr(fromFixed)
            (s, from, offset) = switch.s
            _                 = log.debug(s"$key read from: $from, offset: $offset")
            s                <- from match {
              case None       => s.async
              case Some(from) => if (switch.stop) s.async else events(from, offset, s)
            }
          } yield s
        }

        for {
          (info, read) <- readActions(key, from)
          // TODO use range after eventualRecords
          // TODO prevent from reading calling consume twice!
          info         <- info match {
            case Some(info) => IO2[Async].pure(info)
            case None       => read(None, JournalInfo.empty) { (info, action) => info(action).continue }
          }
          _            = log.debug(s"$key read info: $info")
          result      <- info match {
            case JournalInfo.Empty                 => replicated(from)
            case JournalInfo.NonEmpty(_, deleteTo) => onNonEmpty(deleteTo, read)
            // TODO test this case
            case JournalInfo.Deleted(deleteTo) => deleteTo.next match {
              case None       => s.async
              case Some(next) => replicated(from max next)
            }
          }
        } yield result
      }

      def pointer(key: Key) = {
        // TODO reimplement, we don't need to call `eventual.pointer` without using it's offset

        val from = SeqNr.Min // TODO remove

        def seqNrEventual = eventual.pointer(key).map(_.map(_.seqNr))

        for {
          (info, readActions) <- readActions(key, from)
          result <- info match {
            case Some(info) => info match {
              case JournalInfo.Empty          => seqNrEventual
              case info: JournalInfo.NonEmpty => IO2[Async].pure(Some(info.seqNr))
              case info: JournalInfo.Deleted  => seqNrEventual
            }

            case None =>
              val pointer = eventual.pointer(key)
              for {
                seqNr <- readActions(None /*TODO provide offset from eventual.lastSeqNr*/ , Option.empty[SeqNr]) { (seqNr, action) =>
                  val result = action match {
                    case action: Action.Append => Some(action.range.to)
                    case action: Action.Delete => Some(action.to max seqNr)
                  }
                  result.continue
                }
                pointer <- pointer
              } yield {
                pointer.map(_.seqNr) max seqNr
              }
          }
        } yield result
      }

      def delete(key: Key, to: SeqNr, timestamp: Instant) = {
        for {
          seqNr  <- pointer(key)
          result <- seqNr match {
            case None        => Async.none
            case Some(seqNr) =>

              // TODO not delete already deleted, do not accept deleteTo=2 when already deleteTo=3
              val deleteTo = seqNr min to
              val action = Action.Delete(key, timestamp, origin, deleteTo)
              appendAction(action).map(Some(_))
          }
        } yield result
      }
    }
  }


  trait Metrics[F[_]] {

    def append(topic: Topic, latency: Long, events: Int): F[Unit]

    def read(topic: Topic, latency: Long, events: Int): F[Unit]

    def pointer(topic: Topic, latency: Long): F[Unit]

    def delete(topic: Topic, latency: Long): F[Unit]

    def failure(name: String, topic: Topic): F[Unit]
  }

  object Metrics {

    def empty[F[_]](unit: F[Unit]): Metrics[F] = new Metrics[F] {

      def append(topic: Topic, latency: Long, events: Int) = unit

      def read(topic: Topic, latency: Long, events: Int) = unit

      def pointer(topic: Topic, latency: Long) = unit

      def delete(topic: Topic, latency: Long) = unit

      def failure(name: String, topic: Topic) = unit
    }

    def empty[F[_] : Applicative]: Metrics[F] = empty(().pure[F])
  }
}