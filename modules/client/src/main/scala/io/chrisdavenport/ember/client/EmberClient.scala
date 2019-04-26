package io.chrisdavenport.ember.client

import cats._
import org.http4s.headers.Connection
import org.http4s.client._
import cats.effect._
import cats.implicits._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.SSLContext
import io.chrisdavenport.keypool._
import io.chrisdavenport.log4cats._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

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
  , timeout: Duration = 5.seconds
  , logger: Option[Logger[F]] = None): Resource[F, (KeyPool[F, RequestKey, (ClientHelpers.RequestKeySocket[F], F[Unit])], Client[F])] = {
    Resource.make(
      Sync[F].delay(
        AsynchronousChannelGroup.withFixedThreadPool(acgFixedThreadPoolSize,
          Executors.defaultThreadFactory
        )
      )
    )(acg => Sync[F].delay(acg.shutdown))
      .flatMap(acg => unopinionatedPool[F](sslExecutionContext, acg, maxTotal, maxPerKey, sslContext, chunkSize, maxResponseHeaderSize, timeout, logger))
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
    , logger: Option[Logger[F]] = None
  ): Resource[F, (KeyPool[F, RequestKey, (ClientHelpers.RequestKeySocket[F], F[Unit])], Client[F])] = {
    val logger_ : Logger[F] = logger.getOrElse(Slf4jLogger.getLogger[F])
    for {
      pool <- KeyPool.create[F, RequestKey, (ClientHelpers.RequestKeySocket[F], F[Unit])](
        {requestKey: RequestKey => ClientHelpers.requestKeyToSocketWithKey[F](
        requestKey,
        sslExecutionContext,
        sslContext,
        acg
        ).allocated},
        {case (r, (_, shutdown)) =>  logger_.trace(s"Shutting Down Connection - RequestKey: ${r}") >> shutdown},
        DontReuse,
        30000000000L,
        maxPerKey,
        maxTotal,
        {_ : Throwable => Applicative[F].unit}
      )
    // _ <- Resource.make(logger_.debug("Starting Reporter") >> Concurrent[F].start{
    //   def action : F[Unit] = pool.state.flatMap{ state => logger_.trace(s"Current Pool State - $state")} >> Timer[F].sleep(0.2.seconds) >> action
    //   action
    // })(_ => Sync[F].unit )
    } yield {
      val client = Client[F](request =>
        pool.take(RequestKey.fromRequest(request))
          .evalMap{ m =>
              pool.state.flatMap{poolState =>
                logger_.trace(
                  s"Connection Gotten - PoolState: $poolState - Key: ${m.resource._1.requestKey} - Reused: ${m.isReused}"
                )
              } >>
            // m.canBeReused.set(DontReuse) >>
            ClientHelpers.request[F](
              request,
              m.resource._1,
              chunkSize,
              maxResponseHeaderSize,
              timeout
            ).map(response =>
              response.copy(body =
                response.body.onFinalizeCase{
                  case ExitCase.Completed =>
                    val requestClose = request.isChunked || request.headers.get(Connection).exists(_.hasClose)
                    val responseClose = response.isChunked || response.headers.get(Connection).exists(_.hasClose)

                    if (requestClose || responseClose) Sync[F].unit
                    else m.canBeReused.set(Reuse)
                  case ExitCase.Canceled => Sync[F].unit
                  case ExitCase.Error(_) => Sync[F].unit
                }
              )
            )
          }
      )
      (pool, client)
    }
  }
}

