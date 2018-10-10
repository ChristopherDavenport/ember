package org.http4s.ember.testing

import org.scalacheck._
import org.scalacheck.Gen._
import org.scalacheck.Arbitrary._
import org.http4s._

trait LockedTraits {

  lazy val genTchar: Gen[Char] = oneOf {
    Seq('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~') ++
      ('0' to '9') ++ ('A' to 'Z') ++ ('a' to 'z')
  }
  

  lazy val genToken: Gen[String] =
    nonEmptyListOf(genTchar).map(_.mkString)

  lazy val http4sGenMediaRange: Gen[MediaRange] =
    for {
      `type` <- genToken.map(_.toLowerCase)
      extensions <- http4sGenMediaRangeExtensions
    } yield new MediaRange(`type`, extensions)

  lazy val genValidStatusCode =
    choose(Status.MinCode, Status.MaxCode)

  lazy val genStandardStatus =
    oneOf(Status.registered)

    // TODO Fix Rfc2616BasicRules.QuotedString to support the backslash character
  lazy val allowedQDText: List[Char] = allowedText.filterNot(c => c == '"' || c == '\\')

  lazy val genQDText: Gen[String] = nonEmptyListOf(oneOf(allowedQDText)).map(_.mkString)

  lazy val genQuotedPair: Gen[String] =
    genChar.map(c => s"\\$c")

    // MediaRange exepects the quoted pair without quotes
  lazy val http4sGenUnquotedPair = genQuotedPair.map { c =>
    c.substring(1, c.length - 1)
  }

  lazy val http4sGenMediaRangeExtension: Gen[(String, String)] =
    for {
      token <- genToken
      value <- oneOf(http4sGenUnquotedPair, genQDText)
    } yield (token, value)

  lazy val http4sGenMediaRangeExtensions: Gen[Map[String, String]] =
    Gen.listOf(http4sGenMediaRangeExtension).map(_.toMap)

  lazy val octets: List[Char] = ('\u0000' to '\u00FF').toList

  lazy val genOctet: Gen[Char] = oneOf(octets)

  lazy val allowedText: List[Char] = octets.diff(ctlChar)

  lazy val genChar: Gen[Char] = choose('\u0000', '\u007F')


  lazy val ctlChar: List[Char] = ('\u007F' +: ('\u0000' to '\u001F')).toList



}