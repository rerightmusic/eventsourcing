package shared.data.automapper

import cats.data.NonEmptyList

case class SourceClass(
  field: String,
  data: SourceData,
  list: List[Int],
  nel: NonEmptyList[Int],
  typedList: List[SourceData],
  optional: Option[String],
  typedOptional: Option[SourceData],
  map: Map[String, Int],
  typedMap: Map[String, SourceData],
  level1: SourceLevel1,
  sumOfProducts: SumOfProducts,
  unnestedSumOfProducts: sourcePackage.UnnestedSumOfProducts,
  sumOfProductsAndObjects: SumOfProductsAndObjects[Int],
  complex: SumOfProductsAndObjects[Option[ComplexInner]]
)
case class SourceData(label: String, value: Int)
case class SourceLevel1(level2: Option[SourceLevel2])
case class SourceLevel2(treasure: String)
sealed trait SumOfProducts
object SumOfProducts:
  case class Sum1(a: String) extends SumOfProducts
  case class Sum2(b: Int) extends SumOfProducts

object sourcePackage:
  sealed trait UnnestedSumOfProducts
  case class UnnestedSum1(a: String) extends UnnestedSumOfProducts
  case class UnnestedSum2(b: Int) extends UnnestedSumOfProducts

sealed trait SumOfProductsAndObjects[+T]
case class SumObj1[+T](a: T) extends SumOfProductsAndObjects[T]
case object SumObj2 extends SumOfProductsAndObjects[Nothing]

enum SourceEnum:
  case A, B, C, D

sealed trait SourceEnumTrait
object SourceEnumTrait:
  case object A extends SourceEnumTrait
  case object B extends SourceEnumTrait
  case object C extends SourceEnumTrait
  case object D extends SourceEnumTrait
  def x = 1
  val y = 2

sealed trait ComplexInner
object ComplexInner:
  case object Complex extends ComplexInner
  case class ComplexA(x: SumOfProductsAndObjects[Int]) extends ComplexInner
  case class ComplexB(x: SumOfProductsAndObjects[Int]) extends ComplexInner

case class TargetClass(
  field: String,
  data: TargetData,
  list: List[Int],
  nel: NonEmptyList[Int],
  typedList: List[TargetData],
  optional: Option[String],
  typedOptional: Option[TargetData],
  map: Map[String, Int],
  typedMap: Map[String, TargetData],
  level1: TargetLevel1,
  sumOfProducts: TargetSumOfProducts,
  unnestedSumOfProducts: targetPackage.UnnestedSumOfProducts,
  sumOfProductsAndObjects: SumOfProductsAndObjects[Int],
  complex: SumOfProductsAndObjects[Option[TargetComplexInner]]
)

case class TargetData(label: String, value: Int)
case class TargetLevel1(level2: Option[TargetLevel2])
case class TargetLevel2(treasure: String)

sealed trait TargetSumOfProducts
object TargetSumOfProducts:
  case class Sum1(a: String) extends TargetSumOfProducts
  case class Sum2(b: Int) extends TargetSumOfProducts

object targetPackage:
  sealed trait UnnestedSumOfProducts
  case class UnnestedSum1(a: String) extends UnnestedSumOfProducts
  case class UnnestedSum2(b: Int) extends UnnestedSumOfProducts

sealed trait TargetComplexInner
object TargetComplexInner:
  case object Complex extends TargetComplexInner
  case class ComplexA(x: SumOfProductsAndObjects[Int])
      extends TargetComplexInner
  case class ComplexB(x: SumOfProductsAndObjects[Int])
      extends TargetComplexInner
object TargetSumOfProductsDoesntMap:
  case class Sum11(a: String) extends TargetSumOfProducts
  case class Sum22(b: Int) extends TargetSumOfProducts

case class TargetSubset(data: TargetData)
case class TargetWithUnexpectedField(
  data: TargetData,
  unexpectedField: Exception
)
case class TargetWithOptionalUnexpectedField(
  data: TargetData,
  unexpectedField: Option[Exception]
)
case class TargetWithUnexpectedList(data: TargetData, unexpectedList: List[Int])
case class TargetWithUnexpectedMap(
  data: TargetData,
  unexpectedMap: Map[String, Int]
)
case class TargetWithDefaultValue(data: TargetData, default: String = "default")
case class TargetWithDynamicMapping(
  renamedField: String,
  data: TargetData,
  total: Int
)

