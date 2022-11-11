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

object PostgresAccountBackwardsCompatibleService:
  def live =
    PostgresAggregateService
      .live[
        "Accounts",
        AccountBackwardsCompatible.AccountData,
        D.AccountId,
        AccountBackwardsCompatible.AccountMeta,
        AccountBackwardsCompatible.AccountEvent,
        D.AccountBackwardsCompatible.Account,
        D.AccountBackwardsCompatible.AccountMeta,
        D.AccountBackwardsCompatible.AccountEvent,
      ](
        x => Right(x.data.auto.to[D.AccountBackwardsCompatible.Account]),
        _.auto.to[D.AccountBackwardsCompatible.AccountMeta],
        (id, ev) =>
          Right(ev match
            case a: AccountBackwardsCompatible.AccountEvent.AccountCreated =>
              a.auto.to[D.AccountBackwardsCompatible.AccountCreated]
            case a: AccountBackwardsCompatible.AccountEvent.AccountPasswordUpdated =>
              a.auto.to[D.AccountBackwardsCompatible.AccountPasswordUpdated]
            case a: AccountBackwardsCompatible.AccountEvent.AccountEmailUpdated =>
              a.auto.to[D.AccountBackwardsCompatible.AccountEmailUpdated]
          ),
        x => Right(x.auto.to[AccountBackwardsCompatible.AccountData]),
        _.auto.to[AccountBackwardsCompatible.AccountMeta],
        x =>
          Right(x match
            case a: D.AccountBackwardsCompatible.AccountCreated =>
              a.auto.to[AccountBackwardsCompatible.AccountEvent.AccountCreated]
            case a: D.AccountBackwardsCompatible.AccountPasswordUpdated =>
              a.auto.to[
                AccountBackwardsCompatible.AccountEvent.AccountPasswordUpdated
              ]
            case a: D.AccountBackwardsCompatible.AccountEmailUpdated =>
              a.auto
                .to[AccountBackwardsCompatible.AccountEvent.AccountEmailUpdated]
          ),
        "test",
        1.minutes
      )
