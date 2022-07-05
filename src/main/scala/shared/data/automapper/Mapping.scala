package shared.data.automapper

import scala.language.dynamics

def automap[S](source: S): PartialMapping[S] = new PartialMapping[S](source)

extension [S](s: S) def auto = automap(s)

class PartialMapping[S](source: S) extends Dynamic:
  inline def to[T]: T = ${ materializeNonDynamicMapping[S, T]('source) }
  inline def applyDynamicNamed[T](name: String)(
    inline args: (String, Any)*
  ): T = ${ materializeDynamicMapping[S, T]('source)('args) }
