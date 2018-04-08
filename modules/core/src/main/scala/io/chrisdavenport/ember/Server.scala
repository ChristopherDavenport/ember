package io.chrisdavenport.ember


import cats._
import cats.effect._
import cats.implicits._
import fs2._
import fs2.StreamApp._
import fs2.io._
import fs2.io.tcp.Socket
import fs2.interop.scodec.ByteVectorChunk
import scodec.bits.{BitVector, ByteVector}
import scodec.bits.Bases.{Alphabets, Base64Alphabet}
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.annotation.tailrec
import org.http4s.Request
import org.http4s.Method
import org.http4s.Response
import org.http4s._
import org.http4s.circe._
import _root_.io.circe._

object Server extends StreamApp[IO] {
  private val logger = org.log4s.getLogger

  override def stream(args: List[String], requestShutdown: IO[Unit]) : Stream[IO, ExitCode] = {
    val address = "0.0.0.0"
    val port = 8080
    implicit val appEC = ExecutionContext.global
    implicit val acg = AsynchronousChannelGroup.withFixedThreadPool(100, Executors.defaultThreadFactory)

    val maxConcurrency: Int = Int.MaxValue
    val receiveBufferSize: Int = 256 * 1024
    val maxHeaderSize: Int = 10 *1024
    val requestHeaderReceiveTimeout: Duration = 5.seconds
    val (initial, readDuration) = requestHeaderReceiveTimeout match {
      case fin: FiniteDuration => (true, fin)
      case _ => (false, 0.millis)
    }
    // val helloResponse = Stream("http/1.1 200\r\n\r\n<h1> Hello! </h1>").through(texxt.utf8Encode).chunk
    for {
      schedule <- Scheduler[IO](10)
      counter <- Stream.eval(async.refOf[IO, Int](0))
      _ : Unit<- tcp.server[IO](new InetSocketAddress(address, port)).map(_.flatMap(socket =>
          Stream.eval(async.signalOf[IO, Boolean](initial)).flatMap{ timeoutSignal =>  
            readWithTimeout(socket, readDuration, timeoutSignal.get, receiveBufferSize)
              .through(requestPipe)
              .take(1)
              .flatMap{ req => 
                Stream.eval_(IO(println(s"Request Processed $req"))) ++
                Stream.eval_(timeoutSignal.set(false)) ++
                Stream(req).covary[IO].through(httpServiceToPipe[IO](service)).take(1)
                  .handleErrorWith(e => Stream(Response[IO](Status.InternalServerError)).take(1))
                  .map(resp => (req, resp))
              }
              .attempt
              .evalMap{ attempted => 
                def send(request:Option[Request[IO]], resp: Response[IO]): IO[Unit] = {
                  Stream(resp).covary[IO]
                  .through(respToBytes)
                  .through(socket.writes())
                  .onFinalize(socket.endOfOutput)
                  .compile
                  .drain
                  .attempt
                  .flatMap{
                    case Left(err) => Stream.empty.covary[IO].compile.drain
                    case Right(()) => IO.unit
                  }
                }
                attempted match {
                  case Right((request, response)) => send(Some(request), response)
                  case Left(err) => Stream(Response[IO](Status.InternalServerError)).evalMap { send(None, _) }.compile.drain
                }
              }.drain
          }
        )).join(maxConcurrency)
      _ <- Stream.eval(IO(println("Exiting App From Exit Code")))
      exitCode <- ExitCode.Success.pure[Stream[IO, ?]]
    } yield exitCode
    
    
  }

