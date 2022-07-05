package eventsourcing.infra

object PostgresAggregateViewService
    extends PostgresSchemalessAggregateViewService
    with PostgresFoldAggregateViewService
