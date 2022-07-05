package eventsourcing.test

import domain.*
import infra as I
import zio.{ZIO, ZEnv, Runtime}
import scala.util.Random
import scala.util.Properties
import doobie.util.transactor.Transactor
import eventsourcing.all.*
import zio.blocking.Blocking
import zio.*
import shared.logging.all.Logging
import zio.Has
import shared.postgres.doobie.WithTransactor
import shared.config.{all as Conf}
import shared.postgres.PostgresConfig
import java.io.File
import shared.principals.PrincipalId
import cats.data.NonEmptyList
import java.util.UUID
import zio.clock.Clock
import izumi.reflect.Tag
import cats.syntax.all.*
import zio.interop.catz.*
import zio.duration.durationInt
import zio.Fiber
import org.scalatest.compatible.Assertion

type AppEnv = ZEnv & Logging & Has[WithTransactor["Accounts"]] &
  AggregateService[Account] & AggregateService[AccountV2.Account] &
  AggregateService[Profile] & AggregateViewService[AccountDetails] &
  AggregateViewService[AccountAnalytics] &
  AggregateViewService[AccountProfile] & Has[FiberState]

def runSync[E <: Throwable, A](
  zio: ZIO[AppEnv, E, A]
): A =
  Runtime.default
    .unsafeRunSync(
      for
        fiberRef <- Ref.make[List[Fiber.Runtime[Any, Any]]](List())
        res <- (for
          te <- ZIO.access[Blocking](_.get.blockingExecutor.asEC)
          envFile = Properties
            .envOrNone("TESTS_ENV_FILE")
            .getOrElse(s"${System.getProperty("user.dir")}/tests.env")
          envVars <- Conf.getEnvVars(List(new File(envFile)))
          pgConfig <- PostgresConfig.fromEnv["Accounts"](envVars)
          layer =
            Logging
              .console(
                "aggregates-tests"
              ) >+>
              WithTransactor.live["Accounts"](pgConfig, te) >+>
              ZEnv.live >+>
              I.PostgresAccountService.live >+>
              I.PostgresAccountV2Service.live >+>
              I.PostgresProfileService.live >+>
              I.PostgresAccountDetailsService.live >+>
              I.PostgresAccountAnalyticsService.live >+>
              I.PostgresAccountProfileService.live >+>
              ZLayer.succeed(FiberState(fiberRef))
          r <- zio.provideCustomLayer(layer)
        yield r)
          .onExit(e =>
            for
              fibers <- fiberRef.get
              _ <-
                if fibers.nonEmpty then
                  println(s"Fibers ${fibers.length} killed")
                  Fiber.interruptAll(fibers)
                else ZIO.unit
            yield ()
          )
      yield res
    )
    .fold(
      e => throw e.squash,
      a => a
    )

def transactM[A](f: Transactor[Task] => Task[A])(using
  Tag[WithTransactor["Accounts"]]
) = ZIO.serviceWith[WithTransactor["Accounts"]](x => f(x.transactor))

def transact[A](f: Transactor[Task] => Task[A])(using
  Tag[WithTransactor["Accounts"]]
) = runSync(transactM(f))

def logAndThrow = (e: Throwable) =>
  println(e)
  throw e

def zassert(a: => Assertion): Task[Unit] = ZIO.effect(a)

case class FiberState(ref: Ref[List[Fiber.Runtime[Any, Any]]])
def fork[E, A](
  f: => ZIO[AppEnv, E, A]
): RIO[Has[FiberState] & AppEnv, Fiber.Runtime[E, A]] =
  for
    ref <- ZIO.service[FiberState].map(_.ref)
    value <- ref.get
    fib <- f.fork
    _ <- ref.set(value ++ List(fib))
  yield fib
