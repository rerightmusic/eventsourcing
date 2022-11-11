package shared.postgres.doobie

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import _root_.doobie.hikari.*
import _root_.doobie.*
import _root_.doobie.implicits.*
import _root_.doobie.util.transactor.Transactor
import shared.data.extensions.Null.*
import shared.health.{HealthCheck, Healthy, LeafHealthCheck, Unhealthy}
import shared.postgres.PostgresConfig
import zio.*
import zio.interop.catz.*
import zio.interop.catz.implicits.*
import doobie.util.*
import shared.json.all.{given, *}
import javax.sql.DataSource
import scala.concurrent.ExecutionContext
import zio.Tag

abstract class WithTransactor[S <: String] {
  def name(using witness: ValueOf[S]): String = witness.value
  val transactor: Transactor[Task]

  def healthCheck(using witness: ValueOf[S]): HealthCheck =
    LeafHealthCheck(
      name,
      () =>
        sql"SELECT 1"
          .query[Int]
          .to[List]
          .transact[Task](transactor)
          .fold(
            e =>
              Unhealthy(
                Some(
                  Map(
                    "message" -> e.getMessage.getOrElse("No message")
                  ).toJsonASTOrFail
                )
              ),
            _ => Healthy()
          )
    )

  def copy[S_ <: String](using Tag[S_]): WithTransactor[S_] =
    val txn = this.transactor
    new WithTransactor[S_]:
      val transactor = txn
}

object WithTransactor:
  def live[S <: String](
    conf: PostgresConfig[S],
    ec: ExecutionContext
  )(using Tag[S]): ZLayer[Scope, Throwable, WithTransactor[S]] =
    val config = {
      val config = new HikariConfig

      config.setUsername(conf.user)
      config.setPassword(conf.pass)
      config.setDriverClassName("org.postgresql.Driver")

      conf.instanceConnectionName match
        case None =>
          config.setJdbcUrl(
            s"jdbc:postgresql://${conf.host}:${conf.port}/${conf.database}"
          )
          config.addDataSourceProperty("tcpKeepAlive", true)
        case Some(connectionName) =>
          config.setJdbcUrl(
            String.format("jdbc:postgresql:///%s", conf.database)
          )
          config.addDataSourceProperty(
            "socketFactory",
            "com.google.cloud.sql.postgres.SocketFactory"
          )
          config.addDataSourceProperty(
            "cloudSqlInstance",
            connectionName
          )
          config.addDataSourceProperty("ipTypes", "PUBLIC,PRIVATE")
          config.addDataSourceProperty("tcpKeepAlive", true)

      config.setConnectionTimeout(30000);
      config.setMaxLifetime(600000);
      config.setLeakDetectionThreshold(48000);
      config.setMaximumPoolSize(conf.poolSize)
      config.setLeakDetectionThreshold(60000)
      config
    }
    ZLayer.fromZIO(
      for
        ec <- ZIO.executor.map(_.asExecutionContext)
        xa <-
          HikariTransactor
            .fromHikariConfig[Task](
              config,
              ec
            )
            .toScopedZIO
        _ <- ZIO
          .logInfo(
            s"Postgres connected: ${conf.host}, pool size = ${conf.poolSize}"
          )
      yield new WithTransactor[S]:
        val transactor: Transactor[Task] = xa
    )

  def transactor[S <: String](using Tag[S]) =
    ZIO.service[WithTransactor[S]].map(_.transactor)
