package io.chrisdavenport.ember.core

import fs2._
import scodec.bits.ByteVector
import cats.effect._
import cats.implicits._
import org.http4s._
import java.net.InetSocketAddress


object Shared {

  val `\n` : ByteVector = ByteVector('\n')
  val `\r` : ByteVector = ByteVector('\r')
  val `\r\n`: ByteVector = ByteVector('\r','\n')
  val `\r\n\r\n` = (`\r\n` ++ `\r\n`).compact

  def chunk2ByteVector(chunk: Chunk[Byte]):ByteVector = {
    chunk match  {
      case bv: Chunk.ByteVectorChunk => bv.toByteVector
      case other =>
        val bs = other.toBytes
        ByteVector(bs.values, bs.offset, bs.size)
    }
  }

  /** evaluates address from the host port and scheme, if this is a custom scheme we will default to port 8080**/
  def addressForRequest[F[_]: Sync](req: Request[F]): F[InetSocketAddress] = 
    for {
      scheme <- req.uri.scheme.toRight(EmberException.IncompleteClientRequest("Scheme")).liftTo[F]
      host <- req.uri.host.toRight(EmberException.IncompleteClientRequest("Host")).liftTo[F]
      socketAddress <- addressForComponents(scheme, host, req.uri.port)
    } yield socketAddress
  
  // https://github.com/http4s/http4s/blob/master/blaze-client/src/main/scala/org/http4s/client/blaze/Http1Support.scala#L86
  def addressForComponents[F[_] : Sync](scheme: Uri.Scheme, host: Uri.Host, port: Option[Int]): F[InetSocketAddress] = Sync[F].suspend {
    val finalPort = port.getOrElse {
      scheme match {
        case Uri.Scheme.https => 443
        case _ => 80
      }
    }
    Sync[F].delay(new InetSocketAddress(host.value, finalPort))
  }
}

