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
import zio.duration.Duration

trait PostgresFoldAggregateViewStore:
  def fold[
    Name <: String,
    View,
    DomView
  ](using
    aggView: AggregateViewClass[DomView],
    aggViewIns: AggregateView.Fold[DomView, aggView.Aggregates],
    aggs: AggregateViewStores[aggView.Aggregates],
    tAggs: Tag[aggView.Aggregates],
    cd: JsonCodec[View],
    tName: Tag[Name],
    tDomView: Tag[DomView],
    tt: Tag[AggregateViewStore.Service[DomView, Unit, aggView.Aggregates]]
  )(
    fromView: (ev: View) => Either[Throwable, DomView],
    toView: (ev: DomView) => View,
    schema: String,
    catchUpTimeout: Duration
  ) = ZLayer.fromFunction[
    Has[WithTransactor[Name]] & Logging & aggs.Stores & Clock,
    AggregateViewStore.Service[DomView, Unit, aggView.Aggregates]
  ](env =>
    new AggregateViewStore.Service[DomView, Unit, aggView.Aggregates]
      with PostgresAggregateViewCoreStore[
        Name,
        DomView,
        Unit,
        aggView.Aggregates,
        aggs.AggsId
      ](
        schema,
        aggs,
        env,
        catchUpTimeout
      ):

      def queryToIds(query: Unit) = None

      def countAggregateViewM =
        sql"""SELECT count(*) from ${Fragment
          .const(tableName)}""".query[Int].option.map(_.getOrElse(0))

      def readAggregateViewM(query: Option[Unit]) =
        sql"""select data from ${Fragment
          .const(tableName)}"""
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

      def persistAggregateViewM(
        view: DomView
      ) =
        (sql"""INSERT INTO ${Fragment.const(
          tableName
        )} (name, data) VALUES (${aggViewIns.storeName}, ${toView(
          view
        ).toJsonASTOrFail}) """ ++
          sql"""ON CONFLICT (name) DO UPDATE SET data = EXCLUDED.data""").update.run
          .map(_ => ())
  )
