package shared.postgres

import zio.{Tag, Task, ZIO}
import shared.data.all.*

case class PostgresConfig[S <: String](
  user: String,
  pass: String,
  database: String,
  host: String,
  port: Int,
  instanceConnectionName: Option[String],
  poolSize: Int
)

object PostgresConfig:
  def fromEnv[S <: String](
    env: Map[String, String]
  ): Task[PostgresConfig[S]] =
    for
      pgUser <- env
        .get("PG_USER")
        .getOrFail("PG_USER missing")
      pgPass <- env
        .get("PG_PASS")
        .getOrFail("PG_PASS missing")
      pgHost <- env.get("PG_HOST").getOrFail("PG_HOST missing")
      pgPort <- env.get("PG_PORT") match
        case None => ZIO.succeed(5432)
        case Some(p) =>
          p.toIntOption.getOrFail("PG_PORT is invalid")
      pgDb <- env.get("PG_DB").getOrFail("PG_DB missing")
      pgInstanceConnectionName = env
        .get("PG_INSTANCE_CONNECTION_NAME")
      pgPoolSize <- env
        .get("PG_POOL_SIZE")
        .flatMap(_.toIntOption)
        .getOrFail("PG_POOL_SIZE is invalid")
    yield PostgresConfig(
      user = pgUser,
      pass = pgPass,
      database = pgDb,
      host = pgHost,
      port = pgPort,
      instanceConnectionName = pgInstanceConnectionName,
      poolSize = pgPoolSize
    )
