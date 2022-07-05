package eventsourcing.test.domain

import cats.data.NonEmptyList
import java.time.OffsetDateTime
import eventsourcing.all.*
import shared.newtypes.NewtypeWrapped
import java.util.UUID

import shared.newtypes.NewExtractor

type AccountEvent = AccountCreated | AccountPasswordUpdated |
  AccountEmailUpdated
case class AccountCreated(email: String, password: String)
case class AccountPasswordUpdated(password: String)
case class AccountEmailUpdated(email: String)

type AccountCommand = CreateAccount | UpdateEmail | UpdatePassword
case class CreateAccount(email: String, password: String)
case class UpdateEmail(email: String)
case class UpdatePassword(password: String)

type AccountId = AccountId.Type
object AccountId extends NewtypeWrapped[UUID]

case class AccountMeta(ipAddress: String)

case class Account(
  id: AccountId,
  email: String,
  password: String,
  previousEmails: List[String]
)

object Account:
  given agg: Aggregate.Aux[
    Account,
    AccountId,
    AccountMeta,
    AccountEvent,
    AccountCommand
  ] =
    new Aggregate[Account]:
      type Id = AccountId
      type Meta = AccountMeta
      type EventData = AccountEvent
      type Command = CreateAccount | UpdateEmail | UpdatePassword

      def storeName = "accounts"
      def aggregate = (x, ev) =>
        (x, ev.data) match
          case (
                None,
                AccountCreated(email, password)
              ) =>
            Right(Account(ev.id, email, password, List()))
          case (
                Some(acc),
                AccountPasswordUpdated(pass)
              ) =>
            Right(acc.copy(password = pass))
          case (
                Some(acc),
                AccountEmailUpdated(email)
              ) =>
            Right(
              acc.copy(
                email = email,
                previousEmails = acc.previousEmails ++ List(acc.email)
              )
            )
          case (acc, _) =>
            invalidAggregate(acc, ev)

      def handleCommand =
        case (None, CreateAccount(email, pass)) =>
          Right(NonEmptyList.one(AccountCreated(email, pass)))
        case (Some(acc), UpdateEmail(email)) =>
          Right(NonEmptyList.one(AccountEmailUpdated(email)))
        case (Some(acc), UpdatePassword(pass)) =>
          Right(NonEmptyList.one(AccountPasswordUpdated(pass)))
        case (acc, cmd) => rejectUnmatched(acc, cmd)
