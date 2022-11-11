package shared.time

import java.time.{OffsetDateTime, ZoneOffset}
import zio.{ZIO, UIO}
import java.time.format.DateTimeFormatter

object all:
  type OffsetDateTime = java.time.OffsetDateTime

  def now: UIO[OffsetDateTime] =
    ZIO.succeed(OffsetDateTime.now(ZoneOffset.UTC))

  def nowMillis = now.map(n => n.toInstant.toEpochMilli)

  def nowSync: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

  type LocalDate = java.time.LocalDate
  object LocalDate:
    def parse(str: String) =
      java.time.LocalDate.parse(str)
    def format(date: LocalDate) = date.format(DateTimeFormatter.ISO_DATE)
    def nowSync = java.time.LocalDate.now

  def differenceString(start: OffsetDateTime, end: OffsetDateTime) =
    differenceToString(differenceMillis(start, end))

  def differenceToString(diff: Long) =
    val diff_ = java.time.Duration.ofMillis(diff)
    val hours = diff_.toHours
    val diffHours = diff_.minusHours(hours)
    val mins = diffHours.toMinutes
    val diffMins = diffHours.minusMinutes(mins)
    val secs = diffMins.getSeconds
    val diffSecs = diffMins.minusSeconds(secs)
    val millis = diffSecs.toMillis
    String.format(
      "%02d:%02d:%02d%02d",
      hours,
      mins,
      secs,
      millis
    )

  def differenceMillis(start: OffsetDateTime, end: OffsetDateTime) =
    java.time.Duration
      .between(start, end)
      .toMillis
