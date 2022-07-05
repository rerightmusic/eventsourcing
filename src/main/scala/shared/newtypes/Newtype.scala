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

import scala.deriving.Mirror
import scala.reflect.ClassTag

/** $newtypeBaseDescription */
abstract class Newtype[Src] extends CoreScalaDoc {
  opaque type Type = Src

  extension (self: Type) {
    inline final def value: Src = self
  }

  protected inline final def unsafeBuild(value: Src): Type =
    value

  protected inline final def derive[F[_]](implicit ev: F[Src]): F[Type] =
    ev

  protected def typeName: String =
    getClass().getSimpleName().replaceFirst("[$]$", "")

  implicit val codec: NewExtractor.Aux[Type, Src] =
    new NewExtractor[Type] {
      type Source = Src
      def from(value: Type) =
        Newtype.this.value(value)
      def to(value: Src) =
        unsafeBuild(value)
    }

  given typeInfo: TypeInfo[Type] =
    val raw = TypeInfo.forClasses(ClassTag(getClass))
    TypeInfo(
      typeName = raw.typeName.replaceFirst("[$]$", ""),
      typeLabel = raw.typeLabel.replaceFirst("[$](\\d+[$])?$", ""),
      packageName = raw.packageName,
      typeParams = Nil
    )

  given (using ex: NewExtractor.Aux[Type, Src]): NewExtractor.Aux[Src, Type] =
    new NewExtractor[Src] {
      type Source = Type
      def from(value: Src) =
        ex.to(value)
      def to(value: Type) =
        ex.from(value)
    }
}
