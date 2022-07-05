package shared.data.automapper

import shared.macros.all.*
import scala.quoted._
import scala.util.control.NonFatal
import izumi.reflect.macrortti.LightTypeTagRef.SymName.SymTypeName

def materializeNonDynamicMapping[S, T](
  source: Expr[S]
)(using sourceType: Type[S], targetType: Type[T], quotes: Quotes): Expr[T] =
  materializeMapping(using quotes)(source)

def materializeDynamicMapping[S, T](source: Expr[S])(
  dynamicParams: Expr[Seq[(String, Any)]]
)(using sourceType: Type[S], targetType: Type[T], quotes: Quotes): Expr[T] =
  import quotes.reflect._
  val dynamicNamedArgs = dynamicParams.asTerm match {
    case Inlined(None, Nil, Typed(Repeated(term, _), _)) => term
    case t => matchFailed("MappingMacros Dynamic Arg", t)
  } map {
    case Apply(
          TypeApply(Select(Ident("Tuple2"), "apply"), _),
          List(Literal(StringConstant(field)), term)
        ) =>
      if TypeRepr.of[T].typeSymbol.declaredFields.exists(_.name == field) then
        NamedArg(field, term)
      else
        matchFailed(
          "MappingMacros Dynamic Missing field",
          (field, TypeRepr.of[T].show)
        )
      NamedArg(field, term)
    case t => matchFailed("MappingMacros Dynamic Map", t)
  }
  materializeMapping(using quotes)(source, dynamicNamedArgs)

