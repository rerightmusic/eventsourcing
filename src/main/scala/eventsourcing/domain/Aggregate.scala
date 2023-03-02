package eventsourcing.domain

import cats.data.NonEmptyList
import types.*

trait Aggregate[Agg]:
  type Name <: String
  type Id
  type Meta
  type EventData
  type Command
  type CommandError = Throwable

  val storeName: String
  val schemaVersion: Int
  lazy val versionedStoreName = s"${storeName}_v${schemaVersion}"

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
    AggregateError.InvalidAggregate(agg, event, versionedStoreName, message)
  )

  def reject[E](
    err: E
  ) = Left(err)

  def rejectUnmatched(
    agg: Option[Agg],
    cmd: Command,
    message: String = "Command rejected"
  ) = Left(
    new Exception(s"Aggregate: ${agg}, Command: ${cmd}, ${message}")
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
  ) =
    AggregateService
      .ServiceOps[Agg, agg.Id, agg.Meta, agg.EventData, agg.Command]
