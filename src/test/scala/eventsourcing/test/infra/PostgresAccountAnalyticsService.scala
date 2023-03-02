package eventsourcing.test.infra

import eventsourcing.all.*
import eventsourcing.test.domain as D
import shared.data.all.*
import zio.{given, *}

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
