package eventsourcing.test.domain

import eventsourcing.all.*
import shared.json.all.{*, given}
import cats.data.NonEmptyList
import doobie.util.update.Update
import zio.Task
import doobie.*
import doobie.implicits.*
import cats.syntax.all.*
import shared.postgres.all.{given, *}
import zio.interop.catz.*
import fs2.Chunk
import zio.ZIO
import zio.RIO

case class AccountDetails(
  email: Option[String],
  password: Option[String],
  passwordUpdatedCount: Int,
  emailUpdatedCount: Int
)

object AccountDetails:
  inline given view: AggregateView.Schemaless[
    AccountDetails,
    AccountId,
    AccountMeta,
    AccountId,
    Account *: EmptyTuple
  ] =
    AggregateView.schemaless[
      AccountDetails,
      AccountId,
      AccountMeta,
      AccountId,
      Account *: EmptyTuple
    ](
      "account_details",
      1,
      evs =>
        evs.map(
          _.on[Account](ev => ev.id)
        ),
      (state, ev) =>
        ev.on[Account](ev =>
          val state_ = state.getOrElse(Map.empty)
          val viewState = state_
            .get(ev.id)
            .getOrElse(
              Schemaless(
                id = ev.id,
                meta = ev.meta,
                createdBy = ev.createdBy,
                lastUpdatedBy = ev.createdBy,
                deleted = false,
                created = ev.created,
                lastUpdated = ev.created,
                data = AccountDetails(None, None, 0, 0)
              )
            )

          val newState = ev.data match
            case AccountCreated(email, pass) =>
              state_.updated(
                ev.id,
                viewState
                  .copy(data =
                    viewState.data
                      .copy(email = Some(email), password = Some(pass), 0, 0)
                  )
              )
            case AccountPasswordUpdated(pass) =>
              state_.updated(
                ev.id,
                viewState.copy(data =
                  viewState.data.copy(
                    password = Some(pass),
                    passwordUpdatedCount =
                      viewState.data.passwordUpdatedCount + 1
                  )
                )
              )
            case AccountEmailUpdated(email) =>
              state_.updated(
                ev.id,
                viewState.copy(data =
                  viewState.data
                    .copy(
                      email = Some(email),
                      emailUpdatedCount = viewState.data.emailUpdatedCount + 1
                    )
                )
              )
          Right(newState)
        )
    )
