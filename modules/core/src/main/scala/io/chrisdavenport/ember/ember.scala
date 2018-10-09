package io.chrisdavenport


import fs2._
import fs2.concurrent._
import fs2.io.tcp
import fs2.io.tcp._
import cats.effect._
import cats.implicits._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import org.http4s._
import _root_.io.chrisdavenport.ember.codec.{Encoder, Parser}
import _root_.io.chrisdavenport.ember.util.readWithTimeout

package object ember {

  private val logger = org.log4s.getLogger

  def server[F[_]: ConcurrentEffect: Clock](
    bindAddress: InetSocketAddress,
    httpApp: HttpApp[F],
    onError: Throwable => Response[F],
    onWriteFailure : (Option[Request[F]], Response[F], Throwable) => F[Unit],
    ag: AsynchronousChannelGroup,
    terminationSignal: Signal[F, Boolean],
    // Defaults
    maxConcurrency: Int = Int.MaxValue,
    receiveBufferSize: Int = 256 * 1024,
    maxHeaderSize: Int = 10 * 1024,
    requestHeaderReceiveTimeout: Duration = 5.seconds
  ): Stream[F, Nothing] = {
    implicit val AG = ag
    
    def socketReadRequest(
      socket: Socket[F], 
      requestHeaderReceiveTimeout: Duration, 
      receiveBufferSize: Int): F[Request[F]] = {
        val (initial, readDuration) = requestHeaderReceiveTimeout match {
          case fin: FiniteDuration => (true, fin)
          case _ => (false, 0.millis)
        }
        SignallingRef[F, Boolean](initial).flatMap{timeoutSignal =>
          readWithTimeout[F](socket, readDuration, timeoutSignal.get, receiveBufferSize)
              .through(Parser.Req.parser(maxHeaderSize))
              .take(1)
              .compile
              .lastOrError
              .flatMap{req => 
                Sync[F].delay(logger.debug(s"Request Processed $req")) *>
                timeoutSignal.set(false).as(req)
              }
        }
      }

    tcp.server[F](bindAddress)
      .map(connect => 
        Stream.eval(
          connect.use{socket =>
            val app: F[(Request[F], Response[F])] = for {
              req <- socketReadRequest(socket, requestHeaderReceiveTimeout, receiveBufferSize)
              resp <- httpApp.run(req)
                .handleError(onError)
                .flatTap(resp => Sync[F].delay(logger.debug(s"Response Created $resp")))
            } yield (req, resp)
            def send(request:Option[Request[F]], resp: Response[F]): F[Unit] = {
              Stream(resp)
                .covary[F]
                .flatMap(Encoder.respToBytes[F])
                .through(socket.writes())
                .onFinalize(socket.endOfOutput)
                .compile
                .drain
                .attempt
                .flatMap{
                  case Left(err) => onWriteFailure(request, resp, err)
                  case Right(()) => Sync[F].pure(())
                }
            }
            app.attempt.flatMap{
              case Right((request, response)) => send(Some(request), response)
              case Left(err) => send(None, onError(err))
            }
          }
        )
      ).parJoin(maxConcurrency)
        .interruptWhen(terminationSignal)
        .drain
  }


  def request[F[_]: ConcurrentEffect](
    request: Request[F]
    , acg: AsynchronousChannelGroup
    , chunkSize: Int = 32*1024
    , maxResponseHeaderSize: Int = 4096
    , timeout: Duration = 5.seconds
  )(implicit C: Clock[F]): Resource[F, Response[F]] = {
    implicit val ACG : AsynchronousChannelGroup = acg

    def onNoTimeout(socket: Socket[F]): F[Response[F]] = {
      Encoder.reqToBytes(request)
        .to(socket.writes(None))
        .last
        .onFinalize(socket.endOfOutput)
        .flatMap { _ => socket.reads(chunkSize, None)}
        .through(Parser.Resp.parser[F](maxResponseHeaderSize))
        .take(1)
        .compile
        .lastOrError
    }

    def onTimeout(socket: Socket[F], fin: FiniteDuration): F[Response[F]] = for {
      start <- C.realTime(MILLISECONDS)
      _ <- Encoder.reqToBytes(request)
        .to(socket.writes(Some(fin)))
        .last
        .onFinalize(socket.endOfOutput)
        .compile
        .drain
      timeoutSignal <- SignallingRef[F, Boolean](true)
      sent <- C.realTime(MILLISECONDS)
      remains = fin - (sent - start).millis
      resp <- readWithTimeout(socket, remains, timeoutSignal.get, chunkSize)
        .through (Parser.Resp.parser[F](maxResponseHeaderSize))
        .take(1)
        .compile
        .lastOrError
      _ <- timeoutSignal.set(false).void
    } yield resp

    for {
      socket <- Resource.liftF(codec.Shared.addressForRequest(request))
        .flatMap(io.tcp.client[F](_))
      req <- timeout match {
        case t: FiniteDuration => Resource.liftF(onTimeout(socket, t))
        case _ => Resource.liftF(onNoTimeout(socket))
      }
    } yield req

  }

}