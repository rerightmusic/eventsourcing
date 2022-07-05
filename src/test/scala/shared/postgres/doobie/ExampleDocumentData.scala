package shared.postgres.doobie

import shared.json.all.{given, *}
import cats.data.NonEmptyList
import java.util.UUID
import shared.newtypes.NewtypeWrapped
import scala.util.Random

object Id extends NewtypeWrapped[UUID]
type Id = Id.Type

case class ExampleDocumentData(
  list: List[String],
  name: String,
  id: UUID,
  nonEmptyList: NonEmptyList[String],
  ids: List[UUID],
  objectList: List[ExampleObject],
  `enum`: ExampleEnum,
  enumTrait: ExampleEnumTrait,
  optional: Option[UUID]
) derives JsonCodec

case class ExampleObject(id: String, value: String, `enum`: ExampleEnum)
    derives JsonCodec

enum ExampleEnum derives JsonEnumCodec:
  case EnumA, EnumB

sealed trait ExampleEnumTrait derives JsonEnumCodec
object ExampleEnumTrait:
  case object EnumA extends ExampleEnumTrait
  case object EnumB extends ExampleEnumTrait

def generateData = () =>
  val uuid1a = UUID.randomUUID
  val uuid1b = UUID.randomUUID
  val str1a = randomString
  val str1b = randomString
  val ab = Random.nextInt(2)
  val exEnum = ab match
    case 0 => ExampleEnum.EnumA
    case 1 => ExampleEnum.EnumB
    case _ => ExampleEnum.EnumA
  val exEnumTrait = ab match
    case 0 => ExampleEnumTrait.EnumA
    case 1 => ExampleEnumTrait.EnumB
    case _ => ExampleEnumTrait.EnumA
  val optional = ab match
    case 0 => None
    case 1 => Some(uuid1a)
    case _ => None
  ExampleDocumentData(
    List(str1a, str1b),
    str1a,
    uuid1a,
    NonEmptyList.of(str1a, str1b),
    List(uuid1a, uuid1b),
    List(
      ExampleObject(str1a, str1b, exEnum),
      ExampleObject(str1b, str1a, exEnum)
    ),
    exEnum,
    exEnumTrait,
    optional
  )
