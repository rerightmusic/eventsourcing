package eventsourcing.domain

import types.*
import zio.UIO
import cats.data.NonEmptyList
import zio.Task
import shared.principals.PrincipalId
import zio.RIO
import fs2.*
import zio.interop.catz.*
import zio.ZIO
import shared.newtypes.NewExtractor
import java.util.UUID
import scala.concurrent.duration.FiniteDuration
import doobie.ConnectionIO

trait AggregateStore[Id, Meta, EventData]:
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

  def persistEventsWithTransaction[A](
    events: NonEmptyList[Event[Id, Meta, EventData]],
    f: (persist: () => ConnectionIO[Unit]) => ConnectionIO[A]
  ): Task[A]

  def persistEventsForId(
    id: Id,
    meta: Meta,
    createdBy: PrincipalId,
    events: NonEmptyList[EventData]
  ): Task[Unit]

  def getLastSequenceId: Task[SequenceId]

object AggregateStore:
  def apply[Agg](using
    agg: Aggregate[Agg]
  )(using
    tId: zio.Tag[agg.Id],
    tMeta: zio.Tag[agg.Meta],
    tEventData: zio.Tag[agg.EventData]
  ) = ServiceOps[agg.Id, agg.Meta, agg.EventData]

  class ServiceOps[Id, Meta, EventData](using
    tId: zio.Tag[Id],
    tMeta: zio.Tag[Meta],
    tEventData: zio.Tag[EventData]
  ):
    def streamEventsFrom(
      from: Option[SequenceId],
      to: Option[SequenceId]
    ): RIO[AggregateStore[Id, Meta, EventData], Stream[
      Task,
      Chunk[Event[Id, Meta, EventData]]
    ]] = ZIO.environmentWith[AggregateStore[Id, Meta, EventData]](
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
      ZIO.environmentWith[AggregateStore[Id, Meta, EventData]](
        _.get.streamEventsForIdsFrom(from, to, id)
      )

    def persistEvents(
      events: NonEmptyList[(Id, Meta, PrincipalId, EventData)]
    ): RIO[AggregateStore[Id, Meta, EventData], Unit] =
      ZIO.environmentWithZIO[AggregateStore[Id, Meta, EventData]](
        _.get.persistEvents(events)
      )

    def persistEventsForId(
      id: Id,
      meta: Meta,
      createdBy: PrincipalId,
      events: NonEmptyList[EventData]
    ): RIO[AggregateStore[Id, Meta, EventData], Unit] =
      ZIO.environmentWithZIO[AggregateStore[Id, Meta, EventData]](
        _.get.persistEventsForId(id, meta, createdBy, events)
      )

    def getLastSequenceId =
      ZIO.environmentWithZIO[AggregateStore[Id, Meta, EventData]](
        _.get.getLastSequenceId
      )
