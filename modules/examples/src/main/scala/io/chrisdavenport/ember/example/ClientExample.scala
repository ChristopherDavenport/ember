package io.chrisdavenport.ember.example 

import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.circe._

import _root_.io.circe.Json
import _root_.io.chrisdavenport.ember.client.EmberClient
import scala.concurrent.ExecutionContext.global
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

    EmberClient.pool[IO](global, maxPerKey = 256, maxTotal = 256, logger = logger.some)
      .map(_._2)
      .use( client =>
        // Not Https
        Stream(
          client.fetch(githubReq)(resp =>
            Sync[IO].delay(println(s"My Github - $resp"))
          )
        ).repeat.take(100).map(Stream.eval).parJoin(10).compile.drain >>
        Stream(
        client.fetch(googleReq)(resp =>
          Sync[IO].delay(println(s"Google - $resp")) >>
          resp.body.compile.drain
        )
        ).repeat.take(100).map(Stream.eval).parJoin(10).compile.drain >>
        Stream(client.expect[Json](randomReq).flatMap{ random =>
          Sync[IO].delay(println(s"Random - $random"))
        }).repeat.take(100).map(Stream.eval).parJoin(10).compile.drain >>

        IO(println("Done"))

      )
  }.as(ExitCode.Success)


  // def streamApp: Stream[IO, Nothing] = {
  //   for {
  //     a <- Stream.eval(Semaphore[IO](10))
  //     logger = Slf4jLogger.getLogger[IO]
  //     client <- Stream.resource(EmberClient.pool[IO](global, maxPerKey = 10, maxTotal = 10, logger = logger.some))
  //       .map(_._2)
  //     _ <- Stream.eval(
  //       List.fill(100)(()).parTraverse_(_ => 
  //           a.withPermit(client.expect[Json](randomReq).flatMap{ random => 
  //             logger.info(s"Random - $random")
  //           })
  //       )
  //     )
  //     _ <- Stream.eval(IO(println("Done")))
  //   } yield ()
  // }.drain

}