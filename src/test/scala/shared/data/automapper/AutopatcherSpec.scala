package shared.data.automapper

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AutopatcherSpec extends AnyWordSpec with Matchers with PatcherTestData {

  "autopatch" should {

    "patch a case class with another case class as expected" in {

      autopatch(targetPatch).using(patch) shouldBe TargetPatchClass(
        field = "patchedField",
        data = TargetData("patchedLabel", 110),
        list = valuesPatch,
        typedList = List(
          TargetData("patchedLabel1", 11),
          TargetData("patchedLabel1", 12),
          TargetData("patchedLabel1", 13)
        ),
        optional = Some("patchedOptional"),
        typedOptional = Some(TargetData("patchedLabel", 110)),
        map = mapPatch,
        typedMap = Map(
          "one" -> TargetData("patchedLabel1", 11),
          "two" -> TargetData("patchedLabel2", 12)
        ),
        level1 = TargetLevel1(Some(TargetLevel2("patchedTreasure")))
      )

    }

  }

}
