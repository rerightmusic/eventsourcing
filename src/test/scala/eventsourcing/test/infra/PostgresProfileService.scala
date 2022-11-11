package eventsourcing.test.infra

import eventsourcing.test.domain as D
import zio.ULayer
import shared.postgres.all.{*, given}
import zio.{ZLayer}
import eventsourcing.all.*
import shared.principals.PrincipalId
import cats.data.NonEmptyList
import shared.data.all.{*, given}
import zio.{EnvironmentTag => Tag}
import shared.json.all.given
import zio.durationInt

object PostgresProfileService:
  def live =
    PostgresAggregateService
      .live[
        "Accounts",
        ProfileData,
        D.ProfileId,
        ProfileMeta,
        ProfileEvent,
        D.Profile,
        D.ProfileMeta,
        D.ProfileEvent,
      ](
        x => Right(x.data.auto.to[D.Profile]),
        _.auto.to[D.ProfileMeta],
        (_, ev) =>
          Right(ev match
            case a: ProfileEvent.ProfileCreated =>
              a.auto.to[D.ProfileCreated]
            case a: ProfileEvent.ProfileLastNameUpdated =>
              a.auto.to[D.ProfileLastNameUpdated]
            case a: ProfileEvent.ProfileFirstNameUpdated =>
              a.auto.to[D.ProfileFirstNameUpdated]
          ),
        x => Right(x.auto.to[ProfileData]),
        _.auto.to[ProfileMeta],
        x =>
          Right(x match
            case a: D.ProfileCreated =>
              a.auto.to[ProfileEvent.ProfileCreated]
            case a: D.ProfileLastNameUpdated =>
              a.auto.to[ProfileEvent.ProfileLastNameUpdated]
            case a: D.ProfileFirstNameUpdated =>
              a.auto.to[ProfileEvent.ProfileFirstNameUpdated]
          ),
        "test",
        1.minutes
      )
