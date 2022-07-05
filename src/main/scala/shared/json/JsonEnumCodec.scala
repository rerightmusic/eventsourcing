package shared.json

import shared.newtypes.NewtypeWrapped
import cats.data.NonEmptyList
import eventsourcing.all.*
import zio.json.*
import zio.json.internal.RetractReader
import zio.NonEmptyChunk
import zio.json.internal.*
import zio.json.ast.*
import shared.newtypes.NewExtractor
import shared.macros.all.*

trait JsonEnumCodec[A](using IsEnum[A]) extends JsonCodec[A]
object JsonEnumCodec:
  inline def derived[A](using IsEnum[A]): JsonEnumCodec[A] =
    new JsonEnumCodec[A]:
      def encoder = new JsonEncoder[A]:
        override def isNothing(a: A): Boolean =
          JsonEncoder.string.isNothing(a.toString)
        override def unsafeEncode(a: A, indent: Option[Int], out: Write): Unit =
          JsonEncoder.string.unsafeEncode(a.toString, indent, out)
        override def toJsonAST(a: A): Either[String, Json] =
          Json.decoder.decodeJson(
            JsonEncoder.string.encodeJson(a.toString, None)
          )

      override def unsafeEncode(a: A, indent: Option[Int], out: Write): Unit =
        encoder.unsafeEncode(a, indent, out)

      def decoder = new JsonDecoder[A]:
        override def unsafeDecode(
          trace: List[JsonError],
          in: RetractReader
        ): A =
          enumFromString[A](JsonDecoder.string.unsafeDecode(trace, in))

        override final def fromJsonAST(
          json: Json
        ): Either[String, A] =
          JsonDecoder.string.fromJsonAST(json).map(enumFromString[A](_))

      override def unsafeDecode(trace: List[JsonError], in: RetractReader): A =
        decoder.unsafeDecode(trace, in)
