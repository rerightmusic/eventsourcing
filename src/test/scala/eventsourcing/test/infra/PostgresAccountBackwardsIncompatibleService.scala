package eventsourcing.test.infra

import eventsourcing.test.domain as D
import zio.ULayer
import shared.postgres.all.{*, given}
import zio.{ZLayer}
import eventsourcing.domain.types.*
import shared.principals.PrincipalId
import cats.data.NonEmptyList
import shared.data.all.{*, given}
import zio.{EnvironmentTag => Tag}
import eventsourcing.all.*
import shared.json.all.given
import zio.durationInt

object PostgresAccountBackwardsIncompatibleService:
  def migration = PostgresAggregateMigrationViewService.live[
    "Accounts",
    D.AccountBackwardsIncompatible.Account
  ]("test", 1.minutes)

  def live =
    PostgresAggregateService
      .live[
        "Accounts",
        AccountBackwardsIncompatible.AccountData,
        D.AccountId,
        AccountMeta,
        AccountBackwardsIncompatible.AccountEvent,
        D.AccountBackwardsIncompatible.Account,
        D.AccountMeta,
        D.AccountBackwardsIncompatible.AccountEvent,
      ](
        x => Right(x.data.auto.to[D.AccountBackwardsIncompatible.Account]),
        _.auto.to[D.AccountMeta],
        (id, ev) =>
          Right(ev match
            case a: AccountBackwardsIncompatible.AccountEvent.AccountCreated =>
              a.auto.to[D.AccountBackwardsIncompatible.AccountCreated]
            case a: AccountBackwardsIncompatible.AccountEvent.AccountPasswordUpdated =>
              a.auto.to[
                D.AccountBackwardsIncompatible.AccountPasswordUpdated
              ]
            case a: AccountBackwardsIncompatible.AccountEvent.AccountEmailsUpdated =>
              a.auto
                .to[D.AccountBackwardsIncompatible.AccountEmailsUpdated]
          ),
        x => Right(x.auto.to[AccountBackwardsIncompatible.AccountData]),
        _.auto.to[AccountMeta],
        x =>
          Right(x match
            case a: D.AccountBackwardsIncompatible.AccountCreated =>
              a.auto.to[
                AccountBackwardsIncompatible.AccountEvent.AccountCreated
              ]
            case a: D.AccountBackwardsIncompatible.AccountPasswordUpdated =>
              a.auto.to[
                AccountBackwardsIncompatible.AccountEvent.AccountPasswordUpdated
              ]
            case a: D.AccountBackwardsIncompatible.AccountEmailsUpdated =>
              a.auto
                .to[
                  AccountBackwardsIncompatible.AccountEvent.AccountEmailsUpdated
                ]
          ),
        "test",
        1.minutes
      )
