package shared.http.json

import org.http4s.EntityEncoder
import zio.json.JsonEncoder

trait ZIOEntityEncoder extends ZIOJsonInstances {
  implicit def zioEntityEncoder[F[_], A: JsonEncoder]: EntityEncoder[F, A] =
    jsonEncoderOf[F, A]
}

object ZIOEntityEncoder extends ZIOEntityEncoder
