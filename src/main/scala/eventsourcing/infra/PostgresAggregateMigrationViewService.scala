package eventsourcing.infra

import eventsourcing.domain.types as D
import eventsourcing.domain.{
  Aggregate,
  AggregateStore,
  AggregateViewStore,
  AggregateMigration,
  AggregateViewService
}
import zio.Duration
import shared.postgres.all.*

object PostgresAggregateMigrationViewService:
  def live[
    Name <: String,
    Agg
  ](using
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
    ],
    tName: zio.Tag[Name],
    tAgg: zio.Tag[Agg],
    tMigStore: zio.Tag[
      AggregateStore[
        mig.Id,
        mig.MigratedMeta,
        mig.MigratedEventData
      ]
    ],
    tAggStore: zio.Tag[
      AggregateStore[
        mig.Id,
        mig.Meta,
        mig.EventData
      ]
    ],
    tStore: zio.Tag[AggregateViewStore[
      List[D.Event[mig.Id, mig.Meta, mig.EventData]],
      Unit,
      mig.MigratedAgg *: EmptyTuple
    ]]
  )(
    schema: String,
    catchUpTimeout: Duration
  ): zio.ZLayer[
    WithTransactor[
      Name
    ] &
      AggregateStore[
        mig.Id,
        mig.Meta,
        mig.EventData
      ] &
      AggregateStore[
        mig.Id,
        mig.MigratedMeta,
        mig.MigratedEventData
      ],
    Throwable,
    AggregateViewStore[
      List[D.Event[mig.Id, mig.Meta, mig.EventData]],
      Unit,
      mig.MigratedAgg *: EmptyTuple
    ] & AggregateViewService[AggregateMigration[Agg]]
  ] =
    type Id = mig.Id
    type QueryId = Unit
    type Aggregates = mig.MigratedAgg *: EmptyTuple
    PostgresMigrationAggregateViewStore.live[Name, Agg](
      schema,
      catchUpTimeout
    ) >+> AggregateViewService.live[AggregateMigration[Agg], List[
      D.Event[mig.Id, mig.Meta, mig.EventData],
    ], Unit, mig.MigratedAgg *: EmptyTuple]
