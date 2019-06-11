package io.chrisdavenport.ember.example 

import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.circe._

import _root_.io.circe.Json
import _root_.io.chrisdavenport.ember.client.EmberClientBuilder
// import cats.effect.concurrent._
import fs2._
import _root_.io.chrisdavenport.log4cats.slf4j.Slf4jLogger

object ClientExample extends IOApp{

  val randomReq = Request[IO](Method.GET, Uri.unsafeFromString("https://icanhazdadjoke.com/"))
  val githubReq = Request[IO](Method.GET, Uri.unsafeFromString("http://christopherdavenport.github.io/"))
  val googleReq = Request[IO](Method.GET, Uri.unsafeFromString("https://www.google.com/"))

  val logger = Slf4jLogger.getLogger[IO]

  def run(args: List[String]) : IO[ExitCode] = {

    // streamApp.compile.drain

    EmberClientBuilder.default[IO]
      .build
      .use( client =>
        Stream(  
        // Not Https
        Stream(
          client.fetch(githubReq)(resp =>
            logger.info(s"My Github - $resp")
          )
        ).repeat.take(100).map(Stream.eval).parJoin(10).drain,
        Stream(
        client.fetch(googleReq)(resp =>
          logger.info(s"Google - $resp") //>>
          // resp.body.compile.drain
        )
        ).repeat.take(100).map(Stream.eval).parJoin(10).drain,
        Stream(client.expect[Json](randomReq).flatMap{ random =>
          logger.info(s"Random - $random")
        }).repeat.take(100).map(Stream.eval).parJoin(10).drain
        ).parJoinUnbounded.compile.drain >>

        IO(println("Done"))
      )

  }.as(ExitCode.Success)

}