    def readWithTimeout[F[_]](
    socket: Socket[F]
    , timeout: FiniteDuration
    , shallTimeout: F[Boolean]
    , chunkSize: Int
  )(implicit F: Effect[F]) : Stream[F, Byte] = {
    def go(remains:FiniteDuration) : Stream[F, Byte] = {
      Stream.eval(shallTimeout).flatMap { shallTimeout =>
        if (!shallTimeout) socket.reads(chunkSize, None)
        else {
          if (remains <= 0.millis) Stream.raiseError(new Exception("Timeout!"))
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

  def requestPipe: Pipe[IO, Byte, Request[IO]] = stream => {
    for{
      (methodUriHttpVersionAndheaders, body) <- stream.through(seperateHeadersAndBody(4096))
    } yield headerBlobByteVectorToRequest(methodUriHttpVersionAndheaders, body)
  }

  def respToBytes(implicit ec: ExecutionContext): Pipe[IO, Response[IO], Byte] = _.flatMap{resp: Response[IO] =>
    val statusInstances = new StatusInstances{}
    import statusInstances._
    val headerStrings : List[String] = resp.headers.map(h => h.name + ": " + h.value).toList

    val initSection = Stream(show"${resp.httpVersion} ${resp.status}") ++ Stream.emits(headerStrings)

    initSection.covary[IO].intersperse("\r\n").through(text.utf8Encode) ++ 
    Stream("\r\n\r\n").covary[IO].through(text.utf8Encode) ++
    resp.body
  }

  val `\n` : ByteVector = ByteVector('\n')
  val `\r` : ByteVector = ByteVector('\r')
  val `\r\n`: ByteVector = ByteVector('\r','\n')
  val `\r\n\r\n` = (`\r\n` ++ `\r\n`).compact

    /**
    * From the stream of bytes this extracts Http Header and body part.
    */
  def httpHeaderAndBody[F[_]](maxHeaderSize: Int): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
    def go(buff: ByteVector, in: Stream[F, Byte]): Pull[F, (ByteVector, Stream[F, Byte]), Unit] = {
      in.pull.unconsChunk flatMap {
        case None =>
          Pull.raiseError(new Throwable(s"Incomplete Header received (sz = ${buff.size}): ${buff.decodeUtf8}"))
        case Some((chunk, tl)) =>
          val bv = chunk2ByteVector(chunk)
          val all = buff ++ bv
          val idx = all.indexOfSlice(`\r\n\r\n`)
          if (idx < 0) {
            if (all.size > maxHeaderSize) Pull.raiseError(new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
            else go(all, tl)
          }
          else {
            val (h, t) = all.splitAt(idx)
            if (h.size > maxHeaderSize)  Pull.raiseError(new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
            else  Pull.output1((h, Stream.chunk(ByteVectorChunk(t.drop(`\r\n\r\n`.size))) ++ tl))

          }
      }
    }

    src => go(ByteVector.empty, src).stream
  }

  def chunk2ByteVector(chunk: Chunk[Byte]):ByteVector = {
    chunk match  {
      case bv: ByteVectorChunk => bv.toByteVector
      case other =>
        val bs = other.toBytes
        ByteVector(bs.values, bs.offset, bs.size)
    }
  }

  def seperateHeadersAndBody[F[_]](
    maxHeaderSize: Int
  ): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
    _ through httpHeaderAndBody(maxHeaderSize) flatMap { case (header, bodyRaw) =>
      Stream.emit(header -> bodyRaw)
    }
  }

  def headerBlobByteVectorToRequest(b: ByteVector, s: Stream[IO, Byte]): Request[IO] = {
    val (methodHttpUri, headersBV) = splitHeader(b).fold(throw new Throwable("Invalid Empty Init Line"))(identity)
    val headers = generateHeaders(headersBV)(Headers.empty)
    val (method, uri, http) = bvToRequestTopLine(methodHttpUri)

    Request[IO](
      method = method,
      uri = uri,
      httpVersion = http,
      headers = headers,
      body = s
    )
  }

  def bvToRequestTopLine(b: ByteVector): (Method, Uri, HttpVersion) = {
    val (method, rest) = getMethodEmitRest(b)
    val (uri, httpVString) = getUriEmitHttpVersion(rest)
    val httpVersion = getHttpVersion(httpVString)
    (method, uri, httpVersion)
  }


  def getMethodEmitRest(b: ByteVector): (Method, String) = {
    val opt = for {
      line <- b.decodeAscii.right.toOption
      idx <- Some(line indexOf ' ')
      if (idx >= 0)
      out <- Method.fromString(line.substring(0, idx)).toOption
    } yield (out, line.substring(idx + 1))

    opt.fold(throw new Throwable("Missing Method"))(identity)
  }

  def getUriEmitHttpVersion(s: String): (Uri, String) = {
    val opt = for {
      idx <- Some(s indexOf ' ')
      if (idx >= 0)
      uri <- Uri.fromString(s.substring(0, idx)).toOption
    } yield (uri, s.substring(idx + 1))

    opt.fold(throw new Throwable("Missing URI"))(identity)
  }

  def getHttpVersion(s: String): HttpVersion = {
    logger.info("Unimplemented getHttpVersion")
    HttpVersion(1,2)
  }


  @tailrec
  def generateHeaders(byteVector: ByteVector)(acc: Headers) : Headers = {
    val headerO = splitHeader(byteVector)

    headerO match {
      case Some((lineBV, rest)) =>
        val headerO = for {
          line <- lineBV.decodeAscii.right.toOption
          idx <- Some(line indexOf ':')
          if idx >= 0
          header = Header(line.substring(0, idx), line.substring(idx + 1).trim)
        } yield header
        val newHeaders = acc ++ headerO
        logger.trace(s"Generate Headers Header0 = $headerO")
        generateHeaders(rest)(newHeaders)
      case None => acc
    }

  }

  def splitHeader(byteVector: ByteVector): Option[(ByteVector, ByteVector)] = {
    val index = byteVector.indexOfSlice(`\r\n`)
    if (index >= 0L) {
      val (line, rest) = byteVector.splitAt(index)
      logger.trace(s"Split Header Line: ${line.decodeAscii}")
      logger.trace(s"Split Header Rest: ${rest.decodeAscii}")
      Option((line, rest.drop(`\r\n`.length)))
    }
    else {
      Option.empty[(ByteVector, ByteVector)]
    }
  }

  def service[F[_]: Sync] : HttpService[F] = {
    val dsl = new org.http4s.dsl.Http4sDsl[F]{}
    import dsl._
    
    HttpService[F]{
      case req @ POST -> Root  => 
        for {
          _ <- Sync[F].delay(println("Post Beginning"))
          _ <- writeBody[F](req.body)
          // _ <- bodyStream.through(text.utf8Decode)
          // _ <- req.body.through(text.utf8Decode[IO](IO.ioConcurrentEffect))
            // // .through(text.utf8Decode[IO])
            // .through(text.lines[IO])
            // .evalMap(line => IO(println(s"Line: $line")))
            // .compile
            // .drain
          resp <- Ok("Finished Processing")
          _ <- Sync[F].delay(println("Response Created Correctly"))
        } yield resp
      case GET -> Root => 
        Ok(Json.obj("root" -> Json.fromString("GET")))
    }
  }

  def writeBody[F[_]: Sync](s: Stream[F, Byte]): F[Unit] = {
    s.through(text.utf8Decode[F]).through(text.lines[F])
    .evalMap(l => Sync[F].delay(println(s"Line: $l")))
    .compile
    .drain
  }

  def httpServiceToPipe[F[_]: Sync](h: HttpService[F]): Pipe[F, Request[F], Response[F]] = _.evalMap{req => 
    h(req).value.map(_.fold(Response[F](Status.NotFound))(identity))
      .handleErrorWith(t => Response[F](Status.InternalServerError).pure[F])
  }

}