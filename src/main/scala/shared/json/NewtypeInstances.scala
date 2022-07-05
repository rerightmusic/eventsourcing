package shared.json

import zio.json.*
import zio.json.internal.*
import zio.json.ast.*
import shared.newtypes.NewExtractor
import java.util.UUID
import shared.macros.all.IsNewtype

trait NewtypeInstances:
  given newtypeCodec[A, B](using
    ex: NewExtractor.Aux[A, B],
    codec: JsonCodec[B]
  ): JsonCodec[A] = JsonCodec[A](
    new JsonEncoder[A]:
      override def isNothing(a: A): Boolean =
        codec.encoder.isNothing(ex.from(a))
      override def unsafeEncode(a: A, indent: Option[Int], out: Write): Unit =
        codec.encoder.unsafeEncode(ex.from(a), indent, out)
      override def toJsonAST(a: A): Either[String, Json] =
        Json.decoder.decodeJson(
          codec.encoder.encodeJson(ex.from(a), None)
        )
    ,
    new JsonDecoder[A]:
      override def unsafeDecode(
        trace: List[JsonError],
        in: RetractReader
      ): A =
        ex.to(codec.decoder.unsafeDecode(trace, in))

      override final def fromJsonAST(
        json: Json
      ): Either[String, A] =
        codec.decoder.fromJsonAST(json).map(ex.to)
  )

  given newtypeFieldEncoder[A, B](using
    ex: NewExtractor.Aux[A, B],
    enc: JsonFieldEncoder[B]
  ): JsonFieldEncoder[A] =
    enc.contramap(ex.from(_))

  given newtypeFieldDecoder[A, B](using
    ex: NewExtractor.Aux[A, B],
    dec: JsonFieldDecoder[B]
  ): JsonFieldDecoder[A] =
    dec.map(ex.to)

  given uuidFieldEncoder: JsonFieldEncoder[UUID] =
    JsonFieldEncoder[String].contramap(_.toString)

  given uuidFieldDecoder: JsonFieldDecoder[UUID] =
    JsonFieldDecoder[String].map(UUID.fromString)
