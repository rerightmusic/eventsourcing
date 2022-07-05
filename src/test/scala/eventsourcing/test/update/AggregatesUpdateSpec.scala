package eventsourcing.update

import org.scalatest.matchers.should.Matchers
import scala.annotation.targetName
import scala.quoted._
import eventsourcing.all.*
import java.util.UUID
import cats.data.NonEmptyList
import org.scalatest.flatspec.AnyFlatSpec

class AggregatesUpdateSpec extends AnyFlatSpec with Matchers {
  val id1 = X(UUID.randomUUID)
  val id2 = X(UUID.randomUUID)
  "update an aggregate using an event" should "work" in {
    aggregate
      .update(
        AggregateUpdatedEvent(
          HasUpdated(id1),
          HasUpdated(List(10)),
          HasUpdated(NonEmptyList.of(10)),
          HasUpdated(Map("10" -> "10")),
          HasUpdated(None),
          HasUpdated(Some(id2)),
          HasUpdated(Some(List(10))),
          HasUpdated(None),
          HasUpdated(
            Some(InnerObjUpdated(HasUpdated(10), HasUpdated(Some("20"))))
          ),
          HasUpdated(
            Some(InnerSumEvent.InnerA(HasUpdated(10), HasUpdated("30")))
          ),
          HasUpdated(InnerSumEvent.InnerA(HasUpdated(20), NotUpdated))
        )
      ) shouldBe AggregateData(
      id1,
      List(10),
      NonEmptyList.of(10),
      Map("10" -> "10"),
      None,
      Some(id2),
      Some(List(10)),
      None,
      Some(InnerObj(10, Some("20"))),
      Some(InnerSum.InnerA(10, "30")),
      InnerSum.InnerA(20, "2")
    )

  }

  "update an aggregate using an event dynamically" should "work" in {
    aggregateDyn
      .dynUpdate(
        AggregateUpdatedEvent(
          HasUpdated(id1),
          HasUpdated(List(1)),
          HasUpdated(NonEmptyList.of(1)),
          HasUpdated(Map("1" -> "1")),
          HasUpdated(Some(1)),
          HasUpdated(Some(id2)),
          HasUpdated(Some(List(1))),
          HasUpdated(Some(NonEmptyList.of(1))),
          HasUpdated(
            Some(InnerObjUpdated(HasUpdated(20), HasUpdated(Some("20"))))
          ),
          HasUpdated(Some(InnerSumEvent.InnerB)),
          HasUpdated(InnerSumEvent.InnerB)
        )
      )(
        optList = (o: Option[List[Int]]) => o.toList.flatten.toList
      ) shouldBe AggregateDataDyn(
      id1,
      List(1),
      NonEmptyList.of(1),
      Map("1" -> "1"),
      Some(1),
      Some(id2),
      List(1),
      Some(NonEmptyList.of(1)),
      Some(InnerObj(20, Some("20")))
    )
  }

  "create event from aggregate and update" should "work" in {
    aggregate
      .updated(aggregateUpdate)
      .to[AggregateUpdatedEvent] shouldBe AggregateUpdatedEvent(
      if aggregateUpdate.id != aggregate.id then HasUpdated(aggregateUpdate.id)
      else NotUpdated,
      if aggregateUpdate.list != aggregate.list then
        HasUpdated(aggregateUpdate.list)
      else NotUpdated,
      if aggregateUpdate.nel != aggregate.nel then
        HasUpdated(aggregateUpdate.nel)
      else NotUpdated,
      if aggregateUpdate.map != aggregate.map then
        HasUpdated(aggregateUpdate.map)
      else NotUpdated,
      if aggregateUpdate.opt != aggregate.opt then
        HasUpdated(aggregateUpdate.opt)
      else NotUpdated,
      if aggregateUpdate.optId != aggregate.optId then
        HasUpdated(aggregateUpdate.optId)
      else NotUpdated,
      if aggregateUpdate.optList != aggregate.optList then
        HasUpdated(aggregateUpdate.optList)
      else NotUpdated,
      if aggregateUpdate.optNel != aggregate.optNel then
        HasUpdated(aggregateUpdate.optNel)
      else NotUpdated,
      if aggregateUpdate.obj != aggregate.obj then
        aggregateUpdate.obj match
          case None => HasUpdated(None)
          case Some(o) =>
            HasUpdated(
              Some(
                InnerObjUpdated(
                  if Some(o.a) != aggregate.obj.map(_.a) then HasUpdated(o.a)
                  else NotUpdated,
                  if o.b != aggregate.obj.flatMap(_.b) then HasUpdated(o.b)
                  else NotUpdated
                )
              )
            )
      else NotUpdated,
      if aggregateUpdate.sum != aggregate.sum then
        HasUpdated((aggregateUpdate.sum, aggregate.sum) match
          case (None, _) => None
          case (Some(InnerSum.InnerA(a, b)), Some(InnerSum.InnerA(c, d))) =>
            Some(
              InnerSumEvent.InnerA(
                if a != c then HasUpdated(a) else NotUpdated,
                if b != d then HasUpdated(b) else NotUpdated
              )
            )

          case (Some(InnerSum.InnerA(a, b)), _) =>
            Some(
              InnerSumEvent.InnerA(
                HasUpdated(a),
                HasUpdated(b)
              )
            )
          case (Some(InnerSum.InnerB), _) =>
            Some(InnerSumEvent.InnerB)
        )
      else NotUpdated,
      if aggregateUpdate.sum2 != aggregate.sum2 then
        HasUpdated((aggregateUpdate.sum2, aggregate.sum2) match
          case (InnerSum.InnerA(a, b), InnerSum.InnerA(c, d)) =>
            InnerSumEvent.InnerA(
              if a != c then HasUpdated(a) else NotUpdated,
              if b != d then HasUpdated(b) else NotUpdated
            )

          case (InnerSum.InnerA(a, b), _) =>
            InnerSumEvent.InnerA(
              HasUpdated(a),
              HasUpdated(b)
            )
          case (InnerSum.InnerB, _) =>
            InnerSumEvent.InnerB
        )
      else NotUpdated
    )
  }

}
