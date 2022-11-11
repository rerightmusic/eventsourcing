package shared.json

import shared.newtypes.NewtypeWrapped
import cats.data.NonEmptyList
import zio.json.*
import zio.json.internal.RetractReader
import zio.NonEmptyChunk
import zio.json.internal.*
import zio.json.ast.*
import shared.newtypes.NewExtractor
import shared.macros.all.IsEnum

trait JsonEnumCodec[A] {
  val enc: JsonEnumEncoder[A]
  val dec: JsonEnumDecoder[A]
}
object JsonEnumCodec:
  inline def derived[A](using e: IsEnum[A]): JsonEnumCodec[A] =
    new JsonEnumCodec[A] {
      val enc = JsonEnumEncoder.derived[A]
      val dec = JsonEnumDecoder.derived[A]
    }

trait JsonEnumDecoder[A] extends JsonDecoder[A]
object JsonEnumDecoder:
  inline def derived[A](using e: IsEnum[A]): JsonEnumDecoder[A] =
    new JsonEnumDecoder[A]:
      override def unsafeDecode(
        trace: List[JsonError],
        in: RetractReader
      ): A = e.enumFromString(JsonDecoder.string.unsafeDecode(trace, in))

      override final def fromJsonAST(
        json: Json
      ): Either[String, A] =
        JsonDecoder.string
          .fromJsonAST(json)
          .map(e.enumFromString(_))

trait JsonEnumEncoder[A] extends JsonEncoder[A]
object JsonEnumEncoder:
  inline def derived[A](using e: IsEnum[A]): JsonEnumEncoder[A] =
    new JsonEnumEncoder[A]:
      override def isNothing(a: A): Boolean =
        JsonEncoder.string.isNothing(a.toString)
      override def unsafeEncode(a: A, indent: Option[Int], out: Write): Unit =
        JsonEncoder.string.unsafeEncode(a.toString, indent, out)
      override def toJsonAST(a: A): Either[String, Json] =
        Json.decoder.decodeJson(
          JsonEncoder.string.encodeJson(a.toString, None)
        )
