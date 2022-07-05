package shared.logging

import org.slf4j.LoggerFactory
import zio.logging.*
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger

object all:
  val log = zio.logging.log
  type Logging = zio.logging.Logging

  object Logging:
    def console(name: String) =
      val root = LoggerFactory
        .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        .asInstanceOf[Logger]
      root.setLevel(Level.INFO)
      zio.logging.Logging
        .console(
          LogLevel.Info,
          format = LogFormat.ColoredLogFormat()
        ) >+> Logging
        .withRootLoggerName(name)
    export zio.logging.Logging.*
