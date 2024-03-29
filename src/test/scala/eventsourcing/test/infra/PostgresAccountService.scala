package eventsourcing.test.infra

import eventsourcing.test.domain as D
import shared.postgres.all.{*, given}
import shared.data.all.*
import zio.*
import eventsourcing.all.*
import zio.durationInt

object PostgresAccountService:
  def live =
    PostgresAggregateService
      .live[
        "Accounts",
        AccountData,
        D.AccountId,
        AccountMeta,
        AccountEvent,
        D.Account,
        D.AccountMeta,
        D.AccountEvent,
      ](
        x => Right(x.data.auto.to[D.Account]),
        _.auto.to[D.AccountMeta],
        (_, ev) =>
          Right(ev match
            case a: AccountEvent.AccountCreated =>
              a.auto.to[D.AccountCreated]
            case a: AccountEvent.AccountPasswordUpdated =>
              a.auto.to[D.AccountPasswordUpdated]
            case a: AccountEvent.AccountEmailUpdated =>
              a.auto.to[D.AccountEmailUpdated]
          ),
        x => Right(x.auto.to[AccountData]),
        _.auto.to[AccountMeta],
        x =>
          Right(x match
            case a: D.AccountCreated =>
              a.auto.to[AccountEvent.AccountCreated]
            case a: D.AccountPasswordUpdated =>
              a.auto.to[AccountEvent.AccountPasswordUpdated]
            case a: D.AccountEmailUpdated =>
              a.auto.to[AccountEvent.AccountEmailUpdated]
          ),
        "test",
        1.minutes
      )
