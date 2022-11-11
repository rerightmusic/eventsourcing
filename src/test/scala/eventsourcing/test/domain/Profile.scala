package eventsourcing.test.domain

import cats.data.NonEmptyList
import java.time.OffsetDateTime
import eventsourcing.all.*
import shared.newtypes.NewtypeWrapped
import java.util.UUID

import shared.newtypes.NewExtractor

type ProfileEvent = ProfileCreated | ProfileLastNameUpdated |
  ProfileFirstNameUpdated
case class ProfileCreated(
  accountId: AccountId,
  firstName: String,
  lastName: String
)
case class ProfileFirstNameUpdated(firstName: String)
case class ProfileLastNameUpdated(lastName: String)

type ProfileCommand = CreateProfile | UpdateFirstName | UpdateLastName
case class CreateProfile(
  accountId: AccountId,
  firstName: String,
  lastName: String
)
case class UpdateFirstName(firstName: String)
case class UpdateLastName(lastName: String)

type ProfileId = ProfileId.Type
object ProfileId extends NewtypeWrapped[UUID]

case class ProfileMeta(ipAddress: String)

case class Profile(
  profileId: ProfileId,
  accountId: AccountId,
  firstName: String,
  lastName: String
)

object Profile:
  inline given agg: Aggregate.Aux[
    Profile,
    ProfileId,
    ProfileMeta,
    ProfileEvent,
    ProfileCommand
  ] =
    new Aggregate[Profile]:
      type Id = ProfileId
      type Meta = ProfileMeta
      type EventData = ProfileEvent
      type Command = CreateProfile | UpdateFirstName | UpdateLastName

      val storeName = "profiles"
      val schemaVersion = 1

      def aggregate = (x, ev) =>
        (x, ev.data) match
          case (
                None,
                ProfileCreated(accId, firstName, lastName)
              ) =>
            Right(Profile(ev.id, accId, firstName, lastName))
          case (
                Some(acc),
                ProfileLastNameUpdated(lastName)
              ) =>
            Right(acc.copy(lastName = lastName))
          case (
                Some(acc),
                ProfileFirstNameUpdated(firstName)
              ) =>
            Right(
              acc.copy(
                firstName = firstName
              )
            )
          case (acc, _) =>
            invalidAggregate(acc, ev)

      def handleCommand =
        case (None, CreateProfile(accId, firstName, lastName)) =>
          Right(NonEmptyList.one(ProfileCreated(accId, firstName, lastName)))
        case (Some(acc), UpdateFirstName(firstName)) =>
          Right(NonEmptyList.one(ProfileFirstNameUpdated(firstName)))
        case (Some(acc), UpdateLastName(lastName)) =>
          Right(NonEmptyList.one(ProfileLastNameUpdated(lastName)))
        case (acc, cmd) => rejectUnmatched(acc, cmd)
