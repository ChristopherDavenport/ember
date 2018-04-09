package io.chrisdavenport.ember

import cats._
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

package object util {
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


}