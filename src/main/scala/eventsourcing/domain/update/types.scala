package eventsourcing.domain.update

import cats.data.NonEmptyList
import cats.Applicative
import cats.syntax.all.*
import scala.language.dynamics

object types:
  // TODO implement type classes for map,fold etc. properly
  sealed trait Updated[+A]:
    def traverse[F[_], B](f: A => F[B])(using
      app: Applicative[F]
    ): F[Updated[B]]
    def fold[B](ifNotUpdated: B)(f: A => B): B
    def map[B](f: A => B): Updated[B]
    def toOption: Option[A]
  object Updated:
    def fromOption[A](v: Option[A]): Updated[A] = v match
      case None    => NotUpdated
      case Some(v) => HasUpdated(v)

  case object NotUpdated extends Updated[Nothing]:
    def traverse[F[_], B](f: Nothing => F[B])(using
      app: Applicative[F]
    ): F[Updated[B]] =
      app.pure(NotUpdated)
    def map[B](f: Nothing => B): Updated[B] = NotUpdated
    def fold[B](ifNotUpdated: B)(f: Nothing => B): B = ifNotUpdated
    def toOption = None
  case class HasUpdated[+A](value: A) extends Updated[A]:
    def traverse[F[_], B](f: A => F[B])(using
      app: Applicative[F]
    ): F[Updated[B]] =
      f(value).map(HasUpdated(_))

    def map[B](f: A => B): Updated[B] = HasUpdated(f(value))
    def fold[B](ifNotUpdated: B)(f: A => B): B = f(value)
    def toOption = Some(value)

  type UpdatedOpt[+A] = Updated[Option[A]]
  type UpdatedList[+A] = Updated[List[A]]
  type UpdatedNel[+A] = Updated[NonEmptyList[A]]

  extension [F](
    from: F
  )
    inline def update[U](update: U) = ${
      materializeNonDynamicUpdate[F, U]('from, 'update)
    }

    inline def dynUpdate[U](update: U) = new DynamicUpdate(from, update)
  class DynamicUpdate[F, U](val from: F, val update: U) extends Dynamic:
    inline def applyDynamicNamed(name: String)(
      inline args: (String, Any)*
    ): F = ${ materializeDynamicUpdate[F, U]('from, 'update)('args) }

  extension [F](v: F) inline def updated[U](u: U) = DynamicUpdated(v, u)
  class DynamicUpdated[F, U](from: F, update: U) extends Dynamic:
    inline def to[UE] = ${
      materializeNonDynamicUpdated[F, U, UE]('from, 'update)
    }
    inline def applyDynamicNamed[UE](name: String)(
      inline args: (String, Any)*
    ): UE = ${ materializeDynamicUpdated[F, U, UE]('from, 'update)('args) }
