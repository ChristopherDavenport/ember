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

object Example extends IOApp{

  def run(args: List[String]) : IO[ExitCode] = {
    val address = "0.0.0.0"
    val port = 8080
    val inetAddress = new InetSocketAddress(address, port)
    val acg = AsynchronousChannelGroup.withFixedThreadPool(100, Executors.defaultThreadFactory)

    for {
      terminatedSignal <- Stream.eval(fs2.concurrent.SignallingRef[IO, Boolean](false))
      exitCode <- _root_.io.chrisdavenport.ember.server[IO](
        inetAddress,
        service[IO],
        _ => Stream(Response[IO](Status.InternalServerError)),
        (_,_, _) => Stream.empty,
        acg,
        terminatedSignal
      )
    } yield exitCode
  }.compile.drain.as(ExitCode.Success)

  def service[F[_]: Sync] : HttpApp[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._
    
    HttpRoutes.of[F]{
      case req @ POST -> Root  => 
        for {
          json <- req.decodeJson[Json]
          resp <- Ok(json)
        } yield resp
      case GET -> Root => 
        Ok(Json.obj("root" -> Json.fromString("GET")))
      case GET -> Root / "hello" / name => 
        Ok(show"Hi $name!")
      case GET -> Root / "chunked" =>
        val body = Stream("This IS A CHUNK\n")
          .covary[F]
          .repeat
          .take(100)
          .through(fs2.text.utf8Encode[F])
        Ok(body).withContentType(org.http4s.headers.`Content-Type`(org.http4s.MediaType.text.plain))
    }.orNotFound
  }
}