package io.chrisdavenport.ember.core

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.scalacheck._
import cats.implicits._
import cats.effect.IO
import org.http4s._

import Http4sArbitraries._

class TraversalSpec extends Specification with ScalaCheck {
  "Request Encoder/Parser" should {
    "preserve headers" >> prop { req: Request[IO] =>
      val end = Parser.Request.parser[IO](Int.MaxValue)(
        Encoder.reqToBytes[IO](req)
      ).unsafeRunSync

      end.headers must_=== req.headers
    }.pendingUntilFixed

    "preserve method" >> prop { req: Request[IO] =>

      val end = Parser.Request.parser[IO](Int.MaxValue)(
        Encoder.reqToBytes[IO](req)
      ).unsafeRunSync

      end.method must_=== req.method
    }.pendingUntilFixed

    "preserve uri.scheme" >> prop { req: Request[IO] =>
      val end = Parser.Request.parser[IO](Int.MaxValue)(
        Encoder.reqToBytes[IO](req)
      ).unsafeRunSync

      end.uri.scheme must_=== req.uri.scheme
    }.pendingUntilFixed

    "preserve body with a known uri" >> prop {
      (req: Request[IO], s: String) =>
      val newReq = req
        .withUri(Uri.unsafeFromString("http://www.google.com"))
        .withEntity(s)
      val end = Parser.Request.parser[IO](Int.MaxValue)(
        Encoder.reqToBytes[IO](req)
      ).unsafeRunSync

      end.body.through(fs2.text.utf8Decode)
      .compile
      .foldMonoid
      .unsafeRunSync must_=== s
    }.setArbitrary2(Arbitrary(Gen.alphaNumStr)).pendingUntilFixed
  }
}