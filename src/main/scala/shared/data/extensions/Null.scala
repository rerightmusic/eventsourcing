package shared.data.extensions

import zio.ZIO

object Null:
  extension [A](a: A | Null)
    def getOrElse(b: A) =
      if a == null then b else a

  extension [A](a: A | Null)
    def toOption =
      if a == null then None else Some(a)
