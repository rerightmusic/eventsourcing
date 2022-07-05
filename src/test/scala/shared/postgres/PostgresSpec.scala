package shared.postgres

import _root_.doobie.*
import _root_.doobie.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import shared.json.all.{given, *}
import shared.newtypes.*
import shared.postgres.schemaless.PostgresDocument
import shared.postgres.all.{*, given}
import shared.principals.PrincipalId
import shared.time.*
import shared.uuid.all.*
class PostgresSpec extends AnyFlatSpec with Matchers:
  "Newtype instances" should "should work" in {
    object A extends NewtypeWrapped[UUID]
    type A = A.Type
    val put = summon[Put[A]]
    val get = summon[Get[A]]

    fr"""${A(
      generateUUIDSync
    )}""".toString should be(fr"${generateUUIDSync}".toString)
  }
