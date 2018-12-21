package com.evolutiongaming.kafka.journal.eventual.cassandra

import cats.FlatMap
import cats.effect.IO
import com.datastax.driver.core._
import com.datastax.driver.core.policies.{LoggingRetryPolicy, RetryPolicy}
import com.evolutiongaming.kafka.journal.eventual.cassandra.CassandraHelper._
import com.evolutiongaming.kafka.journal.util.IOFromFuture
import com.evolutiongaming.kafka.journal.{FromFuture, IO2}
import com.evolutiongaming.scassandra.{NextHostRetryPolicy, Session}

trait CassandraSession[F[_]] {

  def prepare(query: String): F[PreparedStatement]

  def execute(statement: Statement): F[ResultSet]

  final def execute(statement: String): F[ResultSet] = execute(new SimpleStatement(statement))
}


object CassandraSession {

  def apply[F[_]](implicit F: CassandraSession[F]): CassandraSession[F] = F

  
  def apply[F[_] : FlatMap](
    session: CassandraSession[F],
    retries: Int,
    trace: Boolean = false): CassandraSession[F] = {

    val retryPolicy = new LoggingRetryPolicy(NextHostRetryPolicy(retries))
    apply(session, retryPolicy, trace)
  }


  def apply[F[_] : FlatMap](
    session: CassandraSession[F],
    retryPolicy: RetryPolicy,
    trace: Boolean): CassandraSession[F] = new CassandraSession[F] {

    def prepare(query: String) = {
      session.prepare(query)
    }

    def execute(statement: Statement) = {
      val configured = statement
        .setRetryPolicy(retryPolicy)
        .setIdempotent(true)
        .trace(trace)
      session.execute(configured)
    }
  }


  def apply[F[_] : IO2 : FromFuture](session: Session): CassandraSession[F] = new CassandraSession[F] {

    def prepare(query: String) = {
      IO2[F].from {
        session.prepare(query)
      }
    }

    def execute(statement: Statement) = {
      import com.evolutiongaming.kafka.journal.IO2.ops._
      for {
        resultSet <- IO2[F].from {
          session.execute(statement)
        }
      } yield {
        ResultSet(resultSet)
      }
    }
  }


  def apply(session: Session): CassandraSession[IO] = new CassandraSession[IO] {

    def prepare(query: String) = {
      IOFromFuture {
        session.prepare(query)
      }
    }

    def execute(statement: Statement) = {
      for {
        resultSet <- IOFromFuture {
          session.execute(statement)
        }
      } yield {
        ResultSet(resultSet)
      }
    }
  }
}