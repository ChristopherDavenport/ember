package io.chrisdavenport.ember


// import cats._
import cats.effect._
import cats.implicits._
import fs2._
import fs2.StreamApp._
import fs2.io._
import fs2.io.tcp.Socket
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object Server extends StreamApp[IO] {

  override def stream(args: List[String], requestShutdown: IO[Unit]) : Stream[IO, ExitCode] = {
    val address = "0.0.0.0"
    val port = 8080
    implicit val appEC = ExecutionContext.global
    implicit val acg = AsynchronousChannelGroup.withFixedThreadPool(100, Executors.defaultThreadFactory)
    // val helloResponse = Stream("http/1.1 200\r\n\r\n<h1> Hello! </h1>").through(texxt.utf8Encode).chunk
    for {
      schedule <- Scheduler[IO](10)
      counter <- Stream.eval(async.refOf[IO, Int](0))
      timeoutSignal <- Stream.eval(async.signalOf[IO, Boolean](true))
      _ <- tcp.server[IO](new InetSocketAddress(address, port))
        .map(_.flatMap(socket => 
          readWithTimeout(socket, 1.second, timeoutSignal.get, 256 * 1024)
            // socket.reads(256 * 1024, 1.second.some)
            .through(text.utf8Decode)
            .through(text.lines)
            .evalMap(text => IO(println(s"Request: $text")))
            .take(11)
            .drain
            ++
              Stream.eval(IO(println("Finished Reading -")))
            ++
            Stream.eval(counter.modify(i => i+1).map(_.now))
            .map(i => s"http/1.1 200\r\nContent-Length: 35\r\nContent-Type: text\\html\r\n\r\n<html><h1> Hello #$i! </h1></html>")
              .through(text.lines)
              .evalMap(text => IO(println(s"Response: $text")).as(text))
              .intersperse("\r\n")
              .through(text.utf8Encode)
              .to(socket.writes())
              .onFinalize(socket.endOfOutput)
              .drain
        
        ).drain
        ).joinUnbounded.void
      _ <- Stream.eval(IO(println("Exiting App From Exit Code")))
      exitCode <- ExitCode.Success.pure[Stream[IO, ?]]
    } yield exitCode
    
    
  }

   /**
    * All credit to fs2-http
    * https://github.com/Spinoco/fs2-http/
    * MIT Licensed
    * Reads from supplied socket with timeout until `shallTimeout` yields to true.
    * @param socket         A socket to read from
    * @param timeout        A timeout
    * @param shallTimeout   If true, timeout will be applied, if false timeout won't be applied.
    * @param chunkSize      Size of chunk to read up to
    */
  def readWithTimeout[F[_]](
    socket: Socket[F]
    , timeout: FiniteDuration
    , shallTimeout: F[Boolean]
    , chunkSize: Int
  )(implicit F: Sync[F]) : Stream[F, Byte] = {
    def go(remains:FiniteDuration) : Stream[F, Byte] = {
      Stream.eval(shallTimeout).flatMap { shallTimeout =>
        if (!shallTimeout) socket.reads(chunkSize, None)
        else {
          if (remains <= 0.millis) Stream.raiseError(new Throwable("Timeout!"))
          else {
            Stream.eval(F.delay(System.currentTimeMillis())).flatMap { start =>
            Stream.eval(socket.read(chunkSize, Some(remains))).flatMap { read =>
            Stream.eval(F.delay(System.currentTimeMillis())).flatMap { end => read match {
              case Some(bytes) => Stream.chunk(bytes) ++ go(remains - (end - start).millis)
              case None => Stream.empty
            }}}}
          }
        }
      }
    }

    go(timeout)
  }

}