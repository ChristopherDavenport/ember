package io.chrisdavenport.ember.client

import cats._
import org.http4s.client._
import cats.effect._
import cats.implicits._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.SSLContext
import io.chrisdavenport.keypool._

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

  def pool[F[_]: ConcurrentEffect: Timer: ContextShift](
    sslExecutionContext: ExecutionContext
  , acgFixedThreadPoolSize: Int = 100
  , maxTotal: Int = 256
  , maxPerKey: Int = 256
  , sslContext : SSLContext = SSLContext.getDefault
  , chunkSize: Int = 32*1024
  , maxResponseHeaderSize: Int = 4096
  , timeout: Duration = 5.seconds): Resource[F, Client[F]] = {
    Resource.make(
      Sync[F].delay(
        AsynchronousChannelGroup.withFixedThreadPool(acgFixedThreadPoolSize, Executors.defaultThreadFactory)
      )
    )(acg => Sync[F].delay(acg.shutdown))
      .flatMap(acg => unopinionatedPool[F](sslExecutionContext, acg, maxTotal, maxPerKey, sslContext, chunkSize, maxResponseHeaderSize, timeout))
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

  def unopinionatedPool[F[_]: ConcurrentEffect: Timer: ContextShift](
    sslExecutionContext: ExecutionContext
    , acg: AsynchronousChannelGroup
    , maxTotal: Int = 256
    , maxPerKey: Int = 256
    , sslContext : SSLContext = SSLContext.getDefault
    , chunkSize: Int = 32*1024
    , maxResponseHeaderSize: Int = 4096
    , timeout: Duration = 5.seconds
  ): Resource[F, Client[F]] = for {
    pool <- KeyPool.create[F, RequestKey, (ClientHelpers.RequestKeySocket[F], F[Unit])](
      {requestKey: RequestKey => ClientHelpers.requestKeyToSocketWithKey[F](
      requestKey,
      sslExecutionContext,
      sslContext,
      acg
      ).allocated},
      {case (_, (_, shutdown)) => shutdown},
      Reuse,
      30000000000L,
      maxPerKey,
      maxTotal,
      {_ => Applicative[F].unit}
    )
  } yield Client[F](request => 
    pool.take(RequestKey.fromRequest(request))
      .evalMap( m => 
        ClientHelpers.request[F](
          request,
          m.resource._1,
          chunkSize,
          maxResponseHeaderSize,
          timeout
        ).onError{ case _ => 
          m.canBeReused.set(DontReuse)
        }
      )
  )
}

