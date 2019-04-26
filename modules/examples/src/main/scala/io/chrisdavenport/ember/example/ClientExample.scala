package io.chrisdavenport.ember.example 

import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.circe._

import _root_.io.circe.Json
import _root_.io.chrisdavenport.ember.client.EmberClient
import scala.concurrent.ExecutionContext.global
import cats.effect.concurrent._
import fs2._

object ClientExample extends IOApp{

  val randomReq = Request[IO](Method.GET, Uri.unsafeFromString("https://icanhazdadjoke.com/"))

  def run(args: List[String]) : IO[ExitCode] = {

    streamApp.compile.drain
    // val githubReq = Request[IO](Method.GET, Uri.unsafeFromString("http://christopherdavenport.github.io/"))
    // val googleReq = Request[IO](Method.GET, Uri.unsafeFromString("https://www.google.com/"))
    
    
    
    
      // .use( client => 
      //   // Not Https
      //   // client.fetch(githubReq)(resp => 
      //   //   Sync[IO].delay(println(s"My Github - $resp"))
      //   // ) >> 
      //   // client.fetch(googleReq)(resp => 
      //   //   Sync[IO].delay(println(s"Google - $resp")) >>
      //   //   resp.body.compile.drain
      //   // ) >>
      //   // client.fetch(googleReq)(resp => 
      //   //   Sync[IO].delay(println(s"Google 2 - $resp"))
      //   // ) >>
      //   List.fill(100)(()).parTraverse_(_ => 
      //     client.expect[Json](randomReq).flatMap{ random => 
      //       Sync[IO].delay(println(s"Random - $random"))
      //     }
      //   ) >>

      //   IO(println("Done"))

      // )
  }.as(ExitCode.Success)

  def streamApp: Stream[IO, Nothing] = {
    for {
      sem <- Stream.eval(Semaphore[IO](10))
      client <- Stream.resource(EmberClient.pool[IO](global, maxPerKey = 10, maxTotal = 10))
      _ <- Stream.eval(
        List.fill(100)(()).parTraverse_(_ => 
            sem.withPermit(client.expect[Json](randomReq).flatMap{ random => 
              Sync[IO].delay(println(s"Random - $random"))
            })
        )
      )
    } yield ()
  }.drain

}