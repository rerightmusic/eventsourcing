package eventsourcing.infra

import eventsourcing.domain.{Aggregate, AggregateStore}
import eventsourcing.domain.types.*
import cats.data.NonEmptyList
import zio.ZIO
import fs2.Stream
import zio.RIO
import cats.syntax.all.*

trait AggregateViewStores[Aggregates <: NonEmptyTuple]:
  type Stores
  type AggsId <: Tuple

  def streamEventsFrom(
    queries: Option[NonEmptyList[AggsId]],
    status: Option[AggregateViewStatus]
  ): Stream[RIO[Stores, *], fs2.Chunk[AggregateViewEvent[Aggregates]]]

  def getLastSequenceIds: RIO[Stores, Map[String, SequenceId]]

object AggregateViewStores:
  type Aux[Aggs <: NonEmptyTuple, Stores_, AggsId_] =
    AggregateViewStores[Aggs] {
      type Stores = Stores_
      type AggsId = AggsId_
    }

  given aggs[Agg, Id, Meta, EventData](using
    t: Aggregate.Aux[Agg, Id, Meta, EventData, ?],
    tId: zio.Tag[Id],
    tMeta: zio.Tag[Meta],
    tEventData: zio.Tag[EventData]
  ): AggregateViewStores.Aux[Agg *: EmptyTuple, AggregateStore[
    Id,
    Meta,
    EventData
  ], Option[Id] *: EmptyTuple] =
    new AggregateViewStores[Agg *: EmptyTuple]:
      type Stores = AggregateStore[Id, Meta, EventData]
      type AggsId = Option[Id] *: EmptyTuple

      def streamEventsFrom(
        queries: Option[NonEmptyList[AggsId]],
        status: Option[AggregateViewStatus]
      ): Stream[RIO[Stores, *], fs2.Chunk[
        AggregateViewEvent[Agg *: EmptyTuple]
      ]] =
        val seqId = status
          .map(_.getSequenceId(t.versionedStoreName))
          .getOrElse(SequenceId(0))
        Stream
          .eval(
            ZIO
              .environmentWith[AggregateStore[Id, Meta, EventData]](svc =>
                queries.flatMap(_.toList.flatMap(_.head.toList).toNel) match
                  case None =>
                    svc.get
                      .streamEventsFrom(
                        Some(seqId),
                        None
                      )
                  case Some(qIds) =>
                    svc.get
                      .streamEventsForIdsFrom(Some(seqId), None, qIds)
              )
              .map(
                _.map(
                  _.map(ev =>
                    AggregateViewEvent(
                      t.versionedStoreName,
                      ev.asInstanceOf[Event[Any, Any, Any]]
                    )
                  )
                )
              )
          )
          .flatten

      def getLastSequenceIds = ZIO
        .environmentWithZIO[AggregateStore[Id, Meta, EventData]](
          _.get.getLastSequenceId
        )
        .map(seqId => Map(t.versionedStoreName -> seqId))

  given aggs2[
    Agg,
    Id,
    Meta,
    EventData,
    X,
    Xs <: Tuple,
    Stores_,
    AggsId_ <: Tuple
  ](using
    t: Aggregate.Aux[Agg, Id, Meta, EventData, ?],
    n: AggregateViewStores.Aux[X *: Xs, Stores_, AggsId_],
    tId: zio.Tag[Id],
    tMeta: zio.Tag[Meta],
    tEventData: zio.Tag[EventData]
  ): AggregateViewStores.Aux[Agg *: X *: Xs, AggregateStore[
    Id,
    Meta,
    EventData
  ] & Stores_, Option[Id] *: AggsId_] =
    new AggregateViewStores[Agg *: X *: Xs]:
      type Stores = AggregateStore[Id, Meta, EventData] & Stores_
      type AggsId = Option[Id] *: AggsId_

      def streamEventsFrom(
        queries: Option[NonEmptyList[AggsId]],
        status: Option[AggregateViewStatus]
      ): Stream[RIO[Stores, *], fs2.Chunk[AggregateViewEvent[
        Agg *: X *: Xs
      ]]] =
        Stream
          .eval(
            ZIO.environmentWith[AggregateStore[Id, Meta, EventData]](svc =>
              val seqId = status
                .map(_.getSequenceId(t.versionedStoreName))
                .getOrElse(SequenceId(0))
              queries.flatMap(_.toList.flatMap { x =>
                val o: Option[Id] = x.head
                o.toList
              }.toNel) match
                case None =>
                  svc.get
                    .streamEventsFrom(Some(seqId), None)
                case Some(qAggsIds) =>
                  svc.get
                    .streamEventsForIdsFrom(Some(seqId), None, qAggsIds)
            )
          )
          .map(
            _.map(
              _.map(ev =>
                AggregateViewEvent[Agg *: X *: Xs](
                  t.versionedStoreName,
                  ev.asInstanceOf[Event[Any, Any, Any]]
                )
              )
            )
          )
          .flatten ++ n
          .streamEventsFrom(queries.map(_.map(_.tail)), status)
          .map(_.asInstanceOf[fs2.Chunk[AggregateViewEvent[Agg *: X *: Xs]]])

      def getLastSequenceIds =
        for
          seqId <- ZIO
            .environmentWithZIO[AggregateStore[Id, Meta, EventData]](
              _.get.getLastSequenceId
            )
          seqIds <- n.getLastSequenceIds
        yield Map(t.versionedStoreName -> seqId) ++ seqIds
