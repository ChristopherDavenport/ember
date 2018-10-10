package io.chrisdavenport.ember.example 

import scala.concurrent.duration._
import fs2._
import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.circe._

import _root_.io.circe.Json
import _root_.io.chrisdavenport.ember.client.EmberClient
import scala.concurrent.ExecutionContext.global
import javax.net.ssl.SSLContext

object ClientExample extends IOApp{

  def run(args: List[String]) : IO[ExitCode] = {
    val githubReq = Request[IO](Method.GET, Uri.unsafeFromString("http://christopherdavenport.github.io/"))
    val googleReq = Request[IO](Method.GET, Uri.unsafeFromString("https://www.google.com/"))
    val randomReq = Request[IO](Method.GET, Uri.unsafeFromString("https://reqres.in/api/users?page=2"))
    EmberClient.simple[IO](global)
      .use( client => 
        // Not Https
        client.fetch(githubReq)(resp => 
          Sync[IO].delay(println(s"My Github - $resp"))
        ) *> 
        client.fetch(googleReq)(resp => 
          Sync[IO].delay(println(s"Google - $resp"))
        ) *>
        client.expect[Json](randomReq).flatMap{ random => 
          Sync[IO].delay(println(s"Random - $random"))
        }

      )
  }.as(ExitCode.Success)

}