package eventsourcing.test.infra

import shared.json.all.{given, *}
import eventsourcing.test.domain as D
import cats.data.NonEmptyList

sealed trait AccountEvent derives JsonCodec
object AccountEvent:
  case class AccountCreated(email: String, password: String)
      extends AccountEvent
  case class AccountPasswordUpdated(password: String) extends AccountEvent
  case class AccountEmailUpdated(email: String) extends AccountEvent

case class AccountMeta(ipAddress: String) derives JsonCodec
case class AccountData(
  id: D.AccountId,
  email: String,
  password: String,
  previousEmails: List[String]
) derives JsonCodec

object AccountBackwardsCompatible:
  sealed trait AccountEvent derives JsonCodec
  object AccountEvent:
    case class AccountCreated(
      email: String,
      newEmailField: Option[String],
      newPasswordField: Option[String]
    ) extends AccountEvent
    case class AccountPasswordUpdated(newPasswordField: Option[String])
        extends AccountEvent
    case class AccountEmailUpdated(email: String, newEmailField: Option[String])
        extends AccountEvent

  case class AccountMeta(ipAddress: String, newMetaField: Option[String])
      derives JsonCodec
  case class AccountData(
    id: D.AccountId,
    email: String,
    previousEmails: List[String],
    newPasswordField: Option[String],
    newEmailField: Option[String]
  ) derives JsonCodec

object AccountBackwardsIncompatible:
  sealed trait AccountEvent derives JsonCodec
  object AccountEvent:
    case class AccountCreated(emails: NonEmptyList[String], password: String)
        extends AccountEvent
    case class AccountPasswordUpdated(password: String) extends AccountEvent
    case class AccountEmailsUpdated(emails: NonEmptyList[String])
        extends AccountEvent
  case class AccountData(
    id: D.AccountId,
    emails: NonEmptyList[String],
    password: String,
    previousEmails: List[String]
  ) derives JsonCodec

sealed trait ProfileEvent derives JsonCodec
object ProfileEvent:
  case class ProfileCreated(
    accountId: D.AccountId,
    firstName: String,
    lastName: String
  ) extends ProfileEvent
  case class ProfileLastNameUpdated(lastName: String) extends ProfileEvent
  case class ProfileFirstNameUpdated(firstName: String) extends ProfileEvent

case class ProfileMeta(ipAddress: String) derives JsonCodec

case class ProfileData(
  profileId: D.ProfileId,
  accountId: D.AccountId,
  firstName: String,
  lastName: String
) derives JsonCodec

case class AccountDetailsData(
  email: Option[String],
  password: Option[String],
  passwordUpdatedCount: Int,
  emailUpdatedCount: Int
) derives JsonCodec

case class AccountProfileData(
  accountId: Option[D.AccountId],
  profileId: Option[D.ProfileId],
  email: Option[String],
  password: Option[String],
  firstName: Option[String],
  lastName: Option[String]
) derives JsonCodec
case class AccountAnalytics(
  totalEmails: Int,
  totalPasswords: Int,
  totalEmailUpdates: Int,
  totalPasswordUpdates: Int,
  averageEmailLength: Double,
  averagePasswordLength: Double
) derives JsonCodec
