package shared.data.automapper

case class ClassPatch(
  field: String,
  data: DataPatch,
  list: List[Int],
  typedList: List[DataPatch],
  optional: Option[String],
  typedOptional: Option[DataPatch],
  map: Map[String, Int],
  typedMap: Map[String, DataPatch],
  level1: Level1Patch
)

case class DataPatch(label: String, value: Int)
case class Level1Patch(level2: Option[Level2Patch])
case class Level2Patch(treasure: String)

case class SubsetPatch(data: DataPatch)
case class WithUnexpectedFieldPatch(data: DataPatch, unexpectedField: Exception)
case class WithOptionalUnexpectedFieldPatch(
  data: DataPatch,
  unexpectedField: Option[Exception]
)
case class WithUnexpectedListPatch(data: DataPatch, unexpectedList: List[Int])
case class WithUnexpectedMapPatch(
  data: DataPatch,
  unexpectedMap: Map[String, Int]
)
case class WithDefaultValuePatch(data: DataPatch, default: String = "default")
case class WithDynamicMappingPatch(
  renamedField: String,
  data: DataPatch,
  total: Int
)

case class TargetPatchClass(
  field: String,
  data: TargetData,
  list: List[Int],
  typedList: List[TargetData],
  optional: Option[String],
  typedOptional: Option[TargetData],
  map: Map[String, Int],
  typedMap: Map[String, TargetData],
  level1: TargetLevel1
)

trait PatcherTestData extends TestData {

  val dataPatch = DataPatch("patchedLabel", 110)
  val level2Patch = Level2Patch("patchedTreasure")
  val level1Patch = Level1Patch(Some(level2Patch))
  val sumOfProducts = SumOfProducts.Sum1("a")
  val valuesPatch = List(11, 12, 13)
  val datasPatch = List(
    DataPatch("patchedLabel1", 11),
    DataPatch("patchedLabel1", 12),
    DataPatch("patchedLabel1", 13)
  )
  val mapPatch = Map("one" -> 11, "two" -> 12)
  val mapWithDataPatch = Map(
    "one" -> DataPatch("patchedLabel1", 11),
    "two" -> DataPatch("patchedLabel2", 12)
  )

  val patch = ClassPatch(
    field = "patchedField",
    data = dataPatch,
    list = valuesPatch,
    typedList = datasPatch,
    optional = Some("patchedOptional"),
    typedOptional = Some(dataPatch),
    map = mapPatch,
    typedMap = mapWithDataPatch,
    level1 = level1Patch
  )

  val targetPatch = TargetPatchClass(
    field = "field",
    data = targetData,
    list = targetValues,
    typedList = targetList,
    optional = Some("optional"),
    typedOptional = Some(targetData),
    map = targetMap,
    typedMap = targetMapWithData,
    level1 = targetLevel1
  )
}
