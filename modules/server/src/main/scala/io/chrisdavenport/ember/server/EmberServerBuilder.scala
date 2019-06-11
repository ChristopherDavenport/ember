package io.chrisdavenport.ember.server

import cats._
import cats.implicits._
import cats.effect._
import fs2.concurrent._
import org.http4s._
import org.http4s.server.Server
import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup


final class EmberServerBuilder[F[_]: Concurrent: Timer: ContextShift] private (
  val host: String,
  val port: Int,
  private val httpApp: HttpApp[F],
  private val agR: Resource[F, AsynchronousChannelGroup],
  private val onError: Throwable => Response[F],
  private val onWriteFailure : (Option[Request[F]], Response[F], Throwable) => F[Unit],
  val maxConcurrency: Int,
  val receiveBufferSize: Int,
  val maxHeaderSize: Int,
  val requestHeaderReceiveTimeout: Duration
){ self => 

  private def copy(
    host: String = self.host,
    port: Int = self.port,
    httpApp: HttpApp[F] = self.httpApp,
    agR: Resource[F, AsynchronousChannelGroup] = self.agR,
    onError: Throwable => Response[F] = self.onError,
    onWriteFailure : (Option[Request[F]], Response[F], Throwable) => F[Unit] = self.onWriteFailure,
    maxConcurrency: Int = self.maxConcurrency,
    receiveBufferSize: Int = self.receiveBufferSize,
    maxHeaderSize: Int = self.maxHeaderSize,
    requestHeaderReceiveTimeout: Duration = self.requestHeaderReceiveTimeout
  ): EmberServerBuilder[F] = new EmberServerBuilder[F](
    host = host,
    port = port,
    httpApp = httpApp,
    agR = agR,
    onError = onError,
    onWriteFailure = onWriteFailure,
    maxConcurrency = maxConcurrency,
    receiveBufferSize = receiveBufferSize,
    maxHeaderSize = maxHeaderSize,
    requestHeaderReceiveTimeout = requestHeaderReceiveTimeout
  )

  def withHost(host: String) = copy(host = host)
  def withPort(port: Int) = copy(port = port)
  def withHttpApp(httpApp: HttpApp[F]) = copy(httpApp = httpApp)
  def withAsynchronousChannelGroup(acg: AsynchronousChannelGroup) =
    copy(agR = acg.pure[Resource[F, ?]])
  def withOnError(onError: Throwable => Response[F]) = copy(onError = onError)
  def withOnWriteFailure(onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit]) =
    copy(onWriteFailure = onWriteFailure)
  def withMaxConcurrency(maxConcurrency: Int) = copy(maxConcurrency = maxConcurrency)
  def withReceiveBufferSize(receiveBufferSize: Int)  = copy(receiveBufferSize = receiveBufferSize)
  def withMaxHeaderSize(maxHeaderSize: Int) = copy(maxHeaderSize = maxHeaderSize)
  def withRequestHeaderReceiveTimeout(requestHeaderReceiveTimeout: Duration) = 
    copy(requestHeaderReceiveTimeout = requestHeaderReceiveTimeout)

  def build: Resource[F, Server[F]] = for {
    bindAddress <- Resource.liftF(Sync[F].delay(new InetSocketAddress(host, port)))
    acg <- agR
    shutdownSignal <- Resource.liftF(SignallingRef[F, Boolean](false))
    out <- Resource.make(
        Concurrent[F].start(
          internal.ServerHelpers.server(
            bindAddress,
            httpApp,
            acg,
            onError,
            onWriteFailure,
            shutdownSignal.some,
            maxConcurrency,
            receiveBufferSize,
            maxHeaderSize,
            requestHeaderReceiveTimeout
          ).compile
          .drain
        ).as(
          new Server[F]{
            def address: InetSocketAddress = bindAddress
            def isSecure: Boolean = false
          }
        )
      )(_ => shutdownSignal.set(true))
  } yield out
}

object EmberServerBuilder {
  

  def default[F[_]: Concurrent: Timer: ContextShift]: EmberServerBuilder[F] = new EmberServerBuilder[F](
    host = Defaults.host,
    port = Defaults.port,
    httpApp = Defaults.httpApp[F],
    agR = Defaults.agR[F],
    onError = Defaults.onError[F],
    onWriteFailure = Defaults.onWriteFailure[F],
    maxConcurrency = Defaults.maxConcurrency,
    receiveBufferSize = Defaults.receiveBufferSize,
    maxHeaderSize = Defaults.maxHeaderSize,
    requestHeaderReceiveTimeout = Defaults.requestHeaderReceiveTimeout
  )

  private object Defaults {
    val host: String = "127.0.0.1"
    val port: Int = 8000

    def httpApp[F[_]: Applicative]: HttpApp[F] = HttpApp.notFound[F]
    def agR[F[_]: Sync]: Resource[F, AsynchronousChannelGroup] = Resource.make(
          Sync[F].delay(
            AsynchronousChannelGroup.withFixedThreadPool(100, Executors.defaultThreadFactory)
          )
        )(acg => Sync[F].delay(acg.shutdown))
    def onError[F[_]]: Throwable => Response[F] = {_: Throwable => Response[F](Status.InternalServerError)}
    def onWriteFailure[F[_]: Applicative] : (Option[Request[F]], Response[F], Throwable) => F[Unit] = 
      { case _: (Option[Request[F]], Response[F], Throwable) => Applicative[F].unit }
    val maxConcurrency: Int = Int.MaxValue
    val receiveBufferSize: Int = 256 * 1024
    val maxHeaderSize: Int = 10 * 1024
    val requestHeaderReceiveTimeout: Duration = 5.seconds
  }
}