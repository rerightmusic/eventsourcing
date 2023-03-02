package eventsourcing.infra

import eventsourcing.domain.types as D
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.AggregateView
import shared.json.all.*
import cats.data.NonEmptyList
import doobie.free.connection.{raiseError, pure}
import doobie.*
import doobie.implicits.*
import cats.syntax.all.*
import zio.ZIO
import zio.ZLayer
import shared.postgres.all.{*, given}
import shared.newtypes.NewExtractor
import java.util.UUID
import eventsourcing.domain.AggregateViewClass
import eventsourcing.domain.generics.Unwrap
import zio.Duration

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
    tMap: zio.Tag[
      Map[unId.Wrapped, D.Schemaless[unId.Wrapped, DomMeta, DomData]]
    ],
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
      b: ReadState[unId.Wrapped, Meta, Data, unQId.Wrapped, DomMeta, DomData]
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
  ]] =
    ZLayer
      .fromZIO[
        WithTransactor[Name] & aggs.Stores,
        Throwable,
        AggregateViewStore.Schemaless[
          unId.Wrapped,
          DomMeta,
          DomData,
          unQId.Wrapped,
          aggView.Aggregates
        ]
      ](
        for env <- ZIO.environment[WithTransactor[Name] & aggs.Stores]
        yield new AggregateViewStore[Map[
          unId.Wrapped,
          D.Schemaless[unId.Wrapped, DomMeta, DomData]
        ], NonEmptyList[
          unQId.Wrapped
        ], aggView.Aggregates]
          with PostgresAggregateViewCoreStore[
            Name,
            Map[unId.Wrapped, D.Schemaless[unId.Wrapped, DomMeta, DomData]],
            NonEmptyList[unQId.Wrapped],
            aggView.Aggregates,
            aggs.AggsId,
          ](
            schema,
            aggs,
            env,
            catchUpTimeout
          ):

          def queryToIds(query: NonEmptyList[unQId.Wrapped]) =
            Some(query.map(queryIdToIds))

          def countAggregateViewM =
            sql"""SELECT count(*) from ${Fragment
                .const(
                  tableName
                )} where schema_version = ${aggViewIns.schemaVersion}"""
              .query[Int]
              .unique

          def readAggregateViewM(query: Option[NonEmptyList[unQId.Wrapped]]) =
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

          def readAggregateViewsM =
            selectAll[unId.Wrapped, Meta, Data](
              tableName,
              aggViewIns.schemaVersion
            ).map(docs =>
              docs
                .traverse(v =>
                  for value <- toSchemaless(v)
                  yield v.id -> value
                )
                .map(x => List(x.toMap))
            )

          def persistAggregateViewM(
            view: Map[
              unId.Wrapped,
              D.Schemaless[unId.Wrapped, DomMeta, DomData]
            ]
          ) =
            view.values.toList.toNel.fold(pure(()))(view_ =>
              view_
                .traverse(v =>
                  for
                    data <- toData(v.data) match
                      case Left(e)  => raiseError(e)
                      case Right(r) => pure(r)
                    doc = PostgresDocument(
                      id = v.id,
                      meta = toMeta(v.meta),
                      createdBy = v.createdBy,
                      lastUpdatedBy = v.lastUpdatedBy,
                      data = data,
                      schemaVersion = aggViewIns.schemaVersion,
                      deleted = v.deleted,
                      created = v.created,
                      lastUpdated = v.lastUpdated
                    )
                    _ <- upsertInto(tableName, doc)
                  yield ()
                )
                .map(_ => ())
            )

          def toSchemaless(
            v: PostgresDocument[unId.Wrapped, Meta, Data]
          ) = for d <- fromData(v)
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
          .fold(selectAll[Id, Meta, Data](tableName, view.schemaVersion))(
            ids_ =>
              trySelectByIds[Id, Meta, Data](
                tableName,
                view.schemaVersion,
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
          .fold(selectAll[Id, Meta, Data](tableName, view.schemaVersion))(
            query_ =>
              val (ids, dataFieldValues) =
                query_.map(idOrDataFieldValue).toList.partitionEither(a => a)
              select[Id, Meta, Data](tableName, view.schemaVersion)
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
          .fold(selectAll[Id, Meta, Data](tableName, view.schemaVersion))(
            query_ =>
              val filters = query_
                .map(getFieldAndValue)
                .toList
                .groupBy(_._1)
                .view
                .mapValues(_.map(_._2))
                .toList
                .foldLeft[FilterPredicate](Filter._false)((prev, next) =>
                  prev.or(
                    next._2.toNel
                      .map(_ => Filter.jsonValueInArray(next._1, next._2))
                      .orFalse
                  )
                )

              select[Id, Meta, Data](tableName, view.schemaVersion)
                .where(filters)
                .query
          )
          .map(docs => Some(docs.map(d => d.id -> d).toMap))
