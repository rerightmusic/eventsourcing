package eventsourcing.infra

import eventsourcing.domain.types as D
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateView
import shared.json.all.{*, given}
import cats.data.NonEmptyList
import doobie.util.update.Update
import doobie.free.connection.{raiseError, pure}
import zio.Task
import doobie.*
import doobie.implicits.*
import cats.syntax.all.*
import zio.interop.catz.*
import fs2.Chunk
import zio.ZIO
import zio.{ZEnv, Has}
import zio.RIO
import zio.ZLayer
import shared.data.all.*
import shared.postgres.all.{*, given}
import shared.newtypes.NewExtractor
import java.util.UUID
import zio.clock.Clock
import zio.duration.durationInt
import izumi.reflect.Tag
import java.time.OffsetDateTime
import zio.logging.Logger
import fs2.Stream
import shared.logging.all.Logging
import cats.arrow.FunctionK
import eventsourcing.domain.AggregateViewClass
import eventsourcing.domain.generics.Unwrap
import zio.duration.Duration

trait PostgresSchemalessAggregateViewStore:
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
    toData: (ev: DomData) => Either[Throwable, Data],
    readState: (
      b: ReadState[unId.Wrapped, Meta, Data, unQId.Wrapped, DomMeta, DomData]
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
    ZLayer
      .fromFunction[
        Has[WithTransactor[Name]] & Logging & aggs.Stores & Clock,
        AggregateViewStore.SchemalessService[
          Id,
          DomMeta,
          DomData,
          QueryId,
          Aggregates
        ]
      ](env =>
        new AggregateViewStore.Service[Map[
          Id,
          D.Schemaless[Id, DomMeta, DomData]
        ], NonEmptyList[
          QueryId
        ], Aggregates]
          with PostgresAggregateViewCoreStore[
            Name,
            Map[Id, D.Schemaless[Id, DomMeta, DomData]],
            NonEmptyList[QueryId],
            Aggregates,
            aggs.AggsId
          ](
            schema,
            aggs,
            env,
            catchUpTimeout
          ):

          def queryToIds(query: NonEmptyList[QueryId]) =
            Some(query.map(queryIdToIds))

          def countAggregateViewM =
            sql"""SELECT count(*) from ${Fragment
              .const(tableName)}""".query[Int].unique

          def readAggregateViewM(query: Option[NonEmptyList[QueryId]]) =
            val res = readState(new ReadState(tableName))(query)
            res.map(
              _.traverse(
                _.toList
                  .traverse(v =>
                    for value <- toSchemaless(v._2)
                    yield v._1 -> value
                  )
                  .map(_.toMap)
              )
            )

          def persistAggregateViewM(
            view: Map[Id, D.Schemaless[Id, DomMeta, DomData]]
          ) = view.values.toList.toNel.fold(pure(()))(view_ =>
            for
              ups <- view_
                .traverse(v =>
                  for
                    data <- toData(v.data) match
                      case Left(e)  => raiseError(e)
                      case Right(r) => pure(r)
                  yield PostgresDocument(
                    id = v.id,
                    meta = toMeta(v.meta),
                    createdBy = v.createdBy,
                    lastUpdatedBy = v.lastUpdatedBy,
                    data = data,
                    deleted = v.deleted,
                    created = v.created,
                    lastUpdated = v.lastUpdated
                  )
                )
              _ <- upsertInto(tableName, ups)
            yield ()
          )

          def toSchemaless(v: PostgresDocument[Id, Meta, Data]) = for
            d <- fromData(v)
          yield D.Schemaless(
            id = v.id,
            meta = fromMeta(v.meta),
            createdBy = v.createdBy,
            lastUpdatedBy = v.lastUpdatedBy,
            data = d,
            deleted = v.deleted,
            created = v.created,
            lastUpdated = v.lastUpdated
          )
      )

  class ReadState[Id, Meta, Data, QueryId, DomMeta, DomData](tableName: String)(
    using
    view: AggregateView[Map[Id, D.Schemaless[Id, DomMeta, DomData]]],
    ex: NewExtractor.Aux[Id, UUID],
    meta: JsonDecoder[Meta],
    data: JsonDecoder[Data]
  ):
    def defaultReadState(using
      eq: QueryId =:= Id
    ): (ids: Option[NonEmptyList[Id]]) => ConnectionIO[
      Option[Map[Id, PostgresDocument[Id, Meta, Data]]]
    ] =
      (ids: Option[NonEmptyList[Id]]) =>
        ids
          .fold(selectAll[Id, Meta, Data](tableName))(ids_ =>
            trySelectByIds[Id, Meta, Data](
              tableName,
              ids_
            )
          )
          .map(docs => Some(docs.map(d => d.id -> d).toMap))

    def readFromIdOrData(
      getDataField: String,
      idOrDataFieldValue: QueryId => Either[Id, String]
    ): (ids: Option[NonEmptyList[QueryId]]) => ConnectionIO[
      Option[Map[Id, PostgresDocument[Id, Meta, Data]]]
    ] =
      (query: Option[NonEmptyList[QueryId]]) =>
        query
          .fold(selectAll[Id, Meta, Data](tableName))(query_ =>
            val (ids, dataFieldValues) =
              query_.map(idOrDataFieldValue).toList.partitionEither(a => a)
            select[Id, Meta, Data](tableName)
              .where(
                ids.toNel
                  .map(_ =>
                    Filter
                      .textFieldInArray("id", ids.map(ex.from(_).toString))
                  )
                  .orFalse
                  .or(
                    dataFieldValues.toNel
                      .map(_ =>
                        Filter.jsonValueInArray(getDataField, dataFieldValues)
                      )
                      .orFalse
                  )
              )
              .query
          )
          .map(docs => Some(docs.map(d => d.id -> d).toMap))

    def readFromData(
      getFieldAndValue: QueryId => (String, String)
    ): (ids: Option[NonEmptyList[QueryId]]) => ConnectionIO[
      Option[Map[Id, PostgresDocument[Id, Meta, Data]]]
    ] =
      (query: Option[NonEmptyList[QueryId]]) =>
        query
          .fold(selectAll[Id, Meta, Data](tableName))(query_ =>
            val filters = query_
              .map(getFieldAndValue)
              .toList
              .groupBy(_._1)
              .view.mapValues(_.map(_._2))
              .toList
              .foldLeft[FilterPredicate](Filter._false)((prev, next) =>
                prev.or(
                  next._2.toNel
                    .map(_ => Filter.jsonValueInArray(next._1, next._2))
                    .orFalse
                )
              )

            select[Id, Meta, Data](tableName)
              .where(filters)
              .query
          )
          .map(docs => Some(docs.map(d => d.id -> d).toMap))
