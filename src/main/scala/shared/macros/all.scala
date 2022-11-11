package shared.macros

import scala.quoted._
import java.util.UUID

object all:
  trait IsNewtype[A]
  object IsNewtype:
    transparent inline given isNewtype[E]: IsNewtype[E] = ${
      isNewtypeImpl[E]
    }

  def isNewtypeImpl[E: Type](using Quotes): Expr[IsNewtype[E]] =
    import quotes.reflect.*
    val repr = TypeRepr.of[E]
    if repr.typeSymbol.owner.name == "Newtype" then '{ new IsNewtype[E] {} }
    else throw new Exception(s"Type ${repr.typeSymbol.name} isn't a newtype")

  trait IsEnum[A]:
    def enumFromString(x: String): A
    def toString(a: A) = a.toString

  object IsEnum:
    transparent inline given isEnum[E]: IsEnum[E] = ${
      isEnumImpl[E]
    }

  def isEnumImpl[E: Type](using Quotes): Expr[IsEnum[E]] =
    import quotes.reflect.*
    val repr = TypeRepr.of[E]
    if repr.typeSymbol.children.nonEmpty && repr.typeSymbol.children.forall(
        _.declaredFields.isEmpty && !repr.typeSymbol.flags.is(Flags.Case)
      )
    then
      '{
        new IsEnum[E] {
          def enumFromString(str: String) = ${ enumFromStringImpl[E]('str) }
        }
      }
    else throw new Exception(s"Type ${repr.typeSymbol.name} isn't an enum")

  private def enumFromStringImpl[E: Type](using Quotes)(
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

  trait IsProductOrSumOfProducts[A]
  object IsProductOrSumOfProducts:
    transparent inline given isProductOrSumOfProducts[E]
      : IsProductOrSumOfProducts[E] = ${
      isProductOrSumOfProductsImpl[E]
    }

  def isProductOrSumOfProductsImpl[E: Type](using
    Quotes
  ): Expr[IsProductOrSumOfProducts[E]] =
    import quotes.reflect.*
    val repr = TypeRepr.of[E]
    if (repr.typeSymbol.flags.is(
        Flags.Case
      ) && repr.typeSymbol.declaredFields.nonEmpty) || isSumOfProducts(repr)
    then '{ new IsProductOrSumOfProducts[E] {} }
    else
      throw new Exception(
        s"Type ${repr.typeSymbol.name} isn't a Product or Sum of Products"
      )

  def matchFailed[A](context: String, t: A) = throw new Exception(
    s"${context}: Unable to match type ${t}"
  )

  def isSealedTraitEnum(using quotes: Quotes)(e: quotes.reflect.TypeRepr) =
    e.typeSymbol.flags.is(quotes.reflect.Flags.Sealed) && e.typeSymbol.flags.is(
      quotes.reflect.Flags.Trait
    ) && e.typeSymbol.children.forall(c =>
      c.flags.is(quotes.reflect.Flags.Final)
    )

  def isSumOfProducts(using quotes: Quotes)(e: quotes.reflect.TypeRepr) =
    e.typeSymbol.flags.is(quotes.reflect.Flags.Sealed) && e.typeSymbol.flags.is(
      quotes.reflect.Flags.Trait
    ) &&
      e.typeSymbol.declaredFields.isEmpty &&
      e.typeSymbol.children
        .flatMap(_.declaredFields)
        .nonEmpty && e.typeSymbol.children.nonEmpty

  extension (using quotes: Quotes)(typeRepr: quotes.reflect.TypeRepr)
    def maybeParent =
      if typeRepr.typeSymbol.flags.is(quotes.reflect.Flags.Case) then
        typeRepr match
          case quotes.reflect.TypeRef(par, name) => Some(par)
          case quotes.reflect.TermRef(par, name) => Some(par)
          case quotes.reflect
                .AppliedType(par, _) =>
            Some(par)
          case _ => None
      else None

    def ident = typeRepr match
      case quotes.reflect.TypeRef(prefix, name) =>
        quotes.reflect.Ident(quotes.reflect.TermRef(prefix, name))
      case quotes.reflect
            .AppliedType(quotes.reflect.TypeRef(prefix, name), _) =>
        quotes.reflect.Ident(quotes.reflect.TermRef(prefix, name))
      case quotes.reflect.TermRef(a, b) =>
        quotes.reflect.Ident(quotes.reflect.TermRef(a, b))
      case _ => matchFailed("Mapping Macros Ident", typeRepr.show)

    def typeParams = typeRepr match
      case quotes.reflect.AppliedType(_, params) => params
      case _                                     => Nil

    def selectChild(child: quotes.reflect.Symbol): quotes.reflect.TypeRepr =
      if child.owner.name
          .replace("$", "") != typeRepr.typeSymbol.name
      then
        typeRepr match
          case quotes.reflect.TypeRef(par, _) =>
            par.select(child)
          case quotes.reflect
                .AppliedType(quotes.reflect.TypeRef(par, _), params) =>
            if (child.typeMembers.nonEmpty) par.select(child).appliedTo(params)
            else par.select(child)
          case _ =>
            matchFailed(
              "Mapping Macros selectChild",
              (typeRepr.show, child.name)
            )
      else quotes.reflect.Select(typeRepr.ident, child).tpe

    def getType(
      child: quotes.reflect.Symbol,
      mapTypeParams: quotes.reflect.TypeRepr => quotes.reflect.TypeRepr =
        (x: quotes.reflect.TypeRepr) => x
    ) =
      if child.owner.name
          .replace("$", "") != typeRepr.typeSymbol.name
      then
        typeRepr match
          case quotes.reflect.TypeRef(par, _) =>
            quotes.reflect.TypeTree.of(using
              par
                .select(child)
                .asType
            )
          case quotes.reflect
                .AppliedType(quotes.reflect.TypeRef(par, _), params) =>
            if child.typeMembers.nonEmpty then
              quotes.reflect.TypeTree.of(using
                par
                  .select(child)
                  .appliedTo(params.map(mapTypeParams))
                  .asType
              )
            else quotes.reflect.TypeTree.of(using par.select(child).asType)
          case _ =>
            matchFailed("Mapping Macros GetType", (typeRepr.show, child.name))
      else
        quotes.reflect.TypeTree
          .of(using typeRepr.ident.tpe.select(child).asType)
