package eventsourcing.domain

import shared.principals.PrincipalId
import java.time.OffsetDateTime
import shared.newtypes.NewtypeWrapped
import java.util.UUID
import cats.data.NonEmptyList
import cats.syntax.all.*
import eventsourcing.domain.generics.Remove
import eventsourcing.domain.generics.Add
import zio.Has

object types:
  type SequenceId = SequenceId.Type
  object SequenceId extends NewtypeWrapped[Int]

  abstract class AggregateError(val msg: String) extends Exception(msg)

  object AggregateError:
    case class AggregateMissing[Id](
      val id: Id,
      val message: Option[String] = None
    ) extends AggregateError(
          s"Aggregate is missing ${id}${message.fold("")(m => s", ${m}")}"
        )

    case class InvalidAggregate[Agg, Id, Meta, EventData](
      val aggregate: Option[Agg],
      val event: Event[Id, Meta, EventData],
      val message: String
    ) extends AggregateError(
          s"Aggregate: ${aggregate}, Event: ${event}, ${message}"
        )

  case class Event[Id, Meta, EventData](
    sequenceId: SequenceId,
    id: Id,
    meta: Meta,
    createdBy: PrincipalId,
    created: OffsetDateTime,
    version: Int,
    deleted: Boolean,
    data: EventData
  )

  abstract class AggregateViewError(val msg: String) extends Exception(msg)

  object AggregateViewError:
    case class InvalidAggregateView[View, Ev](
      val view: Option[View],
      val event: Ev,
      val message: String
    ) extends AggregateViewError(
          s"View: ${view}, Event: ${event}, ${message}"
        )

    case class AggregateViewMissing[Query](
      query: Query,
      message: String = "Aggregate View is missing"
    ) extends AggregateViewError(
          s"message: ${message}, query: ${query}"
        )
    case class RunAggregateViewError[Query](
      message: String
    ) extends AggregateViewError(s"message: ${message}")

  case class AggregateViewEvent[Aggregates <: NonEmptyTuple](
    name: String,
    event: Event[Any, Any, Any]
  )

  object AggregateViewEvent:
    extension [Aggregates <: NonEmptyTuple](ev: AggregateViewEvent[Aggregates])
      def on[Agg](using agg: Aggregate[Agg]) =
        new MatchOn[
          Aggregates,
          Aggregates,
          EmptyTuple,
          Agg,
          agg.Id,
          agg.Meta,
          agg.EventData
        ](ev, EmptyTuple)

    class MatchOn[
      Aggregates <: NonEmptyTuple,
      Aggs <: Tuple,
      Fns <: Tuple,
      Agg,
      Id,
      Meta,
      EventData
    ](
      ev: AggregateViewEvent[Aggregates],
      fns: Fns
    ):
      def apply[Res, Aggs_ <: Tuple, Fns_ <: Tuple](
        f: Event[Id, Meta, EventData] => Res
      )(using
        rem: Remove[Aggs, Agg, Aggs_],
        add: Add[Fns, Event[Id, Meta, EventData] => Res, Fns_]
      ) =
        new MatchOnWith[Aggregates, Aggs_, Fns_](ev, add.add(fns, f))

    class MatchOnWith[Aggregates <: NonEmptyTuple, Aggs <: Tuple, Fns <: Tuple](
      val ev: AggregateViewEvent[Aggregates],
      val fns: Fns
    ):
      def on[Agg](using agg: Aggregate[Agg]) =
        new MatchOn[
          Aggregates,
          Aggs,
          Fns,
          Agg,
          agg.Id,
          agg.Meta,
          agg.EventData
        ](ev, fns)

      def orElse[A, Fns_ <: Tuple](v: A)(using
        add: Add[Fns, Event[Any, Any, Any] => A, Fns_]
      ) =
        new MatchOnWith[Aggregates, EmptyTuple, Fns_](ev, add.add(fns, _ => v))
    object MatchOnWith:
      given [Aggregates <: NonEmptyTuple, Fns <: NonEmptyTuple, Res](using
        f: FnsApply[Aggregates, Fns, Res]
      ): Conversion[MatchOnWith[Aggregates, EmptyTuple, Fns], Res] =
        new Conversion[
          MatchOnWith[Aggregates, EmptyTuple, Fns],
          Res
        ]:
          def apply(m: MatchOnWith[Aggregates, EmptyTuple, Fns]) =
            f.apply(m.fns, m.ev)

    trait FnsApply[Aggregates <: NonEmptyTuple, Fns <: NonEmptyTuple, Res]:
      def apply(fns: Fns, ev: AggregateViewEvent[Aggregates]): Res

    object FnsApply:
      given fns[Agg, Res1 <: Res, Res](using
        agg: Aggregate[Agg]
      ): FnsApply[
        Agg *: EmptyTuple,
        (
          Event[
            agg.Id,
            agg.Meta,
            agg.EventData
          ] => Res1
        ) *: EmptyTuple,
        Res
      ] =
        new FnsApply[
          Agg *: EmptyTuple,
          (
            Event[
              agg.Id,
              agg.Meta,
              agg.EventData
            ] => Res1
          ) *: EmptyTuple,
          Res
        ]:
          def apply(
            fns: (
              Event[
                agg.Id,
                agg.Meta,
                agg.EventData
              ] => Res1
            ) *: EmptyTuple,
            ev: AggregateViewEvent[Agg *: EmptyTuple]
          ): Res = fns.head(
            ev.event.asInstanceOf[Event[agg.Id, agg.Meta, agg.EventData]]
          )

      given fns2[
        Agg,
        Xs <: NonEmptyTuple,
        Fs <: NonEmptyTuple,
        Res1 <: Res,
        Res2 <: Res,
        Res
      ](using
        agg: Aggregate[Agg],
        f: FnsApply[Xs, Fs, Res2]
      ): FnsApply[
        Agg *: Xs,
        (
          Event[
            agg.Id,
            agg.Meta,
            agg.EventData
          ] => Res1
        ) *: Fs,
        Res
      ] =
        new FnsApply[
          Agg *: Xs,
          (
            Event[
              agg.Id,
              agg.Meta,
              agg.EventData
            ] => Res1
          ) *: Fs,
          Res
        ]:
          def apply(
            fns: (
              Event[
                agg.Id,
                agg.Meta,
                agg.EventData
              ] => Res1
            ) *: Fs,
            ev: AggregateViewEvent[Agg *: Xs]
          ): Res = if ev.name === agg.storeName then
            fns.head(
              ev.event.asInstanceOf[Event[agg.Id, agg.Meta, agg.EventData]]
            )
          else f.apply(fns.tail, ev.copy())

  case class AggregateViewStatus(
    sequenceIds: Map[String, SequenceId],
    catchupDuration: Option[Long] = None,
    catchupEventsSize: Option[Int] = None,
    syncDuration: Option[Long] = None,
    syncEventsSize: Option[Int] = None,
    longestDuration: Option[Long] = None,
    longestEventsSize: Option[Int] = None,
    error: Option[String] = None
  ):
    def next = copy(
      sequenceIds = sequenceIds.mapValues(x => SequenceId(x.value + 1)).toMap
    )
    def getSequenceId(name: String): SequenceId = sequenceIds
      .get(name)
      .getOrElse(SequenceId(0))

    def sameSequenceIds(s: AggregateViewStatus) = sequenceIds.forall((k, v) =>
      s.sequenceIds.get(k).fold(false)(sId => sId.value === v.value)
    )

  object AggregateViewStatus:
    def fromEvents[A <: NonEmptyTuple](
      evs: NonEmptyList[AggregateViewEvent[A]],
      catchupDuration: Option[Long] = None,
      catchupEventsSize: Option[Int] = None,
      syncDuration: Option[Long] = None,
      syncEventsSize: Option[Int] = None
    ) =
      AggregateViewStatus(
        evs.foldLeft(Map.empty[String, SequenceId])((prev, ev) =>
          prev.updated(ev.name, ev.event.sequenceId)
        ),
        catchupDuration = catchupDuration,
        catchupEventsSize = catchupEventsSize,
        syncDuration = syncDuration,
        syncEventsSize = syncEventsSize
      )

  case class Schemaless[Id, Meta, Data](
    id: Id,
    meta: Meta,
    createdBy: PrincipalId,
    lastUpdatedBy: PrincipalId,
    data: Data,
    deleted: Boolean,
    created: OffsetDateTime,
    lastUpdated: OffsetDateTime
  )

  enum AggregateViewMode:
    case Continue, Restart

  enum AggregateViewStage:
    case CatchUp, Sync

  type AggregateStores[Agg, Id, Meta, EventData] =
    AggregateStore[Id, Meta, EventData] &
      AggregateViewStore.Schemaless[Id, Meta, Agg, Id, Agg *: EmptyTuple]
