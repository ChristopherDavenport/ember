package io.chrisdavenport.ember.core

import org.specs2.mutable.Specification
import cats.implicits._
import cats.effect.{IO, Sync}
import org.http4s._

import scala.concurrent._
import scala.concurrent.duration._

class EncoderSpec extends Specification {
  private object Helpers {
    def stripLines(s: String): String = s.replace("\r\n", "\n")

    // Only for Use with Text Requests
    def encodeRequestRig[F[_]: Sync](req: Request[F]): F[String] = {
      Encoder.reqToBytes(req)
      .through(fs2.text.utf8Decode[F])
      .compile
      .foldMonoid
      .map(stripLines)
    }

    // Only for Use with Text Requests
    def encodeResponseRig[F[_]: Sync](resp: Response[F]): F[String] = {
      Encoder.respToBytes(resp)
      .through(fs2.text.utf8Decode[F])
      .compile
      .foldMonoid
      .map(stripLines)
    }
  }

  "Encoder.reqToBytes" should {
    "encode a no body request correctly" in {
      val req = Request[IO](Method.GET, Uri.unsafeFromString("http://www.google.com"))
      val expected = 
      """GET  HTTP/1.1
      |Host: www.google.com
      |
      |""".stripMargin

      Helpers.encodeRequestRig(req).unsafeRunSync must_=== expected
    }

    "encode a body with a request correctly" in {
      val req = Request[IO](Method.POST, Uri.unsafeFromString("http://www.google.com"))
        .withEntity("Hello World!")
      val expected = 
      """POST  HTTP/1.1
      |Content-Length: 12
      |Content-Type: text/plain; charset=UTF-8
      |Host: www.google.com
      |
      |Hello World!""".stripMargin

      Await.result(Helpers.encodeRequestRig(req).unsafeToFuture, 1.second) must_=== expected
    }
  }
  

}