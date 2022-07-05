package shared.postgres.doobie

import _root_.doobie.*
import _root_.doobie.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Properties
import shared.data.all.*
import shared.config.{all as Conf}
import shared.postgres.all.{given, *}
import shared.json.all.{given, *}
import java.io.File
import zio.{ZIO, ZEnv, Runtime, Has, Task}
import zio.interop.catz.*
import zio.blocking.Blocking
import shared.logging.all.*
import org.scalatest.BeforeAndAfterAll
import java.util.UUID
import java.time.OffsetDateTime
import cats.data.NonEmptyList
import shared.principals.*
import scala.util.Random
import shared.json.all.*

class PostgresDoobieITest
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll:

  var pgConfig: PostgresConfig["Test"] = null
  var id1: Id | Null = null
  val data1 = generateData()
  var id2: Id | Null = null
  val data2 = generateData()
  var id3: Id | Null = null
  val data3 = generateData()

  override def beforeAll() =
    transact(txn => sql"""
      DROP SCHEMA IF EXISTS test cascade;
      CREATE schema test;
      CREATE TABLE test.example (
        id              uuid,
        meta            jsonb not null,
        created_by      varchar(50),
        last_updated_by varchar(50),
        data            jsonb,
        deleted         boolean,
        created         timestamp(6) with time zone,
        last_updated    timestamp(6) with time zone
      )
    """.update.run.transact(txn))

    id1 = insertData(
      transact,
      NonEmptyList.of(
        data1
      )
    )
    id2 = insertData(
      transact,
      NonEmptyList.of(
        data2
      )
    )
    id3 = insertData(
      transact,
      NonEmptyList.of(
        data3
      )
    )

  override def afterAll() =
    transact(txn => sql"""DROP SCHEMA test cascade;""".update.run.transact(txn))

  "insertInto" should "work" in {
    val id = UUID.randomUUID
    val uuid = UUID.randomUUID
    val uuids = List(UUID.randomUUID, UUID.randomUUID)
    val doc = PostgresDocument(
      Id(id),
      (),
      PrincipalId("principal"),
      PrincipalId("principal"),
      ExampleDocumentData(
        List("a", "b", "c"),
        "name",
        uuid,
        NonEmptyList.of("a", "b"),
        uuids,
        List(ExampleObject("a", "b", ExampleEnum.EnumA)),
        ExampleEnum.EnumA,
        ExampleEnumTrait.EnumA,
        Some(uuid)
      ),
      false,
      OffsetDateTime.now,
      OffsetDateTime.now
    )
    transact(txn =>
      insertInto(
        "test.example",
        NonEmptyList.of(doc)
      ).transact[Task](txn).unit
    )
  }

  "updateData" should "work" in {
    val data = ExampleDocumentData(
      List("a", "b", "c"),
      "name",
      UUID.randomUUID,
      NonEmptyList.of("a", "b"),
      List(UUID.randomUUID),
      List(ExampleObject("a", "b", ExampleEnum.EnumA)),
      ExampleEnum.EnumA,
      ExampleEnumTrait.EnumA,
      Some(UUID.randomUUID)
    )
    val id = insertData(
      transact,
      NonEmptyList.of(data)
    )

    transact(txn =>
      updateData("test.example", id, PrincipalId("principal_b"), data).transact(
        txn
      )
    )
    val res = transact(txn =>
      selectById[Id, Unit, ExampleDocumentData]("test.example", id).transact(
        txn
      )
    )
    res.lastUpdatedBy.value shouldBe "principal_b"
    res.data shouldBe data
  }

  "selectById" should "work" in {
    val data = ExampleDocumentData(
      List("a", "b", "c"),
      "name",
      UUID.randomUUID,
      NonEmptyList.of("a", "b"),
      List(UUID.randomUUID),
      List(ExampleObject("a", "b", ExampleEnum.EnumA)),
      ExampleEnum.EnumA,
      ExampleEnumTrait.EnumA,
      Some(UUID.randomUUID)
    )
    val id = insertData(
      transact,
      NonEmptyList.of(data)
    )

    val res = transact(txn =>
      selectById[Id, Unit, ExampleDocumentData]("test.example", id).transact(
        txn
      )
    )

    res.data shouldBe data
  }

  "data format" should "be correct" in {
    val data = generateData()
    val id = insertData(
      transact,
      NonEmptyList.of(data)
    )

    val res =
      transact(txn =>
        selectById[Id, Unit, Json]("test.example", id).transact(txn)
      )

    res.data shouldBe (Map(
      "id" -> data.id.toString.toJsonASTOrFail,
      "ids" -> data.ids.map(_.toString).toJsonASTOrFail,
      "enum" -> data.`enum`.toString.toJsonASTOrFail,
      "list" -> data.list.toJsonASTOrFail,
      "name" -> data.name.toJsonASTOrFail,
      "enumTrait" -> data.enumTrait.toString.toJsonASTOrFail,
      "objectList" -> data.objectList
        .map(obj =>
          Map(
            "id" -> obj.id,
            "value" -> obj.value,
            "enum" -> obj.`enum`.toString
          ).toJsonASTOrFail
        )
        .toJsonASTOrFail,
      "nonEmptyList" -> data.nonEmptyList.toList.toJsonASTOrFail,
      "optional" -> data.optional.fold(Json.Null)(_.toString.toJsonASTOrFail)
    ).toJsonASTOrFail)
  }

  "jsonArrayContainsValue" should "work" in {
    val res =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.jsonArrayContainsValue(
              "data->'nonEmptyList'",
              data1.nonEmptyList.head
            )
          )
          .transact(
            txn
          )
      )

    res.head.data.name shouldBe (data1.name)
    res.length shouldBe 1

    val res2 =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.jsonArrayContainsValue(
              "data->'objectList'",
              ("id", data1.objectList.head.id)
            )
          )
          .transact(
            txn
          )
      )

    res2.head.data.name shouldBe (data1.name)
    res2.length shouldBe 1
  }

  "jsonArrayContainsAnyValue" should "work" in {
    val res =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.jsonArrayContainsAnyValue(
              "data->'nonEmptyList'",
              data1.nonEmptyList.toList ++ data2.nonEmptyList.toList
            )
          )
          .transact(
            txn
          )
      )

    res.map(_.data.name) shouldBe List(data1.name, data2.name)
    res.length shouldBe 2

    val res2 =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.jsonArrayContainsAnyValue(
              "data->'nonEmptyList'",
              List()
            )
          )
          .transact(
            txn
          )
      )

    res2.length shouldBe >(0)
  }

  "jsonValueInArray" should "work" in {
    val res =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.jsonValueInArray(
              "data->>'name'",
              List(data1.name, data2.name)
            )
          )
          .transact(
            txn
          )
      )

    res.map(_.data.name) shouldBe (List(data1.name, data2.name))
    res.length shouldBe 2

    val res2 =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.jsonValueInArray(
              "data->>'name'",
              List()
            )
          )
          .transact(
            txn
          )
      )
    res2.length shouldBe >(0)
  }

  "jsonObjectArrayContainsAnyValue" should "work" in {
    val res =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.jsonObjectArrayContainsAnyValue(
              "data->'objectList'",
              x => s"${x}->'id'",
              data1.objectList.map(_.id) ++ data2.objectList.map(_.id)
            )
          )
          .transact(
            txn
          )
      )

    res.map(_.data.name) shouldBe (List(data1.name, data2.name))
    res.length shouldBe 2

    val res2 =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.jsonObjectArrayContainsAnyValue(
              "data->'objectList'",
              x => s"${x}->'id'",
              List()
            )
          )
          .transact(
            txn
          )
      )
    res2.length shouldBe >(0)
  }

  "textFieldInArray" should "work" in {
    val res =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .where(
            Filter.textFieldInArray(
              "id",
              List(id1.toString, id2.toString)
            )
          )
          .transact(
            txn
          )
      )

    res.map(_.data.id) shouldBe (List(data1.id, data2.id))
    res.length shouldBe 2

    val res2 = transact(txn =>
      select[Id, Unit, ExampleDocumentData]("test.example")
        .where(
          Filter.textFieldInArray(
            "id",
            List()
          )
        )
        .transact(
          txn
        )
    )
    res2.length shouldBe >(0)
  }

  "orderBy" should "work" in {
    val res1 =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .orderBy(fr0"created DESC")
          .transact(txn)
      )

    val res2 =
      transact(txn =>
        select[Id, Unit, ExampleDocumentData]("test.example")
          .orderBy(fr0"created ASC")
          .transact(
            txn
          )
      )

    res1.head.data.name shouldBe (res2.last.data.name)
    res2.head.data.name shouldBe (res1.last.data.name)
  }

  "selectUpdated" should "work" in {
    val now = OffsetDateTime.now
    val data = generateData()
    val id = insertData(
      transact,
      NonEmptyList.of(
        data
      )
    )
    val res1 =
      transact(txn =>
        selectUpdated[Id, Unit, ExampleDocumentData](
          "test.example",
          None,
          Some(now)
        ).transact(
          txn
        )
      )

    res1.exists(d => d.id === id) shouldBe false
  }

  "selectWhere complex" should "work" in {
    val res = transact(txn =>
      select[Id, Unit, ExampleDocumentData]("test.example")
        .where(
          (Filter
            .iLike("data->>'name'", s"%${data1.name.substring(1, 5)}%")
            .or(
              Filter.jsonObjectArrayContainsAnyValue(
                "data->'objectList'",
                x => s"${x}->'id'",
                data2.objectList.map(_.id)
              )
            )
            .and(
              Filter.jsonArrayContainsValue(
                "data->'objectList'",
                ("id", data2.objectList.head.id)
              )
            ))
        )
        .orderBy(fr0"created DESC")
        .transact(txn)
    )

    res.head.data.name shouldBe (data2.name)
    res.length shouldBe 1
  }
