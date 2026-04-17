package net.ghoula.melian.extraction

import net.ghoula.eru.Eru
import net.ghoula.melian.{ExtractionError, ExtractionSource}

import java.util.UUID

/** Shared primitive parsing used by FromPathSegment and FromQueryParam.
  *
  * Each function returns Eru[ExtractionError, A] with structured error metadata.
  */
object ParsePrimitive {

  def int(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, Int] =
    raw.toIntOption match {
      case Some(n) => Eru.succeed(n)
      case None => Eru.fail(error(source, field, "invalid integer", "integer", raw))
    }

  def long(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, Long] =
    raw.toLongOption match {
      case Some(n) => Eru.succeed(n)
      case None => Eru.fail(error(source, field, "invalid long", "long", raw))
    }

  def double(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, Double] =
    raw.toDoubleOption match {
      case Some(d) => Eru.succeed(d)
      case None => Eru.fail(error(source, field, "invalid double", "number", raw))
    }

  def boolean(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, Boolean] =
    raw.toBooleanOption match {
      case Some(b) => Eru.succeed(b)
      case None => Eru.fail(error(source, field, "invalid boolean", "true|false", raw))
    }

  def uuid(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, UUID] =
    scala.util.Try(UUID.fromString(raw)).fold(
      _ => Eru.fail(error(source, field, "invalid UUID format", "UUID", raw)),
      u => Eru.succeed(u)
    )

  private def error(
    source: ExtractionSource,
    field: String,
    message: String,
    expected: String,
    actual: String
  ): ExtractionError =
    ExtractionError(source, field, message, Some(expected), Some(actual))
}
