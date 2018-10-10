package io.chrisdavenport.ember.example 

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import fs2._
import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.circe._
import _root_.io.circe._

object ClientExample extends IOApp{

  def run(args: List[String]) : IO[ExitCode] = {

    for {
      acg <- Stream.bracket(Sync[IO].delay(AsynchronousChannelGroup.withFixedThreadPool(100, Executors.defaultThreadFactory)))(acg => Sync[IO].delay(acg.shutdown))
      resp <- Stream.resource(_root_.io.chrisdavenport.ember.request[IO](
        Request[IO](Method.GET, Uri.unsafeFromString("https://www.google.com/")),
        acg,
        ExecutionContext.global
      ))
      _ <- Stream.eval(Sync[IO].delay(println(s"Response - $resp")))
      bodyText <- Stream.eval(resp.bodyAsText.compile.foldMonoid)
      _ <- Stream.eval(Sync[IO].delay(println(s"Response Body - $bodyText")))
    } yield ()
  }.compile.drain.as(ExitCode.Success)

}