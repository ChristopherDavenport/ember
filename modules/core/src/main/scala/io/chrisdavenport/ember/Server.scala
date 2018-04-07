package io.chrisdavenport.ember


// import cats._
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

object Server extends StreamApp[IO] {
  private val logger = org.log4s.getLogger

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
            socket.reads(256 * 1024, 10.seconds.some)
              .through(requestPipe(socket.endOfInput))
              .take(1)
              .evalMap(text => IO(println(s"Request: $text")).as(text))
              .through(reqToResp(counter))
              .take(1)
              .attempt
              .flatMap{
                case Left(e) => Stream.empty
                case Right(r) => 
                  Stream(r)
                    .covary[IO]
                    .through(respToBytes)
                    .to(socket.writes())
                    .onFinalize(socket.endOfOutput)
                    .drain
              }
              

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
  import org.http4s.circe._
  import _root_.io.circe._
  def requestPipe(endOfInput: IO[Unit]): Pipe[IO, Byte, Request[IO]] = stream => for{
    (methodUriHttpVersionAndheaders, body) <- stream.through(seperateHeadersAndBody(4096))
    req <- Stream(headerBlobByteVectorToRequest(methodUriHttpVersionAndheaders, body))
  } yield req

  def reqToResp(r: async.Ref[IO, Int]): Pipe[IO, Request[IO], Response[IO]] = _.evalMap{req =>
    for {
      value <- r.modify{_ + 1}.map(_.now)
      out <- Response[IO](Status.Ok)
      .withBody(Json.obj("visitor" -> Json.fromInt(value)))
    } yield out

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

    src => go(ByteVector.empty, src) stream
  }

  def chunk2ByteVector(chunk: Chunk[Byte]):ByteVector = {
    chunk match  {
      case bv: ByteVectorChunk => bv.toByteVector
      case other =>
        val bs = other.toBytes
        ByteVector(bs.values, bs.offset, bs.size)
    }
  }

    /**
    * Reads http header and body from the stream of bytes.
    *
    * If the body is encoded in chunked encoding this will decode it
    *
    * @param maxHeaderSize    Maximum size of the http header
    * @param headerCodec      header codec to use
    * @tparam F
    * @return
    */
  def seperateHeadersAndBody[F[_]](
    maxHeaderSize: Int
  ): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
    _ through httpHeaderAndBody(maxHeaderSize) flatMap { case (header, bodyRaw) =>
      Stream.emit(header -> bodyRaw)
    }
  }

  private val CRLFBytes = ByteVector('\r','\n')



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
    val index = byteVector.indexOfSlice(CRLFBytes)

    if (index >= 0L) {
      val (line, rest) = byteVector.splitAt(index)

      logger.trace(s"Split Header Line: ${line.decodeAscii}")
      logger.trace(s"Split Header Rest: ${rest.decodeAscii}")
      Option((line, rest.drop(CRLFBytes.length)))
    }
    else {
      Option.empty[(ByteVector, ByteVector)]
    }
  }

}