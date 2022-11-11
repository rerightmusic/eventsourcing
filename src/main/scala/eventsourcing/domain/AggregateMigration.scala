package eventsourcing.domain

import types.*
trait AggregateMigration[Agg]:
  type Id
  type Meta
  type EventData
  type MigratedAgg
  type MigratedMeta
  type MigratedEventData
  def migrate: (Event[Id, MigratedMeta, MigratedEventData]) => List[
    Event[Id, Meta, EventData]
  ]

  def migrate_(using
    agg: Aggregate.Aux[Agg, Id, Meta, EventData, ?]
  ): (Event[Id, MigratedMeta, MigratedEventData]) => List[
    Event[Id, Meta, EventData]
  ] = ev => migrate(ev).map(_.copy(schemaVersion = agg.schemaVersion))

object AggregateMigration:
  type Aux[
    Agg,
    Id_,
    Meta_,
    EventData_,
    MigratedAgg_,
    MigratedMeta_,
    MigratedEventData_
  ] = AggregateMigration[Agg] {
    type Id = Id_
    type Meta = Meta_
    type EventData = EventData_
    type MigratedAgg = MigratedAgg_
    type MigratedMeta = MigratedMeta_
    type MigratedEventData = MigratedEventData_
  }
