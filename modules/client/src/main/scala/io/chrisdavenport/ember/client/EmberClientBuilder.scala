package io.chrisdavenport.ember.client


import io.chrisdavenport.keypool._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client._
import cats._
import cats.implicits._
import cats.effect._
import javax.net.ssl.SSLContext
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.http4s.headers.Connection
import org.http4s.client._
import fs2.io.tcp.SocketGroup
import scala.concurrent.duration.Duration

final class EmberClientBuilder[F[_]: Concurrent: Timer: ContextShift] private (
  private val sslContextOpt : Option[(ExecutionContext, SSLContext)]
  , private val sgR: Resource[F, SocketGroup]
  , val maxTotal: Int
  , val maxPerKey: RequestKey => Int
  , val idleTimeInPool: Duration
  , private val logger: Logger[F]
  , val chunkSize: Int
  , val maxResponseHeaderSize: Int
  , val timeout: Duration
){ self =>

  private def copy(
    sslContextOpt: Option[(ExecutionContext, SSLContext)] = self.sslContextOpt
  , sgR: Resource[F, SocketGroup] = self.sgR
  , maxTotal: Int = self.maxTotal
  , maxPerKey: RequestKey => Int = self.maxPerKey
  , idleTimeInPool: Duration = self.idleTimeInPool
  , logger: Logger[F] = self.logger
  , chunkSize: Int = self.chunkSize
  , maxResponseHeaderSize: Int = self.maxResponseHeaderSize
  , timeout: Duration= self.timeout
  ): EmberClientBuilder[F] = new EmberClientBuilder[F](
    sslContextOpt = sslContextOpt,
    sgR = sgR,
    maxTotal = maxTotal,
    maxPerKey = maxPerKey,
    idleTimeInPool = idleTimeInPool,
    logger = logger,
    chunkSize = chunkSize,
    maxResponseHeaderSize = maxResponseHeaderSize,
    timeout = timeout
  )

  def withSSlContext(sslExecutionContext: ExecutionContext, sslContext: SSLContext) =
    copy(sslContextOpt = (sslExecutionContext, sslContext).some)
  def withoutSSLContext = copy(sslContextOpt = Option.empty[(ExecutionContext, SSLContext)])

  def withSocketGroup(sg: SocketGroup) = copy(sgR = sg.pure[Resource[F, ?]])

  def withMaxTotal(maxTotal: Int) = copy(maxTotal = maxTotal)
  def withMaxPerKey(maxPerKey: RequestKey => Int) = copy(maxPerKey = maxPerKey)
  def withIdleTimeInPool(idleTimeInPool: Duration) = copy(idleTimeInPool = idleTimeInPool)

  def withLogger(logger: Logger[F]) = copy(logger = logger)
  def withChunkSize(chunkSize: Int) = copy(chunkSize = chunkSize)
  def withMaxResponseHeaderSize(maxResponseHeaderSize: Int) =
    copy(maxResponseHeaderSize = maxResponseHeaderSize)
  def withTimeout(timeout: Duration) = copy(timeout = timeout)

  def build: Resource[F, Client[F]] = for {
    sg <- sgR
    pool <- KeyPool.create[F, RequestKey, (RequestKeySocket[F], F[Unit])](
        {requestKey: RequestKey => 
          io.chrisdavenport.ember.client.internal.ClientHelpers.requestKeyToSocketWithKey[F](
            requestKey,
            sslContextOpt,
            sg
          ).allocated <* logger.trace(s"Created Connection - RequestKey: ${requestKey}")},
        {case (r, (RequestKeySocket(socket, _), shutdown)) =>
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
  } yield {
    val client = Client[F](request =>
      for {
        managed <- pool.take(RequestKey.fromRequest(request))
        _ <- Resource.liftF(pool.state.flatMap{poolState =>
            logger.trace(
              s"Connection Taken - Key: ${managed.resource._1.requestKey} - Reused: ${managed.isReused} - PoolState: $poolState"
            )
        })
        response <- Resource.make(io.chrisdavenport.ember.client.internal.ClientHelpers.request[F](
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

        ))(resp => managed.canBeReused.get.flatMap{
          case Reuse => resp.body.compile.drain.attempt.void
          case DontReuse => Sync[F].unit
        })
      } yield response
    )
    new EmberClient[F](client, pool)
  }
    
}

object EmberClientBuilder {

  def default[F[_]: Concurrent: Timer: ContextShift] = new EmberClientBuilder[F](
    tryDefaultSslContext.map((ExecutionContext.global, _)),
    Defaults.socketGroup[F],
    Defaults.maxTotal,
    Defaults.maxPerKey,
    Defaults.idleTimeInPool,
    Slf4jLogger.getLogger[F],
    Defaults.chunkSize,
    Defaults.maxResponseHeaderSize,
    Defaults.timeout
  )

  private object Defaults {
    def sslExecutionContext: ExecutionContext = ExecutionContext.global
    val acgFixedThreadPoolSize: Int = 100
    def socketGroup[F[_]: Sync: ContextShift]: Resource[F, SocketGroup] = 
      Blocker[F].flatMap(b => SocketGroup[F](b))

    def sslContext[F[_]: Sync]: F[SSLContext] = Sync[F].delay(SSLContext.getDefault)
    val chunkSize: Int = 32 * 1024
    val maxResponseHeaderSize: Int = 4096
    val timeout: Duration = 60.seconds

    // Pool Settings
    val maxPerKey = {_: RequestKey => 100}
    val maxTotal = 100
    val idleTimeInPool = 30.seconds // 30 Seconds in Nanos
  }

  private def tryDefaultSslContext: Option[SSLContext] =
    try Some(SSLContext.getDefault())
    catch {
      case scala.util.control.NonFatal(_) => None
    }


}