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

    // Pool Settings
    val maxPerKey = 100
    val maxTotal = 100
    val idleTimeInPool: Long = 30000000000L // 30 Seconds in Nanos
  }

  // Simple

  def defaultSimpleClient[F[_]: ConcurrentEffect: Timer: ContextShift]: Resource[F, Client[F]] = {
    for {
      sslContext <- Resource.liftF(Defaults.sslContext[F])
      acg <- Defaults.asynchronousChannelGroup[F]
    } yield simpleClient(
      Defaults.sslExecutionContext,
      acg,
      sslContext,
      Defaults.chunkSize,
      Defaults.maxResponseHeaderSize,
      Defaults.timeout
    )
  }

  def simpleClient[F[_]: ConcurrentEffect: Timer: ContextShift](
      sslExecutionContext: ExecutionContext
    , acg: AsynchronousChannelGroup
    , sslContext : SSLContext
    , chunkSize: Int
    , maxResponseHeaderSize: Int
    , timeout: Duration
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

  // Pooled

  def defaultPooledClient[F[_]: ConcurrentEffect: Timer: ContextShift]: Resource[F,  Client[F]] = {
    val logger_ = Slf4jLogger.getLogger[F]
    for {
      sslContext <- Resource.liftF(Defaults.sslContext[F])
      acg <- Defaults.asynchronousChannelGroup
      pool <- connectionPool(
        Defaults.sslExecutionContext,
        acg,
        sslContext,
        Defaults.maxTotal,
        Defaults.maxPerKey,
        Defaults.idleTimeInPool,
        logger_
      )
    } yield fromConnectionPool(
      pool,
      logger_,
      Defaults.chunkSize,
      Defaults.maxResponseHeaderSize,
      Defaults.timeout
    )
  }

  def connectionPool[F[_]: ConcurrentEffect: Timer: ContextShift](
    sslExecutionContext: ExecutionContext
    , acg: AsynchronousChannelGroup
    , sslContext : SSLContext
    , maxTotal: Int
    , maxPerKey: Int
    , idleTimeInPool: Long
    , logger: Logger[F]
  ): Resource[F, KeyPool[F, RequestKey, (ClientHelpers.RequestKeySocket[F], F[Unit])]] = {
    KeyPool.create[F, RequestKey, (ClientHelpers.RequestKeySocket[F], F[Unit])](
        {requestKey: RequestKey => ClientHelpers.requestKeyToSocketWithKey[F](
        requestKey,
        sslExecutionContext,
        sslContext,
        acg
        ).allocated <* logger.trace(s"Created Connection - RequestKey: ${requestKey}")},
        {case (r, (ClientHelpers.RequestKeySocket(socket, _), shutdown)) =>  
          logger.trace(s"Shutting Down Connection - RequestKey: ${r}") >> 
          socket.endOfInput.attempt.void >>
          socket.endOfOutput.attempt.void >>
          socket.close.attempt.void >>
          shutdown},
        DontReuse,
        idleTimeInPool,
        maxPerKey,
        maxTotal,
        {_ : Throwable => Applicative[F].unit}
      )
  }

  def fromConnectionPool[F[_]: ConcurrentEffect: Timer: ContextShift](
    pool: KeyPool[F, RequestKey, (ClientHelpers.RequestKeySocket[F], F[Unit])]
    , logger: Logger[F]
    , chunkSize: Int
    , maxResponseHeaderSize: Int
    , timeout: Duration
  ): Client[F] = {
      Client[F](request =>
        for {
          managed <- pool.take(RequestKey.fromRequest(request))
          _ <- Resource.liftF(pool.state.flatMap{poolState =>
              logger.trace(
                s"Connection Taken - Key: ${managed.resource._1.requestKey} - Reused: ${managed.isReused} - PoolState: $poolState"
              )
          })
          response <- Resource.make(ClientHelpers.request[F](
              request,
              managed.resource._1,
              chunkSize,
              maxResponseHeaderSize,
              timeout
          ).map(response => 
          response.copy(body =
                response.body.onFinalizeCase{
                  case ExitCase.Completed =>
                    val requestClose = request.headers.get(Connection).exists(_.hasClose)
                    val responseClose = response.isChunked || response.headers.get(Connection).exists(_.hasClose)

                    if (requestClose || responseClose) Sync[F].unit
                    else managed.canBeReused.set(Reuse)
                  case ExitCase.Canceled => Sync[F].unit
                  case ExitCase.Error(_) => Sync[F].unit
                }
              )
        
          ))(resp => resp.body.compile.drain.attempt.void)
        } yield response
      )
  }
}

