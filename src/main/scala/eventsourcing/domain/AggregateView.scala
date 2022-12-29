package eventsourcing.domain

import generics.*
import cats.data.NonEmptyList
import types.*
import cats.syntax.all.*
import zio.ZIO
trait AggregateView[View]:
  type Query
  type Aggregates <: NonEmptyTuple

  val schemaVersion: Int
  val storeName: String
  lazy val versionedStoreName = s"${storeName}_v${schemaVersion}"
  def getQuery: (evs: NonEmptyList[AggregateViewEvent[Aggregates]]) => Query
  def aggregate: (
    state: Option[View],
    ev: AggregateViewEvent[Aggregates]
  ) => Either[AggregateViewError, View]

object AggregateView:
  def apply[View](using
    view: AggregateViewClass[View],
    tSvc: zio.Tag[
      AggregateViewService.Aux[
        View,
        view.ActualView,
        view.Query,
        view.Aggregates
      ]
    ],
    tStore: zio.Tag[
      AggregateViewStore[view.ActualView, view.Query, view.Aggregates]
    ]
  ) = AggregateViewService
    .ServiceOps[View, view.ActualView, view.Query, view.Aggregates]

  type Aux[View, Query_, Aggregates_ <: NonEmptyTuple] =
    AggregateView[View] {
      type Query = Query_
      type Aggregates = Aggregates_
    }

  type Fold[View, Aggregates <: NonEmptyTuple] =
    AggregateView.Aux[
      View,
      Unit,
      Aggregates
    ]

  def fold[View, Aggregates_ <: NonEmptyTuple](
    _storeName: String,
    _schemaVersion: Int,
    _aggregate: (
      state: Option[View],
      event: AggregateViewEvent[Aggregates_]
    ) => Either[AggregateViewError, View]
  ): Fold[View, Aggregates_] =
    new AggregateView[View]:
      type Query = Unit
      type Aggregates = Aggregates_

      val storeName = _storeName
      val schemaVersion = _schemaVersion
      def getQuery = _ => ()
      def aggregate = _aggregate

  type Schemaless[Data, Id, Meta, QueryId, Aggregates <: NonEmptyTuple] =
    AggregateView.Aux[
      Map[Id, types.Schemaless[Id, Meta, Data]],
      NonEmptyList[QueryId],
      Aggregates
    ]

  def schemaless[Data, Id_, Meta_, QueryId_, Aggregates_ <: NonEmptyTuple](
    _storeName: String,
    _schemaVersion: Int,
    _getQuery: (
      evs: NonEmptyList[AggregateViewEvent[Aggregates_]]
    ) => NonEmptyList[QueryId_],
    _aggregate: (
      state: Option[Map[Id_, types.Schemaless[Id_, Meta_, Data]]],
      event: AggregateViewEvent[Aggregates_]
    ) => Either[
      AggregateViewError,
      Map[Id_, types.Schemaless[Id_, Meta_, Data]]
    ]
  ): Schemaless[Data, Id_, Meta_, QueryId_, Aggregates_] =
    new AggregateView[Map[Id_, types.Schemaless[Id_, Meta_, Data]]]:
      type Query = NonEmptyList[QueryId_]
      type Aggregates = Aggregates_

      val storeName = _storeName
      val schemaVersion = _schemaVersion
      def getQuery = _getQuery
      def aggregate = _aggregate

  given aggToView[Agg, Id, Meta, EventData](using
    agg: Aggregate.Aux[Agg, Id, Meta, EventData, ?]
  ): AggregateView.Aux[Map[Id, types.Schemaless[Id, Meta, Agg]], NonEmptyList[
    Id
  ], Agg *: EmptyTuple] =
    new AggregateView[Map[Id, types.Schemaless[Id, Meta, Agg]]]:
      type Query = NonEmptyList[Id]
      type Aggregates = Agg *: EmptyTuple
      val storeName = s"${agg.storeName}_view"
      val schemaVersion = agg.schemaVersion
      def getQuery = _.map(_.event.id.asInstanceOf[Id])
      def aggregate = (state, b) =>
        val state_ = state.getOrElse(Map.empty)
        b.on[Agg](ev =>
          val st = state_.get(ev.id)
          agg
            .aggregate(st.map(_.data), ev)
            .bimap(
              err =>
                AggregateViewError.InvalidAggregateView(
                  st,
                  ev,
                  agg.versionedStoreName,
                  err.msg
                ),
              v =>
                state_.updated(
                  ev.id,
                  agg.defaultView(
                    Schemaless(
                      id = ev.id,
                      meta = ev.meta,
                      createdBy = st.map(_.createdBy).getOrElse(ev.createdBy),
                      lastUpdatedBy = ev.createdBy,
                      deleted = false,
                      created = st.map(_.created).getOrElse(ev.created),
                      lastUpdated = ev.created,
                      data = v
                    ),
                    ev
                  )
                )
            )
        )

  given aggMigrationToView[Agg](using
    mig: AggregateMigration[Agg],
    migratedAgg: Aggregate.Aux[
      mig.MigratedAgg,
      mig.Id,
      mig.MigratedMeta,
      mig.MigratedEventData,
      ?
    ],
    agg: Aggregate.Aux[
      Agg,
      mig.Id,
      mig.Meta,
      mig.EventData,
      ?
    ]
  ): AggregateView.Aux[List[
    Event[mig.Id, mig.Meta, mig.EventData]
  ], Unit, mig.MigratedAgg *: EmptyTuple] =
    new AggregateView[List[Event[mig.Id, mig.Meta, mig.EventData]]]:
      type Query = Unit
      type Aggregates = mig.MigratedAgg *: EmptyTuple
      val storeName = agg.storeName
      val schemaVersion = agg.schemaVersion
      def getQuery = _ => ()
      def aggregate = (state, b) =>
        if migratedAgg.schemaVersion != agg.schemaVersion - 1 then
          Left(
            AggregateViewError.RunAggregateViewError(
              s"Incorrect schema version of migrated: ${migratedAgg.versionedStoreName} and agg: ${agg.versionedStoreName}",
              agg.versionedStoreName
            )
          )
        val state_ = state.getOrElse(List.empty)
        b.on[mig.MigratedAgg](ev => Right(state_ ++ mig.migrate_(ev)))

