package io.chrisdavenport.singlefibered

import munit.CatsEffectSuite
import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

/** Cancelation is caller-local: canceling one caller detaches that caller only.
  * It must not abort the shared computation, and it must not fail the other
  * callers awaiting it.
  */
class SingleFiberedCancelationSpec extends CatsEffectSuite {

  override def munitIOTimeout = 30.seconds

  test("canceling one caller does not fail the others, and the work still runs") {
    for {
      completions <- Ref[IO].of(0)
      action = IO.sleep(1.second) >> completions.update(_ + 1).as(42)
      fa <- SingleFibered.prepare(action)

      leader <- fa.start
      _ <- IO.sleep(100.millis) // let the leader claim the slot
      waiters <- List.fill(5)(fa.attempt.start).sequence
      _ <- IO.sleep(100.millis) // let the waiters park on the Deferred

      _ <- leader.cancel
      results <- waiters.traverse(_.joinWithNever)
      ran <- completions.get
    } yield {
      assertEquals(results, List.fill(5)(Right(42)))
      assertEquals(ran, 1, "the shared computation should have run exactly once")
    }
  }

  test("keyed: canceling one caller does not fail the others on that key") {
    for {
      completions <- Ref[IO].of(0)
      fa <- SingleFibered.prepareFunction[IO, String, Int](_ =>
        IO.sleep(1.second) >> completions.update(_ + 1).as(1)
      )
      leader <- fa("k").start
      _ <- IO.sleep(100.millis)
      waiters <- List.fill(3)(fa("k").attempt.start).sequence
      _ <- IO.sleep(100.millis)
      _ <- leader.cancel
      results <- waiters.traverse(_.joinWithNever)
      ran <- completions.get
    } yield {
      assertEquals(results, List.fill(3)(Right(1)))
      assertEquals(ran, 1)
    }
  }

  test("a canceled caller is still canceled promptly") {
    for {
      fa <- SingleFibered.prepare(IO.sleep(10.seconds).as(1))
      leader <- fa.start
      _ <- IO.sleep(100.millis)
      // cancel must return well before the 10s computation finishes
      outcome <- leader.cancel.timeout(2.seconds).attempt
      joined <- leader.join
    } yield {
      assert(outcome.isRight, "cancelation should not block on the shared computation")
      assert(joined.isCanceled, s"caller should observe its own cancelation, got $joined")
    }
  }

  test("every caller canceling does not wedge the key") {
    for {
      completions <- Ref[IO].of(0)
      fa <- SingleFibered.prepare(IO.sleep(500.millis) >> completions.updateAndGet(_ + 1))
      callers <- List.fill(3)(fa.start).sequence
      _ <- IO.sleep(100.millis)
      _ <- callers.traverse_(_.cancel)
      // the in-flight computation runs to completion and releases the slot
      _ <- IO.sleep(1.second)
      next <- fa // must not hang, and must be a fresh run
    } yield assertEquals(next, 2)
  }

  test("errors are shared with every caller") {
    val boom = new RuntimeException("boom")
    for {
      attempts <- Ref[IO].of(0)
      fa <- SingleFibered.prepare(
        IO.sleep(200.millis) >> attempts.update(_ + 1) >> IO.raiseError[Int](boom)
      )
      results <- List.fill(4)(fa.attempt).parSequence
      ran <- attempts.get
    } yield {
      assertEquals(results, List.fill(4)(Left(boom)))
      assertEquals(ran, 1, "the failing computation should have run exactly once")
    }
  }

  test("the slot is released so later calls re-run the computation") {
    for {
      runs <- Ref[IO].of(0)
      fa <- SingleFibered.prepare(runs.updateAndGet(_ + 1))
      a <- fa
      b <- fa
      c <- fa
    } yield assertEquals(List(a, b, c), List(1, 2, 3))
  }
}
