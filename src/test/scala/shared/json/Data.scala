package shared.json

import shared.newtypes.NewtypeWrapped
import shared.uuid.all.*
import shared.json.all.{given, *}
import shared.time.all.{given, *}
import cats.data.NonEmptyList
import eventsourcing.all.*
import zio.json.*
import zio.NonEmptyChunk
import shared.json.all.given

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
  fieldC: Updated[Int],
  fieldD: UpdatedOpt[Int],
  fieldE: UpdatedNel[Int],
  fieldF: UpdatedOpt[NonEmptyList[Int]],
  fieldG: Updated[List[String]],
  fieldH: UpdatedOpt[List[String]],
  fieldI: Option[Enum],
  fieldJ: Option[Sum],
  fieldK: Option[NonEmptyList[String]],
  fieldL: New,
  fieldM: SumOfProducts,
  fieldN: Option[List[Int]]
) derives JsonCodec

sealed trait SumOfProducts derives JsonCodec
object SumOfProducts:
  case class SumA(ta: String, tb: Option[String]) extends SumOfProducts
  case class SumB(ta: String, tb: Option[String]) extends SumOfProducts
