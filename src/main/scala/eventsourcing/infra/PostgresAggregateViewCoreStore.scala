package eventsourcing.infra

import postgresOperations.*
import eventsourcing.domain.types as D
import eventsourcing.domain.AggregateView
import shared.json.all.{*, given}
import cats.data.NonEmptyList
import doobie.util.update.Update
import zio.Task
import zio.IO
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
import shared.newtypes.NewExtractor
import java.util.UUID
import zio.clock.Clock
import zio.duration.durationInt
import izumi.reflect.Tag
import java.time.OffsetDateTime
import zio.logging.Logger
import doobie.util.transactor.Transactor
import fs2.Stream
import shared.logging.all.Logging
import cats.arrow.FunctionK
import zio.Schedule
import shared.time.all.*
import zio.duration.*
import shared.health.NodeHealthCheck
import shared.health.Healthy
import shared.health.LeafHealthCheck
import shared.health.Unhealthy
import cats.free.Free
import scala.concurrent.duration.*
import zio.interop.catz.implicits.*
import org.postgresql.util.PSQLException

trait PostgresAggregateViewCoreStore[
  Name <: String,
  View,
  Query,
  Aggregates <: NonEmptyTuple,
  AggsId
](
  schema: String,
  val aggs: AggregateViewStores.Aux[Aggregates, ?, AggsId],
  env: Has[WithTransactor[Name]] & aggs.Stores & Clock & Logging,
  catchUpTimeout: zio.duration.Duration
)(using tName: Tag[Name], view: AggregateView[View]):
  val tableName = schema + "." + camelToUnderscores(view.storeName)
  val aggViewsTableName = schema + "." + "aggregate_views"

  def queryToIds(query: Query): Option[NonEmptyList[AggsId]]
  def streamEventsFrom(
    query: Option[Query],
    status: Option[D.AggregateViewStatus]
  ): Stream[Task, Chunk[D.AggregateViewEvent[Aggregates]]] =
    aggs
      .streamEventsFrom(query.flatMap(queryToIds), status)
      .translate(
        new FunctionK[RIO[aggs.Stores, *], Task]:
          def apply[A](x: RIO[aggs.Stores, A]) = x.provide(env)
      )

  def subscribeEventStream
    : fs2.Stream[Task, fs2.Chunk[D.AggregateViewEvent[Aggregates]]] =
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

  def countAggregateViewM: ConnectionIO[Int]
  def countAggregateView = countAggregateViewM.transact(
    env.get[WithTransactor[Name]].transactor
  )
  def readAggregateViewM(
    query: Option[Query]
  ): ConnectionIO[Either[Throwable, Option[View]]]
  def readAggregateView(query: Option[Query]) =
    readAggregateViewM(query)
      .transact(
        env.get[WithTransactor[Name]].transactor
      )
      .absolve

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
    )} (name, status, last_updated) VALUES (${view.storeName}, ${status
      .copy(
        sequenceIds = newSequenceIds,
        longestDuration = List(
          status.catchupDuration.orElse(oldStatus.flatMap(_.catchupDuration)),
          status.syncDuration.orElse(oldStatus.flatMap(_.syncDuration))
        ).flatten.maxOption,
        longestEventsSize = List(
          status.catchupEventsSize.orElse(
            oldStatus.flatMap(_.catchupEventsSize)
          ),
          status.syncEventsSize.orElse(oldStatus.flatMap(_.syncEventsSize))
        ).flatten.maxOption,
        catchupDuration =
          status.catchupDuration.orElse(oldStatus.flatMap(_.catchupDuration)),
        catchupEventsSize = status.catchupEventsSize.orElse(
          oldStatus.flatMap(_.catchupEventsSize)
        ),
        syncDuration =
          status.syncDuration.orElse(oldStatus.flatMap(_.syncDuration)),
        syncEventsSize =
          status.syncEventsSize.orElse(oldStatus.flatMap(_.syncEventsSize)),
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
      env.get[WithTransactor[Name]].transactor
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
    )} WHERE name = ${view.storeName} FOR UPDATE""".query[Int].option
  yield ()

  def persistAggregateViewM(data: View): ConnectionIO[Unit]

  def persistAggregateView(
    startStatus: Option[D.AggregateViewStatus],
    endStatus: D.AggregateViewStatus,
    data: View
  ) = for
    now <- now
    res <- (for
      _ <- lockTablesM
      currStatus <- readAggregateViewStatusM
      res <-
        if startStatus.fold(currStatus.isEmpty)(s =>
            currStatus.fold(false)(c => s.sameSequenceIds(c._1))
          )
        then
          for
            _ <- persistAggregateViewM(data)
            _ <- mergeAggregateViewStatusM(now, endStatus)
          yield true
        else Free.pure(false)
    yield res).transact(env.get[WithTransactor[Name]].transactor)
    _ <-
      if !res then
        env
          .get[Logger[String]]
          .info(
            s"Optimistic Concurrency: Failed to persist aggregate view ${view.storeName}"
          )
      else ZIO.unit
  yield ()

  def resetAggregateView = (for
    _ <- lockTablesM
    _ <- sql"""DELETE from ${Fragment.const(
      aggViewsTableName
    )} WHERE name = ${view.storeName}""".update.run
    _ <- sql"""TRUNCATE ${Fragment.const(tableName)}""".update.run
  yield ()).transact(env.get[WithTransactor[Name]].transactor).unit

  def readAggregateViewStatusM =
    sql"""SELECT status, last_updated from ${Fragment.const(
      aggViewsTableName
    )} where name = ${view.storeName}"""
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
      .transact(env.get[WithTransactor[Name]].transactor)
      .map(_.map(_._1))

  def readAggregateViewAndStatus(
    query: Option[Query]
  ): Task[(Option[View], Option[D.AggregateViewStatus])] = (for
    _ <- lockTablesM
    status <- readAggregateViewStatusM
    data <- readAggregateViewM(query)
  yield (data, status.map(_._1)))
    .transact(env.get[WithTransactor[Name]].transactor)
    .flatMap(x => ZIO.fromEither(x._1.map(_ -> x._2)))

  // TODO put in one transaction
  def isReady = (for
    status <- readAggregateViewStatus
    seqIds <- aggs.getLastSequenceIds
  yield (
    seqIds
      .forall((name, seqId) =>
        status.fold(0)(_.getSequenceId(name).value) === seqId.value
      ),
    status,
    seqIds
  )).provide(env)

  def isCaughtUp = isReady.map(_._1)

  def withCaughtUp[R, E, A](
    f: => ZIO[R, E, A],
    failMessage: Option[String] = None
  ): ZIO[R, E | Throwable, A] =
    for
      (_, ready) <- isReady
        .repeat(
          (Schedule.spaced(1.second) >>> Schedule.elapsed)
            .whileOutput(_ < catchUpTimeout) && Schedule
            .recurUntil(s => s._1)
        )
        .provide(env)
      _ <-
        if !ready._1 then
          val stackErs =
            Thread.currentThread.getStackTrace.map(_.toString).mkString("\n")
          ZIO.fail(
            new Exception(
              s"""Aggregate view withCaughtUp timed out after ${catchUpTimeout.toSeconds} secs, Status: ${ready._2
                .map(_.sequenceIds)}, Last Seq Ids: ${ready._3}${failMessage
                .fold("")(x => s", ${x}")}

${stackErs}"""
            )
          )
        else ZIO.unit
      res <- f
    yield res

  def health = NodeHealthCheck(
    s"AggregateView ${view.storeName}",
    List(
      LeafHealthCheck(
        "Info",
        () =>
          (for
            ready <- isReady
            now <- now
            status <- readAggregateViewStatusM.transact(
              env.get[WithTransactor[Name]].transactor
            )
          yield status
            .flatMap(_._1.error)
            .map(Unhealthy(_))
            .getOrElse(
              Healthy(
                Some(
                  Map(
                    "status" -> Some(
                      if ready._1 then "Ready" else "Catching up"
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
                )
              )
            )).catchAll(err => ZIO.succeed(Unhealthy(err.getMessage)))
      )
    )
  )
