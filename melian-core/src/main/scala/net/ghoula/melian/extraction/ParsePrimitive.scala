package net.ghoula.melian.extraction

import java.util.UUID

import net.ghoula.eru.Eru
import net.ghoula.melian.{ExtractionError, ExtractionSource}

/** Shared primitive parsing used by FromPathSegment and FromQueryParam.
  *
  * Each function returns Eru[ExtractionError, A] with structured error metadata.
  */
object ParsePrimitive {

  def int(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, Int] =
    Eru.fromOption(raw.toIntOption, error(source, field, "invalid integer", "integer", raw))

  def long(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, Long] =
    Eru.fromOption(raw.toLongOption, error(source, field, "invalid long", "long", raw))

  def double(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, Double] =
    Eru.fromOption(raw.toDoubleOption, error(source, field, "invalid double", "number", raw))

  def boolean(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, Boolean] =
    Eru.fromOption(raw.toBooleanOption, error(source, field, "invalid boolean", "true|false", raw))

  def uuid(raw: String, source: ExtractionSource, field: String): Eru[ExtractionError, UUID] =
    Eru
      .fromTry(scala.util.Try(UUID.fromString(raw)))
      .mapError(_ => error(source, field, "invalid UUID format", "UUID", raw))

  private def error(
    source: ExtractionSource,
    field: String,
    message: String,
    expected: String,
    actual: String
  ): ExtractionError =
    ExtractionError(source, field, message, Some(expected), Some(actual))
}
