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
import zio.{ZIO, Runtime, ZEnv, Has}
import zio.blocking.Blocking
import shared.logging.all.*
import scala.util.Properties
import shared.data.all.*
import shared.config.{all as Conf}
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

def runSync[E <: Throwable, A](zio: ZIO[ZEnv, E, A]) = Runtime.default
  .unsafeRunSync(zio)
  .fold(
    e => throw e.squash,
    a => a
  )

val pgConfig = runSync(for
  _ <- ZIO.unit
  envFile = Properties
    .envOrNone("TESTS_ENV_FILE")
    .getOrElse(s"${System.getProperty("user.dir")}/tests.env")
  envVars <- Conf.getEnvVars(List(new File(envFile)))
  pgConfig <- PostgresConfig.fromEnv["Test"](envVars)
yield pgConfig)

def transact[A](f: Transactor[Task] => Task[A]) =
  runSync(for
    te <- ZIO.access[Blocking](_.get.blockingExecutor.asEC)
    layer = Logging.console("test") >>> WithTransactor.live(pgConfig, te)
    res <- ZIO
      .accessM[Has[WithTransactor["Test"]]](x => f(x.get.transactor))
      .provideLayer(layer)
  yield res)
