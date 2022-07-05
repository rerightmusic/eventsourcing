package shared.json

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import shared.newtypes.*
import shared.uuid.all.*
import eventsourcing.all.*
import cats.data.NonEmptyList
import shared.json.all.{given, *}

class JsonSpec extends AnyFlatSpec with Matchers:
  "Zio Json" should "work" in {
    JsonEncoder[Unit]
      .toJsonAST(())
      .getOrElse(throw new Exception()) shouldBe Json.Obj()

    (NotUpdated: Updated[
      Option[List[Int]]
    ]).toJsonAST.getOrElse(throw new Exception()) shouldBe Json.Null

    (HasUpdated(None): Updated[
      Option[List[Int]]
    ]).toJsonAST.getOrElse(throw new Exception()) shouldBe Json.Null

    (HasUpdated(Some(List(1))): Updated[
      Option[List[Int]]
    ]).toJsonAST.getOrElse(throw new Exception()) shouldBe Json.Arr(Json.Num(1))

    (Map(
      "a" -> (NotUpdated: Updated[
        Option[List[Int]]
      ])
    )).toJsonAST.getOrElse(throw new Exception()) shouldBe Json.Obj()

    val x = (Map(
      "a" -> (HasUpdated(None): Updated[
        Option[List[Int]]
      ])
    ))

    (Map(
      "a" -> (HasUpdated(None): Updated[
        Option[List[Int]]
      ])
    )).toJsonAST.getOrElse(throw new Exception()) shouldBe Json.Obj(
      "a" -> Json.Null
    )

    (Map(
      "a" -> (HasUpdated(Some(List(1))): Updated[
        Option[List[Int]]
      ])
    )).toJsonAST.getOrElse(throw new Exception()) shouldBe Json.Obj(
      "a" -> Json.Arr(Json.Num(1))
    )

    "null".fromJson[Updated[
      Option[List[Int]]
    ]] shouldBe Right(HasUpdated(None))

    "[1]".fromJson[Updated[
      Option[List[Int]]
    ]] shouldBe Right(HasUpdated(Some(List(1))))

    val uuid = generateUUIDSync
    val default = Record(
      "a",
      None,
      NotUpdated,
      NotUpdated,
      NotUpdated,
      NotUpdated,
      NotUpdated,
      NotUpdated,
      Some(Enum.A),
      Some(Sum.SumA),
      Some(NonEmptyList.of("1")),
      New(uuid),
      SumOfProducts.SumA("a", None),
      Some(List(1))
    )

    s"""{"fieldA":"1","fieldL":"${uuid.toString}","fieldM":{"SumA": {"ta":"a"}}}"""
      .fromJson[Record] shouldBe (Right(
      Record(
        "1",
        None,
        NotUpdated,
        NotUpdated,
        NotUpdated,
        NotUpdated,
        NotUpdated,
        NotUpdated,
        None,
        None,
        None,
        New(uuid),
        SumOfProducts.SumA("a", None),
        None
      )
    ))

    s"""{"fieldA":"1","fieldB":null,"fieldD":null,"fieldE":[1,2],"fieldF":null,"fieldH":null,"fieldI":"A","fieldJ":"SumA","fieldK":null,"fieldL":"${uuid.toString}","fieldM":{"SumA":{"ta":"a","tb":null}},"fieldN":null}"""
      .fromJson[Record] shouldBe (Right(
      Record(
        "1",
        None,
        NotUpdated,
        HasUpdated(None),
        HasUpdated(NonEmptyList.of(1, 2)),
        HasUpdated(None),
        NotUpdated,
        HasUpdated(None),
        Some(Enum.A),
        Some(Sum.SumA),
        None,
        New(uuid),
        SumOfProducts.SumA("a", None),
        None
      )
    ))

    Record(
      "1",
      None,
      NotUpdated,
      HasUpdated(None),
      HasUpdated(NonEmptyList.of(1, 2)),
      HasUpdated(None),
      NotUpdated,
      HasUpdated(None),
      Some(Enum.A),
      Some(Sum.SumA),
      None,
      New(uuid),
      SumOfProducts.SumA("a", None),
      None
    ).toJson shouldBe s"""{"fieldA":"1","fieldB":null,"fieldD":null,"fieldE":[1,2],"fieldF":null,"fieldH":null,"fieldI":"A","fieldJ":"SumA","fieldK":null,"fieldL":"${uuid.toString}","fieldM":{"SumA":{"ta":"a","tb":null}},"fieldN":null}"""
  }
