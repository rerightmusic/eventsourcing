package eventsourcing.update

import shared.newtypes.NewtypeWrapped
import cats.data.NonEmptyList
import eventsourcing.all.*
import java.util.UUID

type X = X.Type
object X extends NewtypeWrapped[UUID]
case class AggregateData(
  id: X,
  list: List[Int],
  nel: NonEmptyList[Int],
  map: Map[String, String],
  opt: Option[Int],
  optId: Option[X],
  optList: Option[List[Int]],
  optNel: Option[NonEmptyList[Int]],
  optMap: Option[Map[String, String]],
  obj: Option[InnerObj],
  sum: Option[InnerSum],
  sum2: InnerSum
)

case class InnerObj(a: Int, b: Option[String])
sealed trait InnerSum
object InnerSum:
  case class InnerA(v: Int, v2: String) extends InnerSum
  case object InnerB extends InnerSum

val aggregate = AggregateData(
  X(UUID.randomUUID),
  List(1),
  NonEmptyList.of(1),
  Map("1" -> "1"),
  Some(1),
  Some(X(UUID.randomUUID)),
  Some(List(1)),
  Some(NonEmptyList.of(1)),
  Some(Map("1" -> "1")),
  Some(InnerObj(1, Some("1"))),
  Some(InnerSum.InnerB),
  InnerSum.InnerA(1, "2")
)

case class AggregateDataDyn(
  id: X,
  list: List[Int],
  nel: NonEmptyList[Int],
  map: Map[String, String],
  opt: Option[Int],
  optId: Option[X],
  optList: List[Int],
  optNel: Option[NonEmptyList[Int]],
  optMap: Option[Map[String, String]],
  obj: Option[InnerObj]
)

val aggregateDyn = AggregateDataDyn(
  X(UUID.randomUUID),
  List(1),
  NonEmptyList.of(3),
  Map("6" -> "2"),
  Some(1),
  Some(X(UUID.randomUUID)),
  List(4),
  Some(NonEmptyList.of(5)),
  Some(Map("5" -> "5")),
  Some(InnerObj(3, Some("3")))
)

case class AggregateUpdate(
  id: X,
  list: List[Int],
  nel: NonEmptyList[Int],
  map: Map[String, String],
  opt: Option[Int],
  optId: Option[X],
  optList: Option[List[Int]],
  optNel: Option[NonEmptyList[Int]],
  optMap: Option[Map[String, String]],
  obj: Option[InnerObj],
  sum: Option[InnerSum],
  sum2: InnerSum
)

val aggregateUpdate = AggregateUpdate(
  X(UUID.randomUUID),
  List(1),
  NonEmptyList.of(1),
  Map("10" -> "10"),
  Some(2),
  Some(X(UUID.randomUUID)),
  Some(List(3)),
  Some(NonEmptyList.of(8)),
  Some(Map("1" -> "2")),
  Some(InnerObj(2, Some("2"))),
  Some(InnerSum.InnerB),
  InnerSum.InnerB
)

case class AggregateUpdatedEvent(
  id: Updated[X],
  list: Updated[List[Int]],
  nel: UpdatedNel[Int],
  map: Updated[Map[String, String]],
  opt: UpdatedOpt[Int],
  optId: UpdatedOpt[X],
  optList: UpdatedOpt[List[Int]],
  optNel: UpdatedOpt[NonEmptyList[Int]],
  optMap: UpdatedOpt[Map[String, String]],
  obj: UpdatedOpt[InnerObjUpdated],
  sum: UpdatedOpt[InnerSumEvent],
  sum2: Updated[InnerSumEvent]
)

sealed trait InnerSumEvent
object InnerSumEvent:
  case class InnerA(v: Updated[Int], v2: Updated[String]) extends InnerSumEvent
  case object InnerB extends InnerSumEvent

case class InnerObjUpdated(a: Updated[Int], b: UpdatedOpt[String])
