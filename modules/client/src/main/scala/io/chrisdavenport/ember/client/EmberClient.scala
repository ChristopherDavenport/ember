package io.chrisdavenport.ember.client

import cats.implicits._
import org.http4s.client.Client
import cats.effect._
import org.http4s._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.SSLContext

import _root_.io.chrisdavenport.ember.client.internal.ClientHelpers

object EmberClient {

  def simple[F[_]: ConcurrentEffect: Timer: ContextShift](
    sslExecutionContext: ExecutionContext
  , acgFixedThreadPoolSize: Int = 100
  , sslContext : SSLContext = SSLContext.getDefault
  , chunkSize: Int = 32*1024
  , maxResponseHeaderSize: Int = 4096
  , timeout: Duration = 5.seconds): Resource[F, Client[F]] = {
    Resource.make(
      Sync[F].delay(
        AsynchronousChannelGroup.withFixedThreadPool(acgFixedThreadPoolSize, Executors.defaultThreadFactory)
      )
    )(acg => Sync[F].delay(acg.shutdown))
      .map(acg => unopiniatedSimple(sslExecutionContext, acg, sslContext, chunkSize, maxResponseHeaderSize, timeout))
  }

  def unopiniatedSimple[F[_]: ConcurrentEffect: Timer: ContextShift](
      sslExecutionContext: ExecutionContext
    , acg: AsynchronousChannelGroup
    , sslContext : SSLContext = SSLContext.getDefault
    , chunkSize: Int = 32*1024
    , maxResponseHeaderSize: Int = 4096
    , timeout: Duration = 5.seconds
  ): Client[F] = Client[F](req => 
    ClientHelpers.requestToSocketWithKey[F](
      req,
      sslExecutionContext,
      sslContext,
      acg
    ).flatMap(s =>
      Resource.liftF(
        s.lock.withPermit(
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
  )
}

