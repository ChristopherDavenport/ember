package io.chrisdavenport.ember.example 

import fs2._
import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.implicits._
import org.http4s.dsl.Http4sDsl
import org.http4s.circe._
import _root_.io.circe._

import _root_.io.chrisdavenport.ember.server.EmberServerBuilder

object ServerExample extends IOApp{

  def run(args: List[String]) : IO[ExitCode] = {
    val host = "0.0.0.0"
    val port = 8080
    for {
      server <- Stream.resource(
        EmberServerBuilder.default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(service[IO])
          .build
      )
      _ <- Stream.eval(IO.delay(println(s"Server Has Started at ${server.address}")))
      _ <- Stream.never[IO] >> Stream.emit(())
    } yield ()
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
        Ok(body).map(_.withContentType(org.http4s.headers.`Content-Type`(org.http4s.MediaType.text.plain)))
    }.orNotFound
  }
}