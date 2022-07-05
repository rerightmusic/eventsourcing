package eventsourcing.infra

import shared.json.all.{given, *}
import eventsourcing.domain.types as D

case class AggregateViewStatus(
  sequenceIds: Map[String, D.SequenceId],
  longestDuration: Option[Long] = None,
  longestEventsSize: Option[Int] = None,
  catchupDuration: Option[Long],
  catchupEventsSize: Option[Int],
  syncDuration: Option[Long],
  syncEventsSize: Option[Int],
  error: Option[String]
) derives JsonCodec

def camelToUnderscores(name: String) = "[A-Z\\d]".r.replaceAllIn(
  name.toLowerCase,
  { m =>
    "_" + m.group(0).toLowerCase()
  }
)
