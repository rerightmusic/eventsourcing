package eventsourcing.domain

import cats.data.NonEmptyList
import types.*
import izumi.reflect.Tag
import cats.syntax.all.*
import zio.ZIO
import shared.newtypes.NewExtractor
import shared.principals.PrincipalId
import zio.clock.Clock
import zio.blocking.Blocking
import java.util.UUID
import zio.Has
import zio.Task
import zio.{ZLayer, ZEnv}
import shared.logging.all.Logging
import zio.RIO

type AggregateViewService[View] = Has[AggregateViewService.Service[View]]
object AggregateViewService:
  trait Service[View]:
    type ActualView
    type Query
    type Aggregates <: NonEmptyTuple

    def store[R, E, A](
      f: AggregateViewStore.Service[ActualView, Query, Aggregates] => ZIO[
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

  def live[View, ActualView_, Query_, Aggregates_ <: NonEmptyTuple](using
    view: AggregateViewClass.Aux[View, ActualView_, Query_, Aggregates_],
    viewIns: AggregateView.Aux[ActualView_, Query_, Aggregates_],
    t: Tag[Query_],
    t1: Tag[ActualView_],
    t2: Tag[Aggregates_],
    t3: Tag[View],
    t4: Tag[AggregateViewStore.Service[
      ActualView_,
      Query_,
      Aggregates_
    ]]
  ) =
    ZLayer.fromFunction[AggregateViewStore[
      ActualView_,
      Query_,
      Aggregates_
    ] & ZEnv & Logging, Service[View]](env =>
      new Service[View]:
        type ActualView = ActualView_
        type Query = Query_
        type Aggregates = Aggregates_

        def store[R, E, A](
          f: AggregateViewStore.Service[ActualView, Query, Aggregates] => ZIO[
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
            .provide(env)

        def run(mode: AggregateViewMode, subscribe: Boolean) =
          operations
            .runAggregateView[View](
              mode,
              subscribe
            )
            .provide(env)
    )

  def apply[View](using
    view: AggregateViewClass[View],
    t: Tag[
      AggregateViewService.Service[View] {
        type ActualView = view.ActualView
        type Query = view.Query
        type Aggregates = view.Aggregates
      }
    ],
    tw: Tag[
      AggregateViewStore.Service[view.ActualView, view.Query, view.Aggregates]
    ]
  ) = ServiceOps[View, view.ActualView, view.Query, view.Aggregates]

  class ServiceOps[View, ActualView_, Query_, Aggregates_ <: NonEmptyTuple](
    using
    Tag[
      AggregateViewService.Service[View] {
        type ActualView = ActualView_
        type Query = Query_
        type Aggregates = Aggregates_
      }
    ],
    Tag[AggregateViewStore.Service[ActualView_, Query_, Aggregates_]]
  ):
    def store[R, E, A](
      f: AggregateViewStore.Service[ActualView_, Query_, Aggregates_] => ZIO[
        R,
        E,
        A
      ]
    ) =
      ZIO
        .accessM[Has[
          AggregateViewService.Service[View] {
            type ActualView = ActualView_
            type Query = Query_
            type Aggregates = Aggregates_
          }
        ] & R](_.get.store(f))
        .asInstanceOf[ZIO[AggregateViewService[View] & R, E, A]]

    def run(
      mode: AggregateViewMode,
      subscribe: Boolean
    ): ZIO[AggregateViewService[View], Throwable, Unit] = ZIO
      .accessM[Has[
        AggregateViewService.Service[View] {
          type ActualView = ActualView_
          type Query = Query_
          type Aggregates = Aggregates_
        }
      ]](
        _.get.run(mode, subscribe)
      )
      .asInstanceOf[ZIO[AggregateViewService[View], Throwable, Unit]]

    def get(query: Option[Query_]): ZIO[AggregateViewService[
      View
    ], Throwable, Option[ActualView_]] =
      ZIO
        .accessM[Has[
          AggregateViewService.Service[View] {
            type ActualView = ActualView_
            type Query = Query_
            type Aggregates = Aggregates_
          }
        ]](
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
        .accessM[Has[
          AggregateViewService.Service[View] {
            type ActualView = ActualView_
            type Query = Query_
            type Aggregates = Aggregates_
          }
        ] & R](_.get.withCaughtUp(f))
        .asInstanceOf[ZIO[AggregateViewService[View] & R, E | Throwable, A]]

    def isCaughtUp =
      ZIO
        .accessM[Has[
          AggregateViewService.Service[View] {
            type ActualView = ActualView_
            type Query = Query_
            type Aggregates = Aggregates_
          }
        ]](_.get.store(_.isCaughtUp))
        .asInstanceOf[RIO[AggregateViewService[View], Boolean]]
