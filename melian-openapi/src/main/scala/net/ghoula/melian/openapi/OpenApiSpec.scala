package net.ghoula.melian.openapi

import net.ghoula.melian.schema.*
import net.ghoula.sarati.ast.json.JsonValue

/** Generates an OpenAPI 3.1 specification from compile-time route metadata.
  *
  * All type information is resolved at compile time by the macro. This module's only runtime work
  * is deduplicating schemas into the components registry and serializing to JSON.
  */
object OpenApiSpec {

  final case class Info(
    title: String,
    version: String,
    description: Option[String] = None,
    servers: List[String] = Nil
  )

  def generate(info: Info, operations: Vector[OperationSchema]): JsonValue = {
    val components = ComponentRegistry.from(operations)
    val paths = buildPaths(operations, components)

    JsonValue.Object(
      Map(
        "openapi" -> JsonValue.Str("3.1.0"),
        "info" -> buildInfo(info),
        "paths" -> paths,
        "components" -> buildComponents(components)
      ) ++ buildServers(info.servers).map(s => "servers" -> s)
    )
  }

  private def buildServers(servers: List[String]): Option[JsonValue] =
    if servers.isEmpty then None
    else Some(JsonValue.Array(servers.map(u => JsonValue.Object(Map("url" -> JsonValue.Str(u))))))

  private def buildInfo(info: Info): JsonValue = {
    JsonValue.Object(
      Map(
        "title" -> JsonValue.Str(info.title),
        "version" -> JsonValue.Str(info.version)
      ) ++ info.description.map(d => "description" -> JsonValue.Str(d))
    )
  }

  private def buildPaths(operations: Vector[OperationSchema], components: ComponentRegistry): JsonValue = {
    val grouped = operations.groupBy(_.pathTemplate)
    JsonValue.Object(grouped.map { case (path, ops) =>
      val openapiPath = path.replaceAll(":([^/]+)", "{$1}")
      openapiPath -> JsonValue.Object(ops.map { op =>
        op.method.toLowerCase -> buildOperation(op, components)
      }.toMap)
    })
  }

  private def buildOperation(op: OperationSchema, components: ComponentRegistry): JsonValue = {
    val fields = scala.collection.mutable.Map[String, JsonValue]()

    op.summary.foreach(s => fields("summary") = JsonValue.Str(s))
    op.description.foreach(d => fields("description") = JsonValue.Str(d))
    if op.tags.nonEmpty then fields("tags") = JsonValue.Array(op.tags.toList.map(JsonValue.Str(_)))

    if op.parameters.nonEmpty then {
      fields("parameters") = JsonValue.Array(op.parameters.toList.map(buildParameter))
    }

    op.requestBody.foreach { schema =>
      fields("requestBody") = buildRequestBody(schema, op.requestMediaTypes, components)
    }

    fields("responses") = buildResponses(op, components)

    JsonValue.Object(fields.toMap)
  }

  private def buildParameter(param: ParameterSchema): JsonValue = {
    JsonValue.Object(
      Map(
        "name" -> JsonValue.Str(param.name),
        "in" -> JsonValue.Str(locationString(param.in)),
        "required" -> JsonValue.Bool(param.required),
        "schema" -> schemaToJson(param.schema)
      )
    )
  }

  private def buildRequestBody(
    schema: TypeSchema,
    mediaTypes: Vector[String],
    components: ComponentRegistry
  ): JsonValue = {
    val effectiveMediaTypes = if mediaTypes.isEmpty then Vector("application/json") else mediaTypes
    JsonValue.Object(
      Map(
        "required" -> JsonValue.Bool(true),
        "content" -> JsonValue.Object(
          effectiveMediaTypes.map { mt =>
            mt -> JsonValue.Object(Map("schema" -> schemaToJson(schema, components)))
          }.toMap
        )
      )
    )
  }

  private def buildResponses(op: OperationSchema, components: ComponentRegistry): JsonValue = {
    val statusStr = op.responseStatus.toString
    val success = buildSuccessResponse(op, components)
    JsonValue.Object(Map(statusStr -> success) ++ standardErrorResponses())
  }

