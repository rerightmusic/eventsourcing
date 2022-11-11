package eventsourcing.infra

import zio.json.*
import zio.json.ast.*
import eventsourcing.domain.update.types.*
import zio.json.JsonEncoder
import zio.json.internal.*

trait JsonInstances:
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
            case HasUpdated(v) => e.encoder.toJsonAST(v)
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
