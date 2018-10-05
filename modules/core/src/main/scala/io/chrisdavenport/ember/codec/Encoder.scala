package io.chrisdavenport.ember.codec

import cats.effect._
import fs2._
import org.http4s._
import cats._
import cats.implicits._
import Shared._

object Encoder {

  def respToBytes[F[_]: Sync]: Pipe[F, Response[F], Byte] = _.flatMap{resp: Response[F] =>
    val statusInstances = new StatusInstances{}
    import statusInstances._
    val headerStrings : List[String] = resp.headers.map(h => h.name + ": " + h.value).toList

    val initSection = Stream(show"${resp.httpVersion} ${resp.status}") ++ Stream.emits(headerStrings)

    val body = Alternative[Option].guard(resp.isChunked)
      .fold(resp.body)(_ => resp.body.through(ChunkedEncoding.encode[F]))

    initSection.covary[F].intersperse("\r\n").through(text.utf8Encode) ++ 
    Stream.chunk(Chunk.ByteVectorChunk(`\r\n\r\n`)) ++
    resp.body
  }

  def httpServiceToPipe[F[_]: Sync](h: HttpService[F], onMissing: Response[F]): Pipe[F, Request[F], Response[F]] = _.evalMap{req => 
    h(req).value.map(_.fold(onMissing)(identity))
  }

}