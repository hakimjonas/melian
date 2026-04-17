package net.ghoula.melian.router

import munit.FunSuite
import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.melian.*
import net.ghoula.melian.extraction.FromHeader.BearerToken
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.{Decoder, Encoder}

import java.util.UUID

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
                (line = 1, column = 1, offset = 0))
          }
        case _ =>
          net.ghoula.sarati.Result.Failure(
            List(net.ghoula.sarati.DecodeError.TypeMismatch("Object", "other", (line = 1, column = 1, offset = 0))),
            (line = 1, column = 1, offset = 0))
      }
  }

  given Encoder[Workspace, JsonValue] with {
    def encode(value: Workspace): JsonValue = JsonValue.Object(Map(
      "id" -> JsonValue.Str(value.id.toString),
      "name" -> JsonValue.Str(value.name)
    ))
  }

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
    headerPairs.foldLeft[Eru[Any, Request[Body]]](Eru.succeed(base)) { case (acc, (name, value)) =>
      acc.flatMap(_.addHeader(name, value))
    }.unsafeRunSync()
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
      .build.getOrElse(fail("build failed"))

    val userBody = bodyText(run(router.toHandler, requestWith(Method.GET, "/users/550e8400-e29b-41d4-a716-446655440000")))
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

    val response = run(router.toHandler, requestWith(
      Method.POST, s"/workspaces/$testId",
      body = Body.text("""{"name":"My Workspace"}""", MediaType.applicationJson),
      headerPairs = List("Authorization" -> "Bearer test-token-123")
    ))

    assertEquals(response.status, StatusCode.Ok)
    val body = bodyText(response)
    assert(body.contains(testId), s"Missing ID: $body")
    assert(body.contains("My Workspace"), s"Missing name: $body")
  }

  test("POST with missing Authorization header returns error") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(router.toHandler, requestWith(
      Method.POST, "/workspaces/550e8400-e29b-41d4-a716-446655440000",
      body = Body.text("""{"name":"test"}""", MediaType.applicationJson)
    ))

    assert(response.status.value >= 400, s"Expected error, got: ${response.status}")
  }

  test("POST with malformed JSON body returns error") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(router.toHandler, requestWith(
      Method.POST, "/workspaces/550e8400-e29b-41d4-a716-446655440000",
      body = Body.text("not json", MediaType.applicationJson),
      headerPairs = List("Authorization" -> "Bearer token")
    ))

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
        Eru.succeed(Response(StatusCode.NotFound, Headers.empty, Body.text(s"""{"error":"not found","id":"$id"}""", MediaType.applicationJson)))
      case DomainError.Forbidden(reason) =>
        Eru.succeed(Response(StatusCode.Forbidden, Headers.empty, Body.text(s"""{"error":"forbidden","reason":"$reason"}""", MediaType.applicationJson)))
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

  test("POST with missing header AND malformed body accumulates both errors") {
    val handler: (Path[UUID], Header[BearerToken], Json[CreateCommand]) => Eru[Nothing, Ok[Workspace]] =
      (id, auth, cmd) => { val _ = auth; Eru.succeed(Ok(Workspace(id, cmd.name))) }

    val router = Router.builder.post("/workspaces/:id", handler).build.getOrElse(fail("build failed"))

    val response = run(router.toHandler, requestWith(
      Method.POST, "/workspaces/550e8400-e29b-41d4-a716-446655440000",
      body = Body.text("not json", MediaType.applicationJson)
    ))

    assertEquals(response.status, StatusCode.BadRequest)
    val body = bodyText(response)
    assert(body.contains("Authorization") || body.contains("header"), s"Should mention missing header: $body")
    assert(body.contains("parse") || body.contains("Decode") || body.contains("decode"), s"Should mention body error: $body")
  }
}
