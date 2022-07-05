package eventsourcing.test

import domain.*
import eventsourcing.all.*
import doobie.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import eventsourcing.all.*
import zio.{ZIO, ZEnv, Runtime, Has, Task}
import zio.interop.catz.*
import cats.syntax.all.*
import zio.blocking.Blocking
import shared.uuid.all.*
import cats.data.NonEmptyList
import zio.clock.Clock
import zio.duration.durationInt
import zio.Ref
import shared.principals.PrincipalId
import zio.Fiber
import zio.Schedule
import zio.duration.DurationOps
import shared.postgres.all.given

class AggregatesITest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  val RunBeforeAll = true
  val RunAfterAll = true

  override def beforeAll() = if !RunBeforeAll then ()
  else
    transact(txn => sql"""
        DROP SCHEMA IF EXISTS test cascade;
        CREATE schema test;
        CREATE TABLE test.aggregate_views (
          name            text primary key not null,
          status          jsonb not null,
          last_updated    timestamp(6) with time zone not null
        );
        CREATE TABLE test.accounts (
          sequence_id     bigserial primary key not null,
          id              uuid not null,
          meta            jsonb not null,
          created_by      varchar(50) not null,
          created         timestamp(6) with time zone not null,
          version         integer not null,
          deleted         boolean not null,
          data            jsonb not null,

          UNIQUE (id, version, deleted)
        );

        CREATE INDEX ON test.accounts(id);
        CREATE INDEX ON test.accounts(created_by);
        CREATE INDEX ON test.accounts(created);

        CREATE TABLE test.accounts_view (
          id              uuid primary key not null,
          meta            jsonb not null,
          created_by      varchar(50) not null,
          last_updated_by varchar(50) not null,
          data            jsonb not null,
          deleted         boolean not null,
          created         timestamp(6) with time zone not null,
          last_updated    timestamp(6) with time zone not null
        );

        CREATE INDEX ON test.accounts_view(created_by);
        CREATE INDEX ON test.accounts_view(created);
        CREATE INDEX ON test.accounts_view(last_updated_by);
        CREATE INDEX ON test.accounts_view(last_updated);

        CREATE TABLE test.profiles (
          sequence_id     bigserial primary key not null,
          id              uuid not null,
          meta            jsonb not null,
          created_by      varchar(50) not null,
          created         timestamp(6) with time zone not null,
          version         integer not null,
          deleted         boolean not null,
          data            jsonb not null,

          UNIQUE (id, version, deleted)
        );

        CREATE INDEX ON test.profiles(id);
        CREATE INDEX ON test.profiles(created_by);
        CREATE INDEX ON test.profiles(created);

        CREATE TABLE test.profiles_view (
          id              uuid primary key not null,
          meta            jsonb not null,
          created_by      varchar(50) not null,
          last_updated_by varchar(50) not null,
          data            jsonb not null,
          deleted         boolean not null,
          created         timestamp(6) with time zone not null,
          last_updated    timestamp(6) with time zone not null
        );

        CREATE INDEX ON test.profiles_view(created_by);
        CREATE INDEX ON test.profiles_view(created);
        CREATE INDEX ON test.profiles_view(last_updated_by);
        CREATE INDEX ON test.profiles_view(last_updated);

        CREATE TABLE test.account_details (
          id              uuid primary key not null,
          meta            jsonb not null,
          created_by      varchar(50) not null,
          last_updated_by varchar(50) not null,
          data            jsonb not null,
          deleted         boolean not null,
          created         timestamp(6) with time zone not null,
          last_updated    timestamp(6) with time zone not null
        );

        CREATE TABLE test.account_profiles (
          id              uuid primary key not null,
          meta            jsonb not null,
          created_by      varchar(50) not null,
          last_updated_by varchar(50) not null,
          data            jsonb not null,
          deleted         boolean not null,
          created         timestamp(6) with time zone not null,
          last_updated    timestamp(6) with time zone not null
        );

        CREATE INDEX ON test.account_profiles((data->>'accountId'));
        CREATE INDEX ON test.account_profiles((data->>'profileId'));

        CREATE TABLE test.account_analytics (
          name            varchar(50) primary key not null,
          data            jsonb not null
        );
      """.update.run.transact(txn))

    runSync(
      for
        _ <- createAccounts(1000)
        _ <- createProfiles(400)
      yield ()
    )

  override def afterAll() =
    if RunAfterAll then
      transact(txn =>
        sql"""DROP SCHEMA test cascade;""".update.run.transact(txn)
      )
    else ()

  "persistEventsForId" should "work" in {
    val accId = AccountId(generateUUIDSync)
    val accMeta = AccountMeta("meta")
    val prId = PrincipalId(generateUUIDSync.toString)
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
    runSync(
      Aggregate[Account]
        .store(_.persistEventsForId(accId, accMeta, prId, evs))
    )

    val resEvs = runSync(
      Aggregate[Account]
        .store(
          _.streamEventsForIdsFrom(
            Some(SequenceId(0)),
            None,
            NonEmptyList.of(accId)
          ).unchunks.compile.toList
        )
    )

    resEvs.map(_.data) shouldBe (evs.toList)
  }

  "streamEventsFrom" should "work" in {
    val allEvs = runSync(
      Aggregate[Account]
        .store(
          _.streamEventsFrom(Some(SequenceId(1)), None).unchunks
            .take(10001)
            .compile
            .toList
        )
    ).drop(1).foldLeft(0)(_ + _.sequenceId.value)

    val evsFrom = runSync(
      Aggregate[Account]
        .store(
          _.streamEventsFrom(Some(SequenceId(2)), None).unchunks
            .take(10000)
            .compile
            .toList
        )
    ).foldLeft(0)(_ + _.sequenceId.value)

    evsFrom shouldBe allEvs
  }

  "streamEventsForIdFrom" should "work" in {
    val (accId, _, _, _, evs) = runSync(createAccounts(1)).head
    val resEvs = runSync(
      Aggregate[Account]
        .store(
          _.streamEventsForIdsFrom(
            Some(SequenceId(0)),
            None,
            NonEmptyList.of(accId)
          ).unchunks.compile.toList
        )
    )

    resEvs.map(_.data) shouldBe evs.toList
  }

  "create update and exec" should "work" in {
    runSync(
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
        _ <- zassert(acc shouldBe res)
      yield ()
    )
  }

  "Long aggregate" should "work" in {
    runSync(
      for
        _ <- ZIO.unit
        firstEmail = randomString
        id <- Aggregate[Account].create(
          AccountMeta(randomString),
          PrincipalId(randomString),
          CreateAccount(firstEmail, randomString)
        )
        updates <- (1 to 100).toList.traverse(_ =>
          for
            _ <- ZIO.unit
            newEmail = randomString
            _ <- Aggregate[Account].update(
              id,
              AccountMeta(randomString),
              PrincipalId(randomString),
              UpdateEmail(newEmail)
            )
            newPass = randomString
            _ <- Aggregate[Account].update(
              id,
              AccountMeta(randomString),
              PrincipalId(randomString),
              UpdatePassword(newPass)
            )
          yield (newEmail, newPass)
        )
        acc <- Aggregate[Account].get(id)
        res = Account(
          id,
          updates.map(_._1).last,
          updates.map(_._2).last,
          firstEmail :: updates.map(_._1).slice(0, updates.length - 1)
        )
        _ <- zassert(acc shouldBe res)
      yield ()
    )
  }

  "Migrate aggregate" should "work" in {
    runSync(
      for
        (id, lastEmail, lastPass, prevEmails, _) <- createAccounts(1).map(
          _.head
        )

        acc <- Aggregate[AccountV2.Account].get(id)
        _ <- zassert(
          acc shouldBe AccountV2.Account(id, lastEmail, prevEmails, None, None)
        )

        email = randomString
        _ <- Aggregate[AccountV2.Account].update(
          id,
          AccountV2.AccountMeta(randomString, Some(randomString)),
          PrincipalId(randomString),
          AccountV2.UpdateEmail(email, randomString)
        )

        email2 = randomString
        newEmailField2 = randomString
        _ <- Aggregate[AccountV2.Account].update(
          id,
          AccountV2.AccountMeta(randomString, Some(randomString)),
          PrincipalId(randomString),
          AccountV2.UpdateEmail(email2, newEmailField2)
        )

        _ <- Aggregate[AccountV2.Account].update(
          id,
          AccountV2.AccountMeta(randomString, Some(randomString)),
          PrincipalId(randomString),
          AccountV2.UpdatePassword(randomString)
        )
        newPassField = randomString
        _ <- Aggregate[AccountV2.Account].update(
          id,
          AccountV2.AccountMeta(randomString, Some(randomString)),
          PrincipalId(randomString),
          AccountV2.UpdatePassword(newPassField)
        )

        acc2 <- Aggregate[AccountV2.Account].get(id)
        res2 = AccountV2.Account(
          id,
          email2,
          prevEmails ++ List(lastEmail, email),
          Some(newEmailField2),
          Some(newPassField)
        )
        _ <- zassert(acc2 shouldBe res2)
        _ <- transactM(t =>
          sql"""DELETE from test.accounts where id = ${id}""".update.run
            .transact(t)
        )
      yield ()
    )
  }

  "getAggregate" should "work" in {
    runSync(
      for
        _ <- ZIO.unit
        email = randomString
        pass = randomString
        id <- Aggregate[Account].create(
          AccountMeta("123"),
          PrincipalId("abc"),
          CreateAccount(email, pass)
        )
        acc1 <- Aggregate[Account].get(id)
        res1 = Account(
          id,
          email,
          pass,
          List()
        )
        _ <- zassert(acc1 shouldBe res1)
        email2 = randomString
        _ <- Aggregate[Account].update(
          id,
          AccountMeta("123"),
          PrincipalId("abc"),
          UpdateEmail(email2)
        )
        pass2 = randomString
        _ <- Aggregate[Account].update(
          id,
          AccountMeta("123"),
          PrincipalId("abc"),
          UpdatePassword(pass2)
        )
        acc2 <- Aggregate[Account].get(id)
        res2 = Account(
          id,
          email2,
          pass2,
          List(email)
        )
        _ <- zassert(acc2 shouldBe res2)
      yield ()
    )
  }

  "Catchup" should "work" in {
    runSync(for
      _ <- AggregateView[Account].run(AggregateViewMode.Restart, false)
      viewsSize <- AggregateView[Account].store(_.countAggregateView)
      aggsSize <- transactM(t =>
        sql"""select count(distinct id) from test.accounts"""
          .query[Int]
          .unique
          .transact(t)
      )
      _ <- zassert(viewsSize shouldBe aggsSize)
    yield ())
  }

  "Catchups in parallel" should "work" in {
    runSync(
      for
        _ <- AggregateView[Account].store(_.resetAggregateView)
        f1 <- fork(
          AggregateView[Account]
            .run(AggregateViewMode.Continue, false)
        )
        f2 <- fork(
          AggregateView[Account]
            .run(AggregateViewMode.Continue, false)
        )
        f3 <- fork(
          AggregateView[Account]
            .run(AggregateViewMode.Continue, false)
        )
        f4 <- fork(
          AggregateView[Account]
            .run(AggregateViewMode.Continue, false)
        )
        _ <- Fiber.joinAll(List(f1, f2, f3, f4))
        viewsSize <- AggregateView[Account].store(_.countAggregateView)
        aggsSize <- transactM(t =>
          sql"""select count(distinct id) from test.accounts"""
            .query[Int]
            .unique
            .transact(t)
        )
        _ <- zassert(viewsSize shouldBe aggsSize)
      yield ()
    )
  }

  "Process missed events after catchup" should "work" in {
    runSync(for
      _ <- fork(
        AggregateView[Account]
          .run(AggregateViewMode.Restart, true)
      )
      _ <- createAccountsAndProfiles(3)
      viewsSize <- AggregateView[Account].store(
        _.countAggregateViewWithCaughtUp
      )
      aggsSize <- transactM(t =>
        sql"""select count(distinct id) from test.accounts"""
          .query[Int]
          .unique
          .transact(t)
      )
    yield viewsSize shouldBe aggsSize)
  }

  "Process missed and new events after catchup" should "work" in {
    runSync(for
      _ <- fork(
        AggregateView[Account]
          .run(AggregateViewMode.Restart, true)
      )
      _ <- createAccountsAndProfiles(50)
      viewsSize <- AggregateView[Account].store(
        _.countAggregateViewWithCaughtUp
      )
      aggsSize <- transactM(t =>
        sql"""select count(distinct id) from test.accounts"""
          .query[Int]
          .unique
          .transact(t)
      )

      _ <- zassert(viewsSize shouldBe aggsSize)
    yield ())
  }

  "Process missed and new events after catchup in parallel" should "work" in {
    runSync(for
      _ <- AggregateView[Account].store(_.resetAggregateView)
      _ <- fork(
        AggregateView[Account]
          .run(AggregateViewMode.Continue, true)
      )
      _ <- fork(
        AggregateView[Account]
          .run(AggregateViewMode.Continue, true)
      )
      _ <- fork(
        AggregateView[Account]
          .run(AggregateViewMode.Continue, true)
      )
      _ <- fork(
        AggregateView[Account]
          .run(AggregateViewMode.Continue, true)
      )
      _ <- createAccountsAndProfiles(50)
      viewsSize <- AggregateView[Account].store(
        _.countAggregateViewWithCaughtUp
      )
      aggsSize <- transactM(t =>
        sql"""select count(distinct id) from test.accounts"""
          .query[Int]
          .unique
          .transact(t)
      )
      _ <- zassert(viewsSize shouldBe aggsSize)
    yield ())
  }

  "Empty event store catchup in parallel" should "work" in {
    runSync(for
      _ <- transactM(t =>
        sql"""ALTER TABLE test.accounts RENAME TO accounts2""".update.run
          .transact(t)
      )
      _ <- transactM(t => sql"""
        CREATE TABLE test.accounts (
          sequence_id     bigserial primary key not null,
          id              uuid not null,
          meta            jsonb not null,
          created_by      varchar(50) not null,
          created         timestamp(6) with time zone not null,
          version         integer not null,
          deleted         boolean not null,
          data            jsonb not null,

          UNIQUE (id, version, deleted)
        );
        """.update.run.transact(t))
      _ <- AggregateView[Account].store(_.resetAggregateView)
      _ <- fork(AggregateView[Account].run(AggregateViewMode.Continue, true))
      _ <- fork(AggregateView[Account].run(AggregateViewMode.Continue, true))
      _ <- fork(AggregateView[Account].run(AggregateViewMode.Continue, true))
      _ <- fork(AggregateView[Account].run(AggregateViewMode.Continue, true))
      _ <- zio.clock.sleep(3.seconds)
      _ <- createAccounts(10)
      viewsSize <- AggregateView[Account].store(
        _.countAggregateViewWithCaughtUp
      )
      aggsSize <- transactM(t =>
        sql"""select count(distinct id) from test.accounts"""
          .query[Int]
          .unique
          .transact(t)
      )
      _ <- zassert(viewsSize shouldBe aggsSize)
      _ <- transactM(t =>
        sql"""DROP TABLE test.accounts""".update.run
          .transact(t)
      )
      _ <- transactM(t =>
        sql"""ALTER TABLE test.accounts2 RENAME TO accounts""".update.run
          .transact(t)
      )
    yield ())
  }

  "Recover from connection failure" should "work" in {
    runSync(for
      _ <- fork(AggregateView[Account].run(AggregateViewMode.Restart, true))
      _ <- transactM(t =>
        sql"""ALTER TABLE test.aggregate_views RENAME TO aggregate_views2""".update.run
          .transact(t)
      )
      _ <- zio.clock.sleep(2.seconds)
      _ <- transactM(t =>
        sql"""ALTER TABLE test.aggregate_views2 RENAME TO aggregate_views""".update.run
          .transact(t)
      )
      _ <- createAccountsAndProfiles(3)
      _ <- zio.clock.sleep(2.seconds)
      _ <- transactM(t =>
        sql"""ALTER TABLE test.aggregate_views RENAME TO aggregate_views2""".update.run
          .transact(t)
      )
      _ <- zio.clock.sleep(2.seconds)
      _ <- transactM(t =>
        sql"""ALTER TABLE test.aggregate_views2 RENAME TO aggregate_views""".update.run
          .transact(t)
      )
      viewsSize <- AggregateView[Account].store(
        _.countAggregateViewWithCaughtUp
      )
      aggsSize <- transactM(t =>
        sql"""select count(distinct id) from test.accounts"""
          .query[Int]
          .unique
          .transact(t)
      )
      _ <- zassert(viewsSize shouldBe aggsSize)
    yield ())
  }

  "Repeatedly process and read" should "work" in {
    def loop =
      ZIO
        .loop((List[(Option[Int], Option[Int])](), 0))(
          _._2 < 10,
          x => (x._1, x._2 + 1)
        )((prev, idx) =>
          for
            _ <- createAccountsAndProfiles(10)
            viewsSize <- AggregateView[Account]
              .store(
                _.countAggregateViewWithCaughtUp
              )
            aggsSize <- transactM(t =>
              sql"""select count(distinct id) from test.accounts"""
                .query[Int]
                .unique
                .transact(t)
            )
          yield ((viewsSize -> aggsSize) :: prev) -> idx
        )
        .map(_.flatMap(_._1))

    runSync(for
      _ <- AggregateView[Account]
        .run(AggregateViewMode.Restart, false)
      _ <- fork(
        AggregateView[Account]
          .run(AggregateViewMode.Continue, true)
      )
      res <- loop
      _ <- zassert(res.map(_._1) shouldBe res.map(_._2))
    yield ())
  }

  "getAggregateView" should "work" in {
    runSync(
      for
        id <- Aggregate[Account].create(
          AccountMeta("123"),
          PrincipalId("abc"),
          CreateAccount(randomString, randomString)
        )
        email = randomString
        _ <- Aggregate[Account].update(
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
        res1 = AccountDetails(Some(email), Some(pass), 1, 1)
        acc1 <- AggregateView[AccountDetails].get(
          Some(NonEmptyList.of(id))
        )
        _ <- zassert(acc1.size shouldBe 1)
        _ <- zassert(
          acc1.flatMap(_.headOption.map(_._2.data)) shouldBe Some(res1)
        )
        email2 = randomString
        _ <- Aggregate[Account].update(
          id,
          AccountMeta("123"),
          PrincipalId("abc"),
          UpdateEmail(email2)
        )
        pass2 = randomString
        _ <- Aggregate[Account].update(
          id,
          AccountMeta("123"),
          PrincipalId("abc"),
          UpdatePassword(pass2)
        )
        res2 = AccountDetails(Some(email2), Some(pass2), 2, 2)
        acc2 <- AggregateView[AccountDetails].get(
          Some(NonEmptyList.of(id))
        )
        _ <- zassert(acc2.size shouldBe 1)
        _ <- zassert(
          acc2.flatMap(_.headOption.map(_._2.data)) shouldBe Some(res2)
        )
      yield ()
    )
  }

  "test aggregate aggregate view with subscription" should "work" in {
    runSync(for
      _ <- ZIO.unit
      res1 <- createAccounts(1).map(_.head)
      _ <- fork(
        AggregateView[Account]
          .run(AggregateViewMode.Restart, true)
          .catchAll(logAndThrow)
      )
      _ <- zio.clock.sleep(1.second)
      _ <- createAccountsAndProfiles(5)
      res2 <- createAccounts(1).map(_.head)
      q1 <-
        AggregateView[Account]
          .store(
            _.readAggregateViewWithCaughtUp(Some(NonEmptyList.of(res1._1)))
              .map(_.flatMap(_.headOption.map(_._2)))
          )

      q2 <- AggregateView[Account]
        .store(
          _.readAggregateViewWithCaughtUp(Some(NonEmptyList.of(res2._1)))
            .map(_.flatMap(_.headOption.map(_._2)))
        )

      _ <- zassert(
        q1.map(_.data) shouldBe Some(
          Account(
            res1._1,
            res1._2,
            res1._3,
            res1._4
          )
        )
      )

      _ <- zassert(
        q2.map(_.data) shouldBe Some(
          Account(
            res2._1,
            res2._2,
            res2._3,
            res2._4
          )
        )
      )

      lastSeqId <- Aggregate[Account].store(_.getLastSequenceId)
      viewLastSeqId <- AggregateView[Account]
        .store(_.readAggregateViewStatus)
        .map(
          _.get.sequenceIds.head._2
        )
      _ <- zassert(viewLastSeqId shouldBe lastSeqId)
    yield ())
  }

  "test aggregate view with subscription" should "work" in {
    runSync(for
      _ <- ZIO.unit
      acc1 <- createAccounts(1).map(_.head)
      _ <- fork(
        AggregateView[AccountDetails]
          .run(AggregateViewMode.Restart, true)
          .catchAll(logAndThrow)
      )
      _ <- fork(
        AggregateView[AccountAnalytics]
          .run(AggregateViewMode.Restart, true)
          .catchAll(logAndThrow)
      )
      _ <- zio.clock.sleep(1.second)
      _ <- createAccountsAndProfiles(20)
      acc2 <- createAccounts(1).map(_.head)
      q1 <- AggregateView[AccountDetails].store(
        _.readAggregateViewWithCaughtUp(
          Some(NonEmptyList.of(acc1._1))
        )
      )

      _ <- zassert(q1.map(_.size) shouldBe Some(1))
      _ <- zassert(
        q1.flatMap(_.headOption.map(_._2)).map(_.data) shouldBe Some(
          AccountDetails(
            Some(acc1._2),
            Some(acc1._3),
            5,
            5
          )
        )
      )

      q2 <- AggregateView[AccountDetails].store(
        _.readAggregateViewWithCaughtUp(
          Some(NonEmptyList.of(acc2._1))
        )
      )
      _ <- zassert(q2.map(_.size) shouldBe Some(1))
      _ <- zassert(
        q2.flatMap(_.headOption.map(_._2)).map(_.data) shouldBe Some(
          AccountDetails(
            Some(acc2._2),
            Some(acc2._3),
            5,
            5
          )
        )
      )

      q3 <- AggregateView[AccountAnalytics].store(
        _.readAggregateViewWithCaughtUp(
          Some(NonEmptyList.of(acc2._1))
        )
      )

      evs <- Aggregate[Account]
        .store(
          _.streamEventsFrom(Some(SequenceId(0)), None).unchunks.compile.toList
        )
      anals = evs.foldLeft(AccountAnalytics(0, 0, 0, 0, 0, 0))((prev, ev) =>
        ev.data match
          case AccountCreated(email, pass) =>
            prev.copy(
              totalEmails = prev.totalEmails + 1,
              totalPasswords = prev.totalPasswords + 1,
              averageEmailLength =
                (prev.averageEmailLength * (prev.totalEmails - 1) + email.length) / (prev.totalEmails + 1),
              averagePasswordLength =
                (prev.averagePasswordLength * (prev.totalPasswords - 1) + email.length) / (prev.totalPasswords + 1),
            )
          case AccountPasswordUpdated(pass) =>
            prev.copy(
              totalPasswords = prev.totalPasswords + 1,
              totalPasswordUpdates = prev.totalPasswordUpdates + 1,
              averagePasswordLength =
                (prev.averagePasswordLength * (prev.totalPasswords - 1) + pass.length) / (prev.totalPasswords + 1),
            )
          case AccountEmailUpdated(email) =>
            prev.copy(
              totalEmails = prev.totalEmails + 1,
              totalEmailUpdates = prev.totalEmailUpdates + 1,
              averageEmailLength =
                (prev.averageEmailLength * (prev.totalEmails - 1) + email.length) / (prev.totalEmails + 1),
            )
      )
      _ <- zassert(q3 shouldBe Some(anals))

      lastSeqId <- Aggregate[Account].store(_.getLastSequenceId)

      (lastSeq1, lastSeq2) <-
        for
          status1 <- AggregateView[AccountDetails].store(
            _.readAggregateViewStatus
          )
          status2 <- AggregateView[AccountAnalytics].store(
            _.readAggregateViewStatus
          )
        yield (
          status1.map(_.sequenceIds.head._2),
          status2.map(_.sequenceIds.head._2)
        )

      _ <- zassert(
        (lastSeq1, lastSeq2) shouldBe (Some(lastSeqId), Some(lastSeqId))
      )
    yield ())
  }

  "test multiple aggregates view with subscription" should "work" in {
    runSync(for
      _ <- ZIO.unit
      acc1 <- createAccounts(1).map(_.head)
      prf1 <- createProfiles(1).map(_.head)
      _ <- fork(
        AggregateView[AccountProfile]
          .run(AggregateViewMode.Restart, true)
          .catchAll(logAndThrow)
      )
      _ <- zio.clock.sleep(1.second)
      _ <- createAccountsAndProfiles(20)
      acc2 <- createAccounts(1).map(_.head)
      prf2 <- createProfiles(1).map(_.head)
      q1 <- AggregateView[AccountProfile].store(
        _.countAggregateViewWithCaughtUp
      )
      aggsSize <- transactM(t =>
        sql"""select count(distinct id) from test.accounts"""
          .query[Int]
          .unique
          .transact(t)
      )

      _ <- zassert(q1 shouldBe aggsSize)

      q2 <- AggregateView[AccountProfile]
        .store(
          _.readAggregateViewWithCaughtUp(
            Some(
              NonEmptyList.of(
                AccountProfileId(Left(prf1._2._1)),
                AccountProfileId(Left(prf2._2._1))
              )
            )
          )
        )

      _ <- zassert(q2.map(_.size) shouldBe Some(2))
      _ <- zassert(
        q2.flatMap(
          _.find(x => x._2.data.accountId === Some(prf1._2._1)).map(_._2.data)
        ) shouldBe Some(
          AccountProfile(
            Some(prf1._2._1),
            Some(prf1._1),
            Some(prf1._2._2),
            Some(prf1._2._3),
            Some(prf1._3),
            Some(prf1._4)
          )
        )
      )
      _ <- zassert(
        q2.flatMap(
          _.find(x => x._2.data.accountId === Some(prf2._2._1)).map(_._2.data)
        ) shouldBe Some(
          AccountProfile(
            Some(prf2._2._1),
            Some(prf2._1),
            Some(prf2._2._2),
            Some(prf2._2._3),
            Some(prf2._3),
            Some(prf2._4)
          )
        )
      )

      accLastSeqId <- Aggregate[Account].store(_.getLastSequenceId)
      prfLastSeqId <- Aggregate[Profile].store(_.getLastSequenceId)

      lastSeqIds <- AggregateView[AccountProfile]
        .store(_.readAggregateViewStatus)
        .map(
          _.map(_.sequenceIds)
        )

      _ <- zassert(
        lastSeqIds.flatMap(_.get("accounts")) shouldBe Some(accLastSeqId)
      )
      _ <- zassert(
        lastSeqIds.flatMap(_.get("profiles")) shouldBe Some(prfLastSeqId)
      )
    yield ())
  }
