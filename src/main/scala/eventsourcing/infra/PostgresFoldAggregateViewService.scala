package eventsourcing.infra

import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateView
import eventsourcing.domain.AggregateViewClass
import zio.ZLayer
import shared.postgres.doobie.WithTransactor
import zio.Duration
import shared.json.all.*

trait PostgresFoldAggregateViewService:
  def fold[
    Name <: String,
    View,
    DomView
  ](using
    aggView: AggregateViewClass[DomView],
    aggViewIns: AggregateView.Fold[DomView, aggView.Aggregates],
    aggs: AggregateViewStores[aggView.Aggregates],
    cd: JsonCodec[View],
    tDomView: zio.Tag[DomView],
    tAggs: zio.Tag[aggView.Aggregates],
    tName: zio.Tag[Name],
    tStore: zio.Tag[AggregateViewStore.Fold[DomView, aggView.Aggregates]]
  )(
    fromView: (ev: View) => Either[Throwable, DomView],
    toView: (ev: DomView) => View,
    schema: String,
    catchUpTimeout: Duration
  ): ZLayer[WithTransactor[Name] & aggs.Stores, Throwable, AggregateViewStore[
    DomView,
    Unit,
    aggView.Aggregates
  ] & AggregateViewService[DomView]] =
    PostgresAggregateViewStore.fold[Name, View, DomView](
      fromView,
      toView,
      schema,
      catchUpTimeout
    ) >+> AggregateViewService
      .live[DomView, DomView, Unit, aggView.Aggregates]
