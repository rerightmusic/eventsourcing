package eventsourcing.test

import domain.*
import zio.ZIO
import eventsourcing.all.*
import shared.principals.PrincipalId
import cats.data.NonEmptyList
import java.util.UUID
import cats.syntax.all.*
import zio.interop.catz.*
import scala.util.Random

def randomString =
  Random.alphanumeric.filter(_.isLetter).take(Random.nextInt(5) + 7).mkString

def processBatch[A](
  limit: Int,
  f: (size: Int) => ZIO[AppEnv, Throwable, A]
) =
  fs2.Stream
    .range(0, limit)
    .chunkN(5000)
    .evalMap(c => f(c.size))

def createAccounts(limit: Int) =
  processBatch(limit, size => createAccounts_(size)).compile.toList
    .map(_.flatten)

def createAccounts_ = (limit: Int) =>
  val resAndEvs = (1 to limit).toList.map(_ =>
    val accId = AccountId(UUID.randomUUID)
    val accMeta = AccountMeta(randomString)
    val prId = PrincipalId(UUID.randomUUID.toString)
    val evs =
      NonEmptyList
        .fromListUnsafe(
          AccountCreated(
            randomString,
            randomString
          ) ::
            (1 to 5).toList.map[AccountEvent](_ =>
              AccountEmailUpdated(
                randomString
              )
            ) ++
            (1 to 5).toList.map[AccountEvent](_ =>
              AccountPasswordUpdated(
                randomString
              )
            )
        )

    val emails = evs.toList
      .collect(x =>
        x match
          case AccountCreated(email, _) => email
          case e: AccountEmailUpdated   => e.email
      )

    val lastEmail = emails.last

    val lastPass = evs.toList.reverse
      .collectFirst(x =>
        x match
          case e: AccountPasswordUpdated => e.password
      )
      .get

    (accId, lastEmail, lastPass, emails.slice(0, emails.length - 1), evs) -> evs
      .map(e => (accId, accMeta, prId, e))
  )

  Aggregate[Account]
    .store(_.persistEvents(resAndEvs.flatMap(_._2.toList).toNel.get))
    .map(_ => resAndEvs.map(_._1))

def createProfiles(
  limit: Int
) = processBatch(
  limit,
  size =>
    for
      accs <- createAccounts_(size)
      resAndEvs = (1 to limit).toList
        .zip(accs)
        .map((_, acc) =>
          val prfId = ProfileId(UUID.randomUUID)
          val prfMeta = ProfileMeta(randomString)
          val prId = PrincipalId(UUID.randomUUID.toString)
          val evs =
            NonEmptyList
              .fromListUnsafe(
                ProfileCreated(
                  acc._1,
                  randomString,
                  randomString
                ) ::
                  (1 to 5).toList.map[ProfileEvent](_ =>
                    ProfileFirstNameUpdated(
                      randomString
                    )
                  ) ++
                  (1 to 5).toList.map[ProfileEvent](_ =>
                    ProfileLastNameUpdated(
                      randomString
                    )
                  )
              )
          val lastFirstName = evs.toList.reverse
            .collectFirst(x =>
              x match
                case e: ProfileFirstNameUpdated => e.firstName
            )
            .get

          val lastLastName = evs.toList.reverse
            .collectFirst(x =>
              x match
                case e: ProfileLastNameUpdated => e.lastName
            )
            .get
          (prfId, acc, lastFirstName, lastLastName, evs) -> evs.map(e =>
            (prfId, prfMeta, prId, e)
          )
        )
      res <- Aggregate[Profile].store(
        _.persistEvents(resAndEvs.flatMap(_._2.toList).toNel.get).map(_ =>
          resAndEvs.map(_._1)
        )
      )
    yield res
).compile.toList.map(_.flatten)

def createAccountsAndProfiles = (limit: Int) =>
  for
    _ <- createAccounts(limit)
    _ <- createProfiles(limit)
  yield ()
