package eventsourcing.test.json

import shared.json.all.{given, *}
import cats.data.NonEmptyList
import eventsourcing.all.{given, *}
import zio.json.*

case class Record(
  fieldA: Updated[Int],
  fieldB: UpdatedOpt[Int],
  fieldC: UpdatedNel[Int],
  fieldD: UpdatedOpt[NonEmptyList[Int]],
  fieldE: Updated[List[String]],
  fieldF: UpdatedOpt[List[String]],
  fieldG: UpdatedOpt[Map[String, String]]
) derives JsonCodec
