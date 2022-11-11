package eventsourcing.domain

import cats.data.NonEmptyList
import types.*
import cats.syntax.all.*
import zio.ZIO
import shared.newtypes.NewExtractor
import shared.principals.PrincipalId
import java.util.UUID
import zio.Task
import zio.ZEnvironment
import zio.ZLayer

trait AggregateService[Agg]:
  type Id
  type Meta
  type EventData
  type Command
  def store[R, E, A](
    f: AggregateStore[Id, Meta, EventData] => ZIO[R, E, A]
  ): ZIO[R, E, A]

  def viewStore[R, E, A](
    f: AggregateViewStore.Schemaless[
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

object AggregateService:
  type Aux[Agg, Id_, Meta_, EventData_, Command_] = AggregateService[Agg] {
    type Id = Id_
    type Meta = Meta_
    type EventData = EventData_
    type Command = Command_
  }

  def live[Agg](using
    agg: Aggregate[Agg],
    ex: NewExtractor.Aux[agg.Id, UUID],
    tAgg: zio.Tag[Agg],
    tAggs: zio.Tag[Agg *: EmptyTuple],
    tMap: zio.Tag[Map[agg.Id, Schemaless[agg.Id, agg.Meta, Agg]]],
    tId: zio.Tag[agg.Id],
    tMeta: zio.Tag[agg.Meta],
    tEventData: zio.Tag[agg.EventData],
    tSchemaless: zio.Tag[Schemaless[agg.Id, agg.Meta, Agg]],
    tSvc: zio.Tag[
      AggregateService.Aux[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command]
    ],
    tStore: zio.Tag[AggregateStore[agg.Id, agg.Meta, agg.EventData]],
    tViewStore: zio.Tag[AggregateViewStore.Schemaless[
      agg.Id,
      agg.Meta,
      Agg,
      agg.Id,
      Agg *: EmptyTuple
    ]]
  ): ZLayer[
    AggregateStore[
      agg.Id,
      agg.Meta,
      agg.EventData
    ] &
      AggregateViewStore.Schemaless[
        agg.Id,
        agg.Meta,
        Agg,
        agg.Id,
        Agg *: EmptyTuple
      ],
    Throwable,
    AggregateService[Agg]
  ] =
    ZLayer
      .fromZIO[
        AggregateStore[
          agg.Id,
          agg.Meta,
          agg.EventData
        ] &
          AggregateViewStore.Schemaless[
            agg.Id,
            agg.Meta,
            Agg,
            agg.Id,
            Agg *: EmptyTuple
          ],
        Throwable,
        AggregateService[
          Agg
        ]
      ](
        for
          env <- ZIO.environment[
            AggregateStore[
              agg.Id,
              agg.Meta,
              agg.EventData
            ] &
              AggregateViewStore.Schemaless[
                agg.Id,
                agg.Meta,
                Agg,
                agg.Id,
                Agg *: EmptyTuple
              ]
          ]
        yield new AggregateService[Agg]:
          type Id = agg.Id
          type Meta = agg.Meta

          type EventData = agg.EventData

          type Command = agg.Command

          def store[R, E, A](
            f: AggregateStore[Id, Meta, EventData] => ZIO[
              R,
              E,
              A
            ]
          ) = f(env.get[AggregateStore[Id, Meta, EventData]])
          def viewStore[R, E, A](
            f: AggregateViewStore.Schemaless[
              Id,
              Meta,
              Agg,
              Id,
              Agg *: EmptyTuple
            ] => ZIO[R, E, A]
          ) = f(
            env.get[AggregateViewStore.Schemaless[
              Id,
              Meta,
              Agg,
              Id,
              Agg *: EmptyTuple
            ]]
          )

          def get(
            id: Id
          ): Task[Agg] = operations
            .getAggregate[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command](id)
            .provideEnvironment(env)

          def exec(
            id: Id,
            meta: Meta,
            createdBy: PrincipalId,
            cmd: Command
          ) =
            operations
              .runExecuteCommand[Agg](id, meta, createdBy, cmd)
              .provideEnvironment(env)

          def create(meta: Meta, createdBy: PrincipalId, cmd: Command) =
            operations
              .runCreateCommand[Agg](meta, createdBy, cmd)
              .provideEnvironment(env)

          def update(
            id: Id,
            meta: Meta,
            createdBy: PrincipalId,
            cmd: Command
          ) = operations
            .runUpdateCommand[Agg](id, meta, createdBy, cmd)
            .provideEnvironment(env)
      )

  def apply[Agg](using
    agg: Aggregate[Agg],
    tSvc: zio.Tag[
      AggregateService.Aux[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command]
    ],
    tViewStore: zio.Tag[AggregateViewStore.Schemaless[
      agg.Id,
      agg.Meta,
      Agg,
      agg.Id,
      Agg *: EmptyTuple
    ]]
  ): ServiceOps[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command] =
    ServiceOps[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command]

  class ServiceOps[Agg, Id_, Meta_, EventData_, Command_](using
    tSvc: zio.Tag[AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_]],
    tViewStore: zio.Tag[AggregateViewStore.Schemaless[
      Id_,
      Meta_,
      Agg,
      Id_,
      Agg *: EmptyTuple
    ]]
  ):
    def store[R, E, A](
      f: AggregateStore[Id_, Meta_, EventData_] => ZIO[R, E, A]
    ) =
      ZIO
        .environmentWithZIO[
          AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_] & R
        ](
          _.get[AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_]]
            .store(f)
        )
        .asInstanceOf[ZIO[AggregateService[Agg] & R, E, A]]

    def viewStore[R, E, A](
      f: AggregateViewStore.Schemaless[
        Id_,
        Meta_,
        Agg,
        Id_,
        Agg *: EmptyTuple
      ] => ZIO[R, E, A]
    ) = ZIO
      .environmentWithZIO[
        AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_] & R
      ](
        _.get[
          AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_]
        ].viewStore(f)
      )
      .asInstanceOf[ZIO[AggregateService[
        Agg
      ] & R, E, A]]

    def get(
      id: Id_
    ): ZIO[AggregateService[Agg], Throwable, Agg] =
      ZIO
        .environmentWithZIO[
          AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_]
        ](
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
      .environmentWithZIO[
        AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_]
      ](
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
      .environmentWithZIO[
        AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_]
      ](
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
      .environmentWithZIO[
        AggregateService.Aux[Agg, Id_, Meta_, EventData_, Command_]
      ](
        _.get.update(id, meta, createdBy, cmd)
      )
      .asInstanceOf[ZIO[AggregateService[
        Agg
      ], Throwable, Unit]]
