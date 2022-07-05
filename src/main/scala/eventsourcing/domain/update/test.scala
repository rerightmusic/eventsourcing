package eventsourcing.domain.update

import types.*
import shared.data.automapper.*
import cats.data.NonEmptyList

val source =
  Source(
    1,
    Some(1),
    Inner(1),
    Some(Inner(1)),
    Some(Inner(1)),
    NonEmptyList.of(1),
    Some(Sum.SumA(1, None)),
    Some(RightsholderTypeData.Independent)
  )
val event =
  Event(
    HasUpdated(3),
    HasUpdated(Some(3)),
    HasUpdated(InnerEvent(HasUpdated(1))),
    HasUpdated(Some(InnerEvent(HasUpdated(1)))),
    HasUpdated(Some(Inner(1))),
    HasUpdated(NonEmptyList.of(1)),
    HasUpdated(Some(EventSum.SumA(HasUpdated(1), HasUpdated(None)))),
    HasUpdated(Some(RightsholderTypeDataUpdated.Independent))
  )

val t1 = source.update(event)
val t2 =
  source.dynUpdate(event)(v4 = (_: Option[InnerEvent]) => Some(Inner(1)))
val t3 = source.updated(source).to[Event]
