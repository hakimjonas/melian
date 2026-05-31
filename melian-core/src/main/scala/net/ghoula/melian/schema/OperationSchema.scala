package net.ghoula.melian.schema

import scala.language.strictEquality

/** Compile-time metadata describing a single route's API contract.
  *
  * Generated entirely by the macro — no runtime reflection. The openapi module consumes these to
  * produce an OpenAPI 3.1 specification.
  */
final case class OperationSchema(
  pathTemplate: String,
  method: String,
  parameters: Vector[ParameterSchema],
  requestBody: Option[TypeSchema],
  responseStatus: Int,
  responseBody: Option[TypeSchema],
  isEventStream: Boolean
) derives CanEqual

final case class ParameterSchema(
  name: String,
  in: ParameterLocation,
  schema: TypeSchema,
  required: Boolean
) derives CanEqual

enum ParameterLocation derives CanEqual {
  case Path
  case Query
  case Header
}
