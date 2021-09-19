package io.chrisdavenport.singlefibered

import munit.CatsEffectSuite
import cats._
import cats.effect._
import cats.syntax.all._

class SingleFiberedSpec extends CatsEffectSuite {

  test("Only allow one increment at the same time") {
    for {
      ref <- Ref[IO].of(0)
      mod = Resource.make(ref.update(_ + 1))(_ => ref.update(_ - 1))
      action = mod.use(
        _ => ref.get.flatMap(i => if (i == 1) Applicative[IO].unit else new Throwable("Ack").raiseError[IO, Unit])
      )
      f <- SingleFibered.prepare(action)
      l <- List.fill(10000)(f.attempt).parSequence
      check = l.sequence
    } yield assume(check.isRight)
  }

}