def materializeMapping[S, T](using quotes: Quotes)(
  source: Expr[S],
  dynamicNamedArgs: List[quotes.reflect.NamedArg] =
    List.empty[quotes.reflect.NamedArg]
)(using sourceType: Type[S], targetType: Type[T]): Expr[T] =
  import quotes.reflect._

  val sourceTypeRepr = TypeRepr.of[S].dealias
  val targetTypeRepr = TypeRepr.of[T].dealias

  (sourceTypeRepr.asType, targetTypeRepr.asType) match {
    case _ if sourceTypeRepr.show == targetTypeRepr.show =>
      source.asExprOf[T]

    case ('[s], '[t]) if Expr.summon[s => t].isDefined =>
      val conversion = Expr.summon[s => t].get
      val sourceExpr: Expr[s] = source.asExprOf[s]
      '{ $conversion($sourceExpr) }.asExprOf[T]

    case ('[Map[sk, sv]], '[Map[tk, tv]]) =>
      val sourceExpr: Expr[Map[sk, sv]] = source.asExprOf[Map[sk, sv]]
      '{
        $sourceExpr.view.map { case (key: sk, value: sv) =>
          (
            ${ materializeMapping[sk, tk](using quotes)('key) },
            ${ materializeMapping[sv, tv](using quotes)('value) }
          )
        }.toMap
      }.asExprOf[T]

    case ('[Iterable[s]], '[Iterable[t]]) =>
      val sourceExpr: Expr[Iterable[s]] = source.asExprOf[Iterable[s]]
      '{
        $sourceExpr.map((value: s) =>
          ${ materializeMapping[s, t](using quotes)('value) }
        )
      }.asExprOf[T]

    case ('[Option[s]], '[Option[t]]) =>
      val sourceExpr: Expr[Option[s]] = source.asExprOf[Option[s]]
      '{
        $sourceExpr.map((value: s) =>
          ${ materializeMapping[s, t](using quotes)('value) }
        )
      }.asExprOf[T]

    case _
        if isSumOfProducts(
          targetTypeRepr
        ) && !isSealedTraitEnum(targetTypeRepr) =>
      sourceTypeRepr.maybeParent match
        case Some(parRepr) =>
          assertChildrenMatch(parRepr, targetTypeRepr)
          val t = targetTypeRepr.typeSymbol.children
            .find(
              _.name.replace("$", "") == sourceTypeRepr.typeSymbol.name
                .replace("$", "")
            )
            .getOrElse(
              throw Exception(
                s"Failed to match case ${sourceTypeRepr.typeSymbol.name} " +
                  s"${targetTypeRepr.typeSymbol.name}"
              )
            )

          targetTypeRepr.selectChild(t).asType match
            case '[tk] =>
              materializeMapping[S, tk](using quotes)(
                source.asExprOf[S]
              ).asExprOf[T]
            case _ =>
              matchFailed(
                "MappingMacros SUM Specific",
                (targetTypeRepr.show, t.name)
              )
        case None =>
          assertChildrenMatch(sourceTypeRepr, targetTypeRepr)
          Match(
            source.asTerm,
            sourceTypeRepr.typeSymbol.children.map(sourceMemberType =>
              val targetMemberType = targetTypeRepr.typeSymbol.children
                .find(_.name == sourceMemberType.name)
                .getOrElse(
                  throw Exception(
                    s"Failed to match case ${sourceTypeRepr.termSymbol.name} in sum of products ${targetTypeRepr.typeSymbol.name}"
                  )
                )

              (
                sourceTypeRepr.selectChild(sourceMemberType).asType,
                targetTypeRepr.selectChild(targetMemberType).asType
              ) match
                case ('[sk], '[tk]) =>
                  CaseDef(
                    Typed(
                      Wildcard(),
                      sourceTypeRepr
                        .getType(sourceMemberType, _ => Wildcard().tpe)
                    ),
                    None,
                    Block(
                      List(),
                      materializeMapping[sk, tk](using quotes)(
                        TypeApply(
                          Select.unique(
                            source.asTerm,
                            "asInstanceOf"
                          ),
                          List(sourceTypeRepr.getType(sourceMemberType))
                        ).asExprOf[sk]
                      ).asTerm
                    )
                  )
                case _ =>
                  matchFailed(
                    "MappingMacros SUM",
                    (sourceTypeRepr.show, targetTypeRepr.show)
                  )
            )
          ).asExprOf[T]

    case ('[s], '[t])
        if targetTypeRepr.typeSymbol.flags.is(
          Flags.Case
        ) && targetTypeRepr.typeSymbol.flags.is(
          Flags.Final
        ) && targetTypeRepr.typeSymbol.declaredFields.isEmpty =>
      targetTypeRepr.ident.asExprOf[T]

    case ('[s], '[t])
        if targetTypeRepr.typeSymbol.flags
          .is(Flags.Case) && !isSealedTraitEnum(targetTypeRepr) =>
      val sourceFields = sourceTypeRepr.typeSymbol.declaredFields
      val targetFields = targetTypeRepr.typeSymbol.declaredFields

      val namedArgs = targetFields.flatMap { targetField =>

        val targetFieldTypeRepr = targetTypeRepr.memberType(targetField)
        val sourceFieldOption = sourceFields.find(_.name == targetField.name)
        val dynamicNamedArgOption =
          dynamicNamedArgs.find(_.name == targetField.name)

        dynamicNamedArgOption match {
          case Some(dynamicNamedArg) =>
            Some(dynamicNamedArg)
          case None =>
            sourceFieldOption match {
              case Some(sourceField) =>
                val sourceFieldTypeRepr = sourceTypeRepr.memberType(sourceField)
                (sourceFieldTypeRepr.asType, targetFieldTypeRepr.asType) match {
                  case ('[s], '[t]) =>
                    val fieldSelectorExpr =
                      Select(source.asTerm, sourceField).asExprOf[s]
                    Some(
                      NamedArg(
                        targetField.name,
                        materializeMapping[s, t](using quotes)(
                          fieldSelectorExpr
                        ).asTerm
                      )
                    )
                  case _ =>
                    matchFailed(
                      "MappingMacros CASE",
                      (sourceFieldTypeRepr.show, targetFieldTypeRepr.show)
                    )
                }
              case None =>
                targetFieldTypeRepr.asType match {
                  case _
                      if targetTypeRepr.typeSymbol
                        .memberField(targetField.name)
                        .flags
                        .is(Flags.HasDefault) =>
                    None
                  case '[Map[tk, tv]] =>
                    Some(
                      NamedArg(targetField.name, '{ Map.empty[tk, tv] }.asTerm)
                    )
                  case '[Iterable[t]] =>
                    Some(
                      NamedArg(
                        targetField.name,
                        TypeApply(
                          Select(
                            targetFieldTypeRepr.ident,
                            targetFieldTypeRepr.typeSymbol
                              .memberMethod("empty")
                              .head
                          ),
                          List(TypeTree.of[t])
                        )
                      )
                    )
                  case '[Option[t]] =>
                    Some(NamedArg(targetField.name, '{ None }.asTerm))
                  case '[t] => None
                  case _ =>
                    matchFailed(
                      "MappingMacros Wrapper",
                      targetFieldTypeRepr.show
                    )
                }
            }
        }
      }

      Select
        .overloaded(
          targetTypeRepr.ident,
          "apply",
          targetTypeRepr.typeParams,
          namedArgs
        )
        .asExprOf[T]

    case _
        if targetTypeRepr.typeSymbol.flags
          .is(Flags.Enum) || isSealedTraitEnum(targetTypeRepr) =>
      sourceTypeRepr.maybeParent match
        case Some(parRepr) =>
          assertChildrenMatch(parRepr, targetTypeRepr)
          Match(
            source.asTerm,
            List(
              CaseDef(
                parRepr.select(sourceTypeRepr.termSymbol).ident,
                None,
                Block(
                  List(),
                  Select(
                    targetTypeRepr.ident,
                    targetTypeRepr.typeSymbol.children
                      .find(_.name == sourceTypeRepr.termSymbol.name)
                      .getOrElse(
                        throw Exception(
                          s"Failed to match case ${sourceTypeRepr.termSymbol.name} in enums ${parRepr.typeSymbol.name} " +
                            s"${targetTypeRepr.typeSymbol.name}"
                        )
                      )
                  )
                )
              )
            )
          ).asExprOf[T]

        case None =>
          assertChildrenMatch(sourceTypeRepr, targetTypeRepr)
          Match(
            source.asTerm,
            sourceTypeRepr.typeSymbol.children.map(c =>
              CaseDef(
                Select(
                  sourceTypeRepr.ident,
                  c
                ),
                None,
                Block(
                  List(),
                  Select(
                    targetTypeRepr.ident,
                    targetTypeRepr.typeSymbol.children
                      .find(_.name == c.name)
                      .getOrElse(
                        throw Exception(
                          s"Failed to match case ${c.name} in enums ${sourceTypeRepr.typeSymbol.name} " +
                            s"${targetTypeRepr.typeSymbol.name}"
                        )
                      )
                  )
                )
              )
            )
          ).asExprOf[T]

    case _ =>
      source.asExprOf[T]

  }

def assertChildrenMatch(using
  quotes: Quotes
)(repr1: quotes.reflect.TypeRepr, repr2: quotes.reflect.TypeRepr) =
  import quotes.reflect.*
  val children1 =
    if repr1.typeSymbol.children.isEmpty then
      repr1.typeSymbol.declaredFields.filter(_.flags.is(Flags.Case))
    else repr1.typeSymbol.children
  val children2 =
    if repr2.typeSymbol.children.isEmpty then
      repr2.typeSymbol.declaredFields.filter(_.flags.is(Flags.Case))
    else repr2.typeSymbol.children
  if children1
      .map(_.name)
      .diff(children2.map(_.name))
      .nonEmpty
  then
    throw new Exception(
      s"Failed to match enums ${repr1.typeSymbol.name} children (${children1
        .map(_.name)
        .mkString(", ")}) and ${repr2.typeSymbol.name} children (${children2.map(_.name).mkString(", ")})"
    )
