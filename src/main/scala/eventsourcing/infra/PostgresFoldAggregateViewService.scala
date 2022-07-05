package eventsourcing.infra

import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateView
import eventsourcing.domain.AggregateViewClass
import zio.{ZEnv, Has}
import zio.ZLayer
import izumi.reflect.Tag
import shared.logging.all.Logging
import shared.postgres.doobie.WithTransactor
import zio.duration.Duration
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
    tAggs: Tag[aggView.Aggregates],
    cd: JsonCodec[View],
    tName: Tag[Name],
    tDomView: Tag[DomView],
    tt: Tag[AggregateViewStore.Service[DomView, Unit, aggView.Aggregates]]
  )(
    fromView: (ev: View) => Either[Throwable, DomView],
    toView: (ev: DomView) => View,
    schema: String,
    catchUpTimeout: Duration
  ) = PostgresAggregateViewStore.fold[Name, View, DomView](
    fromView,
    toView,
    schema,
    catchUpTimeout
  ) ++ ZLayer.requires[Logging & ZEnv] >+> AggregateViewService
    .live[DomView, DomView, Unit, aggView.Aggregates]
