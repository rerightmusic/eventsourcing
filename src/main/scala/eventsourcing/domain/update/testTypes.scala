package eventsourcing.domain.update

import types.*
import cats.data.NonEmptyList

case class Source(
  v: Int,
  v2: Option[Int],
  v3: Inner,
  v4: Option[Inner],
  v5: Option[Inner],
  v6: NonEmptyList[Int],
  v7: Option[Sum],
  v8: Option[RightsholderTypeData]
)
case class Inner(v: Int)

sealed trait Sum
object Sum:
  case class SumA(a: Int, b: Option[Int]) extends Sum
  case object SumB extends Sum
case class Event(
  v: Updated[Int],
  v2: UpdatedOpt[Int],
  v3: Updated[InnerEvent],
  v4: UpdatedOpt[InnerEvent],
  v5: UpdatedOpt[Inner],
  v6: UpdatedNel[Int],
  v7: UpdatedOpt[EventSum],
  v8: UpdatedOpt[RightsholderTypeDataUpdated]
)
case class InnerEvent(v: Updated[Int])

sealed trait EventSum
object EventSum:
  case class SumA(a: Updated[Int], b: UpdatedOpt[Int]) extends EventSum
  case object SumB extends EventSum

enum RightsholderType:
  case Independent, Subsidiary, SelfReleasing

sealed trait RightsholderTypeData:
  def `type`: RightsholderType
object RightsholderTypeData:
  case object Independent extends RightsholderTypeData:
    def `type` = RightsholderType.Independent
  case class Subsidiary(parentId: String) extends RightsholderTypeData:
    def `type` = RightsholderType.Subsidiary
  case class SelfReleasing(id: String) extends RightsholderTypeData:
    def `type` = RightsholderType.SelfReleasing
sealed trait RightsholderTypeDataUpdated:
  def `type`: RightsholderType
object RightsholderTypeDataUpdated:
  case object Independent extends RightsholderTypeDataUpdated:
    def `type` = RightsholderType.Independent
  case class Subsidiary(parentId: Updated[String])
      extends RightsholderTypeDataUpdated:
    def `type` = RightsholderType.Subsidiary
  case class SelfReleasing(id: Updated[String])
      extends RightsholderTypeDataUpdated:
    def `type` = RightsholderType.SelfReleasing
