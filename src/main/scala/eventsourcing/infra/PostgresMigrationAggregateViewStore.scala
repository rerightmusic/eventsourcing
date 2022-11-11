package eventsourcing.infra

import eventsourcing.domain.{
  AggregateViewStore,
  AggregateView,
  AggregateMigration,
  AggregateStore
}
import eventsourcing.domain.types as D
import eventsourcing.domain.Aggregate
import shared.postgres.all.{*, given}
import shared.data.all.*
import shared.time.all.*
import shared.health.HealthCheck
import zio.ZLayer
import zio.ZIO
import fs2.Stream
import cats.data.NonEmptyList
import zio.Duration
import doobie.*
import doobie.implicits.*
import cats.syntax.all.*
import doobie.free.connection.{raiseError, pure}
import cats.free.Free
import zio.Task
import fs2.Chunk
import shared.json.all.{*, given}
import cats.arrow.FunctionK
import zio.RIO
import zio.ZEnvironment
import scala.concurrent.duration.*
import zio.durationInt
import zio.interop.catz.*
import org.postgresql.util.PSQLException
import java.time.OffsetDateTime
import shared.health.*
import zio.duration2DurationOps
import zio.Schedule

object PostgresMigrationAggregateViewStore:
  def live[Name <: String, Agg](using
    mig: AggregateMigration[Agg],
    migratedAgg: Aggregate.Aux[
      mig.MigratedAgg,
      mig.Id,
      mig.MigratedMeta,
      mig.MigratedEventData,
      ?
    ],
    agg: Aggregate.Aux[
      Agg,
      mig.Id,
      mig.Meta,
      mig.EventData,
      ?
    ],
    tName: zio.Tag[Name],
    tMigStore: zio.Tag[
      AggregateStore[
        mig.Id,
        mig.MigratedMeta,
        mig.MigratedEventData
      ]
    ],
    tAggStore: zio.Tag[
      AggregateStore[
        mig.Id,
        mig.Meta,
        mig.EventData
      ]
    ],
    tStore: zio.Tag[AggregateViewStore[
      List[D.Event[mig.Id, mig.Meta, mig.EventData]],
      Unit,
      mig.MigratedAgg *: EmptyTuple
    ]]
  )(schema: String, catchUpTimeout: Duration) =
    val aggView = AggregateView.aggMigrationToView[Agg]
    ZLayer.fromZIO[
      WithTransactor[
        Name
      ] &
        AggregateStore[
          mig.Id,
          mig.Meta,
          mig.EventData
        ] &
        AggregateStore[
          mig.Id,
          mig.MigratedMeta,
          mig.MigratedEventData
        ],
      Throwable,
      AggregateViewStore[
        List[D.Event[mig.Id, mig.Meta, mig.EventData]],
        Unit,
        mig.MigratedAgg *: EmptyTuple
      ]
    ](
      for
        txn <- ZIO.service[WithTransactor[Name]]
        migratedStore <- ZIO.service[AggregateStore[
          mig.Id,
          mig.MigratedMeta,
          mig.MigratedEventData
        ]]
        aggStore <- ZIO.service[AggregateStore[
          mig.Id,
          mig.Meta,
          mig.EventData
        ]]
      yield new AggregateViewStore[List[
        D.Event[mig.Id, mig.Meta, mig.EventData]
      ], Unit, mig.MigratedAgg *: EmptyTuple] {
        val tableName = schema + "." + camelToUnderscores(aggView.storeName)
        val aggViewsTableName = schema + "." + "aggregate_views"

        def streamEventsFrom(
          query: Option[Unit],
          status: Option[D.AggregateViewStatus]
        ): Stream[Task, Chunk[
          D.AggregateViewEvent[mig.MigratedAgg *: EmptyTuple]
        ]] =
          val seqId = status
            .map(_.getSequenceId(aggView.versionedStoreName))
            .getOrElse(D.SequenceId(0))
          migratedStore
            .streamEventsFrom(Some(seqId), None)
            .translate(
              new FunctionK[RIO[AggregateStore[
                mig.Id,
                mig.MigratedMeta,
                mig.MigratedEventData
              ], *], Task]:
                def apply[A](
                  x: RIO[AggregateStore[
                    mig.Id,
                    mig.MigratedMeta,
                    mig.MigratedEventData
                  ], A]
                ) =
                  x.provideEnvironment(ZEnvironment(migratedStore))
            )
            .map(
              _.map(e =>
                D.AggregateViewEvent(
                  aggView.versionedStoreName,
                  e.asInstanceOf[D.Event[Any, Any, Any]]
                )
              )
            )

        def subscribeEventStream: fs2.Stream[Task, fs2.Chunk[
          D.AggregateViewEvent[mig.MigratedAgg *: EmptyTuple]
        ]] =
          Stream
            .awakeEvery[Task](500.millis)
            .through(t =>
              for
                _ <- t
                status <- Stream
                  .retry(
                    readAggregateViewStatus,
                    500.millis,
                    x => x,
                    10,
                    {
                      case _: PSQLException => true
                      case _                => false
                    }
                  )
                s <- streamEventsFrom(None, status.map(_.next))
              yield s
            )

        def countAggregateView = ZIO.succeed(0)

        def readAggregateView(query: Option[Unit]) = ZIO.none
        def readAggregateViews = ZIO.succeed(Nil)

        def mergeAggregateViewStatusM(
          currTime: OffsetDateTime,
          status: D.AggregateViewStatus
        ) = for
          _ <- lockAggregateViewStatusTableM
          oldStatus <- readAggregateViewStatusM.map(_.map(_._1))
          newSequenceIds = oldStatus.fold(Map.empty)(
            _.sequenceIds
          ) ++ status.sequenceIds
          _ <- (sql"""INSERT INTO ${Fragment.const(
            aggViewsTableName
          )} (name, status, last_updated) VALUES (${aggView.versionedStoreName}, ${status
            .copy(
              sequenceIds = newSequenceIds,
              longestDuration = List(
                status.catchupDuration.orElse(
                  oldStatus.flatMap(_.catchupDuration)
                ),
                status.syncDuration.orElse(oldStatus.flatMap(_.syncDuration))
              ).flatten.maxOption,
              longestEventsSize = List(
                status.catchupEventsSize.orElse(
                  oldStatus.flatMap(_.catchupEventsSize)
                ),
                status.syncEventsSize.orElse(
                  oldStatus.flatMap(_.syncEventsSize)
                )
              ).flatten.maxOption,
              catchupDuration = status.catchupDuration.orElse(
                oldStatus.flatMap(_.catchupDuration)
              ),
              catchupEventsSize = status.catchupEventsSize.orElse(
                oldStatus.flatMap(_.catchupEventsSize)
              ),
              syncDuration =
                status.syncDuration.orElse(oldStatus.flatMap(_.syncDuration)),
              syncEventsSize = status.syncEventsSize.orElse(
                oldStatus.flatMap(_.syncEventsSize)
              ),
              error = status.error
            )
            .auto
            .to[AggregateViewStatus]
            .toJsonASTOrFail}, $currTime) """ ++
            sql"""ON CONFLICT (name) DO UPDATE SET status = EXCLUDED.status, last_updated = EXCLUDED.last_updated""").update.run
            .map(_ => ())
        yield ()

        def mergeAggregateViewStatus(status: D.AggregateViewStatus) = for
          now <- now
          res <- mergeAggregateViewStatusM(now, status).transact(
            txn.transactor
          )
        yield res

        private def lockTablesM = for
          _ <- sql"""LOCK TABLE ${Fragment.const(
            tableName
          )} IN exclusive mode""".update.run
          _ <- lockAggregateViewStatusTableM
        yield ()

        private def lockAggregateViewStatusTableM = for
          _ <- sql"""LOCK TABLE ${Fragment.const(
            aggViewsTableName
          )} IN row exclusive mode""".update.run
          _ <- sql"""SELECT 1 FROM ${Fragment.const(
            aggViewsTableName
          )} WHERE name = ${aggView.versionedStoreName} FOR UPDATE"""
            .query[Int]
            .option
        yield ()

        def persistAggregateView(
          startStatus: Option[D.AggregateViewStatus],
          endStatus: D.AggregateViewStatus,
          data: List[
            D.Event[mig.Id, mig.Meta, mig.EventData]
          ]
        ) = data.toNel.fold(ZIO.unit)(evs =>
          for
            now <- now
            res <- aggStore.persistEventsWithTransaction(
              evs,
              persist =>
                for
                  _ <- lockTablesM
                  currStatus <- readAggregateViewStatusM
                  res <-
                    if startStatus.fold(currStatus.isEmpty)(s =>
                        currStatus.fold(false)(c => s.sameSequenceIds(c._1))
                      )
                    then
                      for
                        _ <- persist()
                        _ <- mergeAggregateViewStatusM(now, endStatus)
                      yield true
                    else Free.pure(false)
                yield res
            )
            _ <-
              if !res then
                ZIO.logInfo(
                  s"Optimistic Concurrency: Failed to persist aggregate view ${aggView.versionedStoreName}"
                )
              else ZIO.unit
          yield ()
        )

        def resetAggregateView =
          ZIO.logInfo("Resetting Aggregate migration is forbidden")

        def readAggregateViewStatusM =
          sql"""SELECT status, last_updated from ${Fragment.const(
            aggViewsTableName
          )} where name = ${aggView.versionedStoreName}"""
            .query[(Json, OffsetDateTime)]
            .option
            .map(
              _.map((x, t) =>
                x.as[AggregateViewStatus]
                  .map(_.auto.to[D.AggregateViewStatus])
                  .getOrElse(
                    throw new Exception(
                      s"Failed to parse AggregateViewStatus from $x"
                    )
                  ) -> t
              )
            )

        def readAggregateViewStatus =
          readAggregateViewStatusM
            .transact(txn.transactor)
            .map(_.map(_._1))

        def readAggregateViewAndStatus(
          query: Option[Unit]
        ): Task[
          (
            Option[List[
              D.Event[mig.Id, mig.Meta, mig.EventData]
            ]],
            Option[D.AggregateViewStatus]
          )
        ] = (for
          _ <- lockTablesM
          status <- readAggregateViewStatusM
        yield (None, status.map(_._1)))
          .transact(txn.transactor)

        // TODO put in one transaction
        def isReady = for
          status <- readAggregateViewStatus
          seqId <- migratedStore.getLastSequenceId
        yield (
          status.fold(0)(
            _.getSequenceId(aggView.versionedStoreName).value
          ) === seqId.value,
          status,
          seqId
        )

        def isCaughtUp = isReady.map(_._1)

        def withCaughtUp[R, E, A](
          f: => ZIO[R, E, A],
          failMessage: Option[String] = None
        ): ZIO[R, E | Throwable, A] =
          for
            res <- isReady
              .repeat(
                (Schedule.spaced(1.second) >>> Schedule.elapsed)
                  .whileOutput(_ < catchUpTimeout) && Schedule
                  .recurUntil(s => s._1)
              )
            (_, ready) = res
            _ <-
              if !ready._1 then
                val stackErs =
                  Thread.currentThread.getStackTrace
                    .map(_.toString)
                    .mkString("\n")
                ZIO.fail(
                  new Exception(
                    s"""Aggregate view withCaughtUp timed out after ${catchUpTimeout.toSeconds} secs, Status: ${ready._2
                      .map(
                        _.sequenceIds
                      )}, Last Seq Ids: ${ready._3}${failMessage
                      .fold("")(x => s", ${x}")}

${stackErs}"""
                  )
                )
              else ZIO.unit
            res <- f
          yield res

        def health =
          NodeHealthCheck(
            s"AggregateView ${aggView.versionedStoreName}",
            List(
              LeafHealthCheck(
                "Info",
                () =>
                  (for
                    ready <- isReady
                    now <- now
                    status <- readAggregateViewStatusM.transact(
                      txn.transactor
                    )
                    details = Map(
                      "status" -> Some(
                        status.map(
                          _._1.error.fold(
                            if ready._1 then "Ready" else "Catching up"
                          )(e => s"Failed, $e")
                        )
                      ).map(_.toJsonASTOrFail),
                      "sequenceIds" -> status
                        .map(_._1.sequenceIds)
                        .map(_.toJsonASTOrFail),
                      "catchup_duration" -> status
                        .flatMap(_._1.catchupDuration)
                        .map(s => differenceToString(s))
                        .map(_.toJsonASTOrFail),
                      "catchup_events" -> status
                        .flatMap(_._1.catchupEventsSize)
                        .map(s => s.toString)
                        .map(_.toJsonASTOrFail),
                      "sync_duration" -> status
                        .flatMap(_._1.syncDuration)
                        .map(s => differenceToString(s))
                        .map(_.toJsonASTOrFail),
                      "sync_events" -> status
                        .flatMap(_._1.syncEventsSize)
                        .map(s => s.toString)
                        .map(_.toJsonASTOrFail),
                      "longest_duration" -> status
                        .flatMap(_._1.longestDuration)
                        .map(s => differenceToString(s))
                        .map(_.toJsonASTOrFail),
                      "longest_events" -> status
                        .flatMap(_._1.longestEventsSize)
                        .map(s => s.toString)
                        .map(_.toJsonASTOrFail),
                      "last_updated" -> status
                        .map(_._2)
                        .map(s => s.toString)
                        .map(_.toJsonASTOrFail)
                    ).filterNot(kv => kv._2.isEmpty).toJsonASTOrFail
                  yield status
                    .flatMap(_._1.error)
                    .map(_ => Unhealthy(Some(details)))
                    .getOrElse(
                      Healthy(
                        Some(
                          details
                        )
                      )
                    )).catchAll(err =>
                    ZIO.succeed(
                      Unhealthy(
                        Some(Map("message" -> err.getMessage).toJsonASTOrFail)
                      )
                    )
                  )
              )
            )
          )

      }
    )
