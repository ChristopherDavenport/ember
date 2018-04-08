# ember [![Build Status](https://travis-ci.org/ChristopherDavenport/ember.svg?branch=master)](https://travis-ci.org/ChristopherDavenport/ember)

A functional fs2 backend for http.

A lot of work within was used from fs2-http.
This library attempts to direct its efforts at interop with http4s.


## Getting Started

First make sure you can get the current snapshot version

```scala
resolvers += Resolver.sonatypeRepo("snapshots")
libraryDependencies ++= Seq(
      "io.chrisdavenport"           %% "ember-core"                 % "0.0.1-SNAPSHOT"
)
```

Then plug in your http service into the server as demonstrated in the example and go wild.
This currently works for request response pairs that operate with a known Content-Length.

## Future Work

[ ] - Chunked Encoding
[ ] - Websocket Support
