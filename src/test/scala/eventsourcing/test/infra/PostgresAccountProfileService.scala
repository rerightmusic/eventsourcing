package eventsourcing.test.infra

import shared.json.all.{given, *}
import eventsourcing.test.domain as D
import zio.ULayer
import shared.postgres.all.{*, given}
import zio.{ZLayer}
import shared.principals.PrincipalId
import cats.data.NonEmptyList
import eventsourcing.all.*
import shared.json.all.given
import shared.data.all.{*, given}
import doobie.implicits.*
import zio.durationInt

object PostgresAccountProfileService:
  def live = PostgresAggregateViewService.schemaless[
    "Accounts",
    Json,
    Json,
    AccountProfileData,
    D.AccountProfile
  ](
    x => x,
    x => x,
    x => Right(x.data.auto.to[D.AccountProfile]),
    x => Right(x.auto.to[AccountProfileData]),
    s =>
      s.readFromData(x =>
        x.value match {
          case Left(accId)  => "data->>'accountId'" -> accId.value.toString
          case Right(prfId) => "data->>'profileId'" -> prfId.value.toString
        },
      ),
    {
      case D.AccountProfileId(Left(accId))  => Some(accId) *: None *: EmptyTuple
      case D.AccountProfileId(Right(prfId)) => None *: Some(prfId) *: EmptyTuple
    },
    "test",
    1.minutes
  )
