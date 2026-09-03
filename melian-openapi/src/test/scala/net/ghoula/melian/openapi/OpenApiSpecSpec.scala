package net.ghoula.melian.openapi

import munit.FunSuite

import net.ghoula.eru.http.*
import net.ghoula.melian.schema.*
import net.ghoula.sarati.ast.json.{JsonValue, compactFormat, formatJson}

class OpenApiSpecSpec extends FunSuite {

  private def json(spec: JsonValue): String = formatJson(spec, compactFormat)

  private val getRoute = OperationSchema(
    pathTemplate = "/users/:id",
    method = "GET",
    parameters = Vector(
      ParameterSchema("id", ParameterLocation.Path, TypeSchema.UuidSchema, required = true),
      ParameterSchema("page", ParameterLocation.Query, TypeSchema.IntSchema, required = true),
      ParameterSchema(
        "status",
        ParameterLocation.Query,
        TypeSchema.OptionalSchema(TypeSchema.StringSchema),
        required = false
      )
    ),
    requestBody = None,
    responseStatus = 200,
    responseBody = Some(
      TypeSchema.ObjectSchema(
        "User",
        Vector(
          FieldSchema("id", TypeSchema.UuidSchema, required = true),
          FieldSchema("name", TypeSchema.StringSchema, required = true),
          FieldSchema("email", TypeSchema.OptionalSchema(TypeSchema.StringSchema), required = false)
        )
      )
    ),
    isEventStream = false
  )

  private val postRoute = OperationSchema(
    pathTemplate = "/users",
    method = "POST",
    parameters = Vector(
      ParameterSchema("Authorization", ParameterLocation.Header, TypeSchema.StringSchema, required = true)
    ),
    requestBody = Some(
      TypeSchema.ObjectSchema(
        "CreateUser",
        Vector(
          FieldSchema("name", TypeSchema.StringSchema, required = true),
          FieldSchema("email", TypeSchema.StringSchema, required = true)
        )
      )
    ),
    responseStatus = 201,
    responseBody = Some(
      TypeSchema.ObjectSchema(
        "User",
        Vector(
          FieldSchema("id", TypeSchema.UuidSchema, required = true),
          FieldSchema("name", TypeSchema.StringSchema, required = true),
          FieldSchema("email", TypeSchema.OptionalSchema(TypeSchema.StringSchema), required = false)
        )
      )
    ),
    isEventStream = false
  )

  private val sseRoute = OperationSchema(
    pathTemplate = "/events/:id",
    method = "GET",
    parameters = Vector(
      ParameterSchema("id", ParameterLocation.Path, TypeSchema.UuidSchema, required = true)
    ),
    requestBody = None,
    responseStatus = 200,
    responseBody = None,
    isEventStream = true
  )

  private val info = OpenApiSpec.Info("Test API", "1.0.0", Some("A test"))

  test("generates valid OpenAPI 3.1 structure") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val output = json(spec)

