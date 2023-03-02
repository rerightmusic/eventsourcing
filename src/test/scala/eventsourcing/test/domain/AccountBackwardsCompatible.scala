package eventsourcing.test.domain

import cats.data.NonEmptyList
import eventsourcing.all.*

object AccountBackwardsCompatible:
  type AccountEvent = AccountCreated | AccountPasswordUpdated |
    AccountEmailUpdated
  case class AccountCreated(
    email: String,
    newEmailField: Option[String],
    newPasswordField: Option[String]
  )
  case class AccountPasswordUpdated(
    newPasswordField: Option[String]
  )
  case class AccountEmailUpdated(email: String, newEmailField: Option[String])

  type AccountCommand = CreateAccount | UpdateEmail | UpdatePassword
  case class CreateAccount(
    email: String,
    newEmailField: String,
    newPasswordField: String
  )
  case class UpdateEmail(email: String, emailField: String)
  case class UpdatePassword(passwordField: String)

  case class AccountMeta(ipAddress: String, newMetaField: Option[String])

  case class Account(
    id: AccountId,
    email: String,
    previousEmails: List[String],
    newEmailField: Option[String],
    newPasswordField: Option[String]
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

        val storeName = "accounts"
        val schemaVersion = 1
        def aggregate = (x, ev) =>
          (x, ev.data) match
            case (
                  None,
                  AccountCreated(
                    email,
                    newEmailField,
                    newPasswordField
                  )
                ) =>
              Right(
                Account(
                  ev.id,
                  email,
                  List(),
                  newEmailField,
                  newPasswordField
                )
              )
            case (
                  Some(acc),
                  AccountPasswordUpdated(newPassField)
                ) =>
              Right(acc.copy(newPasswordField = newPassField))
            case (
                  Some(acc),
                  AccountEmailUpdated(email, newEmailField)
                ) =>
              Right(
                acc.copy(
                  email = email,
                  previousEmails = acc.previousEmails ++ List(acc.email),
                  newEmailField = newEmailField
                )
              )
            case (acc, _) =>
              invalidAggregate(acc, ev)

        def handleCommand =
          case (
                None,
                CreateAccount(email, newEmailField, newPassField)
              ) =>
            Right(
              NonEmptyList.one(
                AccountCreated(
                  email,
                  Some(newEmailField),
                  Some(newPassField)
                )
              )
            )
          case (Some(acc), UpdateEmail(email, newEmailField)) =>
            Right(
              NonEmptyList.one(AccountEmailUpdated(email, Some(newEmailField)))
            )
          case (Some(acc), UpdatePassword(newPasswordField)) =>
            Right(
              NonEmptyList.one(
                AccountPasswordUpdated(Some(newPasswordField))
              )
            )
          case (acc, cmd) => rejectUnmatched(acc, cmd)
