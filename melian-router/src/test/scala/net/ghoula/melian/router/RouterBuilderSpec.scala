package net.ghoula.melian.router

import munit.FunSuite

import java.util.UUID

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.melian.*
import net.ghoula.melian.extraction.FormDecoder
import net.ghoula.melian.extraction.FromHeader.BearerToken
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.{Decoder, Encoder}
import net.ghoula.valar.ValidationErrors.ValidationError
import net.ghoula.valar.{ValidationResult, Validator}

class RouterBuilderSpec extends FunSuite {

  given Encoder[String, JsonValue] with {
    def encode(value: String): JsonValue = JsonValue.Str(value)
  }

  case class CreateCommand(name: String)
  case class Workspace(id: UUID, name: String)

  given Decoder[JsonValue, CreateCommand] with {
    def decode(value: JsonValue): net.ghoula.sarati.Result[net.ghoula.sarati.DecodeError, CreateCommand] =
      value match {
        case JsonValue.Object(fields) =>
          fields.get("name") match {
            case Some(JsonValue.Str(n)) =>
              net.ghoula.sarati.Result.Success(CreateCommand(n), 0)
            case _ =>
              net.ghoula.sarati.Result.Failure(
                List(net.ghoula.sarati.DecodeError.MissingField("name", (line = 1, column = 1, offset = 0))),
                (line = 1, column = 1, offset = 0)
              )
          }
        case _ =>
          net.ghoula.sarati.Result.Failure(
            List(net.ghoula.sarati.DecodeError.TypeMismatch("Object", "other", (line = 1, column = 1, offset = 0))),
            (line = 1, column = 1, offset = 0)
          )
      }
  }

  given Encoder[Workspace, JsonValue] with {
    def encode(value: Workspace): JsonValue = JsonValue.Object(
      Map(
        "id" -> JsonValue.Str(value.id.toString),
        "name" -> JsonValue.Str(value.name)
      )
    )
  }

  given Validator[CreateCommand] = Validator.derive

  import SaratiBridge.given

  /** Run a full request through the handler at the edge. */
  private def run(handler: Request[Body] => Eru[HttpError, Response[Body]], request: Request[Body]): Response[Body] =
    handler(request).unsafeRunSync()

  private def bodyText(response: Response[Body]): String = response.body match {
    case Body.Text(text, _, _) => text
    case other => fail(s"Expected Text body, got: $other"); ""
  }

  private def requestWith(
    method: Method,
    path: String,
    body: Body = Body.empty,
    headerPairs: List[(String, String)] = Nil
  ): Request[Body] = {
    val base = Request(method = method, uri = Uri.http("localhost", path = path), headers = Headers.empty, body = body)
    headerPairs
      .foldLeft[Eru[Any, Request[Body]]](Eru.succeed(base)) { case (acc, (name, value)) =>
        acc.flatMap(_.addHeader(name, value))
      }
      .unsafeRunSync()
  }

  private def requestWithQuery(
    method: Method,
    pathAndQuery: String,
    headerPairs: List[(String, String)] = Nil
  ): Request[Body] = {
    val uri = Uri.parse(s"http://localhost$pathAndQuery").unsafeRunSync()
    val base = Request(method = method, uri = uri, headers = Headers.empty, body = Body.empty)
    headerPairs
      .foldLeft[Eru[Any, Request[Body]]](Eru.succeed(base)) { case (acc, (name, value)) =>
        acc.flatMap(_.addHeader(name, value))
      }
      .unsafeRunSync()
  }

  // --- Phase 2: GET + Path ---

