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

private[ember] object Server {
  private val logger = org.log4s.getLogger

  /**
   * The issue with a normal http body is that there is no termination character, 
   * thus unless you have content-length and the client still has their input side open, 
   * the server cannot know whether more data follows or not
   * This means this Stream MUST be infinite and additional parsing is required.
   * To know how much client input to consume
   */
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
              case Some(bytes) => 
                Stream.chunk(bytes) ++ go(remains - (end - start).millis)
              case None => 
                Stream.empty
            }}}}
          }
        }
      }
    }

    go(timeout)
  }


  def requestPipe[F[_]: Sync]: Pipe[F, Byte, Request[F]] = stream => {
    for{
      (methodUriHttpVersionAndheaders, body) <- stream.through(seperateHeadersAndBody[F](4096))
    } yield headerBlobByteVectorToRequest[F](methodUriHttpVersionAndheaders, body)
  }


  def respToBytes[F[_]: Sync](implicit ec: ExecutionContext): Pipe[F, Response[F], Byte] = _.flatMap{resp: Response[F] =>
    val statusInstances = new StatusInstances{}
    import statusInstances._
    val headerStrings : List[String] = resp.headers.map(h => h.name + ": " + h.value).toList

    val initSection = Stream(show"${resp.httpVersion} ${resp.status}") ++ Stream.emits(headerStrings)

    initSection.covary[F].intersperse("\r\n").through(text.utf8Encode) ++ 
    Stream("\r\n\r\n").covary[F].through(text.utf8Encode) ++
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

  def seperateHeadersAndBody[F[_]: Sync](
    maxHeaderSize: Int
  ): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
    _ through httpHeaderAndBody(maxHeaderSize) flatMap { case (header, bodyRaw) =>
      Stream.emit(header -> bodyRaw) 
    }
  }

  def headerBlobByteVectorToRequest[F[_]: Sync](b: ByteVector, s: Stream[F, Byte]): Request[F] = {
    val (methodHttpUri, headersBV) = splitHeader(b).fold(throw new Throwable("Invalid Empty Init Line"))(identity)
    val headers = generateHeaders(headersBV)(Headers.empty)
    val (method, uri, http) = bvToRequestTopLine(methodHttpUri)
    
    val contentLength = headers.get(org.http4s.headers.`Content-Length`).map(_.length).getOrElse(0L)

    Request[F](
      method = method,
      uri = uri,
      httpVersion = http,
      headers = headers,
      body = s.take(contentLength)
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

  def httpServiceToPipe[F[_]: Sync](h: HttpService[F], onMissing: Response[F]): Pipe[F, Request[F], Response[F]] = _.evalMap{req => 
    h(req).value.map(_.fold(onMissing)(identity))
  }

}