    assert(output.contains("\"openapi\":\"3.1.0\""), s"Missing version: $output")
    assert(output.contains("\"paths\""), s"Missing paths: $output")
  }

  test("info object carries the required title, version, and description") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val output = json(spec)

    // OpenAPI 3.1 requires `title` and `version` on the Info object. Assert the
    // exact key, so a mislabelled field cannot pass.
    assert(output.contains("\"title\":\"Test API\""), s"Missing info.title: $output")
    assert(output.contains("\"version\":\"1.0.0\""), s"Missing info.version: $output")
    assert(output.contains("\"description\":\"A test\""), s"Missing info.description: $output")
  }

  test("GET route with path and query parameters") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val output = json(spec)

    assert(output.contains("\"/users/{id}\""), s"Path not converted: $output")
    assert(output.contains("\"name\":\"id\""), s"Missing path param: $output")
    assert(output.contains("\"name\":\"page\""), s"Missing query param: $output")
    assert(output.contains("\"in\":\"path\""), s"Missing path location: $output")
    assert(output.contains("\"in\":\"query\""), s"Missing query location: $output")
  }

  test("optional primitive field renders as nullable type array") {
    // sarati serializes a `None` field as `{"key": null}` (key present, value
    // null). The spec must describe that: a nullable type, not a bare string.
    val rendered = OpenApiSpec.schemaToJson(TypeSchema.OptionalSchema(TypeSchema.StringSchema))
    rendered match {
      case JsonValue.Object(fields) =>
        assertEquals(
          fields.get("type"),
          Some(JsonValue.Array(List(JsonValue.Str("string"), JsonValue.Str("null")))),
          s"Optional String must render type: [\"string\",\"null\"], got: $rendered"
        )
      case other => fail(s"Expected object schema, got: $other")
    }
  }

  test("optional object ref renders as anyOf with null") {
    // A $ref cannot take a sibling `type: null` (it would be an unsatisfiable
    // intersection), so an optional object ref must use anyOf: [ref, null].
    val user = TypeSchema.ObjectSchema("User", Vector(FieldSchema("id", TypeSchema.UuidSchema, required = true)))
    val components = ComponentRegistry.from(
      Vector(
        OperationSchema(
          pathTemplate = "/u",
          method = "GET",
          parameters = Vector.empty,
          requestBody = None,
          responseStatus = 200,
          responseBody = Some(user),
          isEventStream = false
        )
      )
    )
    val rendered = OpenApiSpec.schemaToJson(TypeSchema.OptionalSchema(user), components)
    rendered match {
      case JsonValue.Object(fields) =>
        fields.get("anyOf") match {
          case Some(JsonValue.Array(branches)) =>
            assert(
              branches.contains(JsonValue.Object(Map("type" -> JsonValue.Str("null")))),
              s"anyOf must include a null branch: $rendered"
            )
            assert(
              branches.contains(JsonValue.Object(Map("$ref" -> JsonValue.Str("#/components/schemas/User")))),
              s"anyOf must include the \\$$ref branch: $rendered"
            )
          case _ => fail(s"Optional object ref must render anyOf, got: $rendered")
        }
      case other => fail(s"Expected object schema, got: $other")
    }
  }

  test("optional field is excluded from the required list") {
    // The getRoute User schema has an optional `email`; it must not appear in
    // `required`, while `id` and `name` must.
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val output = json(spec)
    assert(output.contains("\"required\":[\"id\",\"name\"]"), s"email must be excluded from required: $output")
  }

  test("POST route with request body schema") {
    val spec = OpenApiSpec.generate(info, Vector(postRoute))
    val output = json(spec)

    assert(output.contains("\"requestBody\""), s"Missing requestBody: $output")
    assert(output.contains("\"application/json\""), s"Missing content type: $output")
    assert(output.contains("CreateUser") || output.contains("$ref"), s"Missing body schema ref: $output")
  }

  test("response schema references component") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val output = json(spec)

    assert(output.contains("\"201\"") || output.contains("\"200\""), s"Missing status code: $output")
    assert(
      output.contains("#/components/schemas/User") || output.contains("\"User\""),
      s"Missing response schema: $output"
    )
  }

  test("components registry deduplicates schemas") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute, postRoute))
    val output = json(spec)

    assert(output.contains("\"components\""), s"Missing components: $output")
    assert(output.contains("\"schemas\""), s"Missing schemas: $output")
    assert(output.contains("\"User\""), s"Missing User schema: $output")
    assert(output.contains("\"CreateUser\""), s"Missing CreateUser schema: $output")
  }

  test("EventStream route produces text/event-stream response") {
    val spec = OpenApiSpec.generate(info, Vector(sseRoute))
    val output = json(spec)

    assert(output.contains("\"text/event-stream\""), s"Missing event-stream content type: $output")
    assert(output.contains("Server-Sent Events"), s"Missing SSE description: $output")
  }

  test("multiple methods on same path are grouped") {
    val getUsers = OperationSchema(
      pathTemplate = "/users",
      method = "GET",
      parameters = Vector.empty,
      requestBody = None,
      responseStatus = 200,
      responseBody = Some(TypeSchema.ArraySchema(TypeSchema.StringSchema)),
      isEventStream = false
    )
    val spec = OpenApiSpec.generate(info, Vector(getUsers, postRoute))
    val output = json(spec)

    assert(output.contains("\"get\""), s"Missing GET method: $output")
    assert(output.contains("\"post\""), s"Missing POST method: $output")
  }

  test("enum schema produces string enum") {
    val routeWithEnum = OperationSchema(
      pathTemplate = "/items",
      method = "GET",
      parameters = Vector(
        ParameterSchema(
          "status",
          ParameterLocation.Query,
          TypeSchema.EnumSchema("Status", Vector("Active", "Inactive", "Archived")),
          required = true
        )
      ),
      requestBody = None,
      responseStatus = 200,
      responseBody = None,
      isEventStream = false
    )
    val spec = OpenApiSpec.generate(info, Vector(routeWithEnum))
    val output = json(spec)

    assert(output.contains("\"enum\""), s"Missing enum: $output")
    assert(output.contains("\"Active\""), s"Missing enum value: $output")
  }

  test("recursive type uses $ref instead of infinite nesting") {
    val treeSchema = TypeSchema.ObjectSchema(
      "TreeNode",
      Vector(
        FieldSchema("value", TypeSchema.StringSchema, required = true),
        FieldSchema("children", TypeSchema.ArraySchema(TypeSchema.Ref("TreeNode")), required = true)
      )
    )
    val route = OperationSchema(
      pathTemplate = "/tree",
      method = "GET",
      parameters = Vector.empty,
      requestBody = None,
      responseStatus = 200,
      responseBody = Some(treeSchema),
      isEventStream = false
    )
    val spec = OpenApiSpec.generate(info, Vector(route))
    val output = json(spec)

    assert(output.contains("#/components/schemas/TreeNode"), s"Missing ${"$"}ref for recursive type: $output")
    assert(output.contains("\"TreeNode\""), s"Missing component: $output")
  }

  test("annotations appear in generated schema") {
    val annotatedSchema = TypeSchema.ObjectSchema(
      "AnnotatedModel",
      Vector(
        FieldSchema(
          "name",
          TypeSchema.StringSchema,
          required = true,
          description = Some("Display name"),
          example = Some("My Widget")
        ),
        FieldSchema("count", TypeSchema.IntSchema, required = true, description = Some("Item count"), example = None),
        FieldSchema("tag", TypeSchema.StringSchema, required = false)
      ),
      description = Some("A model with annotations")
    )
    val route = OperationSchema(
      pathTemplate = "/annotated",
      method = "GET",
      parameters = Vector.empty,
      requestBody = None,
      responseStatus = 200,
      responseBody = Some(annotatedSchema),
      isEventStream = false
    )
    val spec = OpenApiSpec.generate(info, Vector(route))
    val output = json(spec)

    assert(output.contains("\"description\":\"Display name\""), s"Missing field description: $output")
    assert(output.contains("\"example\":\"My Widget\""), s"Missing field example: $output")
    assert(output.contains("\"description\":\"Item count\""), s"Missing count description: $output")
    assert(output.contains("\"description\":\"A model with annotations\""), s"Missing type description: $output")
  }

  test("response headers are documented") {
    val op = OperationSchema(
      pathTemplate = "/users",
      method = "POST",
      parameters = Vector.empty,
      requestBody = Some(
        TypeSchema.ObjectSchema("CreateUser", Vector(FieldSchema("name", TypeSchema.StringSchema, required = true)))
      ),
      requestMediaTypes = Vector("application/json"),
      responseStatus = 201,
      responseBody = Some(
        TypeSchema.ObjectSchema("User", Vector(FieldSchema("id", TypeSchema.UuidSchema, required = true)))
      ),
      responseHeaders = Vector(ResponseHeaderSchema("Location", Some("URI of the created resource"), required = true)),
      isEventStream = false
    )
    val spec = OpenApiSpec.generate(info, Vector(op))
    val output = json(spec)

    assert(output.contains("\"headers\""), s"Missing response headers: $output")
    assert(output.contains("\"Location\""), s"Missing Location header: $output")
    assert(output.contains("\"required\":true"), s"Location should be required: $output")
  }

  test("error responses are documented for every operation") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val output = json(spec)

    assert(output.contains("\"400\""), s"Missing 400: $output")
    assert(output.contains("\"404\""), s"Missing 404: $output")
    assert(output.contains("\"405\""), s"Missing 405: $output")
    assert(output.contains("\"422\""), s"Missing 422: $output")
    assert(output.contains("\"500\""), s"Missing 500: $output")
    assert(output.contains("application/problem+json"), s"Missing problem+json: $output")
    assert(output.contains("ProblemDetails"), s"Missing ProblemDetails component: $output")
  }

  test("request body renders multiple media types for Coded bodies") {
    val op = OperationSchema(
      pathTemplate = "/upload",
      method = "POST",
      parameters = Vector.empty,
      requestBody = Some(
        TypeSchema.ObjectSchema("Doc", Vector(FieldSchema("name", TypeSchema.StringSchema, required = true)))
      ),
      requestMediaTypes = Vector("application/json", "application/xml", "application/yaml"),
      responseStatus = 200,
      responseBody = None,
      isEventStream = false
    )
    val spec = OpenApiSpec.generate(info, Vector(op))
    val output = json(spec)

    assert(output.contains("application/json"), s"Missing json: $output")
    assert(output.contains("application/xml"), s"Missing xml: $output")
    assert(output.contains("application/yaml"), s"Missing yaml: $output")
  }

  test("Form request body renders form-urlencoded content type") {
    val op = OperationSchema(
      pathTemplate = "/login",
      method = "POST",
      parameters = Vector.empty,
      requestBody = Some(
        TypeSchema.ObjectSchema("LoginForm", Vector(FieldSchema("username", TypeSchema.StringSchema, required = true)))
      ),
      requestMediaTypes = Vector("application/x-www-form-urlencoded"),
      responseStatus = 200,
      responseBody = None,
      isEventStream = false
    )
    val spec = OpenApiSpec.generate(info, Vector(op))
    val output = json(spec)

    assert(output.contains("application/x-www-form-urlencoded"), s"Missing form-urlencoded: $output")
  }

  test("servers are rendered at the top level") {
    val spec = OpenApiSpec.generate(info.copy(servers = List("https://api.example.com")), Vector(getRoute))
    val output = json(spec)

    assert(output.contains("\"servers\""), s"Missing servers: $output")
    assert(output.contains("https://api.example.com"), s"Missing server URL: $output")
  }

  test("operation summary, description, and tags are rendered") {
    val op = OperationSchema(
      pathTemplate = "/users",
      method = "GET",
      parameters = Vector.empty,
      requestBody = None,
      responseStatus = 200,
      responseBody = None,
      isEventStream = false,
      summary = Some("List users"),
      description = Some("Returns all users"),
      tags = Vector("users", "admin")
    )
    val spec = OpenApiSpec.generate(info, Vector(op))
    val output = json(spec)

    assert(output.contains("\"summary\":\"List users\""), s"Missing summary: $output")
    assert(output.contains("\"description\":\"Returns all users\""), s"Missing description: $output")
    assert(output.contains("\"tags\""), s"Missing tags: $output")
    assert(output.contains("\"users\""), s"Missing tag users: $output")
    assert(output.contains("\"admin\""), s"Missing tag admin: $output")
  }

  // --- OpenApiRoutes ---

  test("spec handler serves JSON at configured path") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val handler = OpenApiRoutes.specHandler(spec, "/openapi.json")
    val request = Request(Method.GET, Uri.http("localhost", path = "/openapi.json"), Headers.empty, Body.empty)
    val result = handler(request).unsafeRunSync()

    assert(result.isDefined, "Should match /openapi.json")
    val response = result.get
    assertEquals(response.status, StatusCode.Ok)
    val body = response.body match {
      case Body.Text(text, _, _) => text
      case other => fail(s"Expected text body, got: $other"); ""
    }
    assert(body.contains("\"openapi\":\"3.1.0\""), s"Missing version: $body")
  }

  test("spec handler returns None for non-matching path") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val handler = OpenApiRoutes.specHandler(spec)
    val request = Request(Method.GET, Uri.http("localhost", path = "/users"), Headers.empty, Body.empty)
    val result = handler(request).unsafeRunSync()

    assert(result.isEmpty, "Should not match /users")
  }

  test("swagger UI handler serves HTML at /docs") {
    val handler = OpenApiRoutes.swaggerUiHandler()
    val request = Request(Method.GET, Uri.http("localhost", path = "/docs"), Headers.empty, Body.empty)
    val result = handler(request).unsafeRunSync()

    assert(result.isDefined, "Should match /docs")
    val body = result.get.body match {
      case Body.Text(text, _, _) => text
      case other => fail(s"Expected text body, got: $other"); ""
    }
    assert(body.contains("swagger-ui"), s"Missing Swagger UI: $body")
    assert(body.contains("/openapi.json"), s"Missing spec URL: $body")
  }

  test("middleware composes spec + UI + inner handler") {
    val spec = OpenApiSpec.generate(info, Vector(getRoute))
    val inner: Request[Body] => net.ghoula.eru.Eru[HttpError, Response[Body]] = { _ =>
      net.ghoula.eru.Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("inner")))
    }
    val composed = OpenApiRoutes.middleware(spec)(inner)

    val specReq = Request(Method.GET, Uri.http("localhost", path = "/openapi.json"), Headers.empty, Body.empty)
    val specResp = composed(specReq).unsafeRunSync()
    assertEquals(specResp.status, StatusCode.Ok)

    val docsReq = Request(Method.GET, Uri.http("localhost", path = "/docs"), Headers.empty, Body.empty)
    val docsResp = composed(docsReq).unsafeRunSync()
    assertEquals(docsResp.status, StatusCode.Ok)

    val otherReq = Request(Method.GET, Uri.http("localhost", path = "/users"), Headers.empty, Body.empty)
    val otherResp = composed(otherReq).unsafeRunSync()
    val otherBody = otherResp.body match {
      case Body.Text(text, _, _) => text
      case _ => ""
    }
    assertEquals(otherBody, "inner")
  }

  test("explicit operationId is emitted, otherwise derived from method and path") {
    val explicit = getRoute.copy(operationId = Some("getUser"))
    val spec = OpenApiSpec.generate(info, Vector(explicit))
    val output = json(spec)
    assert(output.contains("\"operationId\":\"getUser\""), s"Missing explicit operationId: $output")

    val derived = OpenApiSpec.generate(info, Vector(getRoute))
    val derivedOutput = json(derived)
    assert(
      derivedOutput.contains("\"operationId\":\"getUsersById\""),
      s"Missing derived operationId: $derivedOutput"
    )
  }

  test("derived operationId is a valid identifier for odd paths") {
    val op = getRoute.copy(pathTemplate = "/workspaces/:workspaceId/posts/:postId", operationId = None)
    val spec = OpenApiSpec.generate(info, Vector(op))
    val output = json(spec)
    assert(
      output.contains("\"operationId\":\"getWorkspacesByWorkspaceIdPostsByPostId\""),
      s"Missing derived operationId: $output"
    )
  }

  test("negotiating routes document every response media type") {
    val negotiating =
      getRoute.copy(responseMediaTypes = Vector("application/json", "application/xml", "application/yaml"))
    val spec = OpenApiSpec.generate(info, Vector(negotiating))
    val output = json(spec)
    assert(output.contains("\"application/json\""), s"Missing json: $output")
    assert(output.contains("\"application/xml\""), s"Missing xml: $output")
    assert(output.contains("\"application/yaml\""), s"Missing yaml: $output")
  }

  test("WebSocket operations are marked with the x-websocket extension") {
    val wsOp = OperationSchema(
      pathTemplate = "/ws/:id",
      method = "GET",
      parameters = Vector.empty,
      requestBody = None,
      responseStatus = 101,
      responseBody = None,
      isEventStream = false,
      isWebSocket = true
    )
    val spec = OpenApiSpec.generate(info, Vector(wsOp))
    val output = json(spec)
    assert(output.contains("\"x-websocket\":true"), s"Missing x-websocket: $output")
  }

  test("Unauthorized and TooManyRequests operations document their required headers") {
    val op = OperationSchema(
      pathTemplate = "/secure",
      method = "GET",
      parameters = Vector.empty,
      requestBody = None,
      responseStatus = 401,
      responseBody = Some(TypeSchema.StringSchema),
      responseHeaders = Vector(
        ResponseHeaderSchema("WWW-Authenticate", Some("Authentication challenge"), required = true)
      ),
      isEventStream = false
    )
    val spec = OpenApiSpec.generate(info, Vector(op))
    val output = json(spec)
    assert(output.contains("\"401\""), s"Missing 401: $output")
    assert(output.contains("\"WWW-Authenticate\""), s"Missing WWW-Authenticate: $output")
  }
}
