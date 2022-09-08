package eventsourcing.infra

import eventsourcing.domain.types as D
import eventsourcing.domain.Aggregate
import eventsourcing.domain.AggregateService
import eventsourcing.domain.AggregateViewService
import doobie.util.Put
import org.tpolecat.typename.TypeName
import shared.newtypes.NewExtractor
import shared.uuid.all.*
import shared.json.all.*
import izumi.reflect.Tag
import shared.postgres.schemaless.PostgresDocument
import zio.ZLayer
import zio.clock.Clock
import zio.blocking.Blocking
import cats.data.NonEmptyList
import zio.duration.Duration
import zio.Task

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
    tId: Tag[Id],
    tMeta: Tag[DomMeta],
    tEv: Tag[DomEventData],
    tAgg: Tag[DomAgg],
    tName: Tag[Name]
  ) =
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
    ) ++ ZLayer
      .identity[Clock & Blocking] >+> AggregateService
      .live[DomAgg] >+> AggregateViewService
      .live[DomAgg, Map[Id, D.Schemaless[Id, DomMeta, DomAgg]], NonEmptyList[
        Id
      ], DomAgg *: EmptyTuple]
