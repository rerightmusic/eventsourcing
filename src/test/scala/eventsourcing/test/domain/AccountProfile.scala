package eventsourcing.test.domain

import eventsourcing.all.*
import shared.json.all.*
import shared.uuid.all.*

case class AccountProfile(
  accountId: Option[AccountId],
  profileId: Option[ProfileId],
  email: Option[String],
  password: Option[String],
  firstName: Option[String],
  lastName: Option[String]
)

case class AccountProfileId(value: Either[AccountId, ProfileId])

object AccountProfile:
  given view: AggregateView.Schemaless[
    AccountProfile,
    UUID,
    Json,
    AccountProfileId,
    Account *: Profile *: EmptyTuple
  ] =
    AggregateView.schemaless[
      AccountProfile,
      UUID,
      Json,
      AccountProfileId,
      Account *: Profile *: EmptyTuple
    ](
      "account_profiles",
      1,
      evs =>
        evs.map(
          _.on[Account](ev => AccountProfileId(Left(ev.id)))
            .on[Profile](ev =>
              ev.data match
                case ProfileCreated(accId, _, _) =>
                  AccountProfileId(Left(accId))
                case _ => AccountProfileId(Right(ev.id))
            )
        ),
      (state, event) =>
        val state_ = state.getOrElse(Map.empty)
        val profilesState =
          state_.toList
            .flatMap(v => v._2.data.profileId.map(prId => prId -> v._2))
            .toMap
        val accountsState =
          state_.toList
            .flatMap(v => v._2.data.accountId.map(accId => accId -> v._2))
            .toMap
        event
          .on[Account](ev =>
            val viewState = accountsState
              .get(ev.id)
              .getOrElse(
                Schemaless(
                  id = generateUUIDSync,
                  meta = emptyObject,
                  createdBy = ev.createdBy,
                  lastUpdatedBy = ev.createdBy,
                  deleted = false,
                  created = ev.created,
                  lastUpdated = ev.created,
                  data =
                    AccountProfile(Some(ev.id), None, None, None, None, None),
                )
              )
            val newState = ev.data match
              case AccountCreated(email, pass) =>
                state_.updated(
                  viewState.id,
                  viewState
                    .copy(data =
                      viewState.data
                        .copy(email = Some(email), password = Some(pass))
                    )
                )
              case AccountPasswordUpdated(pass) =>
                state_.updated(
                  viewState.id,
                  viewState.copy(data =
                    viewState.data.copy(
                      password = Some(pass)
                    )
                  )
                )
              case AccountEmailUpdated(email) =>
                state_.updated(
                  viewState.id,
                  viewState.copy(data =
                    viewState.data
                      .copy(
                        email = Some(email)
                      )
                  )
                )
            Right(newState)
          )
          .on[Profile](ev =>
            val viewState = profilesState
              .get(ev.id)
              .getOrElse(
                Schemaless(
                  id = generateUUIDSync,
                  meta = emptyObject,
                  createdBy = ev.createdBy,
                  lastUpdatedBy = ev.createdBy,
                  deleted = false,
                  created = ev.created,
                  lastUpdated = ev.created,
                  data =
                    AccountProfile(None, Some(ev.id), None, None, None, None),
                )
              )
            val newState = ev.data match
              case ProfileCreated(accId, firstName, lastName) =>
                val viewState_ = accountsState
                  .get(accId)
                  .getOrElse(viewState)
                state_.updated(
                  viewState_.id,
                  viewState_.copy(data =
                    viewState_.data
                      .copy(
                        accountId = Some(accId),
                        profileId = Some(ev.id),
                        firstName = Some(firstName),
                        lastName = Some(lastName)
                      )
                  )
                )
              case ProfileFirstNameUpdated(firstName) =>
                state_.updated(
                  viewState.id,
                  viewState.copy(data =
                    viewState.data
                      .copy(
                        firstName = Some(firstName)
                      )
                  )
                )

              case ProfileLastNameUpdated(lastName) =>
                state_.updated(
                  viewState.id,
                  viewState.copy(data =
                    viewState.data
                      .copy(
                        lastName = Some(lastName)
                      )
                  )
                )
            Right(newState)
          )
    )