trait AggregateViewClass[View]:
  type ActualView
  type Query
  type Aggregates <: NonEmptyTuple
  given instance: AggregateView.Aux[ActualView, Query, Aggregates]
object AggregateViewClass:
  type Aux[View, ActualView_, Query_, Aggregates_ <: NonEmptyTuple] =
    AggregateViewClass[View] {
      type ActualView = ActualView_
      type Query = Query_
      type Aggregates = Aggregates_
    }

  given view[View, Query_, Aggregates_ <: NonEmptyTuple](using
    ins: AggregateView.Aux[View, Query_, Aggregates_]
  ): AggregateViewClass.Aux[View, View, Query_, Aggregates_] =
    new AggregateViewClass[View]:
      type ActualView = View
      type Query = Query_
      type Aggregates = Aggregates_
      val instance = ins

  given view2[Id, Meta, View, QueryId, Aggregates_ <: NonEmptyTuple](using
    ins: AggregateView.Aux[Map[
      Id,
      Schemaless[Id, Meta, View]
    ], NonEmptyList[QueryId], Aggregates_]
  ): AggregateViewClass.Aux[View, Map[
    Id,
    Schemaless[Id, Meta, View]
  ], NonEmptyList[QueryId], Aggregates_] =
    new AggregateViewClass[View]:
      type ActualView = Map[Id, Schemaless[Id, Meta, View]]
      type Query = NonEmptyList[QueryId]
      type Aggregates = Aggregates_
      val instance = ins

  given migrationView[Agg](using
    mig: AggregateMigration[Agg],
    migratedAgg: Aggregate.Aux[
      mig.MigratedAgg,
      mig.Id,
      mig.MigratedMeta,
      mig.MigratedEventData,
      ?
    ],
    agg: Aggregate.Aux[
      Agg,
      mig.Id,
      mig.Meta,
      mig.EventData,
      ?
    ]
  ): AggregateViewClass.Aux[AggregateMigration[Agg], List[
    Event[mig.Id, mig.Meta, mig.EventData]
  ], Unit, mig.MigratedAgg *: EmptyTuple] =
    new AggregateViewClass[AggregateMigration[Agg]]:
      type ActualView = List[Event[mig.Id, mig.Meta, mig.EventData]]
      type Query = Unit
      type Aggregates = mig.MigratedAgg *: EmptyTuple
      val instance = AggregateView.aggMigrationToView[Agg]
