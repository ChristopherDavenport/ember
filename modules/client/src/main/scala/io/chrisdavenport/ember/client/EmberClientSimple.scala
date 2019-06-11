package io.chrisdavenport.ember.client

import org.http4s.client._
import cats.effect._
import cats.implicits._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.SSLContext

import _root_.io.chrisdavenport.ember.client.internal.ClientHelpers

private[client] object EmberClientSimple {

  object Defaults {
    def sslExecutionContext: ExecutionContext = ExecutionContext.global
    val acgFixedThreadPoolSize: Int = 100
    def asynchronousChannelGroup[F[_]: Sync]: Resource[F, AsynchronousChannelGroup] = Resource.make(
      Sync[F].delay(
        AsynchronousChannelGroup.withFixedThreadPool(acgFixedThreadPoolSize, Executors.defaultThreadFactory)
      )
    )(acg => Sync[F].delay(acg.shutdown))
    def sslContext[F[_]: Sync]: F[SSLContext] = Sync[F].delay(SSLContext.getDefault)
    val chunkSize: Int = 32 * 1024
    val maxResponseHeaderSize: Int = 4096
    val timeout: Duration = 60.seconds
  }
  // Simple

  def defaultSimpleClient[F[_]: Concurrent: Timer: ContextShift]: Resource[F, Client[F]] = {
    for {
      sslContext <- Resource.liftF(Defaults.sslContext[F])
      acg <- Defaults.asynchronousChannelGroup[F]
    } yield simpleClient(
      (Defaults.sslExecutionContext, sslContext).some,
      acg,
      Defaults.chunkSize,
      Defaults.maxResponseHeaderSize,
      Defaults.timeout
    )
  }

  def simpleClient[F[_]: Concurrent: Timer: ContextShift](
      sslContext: Option[(ExecutionContext, SSLContext)]
    , acg: AsynchronousChannelGroup
    , chunkSize: Int
    , maxResponseHeaderSize: Int
    , timeout: Duration
  ): Client[F] = Client[F](req =>
    ClientHelpers.requestToSocketWithKey[F](
      req,
      sslContext,
      acg
    ).flatMap(s =>
      Resource.liftF(
        ClientHelpers.request[F](
          req,
          s,
          chunkSize,
          maxResponseHeaderSize,
          timeout
        )
      )
    )
  )
}

