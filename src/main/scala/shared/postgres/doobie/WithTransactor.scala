package shared.postgres.doobie

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import _root_.doobie.hikari.*
import _root_.doobie.*
import _root_.doobie.implicits.*
import _root_.doobie.util.transactor.Transactor
import shared.data.extensions.Null.*
import shared.health.{HealthCheck, Healthy, LeafHealthCheck, Unhealthy}
import shared.logging.all.Logging
import shared.postgres.PostgresConfig
import zio.*
import zio.interop.catz.*
import zio.interop.catz.implicits.*
import doobie.util.*

import javax.sql.DataSource
import scala.concurrent.ExecutionContext
import izumi.reflect.Tag
import zio.blocking.Blocking
import scala.concurrent.ExecutionContextExecutor

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
            e => Unhealthy(e.getMessage.getOrElse("No message")),
            _ => Healthy()
          )
    )

  def copy[S_ <: String](using
    Tag[WithTransactor[S_]]
  ): WithTransactor[S_] =
    val txn = this.transactor
    new WithTransactor:
      val transactor = txn
}

object WithTransactor {
  def live[S <: String](
    conf: PostgresConfig[S],
    ec: ExecutionContext
  )(using
    Tag[WithTransactor[S]]
  ): ZLayer[Logging, Throwable, Has[WithTransactor[S]]] =
    ZLayer
      .fromFunctionManaged[Logging, Throwable, WithTransactor[S]](log =>
        for
          rt <- ZIO.runtime[Any].toManaged_
          dispatcher =
            rt
              .unsafeRun(
                cats.effect.std
                  .Dispatcher[zio.Task]
                  .allocated
              )
              ._1
          config = {
            val config = new HikariConfig

            config.setUsername(conf.user)
            config.setPassword(conf.pass)
            config.setDriverClassName("org.postgresql.Driver")

            conf.instanceConnectionName match
              case None =>
                config.setJdbcUrl(
                  s"jdbc:postgresql://${conf.host}:${conf.port}/${conf.database}"
                )
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

            config.setMinimumIdle(3);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(30000);
            config.setMaxLifetime(1800000);
            config.setLeakDetectionThreshold(48000);
            config.setMaximumPoolSize(conf.poolSize)
            config.setLeakDetectionThreshold(60000)
            config
          }
          xa <- HikariTransactor
            .fromHikariConfig[Task](
              config,
              rt.platform.executor.asEC
            )
            .toManaged(summon, dispatcher)
          _ <- log.get
            .info(
              s"Postgres connected: ${conf.host}, pool size = ${conf.poolSize}"
            )
            .toManaged_
        yield new WithTransactor[S] {
          val transactor: Transactor[Task] = xa
        }
      )
}
