package eventsourcing.infra

import eventsourcing.domain.types as D
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateView
import eventsourcing.domain.AggregateViewClass
import eventsourcing.domain.generics.Unwrap
import cats.data.NonEmptyList
import doobie.*
import zio.ZLayer
import shared.postgres.all.*
import shared.newtypes.NewExtractor
import java.util.UUID
import shared.json.all.*
import zio.Duration

trait PostgresSchemalessAggregateViewService:
  def schemaless[
    Name <: String,
    Meta,
    DomMeta,
    Data,
    DomData
  ](using
    aggView: AggregateViewClass[DomData],
    aggs: AggregateViewStores[aggView.Aggregates],
    unId: Unwrap[
      aggView.ActualView,
      [x] =>> Map[x, D.Schemaless[x, DomMeta, DomData]]
    ],
    unQId: Unwrap[aggView.Query, NonEmptyList],
    ex: NewExtractor.Aux[unId.Wrapped, UUID],
    aggViewIns: AggregateView.Schemaless[
      DomData,
      unId.Wrapped,
      DomMeta,
      unQId.Wrapped,
      aggView.Aggregates
    ],
    cdMeta: JsonCodec[Meta],
    cd: JsonCodec[Data],
    tSchemaless: zio.Tag[
      D.Schemaless[unId.Wrapped, DomMeta, DomData]
    ],
    tDomData: zio.Tag[DomData],
    tAggs: zio.Tag[aggView.Aggregates],
    tName: zio.Tag[Name],
    tStore: zio.Tag[AggregateViewStore.Schemaless[
      unId.Wrapped,
      DomMeta,
      DomData,
      unQId.Wrapped,
      aggView.Aggregates
    ]]
  )(
    fromMeta: (meta: Meta) => DomMeta,
    toMeta: (ev: DomMeta) => Meta,
    fromData: (
      ev: PostgresDocument[unId.Wrapped, Meta, Data]
    ) => Either[Throwable, DomData],
    toData: (ev: DomData) => Either[Throwable, Data],
    readState: (
      b: PostgresAggregateViewStore.ReadState[
        unId.Wrapped,
        Meta,
        Data,
        unQId.Wrapped,
        DomMeta,
        DomData
      ]
    ) => Option[NonEmptyList[unQId.Wrapped]] => ConnectionIO[
      Option[Map[unId.Wrapped, PostgresDocument[unId.Wrapped, Meta, Data]]]
    ],
    queryIdToIds: (id: unQId.Wrapped) => aggs.AggsId,
    schema: String,
    catchUpTimeout: Duration
  ): zio.ZLayer[WithTransactor[
    Name
  ] & aggs.Stores, Throwable, AggregateViewStore.Schemaless[
    unId.Wrapped,
    DomMeta,
    DomData,
    unQId.Wrapped,
    aggView.Aggregates
  ] & AggregateViewService[DomData]] =
    type Id = unId.Wrapped
    type QueryId = unQId.Wrapped
    type Aggregates = aggView.Aggregates
    PostgresAggregateViewStore.schemaless[Name, Meta, DomMeta, Data, DomData](
      fromMeta,
      toMeta,
      fromData,
      toData,
      readState,
      queryIdToIds,
      schema,
      catchUpTimeout
    ) >+> AggregateViewService.live[DomData, Map[
      Id,
      D.Schemaless[Id, DomMeta, DomData]
    ], NonEmptyList[
      QueryId
    ], Aggregates]
