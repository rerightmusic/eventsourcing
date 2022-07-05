package eventsourcing.infra

import eventsourcing.domain.Aggregate
import eventsourcing.domain.AggregateView
import eventsourcing.domain.AggregateStore
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.types.*
import shared.json.all.{given, *}
import postgresOperations.*
import cats.data.NonEmptyList
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.*
import doobie.util.Put
import doobie.util.fragment.Fragment
import org.tpolecat.typename.TypeName
import shared.newtypes.NewExtractor
import shared.postgres.doobie.WithTransactor
import shared.postgres.schemaless.operations.Filter
import shared.principals.PrincipalId
import shared.time.all.*
import zio.Task
import zio.ZLayer
import zio.interop.catz.*
import zio.logging.{Logger, Logging}

import shared.uuid.all.*
import scala.concurrent.duration.DurationInt

import izumi.reflect.Tag
import zio.{Has, ZIO, ZEnv}
import zio.clock.Clock
import zio.blocking.Blocking
import shared.postgres.schemaless.PostgresDocument
import cats.arrow.FunctionK
import zio.RIO
import scala.concurrent.duration.FiniteDuration
import fs2.{Stream, Pipe}
import fs2.Stream._
import org.postgresql.PGNotification
import eventsourcing.domain
import zio.duration.Duration

object PostgresAggregateStores:
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
    toAgg: (ev: DomAgg) => Agg,
    toMeta: (meta: DomMeta) => Meta,
    toEventData: (ev: DomEventData) => EventData,
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
    ZLayer.identity[Has[WithTransactor[Name]] & Logging & ZEnv] >+>
      aggregateStore[
        Name,
        DomAgg,
        Id,
        Meta,
        EventData,
        DomMeta,
        DomEventData
      ](fromMeta, fromEventData, toMeta, toEventData, schema) >+>
      PostgresAggregateViewStore
        .schemaless[Name, Meta, DomMeta, Agg, DomAgg](
          fromMeta,
          toMeta,
          fromAgg,
          toAgg,
          s => s.defaultReadState,
          i => Some(i) *: EmptyTuple,
          schema,
          catchUpTimeout
        )

  def aggregateStore[
    Name <: String,
    Agg,
    Id,
    Meta,
    EventData,
    DomMeta,
    DomEventData
  ](
    fromMeta: (meta: Meta) => DomMeta,
    fromEventData: (
      id: Id,
      ev: EventData
    ) => Either[Throwable, DomEventData],
    toMeta: (meta: DomMeta) => Meta,
    toEventData: (ev: DomEventData) => EventData,
    schema: String
  )(using
    witness: ValueOf[Name],
    agg: Aggregate[Agg],
    id: Put[Id],
    ex: NewExtractor.Aux[SequenceId, Int],
    ex2: NewExtractor.Aux[Id, UUID],
    dec: JsonDecoder[Meta],
    en: JsonEncoder[Meta],
    tt: TypeName[Meta],
    en2: JsonEncoder[EventData],
    dec2: JsonDecoder[EventData],
    tt2: TypeName[EventData],
    tag: Tag[AggregateStore.Service[Id, DomMeta, DomEventData]],
    tag2: Tag[WithTransactor[Name]]
  ) = ZLayer
    .fromServices[WithTransactor[Name], Logger[
      String
    ], Clock.Service, Blocking.Service, AggregateStore.Service[
      Id,
      DomMeta,
      DomEventData
    ]]((txn, log, clock, blocking) =>
      new AggregateStore.Service[Id, DomMeta, DomEventData]:
        val getNotifyChannel =
          s"${schema}_${camelToUnderscores(agg.storeName)}"

        val tableName =
          schema + "." + camelToUnderscores(agg.storeName)

        def rangeFragment =
          (from: Option[SequenceId], to: Option[SequenceId]) =>
            val filterFrom =
              from.fold(fr0"true")(f => fr0"sequence_id >= ${f.value}")
            val filterTo =
              to.fold(fr0"true")(f => fr0"sequence_id <= ${f.value}")
            fr"${filterFrom} AND ${filterTo}"

        def streamEventsFrom(
          from: Option[SequenceId],
          to: Option[SequenceId]
        ) =
          (sql"""SELECT ${readCols.frPGDocStar} FROM ${Fragment.const(
            tableName
          )} WHERE deleted = false AND ${rangeFragment(
            from,
            to
          )} ORDER BY sequence_id""")
            .query[ReadPostgresDocument[Id, Meta, EventData]]
            .stream
            .chunkN(5000)
            .transact[Task](txn.transactor)
            .evalMap(_.traverse(x => ZIO.fromEither(docToEvent(x))))

        def streamEventsForIdsFrom(
          from: Option[SequenceId],
          to: Option[SequenceId],
          ids: NonEmptyList[Id]
        ) = (sql"""SELECT ${readCols.frPGDocStar} FROM ${Fragment.const(
          tableName
        )} WHERE deleted = false AND ${Filter
          .textFieldInArray(
            "id",
            ids.map(ex2.from(_).toString).toList
          )
          .fragment} AND ${rangeFragment(from, to)} ORDER BY sequence_id""")
          .query[ReadPostgresDocument[Id, Meta, EventData]]
          .stream
          .chunkN(5000)
          .transact[Task](txn.transactor)
          .evalMap(_.traverse(x => ZIO.fromEither(docToEvent(x))))

        def persistEventsForId(
          id: Id,
          meta: DomMeta,
          createdBy: PrincipalId,
          events: NonEmptyList[DomEventData]
        ) = persistEvents(events.map(ev => (id, meta, createdBy, ev)))

        def persistEvents(
          events: NonEmptyList[(Id, DomMeta, PrincipalId, DomEventData)]
        ) =
          for
            now <- now
            docs <- events.toList
              .groupBy(_._1)
              .toList
              .traverse((id, evs) =>
                for
                  nextVersion <- getNextVersion(id)
                  docs = evs.mapWithIndex((ev, idx) =>
                    WritePostgresDocument(
                      ev._1,
                      toMeta(ev._2),
                      ev._3,
                      now,
                      nextVersion + idx,
                      false,
                      toEventData(ev._4)
                    )
                  )
                yield docs
              )
              .map(_.flatten)
            _ <-
              insertInto(tableName, docs)
                .transact[Task](txn.transactor)
                .unit
          yield ()

        def getNextVersion(id: Id) =
          (sql"""SELECT MAX(version) FROM ${Fragment.const(
            tableName
          )} WHERE deleted = false AND id = ${id}""")
            .query[Option[Int]]
            .unique
            .transact[Task](txn.transactor)
            .map(_.fold(1)(x => x + 1))

        def getLastSequenceId =
          (sql"""SELECT MAX(sequence_id) FROM ${Fragment.const(
            tableName
          )} WHERE deleted = false""")
            .query[Option[Int]]
            .unique
            .transact[Task](txn.transactor)
            .map(_.fold(SequenceId(0))(x => SequenceId(x)))

        def docToEvent(
          doc: ReadPostgresDocument[Id, Meta, EventData]
        ) = for data <- fromEventData(doc.id, doc.data)
        yield Event(
          sequenceId = doc.sequenceId,
          id = doc.id,
          meta = fromMeta(doc.meta),
          createdBy = doc.createdBy,
          created = doc.created,
          version = doc.version,
          deleted = doc.deleted,
          data = data
        )
    )
