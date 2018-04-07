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
            socket.reads(256 * 1024, 1.second.some)
              .through(requestPipe)
              .evalMap(text => IO(println(s"Request: $text")).as(text))
              .take(1)
              .through(reqToResp)
              .take(1)
              .through(respToBytes)
              .to(socket.writes())
              .onFinalize(socket.endOfOutput)
              .drain
        )
        ).joinUnbounded.void
      _ <- Stream.eval(IO(println("Exiting App From Exit Code")))
      exitCode <- ExitCode.Success.pure[Stream[IO, ?]]
    } yield exitCode
    
    
  }

  import org.http4s.Request
  import org.http4s.Method
  import org.http4s.Response
  import org.http4s._
  def requestPipe: Pipe[IO, Byte, Request[IO]] = stream => Stream(Request[IO](Method.GET))


  def reqToResp: Pipe[IO, Request[IO], Response[IO]] = _.evalMap{req => 
    Response[IO](Status.Ok)
      .withBody("<html><h1>Hello From Http4s</h1></html>")
      .map(resp => resp.withContentType(headers.`Content-Type`(MediaType.`text/html`)))

  }


  def respToBytes: Pipe[IO, Response[IO], Byte] = _.flatMap{resp: Response[IO] =>
    val statusInstances = new StatusInstances{}
    import statusInstances._
    implicit val appEC = ExecutionContext.global
    // val httpV = resp.httpVersion
    // val status = resp.status
    val headerStrings : List[String] = resp.headers.map(h => h.name + ": " + h.value).toList

    
    val initSection = Stream(show"${resp.httpVersion} ${resp.status}") ++ Stream.emits(headerStrings)

    
    // Stream.eval(IO(println(resp))).drain ++
    // Stream.eval(IO(println(initSection.toList))).drain ++ 
    val respStream = initSection.covary[IO].intersperse("\r\n").through(text.utf8Encode) ++ 
    Stream("\r\n\r\n").covary[IO].through(text.utf8Encode) ++
    resp.body 

    val bufferedResp = respStream.compile.toVector

    respStream
    .observe(s => s
      .through(text.utf8Decode[IO])
      .through(text.lines)
      .evalMap{line => IO(println(show"Response- $line"))}
    )



  }

}