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
import zio.blocking.Blocking
import java.util.UUID
import zio.Has
trait Aggregate[Agg]:
  type Name <: String
  type Id
  type Meta
  type EventData
  type Command
  type CommandError = Throwable

  def storeName: String

  def aggregate: (
    agg: Option[Agg],
    event: Event[Id, Meta, EventData]
  ) => Either[AggregateError, Agg]

  def defaultView: (
    view: Schemaless[Id, Meta, Agg],
    event: Event[Id, Meta, EventData]
  ) => Schemaless[Id, Meta, Agg] = (v, _) => v

  def handleCommand: (
    agg: Option[Agg],
    cmd: Command
  ) => Either[CommandError, NonEmptyList[EventData]]

  def invalidAggregate(
    agg: Option[Agg],
    event: Event[Id, Meta, EventData],
    message: String = "Invalid aggregate"
  ) = Left(
    AggregateError.InvalidAggregate(agg, event, message)
  )

  def reject[E](
    err: E
  ) = Left(err)

  def rejectUnmatched(
    agg: Option[Agg],
    cmd: Command,
    message: String = "Command rejected"
  ) = Left(
    new Exception(s"Aggregate: ${aggregate}, Command: ${cmd}, ${message}")
  )

object Aggregate:
  type Aux[Agg, Id_, Meta_, EventData_, Command_] =
    Aggregate[Agg] {
      type Id = Id_
      type Meta = Meta_
      type EventData = EventData_
      type Command = Command_
    }

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
    AggregateService
      .ServiceOps[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command]
