package io.chrisdavenport.ember.client


import io.chrisdavenport.keypool._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client._
import cats._
import cats.implicits._
import cats.effect._
import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.SSLContext
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.http4s.headers.Connection
import org.http4s.client._


final class EmberClientBuilder[F[_]: Concurrent: Timer: ContextShift] private (
    // private val sslExecutionContext: ExecutionContext
  private val sslContextOpt : Option[(ExecutionContext, SSLContext)]
  , private val acgR: Resource[F, AsynchronousChannelGroup]
  , val maxTotal: Int
  , val maxPerKey: Int
  , val idleTimeInPool: Long
  , private val logger: Logger[F]
  , val chunkSize: Int
  , val maxResponseHeaderSize: Int
  , val timeout: Duration
){ self =>

  private def copy(
    sslContextOpt: Option[(ExecutionContext, SSLContext)] = self.sslContextOpt
  , acgR: Resource[F, AsynchronousChannelGroup] = self.acgR
  , maxTotal: Int = self.maxTotal
  , maxPerKey: Int = self.maxPerKey
  , idleTimeInPool: Long = self.idleTimeInPool
  , logger: Logger[F] = self.logger
  , chunkSize: Int = self.chunkSize
  , maxResponseHeaderSize: Int = self.maxResponseHeaderSize
  , timeout: Duration= self.timeout
  ): EmberClientBuilder[F] = new EmberClientBuilder[F](
    sslContextOpt = sslContextOpt,
    acgR = acgR,
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

  def withAsynchronousChannelGroup(acg: AsynchronousChannelGroup) =
    copy(acgR = acg.pure[Resource[F, ?]])

  def withMaxTotal(maxTotal: Int) = copy(maxTotal = maxTotal)
  def withMaxPerKey(maxPerKey: Int) = copy(maxPerKey = maxPerKey)
  def withIdleTimeInPool(idleTimeInPool: Long) = copy(idleTimeInPool = idleTimeInPool)

  def withLogger(logger: Logger[F]) = copy(logger = logger)
  def withChunkSize(chunkSize: Int) = copy(chunkSize = chunkSize)
  def withMaxResponseHeaderSize(maxResponseHeaderSize: Int) =
    copy(maxResponseHeaderSize = maxResponseHeaderSize)
  def withTimeout(timeout: Duration) = copy(timeout = timeout)

  def build: Resource[F, Client[F]] = for {
    acg <- acgR
    pool <- KeyPool.create[F, RequestKey, (RequestKeySocket[F], F[Unit])](
        {requestKey: RequestKey => internal.ClientHelpers.requestKeyToSocketWithKey[F](
        requestKey,
        sslContextOpt,
        acg
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
        response <- Resource.make(internal.ClientHelpers.request[F](
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
    Defaults.asynchronousChannelGroup[F],
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

  private def tryDefaultSslContext: Option[SSLContext] =
    try Some(SSLContext.getDefault())
    catch {
      case scala.util.control.NonFatal(_) => None
    }


}