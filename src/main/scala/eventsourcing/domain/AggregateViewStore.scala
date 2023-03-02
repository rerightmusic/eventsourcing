package eventsourcing.domain

import types.*
import cats.data.NonEmptyList
import zio.Task
import fs2.*
import zio.ZIO
import shared.health.HealthCheck

trait AggregateViewStore[View, Query, Aggregates <: NonEmptyTuple]:
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
  def readAggregateViews: Task[List[View]]
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

object AggregateViewStore:
  type Fold[View, Aggregates <: NonEmptyTuple] =
    AggregateViewStore[View, Unit, Aggregates]

  type Schemaless[Id, Meta, Data, QueryId, Aggregates <: NonEmptyTuple] =
    AggregateViewStore[Map[
      Id,
      types.Schemaless[Id, Meta, Data]
    ], NonEmptyList[
      QueryId
    ], Aggregates]

  def apply[View](using
    view: AggregateViewClass[View],
    tActualView: zio.Tag[view.ActualView],
    tQuery: zio.Tag[view.Query],
    tAggregates: zio.Tag[view.Aggregates]
  ) = ServiceOps[view.ActualView, view.Query, view.Aggregates]

  class ServiceOps[View, Query, Aggregates <: NonEmptyTuple](using
    tView: zio.Tag[View],
    tQuery: zio.Tag[Query],
    tAggregates: zio.Tag[Aggregates]
  ):
    def streamEventsFrom(
      query: Option[Query],
      status: Option[AggregateViewStatus]
    ) = ZIO
      .environmentWith[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.streamEventsFrom(query, status))

    def subscribeEventStream = ZIO
      .environmentWith[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.subscribeEventStream)

    def countAggregateView = ZIO
      .environmentWithZIO[AggregateViewStore[
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
      res <- withCaughtUp(countAggregateView.provideEnvironment(env))
    yield res

    def readAggregateView(
      query: Option[Query]
    ) = ZIO
      .environmentWithZIO[AggregateViewStore[
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
      res <- withCaughtUp(
        readAggregateView(query).provideEnvironment(env)
      )
    yield res

    def persistAggregateView(
      startStatus: Option[AggregateViewStatus],
      endStatus: AggregateViewStatus,
      view: View
    ) = ZIO
      .environmentWithZIO[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.persistAggregateView(startStatus, endStatus, view))

    def resetAggregateView = ZIO
      .environmentWithZIO[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.resetAggregateView)

    def readAggregateViewStatus = ZIO
      .environmentWithZIO[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.readAggregateViewStatus)

    def readAggregateViewAndStatus(
      query: Option[Query]
    ) = ZIO
      .environmentWithZIO[AggregateViewStore[
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
      res <- withCaughtUp(
        readAggregateViewAndStatus(query).provideEnvironment(env)
      )
    yield res

    def withCaughtUp[R, E, A](
      f: => ZIO[R, E, A],
      failMessage: Option[String] = None
    ) =
      ZIO
        .environmentWithZIO[AggregateViewStore[
          View,
          Query,
          Aggregates
        ] & R](
          _.get[AggregateViewStore[
            View,
            Query,
            Aggregates
          ]].withCaughtUp(f, failMessage)
        )

    def health = ZIO
      .environmentWith[AggregateViewStore[
        View,
        Query,
        Aggregates
      ]](_.get.health)
