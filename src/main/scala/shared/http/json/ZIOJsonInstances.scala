package shared.http.json

import cats.effect.Concurrent
import org.http4s.headers.`Content-Type`
import org.http4s.*
import zio.json.*
import zio.json.ast.*

import java.nio.charset.StandardCharsets

trait ZIOJsonInstances {
  given jsonEncoder[F[_]: Concurrent]: EntityEncoder[F, Json] = jsonEncoderOf
  given jsonDecoder[F[_]: Concurrent]: EntityDecoder[F, Json] = jsonOf
  def jsonOf[F[_]: Concurrent, A: JsonDecoder]: EntityDecoder[F, A] =
    EntityDecoder.decodeBy[F, A](MediaType.application.json) { m =>
      EntityDecoder.collectBinary(m).subflatMap { chunk =>
        val str = new String(chunk.toArray, StandardCharsets.UTF_8)
        if (str.nonEmpty)
          str.fromJson
            .fold(e => Left(MalformedMessageBodyFailure(e, None)), Right(_))
        else
          Left(MalformedMessageBodyFailure("Invalid JSON: empty body"))
      }
    }

  def jsonEncoderOf[F[_], A: JsonEncoder]: EntityEncoder[F, A] = EntityEncoder
    .stringEncoder[F]
    .contramap[A](_.toJson)
    .withContentType(`Content-Type`(MediaType.application.json))
}
