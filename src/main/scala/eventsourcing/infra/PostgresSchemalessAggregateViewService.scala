package eventsourcing.infra

import eventsourcing.domain.types as D
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateView
import eventsourcing.domain.AggregateViewClass
import eventsourcing.domain.generics.Unwrap
import cats.data.NonEmptyList
import doobie.*
import zio.{ZEnv, Has}
import zio.ZLayer
import shared.postgres.all.{*, given}
import shared.newtypes.NewExtractor
import java.util.UUID
import izumi.reflect.Tag
import shared.logging.all.Logging
import shared.json.all.*
import zio.duration.Duration

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
    tName: Tag[Name],
    tMeta: Tag[DomMeta],
    tData: Tag[DomData],
    tt: Tag[AggregateViewStore.SchemalessService[
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
    toData: (ev: DomData) => Data,
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
  ) =
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
    ) ++ ZLayer
      .requires[Logging & ZEnv] >+> AggregateViewService.live[DomData, Map[
      Id,
      D.Schemaless[Id, DomMeta, DomData]
    ], NonEmptyList[
      QueryId
    ], Aggregates]
