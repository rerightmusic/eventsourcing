# Event Sourcing

## Build

```bash
sbt "Test / compile"
```

## Test

```bash
docker run -e POSTGRES_PASSWORD=pass -e POSTGRES_PORT=5433 -e POSTGRES_USER=postgres \
-e POSTGRES_DB=postgres -p 5433:5432 -d postgres

TESTS_ENV_FILE=$PWD/tests.env sbt test
```

## Watch

```bash
sbt ~"Test / compile"
```

## Examples

[Aggregate](./src/test/scala/aggregates/test/domain/Account.scala)

```scala
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
```

[AggregateView](./src/test/scala/aggregates/test/domain/AccountAnalytics.scala)

```scala
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
```

[Operations](./src/test/scala/aggregates/test/AggregatesITest.scala#L232)

```scala
for
  _ <- ZIO.unit
  prevEmail = randomString
  id <- Aggregate[Account].create(
    AccountMeta("123"),
    PrincipalId("abc"),
    CreateAccount(prevEmail, "pass")
  )
  email = randomString
  _ <- Aggregate[Account].exec(
    id,
    AccountMeta("123"),
    PrincipalId("abc"),
    UpdateEmail(email)
  )
  pass = randomString
  _ <- Aggregate[Account].update(
    id,
    AccountMeta("123"),
    PrincipalId("abc"),
    UpdatePassword(pass)
  )
  acc <- Aggregate[Account].get(id)

  res = Account(
    id,
    email,
    pass,
    List(prevEmail)
  )
yield ()
```
