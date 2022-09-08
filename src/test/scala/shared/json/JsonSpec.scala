package shared.json

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import shared.newtypes.*
import shared.uuid.all.*
import cats.data.NonEmptyList
import shared.json.all.{given, *}

class JsonSpec extends AnyFlatSpec with Matchers:
  "Zio Json" should "work" in {
    JsonEncoder[Unit]
      .toJsonAST(())
      .getOrElse(throw new Exception()) shouldBe Json.Obj()

    val uuid = generateUUIDSync
    val default = Record(
      "a",
      None,
      Some(Enum.A),
      Some(Sum.SumA),
      Some(NonEmptyList.of("1")),
      New(uuid),
      SumOfProducts.SumA("a", None),
      Some(List(1))
    )

    s"""{"fieldA":"1","fieldF":"${uuid.toString}","fieldG":{"SumA": {"ta":"a"}}}"""
      .fromJson[Record] shouldBe (Right(
      Record(
        "1",
        None,
        None,
        None,
        None,
        New(uuid),
        SumOfProducts.SumA("a", None),
        None
      )
    ))

    s"""{"fieldA":"1","fieldC":"A","fieldD":"SumA","fieldE":null,"fieldF":"${uuid.toString}","fieldG":{"SumA":{"ta":"a","tb":null}},"fieldH":null}"""
      .fromJson[Record] shouldBe (Right(
      Record(
        "1",
        None,
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
      Some(Enum.A),
      Some(Sum.SumA),
      None,
      New(uuid),
      SumOfProducts.SumA("a", None),
      None
    ).toJson shouldBe s"""{"fieldA":"1","fieldB":null,"fieldC":"A","fieldD":"SumA","fieldE":null,"fieldF":"${uuid.toString}","fieldG":{"SumA":{"ta":"a","tb":null}},"fieldH":null}"""
  }
