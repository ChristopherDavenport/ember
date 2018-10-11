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

import io.chrisdavenport.ember.core.request

object EmberClient {

  def simple[F[_]: ConcurrentEffect: Timer: ContextShift](
    sslExecutionContext: ExecutionContext
  , sslContext : SSLContext = SSLContext.getDefault
  , chunkSize: Int = 32*1024
  , maxResponseHeaderSize: Int = 4096
  , timeout: Duration = 5.seconds): Resource[F, Client[F]] = {
    Resource.make(
      Sync[F].delay(
        AsynchronousChannelGroup.withFixedThreadPool(100, Executors.defaultThreadFactory)
      )
    )(acg => Sync[F].delay(acg.shutdown))
      .map(acg => unopiniated(sslExecutionContext, acg, sslContext, chunkSize, maxResponseHeaderSize, timeout))
  }

  def unopiniated[F[_]: ConcurrentEffect: Timer: ContextShift](
      sslExecutionContext: ExecutionContext
    , acg: AsynchronousChannelGroup
    , sslContext : SSLContext = SSLContext.getDefault
    , chunkSize: Int = 32*1024
    , maxResponseHeaderSize: Int = 4096
    , timeout: Duration = 5.seconds
  ): Client[F] = Client[F](req => 
    request(
      req,
      sslExecutionContext,
      acg,
      sslContext,
      chunkSize,
      maxResponseHeaderSize,
      timeout
    )
  )
}

