package eventsourcing.test.infra

import eventsourcing.test.domain as D
import zio.ULayer
import shared.postgres.all.{*, given}
import zio.{ZEnv, ZLayer}
import eventsourcing.domain.types.*
import shared.principals.PrincipalId
import cats.data.NonEmptyList
import shared.logging.all.Logging
import zio.blocking.Blocking
import shared.data.all.{*, given}
import zio.Has
import izumi.reflect.Tag
import eventsourcing.all.*
import zio.duration.durationInt

object PostgresAccountV2Service:
  def live =
    PostgresAggregateService
      .live[
        "Accounts",
        AccountV2.AccountData,
        D.AccountId,
        AccountV2.AccountMeta,
        AccountV2.AccountEvent,
        D.AccountV2.Account,
        D.AccountV2.AccountMeta,
        D.AccountV2.AccountEvent,
      ](
        x => Right(x.data.auto.to[D.AccountV2.Account]),
        _.auto.to[D.AccountV2.AccountMeta],
        (id, ev) =>
          Right(ev match
            case a: AccountV2.AccountEvent.AccountCreated =>
              a.auto.to[D.AccountV2.AccountCreated]
            case a: AccountV2.AccountEvent.AccountPasswordUpdated =>
              a.auto.to[D.AccountV2.AccountPasswordUpdated]
            case a: AccountV2.AccountEvent.AccountEmailUpdated =>
              a.auto.to[D.AccountV2.AccountEmailUpdated]
          ),
        _.auto.to[AccountV2.AccountData],
        _.auto.to[AccountV2.AccountMeta],
        x =>
          x match
            case a: D.AccountV2.AccountCreated =>
              a.auto.to[AccountV2.AccountEvent.AccountCreated]
            case a: D.AccountV2.AccountPasswordUpdated =>
              a.auto.to[AccountV2.AccountEvent.AccountPasswordUpdated]
            case a: D.AccountV2.AccountEmailUpdated =>
              a.auto.to[AccountV2.AccountEvent.AccountEmailUpdated]
        ,
        "test",
        1.minutes
      )
