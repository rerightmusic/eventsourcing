package shared.newtypes

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import shared.newtypes.*

class NewtypesSpec extends AnyFlatSpec with Matchers:
  "NewtypesSpec" should "provide NewExtractor" in {
    object A extends NewtypeWrapped[String]
    type A = A.type
    val extractor = summon[NewExtractor.Aux[A.Type, String]]
    extractor.to("a") should be(A("a"))
    extractor.from(A("a")) should be("a")
  }
