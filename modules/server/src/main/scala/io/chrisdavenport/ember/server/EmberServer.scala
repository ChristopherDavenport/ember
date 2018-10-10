package io.chrisdavenport.ember.server

import cats.implicits._
import fs2.Stream
import org.http4s.server.Server
import cats.effect._
import fs2.concurrent._
import org.http4s._
import scala.concurrent.duration._
import java.util.concurrent.Executors
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import io.chrisdavenport.ember.core.server


abstract class EmberServer[F[_]] private () extends Server[F]{
  def shutdown: F[Unit]
}
object EmberServer {

  def impl[F[_]: ConcurrentEffect: Clock](
    host: String,
    port: Int,
    httpApp: HttpApp[F],
    onError: Throwable => Response[F] = {_: Throwable => Response[F](Status.InternalServerError)},
    onWriteFailure : Option[(Option[Request[F]], Response[F], Throwable) => F[Unit]] = None,
    maxConcurrency: Int = Int.MaxValue,
    receiveBufferSize: Int = 256 * 1024,
    maxHeaderSize: Int = 10 * 1024,
    requestHeaderReceiveTimeout: Duration = 5.seconds
  ): Stream[F, EmberServer[F]] = for {
    socket <- Stream.eval(Sync[F].delay(new InetSocketAddress(host, port)))
    acg <- Stream.resource(
      Resource.make(
        Sync[F].delay(
          AsynchronousChannelGroup.withFixedThreadPool(100, Executors.defaultThreadFactory)
        )
      )(acg => Sync[F].delay(acg.shutdown))
    )
    out <- unopinionated(
      socket,
      httpApp,
      acg,
      onError,
      onWriteFailure,
      maxConcurrency,
      receiveBufferSize,
      maxHeaderSize,
      requestHeaderReceiveTimeout
    )
  } yield out

  def unopinionated[F[_]: ConcurrentEffect: Clock](
    bindAddress: InetSocketAddress,
    httpApp: HttpApp[F],
    ag: AsynchronousChannelGroup,
    // Defaults
    onError: Throwable => Response[F] = {_: Throwable => Response[F](Status.InternalServerError)},
    onWriteFailure : Option[(Option[Request[F]], Response[F], Throwable) => F[Unit]] = None,
    maxConcurrency: Int = Int.MaxValue,
    receiveBufferSize: Int = 256 * 1024,
    maxHeaderSize: Int = 10 * 1024,
    requestHeaderReceiveTimeout: Duration = 5.seconds
  ): Stream[F, EmberServer[F]] = {
    for {
      shutdownSignal <- Stream.eval(SignallingRef[F, Boolean](false))
      out <- Stream.emit(
        new EmberServer[F](){
          def shutdown: F[Unit] = shutdownSignal.set(true)
  
          // Members declared in org.http4s.server.Server
          def address: java.net.InetSocketAddress = bindAddress
          def isSecure: Boolean = false
        }
      ).concurrently(
        server(
          bindAddress,
          httpApp,
          ag,
          onError,
          onWriteFailure,
          shutdownSignal.some,
          maxConcurrency,
          receiveBufferSize,
          maxHeaderSize,
          requestHeaderReceiveTimeout
        )
      )
    } yield out
  }
}

