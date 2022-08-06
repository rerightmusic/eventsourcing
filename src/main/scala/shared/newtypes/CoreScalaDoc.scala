/*
 * Copyright (c) 2021 the Newtypes contributors.
 * See the project homepage at: https://newtypes.monix.io/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shared.newtypes

/** @define newtypeBaseDescription
  *   Base class for defining newtypes that have no type parameters.
  *
  * This class does not define any "builder", as you're expected to provide one
  * yourself.
  *
  * Usage: {{{ type EmailAddress = EmailAddress.Type
  *
  * object EmailAddress extends Newtype[String] { def apply(value: String):
  * Option[EmailAddress] = if (value.contains("@")) Some(unsafeBuild(value))
  * else None } }}}
  *
  * @see
  *   [[NewtypeWrapped]]
  *
  * @define newtypeKDescription
  *   For building newtypes over types that have an invariant type parameter
  *   (higher-kinded types).
  *
  * Example: {{{ // Only needed for type-class derivation import cats._ import
  * cats.implicits._
  *
  * type Nel[A] = Nel.Type[A]
  *
  * object Nel extends NewtypeK[List] { def apply[A](head: A, tail: A*): Nel[A]
  * = unsafeBuild(head :: tail.toList)
  *
  * def unapply[F[_], A](list: F[A])( implicit ev: F[A] =:= Nel[A] ): Some[(A,
  * List[A])] = { val l = value(list) Some((l.head, l.tail)) }
  *
  * implicit def eq[A: Eq]: Eq[Nel[A]] = derive
  *
  * implicit val traverse: Traverse[Nel] = deriveK
  *
  * implicit val monad: Monad[Nel] = deriveK } }}}
  *
  * NOTE: the type-parameter is invariant. See [[NewtypeCovariantK]].
  *
  * @define newtypeCovariantKDescription
  *   For building newtypes over types that have a covariant type parameter
  *   (higher-kinded types).
  *
  * Example: {{{ // Only needed for type-class derivation import cats._ import
  * cats.implicits._
  *
  * type NonEmptyList[A] = NonEmptyList.Type[A]
  *
  * object NonEmptyList extends NewtypeCovariantK[List] { def apply[A](head: A,
  * tail: A*): NonEmptyList[A] = unsafeBuild(head :: tail.toList)
  *
  * def unapply[F[_], A](list: F[A])( implicit ev: F[A] =:= NonEmptyList[A] ):
  * Some[(A, List[A])] = { val l = value(list) Some((l.head, l.tail)) }
  *
  * implicit def eq[A: Eq]: Eq[NonEmptyList[A]] = derive
  *
  * implicit val traverse: Traverse[NonEmptyList] = deriveK
  *
  * implicit val monad: Monad[NonEmptyList] = deriveK } }}}
  *
  * NOTE: the type-parameter is covariant. Also see [[NewtypeK]] for working
  * with invariance.
  *
  * @define newsubtypeBaseDescription
  *   Base class for defining newsubtypes that have no type parameters.
  *
  * This class does not define any "builder", as you're expected to provide one
  * yourself.
  *
  * Usage: {{{ type PositiveInt = PositiveInt.Type
  *
  * object PositiveInt extends Newsubtype[Int] { def apply(value: Int):
  * Option[PositiveInt] = if (value > 0) Some(unsafeBuild(value)) else None }
  * }}}
  *
  * @see
  *   [[NewsubtypeWrapped]]
  *
  * @define newsubtypeKDescription
  *   For building newsubtypes over types that have an invariant type parameter
  *   (higher-kinded types).
  *
  * Example: {{{ // Only needed for type-class derivation import cats._ import
  * cats.implicits._
  *
  * type Nelsub[A] = Nelsub.Type[A]
  *
  * object Nelsub extends NewsubtypeK[List] { def apply[A](head: A, tail: A*):
  * Nelsub[A] = unsafeBuild(head :: tail.toList)
  *
  * def unapply[F[_], A](list: F[A])( implicit ev: F[A] =:= Nelsub[A] ):
  * Some[(A, List[A])] = { val l = value(list) Some((l.head, l.tail)) }
  *
  * implicit def eq[A: Eq]: Eq[Nelsub[A]] = derive
  *
  * implicit val traverse: Traverse[Nelsub] = deriveK
  *
  * implicit val monad: Monad[Nelsub] = deriveK } }}}
  *
  * NOTE: the type-parameter is invariant. See [[NewsubtypeCovariantK]].
  *
  * @define newsubtypeCovariantKDescription
  *   For building newsubtypes over types that have a covariant type parameter
  *   (higher-kinded types).
  *
  * Example: {{{ // Only needed for type-class derivation import cats._ import
  * cats.implicits._
  *
  * type NonEmptyListSub[A] = NonEmptyListSub.Type[A]
  *
  * object NonEmptyListSub extends NewsubtypeCovariantK[List] { def
  * apply[A](head: A, tail: A*): NonEmptyListSub[A] = unsafeBuild(head ::
  * tail.toList)
  *
  * def unapply[F[_], A](list: F[A])( implicit ev: F[A] =:= NonEmptyListSub[A]
  * ): Some[(A, List[A])] = { val l = value(list) Some((l.head, l.tail)) }
  *
  * implicit def eq[A: Eq]: Eq[NonEmptyListSub[A]] = derive
  *
  * implicit val traverse: Traverse[NonEmptyListSub] = deriveK
  *
  * implicit val monad: Monad[NonEmptyListSub] = deriveK } }}}
  *
  * NOTE: the type-parameter is covariant. Also see [[NewsubtypeK]] for working
  * with invariance.
  */
private[newtypes] trait CoreScalaDoc
