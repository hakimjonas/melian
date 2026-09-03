package net.ghoula.melian.router

import munit.FunSuite

import java.util.UUID
import scala.concurrent.duration.*

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.melian.*
import net.ghoula.melian.extraction.FormDecoder
import net.ghoula.melian.extraction.FromHeader.Accept
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.ast.xml.XmlNode
import net.ghoula.sarati.ast.yaml.YamlValue
import net.ghoula.sarati.codec.{Decoder, Encoder}
import net.ghoula.valar.Validator

class RouterFeatureSpec extends FunSuite {

  given Encoder[String, JsonValue] with {
    def encode(value: String): JsonValue = JsonValue.Str(value)
  }

  import SaratiBridge.given

  private def run(handler: Request[Body] => Eru[HttpError, Response[Body]], request: Request[Body]): Response[Body] =
    handler(request).unsafeRunSync()

  private def bodyText(response: Response[Body]): String = response.body match {
    case Body.Text(text, _, _) => text
    case other => fail(s"Expected Text body, got: $other"); ""
  }

  private def header(response: Response[Body], name: String): Option[String] =
    response.headers.getFirst(name).map(_.value)

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

  // --- Unauthorized / TooManyRequests / Status ---

  test("Unauthorized renders 401 with WWW-Authenticate and an encoded body") {
    val handler: Path[String] => Eru[Nothing, Unauthorized[String]] =
      (user: Path[String]) => Eru.succeed(Unauthorized("Bearer realm=\"api\"", s"denied:$user"))

    val router = Router.builder.get("/secure/:user", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/secure/alice"))

    assertEquals(response.status, StatusCode.Unauthorized)
    assertEquals(header(response, "WWW-Authenticate"), Some("Bearer realm=\"api\""))
    assert(bodyText(response).contains("denied:alice"), s"Body: ${bodyText(response)}")
  }

  test("TooManyRequests renders 429 with Retry-After in seconds") {
    val handler: () => Eru[Nothing, TooManyRequests[String]] =
      () => Eru.succeed(TooManyRequests(90.seconds, "slow down"))

    val router = Router.builder.get("/limited", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/limited"))

    assertEquals(response.status, StatusCode.TooManyRequests)
    assertEquals(header(response, "Retry-After"), Some("90"))
    assert(bodyText(response).contains("slow down"))
  }

  test("Status[203, A] renders the phantom status code with a body") {
    val handler: () => Eru[Nothing, Status[203, String]] =
      () => Eru.succeed(Status[203, String]("non-authoritative"))

    val router = Router.builder.get("/partial", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/partial"))

    assertEquals(response.status.value, 203)
    assert(bodyText(response).contains("non-authoritative"))
  }

  test("Status with a body-forbidden code does not compile") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.Encoder
      import net.ghoula.melian.router.SaratiBridge.given

      given Encoder[String, JsonValue] = ???

      Router.builder.get("/x", () => Eru.succeed(Status[204, String]("no")))
    """)
    assert(errors.replaceAll("\\s+", " ").contains("does not allow a response body"), s"Errors: $errors")
  }

  test("Status with a header-requiring code does not compile") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.Encoder
      import net.ghoula.melian.router.SaratiBridge.given

      given Encoder[String, JsonValue] = ???

      Router.builder.get("/x", () => Eru.succeed(Status[401, String]("no")))
    """)
    assert(errors.replaceAll("\\s+", " ").contains("dedicated response wrapper"), s"Errors: $errors")
  }

  // --- Warnings ---

  case class PartialCmd(name: String)

  given Decoder[JsonValue, PartialCmd] with {
    def decode(value: JsonValue): net.ghoula.sarati.Result[net.ghoula.sarati.DecodeError, PartialCmd] =
      value match {
        case JsonValue.Object(fields) =>
          fields.get("name") match {
            case Some(JsonValue.Str(n)) =>
              net.ghoula.sarati.Result.Partial(
                PartialCmd(n),
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

  given Validator[PartialCmd] = net.ghoula.valar.Validator.derive

  // The JSON decoder above is the one under test; XML and YAML are stubs for the bundle.
  given Decoder[XmlNode, PartialCmd] with {
    def decode(value: XmlNode): net.ghoula.sarati.Result[net.ghoula.sarati.DecodeError, PartialCmd] =
      net.ghoula.sarati.Result.Failure(
        List(net.ghoula.sarati.DecodeError.Custom("unused in this spec", (line = 1, column = 1, offset = 0))),
        (line = 1, column = 1, offset = 0)
      )
  }

  given Decoder[YamlValue, PartialCmd] with {
    def decode(value: YamlValue): net.ghoula.sarati.Result[net.ghoula.sarati.DecodeError, PartialCmd] =
      net.ghoula.sarati.Result.Failure(
        List(net.ghoula.sarati.DecodeError.Custom("unused in this spec", (line = 1, column = 1, offset = 0))),
        (line = 1, column = 1, offset = 0)
      )
  }

  given CodedDecoder[PartialCmd] = CodedDecoder.fromDecoders(summon[Decoder[JsonValue, PartialCmd]], summon, summon)

  test("Coded body decode warnings surface via X-Melian-Warnings") {
    val handler: Coded[PartialCmd] => Eru[Nothing, Ok[String]] =
      (cmd: Coded[PartialCmd]) => Eru.succeed(Ok(cmd.name))

    val router = Router.builder.post("/coded-warn", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/coded-warn",
        body = Body.text("""{"name":"x"}""", MediaType.applicationJson),
        headerPairs = List("Content-Type" -> "application/json")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(header(response, MelianHeaders.Warnings), Some("1 decode warning"))
  }

  test("Coded body warnings reach Endpoint handlers through RequestContext") {
    val handler: Coded[PartialCmd] => Endpoint[Nothing, Ok[String]] =
      (cmd: Coded[PartialCmd]) => {
        val ctx = summon[RequestContext]
        Eru.succeed(Ok(s"warnings:${ctx.warnings.size}:${cmd.name}"))
      }

    val router = Router.builder.post("/coded-ctx", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/coded-ctx",
        body = Body.text("""{"name":"x"}""", MediaType.applicationJson),
        headerPairs = List("Content-Type" -> "application/json")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("warnings:1:x"), s"Expected one warning, got: ${bodyText(response)}")
  }

  test("Form unknown fields surface as warnings") {
    case class LoginForm(username: String)

    given FormDecoder[LoginForm] = FormDecoder.derived
    given Validator[LoginForm] with {
      def validate(a: LoginForm): net.ghoula.valar.ValidationResult[LoginForm] =
        net.ghoula.valar.ValidationResult.Valid(a)
    }

    val handler: Form[LoginForm] => Eru[Nothing, Ok[String]] =
      (f: Form[LoginForm]) => Eru.succeed(Ok(f.username))

    val router = Router.builder.post("/form-warn", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/form-warn",
        body = Body.text("username=alice&extra=1&other=2"),
        headerPairs = List("Content-Type" -> "application/x-www-form-urlencoded")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(header(response, MelianHeaders.Warnings), Some("2 decode warnings"))
  }

  // --- Strict JSON parsing ---

  test("Strict[Json[A]] rejects a partially-parsed body") {
    val handler: Strict[Json[PartialCmd]] => Eru[Nothing, Ok[String]] =
      (cmd: Strict[Json[PartialCmd]]) => Eru.succeed(Ok(cmd.name))

    val router = Router.builder.post("/strict", handler).build.getOrElse(fail("build failed"))

    // The decoder returns Partial for a valid "name" plus an unknown-field error; strict mode
    // must reject what the resilient default accepts with a warning.
    val response = run(
      router.toHandler,
      requestWith(Method.POST, "/strict", body = Body.text("""{"name":"x"}""", MediaType.applicationJson))
    )

    assertEquals(response.status, StatusCode.BadRequest)
    assert(bodyText(response).contains("Bad Request"), s"Body: ${bodyText(response)}")
  }

  test("Strict[Coded[A]] rejects a body the decoder only recovers partially") {
    val handler: Strict[Coded[PartialCmd]] => Eru[Nothing, Ok[String]] =
      (cmd: Strict[Coded[PartialCmd]]) => Eru.succeed(Ok(cmd.name))

    val router = Router.builder.post("/strict-coded", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/strict-coded",
        body = Body.text("""{"name":"x"}""", MediaType.applicationJson),
        headerPairs = List("Content-Type" -> "application/json")
      )
    )

    assertEquals(response.status, StatusCode.BadRequest)
  }

  test("Json[A] resilient default accepts a partially-parsed body with a warning") {
    val handler: Json[PartialCmd] => Eru[Nothing, Ok[String]] =
      (cmd: Json[PartialCmd]) => Eru.succeed(Ok(cmd.name))

    val router = Router.builder.post("/resilient", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(Method.POST, "/resilient", body = Body.text("""{"name":"x"}""", MediaType.applicationJson))
    )

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(header(response, MelianHeaders.Warnings), Some("1 decode warning"))
  }

  // --- Response content negotiation ---

  case class Widget(id: Int, name: String)

  import net.ghoula.sarati.codec.{JsonEncoders, XmlEncoders, YamlEncoders}
  import JsonEncoders.given
  import XmlEncoders.given
  import YamlEncoders.given

  given Encoder[Widget, JsonValue] = Encoder.derived
  given Encoder[Widget, XmlNode] = Encoder.derived
  given Encoder[Widget, YamlValue] = Encoder.derived

  private def widgetHandler: (Header[Accept], Path[Int]) => Eru[Nothing, Ok[Widget]] =
    (_, id: Path[Int]) => Eru.succeed(Ok(Widget(id, "wrench")))

  test("Header[Accept] negotiates XML when an XmlNode encoder exists") {
    val router = Router.builder.get("/widgets/:id", widgetHandler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(Method.GET, "/widgets/7", headerPairs = List("Accept" -> "application/xml"))
    )

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(header(response, "Content-Type").map(_.takeWhile(c => c != ';')), Some("application/xml"))
    assert(bodyText(response).contains("wrench"), s"Body: ${bodyText(response)}")
  }

  test("Header[Accept] negotiates YAML and honors q-values") {
    val router = Router.builder.get("/widgets/:id", widgetHandler).build.getOrElse(fail("build failed"))

    val yamlResponse = run(
      router.toHandler,
      requestWith(Method.GET, "/widgets/7", headerPairs = List("Accept" -> "application/yaml"))
    )
    assertEquals(yamlResponse.status, StatusCode.Ok)
    assert(bodyText(yamlResponse).contains("wrench"), s"YAML body: ${bodyText(yamlResponse)}")

    // Both offered; YAML has the higher q-value, so it must win over JSON.
    val qResponse = run(
      router.toHandler,
      requestWith(
        Method.GET,
        "/widgets/7",
        headerPairs = List("Accept" -> "application/json;q=0.2, application/yaml;q=0.9")
      )
    )
    assertEquals(header(qResponse, "Content-Type").map(_.takeWhile(c => c != ';')), Some("application/yaml"))

    // Equal q-values keep the server preference: JSON first.
    val tieResponse = run(
      router.toHandler,
      requestWith(
        Method.GET,
        "/widgets/7",
        headerPairs = List("Accept" -> "application/yaml;q=0.5, application/json;q=0.5")
      )
    )
    assertEquals(header(tieResponse, "Content-Type").map(_.takeWhile(c => c != ';')), Some("application/json"))
  }

  test("routes without Header[Accept] keep encoding JSON") {
    val handler: Path[Int] => Eru[Nothing, Ok[Widget]] =
      (id: Path[Int]) => Eru.succeed(Ok(Widget(id, "wrench")))

    val router = Router.builder.get("/plain/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(router.toHandler, requestWith(Method.GET, "/plain/7"))

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(header(response, "Content-Type").map(_.takeWhile(c => c != ';')), Some("application/json"))
  }

  test("an Accept header matching no supported type answers 406 problem+json") {
    val router = Router.builder.get("/widgets/:id", widgetHandler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(Method.GET, "/widgets/7", headerPairs = List("Accept" -> "text/csv"))
    )

    assertEquals(response.status.value, 406)
    assert(bodyText(response).contains("Not Acceptable"), s"Body: ${bodyText(response)}")
  }

  test("negotiating routes document every offered media type") {
    val builder = Router.builder.get("/widgets/:id", widgetHandler)
    val schema = builder.operationSchemas.head

    assertEquals(schema.responseMediaTypes, Vector("application/json", "application/xml", "application/yaml"))
  }

  // --- ErrorRenderer tiers ---

  enum AdminError derives CanEqual {
    case Denied
  }

  test("builder-level errorRenderer renders domain errors without a given") {
    val handler: Path[String] => Eru[AdminError, Ok[String]] =
      (_: Path[String]) => Eru.fail(AdminError.Denied)

    val router = Router.builder.errorRenderer { case _: AdminError.Denied.type =>
      Eru.succeed(Response(StatusCode.Forbidden, Headers.empty, Body.text("admin denied")))
    }
      .get("/admin/:id", handler)
      .build
      .getOrElse(fail("build failed"))

    val response = run(router.toHandler, requestWith(Method.GET, "/admin/1"))
    assertEquals(response.status, StatusCode.Forbidden)
    assertEquals(bodyText(response), "admin denied")
  }

  test("an ErrorRenderer given beats the builder-level renderer") {
    given ErrorRenderer[AdminError] with {
      def render(error: AdminError): Eru[Nothing, Response[Body]] = error match {
        case AdminError.Denied =>
          Eru.succeed(Response(StatusCode.Unauthorized, Headers.empty, Body.text("given wins")))
      }
    }

    val handler: Path[String] => Eru[AdminError, Ok[String]] =
      (_: Path[String]) => Eru.fail(AdminError.Denied)

    val router = Router.builder.errorRenderer { case _: AdminError.Denied.type =>
      Eru.succeed(Response(StatusCode.Forbidden, Headers.empty, Body.text("builder wins")))
    }
      .get("/admin/:id", handler)
      .build
      .getOrElse(fail("build failed"))

    val response = run(router.toHandler, requestWith(Method.GET, "/admin/1"))
    assertEquals(bodyText(response), "given wins")
  }

  test("an unclaimed domain error falls back to a generic problem-details 500") {
    enum OtherError derives CanEqual {
      case Boom
    }

    val handler: Path[String] => Eru[OtherError, Ok[String]] =
      (_: Path[String]) => Eru.fail(OtherError.Boom)

    val router = Router.builder.errorRenderer { case _: AdminError.Denied.type =>
      Eru.succeed(Response(StatusCode.Forbidden, Headers.empty, Body.text("admin denied")))
    }
      .get("/other/:id", handler)
      .build
      .getOrElse(fail("build failed"))

    val response = run(router.toHandler, requestWith(Method.GET, "/other/1"))
    assertEquals(response.status, StatusCode.InternalServerError)
    assert(bodyText(response).contains("Internal Server Error"), s"Body: ${bodyText(response)}")
    assert(
      !bodyText(response).contains("Boom"),
      s"The fallback must not leak the error value: ${bodyText(response)}"
    )
  }

  // --- operationId ---

  test("explicit operationId flows into the operation schema") {
    val handler: () => Eru[Nothing, Ok[String]] = () => Eru.succeed(Ok("ok"))
    val builder = Router.builder.get("/things", handler, operationId = "listThings")
    assertEquals(builder.operationSchemas.head.operationId, Some("listThings"))
  }

  test("a route UUID path param keeps extracting after a Status response exists elsewhere") {
    // Guard against regressions in the fast path when Status routes share a router.
    val statusHandler: () => Eru[Nothing, Status[203, String]] = () => Eru.succeed(Status[203, String]("p"))
    val pathHandler: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"user-$id"))

    val router = Router.builder
      .get("/partial", statusHandler)
      .get("/users/:id", pathHandler)
      .build
      .getOrElse(fail("build failed"))

    val ok = run(
      router.toHandler,
      requestWith(Method.GET, "/users/550e8400-e29b-41d4-a716-446655440000")
    )
    assertEquals(ok.status, StatusCode.Ok)
    assert(bodyText(ok).contains("user-550e8400"))
  }

  // --- Review fixes: negotiation precedence, q=0, form warnings, cursor scope ---

  test("a specific Accept range beats an equally-preferred wildcard (RFC 9110 12.5.2)") {
    val router = Router.builder.get("/widgets/:id", widgetHandler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(Method.GET, "/widgets/7", headerPairs = List("Accept" -> "application/*, application/xml"))
    )

    assertEquals(header(response, "Content-Type").map(_.takeWhile(_ != ';')), Some("application/xml"))
  }

  test("Accept q=0 means unacceptable and answers 406") {
    val router = Router.builder.get("/widgets/:id", widgetHandler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(Method.GET, "/widgets/7", headerPairs = List("Accept" -> "application/json;q=0"))
    )

    assertEquals(response.status.value, 406)
  }

  test("Header[Accept] negotiates Created responses alongside the Location header") {
    val handler: (Header[Accept], Path[Int]) => Eru[Nothing, Created[Widget]] =
      (_, id: Path[Int]) => Eru.succeed(Created(Widget(id, "wrench")))

    val router = Router.builder.get("/created/:id", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(Method.GET, "/created/7", headerPairs = List("Accept" -> "application/xml"))
    )

    assertEquals(response.status, StatusCode.Created)
    assertEquals(header(response, "Content-Type").map(_.takeWhile(_ != ';')), Some("application/xml"))
    assert(header(response, "Location").isDefined, "Created must keep its Location header")
    assert(bodyText(response).contains("wrench"))
  }
  test("EventStream responses carry the warnings header when the body decoded with warnings") {
    val handler: Json[PartialCmd] => Eru[Nothing, EventStream[ServerSentEvent]] =
      (_: Json[PartialCmd]) =>
        Eru.succeed(EventStream(EventSource.fromServerSentEvents(List(ServerSentEvent.data("hi")))))

    val router = Router.builder.post("/sse-warn", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(Method.POST, "/sse-warn", body = Body.text("""{"name":"x"}""", MediaType.applicationJson))
    )

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(header(response, MelianHeaders.Warnings), Some("1 decode warning"))
    assert(response.headers.contentTypeRaw.exists(_.contains("event-stream")))
  }

  test("hand-written FormDecoders do not emit unknown-field warnings") {
    case class ManualForm(a: String)

    given FormDecoder[ManualForm] with {
      def decode(form: Map[String, String]): Either[Vector[FieldError], ManualForm] =
        form.get("a").map(ManualForm(_)).toRight(Vector(FieldError("a", "missing", None)))
    }
    given net.ghoula.valar.Validator[ManualForm] with {
      def validate(a: ManualForm): net.ghoula.valar.ValidationResult[ManualForm] =
        net.ghoula.valar.ValidationResult.Valid(a)
    }

    val handler: Form[ManualForm] => Eru[Nothing, Ok[String]] =
      (f: Form[ManualForm]) => Eru.succeed(Ok(f.a))

    val router = Router.builder.post("/manual-form", handler).build.getOrElse(fail("build failed"))
    val response = run(
      router.toHandler,
      requestWith(
        Method.POST,
        "/manual-form",
        body = Body.text("a=1&b=2"),
        headerPairs = List("Content-Type" -> "application/x-www-form-urlencoded")
      )
    )

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(header(response, MelianHeaders.Warnings), None, "Decoders without knownFields opt out of the check")
  }

  test("the builder errorRenderer is a cursor: routes registered before it keep the fallback") {
    enum ScopedError derives CanEqual {
      case Denied
    }

    val failing: Path[String] => Eru[ScopedError, Ok[String]] =
      (_: Path[String]) => Eru.fail(ScopedError.Denied)

    val router = Router.builder
      .get("/before/:id", failing)
      .errorRenderer { case _: ScopedError.Denied.type =>
        Eru.succeed(Response(StatusCode.Forbidden, Headers.empty, Body.text("scoped")))
      }
      .get("/after/:id", failing)
      .build
      .getOrElse(fail("build failed"))

    val before = run(router.toHandler, requestWith(Method.GET, "/before/1"))
    assertEquals(before.status, StatusCode.InternalServerError, "No renderer was active for the earlier route")
    val after = run(router.toHandler, requestWith(Method.GET, "/after/1"))
    assertEquals(after.status, StatusCode.Forbidden)
  }

  test("Status with an out-of-range code does not compile") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.melian.*
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.Encoder
      import net.ghoula.melian.router.SaratiBridge.given
      import net.ghoula.eru.http.*

      given Encoder[String, JsonValue] = ???

      Router.builder.get("/x", () => Eru.succeed(Status[600, String]("no")))
    """)
    assert(errors.replaceAll("\\s+", " ").contains("between 100 and 599"), s"Errors: $errors")
  }
}
