package com.evolutiongaming.kafka.journal

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.evolutiongaming.concurrent.CurrentThreadExecutionContext
import com.evolutiongaming.concurrent.async.Async
import com.evolutiongaming.concurrent.async.Async.{Failed, InCompleted, Succeed}
import com.evolutiongaming.kafka.journal.FutureHelper._
import com.evolutiongaming.nel.Nel

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.ControlThrowable
import scala.util.{Failure, Success}

object FoldWhileHelper {

  type Fold[S, E] = (S, E) => Switch[S]

  case class Switch[@specialized +S](s: S, continue: Boolean) {
    def stop: Boolean = !continue
    def map[SS](f: S => SS): Switch[SS] = copy(s = f(s))
    def nest: Switch[Switch[S]] = map(_ => this)
  }

  object Switch {
    def continue[@specialized S](s: S) = Switch(s, continue = true)
    def stop[@specialized S](s: S) = Switch(s, continue = false)
  }


  implicit class AnySwitchOps[S](val self: S) extends AnyVal {
    def continue: Switch[S] = Switch.continue(self)
    def stop: Switch[S] = Switch.stop(self)
    def switch(continue: Boolean): Switch[S] = Switch(self, continue)
  }


  implicit class IterableFoldWhile[E](val self: Iterable[E]) extends AnyVal {
    import IterableFoldWhile._

    def foldWhile[S](s: S)(f: Fold[S, E]): Switch[S] = {
      try {
        val ss = self.foldLeft(s) { (s, e) =>
          val switch = f(s, e)
          if (switch.stop) throw Return(switch) else switch.s
        }
        ss.continue
      } catch {
        case Return(switch) => switch.asInstanceOf[Switch[S]]
      }
    }
  }

  object IterableFoldWhile {
    private case class Return[S](s: S) extends ControlThrowable
  }


  implicit class NelFoldWhile[E](val self: Nel[E]) extends AnyVal {
    def foldWhile[S](s: S)(f: Fold[S, E]): Switch[S] = {
      self.toList.foldWhile(s)(f)
    }
  }


  implicit class FutureFoldWhile[S](val self: S => Future[Switch[S]]) extends AnyVal {

    def foldWhile(s: S)(implicit ec: ExecutionContext): Future[S] = {
      for {
        switch <- self(s)
        s = switch.s
        s <- if (switch.stop) s.future else foldWhile(s)
      } yield s
    }
  }

  implicit class AsyncFoldWhile[S](val self: S => Async[Switch[S]]) extends AnyVal {

    def foldWhile(s: S): Async[S] = {

      @tailrec def foldWhile(switch: Switch[S]): Async[S] = {
        if (switch.stop) Async[S](switch.s)
        else {
          self(switch.s) match {
            case Succeed(v)                => foldWhile(v)
            case Failed(v)                 => Failed(v)
            case v: InCompleted[Switch[S]] => v.value() match {
              case Some(Success(v)) => foldWhile(v)
              case Some(Failure(v)) => Failed(v)
              case None             => v.flatMap(break)
            }
          }
        }
      }

      def break(switch: Switch[S]): Async[S] = foldWhile(switch)

      foldWhile(s.continue)
    }
  }


  implicit class SourceObjFoldWhile(val self: Source.type) extends AnyVal {

    def foldWhile[S, E](s: S)(f: S => Future[(Switch[S], Iterable[E])]): Source[E, NotUsed] = {
      implicit val ec = CurrentThreadExecutionContext
      val source = Source.unfoldAsync[Switch[S], Iterable[E]](s.continue) { switch =>
        if (switch.stop) Future.none
        else {
          for {
            (switch, es) <- f(switch.s)
          } yield {
            if (switch.stop || es.isEmpty) None
            else Some((switch, es))
          }
        }
      }
      source.mapConcat(_.to[immutable.Iterable])
    }
  }
}