package shared.json

import scala.quoted.*
import shared.macros.all.*

inline def enumFromString[E: IsEnum](str: String): E = ${
  enumFromStringImpl[E]('str)
}

def enumFromStringImpl[E: Type](using Quotes)(
  str: Expr[String]
): Expr[E] =
  import quotes.reflect.*
  val repr = TypeRepr.of[E]
  if repr.typeSymbol.flags.is(Flags.Enum) then
    Select
      .overloaded(repr.ident, "valueOf", Nil, List(str.asTerm))
      .asExprOf[E]
  else
    Match(
      str.asTerm,
      repr.typeSymbol.children.map(c =>
        val childName = repr.selectChild(c).typeSymbol.name
        CaseDef(
          Literal(StringConstant(childName.replace("$", ""))),
          None,
          Ref(
            repr
              .selectChild(c)
              .typeSymbol
              .companionModule
          )
            .asExprOf[E]
            .asTerm
        )
      ) ++ List(
        CaseDef(
          Wildcard(),
          None,
          Select.overloaded(
            '{ (tpeName: String) =>
              throw new Exception(
                s"Failed to match ${$str} for enum ${tpeName}"
              )
            }.asTerm,
            "apply",
            Nil,
            List(Literal(StringConstant(repr.typeSymbol.name)))
          )
        )
      )
    ).asExprOf[E]
