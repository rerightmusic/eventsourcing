package shared.logging

import zio.logging.*
import zio.logging.backend.SLF4J
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import zio.logging.LogFormat.*
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.classic.filter.LevelFilter
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.spi.ILoggingEvent
import net.logstash.logback.encoder.LogstashEncoder
import ch.qos.logback.core.encoder.Encoder
import ch.qos.logback.core.spi.FilterReply
import shared.config.Env
import zio.Runtime
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.encoder.EncoderBase
import org.slf4j.MDC

object all:

  var loadedType: Option["default" | "console" | "json"] = None
  var logger: Logger = loadConsoleLogger("default", "default")

  def loadLogger[E](
    name: String,
    encoder: EncoderBase[E],
    _loadedType: "default" | "console" | "json"
  ) =
    val lc = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    lc.setName(name)
    val logger_ = LoggerFactory
      .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
      .asInstanceOf[Logger]

    if loadedType.nonEmpty then logger_.detachAndStopAllAppenders

    val hikariLogger_ = LoggerFactory
      .getLogger("com.zaxxer.hikari")
      .asInstanceOf[Logger]

    logger = logger_
    loadedType = Some(_loadedType)

    val stdErrAppender = new ConsoleAppender()
    stdErrAppender.setContext(lc)
    stdErrAppender.setTarget("System.err")
    val threasholdFilter = new ThresholdFilter()
    threasholdFilter.setLevel("WARN")
    threasholdFilter.start
    stdErrAppender.addFilter(
      threasholdFilter.asInstanceOf[Filter[Nothing]]
    )
    encoder.setContext(lc)
    encoder.start
    stdErrAppender.setEncoder(encoder.asInstanceOf[Encoder[Nothing]])
    stdErrAppender.start

    val stdOutAppender = new ConsoleAppender()
    stdOutAppender.setContext(lc)
    stdOutAppender.setTarget("System.out")
    val debugFilter = new LevelFilter()
    debugFilter.setLevel(Level.DEBUG)
    debugFilter.setOnMatch(FilterReply.ACCEPT)
    debugFilter.start
    stdOutAppender.addFilter(
      debugFilter.asInstanceOf[Filter[Nothing]]
    )
    val infoFilter = new LevelFilter()
    infoFilter.setLevel(Level.INFO)
    infoFilter.setOnMatch(FilterReply.ACCEPT)
    infoFilter.start
    stdOutAppender.addFilter(
      infoFilter.asInstanceOf[Filter[Nothing]]
    )
    val traceFilter = new LevelFilter()
    traceFilter.setLevel(Level.TRACE)
    traceFilter.setOnMatch(FilterReply.ACCEPT)
    traceFilter.start
    stdOutAppender.addFilter(
      traceFilter.asInstanceOf[Filter[Nothing]]
    )
    val warnFilter = new LevelFilter()
    warnFilter.setLevel(Level.WARN)
    warnFilter.setOnMatch(FilterReply.DENY)
    warnFilter.start
    stdOutAppender.addFilter(
      warnFilter.asInstanceOf[Filter[Nothing]]
    )
    val errorFilter = new LevelFilter()
    errorFilter.setLevel(Level.ERROR)
    errorFilter.setOnMatch(FilterReply.DENY)
    errorFilter.start
    stdOutAppender.addFilter(
      errorFilter.asInstanceOf[Filter[Nothing]]
    )
    stdOutAppender.setEncoder(encoder.asInstanceOf[Encoder[Nothing]])
    stdOutAppender.start

    List(logger_, hikariLogger_).map(l =>
      l.setLevel(Level.INFO)
      l.detachAppender("console");
      l.addAppender(
        stdErrAppender
          .asInstanceOf[ch.qos.logback.core.Appender[ILoggingEvent]]
      )
      l.addAppender(
        stdOutAppender
          .asInstanceOf[ch.qos.logback.core.Appender[ILoggingEvent]]
      )
    )
    logger_

  def loadConsoleLogger(name: String, _loadedType: "default" | "console") =
    val encoder = new PatternLayoutEncoder()
    encoder.setPattern(
      "%contextName %d{yyyy-MM-dd'T'HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
    )
    loadLogger(name, encoder, _loadedType)

  def loadJsonLogger(name: String) =
    val encoder = new LogstashEncoder()
    encoder.setCustomFields(s"""{"name": "${name}"}""")
    loadLogger(name, encoder, "json")

  def defaultLogger(name: String, json: Boolean = false) =
    if !json then
      if loadedType.fold(true)(_ != "console") then
        loadConsoleLogger(name, "console")
      zio.logging.console(
        LogFormat
          .text(name)
          .color(LogColor.GREEN) |-| LogFormat.colored
      )
    else
      if loadedType.fold(true)(_ != "json") then loadJsonLogger(name)
      Runtime.removeDefaultLoggers >>> SLF4J.slf4j
