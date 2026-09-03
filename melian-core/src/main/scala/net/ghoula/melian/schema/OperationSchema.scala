package net.ghoula.melian.schema

import scala.language.strictEquality

/** Compile-time metadata describing a single route's API contract.
  *
  * Generated entirely by the macro, with no runtime reflection. The openapi module consumes these
  * to produce an OpenAPI 3.1 specification.
  */
final case class OperationSchema(
  pathTemplate: String,
  method: String,
  parameters: Vector[ParameterSchema],
  requestBody: Option[TypeSchema],
  requestMediaTypes: Vector[String] = Vector.empty,
  responseStatus: Int,
  responseBody: Option[TypeSchema],
  responseHeaders: Vector[ResponseHeaderSchema] = Vector.empty,
  isEventStream: Boolean,
  isWebSocket: Boolean = false,
  operationId: Option[String] = None,
  responseMediaTypes: Vector[String] = Vector.empty,
  summary: Option[String] = None,
  description: Option[String] = None,
  tags: Vector[String] = Vector.empty
) derives CanEqual

final case class ParameterSchema(
  name: String,
  in: ParameterLocation,
  schema: TypeSchema,
  required: Boolean
) derives CanEqual

final case class ResponseHeaderSchema(
  name: String,
  description: Option[String] = None,
  required: Boolean = false,
  schema: TypeSchema = TypeSchema.StringSchema
) derives CanEqual

enum ParameterLocation derives CanEqual {
  case Path
  case Query
  case Header
}
