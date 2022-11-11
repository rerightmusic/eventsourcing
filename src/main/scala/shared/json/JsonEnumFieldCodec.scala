package shared.json

import zio.json.*
import zio.json.internal.*
import zio.json.ast.*
import shared.macros.all.IsEnum

trait JsonEnumFieldEncoder[A] extends JsonFieldEncoder[A]
object JsonEnumFieldEncoder:
  inline def derived[A](using e: IsEnum[A]): JsonEnumFieldEncoder[A] =
    new JsonEnumFieldEncoder[A]:
      def unsafeEncodeField(in: A): String = e.toString(in)

trait JsonEnumFieldDecoder[A] extends JsonFieldDecoder[A]
object JsonEnumFieldDecoder:
  inline def derived[A](using e: IsEnum[A]): JsonEnumFieldDecoder[A] =
    new JsonEnumFieldDecoder[A]:
      def unsafeDecodeField(trace: List[JsonError], in: String): A =
        e.enumFromString(in)
