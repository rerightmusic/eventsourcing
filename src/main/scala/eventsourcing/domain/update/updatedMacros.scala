package eventsourcing.domain.update

import types.*
import scala.quoted._
import scala.util.control.NonFatal
import shared.data.automapper.all.*
import shared.macros.all.*

def materializeNonDynamicUpdated[F, U, UE](
  from: Expr[F],
  update: Expr[U]
)(using
  fromType: Type[F],
  updateType: Type[U],
  updateEventType: Type[UE],
  quotes: Quotes
): Expr[UE] =
  materializeUpdated(using quotes)(from, update)

def materializeDynamicUpdated[F, U, UE](from: Expr[F], update: Expr[U])(
  dynamicParams: Expr[Seq[(String, Any)]]
)(using
  fromType: Type[F],
  updateType: Type[U],
  updateEventType: Type[UE],
  quotes: Quotes
): Expr[UE] =
  import quotes.reflect._
  val dynamicNamedArgs = dynamicParams.asTerm match {
    case Inlined(None, Nil, Typed(Repeated(term, _), _)) => term
    case t => matchFailed("Updated Macros Dynamic Arg", t)
  } map {
    case Apply(
          TypeApply(Select(Ident("Tuple2"), "apply"), _),
          List(Literal(StringConstant(field)), term)
        ) =>
      if TypeRepr.of[UE].typeSymbol.declaredFields.exists(_.name == field) then
        NamedArg(field, term)
      else
        matchFailed(
          "UpdatedMacros Dynamic Missing field",
          (field, TypeRepr.of[U].show)
        )
    case t => matchFailed("Updated Macros Dynamic Map", t)
  }
  materializeUpdated(using quotes)(from, update, dynamicNamedArgs)

