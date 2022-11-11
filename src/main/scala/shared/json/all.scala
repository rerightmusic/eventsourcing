package shared.json

import zio.json.*
import zio.json.ast.Json
import zio.json.internal.Write
import zio.json.internal.RetractReader

object all extends AllInstances:
  type JsonEnumCodec[A] = shared.json.JsonEnumCodec[A]
  val JsonEnumCodec = shared.json.JsonEnumCodec
  type JsonEnumEncoder[A] = shared.json.JsonEnumEncoder[A]
  val JsonEnumEncoder = shared.json.JsonEnumEncoder
  type JsonEnumDecoder[A] = shared.json.JsonEnumDecoder[A]
  val JsonEnumDecoder = shared.json.JsonEnumDecoder
  type JsonEnumFieldEncoder[A] = shared.json.JsonEnumFieldEncoder[A]
  val JsonEnumFieldEncoder = shared.json.JsonEnumFieldEncoder
  type JsonEnumFieldDecoder[A] = shared.json.JsonEnumFieldDecoder[A]
  val JsonEnumFieldDecoder = shared.json.JsonEnumFieldDecoder
  type JsonCodec[A] = zio.json.JsonCodec[A]
  val JsonCodec = zio.json.JsonCodec
  type JsonEncoder[A] = zio.json.JsonEncoder[A]
  val JsonEncoder = zio.json.JsonEncoder
  type JsonDecoder[A] = zio.json.JsonDecoder[A]
  val JsonDecoder = zio.json.JsonDecoder
  type JsonFieldEncoder[A] = zio.json.JsonFieldEncoder[A]
  val JsonFieldEncoder = zio.json.JsonFieldEncoder
  type JsonFieldDecoder[A] = zio.json.JsonFieldDecoder[A]
  val JsonFieldDecoder = zio.json.JsonFieldDecoder
  val JsonCursor = zio.json.ast.JsonCursor
  type Json = zio.json.ast.Json
  val Json = zio.json.ast.Json
  def emptyObject = Map[String, String]().toJsonASTOrFail

  implicit def decOps[A](json: String): DecoderOps =
    new DecoderOps(json)

  extension [A](a: A)(using c: JsonEncoder[A])
    def toJson: String =
      c.encodeJson(a, None).toString

    def toJsonPretty: String =
      c.encodeJson(a, Some(0)).toString

    def toJsonAST: Either[String, Json] =
      Json.decoder.decodeJson(c.encodeJson(a, None))

    def toJsonASTOrFail: Json =
      toJsonAST.fold(e => throw new Exception(e), v => v)
