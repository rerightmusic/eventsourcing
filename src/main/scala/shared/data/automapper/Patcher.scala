package shared.data.automapper

import scala.language.dynamics

def autopatch[T](target: T): PartialPatch[T] = new PartialPatch(target)

extension [S](s: S) def patch = autopatch(s)
class PartialPatch[T](target: T):
  inline def using[P](patch: P): T = ${
    materializePatcher[T, P]('target, 'patch)
  }
  inline def apply[P](patch: P): T = ${
    materializePatcher[T, P]('target, 'patch)
  }