  private def buildSuccessResponse(op: OperationSchema, components: ComponentRegistry): JsonValue = {
    val responseObj = op.responseBody match {
      case Some(schema) if !op.isEventStream =>
        JsonValue.Object(
          Map(
            "description" -> JsonValue.Str("Success"),
            "content" -> JsonValue.Object(
              Map(
                "application/json" -> JsonValue.Object(
                  Map(
                    "schema" -> schemaToJson(schema, components)
                  )
                )
              )
            )
          )
        )
      case _ if op.isEventStream =>
        JsonValue.Object(
          Map(
            "description" -> JsonValue.Str("Server-Sent Events stream"),
            "content" -> JsonValue.Object(
              Map(
                "text/event-stream" -> JsonValue.Object(Map.empty)
              )
            )
          )
        )
      case _ =>
        JsonValue.Object(Map("description" -> JsonValue.Str("Success")))
    }

    buildResponseHeaders(op.responseHeaders) match {
      case Some(headers) =>
        responseObj match {
          case JsonValue.Object(fields) => JsonValue.Object(fields + ("headers" -> headers))
          case other => other
        }
      case None => responseObj
    }
  }

  private def buildResponseHeaders(headers: Vector[ResponseHeaderSchema]): Option[JsonValue] =
    if headers.isEmpty then None
    else
      Some(
        JsonValue.Object(
          headers.map { h =>
            val fields = Map("schema" -> schemaToJson(h.schema))
              ++ h.description.map(d => "description" -> JsonValue.Str(d))
              ++ (if h.required then Map("required" -> JsonValue.Bool(true)) else Map.empty)
            h.name -> JsonValue.Object(fields)
          }.toMap
        )
      )

  /** Every Melian operation can fail at the router/transport level with these responses. 400, 404,
    * 405, and 500 come from the router itself; 422 is the RFC 9457 validation error rendered from
    * the Girdle pipeline. 400/422 carry the problem+json schema; the others are empty.
    */
  private def standardErrorResponses(): Map[String, JsonValue] = {
    val problemJson = JsonValue.Object(
      Map(
        "schema" -> JsonValue.Object(Map("$ref" -> JsonValue.Str("#/components/schemas/ProblemDetails")))
      )
    )
    Map(
      "400" -> JsonValue.Object(
        Map(
          "description" -> JsonValue.Str("Bad Request"),
          "content" -> JsonValue.Object(Map("application/problem+json" -> problemJson))
        )
      ),
      "404" -> JsonValue.Object(Map("description" -> JsonValue.Str("Not Found"))),
      "405" -> JsonValue.Object(Map("description" -> JsonValue.Str("Method Not Allowed"))),
      "422" -> JsonValue.Object(
        Map(
          "description" -> JsonValue.Str("Unprocessable Content"),
          "content" -> JsonValue.Object(Map("application/problem+json" -> problemJson))
        )
      ),
      "500" -> JsonValue.Object(Map("description" -> JsonValue.Str("Internal Server Error")))
    )
  }

  private def buildComponents(components: ComponentRegistry): JsonValue = {
    JsonValue.Object(
      Map(
        "schemas" -> JsonValue.Object(
          components.schemas.map { case (name, schema) =>
            name -> schemaToJson(schema)
          } + ("ProblemDetails" -> problemDetailsSchema)
        )
      )
    )
  }

  /** RFC 9457 problem-details shape as emitted by [[net.ghoula.melian.router.ProblemDetails]]. The
    * `errors` array carries Melian's structured per-field/per-source detail.
    */
  private def problemDetailsSchema: JsonValue =
    JsonValue.Object(
      Map(
        "type" -> JsonValue.Str("object"),
        "properties" -> JsonValue.Object(
          Map(
            "type" -> JsonValue.Object(
              Map("type" -> JsonValue.Str("string"), "format" -> JsonValue.Str("uri-reference"))
            ),
            "title" -> JsonValue.Object(Map("type" -> JsonValue.Str("string"))),
            "status" -> JsonValue.Object(Map("type" -> JsonValue.Str("integer"))),
            "detail" -> JsonValue.Object(Map("type" -> JsonValue.Str("string"))),
            "instance" -> JsonValue.Object(
              Map("type" -> JsonValue.Str("string"), "format" -> JsonValue.Str("uri-reference"))
            ),
            "errors" -> JsonValue.Object(
              Map(
                "type" -> JsonValue.Str("array"),
                "items" -> JsonValue.Object(Map("type" -> JsonValue.Str("object")))
              )
            )
          )
        )
      )
    )

