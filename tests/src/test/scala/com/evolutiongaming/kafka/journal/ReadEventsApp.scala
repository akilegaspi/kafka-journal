package com.evolutiongaming.kafka.journal

import cats.effect._
import cats.implicits._
import com.evolutiongaming.kafka.journal.eventual.cassandra._
import com.evolutiongaming.kafka.journal.util.{FromFuture, ToFuture}
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.scassandra.{AuthenticationConfig, CassandraConfig}
import com.evolutiongaming.skafka.CommonConfig
import com.evolutiongaming.skafka.consumer.ConsumerConfig
import com.evolutiongaming.skafka.producer.{Acks, ProducerConfig}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object ReadEventsApp extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    implicit val parallel = IO.ioParallel
    implicit val ec = ExecutionContext.global
    runF[IO].as(ExitCode.Success)
  }

  private def runF[F[_] : Concurrent : ContextShift : Timer : Clock : FromFuture : ToFuture : Par](
    implicit ec: ExecutionContext
  ): F[Unit] = {

    for {
      logOf  <- LogOf.slfj4[F]
      log    <- logOf(ReadEventsApp.getClass)
      result <- {
        implicit val logOf1 = logOf
        implicit val log1 = log
        runF[F](ec, log).handleErrorWith { error =>
          log.error(s"failed with $error", error)
        }
      }
    } yield result

  }

  private def runF[F[_] : Concurrent : ContextShift : Timer : Clock : FromFuture : ToFuture : Par : LogOf : Log](
    blocking: ExecutionContext, log: Log[F]): F[Unit] = {

    implicit val kafkaConsumerOf = KafkaConsumerOf[F](blocking)

    implicit val kafkaProducerOf = KafkaProducerOf[F](blocking)

    val commonConfig = CommonConfig(
      clientId = "ReadEventsApp".some,
      bootstrapServers = Nel("localhost:9092"))

    val producerConfig = ProducerConfig(
      common = commonConfig,
      idempotence = true,
      acks = Acks.All)

    val consumerConfig = ConsumerConfig(common = commonConfig)

    val consumer = Journal.Consumer.of[F](consumerConfig)

    val eventualCassandraConfig = EventualCassandraConfig(
      schema = SchemaConfig(
        keyspace = SchemaConfig.Keyspace(
          name = "keyspace",
          autoCreate = false),
        autoCreate = false),
      client = CassandraConfig(
        contactPoints = Nel("127.0.0.1"),
        authentication = Some(AuthenticationConfig(
          username = "username",
          password = "password"))))

    val journal = for {
      eventualJournal <- EventualCassandra.of[F](eventualCassandraConfig, None)
      headCache       <- HeadCache.of[F](consumerConfig, eventualJournal, None)
      producer        <- Journal.Producer.of[F](producerConfig)
    } yield {
      val origin = Origin("ReadEventsApp")
      val journal = Journal[F](origin.some, producer, consumer, eventualJournal, 100.millis, headCache)
      val key = Key(id = "id", topic = "topic")
      for {
        pointer <- journal.pointer(key)
        seqNrs  <- journal.read(key, SeqNr.Min).map(_.seqNr).toList
        _       <- log.info(s"pointer: $pointer")
        _       <- log.info(s"seqNrs: $seqNrs")
      } yield {}
    }

    journal.use(identity)
  }
}
