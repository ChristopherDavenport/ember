package io.chrisdavenport.ember

import cats.effect._
import cats.implicits._
import fs2._
import fs2.io.tcp.Socket
import fs2.interop.scodec.ByteVectorChunk
import scodec.bits.ByteVector
import scala.concurrent.duration._
import scala.annotation.tailrec
import org.http4s.Request
import org.http4s.Method
import org.http4s.Response
import org.http4s._
import codec.Shared._

private[ember] object Server {
  private val logger = org.log4s.getLogger

  /**
   * The issue with a normal http body is that there is no termination character, 
   * thus unless you have content-length and the client still has their input side open, 
   * the server cannot know whether more data follows or not
   * This means this Stream MUST be infinite and additional parsing is required.
   * To know how much client input to consume
   * 
   * Function if timeout reads via socket read and then incrementally lowers
   * the remaining time after each read.
   * By setting the timeout signal outside this after the
   * headers have been read it triggers this function
   * to then not timeout on the remaining body.
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




  def respToBytes[F[_]: Sync]: Pipe[F, Response[F], Byte] = _.flatMap{resp: Response[F] =>
    val statusInstances = new StatusInstances{}
    import statusInstances._
    val headerStrings : List[String] = resp.headers.map(h => h.name + ": " + h.value).toList

    val initSection = Stream(show"${resp.httpVersion} ${resp.status}") ++ Stream.emits(headerStrings)

    initSection.covary[F].intersperse("\r\n").through(text.utf8Encode) ++ 
    Stream.chunk(ByteVectorChunk(`\r\n\r\n`)) ++
    resp.body
  }

  // val `\n` : ByteVector = ByteVector('\n')
  // val `\r` : ByteVector = ByteVector('\r')
  // val `\r\n`: ByteVector = ByteVector('\r','\n')
  // val `\r\n\r\n` = (`\r\n` ++ `\r\n`).compact

  //   /**
  //   * From the stream of bytes this extracts Http Header and body part.
  //   */
  // def httpHeaderAndBody[F[_]](maxHeaderSize: Int): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
  //   def go(buff: ByteVector, in: Stream[F, Byte]): Pull[F, (ByteVector, Stream[F, Byte]), Unit] = {
  //     in.pull.unconsChunk flatMap {
  //       case None =>
  //         Pull.raiseError(new Throwable(s"Incomplete Header received (sz = ${buff.size}): ${buff.decodeUtf8}"))
  //       case Some((chunk, tl)) =>
  //         val bv = chunk2ByteVector(chunk)
  //         val all = buff ++ bv
  //         val idx = all.indexOfSlice(`\r\n\r\n`)
  //         if (idx < 0) {
  //           if (all.size > maxHeaderSize) Pull.raiseError(new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
  //           else go(all, tl)
  //         }
  //         else {
  //           val (h, t) = all.splitAt(idx)
  //           if (h.size > maxHeaderSize)  Pull.raiseError(new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
  //           else  Pull.output1((h, Stream.chunk(ByteVectorChunk(t.drop(`\r\n\r\n`.size))) ++ tl))

  //         }
  //     }
  //   }

  //   src => go(ByteVector.empty, src).stream
  // }

  // def chunk2ByteVector(chunk: Chunk[Byte]):ByteVector = {
  //   chunk match  {
  //     case bv: ByteVectorChunk => bv.toByteVector
  //     case other =>
  //       val bs = other.toBytes
  //       ByteVector(bs.values, bs.offset, bs.size)
  //   }
  // }

  // def seperateHeadersAndBody[F[_]: Sync](
  //   maxHeaderSize: Int
  // ): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
  //   _ through httpHeaderAndBody(maxHeaderSize) flatMap { case (header, bodyRaw) =>
  //     Stream.emit(header -> bodyRaw) 
  //   }
  // }






  def httpServiceToPipe[F[_]: Sync](h: HttpService[F], onMissing: Response[F]): Pipe[F, Request[F], Response[F]] = _.evalMap{req => 
    h(req).value.map(_.fold(onMissing)(identity))
  }

}