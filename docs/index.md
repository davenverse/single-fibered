# single-fibered - Single Fiber Convenience Methods [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/single-fibered_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/single-fibered_2.12)

## Quick Start

To use single-fibered in an existing SBT project with Scala 2.11 or a later version, add the following dependencies to your
`build.sbt` depending on your needs:

```scala
libraryDependencies ++= Seq(
  "io.chrisdavenport" %% "single-fibered" % "<version>"
)
```

## Cancelation

Callers of a single-fibered action share one running computation, but they do not
share a cancelation fate. The computation runs on its own fiber, so canceling any
individual caller detaches only that caller: the computation keeps running and the
remaining callers still receive its result.

The tradeoff is that the computation runs to completion even once every caller has
walked away. For a deduplicated read - a cache fill, a token refresh, a downstream
fetch - that is usually what you want, since the work would have happened anyway
and the next caller gets a warm result. If your action is expensive enough that you
would rather abandon it when nobody is waiting, guard it with a timeout inside the
action itself rather than relying on caller cancelation.

Prior to 0.3.1, cancelation was shared: canceling whichever caller happened to arrive
first aborted the computation and failed every other caller with a
`CancellationException`. Callers that never canceled could be failed by one that
did.