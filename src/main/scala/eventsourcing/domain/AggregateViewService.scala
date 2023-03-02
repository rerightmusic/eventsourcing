package eventsourcing.domain

import types.*
import zio.ZIO
import zio.Task
import zio.ZLayer
import zio.RIO

trait AggregateViewService[View]:
  type ActualView
  type Query
  type Aggregates <: NonEmptyTuple

  def store[R, E, A](
    f: AggregateViewStore[ActualView, Query, Aggregates] => ZIO[
      R,
      E,
      A
    ]
  ): ZIO[R, E, A]

  def withCaughtUp[R, E, A](
    f: => ZIO[R, E, A],
    failMessage: Option[String] = None
  ) = store(_.withCaughtUp(f, failMessage))

  def isCaughtUp: Task[Boolean] = store(_.isCaughtUp)

  def run(
    mode: AggregateViewMode,
    subscribe: Boolean
  ): Task[Unit]

  def get(query: Option[Query]): Task[Option[ActualView]]

object AggregateViewService:
  type Aux[View, ActualView_, Query_, Aggregates_] =
    AggregateViewService[View] {
      type ActualView = ActualView_
      type Query = Query_
      type Aggregates = Aggregates_
    }

  def live[View, ActualView_, Query_, Aggregates_ <: NonEmptyTuple](using
    view: AggregateViewClass.Aux[View, ActualView_, Query_, Aggregates_],
    viewIns: AggregateView.Aux[ActualView_, Query_, Aggregates_],
    tActualView: zio.Tag[ActualView_],
    tView: zio.Tag[View],
    tQuery: zio.Tag[Query_],
    tAggs: zio.Tag[Aggregates_],
    tViewSvc: zio.Tag[AggregateViewService[View]],
    tViewStore: zio.Tag[AggregateViewStore[ActualView_, Query_, Aggregates_]]
  ): ZLayer[
    AggregateViewStore[ActualView_, Query_, Aggregates_],
    Throwable,
    AggregateViewService[View]
  ] =
    ZLayer.fromZIO[AggregateViewStore[
      ActualView_,
      Query_,
      Aggregates_
    ], Throwable, AggregateViewService[View]](
      for env <- ZIO.environment[AggregateViewStore[
          ActualView_,
          Query_,
          Aggregates_
        ]]
      yield new AggregateViewService[View]:
        type ActualView = ActualView_
        type Query = Query_
        type Aggregates = Aggregates_

        def store[R, E, A](
          f: AggregateViewStore[ActualView, Query, Aggregates] => ZIO[
            R,
            E,
            A
          ]
        ) = f(env.get)

        def get(query: Option[Query]) =
          operations
            .getAggregateView[ActualView_, Query_, Aggregates_](
              query
            )
            .provideEnvironment(env)

        def run(mode: AggregateViewMode, subscribe: Boolean) =
          operations
            .runAggregateView[View](
              mode,
              subscribe
            )
            .provideEnvironment(env)
    )

  def apply[View](using
    view: AggregateViewClass[View],
    tSvc: zio.Tag[
      AggregateViewService.Aux[
        View,
        view.ActualView,
        view.Query,
        view.Aggregates
      ]
    ],
    tStore: zio.Tag[
      AggregateViewStore[view.ActualView, view.Query, view.Aggregates]
    ]
  ) = ServiceOps[View, view.ActualView, view.Query, view.Aggregates]

  class ServiceOps[View, ActualView_, Query_, Aggregates_ <: NonEmptyTuple](
    using
    tSvc: zio.Tag[
      AggregateViewService.Aux[
        View,
        ActualView_,
        Query_,
        Aggregates_
      ]
    ],
    tStore: zio.Tag[AggregateViewStore[ActualView_, Query_, Aggregates_]]
  ):
    def store[R, E, A](
      f: AggregateViewStore[ActualView_, Query_, Aggregates_] => ZIO[
        R,
        E,
        A
      ]
    ) =
      ZIO
        .environmentWithZIO[
          AggregateViewService.Aux[View, ActualView_, Query_, Aggregates_] & R
        ](
          _.get[
            AggregateViewService.Aux[View, ActualView_, Query_, Aggregates_]
          ].store(f)
        )
        .asInstanceOf[ZIO[AggregateViewService[View] & R, E, A]]

    def run(
      mode: AggregateViewMode,
      subscribe: Boolean
    ): ZIO[AggregateViewService[View], Throwable, Unit] = ZIO
      .environmentWithZIO[
        AggregateViewService.Aux[View, ActualView_, Query_, Aggregates_]
      ](
        _.get[
          AggregateViewService.Aux[View, ActualView_, Query_, Aggregates_]
        ].run(mode, subscribe)
      )
      .asInstanceOf[ZIO[AggregateViewService[View], Throwable, Unit]]

    def get(query: Option[Query_]): ZIO[AggregateViewService[
      View
    ], Throwable, Option[ActualView_]] =
      ZIO
        .environmentWithZIO[
          AggregateViewService.Aux[View, ActualView_, Query_, Aggregates_]
        ](
          _.get.get(query)
        )
        .asInstanceOf[ZIO[AggregateViewService[View], Throwable, Option[
          ActualView_
        ]]]

    def withCaughtUp[R, E, A](
      f: => ZIO[R, E, A],
      failMessage: Option[String] = None
    ) =
      ZIO
        .environmentWithZIO[
          AggregateViewService.Aux[View, ActualView_, Query_, Aggregates_] & R
        ](
          _.get[
            AggregateViewService.Aux[View, ActualView_, Query_, Aggregates_]
          ].withCaughtUp(f)
        )
        .asInstanceOf[ZIO[AggregateViewService[View] & R, E | Throwable, A]]

    def isCaughtUp =
      ZIO
        .environmentWithZIO[
          AggregateViewService.Aux[View, ActualView_, Query_, Aggregates_]
        ](_.get.store(_.isCaughtUp))
        .asInstanceOf[RIO[AggregateViewService[View], Boolean]]
