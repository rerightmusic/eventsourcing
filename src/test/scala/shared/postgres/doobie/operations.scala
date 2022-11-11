package shared.postgres.doobie

import _root_.doobie.implicits.*
import shared.postgres.all.{given, *}
import shared.json.all.{given, *}
import zio.interop.catz.*
import java.util.UUID
import java.time.OffsetDateTime
import cats.data.NonEmptyList
import shared.principals.*
import doobie.util.transactor.Transactor
import zio.Task
import scala.util.Random
import zio.{ZIO, Runtime}
import shared.logging.all.*
import scala.util.Properties
import shared.data.all.*
import shared.config.{all as Conf, Env}
import java.io.File

def insertData(
  transact: (
    txn: Transactor[Task] => Task[Unit]
  ) => Unit,
  data: NonEmptyList[ExampleDocumentData]
) =
  val id = UUID.randomUUID
  val docs = data.map(d =>
    PostgresDocument(
      Id(id),
      (),
      PrincipalId("principal"),
      PrincipalId("principal"),
      d,
      1,
      false,
      OffsetDateTime.now,
      OffsetDateTime.now
    )
  )
  transact(txn =>
    insertInto(
      "test.example",
      docs
    ).transact[Task](txn).unit
  )
  Id(id)

def randomString = Random.alphanumeric.filter(_.isLetter).take(10).mkString

def runSync[E <: Throwable, A](_zio: ZIO[zio.Scope, E, A]) =
  zio.Unsafe.unsafe { implicit u =>
    Runtime.default.unsafe.run(ZIO.scoped(_zio)).getOrThrowFiberFailure()
  }

val preConfEnv = defaultLogger("test")
val pgConfig = runSync(for
  _ <- ZIO.unit
  envFile = Properties
    .envOrNone("TESTS_ENV_FILE")
    .getOrElse(s"${System.getProperty("user.dir")}/../tests.env")
  envVars <- Conf
    .getEnvVars(List(new File(envFile)))
    .provideLayer(preConfEnv)
  pgConfig <- PostgresConfig.fromEnv["Test"](envVars)
yield pgConfig)

def transact[A](f: Transactor[Task] => Task[A]) =
  runSync(for
    te <- ZIO.executor.map(_.asExecutionContext)
    layer = preConfEnv >>> WithTransactor.live(pgConfig, te)
    res <- ZIO
      .environmentWithZIO[WithTransactor["Test"]](x => f(x.get.transactor))
      .provideLayer(layer)
  yield res)
