package eventsourcing.test.domain

import eventsourcing.test.domain as D
import cats.data.NonEmptyList
import java.time.OffsetDateTime
import eventsourcing.all.*
import shared.newtypes.NewtypeWrapped
import java.util.UUID

import shared.newtypes.NewExtractor

object AccountBackwardsIncompatible:
  type AccountEvent = AccountCreated | AccountPasswordUpdated |
    AccountEmailsUpdated
  case class AccountCreated(emails: NonEmptyList[String], password: String)
  case class AccountPasswordUpdated(password: String)
  case class AccountEmailsUpdated(emails: NonEmptyList[String])

  type AccountCommand = CreateAccount | UpdateEmails | UpdatePassword
  case class CreateAccount(emails: NonEmptyList[String], password: String)
  case class UpdateEmails(emails: NonEmptyList[String])
  case class UpdatePassword(password: String)

  case class Account(
    id: D.AccountId,
    emails: NonEmptyList[String],
    password: String,
    previousEmails: List[String]
  )

  object Account:
    given mig: AggregateMigration.Aux[
      Account,
      D.AccountId,
      D.AccountMeta,
      AccountEvent,
      D.Account,
      D.AccountMeta,
      D.AccountEvent,
    ] = new AggregateMigration[Account]:
      type Id = D.AccountId
      type Meta = D.AccountMeta
      type EventData = AccountEvent
      type MigratedAgg = D.Account
      type MigratedMeta = D.AccountMeta
      type MigratedEventData = D.AccountEvent

      def migrate = ev =>
        ev.data match
          case D.AccountCreated(email, pass) =>
            List(ev.copy(data = AccountCreated(NonEmptyList.of(email), pass)))
          case D.AccountPasswordUpdated(pass) =>
            List(ev.copy(data = AccountPasswordUpdated(pass)))
          case D.AccountEmailUpdated(email) =>
            List(ev.copy(data = AccountEmailsUpdated(NonEmptyList.of(email))))

    given agg: Aggregate.Aux[
      Account,
      D.AccountId,
      D.AccountMeta,
      AccountEvent,
      AccountCommand
    ] =
      new Aggregate[Account]:
        type Id = D.AccountId
        type Meta = D.AccountMeta
        type EventData = AccountEvent
        type Command = AccountCommand

        val storeName = "accounts"
        val schemaVersion = 2

        def aggregate = (x, ev) =>
          (x, ev.data) match
            case (
                  None,
                  AccountCreated(emails, password)
                ) =>
              Right(Account(ev.id, emails, password, List()))
            case (
                  Some(acc),
                  AccountPasswordUpdated(pass)
                ) =>
              Right(acc.copy(password = pass))
            case (
                  Some(acc),
                  AccountEmailsUpdated(emails)
                ) =>
              Right(
                acc.copy(
                  emails = emails,
                  previousEmails = acc.previousEmails ++ acc.emails.toList
                )
              )
            case (acc, _) =>
              invalidAggregate(acc, ev)

        def handleCommand =
          case (None, CreateAccount(emails, pass)) =>
            Right(NonEmptyList.one(AccountCreated(emails, pass)))
          case (Some(acc), UpdateEmails(emails)) =>
            Right(NonEmptyList.one(AccountEmailsUpdated(emails)))
          case (Some(acc), UpdatePassword(pass)) =>
            Right(NonEmptyList.one(AccountPasswordUpdated(pass)))
          case (acc, cmd) => rejectUnmatched(acc, cmd)