enum TargetEnum:
  case A, B, C, D, E

def x(s: SourceEnumTrait) = s match {
  case shared.data.automapper.SourceEnumTrait.A =>
    shared.data.automapper.TargetEnum.A
  case shared.data.automapper.SourceEnumTrait.B =>
    shared.data.automapper.TargetEnum.B
  case shared.data.automapper.SourceEnumTrait.C =>
    shared.data.automapper.TargetEnum.C
  case shared.data.automapper.SourceEnumTrait.D =>
    shared.data.automapper.TargetEnum.D
}

sealed trait TargetEnumTrait
object TargetEnumTrait:
  case object A extends TargetEnumTrait
  case object B extends TargetEnumTrait
  case object C extends TargetEnumTrait
  case object D extends TargetEnumTrait
  def e = 1
  val z = 2

enum TargetEnumDoesntMap:
  case A, B, D, E

sealed trait TargetEnumTraitDoesntMap
object TargetEnumTraitDoesntMap:
  case object A extends TargetEnumTraitDoesntMap
  case object B extends TargetEnumTraitDoesntMap
  case object D extends TargetEnumTraitDoesntMap
  case object E extends TargetEnumTraitDoesntMap

object SomeObject {
  case class Source(value: String, data: Data)
  case class Data(value: Int)
}

object AnotherObject {
  case class Target(value: String, data: Data)
  case class Data(value: Int)
}

object reexport:
  export shared.data.automapper.TargetClass
trait TestData:
  val sourceData = SourceData("label", 10)
  val sourceLevel2 = SourceLevel2("treasure")
  val sourceLevel1 = SourceLevel1(Some(sourceLevel2))
  val sourceSumOfProducts = SumOfProducts.Sum1("a")
  val sourceUnnestedSumOfProducts = sourcePackage.UnnestedSum1("a")
  val sourceSumOfProductsAndObjects = SumObj2
  val sourceComplex: SumOfProductsAndObjects[Option[ComplexInner]] = SumObj2

  val sourceValues = List(1, 2, 3)
  val sourceNel = NonEmptyList.of(1, 2, 3)
  val sourceList = List(
    SourceData("label1", 1),
    SourceData("label1", 2),
    SourceData("label1", 3)
  )
  val sourceMap = Map("one" -> 1, "two" -> 2)
  val sourceMapWithData =
    Map("one" -> SourceData("label1", 1), "two" -> SourceData("label2", 2))

  val source = SourceClass(
    field = "field",
    data = sourceData,
    list = sourceValues,
    nel = sourceNel,
    typedList = sourceList,
    optional = Some("optional"),
    typedOptional = Some(sourceData),
    map = sourceMap,
    typedMap = sourceMapWithData,
    level1 = sourceLevel1,
    sumOfProducts = sourceSumOfProducts,
    unnestedSumOfProducts = sourceUnnestedSumOfProducts,
    sumOfProductsAndObjects = sourceSumOfProductsAndObjects,
    complex = sourceComplex
  )

  val targetData = TargetData("label", 10)
  val targetLevel2 = TargetLevel2("treasure")
  val targetLevel1 = TargetLevel1(Some(targetLevel2))
  val targetSumOfProducts = TargetSumOfProducts.Sum1("a")
  val targetUnnestedSumOfProducts = targetPackage.UnnestedSum1("a")
  val targetSumOfProductsAndObjects = SumObj2
  val targetComplex: SumOfProductsAndObjects[Option[TargetComplexInner]] =
    SumObj2

  val targetValues = List(1, 2, 3)
  val targetNel = NonEmptyList.of(1, 2, 3)
  val targetList = List(
    TargetData("label1", 1),
    TargetData("label1", 2),
    TargetData("label1", 3)
  )
  val targetMap = Map("one" -> 1, "two" -> 2)
  val targetMapWithData =
    Map("one" -> TargetData("label1", 1), "two" -> TargetData("label2", 2))

  val target = TargetClass(
    field = "field",
    data = targetData,
    list = targetValues,
    nel = targetNel,
    typedList = targetList,
    optional = Some("optional"),
    typedOptional = Some(targetData),
    map = targetMap,
    typedMap = targetMapWithData,
    level1 = targetLevel1,
    sumOfProducts = targetSumOfProducts,
    unnestedSumOfProducts = targetUnnestedSumOfProducts,
    sumOfProductsAndObjects = targetSumOfProductsAndObjects,
    complex = targetComplex
  )
