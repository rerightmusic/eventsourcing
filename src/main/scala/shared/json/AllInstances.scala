package shared.json

import shared.newtypes.NewtypeWrapped
import zio.json.*
import zio.json.internal.RetractReader
import zio.NonEmptyChunk
import zio.json.internal.*
import zio.json.ast.*
import shared.newtypes.NewExtractor
import cats.data.{NonEmptyMap, NonEmptyList}
import cats.kernel.Order
import cats.syntax.all.*

trait AllInstances extends NewtypeInstances:
  given nemCodec[K, V](using
    c: JsonCodec[Map[K, V]],
    o: Order[K]
  ): JsonCodec[NonEmptyMap[K, V]] =
    c.xmapOrFail(
      m =>
        m.toList.toNel
          .toRight("Failed to get NonEmptyMap from Map")
          .map(_.toNem),
      m => m.toSortedMap.toMap
    )

  given enumToFieldDecoder[A](using c: JsonEnumCodec[A]): JsonFieldDecoder[A] =
    new JsonFieldDecoder[A]:
      def unsafeDecodeField(trace: List[JsonError], in: String): A =
        in.fromJson.fold(
          e => throw new Exception(s"${e}, ${trace.mkString(", ")} "),
          v => v
        )

  given enumToFieldEncoder[A](using c: JsonEnumCodec[A]): JsonFieldEncoder[A] =
    new JsonFieldEncoder[A]:
      def unsafeEncodeField(a: A): String =
        a.toJson

  given JsonCodec[Unit] = JsonCodec[Unit](
    JsonEncoder[Json].contramap(_ => Json.Obj()),
    JsonDecoder[Json].mapOrFail {
      case Json.Obj(f) if f.isEmpty => Right(())
      case v => Left(s"Failed to decode Unit from ${v.toJson}")
    }
  )

  given zioOptionCodec[A](using e: JsonCodec[A]): JsonCodec[Option[A]] =
    JsonCodec[Option[A]](
      new JsonEncoder[Option[A]]:
        val enc = JsonEncoder.option
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
        val dec = JsonDecoder.option
        override def unsafeDecodeMissing(trace: List[JsonError]): Option[A] =
          None

        def unsafeDecode(
          trace: List[JsonError],
          in: RetractReader
        ): Option[A] = dec.unsafeDecode(trace, in)
    )

  given zioNelCodec[A](using
    e: JsonCodec[A]
  ): JsonCodec[NonEmptyList[A]] = JsonCodec[NonEmptyList[A]](
    JsonEncoder.nonEmptyChunk[A].contramap { case NonEmptyList(h, t) =>
      NonEmptyChunk.fromIterable(h, t)
    },
    JsonDecoder
      .nonEmptyChunk[A]
      .map(c => NonEmptyList(c.head, c.tail.toList))
  )
