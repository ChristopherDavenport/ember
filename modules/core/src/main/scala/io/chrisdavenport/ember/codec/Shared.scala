package io.chrisdavenport.ember.codec

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
      scheme <- req.uri.scheme.toRight(new Throwable("Missing Scheme")).liftTo[F]
      host <- req.uri.host.toRight(new Throwable("Missing Host")).liftTo[F]
      socketAddress <- addressForComponents(scheme, host, req.uri.port)
    } yield socketAddress
    
  def addressForComponents[F[_] : Sync](scheme: Uri.Scheme, host: Uri.Host, port: Option[Int]): F[InetSocketAddress] = Sync[F].delay {
    val finalPort = port.getOrElse {
      scheme match {
        case Uri.Scheme.https => 443
        case Uri.Scheme.http => 80
        case _ => 8080
      }
    }
    new InetSocketAddress(host.value, finalPort)
  }
}