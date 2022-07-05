package shared.postgres.schemaless

object all:
  export operations.*
  type PostgresDocument[I, M, D] =
    shared.postgres.schemaless.PostgresDocument[I, M, D]
  val PostgresDocument = shared.postgres.schemaless.PostgresDocument
