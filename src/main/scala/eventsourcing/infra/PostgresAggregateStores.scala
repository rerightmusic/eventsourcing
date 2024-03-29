package eventsourcing.infra

import eventsourcing.domain.Aggregate
import eventsourcing.domain.AggregateStore
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.types.*
import shared.json.all.{given, *}
import postgresOperations.*
import cats.data.NonEmptyList
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
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
import shared.uuid.all.*
import zio.ZIO
import shared.postgres.schemaless.PostgresDocument
import zio.Duration

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
    tId: zio.Tag[Id],
    tDomMeta: zio.Tag[DomMeta],
    tDomEventData: zio.Tag[DomEventData],
    tMap: zio.Tag[Map[Id, Schemaless[Id, DomMeta, DomAgg]]],
    tAggs: zio.Tag[DomAgg *: EmptyTuple]
  ): zio.ZLayer[WithTransactor[Name], Throwable, WithTransactor[
    Name
  ] & AggregateStore[Id, DomMeta, DomEventData] & AggregateViewStore.Schemaless[Id, DomMeta, DomAgg, Id, DomAgg *: EmptyTuple]] =
    ZLayer.environment[WithTransactor[Name]] >+>
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
    toEventData: (ev: DomEventData) => Either[Throwable, EventData],
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
    tName: zio.Tag[Name],
    tId: zio.Tag[Id],
    tDomMeta: zio.Tag[DomMeta],
    tDomEventData: zio.Tag[DomEventData],
    tStore: zio.Tag[AggregateStore[
      Id,
      DomMeta,
      DomEventData
    ]]
  ): ZLayer[WithTransactor[Name], Throwable, AggregateStore[
    Id,
    DomMeta,
    DomEventData
  ]] =
    ZLayer.fromZIO[WithTransactor[Name], Throwable, AggregateStore[
      Id,
      DomMeta,
      DomEventData
    ]](
      for transactor <- WithTransactor.transactor[Name]
      yield new AggregateStore[Id, DomMeta, DomEventData]:
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
          )} WHERE schema_version = ${agg.schemaVersion} AND schema_version = ${agg.schemaVersion} AND deleted = false AND ${rangeFragment(
            from,
            to
          )} ORDER BY sequence_id""")
            .query[ReadEventPostgresDocument[Id, Meta, EventData]]
            .stream
            .chunkN(5000)
            .transact[Task](transactor)
            .evalMap(_.traverse(x => ZIO.fromEither(docToEvent(x))))

        def streamEventsForIdsFrom(
          from: Option[SequenceId],
          to: Option[SequenceId],
          ids: NonEmptyList[Id]
        ) = (sql"""SELECT ${readCols.frPGDocStar} FROM ${Fragment.const(
          tableName
        )} WHERE schema_version = ${agg.schemaVersion} AND schema_version = ${agg.schemaVersion} AND deleted = false AND ${Filter
          .textFieldInArray(
            "id",
            ids.map(ex2.from(_).toString).toList
          )
          .fragment} AND ${rangeFragment(from, to)} ORDER BY sequence_id""")
          .query[ReadEventPostgresDocument[Id, Meta, EventData]]
          .stream
          .chunkN(5000)
          .transact[Task](transactor)
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
                  docs <- evs.zipWithIndex.traverse((ev, idx) =>
                    for evData <- ZIO.fromEither(toEventData(ev._4))
                    yield WriteEventPostgresDocument(
                      ev._1,
                      toMeta(ev._2),
                      ev._3,
                      now,
                      nextVersion + idx,
                      agg.schemaVersion,
                      false,
                      evData
                    )
                  )
                yield docs
              )
              .map(_.flatten)
            _ <- (for
              _ <- sql"""LOCK TABLE ${Fragment.const(
                tableName
              )} IN exclusive mode""".update.run
              _ <- insertInto(tableName, docs)
            yield ()).transact[Task](transactor)
          yield ()

        def persistEventsWithTransaction[A](
          events: NonEmptyList[Event[Id, DomMeta, DomEventData]],
          f: (persist: () => ConnectionIO[Unit]) => ConnectionIO[A]
        ) =
          for
            events_ <- events.traverse(ev =>
              for evData <- ZIO.fromEither(toEventData(ev.data))
              yield WriteEventPostgresDocument(
                ev.id,
                toMeta(ev.meta),
                ev.createdBy,
                ev.created,
                ev.version,
                ev.schemaVersion,
                ev.deleted,
                evData
              )
            )
            res <- f(() =>
              for
                _ <- sql"""LOCK TABLE ${Fragment.const(
                  tableName
                )} IN exclusive mode""".update.run
                _ <- insertInto(
                  tableName,
                  events_
                )
              yield ()
            )
              .transact[Task](transactor)
          yield res

        def getNextVersion(id: Id) =
          (sql"""SELECT MAX(version) FROM ${Fragment.const(
            tableName
          )} WHERE schema_version = ${agg.schemaVersion} AND deleted = false AND id = ${id}""")
            .query[Option[Int]]
            .unique
            .transact[Task](transactor)
            .map(_.fold(1)(x => x + 1))

        def getLastSequenceId =
          (sql"""SELECT MAX(sequence_id) FROM ${Fragment.const(
            tableName
          )} WHERE schema_version = ${agg.schemaVersion} AND deleted = false""")
            .query[Option[Int]]
            .unique
            .transact[Task](transactor)
            .map(_.fold(SequenceId(0))(x => SequenceId(x)))

        def docToEvent(
          doc: ReadEventPostgresDocument[Id, Meta, EventData]
        ) = for data <- fromEventData(doc.id, doc.data)
        yield Event(
          sequenceId = doc.sequenceId,
          id = doc.id,
          meta = fromMeta(doc.meta),
          createdBy = doc.createdBy,
          created = doc.created,
          version = doc.version,
          schemaVersion = doc.schemaVersion,
          deleted = doc.deleted,
          data = data
        )
    )
