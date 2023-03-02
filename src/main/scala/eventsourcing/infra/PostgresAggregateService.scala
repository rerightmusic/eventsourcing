package eventsourcing.infra

import eventsourcing.domain.types as D
import eventsourcing.domain.Aggregate
import eventsourcing.domain.AggregateService
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateViewStore
import doobie.util.Put
import org.tpolecat.typename.TypeName
import shared.newtypes.NewExtractor
import shared.uuid.all.*
import shared.json.all.*
import shared.postgres.schemaless.PostgresDocument
import zio.ZLayer
import cats.data.NonEmptyList
import zio.Duration
import eventsourcing.domain.AggregateStore
import shared.postgres.doobie.WithTransactor

object PostgresAggregateService:
  def live[
    Name <: String,
    Agg,
    Id,
    Meta,
    EventData,
    DomAgg,
    DomMeta,
    DomEventData
  ](
    fromAgg: (ev: PostgresDocument[Id, Meta, Agg]) => Either[Throwable, DomAgg],
    fromMeta: (meta: Meta) => DomMeta,
    fromEventData: (
      id: Id,
      ev: EventData
    ) => Either[Throwable, DomEventData],
    toAgg: (ev: DomAgg) => Either[Throwable, Agg],
    toMeta: (meta: DomMeta) => Meta,
    toEventData: (ev: DomEventData) => Either[Throwable, EventData],
    schema: String,
    catchUpTimeout: Duration
  )(using
    put: Put[Id],
    witness: ValueOf[Name],
    agg: Aggregate.Aux[DomAgg, Id, DomMeta, DomEventData, ?],
    ex2: NewExtractor.Aux[Id, UUID],
    codAgg: JsonCodec[Agg],
    codMeta: JsonCodec[Meta],
    ttMeta: TypeName[Meta],
    codEv: JsonCodec[EventData],
    ttEv: TypeName[EventData],
    tName: zio.Tag[Name],
    tDomAgg: zio.Tag[DomAgg],
    tId: zio.Tag[Id],
    tDomMeta: zio.Tag[DomMeta],
    tDomEventData: zio.Tag[DomEventData]
  ): ZLayer[
    WithTransactor[Name],
    Throwable,
    WithTransactor[Name] & AggregateStore[Id, DomMeta, DomEventData] &
      (AggregateViewStore.Schemaless[
        Id,
        DomMeta,
        DomAgg,
        Id,
        DomAgg *: EmptyTuple
      ] & AggregateService[DomAgg]) & AggregateViewService[DomAgg]
  ] =
    PostgresAggregateStores.live[
      Name,
      Agg,
      Id,
      Meta,
      EventData,
      DomAgg,
      DomMeta,
      DomEventData
    ](
      fromAgg,
      fromMeta,
      fromEventData,
      toAgg,
      toMeta,
      toEventData,
      schema,
      catchUpTimeout
    ) >+>
      AggregateService
        .live[DomAgg] >+>
      AggregateViewService
        .live[DomAgg, Map[
          Id,
          D.Schemaless[Id, DomMeta, DomAgg]
        ], NonEmptyList[
          Id
        ], DomAgg *: EmptyTuple]
