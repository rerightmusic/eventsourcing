package eventsourcing.infra

object PostgresAggregateViewStore
    extends PostgresSchemalessAggregateViewStore
    with PostgresFoldAggregateViewStore
