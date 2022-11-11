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

case class AccountAnalytics(
  totalEmails: Int,
  totalPasswords: Int,
  totalEmailUpdates: Int,
  totalPasswordUpdates: Int,
  averageEmailLength: Double,
  averagePasswordLength: Double
)

object AccountAnalytics:
  inline given view: AggregateView.Fold[
    AccountAnalytics,
    Account *: EmptyTuple
  ] =
    AggregateView.fold[AccountAnalytics, Account *: EmptyTuple](
      "account_analytics",
      1,
      (state, ev) =>
        ev.on[Account](ev =>
          val state_ = state.getOrElse(AccountAnalytics(0, 0, 0, 0, 0, 0))
          val newState = ev.data match
            case AccountCreated(email, pass) =>
              state_.copy(
                totalEmails = state_.totalEmails + 1,
                totalPasswords = state_.totalPasswords + 1,
                averageEmailLength =
                  (state_.averageEmailLength * (state_.totalEmails - 1) + email.length) / (state_.totalEmails + 1),
                averagePasswordLength =
                  (state_.averagePasswordLength * (state_.totalPasswords - 1) + email.length) / (state_.totalPasswords + 1),
              )
            case AccountPasswordUpdated(pass) =>
              state_.copy(
                totalPasswords = state_.totalPasswords + 1,
                totalPasswordUpdates = state_.totalPasswordUpdates + 1,
                averagePasswordLength =
                  (state_.averagePasswordLength * (state_.totalPasswords - 1) + pass.length) / (state_.totalPasswords + 1),
              )
            case AccountEmailUpdated(email) =>
              state_.copy(
                totalEmails = state_.totalEmails + 1,
                totalEmailUpdates = state_.totalEmailUpdates + 1,
                averageEmailLength =
                  (state_.averageEmailLength * (state_.totalEmails - 1) + email.length) / (state_.totalEmails + 1),
              )
          Right(newState)
        )
    )
