package eventsourcing.domain

import types.*
import zio.UIO
import cats.data.NonEmptyList
import zio.Has
import zio.Task
import zio.IO
import shared.principals.PrincipalId
import izumi.reflect.Tag
import zio.logging.Logging
import zio.RIO
import fs2.*
import zio.interop.catz.*
import zio.clock.Clock
import zio.blocking.Blocking
import zio.ZIO
import shared.health.HealthCheck

type AggregateViewStore[View, Query, Aggregates <: NonEmptyTuple] =
  Has[AggregateViewStore.Service[View, Query, Aggregates]]

object AggregateViewStore:
  trait Service[View, Query, Aggregates <: NonEmptyTuple]:
    def streamEventsFrom(
      query: Option[Query],
      status: Option[AggregateViewStatus]
    ): Stream[Task, Chunk[AggregateViewEvent[Aggregates]]]

    def subscribeEventStream
      : fs2.Stream[Task, Chunk[AggregateViewEvent[Aggregates]]]

    def countAggregateView: Task[Int]
    def countAggregateViewWithCaughtUp: Task[Int] = withCaughtUp(
      countAggregateView
    )
    def readAggregateView(
      query: Option[Query]
    ): Task[Option[View]]

    def readAggregateViewWithCaughtUp(
      query: Option[Query]
    ): Task[Option[View]] = withCaughtUp(
      readAggregateView(query)
    )
    def persistAggregateView(
      startStatus: Option[AggregateViewStatus],
      endStatus: AggregateViewStatus,
      view: View
    ): Task[Unit]
    def mergeAggregateViewStatus(
      status: AggregateViewStatus
    ): Task[Unit]
    def resetAggregateView: Task[Unit]
    def readAggregateViewStatus: Task[Option[AggregateViewStatus]]
    def readAggregateViewAndStatus(
      query: Option[Query]
    ): Task[(Option[View], Option[AggregateViewStatus])]
    def readAggregateViewAndStatusWaitCatchup(
      query: Option[Query]
    ): Task[(Option[View], Option[AggregateViewStatus])] = withCaughtUp(
      readAggregateViewAndStatus(query)
    )

    def withCaughtUp[R, E, A](
      f: => ZIO[R, E, A],
      failMessage: Option[String] = None
    ): ZIO[R, E | Throwable, A]

    def isCaughtUp: Task[Boolean]

    def health: HealthCheck

  type Fold[View, Aggregates <: NonEmptyTuple] =
    Has[FoldService[View, Aggregates]]

  type FoldService[View, Aggregates <: NonEmptyTuple] =
    AggregateViewStore.Service[View, Unit, Aggregates]

  type Schemaless[Id, Meta, Data, QueryId, Aggregates <: NonEmptyTuple] =
    Has[SchemalessService[Id, Meta, Data, QueryId, Aggregates]]

  type SchemalessService[Id, Meta, Data, QueryId, Aggregates <: NonEmptyTuple] =
    AggregateViewStore.Service[Map[
      Id,
      types.Schemaless[Id, Meta, Data]
    ], NonEmptyList[
      QueryId
    ], Aggregates]

  def apply[View](using
    view: AggregateViewClass[View],
    t1: Tag[view.ActualView],
    t2: Tag[view.Query],
    t3: Tag[view.Aggregates]
  ) = ServiceOps[view.ActualView, view.Query, view.Aggregates]

  class ServiceOps[View, Query, Aggregates <: NonEmptyTuple](using
    t1: Tag[View],
    t2: Tag[Query],
    t3: Tag[Aggregates]
  ):
    def streamEventsFrom(
      query: Option[Query],
      status: Option[AggregateViewStatus]
    ) = ZIO
      .access[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.streamEventsFrom(query, status))

    def subscribeEventStream = ZIO
      .access[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.subscribeEventStream)

    def countAggregateView = ZIO
      .accessM[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.countAggregateView)

    def countAggregateViewWithCaughtUp = for
      env <- ZIO.environment[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]]
      res <- withCaughtUp(countAggregateView.provide(env))
    yield res

    def readAggregateView(
      query: Option[Query]
    ) = ZIO
      .accessM[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.readAggregateView(query))

    def readAggregateViewWithCaughtUp(
      query: Option[Query]
    ) = for
      env <- ZIO.environment[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]]
      res <- withCaughtUp(readAggregateView(query).provide(env))
    yield res

    def persistAggregateView(
      startStatus: Option[AggregateViewStatus],
      endStatus: AggregateViewStatus,
      view: View
    ) = ZIO
      .accessM[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.persistAggregateView(startStatus, endStatus, view))

    def resetAggregateView = ZIO
      .accessM[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.resetAggregateView)

    def readAggregateViewStatus = ZIO
      .accessM[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.readAggregateViewStatus)

    def readAggregateViewAndStatus(
      query: Option[Query]
    ) = ZIO
      .accessM[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.readAggregateViewAndStatus(query))

    def readAggregateViewAndStatusWithCaughtUp(
      query: Option[Query]
    ) = for
      env <- ZIO.environment[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]]
      res <- withCaughtUp(readAggregateViewAndStatus(query).provide(env))
    yield res

    def withCaughtUp[R, E, A](
      f: => ZIO[R, E, A],
      failMessage: Option[String] = None
    ) =
      ZIO
        .accessM[AggregateViewStore[
          View,
          Query,
          Aggregates
        ] & R](_.get.withCaughtUp(f, failMessage))

    def health = ZIO
      .access[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.health)
