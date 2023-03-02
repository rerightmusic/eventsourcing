package eventsourcing.domain.update

import types.*
import scala.quoted.*
import shared.macros.all.*
import shared.data.automapper.all.*

def materializeNonDynamicUpdate[T, U](
  target: Expr[T],
  update: Expr[U]
)(using sourceType: Type[T], targetType: Type[U], quotes: Quotes): Expr[T] =
  materializeUpdate(using quotes)(target, update)

def materializeDynamicUpdate[T, U](target: Expr[T], update: Expr[U])(
  dynamicParams: Expr[Seq[(String, Any)]]
)(using targetType: Type[T], updateType: Type[U], quotes: Quotes): Expr[T] =
  import quotes.reflect.*
  val dynamicNamedArgs = dynamicParams.asTerm match {
    case Inlined(None, Nil, Typed(Repeated(term, _), _)) => term
    case t => matchFailed("UpdateMacros Dynamic Arg", t)
  } map {
    case Apply(
          TypeApply(Select(Ident("Tuple2"), "apply"), _),
          List(Literal(StringConstant(field)), term)
        ) =>
      if TypeRepr.of[T].typeSymbol.declaredFields.exists(_.name == field) then
        NamedArg(field, term)
      else
        matchFailed(
          "UpdateMacros Dynamic Missing field",
          (field, TypeRepr.of[U].show)
        )
    case t => matchFailed("UpdateMacros Dynamic Map", t)
  }
  materializeUpdate(using quotes)(target, update, dynamicNamedArgs)

