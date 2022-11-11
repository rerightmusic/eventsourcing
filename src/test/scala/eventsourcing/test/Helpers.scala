package eventsourcing.test

import domain.*
import infra as I
import zio.{ZIO, Runtime}
import scala.util.Random
import scala.util.Properties
import doobie.util.transactor.Transactor
import eventsourcing.all.*
import zio.{System => _, *}
import shared.logging.all.*
import shared.postgres.doobie.WithTransactor
import shared.config.{all as Conf}
import shared.postgres.PostgresConfig
import java.io.File
import shared.principals.PrincipalId
import cats.data.NonEmptyList
import java.util.UUID
import zio.{EnvironmentTag => Tag}
import cats.syntax.all.*
import zio.interop.catz.*
import zio.Fiber
import org.scalatest.compatible.Assertion

type AppEnv = WithTransactor["Accounts"] & AggregateService[Account] &
  AggregateViewService[Account] &
  AggregateService[AccountBackwardsCompatible.Account] &
  AggregateViewService[AccountBackwardsCompatible.Account] &
  AggregateService[Profile] & AggregateViewService[Profile] &
  AggregateViewService[AccountDetails] &
  AggregateViewService[AccountAnalytics] &
  AggregateViewService[AccountProfile] &
  AggregateService[
    AccountBackwardsIncompatible.Account
  ] &
  AggregateViewService[
    AccountBackwardsIncompatible.Account
  ] &
  AggregateViewService[
    AggregateMigration[AccountBackwardsIncompatible.Account]
  ] & FiberState

def runSync[R >: AppEnv, E <: Throwable, A](
  _zio: ZIO[R, E, A]
): A =
  zio.Unsafe.unsafe { implicit u =>
    Runtime.default.unsafe
      .run(ZIO.scoped(for
        fiberRef <- Ref.make[List[Fiber.Runtime[Any, Any]]](List())
        res <- (for
          te <- ZIO.executor.map(_.asExecutionContext)
          envFile = Properties
            .envOrNone("TESTS_ENV_FILE")
            .getOrElse(s"${System.getProperty("user.dir")}/../tests.env")
          preConfEnv = defaultLogger("aggregates-tests")
          envVars <- Conf
            .getEnvVars(List(new File(envFile)))
            .provideLayer(preConfEnv)
          pgConfig <- PostgresConfig.fromEnv["Accounts"](envVars)
          layer: ZLayer[Scope, Throwable, AppEnv] =
            preConfEnv >>>
              WithTransactor.live["Accounts"](pgConfig, te) >+>
              I.PostgresAccountService.live >+>
              I.PostgresAccountBackwardsCompatibleService.live >+>
              I.PostgresProfileService.live >+>
              I.PostgresAccountDetailsService.live >+>
              I.PostgresAccountAnalyticsService.live >+>
              I.PostgresAccountProfileService.live >+>
              I.PostgresAccountBackwardsIncompatibleService.live >+>
              I.PostgresAccountBackwardsIncompatibleService.migration >+>
              ZLayer.succeed(FiberState(fiberRef))
          env <- ZIO.environment[AppEnv].provideLayer(layer)
          r <- _zio.provideLayer(layer)
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
      yield res))
      .getOrThrowFiberFailure()
  }

def transactM[A](f: Transactor[Task] => Task[A])(using
  Tag[WithTransactor["Accounts"]]
) = ZIO.serviceWithZIO[WithTransactor["Accounts"]](x => f(x.transactor))

def transact[A](f: Transactor[Task] => Task[A])(using
  Tag[WithTransactor["Accounts"]]
) = runSync(transactM(f))

def logAndThrow = (e: Throwable) =>
  println(e)
  throw e

// Creating a function and then calling it here is necessary otherwise the following exception occurs
// java.lang.ClassCastException: class org.scalatest.Succeeded$ cannot be cast to class scala.runtime.BoxedUnit
// (org.scalatest.Succeeded$ and scala.runtime.BoxedUnit are in unnamed module of loader 'app')
def zassert(a: => Assertion): Task[Unit] = ZIO
  .attempt(() => a)
  .map(_())
  .map(_ => ())
  .catchAllDefect(e =>
    for
      fId <- ZIO.fiberId
      _ <- ZIO
        .failCause(Cause.die(e, zio.StackTrace.fromJava(fId, e.getStackTrace)))
    yield ()
  )

case class FiberState(ref: Ref[List[Fiber.Runtime[Any, Any]]])
def fork[E, A](
  f: => ZIO[AppEnv, E, A]
): RIO[FiberState & AppEnv, Fiber.Runtime[E, A]] =
  for
    ref <- ZIO.service[FiberState].map(_.ref)
    value <- ref.get
    fib <- f.fork
    _ <- ref.set(value ++ List(fib))
  yield fib
