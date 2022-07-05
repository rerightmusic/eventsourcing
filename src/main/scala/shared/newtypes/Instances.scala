package shared.newtypes

import cats.kernel.Order

object Instances:
  given [A, B](using ex: NewExtractor.Aux[A, B], or: Order[A]): Order[B] =
    Order.from((a, b) => or.compare(ex.to(a), ex.to(b)))