def materializeUpdate[T, U](using
  quotes: Quotes
)(
  target: Expr[T],
  update: Expr[U],
  dynamicNamedArgs: List[quotes.reflect.NamedArg] =
    List.empty[quotes.reflect.NamedArg]
)(using
  targetType: Type[T],
  updateType: Type[U]
): Expr[T] =
  import quotes.reflect.*

  val targetTypeRepr = TypeRepr.of[T]
  val updateTypeRepr = TypeRepr.of[U]

  (targetTypeRepr.asType, updateTypeRepr.asType) match
    case _ if targetTypeRepr.show == updateTypeRepr.show =>
      '{ if $target != $update then $update else $target }.asExprOf[T]
    case ('[t], '[Updated[p]]) =>
      val targetExpr: Expr[t] = target.asExprOf[t]
      val updateExpr: Expr[Updated[p]] =
        update.asExprOf[Updated[p]]
      '{
        $updateExpr match {
          case HasUpdated(updateValue) =>
            ${
              materializeUpdate[t, p](using quotes)(
                targetExpr,
                'updateValue
              ).asExprOf[T]
            }
          case NotUpdated => $target
        }
      }

    case _ if isSumOfProducts(targetTypeRepr) =>
      updateTypeRepr match
        case _ if updateTypeRepr.typeSymbol.flags.is(Flags.Case) =>
          val t = targetTypeRepr.typeSymbol.children
            .find(
              _.name.replace("$", "") == updateTypeRepr.typeSymbol.name
                .replace("$", "")
            )
            .getOrElse(
              throw Exception(
                s"Failed to match case ${updateTypeRepr.typeSymbol.name} " +
                  s"${targetTypeRepr.typeSymbol.name}"
              )
            )

          targetTypeRepr.selectChild(t).asType match
            case '[tk] =>
              materializeUpdate[tk, U](using quotes)(
                target.asExprOf[tk],
                update.asExprOf[U]
              ).asExprOf[T]
            case _ =>
              matchFailed(
                "Mapping Macros SUM Specific",
                (targetTypeRepr.show, t.name)
              )
        case _ =>
          Match(
            update.asTerm,
            updateTypeRepr.typeSymbol.children.map(updateMemberType =>
              val targetMemberType = targetTypeRepr.typeSymbol.children
                .find(_.name == updateMemberType.name)
                .getOrElse(
                  throw Exception(
                    s"Failed to match case ${updateTypeRepr.termSymbol.name} in sum of products ${targetTypeRepr.typeSymbol.name}"
                  )
                )

              val updateTpe = updateTypeRepr.getType(
                updateMemberType,
                x => x
              )
              val targetTpe = targetTypeRepr.getType(
                targetMemberType,
                x => x
              )
              (
                updateTypeRepr.selectChild(updateMemberType).asType,
                targetTypeRepr.selectChild(targetMemberType).asType
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
                      '{
                        if ${
                            TypeApply(
                              Select.unique(
                                target.asTerm,
                                "isInstanceOf"
                              ),
                              List(targetTpe)
                            ).asExprOf[Boolean]
                          }
                        then
                          ${
                            materializeUpdate[tk, uk](using quotes)(
                              TypeApply(
                                Select.unique(
                                  target.asTerm,
                                  "asInstanceOf"
                                ),
                                List(
                                  targetTpe
                                )
                              ).asExprOf[tk],
                              TypeApply(
                                Select.unique(
                                  update.asTerm,
                                  "asInstanceOf"
                                ),
                                List(
                                  updateTpe
                                )
                              ).asExprOf[uk]
                            )
                          }
                        else
                          ${
                            fromEvent[uk, tk](using quotes)(
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
                    (updateTypeRepr.show, targetTypeRepr.show)
                  )
            )
          ).asExprOf[T]

    case ('[t], '[u])
        if targetTypeRepr.typeSymbol.flags.is(
          Flags.Case
        ) && targetTypeRepr.typeSymbol.flags.is(
          Flags.Final
        ) && targetTypeRepr.typeSymbol.declaredFields.isEmpty =>
      targetTypeRepr.ident.asExprOf[T]

    case ('[t], '[p]) if updateTypeRepr.typeSymbol.flags.is(Flags.Case) =>
      val targetFields = targetTypeRepr.typeSymbol.declaredFields
      val updateFields = updateTypeRepr.typeSymbol.declaredFields

      val namedArgs = targetFields.map { targetField =>

        val targetFieldTypeRepr = targetTypeRepr.memberType(targetField)
        val updateFieldOption = updateFields.find(_.name == targetField.name)
        val dynamicNamedArgOption =
          dynamicNamedArgs.find(_.name == targetField.name).map {
            case NamedArg(_, v) => v
          }

        updateFieldOption match {
          case Some(updateField) =>
            val updateFieldTypeRepr = updateTypeRepr.memberType(updateField)
            (
              targetFieldTypeRepr.asType,
              updateFieldTypeRepr.asType
            ) match {
              case ('[t], '[Updated[p]]) =>
                val targetFieldSelectorExpr =
                  Select(target.asTerm, targetField).asExprOf[t]
                val updateFieldSelectorExpr =
                  Select(update.asTerm, updateField).asExprOf[Updated[p]]
                NamedArg(
                  targetField.name,
                  dynamicNamedArgOption.fold(
                    materializeUpdate[t, Updated[p]](using quotes)(
                      targetFieldSelectorExpr,
                      updateFieldSelectorExpr.asExprOf[Updated[p]]
                    ).asTerm
                  )(f =>
                    val f_ =
                      if f.tpe.isFunctionType then
                        '{ (v: p) => ${ f.asExprOf[p => t] }(v) }
                      else '{ (v: p) => ${ f.asExprOf[t] } }
                    '{
                      val ex = $updateFieldSelectorExpr.map(v => $f_(v))
                      ${
                        materializeUpdate[t, Updated[t]](using quotes)(
                          targetFieldSelectorExpr,
                          'ex
                        )
                      }
                    }.asTerm
                  )
                )
              case ('[t], '[p]) =>
                val targetFieldSelectorExpr =
                  Select(target.asTerm, targetField).asExprOf[t]
                val updateFieldSelectorExpr =
                  Select(update.asTerm, updateField).asExprOf[p]
                NamedArg(
                  targetField.name,
                  dynamicNamedArgOption.fold(
                    materializeUpdate[t, p](using quotes)(
                      targetFieldSelectorExpr,
                      updateFieldSelectorExpr.asExprOf[p]
                    ).asTerm
                  )(f =>
                    val f_ =
                      if f.tpe.isFunctionType then
                        '{ (v: p) => ${ f.asExprOf[p => t] }(v) }
                      else '{ (v: p) => ${ f.asExprOf[t] } }
                    '{
                      val ex = $f_($updateFieldSelectorExpr)
                      ${
                        materializeUpdate[t, t](using quotes)(
                          targetFieldSelectorExpr,
                          'ex
                        )
                      }
                    }.asTerm
                  )
                )
              case _ =>
                matchFailed(
                  s"UpdateMacros CASE",
                  s"""
                    ${updateField.name}
                    ${targetTypeRepr.show}
                    ${targetFieldTypeRepr.show}
                    ${updateTypeRepr.show}
                    ${updateFieldTypeRepr.show}
                  """
                )
            }

          case None =>
            dynamicNamedArgOption match
              case Some(arg) => NamedArg(targetField.name, arg)
              case None =>
                val targetFieldSelectorExpr = targetFieldTypeRepr.asType match {
                  case '[t] => Select(target.asTerm, targetField).asExprOf[t]
                  case _ =>
                    matchFailed(
                      "UpdateMacros CASE Missing Field",
                      (targetField, targetFieldTypeRepr.show)
                    )
                }
                NamedArg(targetField.name, targetFieldSelectorExpr.asTerm)
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

    case ('[Option[t]], '[Option[u]]) =>
      '{
        (
          ${ target.asExprOf[Option[t]] },
          ${ update.asExprOf[Option[u]] }
        ) match
          case (_, None) => None
          case (Some(tt), Some(uu)) =>
            Some(${ materializeUpdate[t, u](using quotes)('tt, 'uu) })
          case (None, Some(uu)) =>
            Some(${ fromEvent[u, t](using quotes)('uu) })
      }.asExprOf[T]

    case _ =>
      matchFailed(
        "UpdateMacros WILDCARD",
        (targetTypeRepr.show, updateTypeRepr.show)
      )

def fromEvent[Ev, T](using quotes: Quotes)(
  event: Expr[Ev]
)(using targetType: Type[T], eventType: Type[Ev]): Expr[T] =
  import quotes.reflect.*
  val eventTypeRepr = TypeRepr.of[Ev]
  val targetTypeRepr = TypeRepr.of[T]
  eventTypeRepr.asType match
    case _ if isSumOfProducts(targetTypeRepr) =>
      eventTypeRepr match
        case _ if eventTypeRepr.typeSymbol.flags.is(Flags.Case) =>
          val t = targetTypeRepr.typeSymbol.children
            .find(
              _.name.replace("$", "") == eventTypeRepr.typeSymbol.name
                .replace("$", "")
            )
            .getOrElse(
              throw Exception(
                s"Failed to match case ${eventTypeRepr.typeSymbol.name} " +
                  s"${targetTypeRepr.typeSymbol.name}"
              )
            )

          targetTypeRepr.selectChild(t).asType match
            case '[tk] =>
              fromEvent[Ev, tk](using quotes)(
                event.asExprOf[Ev]
              ).asExprOf[T]
            case _ =>
              matchFailed(
                "Mapping Macros SUM Specific",
                (targetTypeRepr.show, t.name)
              )
        case _ =>
          Match(
            event.asTerm,
            eventTypeRepr.typeSymbol.children.map(eventMemberType =>
              val targetMemberType = targetTypeRepr.typeSymbol.children
                .find(_.name == eventMemberType.name)
                .getOrElse(
                  throw Exception(
                    s"Failed to match case ${eventTypeRepr.termSymbol.name} in sum of products ${targetTypeRepr.typeSymbol.name}"
                  )
                )

              (
                eventTypeRepr.selectChild(eventMemberType).asType,
                targetTypeRepr.selectChild(targetMemberType).asType
              ) match
                case ('[uk], '[tk]) =>
                  CaseDef(
                    Typed(
                      Wildcard(),
                      eventTypeRepr
                        .getType(eventMemberType, _ => Wildcard().tpe)
                    ),
                    None,
                    Block(
                      List(),
                      fromEvent[uk, tk](using quotes)(
                        TypeApply(
                          Select.unique(
                            event.asTerm,
                            "asInstanceOf"
                          ),
                          List(eventTypeRepr.getType(eventMemberType, x => x))
                        ).asExprOf[uk]
                      ).asTerm
                    )
                  )
                case _ =>
                  matchFailed(
                    "Mapping Macros SUM",
                    (eventTypeRepr.show, targetTypeRepr.show)
                  )
            )
          ).asExprOf[T]

    case _
        if targetTypeRepr.typeSymbol.flags.is(
          Flags.Case
        ) && targetTypeRepr.typeSymbol.flags.is(
          Flags.Final
        ) && targetTypeRepr.typeSymbol.declaredFields.isEmpty =>
      targetTypeRepr.ident.asExprOf[T]

    case '[ev] if eventTypeRepr.typeSymbol.flags.is(Flags.Case) =>
      val namedArgs = eventTypeRepr.typeSymbol.declaredFields.map {
        eventField =>
          val eventFieldTypeRepr = eventTypeRepr.memberType(eventField)
          val targetField =
            targetTypeRepr.typeSymbol.declaredFields
              .find(x => x.name == eventField.name)
              .getOrElse(
                throw new Exception(
                  s"Field is missing ${eventField.name} in ${targetTypeRepr.show}"
                )
              )
          val targetFieldTypeRepr = targetTypeRepr.memberType(targetField)
          (eventFieldTypeRepr.asType, targetFieldTypeRepr.asType) match
            case ('[f], '[t]) =>
              val eventSelected = Select(event.asTerm, eventField).asExprOf[f]
              NamedArg(
                eventField.name,
                fromEvent[f, t](using quotes)(
                  eventSelected
                ).asTerm
              )
            case _ =>
              matchFailed(
                "UpdateMacros fromEvent CASE",
                (eventFieldTypeRepr.show, targetFieldTypeRepr.show)
              )
      }
      Select
        .overloaded(
          targetTypeRepr.ident,
          "apply",
          targetTypeRepr.typeParams,
          namedArgs
        )
        .asExprOf[T]

    case '[Updated[u]] =>
      '{
        $event match
          case HasUpdated(ua) => ${ fromEvent[u, T]('{ ua.asInstanceOf[u] }) }
          case NotUpdated =>
            throw new Exception(
              s"fromEvent failed. NotUpdated found"
            )

      }.asExprOf[T]
    case _ =>
      '{ $event.auto.to[T] }
