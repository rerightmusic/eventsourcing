package shared.json

import shared.newtypes.NewtypeWrapped
import eventsourcing.all.*
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

  given zioUpdatedCodec[A](using e: JsonCodec[A]): JsonCodec[Updated[A]] =
    JsonCodec[Updated[A]](
      new JsonEncoder[Updated[A]]:
        override def isNothing(a: Updated[A]): Boolean =
          a match
            case NotUpdated =>
              true
            case HasUpdated(v) => false

        override def unsafeEncode(
          a: Updated[A],
          indent: Option[Int],
          out: Write
        ): Unit =
          a match
            case NotUpdated => out.write("null")
            case HasUpdated(v) =>
              e.encoder.unsafeEncode(v, indent, out)

        override def toJsonAST(a: Updated[A]): Either[String, Json] =
          a match
            case NotUpdated    => Right(Json.Null)
            case HasUpdated(v) => e.toJsonAST(v)
      ,
      new JsonDecoder[Updated[A]]:
        override def unsafeDecodeMissing(trace: List[JsonError]): Updated[A] =
          NotUpdated

        def unsafeDecode(
          trace: List[JsonError],
          in: RetractReader
        ): Updated[A] =
          HasUpdated(e.decoder.unsafeDecode(trace, in))
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