def materializeUpdated[F, U, UE](using
  quotes: Quotes
)(
  from: Expr[F],
  update: Expr[U],
  dynamicNamedArgs: List[quotes.reflect.NamedArg] =
    List.empty[quotes.reflect.NamedArg]
)(using
  fromType: Type[F],
  updateType: Type[U],
  updateEventType: Type[UE]
): Expr[UE] =
  import quotes.reflect._

  val fromTypeRepr = TypeRepr.of[F]
  val updateTypeRepr = TypeRepr.of[U]
  val updatedTypeRepr = TypeRepr.of[UE]

  (fromTypeRepr.asType, updateTypeRepr.asType, updatedTypeRepr.asType) match
    case _
        if fromTypeRepr.show == updateTypeRepr.show && updateTypeRepr.show == updatedTypeRepr.show =>
      '{ if $from != $update then $update else $from }.asExprOf[UE]

    case ('[t], '[p], '[Updated[ue]]) =>
      val fromExpr: Expr[t] = from.asExprOf[t]
      val updateExpr: Expr[p] = update.asExprOf[p]
      '{
        if $fromExpr == $updateExpr then NotUpdated
        else
          HasUpdated(
            ${
              materializeUpdated[t, p, ue](using quotes)(
                fromExpr,
                updateExpr
              )
            }
          )
      }.asExprOf[UE]

    case _ if isSumOfProducts(updateTypeRepr) =>
      updateTypeRepr match
        case _ if updateTypeRepr.typeSymbol.flags.is(Flags.Case) =>
          val t = updatedTypeRepr.typeSymbol.children
            .find(
              _.name.replace("$", "") == updateTypeRepr.typeSymbol.name
                .replace("$", "")
            )
            .getOrElse(
              throw Exception(
                s"Failed to match case ${updateTypeRepr.typeSymbol.name} " +
                  s"${updatedTypeRepr.typeSymbol.name}"
              )
            )

          updatedTypeRepr.selectChild(t).asType match
            case '[tk] =>
              materializeUpdated[F, U, tk](using quotes)(
                from.asExprOf[F],
                update.asExprOf[U]
              ).asExprOf[UE]
            case _ =>
              matchFailed(
                "Mapping Macros SUM Specific",
                (updatedTypeRepr.show, t.name)
              )

        case _ =>
          Match(
            update.asTerm,
            updateTypeRepr.typeSymbol.children.map(updateMemberType =>
              val fromMemberType = fromTypeRepr.typeSymbol.children
                .find(_.name == updateMemberType.name)
                .getOrElse(
                  throw Exception(
                    s"Failed to match case ${updateTypeRepr.termSymbol.name} in sum of products ${fromTypeRepr.typeSymbol.name}"
                  )
                )
              val updatedMemberType = updatedTypeRepr.typeSymbol.children
                .find(_.name == updateMemberType.name)
                .getOrElse(
                  throw Exception(
                    s"Failed to match case ${updateTypeRepr.termSymbol.name} in sum of products ${updatedTypeRepr.typeSymbol.name}"
                  )
                )
              val fromTpe = fromTypeRepr.getType(fromMemberType, x => x)
              val updateTpe = updateTypeRepr.getType(updateMemberType, x => x)
              (
                fromTypeRepr.selectChild(fromMemberType).asType,
                updateTypeRepr.selectChild(updateMemberType).asType,
                updatedTypeRepr.selectChild(updatedMemberType).asType
              ) match
                case ('[fk], '[uk], '[tk]) =>
                  CaseDef(
                    Typed(
                      Wildcard(),
                      updateTypeRepr
                        .getType(updateMemberType, _ => Wildcard().tpe)
                    ),
                    None,
                    Block(
                      List(),
                      '{
                        if ${
                            TypeApply(
                              Select.unique(
                                from.asTerm,
                                "isInstanceOf"
                              ),
                              List(updateTpe)
                            ).asExprOf[Boolean]
                          }
                        then
                          ${
                            materializeUpdated[fk, uk, tk](using quotes)(
                              TypeApply(
                                Select.unique(
                                  from.asTerm,
                                  "asInstanceOf"
                                ),
                                List(fromTpe)
                              ).asExprOf[fk],
                              TypeApply(
                                Select.unique(
                                  update.asTerm,
                                  "asInstanceOf"
                                ),
                                List(updateTpe)
                              ).asExprOf[uk]
                            )
                          }
                        else
                          ${
                            toEvent[uk, tk](using quotes)(
                              TypeApply(
                                Select.unique(
                                  update.asTerm,
                                  "asInstanceOf"
                                ),
                                List(updateTpe)
                              ).asExprOf[uk]
                            )
                          }
                      }.asTerm
                    )
                  )
                case _ =>
                  matchFailed(
                    "Mapping Macros SUM",
                    (updateTypeRepr.show, updatedTypeRepr.show)
                  )
            )
          ).asExprOf[UE]

    case ('[f], '[u], _)
        if updatedTypeRepr.typeSymbol.flags.is(
          Flags.Case
        ) && updatedTypeRepr.typeSymbol.flags.is(
          Flags.Final
        ) && updatedTypeRepr.typeSymbol.declaredFields.isEmpty =>
      updatedTypeRepr.ident.asExprOf[UE]

    case ('[t], '[p], _) if updateTypeRepr.typeSymbol.flags.is(Flags.Case) =>
      val fromFields = fromTypeRepr.typeSymbol.declaredFields
      val updateFields = updateTypeRepr.typeSymbol.declaredFields
      val updatedEventFields = updatedTypeRepr.typeSymbol.declaredFields

      val namedArgs = updatedEventFields.map { updatedField =>
        val fromFieldOption = fromFields.find(_.name == updatedField.name)
        val updateFieldOption = updateFields.find(_.name == updatedField.name)
        val dynamicNamedArgOption =
          dynamicNamedArgs.find(_.name == updatedField.name).map {
            case NamedArg(_, v) => v
          }

        val updatedEventFieldTypeRepr =
          updatedTypeRepr.memberType(updatedField)

        val updatedFieldType = updatedEventFieldTypeRepr.asType

        (fromFieldOption, updateFieldOption) match {
          case (Some(fromField), Some(updateField)) =>
            val updateFieldTypeRepr = updateTypeRepr.memberType(updateField)
            val fromFieldTypeRepr = fromTypeRepr.memberType(fromField)
            (
              fromFieldTypeRepr.asType,
              updateFieldTypeRepr.asType,
              updatedFieldType
            ) match {
              case ('[t], '[p], '[ue]) =>
                val fromFieldSelectorExpr =
                  Select(from.asTerm, fromField).asExprOf[t]
                val updateFieldSelectorExpr =
                  Select(update.asTerm, updateField).asExprOf[p]

                NamedArg(
                  updatedField.name,
                  dynamicNamedArgOption.fold(
                    materializeUpdated[t, t, ue](using quotes)(
                      fromFieldSelectorExpr,
                      updateFieldSelectorExpr.asExprOf[t]
                    ).asTerm
                  )(f =>
                    if f.tpe.isFunctionType then
                      val f_ = f.asExprOf[(t, p) => ue]
                      '{
                        $f_(
                          $fromFieldSelectorExpr,
                          $updateFieldSelectorExpr
                        )
                      }.asTerm
                    else f.asExprOf[ue].asTerm
                  )
                )
              case t =>
                matchFailed(
                  "UpdatedMacros CASE",
                  s"""
                    ${updateField.name}
                    ${updatedTypeRepr.show}
                    ${fromTypeRepr.show}
                    ${fromFieldTypeRepr.show}
                    ${updateTypeRepr.show}
                    ${updateFieldTypeRepr.show}
                  """
                )
            }
          case (Some(fromField), None) =>
            NamedArg(
              updatedField.name,
              '{ NotUpdated }.asTerm
            )

          case (None, Some(updateField)) =>
            updateTypeRepr.memberType(updateField).asType match
              case '[u] =>
                NamedArg(
                  updatedField.name,
                  '{
                    HasUpdated(
                      ${
                        Select(update.asTerm, updateField)
                          .asExprOf[u]
                      }
                    )
                  }.asTerm
                )
              case _ =>
                matchFailed("Updated Macros CASE Field", updateTypeRepr.show)

          case (None, None) =>
            (dynamicNamedArgOption, updatedFieldType) match
              case (Some(f), '[ue]) =>
                val f_ = f.asExprOf[ue]

                NamedArg(
                  updatedField.name,
                  f_.asTerm
                )
              case _ =>
                matchFailed(
                  "Updated Macros CASE WILDCARD",
                  (updatedTypeRepr.show, fromTypeRepr.show, updateTypeRepr.show)
                )
        }
      }

      Select
        .overloaded(
          updatedTypeRepr.ident,
          "apply",
          updatedTypeRepr.typeParams,
          namedArgs
        )
        .asExprOf[UE]

    case ('[Option[t]], '[Option[u]], '[Option[ev]]) =>
      '{
        (
          ${ from.asExprOf[Option[t]] },
          ${ update.asExprOf[Option[u]] }
        ) match
          case (_, None) => None
          case (Some(tt), Some(uu)) =>
            Some(${ materializeUpdated[t, u, ev](using quotes)('tt, 'uu) })
          case (None, Some(uu)) =>
            Some(${ toEvent[u, ev](using quotes)('uu) })
      }.asExprOf[UE]

    case _ =>
      matchFailed(
        "Updated Macros WILDCARD",
        (fromTypeRepr.show, updateTypeRepr.show, updatedTypeRepr.show)
      )

def toEvent[U, Ev](using quotes: Quotes)(
  update: Expr[U]
)(using updateType: Type[U], eventType: Type[Ev]): Expr[Ev] =
  import quotes.reflect._
  val eventTypeRepr = TypeRepr.of[Ev]
  val updateTypeRepr = TypeRepr.of[U]
  eventTypeRepr.asType match
    case _ if isSumOfProducts(updateTypeRepr) =>
      updateTypeRepr match
        case _ if updateTypeRepr.typeSymbol.flags.is(Flags.Case) =>
          val t = eventTypeRepr.typeSymbol.children
            .find(
              _.name.replace("$", "") == updateTypeRepr.typeSymbol.name
                .replace("$", "")
            )
            .getOrElse(
              throw Exception(
                s"Failed to match case ${updateTypeRepr.typeSymbol.name} " +
                  s"${eventTypeRepr.typeSymbol.name}"
              )
            )

          eventTypeRepr.selectChild(t).asType match
            case '[tk] =>
              toEvent[U, tk](using quotes)(
                update.asExprOf[U]
              ).asExprOf[Ev]
            case _ =>
              matchFailed(
                "Mapping Macros SUM Specific",
                (eventTypeRepr.show, t.name)
              )
        case _ =>
          Match(
            update.asTerm,
            updateTypeRepr.typeSymbol.children.map(updateMemberType =>
              val eventMemberType = eventTypeRepr.typeSymbol.children
                .find(_.name == updateMemberType.name)
                .getOrElse(
                  throw Exception(
                    s"Failed to match case ${updateTypeRepr.termSymbol.name} in sum of products ${eventTypeRepr.typeSymbol.name}"
                  )
                )

              (
                updateTypeRepr.selectChild(updateMemberType).asType,
                eventTypeRepr.selectChild(eventMemberType).asType
              ) match
                case ('[uk], '[tk]) =>
                  CaseDef(
                    Typed(
                      Wildcard(),
                      updateTypeRepr
                        .getType(updateMemberType, _ => Wildcard().tpe)
                    ),
                    None,
                    Block(
                      List(),
                      toEvent[uk, tk](using quotes)(
                        TypeApply(
                          Select.unique(
                            update.asTerm,
                            "asInstanceOf"
                          ),
                          List(updateTypeRepr.getType(updateMemberType, x => x))
                        ).asExprOf[uk]
                      ).asTerm
                    )
                  )
                case _ =>
                  matchFailed(
                    "Mapping Macros SUM",
                    (updateTypeRepr.show, eventTypeRepr.show)
                  )
            )
          ).asExprOf[Ev]

    case _
        if eventTypeRepr.typeSymbol.flags.is(
          Flags.Case
        ) && eventTypeRepr.typeSymbol.flags.is(
          Flags.Final
        ) && eventTypeRepr.typeSymbol.declaredFields.isEmpty =>
      eventTypeRepr.ident.asExprOf[Ev]

    case '[ev] if eventTypeRepr.typeSymbol.flags.is(Flags.Case) =>
      val namedArgs = eventTypeRepr.typeSymbol.declaredFields.map {
        eventField =>
          val eventFieldTypeRepr = eventTypeRepr.memberType(eventField)
          val updateField =
            updateTypeRepr.typeSymbol.declaredFields
              .find(x => x.name == eventField.name)
              .getOrElse(
                throw new Exception(
                  s"Field is missing ${eventField.name} in ${updateTypeRepr.show}"
                )
              )
          val updateFieldTypeRepr = updateTypeRepr.memberType(updateField)
          (eventFieldTypeRepr.asType, updateFieldTypeRepr.asType) match
            case ('[f], '[t]) =>
              val updateSelected =
                Select(update.asTerm, updateField).asExprOf[t]
              NamedArg(
                updateField.name,
                toEvent[t, f](using quotes)(
                  updateSelected
                ).asTerm
              )
            case _ =>
              matchFailed(
                "UpdateMacros toEvent CASE",
                (eventFieldTypeRepr.show, updateFieldTypeRepr.show)
              )
      }

      Select
        .overloaded(
          eventTypeRepr.ident,
          "apply",
          eventTypeRepr.typeParams,
          namedArgs
        )
        .asExprOf[Ev]

    case '[Updated[u]] =>
      '{ HasUpdated(${ toEvent[U, u](update) }) }.asExprOf[Ev]

    case _ =>
      '{ $update.auto.to[Ev] }
