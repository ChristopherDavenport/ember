package io.chrisdavenport.ember.client

import fs2.io.tcp._
import org.http4s.client.RequestKey

final case class RequestKeySocket[F[_]](
  socket: Socket[F],
  requestKey: RequestKey
)