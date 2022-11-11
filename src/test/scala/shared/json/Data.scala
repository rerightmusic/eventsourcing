package shared.json

import shared.newtypes.NewtypeWrapped
import shared.uuid.all.*
import shared.json.all.{given, *}
import shared.time.all.{given, *}
import cats.data.NonEmptyList
import zio.json.*
import zio.NonEmptyChunk

object New extends NewtypeWrapped[UUID]
type New = New.Type
enum Enum derives JsonEnumCodec:
  case A, B, C

sealed trait Sum derives JsonEnumCodec
object Sum:
  case object SumA extends Sum
  case object SumB extends Sum

case class Record(
  fieldA: String,
  fieldB: Option[Int],
  fieldC: Option[Enum],
  fieldD: Option[Sum],
  fieldE: Option[NonEmptyList[String]],
  fieldF: New,
  fieldG: SumOfProducts,
  fieldH: Option[List[Int]]
) derives JsonCodec

sealed trait SumOfProducts derives JsonCodec
object SumOfProducts:
  case class SumA(ta: String, tb: Option[String]) extends SumOfProducts
  case class SumB(ta: String, tb: Option[String]) extends SumOfProducts
