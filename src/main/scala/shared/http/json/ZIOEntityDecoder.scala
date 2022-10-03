package shared.http.json

import cats.effect.Concurrent
import org.http4s.EntityDecoder
import zio.json.JsonDecoder

trait ZIOEntityDecoder extends ZIOJsonInstances {
  implicit def zioEntityDecoder[F[_]: Concurrent, A: JsonDecoder]
    : EntityDecoder[F, A] =
    jsonOf[F, A]
}

object ZIOEntityDecoder extends ZIOEntityDecoder