  private[openapi] def schemaToJson(
    schema: TypeSchema,
    components: ComponentRegistry = ComponentRegistry.empty
  ): JsonValue = {
    schema match {
      case TypeSchema.StringSchema => JsonValue.Object(Map("type" -> JsonValue.Str("string")))
      case TypeSchema.IntSchema =>
        JsonValue.Object(Map("type" -> JsonValue.Str("integer"), "format" -> JsonValue.Str("int32")))
      case TypeSchema.LongSchema =>
        JsonValue.Object(Map("type" -> JsonValue.Str("integer"), "format" -> JsonValue.Str("int64")))
      case TypeSchema.DoubleSchema =>
        JsonValue.Object(Map("type" -> JsonValue.Str("number"), "format" -> JsonValue.Str("double")))
      case TypeSchema.BooleanSchema => JsonValue.Object(Map("type" -> JsonValue.Str("boolean")))
      case TypeSchema.UuidSchema =>
        JsonValue.Object(Map("type" -> JsonValue.Str("string"), "format" -> JsonValue.Str("uuid")))
      case TypeSchema.UnitSchema => JsonValue.Object(Map.empty)

      case TypeSchema.OptionalSchema(inner) => nullable(schemaToJson(inner, components))

      case TypeSchema.ArraySchema(items) =>
        JsonValue.Object(Map("type" -> JsonValue.Str("array"), "items" -> schemaToJson(items, components)))

      case obj: TypeSchema.ObjectSchema =>
        if components.schemas.contains(obj.name) then
          JsonValue.Object(Map("$ref" -> JsonValue.Str(s"#/components/schemas/${obj.name}")))
        else inlineObjectSchema(obj, components)

      case TypeSchema.EnumSchema(_, values) =>
        JsonValue.Object(
          Map(
            "type" -> JsonValue.Str("string"),
            "enum" -> JsonValue.Array(values.toList.map(JsonValue.Str(_)))
          )
        )

      case TypeSchema.Ref(name) =>
        JsonValue.Object(Map("$ref" -> JsonValue.Str(s"#/components/schemas/$name")))

      case TypeSchema.EventStreamSchema =>
        JsonValue.Object(Map("type" -> JsonValue.Str("string"), "format" -> JsonValue.Str("event-stream")))
    }
  }

  /** Makes a rendered schema admit JSON `null`, matching how sarati actually serializes a `None`
    * field (key present, value `null`), so the spec describes the real wire shape.
    *
    * Uses the idiomatic JSON Schema 2020-12 `type` array (`["string", "null"]`) for plain typed
    * schemas, preserving siblings like `format`/`items`/`properties`. Falls back to
    * `anyOf: [schema, {type: "null"}]` where the type-array form is wrong: a `$ref` (a `type: null`
    * sibling would be an unsatisfiable intersection) or an `enum` (which would otherwise reject
    * `null`).
    */
  private def nullable(schema: JsonValue): JsonValue = schema match {
    case JsonValue.Object(fields) if !fields.contains("$ref") && !fields.contains("enum") =>
      fields.get("type") match {
        case Some(JsonValue.Str(t)) =>
          JsonValue.Object(fields + ("type" -> JsonValue.Array(List(JsonValue.Str(t), JsonValue.Str("null")))))
        case Some(JsonValue.Array(ts)) if !ts.contains(JsonValue.Str("null")) =>
          JsonValue.Object(fields + ("type" -> JsonValue.Array(ts :+ JsonValue.Str("null"))))
        case _ => anyOfNull(schema)
      }
    case _ => anyOfNull(schema)
  }

  private def anyOfNull(schema: JsonValue): JsonValue =
    JsonValue.Object(
      Map("anyOf" -> JsonValue.Array(List(schema, JsonValue.Object(Map("type" -> JsonValue.Str("null"))))))
    )

  private def inlineObjectSchema(obj: TypeSchema.ObjectSchema, components: ComponentRegistry): JsonValue = {
    val props = obj.fields.map { f =>
      val base = schemaToJson(f.schema, components)
      val enriched = (base, f.description, f.example) match {
        case (JsonValue.Object(fields), desc, ex) =>
          JsonValue.Object(
            fields
              ++ desc.map(d => "description" -> JsonValue.Str(d))
              ++ ex.map(e => "example" -> JsonValue.Str(e))
          )
        case _ => base
      }
      f.name -> enriched
    }.toMap
    val required = obj.fields.filter(_.required).map(f => JsonValue.Str(f.name)).toList

    JsonValue.Object(
      Map(
        "type" -> JsonValue.Str("object"),
        "properties" -> JsonValue.Object(props)
      ) ++ obj.description.map(d => "description" -> JsonValue.Str(d))
        ++ (if required.nonEmpty then Map("required" -> JsonValue.Array(required)) else Map.empty)
    )
  }

  private def locationString(loc: ParameterLocation): String = loc match {
    case ParameterLocation.Path => "path"
    case ParameterLocation.Query => "query"
    case ParameterLocation.Header => "header"
  }
}
