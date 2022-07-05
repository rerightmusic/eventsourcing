package shared.data.automapper

import testTypes.*

val t1 =
  sourcePackage.UnnestedSum1("1").auto.to[targetPackage.UnnestedSumOfProducts]

val t2 =
  HasDone("1").auto
    .to[Done[String]]

val t3 =
  HasDone(HasDone("1")).auto
    .to[Done[Done[String]]]

val t4 =
  HasDone(sourcePackage.UnnestedSum1("1")).auto
    .to[Done[targetPackage.UnnestedSumOfProducts]]

val t5 = HasDone(Some(Inner.InnerA)).auto
  .to[Done[Option[TargetInner]]]

val v: Done[Option[Inner]] = HasDone(Some(Inner.InnerA))
val t6 = v.auto
  .to[Done[Option[TargetInner]]]

val t7 = Source(SumOfProducts.SumA("a")).auto
  .to[Target]
