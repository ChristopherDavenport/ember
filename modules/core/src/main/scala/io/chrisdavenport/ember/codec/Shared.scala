package io.chrisdavenport.ember.codec

import fs2._
import scodec.bits.ByteVector
import fs2.interop.scodec.ByteVectorChunk
import org.http4s.HttpVersion

object Shared {

  val `\n` : ByteVector = ByteVector('\n')
  val `\r` : ByteVector = ByteVector('\r')
  val `\r\n`: ByteVector = ByteVector('\r','\n')
  val `\r\n\r\n` = (`\r\n` ++ `\r\n`).compact

  def chunk2ByteVector(chunk: Chunk[Byte]):ByteVector = {
    chunk match  {
      case bv: ByteVectorChunk => bv.toByteVector
      case other =>
        val bs = other.toBytes
        ByteVector(bs.values, bs.offset, bs.size)
    }
  }


  def getHttpVersion(s: String): HttpVersion = {
    val _ = s
    HttpVersion(1,2)
  }


}