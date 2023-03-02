package eventsourcing.test.json

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import shared.uuid.all.*
import eventsourcing.all.*
import cats.data.NonEmptyList
import shared.json.all.{given, *}
import eventsourcing.all.given

class AggregatesJsonSpec extends AnyFlatSpec with Matchers:
  "Aggregates Json" should "work" in {
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
      NotUpdated,
      NotUpdated,
      NotUpdated,
      NotUpdated,
      NotUpdated,
      NotUpdated,
      NotUpdated
    )

    s"""{}"""
      .fromJson[Record] shouldBe (Right(
      Record(
        NotUpdated,
        NotUpdated,
        NotUpdated,
        NotUpdated,
        NotUpdated,
        NotUpdated,
        NotUpdated
      )
    ))

    s"""{"fieldB":null,"fieldC":[1,2],"fieldD":null,"fieldF":null,"fieldG":null}"""
      .fromJson[Record] shouldBe (Right(
      Record(
        NotUpdated,
        HasUpdated(None),
        HasUpdated(NonEmptyList.of(1, 2)),
        HasUpdated(None),
        NotUpdated,
        HasUpdated(None),
        HasUpdated(None)
      )
    ))

    Record(
      NotUpdated,
      HasUpdated(None),
      HasUpdated(NonEmptyList.of(1, 2)),
      HasUpdated(None),
      NotUpdated,
      HasUpdated(None),
      HasUpdated(None)
    ).toJson shouldBe s"""{"fieldB":null,"fieldC":[1,2],"fieldD":null,"fieldF":null,"fieldG":null}"""
  }
