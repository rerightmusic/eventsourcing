package shared.postgres.doobie

import _root_.doobie.util.{Get, Put}
import shared.newtypes.NewExtractor
import shared.json.all.*
import org.postgresql.util.PGobject
import cats.data.NonEmptyList
import cats.syntax.all.*
import doobie.util.*
import cats.Show

object AllInstances extends NewtypeInstances:
  implicit val showPGobject: Show[PGobject] = Show.show(_.getValue.take(250))
  given getZioJson(using
    s: Get[String]
  ): Get[Json] =
    Get.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .temap(a => JsonDecoder[Json].decodeJson(a.getValue).leftMap(_.show))

  given putZioJson: Put[Json] =
    Put.Advanced
      .other[PGobject](
        NonEmptyList.of("jsonb")
      )
      .tcontramap { a =>
        val o = new PGobject
        o.setType("jsonb")
        o.setValue(a.toJson)
        o
      }
