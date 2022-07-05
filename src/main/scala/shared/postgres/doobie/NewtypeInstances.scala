package shared.postgres.doobie

import _root_.doobie.util.{Get, Put}
import shared.newtypes.NewExtractor
import shared.macros.all.IsNewtype

trait NewtypeInstances:
  inline given getNewtype[A, B](using
    ex: NewExtractor.Aux[A, B],
    s: Get[B]
  ): Get[A] = Get[B].map(ex.to)

  inline given putNewtype[A, B](using
    ex: NewExtractor.Aux[A, B],
    s: Put[B]
  ): Put[A] = Put[B].contramap(ex.from)
