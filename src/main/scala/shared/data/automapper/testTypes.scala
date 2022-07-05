package shared.data.automapper

object testTypes:
  sealed trait Done[+T]
  case object NotDone extends Done[Nothing]
  case class HasDone[+T](v: T) extends Done[T]

  sealed trait Inner
  object Inner:
    case object InnerA extends Inner
    case class InnerB(v: Int) extends Inner

  object sourcePackage:
    sealed trait UnnestedSumOfProducts
    case class UnnestedSum1(a: String) extends UnnestedSumOfProducts
    case class UnnestedSum2(b: Int) extends UnnestedSumOfProducts

  case class Source(v: SumOfProducts)
  sealed trait SumOfProducts
  object SumOfProducts:
    case class SumA(v: String) extends SumOfProducts
    case class SumB(v: Int) extends SumOfProducts

  sealed trait TargetInner
  object TargetInner:
    case object InnerA extends TargetInner
    case class InnerB(v: Int) extends TargetInner

  object targetPackage:
    sealed trait UnnestedSumOfProducts
    case class UnnestedSum1(a: String) extends UnnestedSumOfProducts
    case class UnnestedSum2(b: Int) extends UnnestedSumOfProducts

  case class Target(v: TargetSumOfProducts)
  sealed trait TargetSumOfProducts
  object TargetSumOfProducts:
    case class SumA(v: String) extends TargetSumOfProducts
    case class SumB(v: Int) extends TargetSumOfProducts
