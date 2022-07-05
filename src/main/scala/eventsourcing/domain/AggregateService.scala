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
import zio.ZLayer

type AggregateService[Agg] = Has[AggregateService.Service[Agg]] &
  AggregateViewService[Agg]
object AggregateService:
  trait Service[Agg]:
    type Id
    type Meta
    type EventData
    type Command
    def store[R, E, A](
      f: AggregateStore.Service[Id, Meta, EventData] => ZIO[R, E, A]
    ): ZIO[R, E, A]
    def viewStore[R, E, A](
      f: AggregateViewStore.SchemalessService[
        Id,
        Meta,
        Agg,
        Id,
        Agg *: EmptyTuple
      ] => ZIO[R, E, A]
    ): ZIO[R, E, A]
    def get(id: Id): Task[Agg]
    def exec(
      id: Id,
      meta: Meta,
      createdBy: PrincipalId,
      cmd: Command
    ): Task[Unit]
    def create(meta: Meta, createdBy: PrincipalId, cmd: Command): Task[Id]
    def update(
      id: Id,
      meta: Meta,
      createdBy: PrincipalId,
      cmd: Command
    ): Task[Unit]

  def live[Agg](using
    agg: Aggregate[Agg],
    t: Tag[agg.Id],
    t2: Tag[agg.Meta],
    t3: Tag[agg.EventData],
    t4: Tag[Agg],
    t5: Tag[Map[
      agg.Id,
      Schemaless[agg.Id, agg.Meta, Agg]
    ]],
    t6: Tag[NonEmptyList[agg.Id]],
    t7: Tag[AggregateStore.Service[agg.Id, agg.Meta, agg.EventData]],
    t8: Tag[AggregateViewStore.SchemalessService[
      agg.Id,
      agg.Meta,
      Agg,
      agg.Id,
      Agg *: EmptyTuple
    ]],
    ex: NewExtractor.Aux[agg.Id, UUID]
  ) =
    ZLayer
      .fromFunction[AggregateStores[
        Agg,
        agg.Id,
        agg.Meta,
        agg.EventData
      ] & Clock & Blocking, Service[
        Agg
      ]](env =>
        new Service[Agg]:
          type Id = agg.Id
          type Meta = agg.Meta

          type EventData = agg.EventData

          type Command = agg.Command

          def store[R, E, A](
            f: AggregateStore.Service[Id, Meta, EventData] => ZIO[R, E, A]
          ) = f(env.get)
          def viewStore[R, E, A](
            f: AggregateViewStore.SchemalessService[
              Id,
              Meta,
              Agg,
              Id,
              Agg *: EmptyTuple
            ] => ZIO[R, E, A]
          ) = f(env.get)

          def get(
            id: Id
          ): Task[Agg] = operations
            .getAggregate[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command](id)
            .provide(env)

          def exec(
            id: Id,
            meta: Meta,
            createdBy: PrincipalId,
            cmd: Command
          ) =
            operations
              .runExecuteCommand[Agg](id, meta, createdBy, cmd)
              .provide(env)

          def create(meta: Meta, createdBy: PrincipalId, cmd: Command) =
            operations.runCreateCommand[Agg](meta, createdBy, cmd).provide(env)

          def update(
            id: Id,
            meta: Meta,
            createdBy: PrincipalId,
            cmd: Command
          ) = operations
            .runUpdateCommand[Agg](id, meta, createdBy, cmd)
            .provide(env)
      )

  def apply[Agg](using
    agg: Aggregate[Agg],
    t: Tag[
      AggregateService.Service[Agg] {
        type Id = agg.Id
        type Meta = agg.Meta
        type EventData = agg.EventData
        type Command = agg.Command

      }
    ],
    t2: Tag[AggregateStore.Service[agg.Id, agg.Meta, agg.EventData]],
    t3: Tag[AggregateViewStore.SchemalessService[
      agg.Id,
      agg.Meta,
      Agg,
      agg.Id,
      Agg *: EmptyTuple
    ]]
  ) =
    ServiceOps[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command]

  class ServiceOps[Agg, Id_, Meta_, EventData_, Command_](using
    Tag[
      AggregateService.Service[Agg] {
        type Id = Id_
        type Meta = Meta_
        type EventData = EventData_
        type Command = Command_
      }
    ],
    Tag[AggregateStore.Service[Id_, Meta_, EventData_]],
    Tag[AggregateViewStore.SchemalessService[
      Id_,
      Meta_,
      Agg,
      Id_,
      Agg *: EmptyTuple
    ]]
  ):
    def store[R, E, A](
      f: AggregateStore.Service[Id_, Meta_, EventData_] => ZIO[R, E, A]
    ) =
      ZIO
        .accessM[Has[
          AggregateService.Service[Agg] {
            type Id = Id_
            type Meta = Meta_
            type EventData = EventData_
            type Command = Command_
          }
        ] & R](_.get.store(f))
        .asInstanceOf[ZIO[AggregateService[
          Agg
        ] & R, E, A]]

    def viewStore[R, E, A](
      f: AggregateViewStore.SchemalessService[
        Id_,
        Meta_,
        Agg,
        Id_,
        Agg *: EmptyTuple
      ] => ZIO[R, E, A]
    ) = ZIO
      .accessM[Has[
        AggregateService.Service[Agg] {
          type Id = Id_
          type Meta = Meta_
          type EventData = EventData_
          type Command = Command_
        }
      ] & R](_.get.viewStore(f))
      .asInstanceOf[ZIO[AggregateService[
        Agg
      ] & R, E, A]]

    def get(
      id: Id_
    ): ZIO[AggregateService[Agg], Throwable, Agg] =
      ZIO
        .accessM[Has[
          AggregateService.Service[Agg] {
            type Id = Id_
            type Meta = Meta_

            type EventData = EventData_
            type Command = Command_
          }
        ]](
          _.get.get(id)
        )
        .asInstanceOf[ZIO[AggregateService[
          Agg
        ], Throwable, Agg]]

    def exec(
      id: Id_,
      meta: Meta_,
      createdBy: PrincipalId,
      cmd: Command_
    ): ZIO[
      AggregateService[Agg],
      Throwable,
      Unit
    ] = ZIO
      .accessM[Has[
        AggregateService.Service[Agg] {
          type Id = Id_
          type Meta = Meta_
          type EventData = EventData_
          type Command = Command_
        }
      ]](
        _.get.exec(id, meta, createdBy, cmd)
      )
      .asInstanceOf[ZIO[AggregateService[
        Agg
      ], Throwable, Unit]]

    def create(
      meta: Meta_,
      createdBy: PrincipalId,
      cmd: Command_
    ): ZIO[
      AggregateService[Agg],
      Throwable,
      Id_
    ] = ZIO
      .accessM[Has[
        AggregateService.Service[Agg] {
          type Id = Id_
          type Meta = Meta_
          type EventData = EventData_
          type Command = Command_
        }
      ]](
        _.get.create(meta, createdBy, cmd)
      )
      .asInstanceOf[ZIO[AggregateService[
        Agg
      ], Throwable, Id_]]

    def update(
      id: Id_,
      meta: Meta_,
      createdBy: PrincipalId,
      cmd: Command_
    ): ZIO[
      AggregateService[Agg],
      Throwable,
      Unit
    ] = ZIO
      .accessM[Has[
        AggregateService.Service[Agg] {
          type Id = Id_
          type Meta = Meta_
          type EventData = EventData_
          type Command = Command_
        }
      ]](
        _.get.update(id, meta, createdBy, cmd)
      )
      .asInstanceOf[ZIO[AggregateService[
        Agg
      ], Throwable, Unit]]
