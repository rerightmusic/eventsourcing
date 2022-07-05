package shared.data.automapper

import shared.macros.all.*
import scala.quoted._
import scala.util.control.NonFatal

def materializePatcher[T, P](using
  quotes: Quotes
)(target: Expr[T], patch: Expr[P])(using
  targetType: Type[T],
  patchType: Type[P]
): Expr[T] =
  import quotes.reflect._

  val targetTypeRepr = TypeRepr.of[T]
  val patchTypeRepr = TypeRepr.of[P]

  (targetTypeRepr.asType, patchTypeRepr.asType) match
    case _ if targetTypeRepr == patchTypeRepr =>
      patch.asExprOf[T]

    case ('[Map[tk, tv]], '[Map[pk, pv]]) =>
      val targetExpr: Expr[Map[tk, tv]] = target.asExprOf[Map[tk, tv]]
      val patchExpr: Expr[Map[tk, pv]] = patch.asExprOf[Map[tk, pv]]
      '{
        $targetExpr.view.map { case (key: tk, targetValue: tv) =>
          $patchExpr.get(key) match {
            case Some(patchValue) =>
              (
                key,
                ${
                  materializePatcher[tv, pv](using quotes)(
                    'targetValue,
                    'patchValue
                  )
                }
              )
            case None => (key, targetValue)
          }
        }.toMap
      }.asExprOf[T]

    case ('[Iterable[t]], '[Iterable[p]]) =>
      val targetExpr: Expr[Iterable[t]] = target.asExprOf[Iterable[t]]
      val patchExpr: Expr[Iterable[p]] = patch.asExprOf[Iterable[p]]
      '{
        $targetExpr.zip($patchExpr).map {
          case (targetValue: t, patchValue: p) =>
            ${
              materializePatcher[t, p](using quotes)('targetValue, 'patchValue)
            }
        }
      }.asExprOf[T]

    case ('[Option[t]], '[Option[p]]) =>
      val targetExpr: Expr[Option[t]] = target.asExprOf[Option[t]]
      val patchExpr: Expr[Option[p]] = patch.asExprOf[Option[p]]
      '{
        $targetExpr.map((targetValue: t) =>
          $patchExpr match {
            case Some(patchValue) =>
              ${
                materializePatcher[t, p](using quotes)(
                  'targetValue,
                  'patchValue
                )
              }
            case None => targetValue
          }
        )
      }.asExprOf[T]

    case ('[t], '[p]) if patchTypeRepr.typeSymbol.flags.is(Flags.Case) =>
      val targetFields = targetTypeRepr.typeSymbol.declaredFields
      val patchFields = patchTypeRepr.typeSymbol.declaredFields

      val namedArgs = targetFields.map { targetField =>

        val targetFieldTypeRepr = targetTypeRepr.memberType(targetField)
        val patchFieldOption = patchFields.find(_.name == targetField.name)

        patchFieldOption match {
          case Some(patchField) =>
            val patchFieldTypeRepr = patchTypeRepr.memberType(patchField)
            (targetFieldTypeRepr.asType, patchFieldTypeRepr.asType) match {
              case ('[t], '[p]) =>
                val targetFieldSelectorExpr =
                  Select(target.asTerm, targetField).asExprOf[t]
                val patchFieldSelectorExpr =
                  Select(patch.asTerm, patchField).asExprOf[p]
                NamedArg(
                  patchField.name,
                  materializePatcher[t, p](using quotes)(
                    targetFieldSelectorExpr,
                    patchFieldSelectorExpr
                  ).asTerm
                )

              case t => matchFailed("Patcher Macros CASE", t)
            }
          case None =>
            val targetFieldSelectorExpr = targetFieldTypeRepr.asType match {
              case '[t] => Select(target.asTerm, targetField).asExprOf[t]
              case t    => matchFailed("Patcher Macros CASE Missing", t)
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

    case _ =>
      patch.asExprOf[T]
