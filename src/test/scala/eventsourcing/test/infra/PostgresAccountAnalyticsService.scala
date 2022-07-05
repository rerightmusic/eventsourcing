package eventsourcing.test.infra

import cats.data.NonEmptyList
import eventsourcing.all.*
import eventsourcing.test.domain as D
import shared.data.all.{_, given}

import shared.logging.all.Logging
import shared.postgres.all.{_, given}
import shared.principals.PrincipalId
import zio.*
import zio.blocking.Blocking
import zio.duration.durationInt

object PostgresAccountAnalyticsService:
  def live = PostgresAggregateViewService.fold[
    "Accounts",
    AccountAnalytics,
    D.AccountAnalytics,
  ](
    x => Right(x.auto.to[D.AccountAnalytics]),
    x => x.auto.to[AccountAnalytics],
    "test",
    1.minutes
  )
