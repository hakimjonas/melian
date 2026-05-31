package net.ghoula.melian.schema

import scala.annotation.StaticAnnotation

/** Describes a field or type for OpenAPI documentation. Read at compile time by the schema macro.
  */
final class description(val value: String) extends StaticAnnotation

/** Provides an example value for OpenAPI documentation. Read at compile time by the schema macro.
  */
final class example(val value: String) extends StaticAnnotation
