package net.ghoula.melian.schema

import scala.language.strictEquality

/** Compile-time type descriptors for OpenAPI schema generation.
  *
  * The macro produces these from TypeRepr/Mirror at compile time. The runtime only deduplicates
  * into a components registry and serializes to JSON, with zero reflection.
  */
enum TypeSchema derives CanEqual {
  case StringSchema
  case IntSchema
  case LongSchema
  case DoubleSchema
  case BooleanSchema
  case UuidSchema
  case ObjectSchema(name: String, fields: Vector[FieldSchema], description: Option[String] = None)
  case ArraySchema(items: TypeSchema)
  case OptionalSchema(inner: TypeSchema)
  case EnumSchema(name: String, values: Vector[String])
  case Ref(name: String)
  case EventStreamSchema
  case UnitSchema
}

final case class FieldSchema(
  name: String,
  schema: TypeSchema,
  required: Boolean,
  description: Option[String] = None,
  example: Option[String] = None
) derives CanEqual