  test("GET with path param extracts UUID and returns JSON") {
    val handler: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"user-${id}"))

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(fail("build failed"))
    val testId = "550e8400-e29b-41d4-a716-446655440000"
    val response = run(router.toHandler, requestWith(Method.GET, s"/users/$testId"))

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains(s"user-$testId"))
  }

  test("GET to unknown path returns 404") {
    val handler: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"user-${id}"))

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/unknown"))

    assertEquals(response.status, StatusCode.NotFound)
  }

  test("POST to GET-only route returns 405") {
    val handler: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"user-${id}"))

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.POST, "/users/550e8400-e29b-41d4-a716-446655440000"))

    assertEquals(response.status, StatusCode.MethodNotAllowed)
  }

  test("duplicate route is rejected at build time") {
    val handler: Path[String] => Eru[Nothing, Ok[String]] =
      (id: Path[String]) => Eru.succeed(Ok(id))

    val result = Router.builder
      .get("/users/:id", handler)
      .get("/users/:id", handler)
      .build

    assert(result.isLeft, "A duplicate path and method must be rejected")
  }

  test("structurally-identical routes with different parameter names are rejected") {
    val handler: Path[String] => Eru[Nothing, Ok[String]] =
      (id: Path[String]) => Eru.succeed(Ok(id))

    val result = Router.builder
      .get("/users/:id", handler)
      .get("/users/:name", handler)
      .build

    assert(result.isLeft, "Routes differing only in parameter name must be rejected")
  }

  test("multiple routes dispatch correctly") {
    val getUser: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"get-${id}"))

    val getItem: Path[Int] => Eru[Nothing, Ok[String]] =
      (id: Path[Int]) => Eru.succeed(Ok(s"item-${id}"))

    val router = Router.builder
      .get("/users/:id", getUser)
      .get("/items/:id", getItem)
      .build
      .getOrElse(fail("build failed"))

    val userBody =
      bodyText(run(router.toHandler, requestWith(Method.GET, "/users/550e8400-e29b-41d4-a716-446655440000")))
    val itemBody = bodyText(run(router.toHandler, requestWith(Method.GET, "/items/42")))

    assert(userBody.contains("get-550e8400"))
    assert(itemBody.contains("item-42"))
  }

  // --- Phase 3: POST + Path + Header + Json ---

  test("POST with path param, header, and JSON body") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val testId = "550e8400-e29b-41d4-a716-446655440000"
    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        s"/workspaces/$testId",
        body = Body.text("""{"name":"My Workspace"}""", MediaType.applicationJson),
        headerPairs = List("Authorization" -> "Bearer test-token-123")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    val body = bodyText(response)
    assert(body.contains(testId), s"Missing ID: $body")
    assert(body.contains("My Workspace"), s"Missing name: $body")
  }

  test("POST with missing Authorization header returns error") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/workspaces/550e8400-e29b-41d4-a716-446655440000",
        body = Body.text("""{"name":"test"}""", MediaType.applicationJson)
      )
    )

    assert(response.status.value >= 400, s"Expected error, got: ${response.status}")
  }

  test("POST with malformed JSON body returns error") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/workspaces/550e8400-e29b-41d4-a716-446655440000",
        body = Body.text("not json", MediaType.applicationJson),
        headerPairs = List("Authorization" -> "Bearer token")
      )
    )

    assert(response.status.value >= 400, s"Expected error, got: ${response.status}")
  }

  // --- Endpoint (context function) tests ---

  test("handler returning Endpoint gets RequestContext with requestId") {
    val handler: Path[UUID] => Endpoint[Nothing, Ok[String]] =
      (id: Path[UUID]) => {
        val ctx = summon[RequestContext]
        Eru.succeed(Ok(s"${id}:${ctx.requestId}"))
      }

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/users/550e8400-e29b-41d4-a716-446655440000"))

    assertEquals(response.status, StatusCode.Ok)
    val body = bodyText(response)
    assert(body.contains("550e8400"), s"Missing user ID: $body")
    // requestId is a UUID; check it's present (36 chars with dashes)
    assert(body.length > 40, s"Body too short to contain requestId: $body")
  }

  test("Endpoint handler receives decode warnings via RequestContext") {
    case class Cmd(name: String)

    given Decoder[JsonValue, Cmd] with {
      def decode(value: JsonValue): net.ghoula.sarati.Result[net.ghoula.sarati.DecodeError, Cmd] =
        value match {
          case JsonValue.Object(fields) =>
            fields.get("name") match {
              case Some(JsonValue.Str(n)) =>
                net.ghoula.sarati.Result.Partial(
                  Cmd(n),
                  List(net.ghoula.sarati.DecodeError.Custom("unknown field", (line = 1, column = 1, offset = 0))),
                  0
                )
              case _ =>
                net.ghoula.sarati.Result.Failure(
                  List(net.ghoula.sarati.DecodeError.MissingField("name", (line = 1, column = 1, offset = 0))),
                  (line = 1, column = 1, offset = 0)
                )
            }
          case _ =>
            net.ghoula.sarati.Result.Failure(
              List(net.ghoula.sarati.DecodeError.TypeMismatch("Object", "other", (line = 1, column = 1, offset = 0))),
              (line = 1, column = 1, offset = 0)
            )
        }
    }

    given Validator[Cmd] = Validator.derive

    val handler: Json[Cmd] => Endpoint[Nothing, Ok[String]] =
      (cmd: Json[Cmd]) => {
        val ctx = summon[RequestContext]
        Eru.succeed(Ok(s"${cmd.name}:${ctx.warnings.size}"))
      }

    val router = Router.builder.post("/warn", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(Method.POST, "/warn", body = Body.text("""{"name":"x"}""", MediaType.applicationJson))
    )

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains(":1"), s"Expected one decode warning, got: ${bodyText(response)}")
  }

  // --- ErrorRenderer tests ---

  enum DomainError derives CanEqual {
    case NotFound(id: UUID)
    case Forbidden(reason: String)
  }

  given ErrorRenderer[DomainError] with {
    def render(error: DomainError): Eru[Nothing, net.ghoula.eru.http.Response[Body]] = error match {
      case DomainError.NotFound(id) =>
        Eru.succeed(
          Response(
            StatusCode.NotFound,
            Headers.empty,
            Body.text(s"""{"error":"not found","id":"$id"}""", MediaType.applicationJson)
          )
        )
      case DomainError.Forbidden(reason) =>
        Eru.succeed(
          Response(
            StatusCode.Forbidden,
            Headers.empty,
            Body.text(s"""{"error":"forbidden","reason":"$reason"}""", MediaType.applicationJson)
          )
        )
    }
  }

  test("domain error rendered via ErrorRenderer") {
    val handler: Path[UUID] => Eru[DomainError, Ok[String]] =
      (id: Path[UUID]) => Eru.fail(DomainError.NotFound(id))

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/users/550e8400-e29b-41d4-a716-446655440000"))

    assertEquals(response.status, StatusCode.NotFound)
    val body = bodyText(response)
    assert(body.contains("not found"), s"Body: $body")
    assert(body.contains("550e8400"), s"Body missing ID: $body")
  }

  test("extraction error returns RFC 9457 problem+json") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/workspaces/not-a-uuid",
        body = Body.text("""{"name":"test"}""", MediaType.applicationJson),
        headerPairs = List("Authorization" -> "Bearer token")
      )
    )

    assertEquals(response.status, StatusCode.BadRequest)
    val body = bodyText(response)
    assert(body.contains("\"type\":\"about:blank\""), s"Missing RFC 9457 type: $body")
    assert(body.contains("\"title\":\"Bad Request\""), s"Missing title: $body")
    assert(body.contains("\"errors\""), s"Missing errors array: $body")
    assert(body.contains("\"in\":\"path\""), s"Missing extraction source: $body")
  }

  test("POST with missing header AND malformed body surfaces decode error") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/workspaces/550e8400-e29b-41d4-a716-446655440000",
        body = Body.text("not json", MediaType.applicationJson)
      )
    )

    assertEquals(response.status, StatusCode.BadRequest)
    val body = bodyText(response)
    assert(
      body.contains("parse") || body.contains("Decode") || body.contains("decode"),
      s"Should mention body error: $body"
    )
  }

  test("POST with missing header AND valid body accumulates extraction error") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/workspaces/550e8400-e29b-41d4-a716-446655440000",
        body = Body.text("""{"name":"test"}""", MediaType.applicationJson)
      )
    )

    assertEquals(response.status, StatusCode.BadRequest)
    val body = bodyText(response)
    assert(body.contains("Authorization") || body.contains("header"), s"Should mention missing header: $body")
  }

  // --- QUERY (RFC 10008) ---

  test("QUERY with JSON body dispatches") {
    val handler: Json[CreateCommand] => Eru[Nothing, Ok[String]] =
      (cmd: Json[CreateCommand]) => Eru.succeed(Ok(s"query-${cmd.name}"))

    val router = Router.builder.query("/search", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.QUERY,
        "/search",
        body = Body.text("""{"name":"needle"}""", MediaType.applicationJson),
        headerPairs = List("Content-Type" -> "application/json")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("query-needle"))
  }

  test("QUERY to a path with a non-QUERY route returns 405") {
    val handler: Json[CreateCommand] => Eru[Nothing, Ok[String]] =
      (cmd: Json[CreateCommand]) => Eru.succeed(Ok(cmd.name))

    val router = Router.builder.post("/search", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.QUERY,
        "/search",
        body = Body.text("""{"name":"x"}""", MediaType.applicationJson),
        headerPairs = List("Content-Type" -> "application/json")
      )
    )

    assertEquals(response.status, StatusCode.MethodNotAllowed)
  }

  // --- Query extraction ---

  test("GET with required query param extracts value") {
    val handler: Query["page", Int] => Eru[Nothing, Ok[String]] =
      (page: Query["page", Int]) => Eru.succeed(Ok(s"page-$page"))

    val router = Router.builder.get("/items", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWithQuery(Method.GET, "/items?page=3"))

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("page-3"))
  }

  test("GET with missing required query param returns error") {
    val handler: Query["page", Int] => Eru[Nothing, Ok[String]] =
      (page: Query["page", Int]) => Eru.succeed(Ok(s"page-$page"))

    val router = Router.builder.get("/items", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWithQuery(Method.GET, "/items"))

    assertEquals(response.status, StatusCode.BadRequest)
    val body = bodyText(response)
    assert(body.contains("page"), s"Should mention missing param: $body")
  }

  test("GET with invalid query param type returns error") {
    val handler: Query["page", Int] => Eru[Nothing, Ok[String]] =
      (page: Query["page", Int]) => Eru.succeed(Ok(s"page-$page"))

    val router = Router.builder.get("/items", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWithQuery(Method.GET, "/items?page=abc"))

    assertEquals(response.status, StatusCode.BadRequest)
    val body = bodyText(response)
    assert(body.contains("page"), s"Should mention param name: $body")
  }

  test("GET with optional query param present extracts Some") {
    val handler: Query["status", Option[String]] => Eru[Nothing, Ok[String]] =
      (status: Query["status", Option[String]]) => Eru.succeed(Ok(s"status-${status.getOrElse("none")}"))

    val router = Router.builder.get("/items", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWithQuery(Method.GET, "/items?status=active"))

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("status-active"))
  }

  test("GET with optional query param absent returns None") {
    val handler: Query["status", Option[String]] => Eru[Nothing, Ok[String]] =
      (status: Query["status", Option[String]]) => Eru.succeed(Ok(s"status-${status.getOrElse("none")}"))

    val router = Router.builder.get("/items", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWithQuery(Method.GET, "/items"))

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("status-none"))
  }

  test("query parameter preserves a literal plus character") {
    val handler: Query["q", String] => Eru[Nothing, Ok[String]] =
      (q: Query["q", String]) => Eru.succeed(Ok(s"q-$q"))

    val router = Router.builder.get("/search", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWithQuery(Method.GET, "/search?q=a+b"))

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("q-a+b"), s"Expected literal plus, got: ${bodyText(response)}")
  }

  test("malformed percent-encoding in a query parameter does not crash") {
    val handler: Query["q", String] => Eru[Nothing, Ok[String]] =
      (q: Query["q", String]) => Eru.succeed(Ok(s"q-$q"))

    val router = Router.builder.get("/search", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWithQuery(Method.GET, "/search?q=%ZZ"))

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("q-%ZZ"), s"Expected literal fallback, got: ${bodyText(response)}")
  }

  test("GET with path param and multiple query params") {
    val handler: (Path[UUID], Query["page", Int], Query["limit", Int]) => Eru[Nothing, Ok[String]] =
      (id, page, limit) => Eru.succeed(Ok(s"$id:page=$page:limit=$limit"))

    val router = Router.builder.get("/users/:id/items", handler).build.getOrElse(fail("build failed"))
    val testId = "550e8400-e29b-41d4-a716-446655440000"
    val response = run(router.toHandler, requestWithQuery(Method.GET, s"/users/$testId/items?page=2&limit=50"))

    assertEquals(response.status, StatusCode.Ok)
    val body = bodyText(response)
    assert(body.contains(testId), s"Missing ID: $body")
    assert(body.contains("page=2"), s"Missing page: $body")
    assert(body.contains("limit=50"), s"Missing limit: $body")
  }

  // --- Valar validation ---

  case class ValidatedCommand(name: String, count: Int)

  given Decoder[JsonValue, ValidatedCommand] with {
    def decode(value: JsonValue): net.ghoula.sarati.Result[net.ghoula.sarati.DecodeError, ValidatedCommand] =
      value match {
        case JsonValue.Object(fields) =>
          (fields.get("name"), fields.get("count")) match {
            case (Some(JsonValue.Str(n)), Some(JsonValue.Number(c, _))) =>
              net.ghoula.sarati.Result.Success(ValidatedCommand(n, c.toInt), 0)
            case _ =>
              net.ghoula.sarati.Result.Failure(
                List(net.ghoula.sarati.DecodeError.MissingField("name/count", (line = 1, column = 1, offset = 0))),
                (line = 1, column = 1, offset = 0)
              )
          }
        case _ =>
          net.ghoula.sarati.Result.Failure(
            List(net.ghoula.sarati.DecodeError.TypeMismatch("Object", "other", (line = 1, column = 1, offset = 0))),
            (line = 1, column = 1, offset = 0)
          )
      }
  }

  given Validator[ValidatedCommand] with {
    def validate(a: ValidatedCommand): ValidationResult[ValidatedCommand] = {
      val nameResult =
        if a.name.nonEmpty then ValidationResult.Valid(a.name)
        else
          ValidationResult.Invalid(
            Vector(ValidationError(message = "name must not be empty", fieldPath = List("name")))
          )
      val countResult =
        if a.count > 0 then ValidationResult.Valid(a.count)
        else
          ValidationResult.Invalid(
            Vector(ValidationError(message = "count must be positive", fieldPath = List("count")))
          )
      (nameResult, countResult) match {
        case (ValidationResult.Valid(n), ValidationResult.Valid(c)) =>
          ValidationResult.Valid(ValidatedCommand(n, c))
        case _ =>
          val errors = List(nameResult, countResult).collect { case ValidationResult.Invalid(errs) =>
            errs
          }.flatten.toVector
          ValidationResult.Invalid(errors)
      }
    }
  }

  test("POST with valid body passes validation") {
    val handler: (Header[BearerToken], Json[ValidatedCommand]) => Eru[Nothing, Ok[String]] =
      (auth, cmd) => { val _ = auth; Eru.succeed(Ok(s"${cmd.name}:${cmd.count}")) }

    val router = Router.builder.post("/commands", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/commands",
        body = Body.text("""{"name":"deploy","count":3}""", MediaType.applicationJson),
        headerPairs = List("Authorization" -> "Bearer token")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("deploy:3"))
  }

  test("POST with invalid body returns 422 with validation errors") {
    val handler: (Header[BearerToken], Json[ValidatedCommand]) => Eru[Nothing, Ok[String]] =
      (auth, cmd) => { val _ = auth; Eru.succeed(Ok(s"${cmd.name}:${cmd.count}")) }

    val router = Router.builder.post("/commands", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/commands",
        body = Body.text("""{"name":"","count":-1}""", MediaType.applicationJson),
        headerPairs = List("Authorization" -> "Bearer token")
      )
    )

    assertEquals(response.status, StatusCode(422).unsafeRunSync())
    val body = bodyText(response)
    assert(body.contains("Unprocessable Entity"), s"Missing title: $body")
    assert(body.contains("name"), s"Missing field path for name: $body")
    assert(body.contains("count"), s"Missing field path for count: $body")
  }

  test("POST validation errors accumulate across fields") {
    val handler: (Header[BearerToken], Json[ValidatedCommand]) => Eru[Nothing, Ok[String]] =
      (auth, cmd) => { val _ = auth; Eru.succeed(Ok(s"${cmd.name}:${cmd.count}")) }

    val router = Router.builder.post("/commands", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/commands",
        body = Body.text("""{"name":"","count":-1}""", MediaType.applicationJson),
        headerPairs = List("Authorization" -> "Bearer token")
      )
    )

    val body = bodyText(response)
    assert(body.contains("2 validation error"), s"Should have 2 errors: $body")
  }

  // --- Coded body (Content-Type dispatch) ---

  test("Coded body dispatches on Content-Type: JSON, XML, YAML") {
    import net.ghoula.sarati.codec.JsonDecoders.given
    import net.ghoula.sarati.codec.XmlDecoders.given
    import net.ghoula.sarati.codec.YamlDecoders.given

    given Validator[String] with {
      def validate(a: String): ValidationResult[String] = ValidationResult.Valid(a)
    }

    val handler: Coded[String] => Eru[Nothing, Ok[String]] =
      (s: Coded[String]) => Eru.succeed(Ok(s"got:$s"))

    val router = Router.builder.post("/coded", handler).build.getOrElse(fail("build failed"))

    val jsonResp = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/coded",
        body = Body.text("\"hello\"", MediaType.applicationJson),
        headerPairs = List("Content-Type" -> "application/json")
      )
    )
    assertEquals(jsonResp.status, StatusCode.Ok, s"JSON response: ${jsonResp.status} body=${bodyText(jsonResp)}")
    assert(bodyText(jsonResp).contains("hello"), s"JSON body: ${bodyText(jsonResp)}")

    val xmlResp = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/coded",
        body = Body.text("<root>world</root>", MediaType.applicationXml),
        headerPairs = List("Content-Type" -> "application/xml")
      )
    )
    assertEquals(xmlResp.status, StatusCode.Ok)
    assert(bodyText(xmlResp).contains("world"), s"XML body: ${bodyText(xmlResp)}")

    val yamlResp = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/coded",
        body = Body.text("yamlValue"),
        headerPairs = List("Content-Type" -> "application/yaml")
      )
    )
    assertEquals(yamlResp.status, StatusCode.Ok)
    assert(bodyText(yamlResp).contains("yamlValue"), s"YAML body: ${bodyText(yamlResp)}")
  }

  // --- Form body (urlencoded) ---

  test("Form body decodes urlencoded fields") {
    case class LoginForm(username: String, password: String)

    given FormDecoder[LoginForm] with {
      def decode(form: Map[String, String]): Either[Vector[FieldError], LoginForm] =
        (form.get("username"), form.get("password")) match {
          case (Some(u), Some(p)) => Right(LoginForm(u, p))
          case _ => Left(Vector(FieldError("form", "missing username or password", None)))
        }
    }

    given Validator[LoginForm] with {
      def validate(a: LoginForm): ValidationResult[LoginForm] = ValidationResult.Valid(a)
    }

    val handler: Form[LoginForm] => Eru[Nothing, Ok[String]] =
      (f: Form[LoginForm]) => Eru.succeed(Ok(s"user:${f.username}"))

    val router = Router.builder.post("/login", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/login",
        body = Body.text("username=alice&password=secret"),
        headerPairs = List("Content-Type" -> "application/x-www-form-urlencoded")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("user:alice"), s"Form body: ${bodyText(response)}")
  }

  test("Coded body with derived CodedDecoder decodes a case class") {
    import net.ghoula.sarati.codec.JsonDecoders.given
    import net.ghoula.sarati.codec.XmlDecoders.given
    import net.ghoula.sarati.codec.YamlDecoders.given

    case class Payload(name: String, count: Int)

    given CodedDecoder[Payload] = CodedDecoder.derived
    given Validator[Payload] = Validator.derive

    val handler: Coded[Payload] => Eru[Nothing, Ok[String]] =
      (p: Coded[Payload]) => Eru.succeed(Ok(s"${p.name}:${p.count}"))

    val router = Router.builder.post("/payload", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/payload",
        body = Body.text("""{"name":"widget","count":3}""", MediaType.applicationJson),
        headerPairs = List("Content-Type" -> "application/json")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("widget:3"), s"Body: ${bodyText(response)}")
  }

  test("Coded body with unsupported Content-Type returns 415") {
    import net.ghoula.sarati.codec.JsonDecoders.given
    import net.ghoula.sarati.codec.XmlDecoders.given
    import net.ghoula.sarati.codec.YamlDecoders.given

    given Validator[String] with {
      def validate(a: String): ValidationResult[String] = ValidationResult.Valid(a)
    }

    val handler: Coded[String] => Eru[Nothing, Ok[String]] =
      (s: Coded[String]) => Eru.succeed(Ok(s))

    val router = Router.builder.post("/coded", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/coded",
        body = Body.text("whatever"),
        headerPairs = List("Content-Type" -> "text/csv")
      )
    )

    assertEquals(response.status, StatusCode.UnsupportedMediaType)
  }

  test("Form body with derived FormDecoder") {
    case class LoginForm(username: String, count: Int, note: Option[String])

    given FormDecoder[LoginForm] = FormDecoder.derived
    given Validator[LoginForm] with {
      def validate(a: LoginForm): ValidationResult[LoginForm] = ValidationResult.Valid(a)
    }

    val handler: Form[LoginForm] => Eru[Nothing, Ok[String]] =
      (f: Form[LoginForm]) => Eru.succeed(Ok(s"${f.username}:${f.count}:${f.note.getOrElse("none")}"))

    val router = Router.builder.post("/login2", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/login2",
        body = Body.text("username=bob&count=7"),
        headerPairs = List("Content-Type" -> "application/x-www-form-urlencoded")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("bob:7:none"), s"Body: ${bodyText(response)}")
  }

  test("Form body with derived FormDecoder rejects missing required field") {
    case class LoginForm(username: String, count: Int)

    given FormDecoder[LoginForm] = FormDecoder.derived
    given Validator[LoginForm] with {
      def validate(a: LoginForm): ValidationResult[LoginForm] = ValidationResult.Valid(a)
    }

    val handler: Form[LoginForm] => Eru[Nothing, Ok[String]] =
      (f: Form[LoginForm]) => Eru.succeed(Ok(f.username))

    val router = Router.builder.post("/login3", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/login3",
        body = Body.text("username=bob"),
        headerPairs = List("Content-Type" -> "application/x-www-form-urlencoded")
      )
    )

    assertEquals(response.status, StatusCode(422).unsafeRunSync())
  }

  // --- HEAD auto-generation (RFC 9110 9.3.2) ---

  test("HEAD auto-generated from GET returns status and headers without body") {
    val handler: Path[UUID] => Eru[Nothing, Ok[Workspace]] =
      (id: Path[UUID]) => Eru.succeed(Ok(Workspace(id, "name")))

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.HEAD, "/users/550e8400-e29b-41d4-a716-446655440000"))

    assertEquals(response.status, StatusCode.Ok)
    assert(response.body.isEmpty, s"HEAD must have an empty body, got: ${response.body}")
    assert(
      response.headers.contentTypeRaw.exists(_.contains("json")),
      "HEAD must preserve the GET response's Content-Type"
    )
  }

  test("HEAD preserves the GET response's Content-Length") {
    val handler: Path[UUID] => Eru[Nothing, Ok[Workspace]] =
      (id: Path[UUID]) => Eru.succeed(Ok(Workspace(id, "name")))

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(fail("build failed"))
    val path = "/users/550e8400-e29b-41d4-a716-446655440000"

    val getResponse = run(router.toHandler, requestWith(Method.GET, path))
    val headResponse = run(router.toHandler, requestWith(Method.HEAD, path))

    val headLength = headResponse.headers.getFirst("Content-Length").map(_.value.toLong)
    assertEquals(headLength, getResponse.body.contentLength, "HEAD must report the GET body length")
  }

  test("explicit HEAD route serves without body") {
    val handler: () => Eru[Nothing, Ok[String]] =
      () => Eru.succeed(Ok("explicit-head"))

    val router = Router.builder.head("/explicit", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.HEAD, "/explicit"))

    assertEquals(response.status, StatusCode.Ok)
    assert(response.body.isEmpty, s"Explicit HEAD must have an empty body, got: ${response.body}")
  }

  test("405 Allow header advertises HEAD for a GET route") {
    val handler: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"user-${id}"))

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.POST, "/users/550e8400-e29b-41d4-a716-446655440000"))

    assertEquals(response.status, StatusCode.MethodNotAllowed)
    val allow = response.headers.getFirst("Allow").map(_.value).getOrElse("")
    assert(allow.contains("GET"), s"Allow should contain GET: $allow")
    assert(allow.contains("HEAD"), s"Allow should contain HEAD: $allow")
  }

  test("route summary, description, and tags flow into the operation schema") {
    val handler: () => Eru[Nothing, Ok[String]] =
      () => Eru.succeed(Ok("ok"))

    val builder = Router.builder.get(
      "/meta",
      handler,
      summary = "A summary",
      description = "A description",
      tags = "one, two"
    )
    val schema = builder.operationSchemas.head

    assertEquals(schema.summary, Some("A summary"))
    assertEquals(schema.description, Some("A description"))
    assertEquals(schema.tags, Vector("one", "two"))
  }

  // --- Redirect / no-body status ---

  test("SeeOther response sets 303 with Location header") {
    val target = Uri.http("example.org", path = "/elsewhere")
    val handler: () => Eru[Nothing, SeeOther] =
      () => Eru.succeed(SeeOther(target))

    val router = Router.builder.get("/redirect", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/redirect"))

    assertEquals(response.status, StatusCode.SeeOther)
    assert(response.headers.getFirst("Location").exists(_.value.contains("/elsewhere")), "Missing Location header")
  }

  test("NotModified response sets 304") {
    val handler: () => Eru[Nothing, NotModified.type] =
      () => Eru.succeed(NotModified)

    val router = Router.builder.get("/cached", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/cached"))

    assertEquals(response.status, StatusCode.NotModified)
  }

  // --- EventStream / SSE ---

  test("GET returning EventStream produces SSE response") {
    val events = List(
      ServerSentEvent.data("hello"),
      ServerSentEvent.data("world")
    )
    val handler: Path[UUID] => Eru[Nothing, EventStream[ChunkStream]] =
      (_: Path[UUID]) => {
        val stream = ServerSentEvent.toChunkStream(events)
        Eru.succeed(EventStream(stream))
      }

    val router = Router.builder.get("/events/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/events/550e8400-e29b-41d4-a716-446655440000"))

    assertEquals(response.status, StatusCode.Ok)
    val contentType = response.headers.contentTypeRaw
    assert(contentType.exists(_.contains("event-stream")), s"Expected text/event-stream, got: $contentType")
  }

  test("EventStream body contains SSE-formatted events") {
    val events = List(
      ServerSentEvent.data("event-one"),
      ServerSentEvent.event("update", "event-two")
    )
    val handler: () => Eru[Nothing, EventStream[ChunkStream]] =
      () => Eru.succeed(EventStream(ServerSentEvent.toChunkStream(events)))

    val router = Router.builder.get("/feed", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/feed"))

    assertEquals(response.status, StatusCode.Ok)
    val bodyContent = response.body match {
      case s: Body.Stream => s.asString().unsafeRunSync()
      case Body.Text(text, _, _) => text
      case other => fail(s"Expected streaming body, got: $other"); ""
    }
    assert(bodyContent.contains("data: event-one"), s"Missing first event: $bodyContent")
    assert(bodyContent.contains("data: event-two"), s"Missing second event: $bodyContent")
    assert(bodyContent.contains("event: update"), s"Missing event type: $bodyContent")
  }
}
