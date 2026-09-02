package net.ghoula.melian.openapi

import net.ghoula.melian.schema.*

/** Deduplicates ObjectSchema types from route metadata into a components registry.
  *
  * Walks all OperationSchema instances, collects named object schemas, and deduplicates by name.
  * This is the only runtime work; the type information was fully resolved at compile time.
  */
final case class ComponentRegistry(schemas: Map[String, TypeSchema.ObjectSchema])

object ComponentRegistry {

  val empty: ComponentRegistry = ComponentRegistry(Map.empty)

  def from(operations: Vector[OperationSchema]): ComponentRegistry = {
    val collected = scala.collection.mutable.Map[String, TypeSchema.ObjectSchema]()

    def collect(schema: TypeSchema): Unit = schema match {
      case obj: TypeSchema.ObjectSchema =>
        collected.put(obj.name, obj)
        obj.fields.foreach(f => collect(f.schema))
      case TypeSchema.ArraySchema(items) => collect(items)
      case TypeSchema.OptionalSchema(inner) => collect(inner)
      case _ => ()
    }

    operations.foreach { op =>
      op.parameters.foreach(p => collect(p.schema))
      op.requestBody.foreach(collect)
      op.responseBody.foreach(collect)
    }

    ComponentRegistry(collected.toMap)
  }
}
