package eventsourcing.domain

import types.*
import zio.UIO
import cats.data.NonEmptyList
import zio.Has
import zio.Task
import shared.principals.PrincipalId
import izumi.reflect.Tag
import zio.logging.Logging
import zio.RIO
import fs2.*
import zio.interop.catz.*
import zio.clock.Clock
import zio.blocking.Blocking
import zio.ZIO
import shared.newtypes.NewExtractor
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

type AggregateStore[Id, Meta, EventData] =
  Has[AggregateStore.Service[Id, Meta, EventData]]

object AggregateStore:
  trait Service[Id, Meta, EventData]:
    def streamEventsFrom(
      from: Option[SequenceId],
      to: Option[SequenceId]
    ): Stream[Task, Chunk[Event[Id, Meta, EventData]]]

    def streamEventsForIdsFrom(
      from: Option[SequenceId],
      to: Option[SequenceId],
      id: NonEmptyList[Id]
    ): Stream[Task, Chunk[Event[Id, Meta, EventData]]]

    def persistEvents(
      events: NonEmptyList[(Id, Meta, PrincipalId, EventData)]
    ): Task[Unit]

    def persistEventsForId(
      id: Id,
      meta: Meta,
      createdBy: PrincipalId,
      events: NonEmptyList[EventData]
    ): Task[Unit]

    def getLastSequenceId: Task[SequenceId]

  def apply[Agg](using
    agg: Aggregate[Agg],
    t1: Tag[agg.Id],
    t2: Tag[agg.Meta],
    t3: Tag[agg.EventData]
  ) = ServiceOps[agg.Id, agg.Meta, agg.EventData]

  class ServiceOps[Id, Meta, EventData](using
    t1: Tag[Id],
    t2: Tag[Meta],
    t3: Tag[EventData]
  ):
    def streamEventsFrom(
      from: Option[SequenceId],
      to: Option[SequenceId]
    ): RIO[AggregateStore[Id, Meta, EventData], Stream[
      Task,
      Chunk[Event[Id, Meta, EventData]]
    ]] = ZIO.access[AggregateStore[Id, Meta, EventData]](
      _.get.streamEventsFrom(from, to)
    )

    def streamEventsForIdsFrom(
      from: Option[SequenceId],
      to: Option[SequenceId],
      id: NonEmptyList[Id]
    ): RIO[AggregateStore[Id, Meta, EventData], Stream[
      Task,
      Chunk[Event[Id, Meta, EventData]]
    ]] =
      ZIO.access[AggregateStore[Id, Meta, EventData]](
        _.get.streamEventsForIdsFrom(from, to, id)
      )

    def persistEvents(
      events: NonEmptyList[(Id, Meta, PrincipalId, EventData)]
    ): RIO[AggregateStore[Id, Meta, EventData], Unit] =
      ZIO.accessM[AggregateStore[Id, Meta, EventData]](
        _.get.persistEvents(events)
      )

    def persistEventsForId(
      id: Id,
      meta: Meta,
      createdBy: PrincipalId,
      events: NonEmptyList[EventData]
    ): RIO[AggregateStore[Id, Meta, EventData], Unit] =
      ZIO.accessM[AggregateStore[Id, Meta, EventData]](
        _.get.persistEventsForId(id, meta, createdBy, events)
      )

    def getLastSequenceId(using
      t1: Tag[Id],
      t2: Tag[Meta],
      t3: Tag[EventData]
    ) =
      ZIO.accessM[AggregateStore[Id, Meta, EventData]](
        _.get.getLastSequenceId
      )
