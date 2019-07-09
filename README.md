# ember [![Build Status](https://travis-ci.org/ChristopherDavenport/ember.svg?branch=master)](https://travis-ci.org/ChristopherDavenport/ember)

# Has Been Ported into [Http4s](https://github.com/http4s/http4s) as Ember Projects. Please direct issues there.

A functional fs2 backend for http.

A lot of work within was used from fs2-http.
This library attempts to direct its efforts at interop with http4s.

## Getting Started

First make sure you can get the current version.

```scala
libraryDependencies ++= Seq(
  "io.chrisdavenport" %% "ember-core" % "<version>",
  "io.chrisdavenport" %% "ember-server" % "<version>",
  "io.chrisdavenport" %% "ember-client" % "<version>"
)
```

Then plug in your http service into the server as demonstrated in the example and go wild.
This currently works for request response pairs that operate with a known Content-Length.

If you run into any difficulties please enable partial unification in your `build.sbt`

```scala
scalacOptions ++= Seq("-Ypartial-unification")
```

## Future Work

- [x] Chunked Encoding
- [ ] Websocket Support
- [x] Client Support
- [ ] Websocket Client
