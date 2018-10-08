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

object Example extends StreamApp[IO]{

  def stream(args: List[String], requestShutdown: IO[Unit]) : Stream[IO, StreamApp.ExitCode] = {
    val address = "0.0.0.0"
    val port = 8080
    val inetAddress = new InetSocketAddress(address, port)
    
    val appEC = ExecutionContext.global
    val acg = AsynchronousChannelGroup.withFixedThreadPool(100, Executors.defaultThreadFactory)
    
    // Defaults
    val maxConcurrency: Int = Int.MaxValue
    val receiveBufferSize: Int = 256 * 1024
    val maxHeaderSize: Int = 10 *1024
    val requestHeaderReceiveTimeout: Duration = 5.seconds

    for {
      sched <- Scheduler[IO](5)
      terminatedSignal <- Stream.eval(async.signalOf[IO, Boolean](false)(Effect[IO], appEC))
      exitCode <- _root_.io.chrisdavenport.ember.server[IO](
        maxConcurrency,
        receiveBufferSize,
        maxHeaderSize,
        requestHeaderReceiveTimeout,
        inetAddress,
        service[IO],
        Response[IO](Status.NotFound),
        _ => Stream(Response[IO](Status.InternalServerError)),
        (_,_, _) => Stream.empty,
        appEC,
        acg,
        terminatedSignal
      )
    } yield exitCode
  }

  def service[F[_]: Sync] : HttpService[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._
    
    HttpService[F]{
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
        Ok(body).withContentType(org.http4s.headers.`Content-Type`(org.http4s.MediaType.`text/plain`))
    }
  }
}