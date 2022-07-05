package shared.data.extensions

import zio.{ZIO, IO, Task}
import cats.syntax.all.*

object Options:
  extension [A](x: Option[A])
    def getOrLeft(message: String): Either[Throwable, A] =
      x match
        case None        => Left(new Exception(message))
        case Some(value) => Right(value)

    def getOrFail(message: String): Task[A] =
      ZIO.fromEither(x.getOrLeft(message))

    def getOrFail[E](err: E): IO[E, A] =
      x match
        case None        => ZIO.fail(err)
        case Some(value) => ZIO.succeed(value)

  extension [A](x: Option[List[A]])
    def toNel = x.toList.flatten.toNel
    def map[B](f: A => B) = x.map(_.map(f))
