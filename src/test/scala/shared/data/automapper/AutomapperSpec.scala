package shared.data.automapper

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.annotation.targetName
import scala.quoted._
class AutomapperSpec extends AnyWordSpec with Matchers with TestData {

  "automap" should {

    "map a case class to another case class as expected" in {

      automap(source).to[TargetClass] shouldEqual target

    }

    "map a case class to another rexported case class as expected" in {

      automap(source).to[reexport.TargetClass] shouldEqual target

    }

    "map a case class with missing optionals to another case class as expected" in {

      val sourceWithMissingOptionals = SourceClass(
        "field",
        sourceData,
        sourceValues,
        sourceNel,
        sourceList,
        None,
        None,
        sourceMap,
        sourceMapWithData,
        sourceLevel1,
        sourceSumOfProducts,
        sourceUnnestedSumOfProducts,
        sourceSumOfProductsAndObjects,
        sourceComplex
      )
      val targetWithMissingOptionals = TargetClass(
        "field",
        targetData,
        targetValues,
        targetNel,
        targetList,
        None,
        None,
        targetMap,
        targetMapWithData,
        targetLevel1,
        targetSumOfProducts,
        targetUnnestedSumOfProducts,
        targetSumOfProductsAndObjects,
        targetComplex
      )

      automap(sourceWithMissingOptionals)
        .to[TargetClass] shouldEqual targetWithMissingOptionals

    }

    "map a case class to another case class with a subset of fields" in {

      automap(source).to[TargetSubset] shouldEqual TargetSubset(targetData)

    }

    "map a case class to another case class by setting None for fields not present in the first class" in {

      automap(source).to[
        TargetWithOptionalUnexpectedField
      ] shouldEqual TargetWithOptionalUnexpectedField(targetData, None)

    }

    "map a case class to another case class by setting an empty iterable for fields not present in the first class" in {

      automap(source)
        .to[TargetWithUnexpectedList] shouldEqual TargetWithUnexpectedList(
        targetData,
        List.empty
      )

    }

    "map a case class to another case class by setting an empty map for fields not present in the first class" in {

      automap(source)
        .to[TargetWithUnexpectedMap] shouldEqual TargetWithUnexpectedMap(
        targetData,
        Map.empty
      )

    }

    "map a case class to another case class by setting the default value for fields not present in the first class" in {

      automap(source)
        .to[TargetWithDefaultValue] shouldEqual TargetWithDefaultValue(
        targetData
      )

    }

    "map a case class to another case class when using a qualified type" in {

      automap(SomeObject.Source("value", SomeObject.Data(1)))
        .to[AnotherObject.Target] shouldEqual AnotherObject.Target(
        "value",
        AnotherObject.Data(1)
      )

    }

    "map an enum to another enum" in {
      automap(SourceEnum.A).to[TargetEnum] shouldEqual TargetEnum.A
    }

    "map an enum to another sealed trait enum" in {
      automap(SourceEnum.A).to[TargetEnumTrait] shouldEqual TargetEnumTrait.A
    }

    "map a sealed trait enum to another enum" in {
      val x: SourceEnumTrait = SourceEnumTrait.A
      automap(SourceEnumTrait.A: SourceEnumTrait)
        .to[TargetEnum] shouldEqual TargetEnum.A
      automap(SourceEnumTrait.A).to[TargetEnum] shouldEqual TargetEnum.A
    }

    "map a sealed trait enum to another sealed trait enum" in {
      automap(SourceEnumTrait.A)
        .to[TargetEnumTrait] shouldEqual TargetEnumTrait.A
      automap(SourceEnumTrait.A: SourceEnumTrait)
        .to[TargetEnumTrait] shouldEqual TargetEnumTrait.A
    }

    "map sum of products" in {
      automap(SumOfProducts.Sum1("a").asInstanceOf[SumOfProducts])
        .to[TargetSumOfProducts]
      automap(SumOfProducts.Sum1("b")).to[TargetSumOfProducts]
    }

    // "not compile if mapping cannot be generated" in {

    //   "automap(source).to[TargetWithUnexpectedField]" shouldNot compile

    // }

    // "not compile if enum mapping cannot be generated" in {
    //   automap(SourceEnum.A)
    //     .to[TargetEnumDoesntMap] shouldEqual TargetEnumDoesntMap.A
    //   automap(SourceEnum.A)
    //     .to[TargetEnumTraitDoesntMap] shouldEqual TargetEnumTraitDoesntMap.A
    //   automap(SourceEnumTrait.A)
    //     .to[TargetEnumDoesntMap] shouldEqual TargetEnumDoesntMap.A
    //   automap(SourceEnumTrait.A)
    //     .to[TargetEnumTraitDoesntMap] shouldEqual TargetEnumTraitDoesntMap.A
    // }

    // "not compile if sum of products mapping cannot be generated" in {
    //   // automap(SumOfProducts.A).to[TargetSumOfProductsDoesntMap.A]
    //   automap(SumOfProducts.Sum1("a").asInstanceOf[SumOfProducts])
    //     .to[TargetSumOfProducts]
    //   automap(SumOfProducts.Sum1("a")).to[TargetSumOfProducts]
    // }

  }

  "automap dynamically" should {

    val values = source.list
    def sum(values: List[Int]) = values.sum

    "map a case class to another case class allowing dynamic fields mapping" in {

      automap(source).dynamicallyTo[TargetWithDynamicMapping](
        renamedField = source.field,
        total = sum(values)
      ) shouldEqual TargetWithDynamicMapping("field", targetData, 6)

    }

    // "not compile if missing mappings have not been provided in the dynamic mapping" in {

    //   """
    //   automap(source).dynamicallyTo[TargetWithDynamicMapping](
    //     renamedField = source.field
    //   )
    //   """ shouldNot compile

    // }

    // "not compile if typechecking fails when assigning a field dynamically" in {

    //   """
    //   automap(source).dynamicallyTo[TargetWithDynamicMapping](
    //     renamedField = 10,
    //     total = "value"
    //   )
    //   """ shouldNot compile

    // }

  }

}
