package io.chrisdavenport


import fs2._
import fs2.concurrent._
import fs2.io.tcp
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

  def server[F[_]: ConcurrentEffect](
    bindAddress: InetSocketAddress,
    httpApp: HttpApp[F],
    onError: Throwable => Stream[F, Response[F]],
    onWriteFailure : (Option[Request[F]], Response[F], Throwable) => Stream[F, Nothing],
    ag: AsynchronousChannelGroup,
    terminationSignal: fs2.concurrent.Signal[F, Boolean],
    // Defaults
    maxConcurrency: Int = Int.MaxValue,
    receiveBufferSize: Int = 256 * 1024,
    maxHeaderSize: Int = 10 * 1024,
    requestHeaderReceiveTimeout: Duration = 5.seconds
  ): Stream[F, Nothing] = {
    implicit val AG = ag
    val (initial, readDuration) = requestHeaderReceiveTimeout match {
      case fin: FiniteDuration => (true, fin)
      case _ => (false, 0.millis)
    }

    tcp.server[F](bindAddress)
      .map(connect => Stream.resource(connect).flatMap(
        socket =>
          Stream.eval(SignallingRef[F, Boolean](initial)).flatMap{ 
            timeoutSignal =>  
              readWithTimeout[F](socket, readDuration, timeoutSignal.get, receiveBufferSize)
              .through(Parser.Req.parser(maxHeaderSize))
              .take(1)
              .flatMap{ req => 
                Stream.eval_(Sync[F].delay(logger.debug(s"Request Processed $req"))) ++
                Stream.eval_(timeoutSignal.set(false)) ++
                Stream(req).covary[F].through(Encoder.httpAppToPipe[F](httpApp)).take(1)
                  .handleErrorWith(onError).take(1)
                  .flatTap(resp => Stream.eval(Sync[F].delay(logger.debug(s"Response Created $resp"))))
                  .map(resp => (req, resp))
              }
              .attempt
              .evalMap{ attempted => 
                def send(request:Option[Request[F]], resp: Response[F]): F[Unit] = {
                  Stream(resp)
                  .covary[F]
                  .through(Encoder.respToBytesPipe[F])
                  .through(socket.writes())
                  .onFinalize(socket.endOfOutput)
                  .compile
                  .drain
                  .attempt
                  .flatMap{
                    case Left(err) => onWriteFailure(request, resp, err).compile.drain
                    case Right(()) => Sync[F].pure(())
                  }
                }
                attempted match {
                  case Right((request, response)) => send(Some(request), response)
                  case Left(err) => onError(err).evalMap { send(None, _) }.compile.drain
                }
              }.drain
          }
        )).parJoin(maxConcurrency)
          .interruptWhen(terminationSignal)
          .drain
  }


  def request[F[_]: ConcurrentEffect](
    request: Request[F]
    , acg: AsynchronousChannelGroup
    , chunkSize: Int = 32*1024
    , maxResponseHeaderSize: Int = 4096
    , timeout: Duration = 5.seconds
  ): Stream[F, Response[F]] = {
    implicit val ACG : AsynchronousChannelGroup = acg
    Stream.eval(codec.Shared.addressForRequest(request))
    .flatMap {address => Stream.resource(io.tcp.client[F](address))}
    .flatMap{socket => 
      timeout match {
        case fin: FiniteDuration =>
          Stream.eval(Sync[F].delay(System.currentTimeMillis())).flatMap { start =>
          Encoder.reqToBytes(request).to(socket.writes(Some(fin))).last.onFinalize(socket.endOfOutput).flatMap { _ =>
          Stream.eval(fs2.concurrent.SignallingRef[F, Boolean](true)).flatMap { timeoutSignal =>
          Stream.eval(Sync[F].delay(System.currentTimeMillis())).flatMap { sent =>
            val remains = fin - (sent - start).millis
            readWithTimeout(socket, remains, timeoutSignal.get, chunkSize)
            .through (Parser.Resp.parser[F](maxResponseHeaderSize))
            .flatMap { response =>
              Stream.eval_(timeoutSignal.set(false)) ++ Stream.emit(response)
            }
          }}}}
        case _ =>
          Encoder.reqToBytes(request).to(socket.writes(None)).last.onFinalize(socket.endOfOutput).flatMap { _ =>
            socket.reads(chunkSize, None) through Parser.Resp.parser[F](maxResponseHeaderSize)
          }
      }
}

  }

}