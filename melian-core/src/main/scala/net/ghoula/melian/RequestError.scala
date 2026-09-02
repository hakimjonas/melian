package net.ghoula.melian

import net.ghoula.eru.http.MediaType

/** Unified error type for the inbound Girdle pipeline (RFC 9457 Problem Details).
  *
  * All pipeline errors coexist in a single response. Extraction errors from path, query, and header
  * are collected in parallel. With resilient parsing, body errors accumulate across Rumil, Sarati,
  * and Valar stages.
  */
enum RequestError derives CanEqual {
  case ExtractionFailed(errors: Vector[ExtractionError])
  case ParseFailed(errors: List[String])
  case DecodeFailed(errors: List[String])
  case ValidationFailed(errors: Vector[FieldError])
  case BodyMissing
  case UnsupportedMediaType(expected: List[MediaType], actual: Option[MediaType])
}

/** Where an extraction error originated. */
enum ExtractionSource derives CanEqual {
  case Path
  case Query
  case Header
}

/** A structured extraction error with source, field, and diagnostic metadata. */
final case class ExtractionError(
  source: ExtractionSource,
  field: String,
  message: String,
  expected: Option[String] = None,
  actual: Option[String] = None
) derives CanEqual

/** A field-level validation or constraint violation from Valar. */
final case class FieldError(
  path: String,
  message: String,
  code: Option[String] = None
) derives CanEqual
