package io.chrisdavenport.singlefibered

import cats.syntax.all._
import cats.effect._
import io.chrisdavenport.mapref.MapRef

object SingleFibered {

  /**
    * Prepares a function so that the resulting function is single-fibered.
    * This means that no matter how many fibers are executing this function.
    * For any specific key only 1 will be running at the same time, others
    * will share the result of that running computation. As soon as that computation
    * completes other computations will again be able to run the function again.
    * 
    * Any mildly calls that you want to reduce concurrent runs can leverage this,
    * but note that the suspension has an associated cost so you may want to avoid
    * this for particularly fast parts of a computation.
    * 
    */
  def prepareFunction[F[_]: Concurrent, K, V](f: K => F[V]): F[K => F[V]] = {
    val state: F[K => Ref[F, Option[F[V]]]] =
      MapRef.ofShardedImmutableMap[F, K, F[V]](10)
      .map((m: MapRef[F, K, Option[F[V]]]) =>
        {(k: K) => m(k)}  
      )
    state.map{ r => 
      singleFiberedFunction[F, K, V](r, f)
    }
  }

  /**
    * Prepares an individual action so that when evaluated
    * only one runs at the same time. Other wait on the
    * result of that computation
    */
  def prepare[F[_]: Concurrent, V](f: F[V]): F[F[V]] = {
    val state: F[Ref[F, Option[F[V]]]] =
      Ref[F].of(None)
    state.map{ r => 
      singleFibered[F, V](r, f)
    }
  }

  /* Useful for SyncIO/IO Context or Unsafe Instantiation */
  def inPrepareFunction[F[_]: Sync, G[_]: Async, K, V](f: K => G[V]): F[K => G[V]] = {
    val state: F[K => Ref[G, Option[G[V]]]] =
      MapRef.inShardedImmutableMap[F, G, K, G[V]](10)
      .map((m: MapRef[G, K, Option[G[V]]]) =>
        {(k: K) => m(k)}  
      )
    state.map{ r => 
      singleFiberedFunction[G, K, V](r, f)
    }
  }

  def inPrepare[F[_]: Sync, G[_]: Async, V](f: G[V]): F[G[V]] = {
    val state: F[Ref[G, Option[G[V]]]] =
      Ref.in[F, G, Option[G[V]]](None)
    state.map{ r => 
      singleFibered[G, V](r, f)
    }
  }

  /**
    * This is an unprepared function invocation. Allowing callers to dictate
    * which call they would like to run if they are given control of the 
    * execution fiber. Caution should be taken when leveraging this function.
    * As independent computations utilizing sufficiently broad types 
    */
  def unpreparedFunction[F[_]: Concurrent, K, V]: F[(K => F[V]) => (K => F[V])] = {
    val state: F[K => Ref[F, Option[F[V]]]] =
      MapRef.ofShardedImmutableMap[F, K, F[V]](10)
      .map((m: MapRef[F, K, Option[F[V]]]) =>
        {(k: K) => m(k)}  
      )
    state.map{ r => 
      (f: K => F[V]) => singleFiberedFunction[F, K, V](r, f)
    }
  }

  /* Useful for SyncIO/IO Context or Unsafe Instantiation */
  def inUnpreparedFunction[F[_]: Sync, G[_]: Async, K, V]: F[(K => G[V]) => (K => G[V])] = {
    val state: F[K => Ref[G, Option[G[V]]]] =
      MapRef.inShardedImmutableMap[F, G, K, G[V]](10)
      .map((m: MapRef[G, K, Option[G[V]]]) =>
        {(k: K) => m(k)}  
      )
    state.map{ r => 
      (f: K => G[V]) => singleFiberedFunction[G, K, V](r, f)
    }
  }

  /**
    * This is the core of the single-fibered abstraction
    * Given some way to identify a state for a Key,
    * we can then put ourselves into a conditional execution pattern
    * 
    * If no current computation is running we place a deferred in place
    * for following computations to wait on. And we execute our computation and
    * guarantee we reset the state and complete the deferred no matter what
    * the outcome was.
    * 
    * If a current computation is running then we wait on the result of that computation.
    */
  def singleFiberedFunction[F[_]: Concurrent, K, V](
    state: K => Ref[F, Option[F[V]]],
    f: K => F[V]
  ): K => F[V] = (k: K) => singleFibered(state(k), f(k))

  def singleFibered[F[_]: Concurrent, V](
    state: Ref[F, Option[F[V]]],
    f: F[V]
  ): F[V] =
    Concurrent[F].memoize(f <* state.set(Option.empty))
      .flatMap(memd =>
        state.modify {
          case s@Some(value) => s -> value
          case None => memd.some -> memd
        }
      )
      .flatten
}