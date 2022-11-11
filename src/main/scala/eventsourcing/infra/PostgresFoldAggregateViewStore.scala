package eventsourcing.infra

import eventsourcing.domain.types as D
import eventsourcing.domain.AggregateViewStore
import eventsourcing.domain.AggregateViewService
import eventsourcing.domain.AggregateView
import shared.json.all.{*, given}
import cats.data.NonEmptyList
import doobie.util.update.Update
import doobie.free.connection.pure
import zio.Task
import doobie.*
import doobie.implicits.*
import cats.syntax.all.*
import shared.postgres.all.{given, *}
import zio.interop.catz.*
import fs2.Chunk
import zio.ZIO
import zio.RIO
import zio.ZLayer
import shared.data.all.*
import shared.postgres.all.{*, given}
import shared.newtypes.NewExtractor
import java.util.UUID
import java.time.OffsetDateTime
import fs2.Stream
import cats.arrow.FunctionK
import eventsourcing.domain.AggregateViewClass
import zio.Duration

trait PostgresFoldAggregateViewStore:
  def fold[
    Name <: String,
    View,
    DomView
  ](using
    aggView: AggregateViewClass[DomView],
    aggViewIns: AggregateView.Fold[DomView, aggView.Aggregates],
    aggs: AggregateViewStores[aggView.Aggregates],
    cd: JsonCodec[View],
    tDomView: zio.Tag[DomView],
    tName: zio.Tag[Name],
    tStore: zio.Tag[AggregateViewStore.Fold[DomView, aggView.Aggregates]]
  )(
    fromView: (ev: View) => Either[Throwable, DomView],
    toView: (ev: DomView) => View,
    schema: String,
    catchUpTimeout: Duration
  ): ZLayer[WithTransactor[Name] & aggs.Stores, Throwable, AggregateViewStore[
    DomView,
    Unit,
    aggView.Aggregates
  ]] = ZLayer.fromZIO[
    WithTransactor[Name] & aggs.Stores,
    Throwable,
    AggregateViewStore.Fold[DomView, aggView.Aggregates]
  ](
    for env <- ZIO.environment[WithTransactor[Name] & aggs.Stores]
    yield new AggregateViewStore[DomView, Unit, aggView.Aggregates]
      with PostgresAggregateViewCoreStore[
        Name,
        DomView,
        Unit,
        aggView.Aggregates,
        aggs.AggsId,
      ](
        schema,
        aggs,
        env,
        catchUpTimeout
      ):

      def queryToIds(query: Unit) = None

      def countAggregateViewM =
        sql"""SELECT count(*) from ${Fragment
          .const(
            tableName
          )} where schema_version = ${aggView.instance.schemaVersion}"""
          .query[Int]
          .option
          .map(_.getOrElse(0))

      def readAggregateViewM(query: Option[Unit]) =
        sql"""select data from ${Fragment
          .const(
            tableName
          )} where schema_version = ${aggView.instance.schemaVersion}"""
          .query[Json]
          .option
          .map(
            _.traverse(x =>
              x.as[View]
                .flatMap(fromView)
                .bimap(
                  err =>
                    new Exception(
                      s"Failed to parse ${tDomView.tag.longName} from $x, Error: ${err}"
                    ),
                  v => v
                )
            )
          )

      def readAggregateViewsM =
        readAggregateViewM(None).map(_.map(_.toList))

      def persistAggregateViewM(
        view: DomView
      ) =
        (sql"""INSERT INTO ${Fragment.const(
          tableName
        )} (schema_version, data) VALUES (${aggView.instance.schemaVersion}, ${toView(
          view
        ).toJsonASTOrFail}) """ ++
          sql"""ON CONFLICT (schema_version) DO UPDATE SET data = EXCLUDED.data""").update.run
          .map(_ => ())
  )
