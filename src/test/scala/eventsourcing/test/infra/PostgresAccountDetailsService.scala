package eventsourcing.test.infra

import eventsourcing.test.domain as D
import zio.ULayer
import shared.postgres.all.{*, given}
import zio.{ZEnv, ZLayer}
import shared.principals.PrincipalId
import cats.data.NonEmptyList
import shared.logging.all.Logging
import zio.blocking.Blocking
import shared.data.all.{*, given}
import zio.Has
import eventsourcing.all.*
import zio.duration.durationInt

object PostgresAccountDetailsService:
  def live = PostgresAggregateViewService.schemaless[
    "Accounts",
    AccountMeta,
    D.AccountMeta,
    AccountDetailsData,
    D.AccountDetails
  ](
    x => x.auto.to[D.AccountMeta],
    x => x.auto.to[AccountMeta],
    x => Right(x.data.auto.to[D.AccountDetails]),
    x => Right(x.auto.to[AccountDetailsData]),
    s => s.defaultReadState,
    i => Some(i) *: EmptyTuple,
    "test",
    1.minutes
  )
