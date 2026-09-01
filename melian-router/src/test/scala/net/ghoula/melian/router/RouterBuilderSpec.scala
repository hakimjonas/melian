package net.ghoula.melian.router

import munit.FunSuite

import java.util.UUID

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.melian.*
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
    // requestId is a UUID — check it's present (36 chars with dashes)
    assert(body.length > 40, s"Body too short to contain requestId: $body")
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
