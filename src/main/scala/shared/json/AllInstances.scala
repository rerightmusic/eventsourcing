package shared.json

import shared.newtypes.NewtypeWrapped
import zio.json.*
import zio.json.internal.RetractReader
import zio.NonEmptyChunk
import zio.json.internal.Write
import zio.json.ast.Json
import shared.newtypes.NewExtractor
import cats.data.{NonEmptyMap, NonEmptyList, NonEmptySet}
import cats.kernel.Order
import cats.syntax.all.*

trait AllInstances extends NewtypeInstances:
  implicit def ec2c[A](implicit c: JsonEnumCodec[A]): JsonCodec[A] =
    JsonCodec(c.enc, c.dec)
  implicit def jc2e[A](implicit c: JsonCodec[A]): JsonDecoder[A] =
    c.decoder
  implicit def jc2d[A](implicit c: JsonCodec[A]): JsonEncoder[A] =
    c.encoder

  implicit def jsonCodec: JsonCodec[Json] =
    JsonCodec(Json.encoder, Json.decoder)

  given nesCodec[V](using
    e: JsonCodec[V],
    o: Order[V]
  ): JsonCodec[NonEmptySet[V]] =
    JsonCodec(
      nelCodec.transformOrFail(
        m => Right(m.toNes),
        m => m.toNonEmptyList
      )
    )

  given nemCodec[K, V](using
    c: JsonCodec[Map[K, V]],
    o: Order[K]
  ): JsonCodec[NonEmptyMap[K, V]] =
    JsonCodec(
      c.transformOrFail(
        m =>
          m.toList.toNel
            .toRight("Failed to get NonEmptyMap from Map")
            .map(_.toNem),
        m => m.toSortedMap.toMap
      )
    )

  given JsonCodec[Unit] = JsonCodec[Unit](
    JsonCodec(
      JsonEncoder[Json].contramap(_ => Json.Obj()),
      JsonDecoder[Json].mapOrFail {
        case Json.Obj(f) if f.isEmpty => Right(())
        case v => Left(s"Failed to decode Unit from ${v.toJson}")
      }
    )
  )

  given zioOptionCodec[A](using e: JsonCodec[A]): JsonCodec[Option[A]] =
    JsonCodec[Option[A]](
      JsonCodec(
        new JsonEncoder[Option[A]]:
          val enc = JsonEncoder.option[A]
          override def isNothing(a: Option[A]): Boolean =
            false
          override def unsafeEncode(
            a: Option[A],
            indent: Option[Int],
            out: Write
          ): Unit =
            enc.unsafeEncode(a, indent, out)

          override def toJsonAST(a: Option[A]): Either[String, Json] =
            enc.toJsonAST(a)
        ,
        new JsonDecoder[Option[A]]:
          val dec = JsonDecoder.option[A]
          override def unsafeDecodeMissing(trace: List[JsonError]): Option[A] =
            None

          def unsafeDecode(
            trace: List[JsonError],
            in: RetractReader
          ): Option[A] = dec.unsafeDecode(trace, in)
      )
    )

  given nelCodec[A](using
    e: JsonCodec[A]
  ): JsonCodec[NonEmptyList[A]] = JsonCodec[NonEmptyList[A]](
    JsonCodec[NonEmptyList[A]](
      JsonEncoder.nonEmptyChunk[A].contramap { case NonEmptyList(h, t) =>
        NonEmptyChunk.fromIterable(h, t)
      },
      JsonDecoder
        .nonEmptyChunk[A]
        .map(c => NonEmptyList(c.head, c.tail.toList))
    )
  )
