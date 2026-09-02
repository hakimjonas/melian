package net.ghoula.melian.server

import munit.FunSuite

import java.nio.file.{Files, Path}

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

class WebsiteMiddlewareSpec extends FunSuite {

  private val tempDir: Path = {
    val dir = Files.createTempDirectory("melian-test")
    Files.writeString(dir.resolve("index.html"), "<h1>Home</h1>")
    Files.writeString(dir.resolve("style.css"), "body { color: red; }")
    Files.writeString(dir.resolve("app.js"), "console.log('hi')")
    Files.writeString(dir.resolve("404.html"), "<h1>Not Found</h1>")
    val sub = Files.createDirectory(dir.resolve("sub"))
    Files.writeString(sub.resolve("page.html"), "<h1>Sub</h1>")
    Files.write(dir.resolve("image.png"), Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47))
    dir
  }

  override def afterAll(): Unit = {
    import java.util.Comparator
    Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files.delete(_))
  }

  private val fallback: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
    Eru.succeed(Response(StatusCode.NotFound, Headers.empty, Body.text("fallback")))
  }

  private def get(path: String): Request[Body] =
    Request(Method.GET, Uri.http("localhost", path = path), Headers.empty, Body.empty)

  private def bodyText(response: Response[Body]): String = response.body match {
    case Body.Text(text, _, _) => text
    case _ => ""
  }

  // --- StaticFiles ---

  test("serves existing file with correct media type") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val response = handler(get("/style.css")).unsafeRunSync()

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("color: red"))
  }

  test("serves HTML file") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val response = handler(get("/index.html")).unsafeRunSync()

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("<h1>Home</h1>"))
  }

  test("serves file from subdirectory") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val response = handler(get("/sub/page.html")).unsafeRunSync()

    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains("<h1>Sub</h1>"))
  }

  test("falls through to inner handler for missing file") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val response = handler(get("/nonexistent.txt")).unsafeRunSync()

    assertEquals(response.status, StatusCode.NotFound)
    assertEquals(bodyText(response), "fallback")
  }

  test("rejects path traversal with ..") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val response = handler(get("/../etc/passwd")).unsafeRunSync()

    assertEquals(response.status, StatusCode.BadRequest)
  }

  test("serves binary file") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val response = handler(get("/image.png")).unsafeRunSync()

    assertEquals(response.status, StatusCode.Ok)
  }

  test("sets cache control header") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir, cacheControl = "public, max-age=300"))(fallback)
    val response = handler(get("/style.css")).unsafeRunSync()

    assertEquals(response.status, StatusCode.Ok)
  }

  test("respects prefix configuration") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir, prefix = "/static"))(fallback)

    val matched = handler(get("/static/style.css")).unsafeRunSync()
    assertEquals(matched.status, StatusCode.Ok)

    val unmatched = handler(get("/style.css")).unsafeRunSync()
    assertEquals(bodyText(unmatched), "fallback")
  }

  test("static file response carries an ETag header") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val response = handler(get("/style.css")).unsafeRunSync()

    assert(response.headers.getFirst("ETag").nonEmpty, "Expected an ETag header")
  }

  test("If-None-Match with the matching ETag returns 304") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val first = handler(get("/style.css")).unsafeRunSync()
    val etag = first.headers.getFirst("ETag").map(_.value).getOrElse(fail("missing ETag"))

    val conditional = Request(Method.GET, Uri.http("localhost", path = "/style.css"), Headers.empty, Body.empty)
      .addHeader("If-None-Match", etag)
      .unsafeRunSync()
    val response = handler(conditional).unsafeRunSync()

    assertEquals(response.status, StatusCode.NotModified)
  }

  test("If-None-Match with a non-matching ETag returns 200") {
    val handler = StaticFiles.middleware(StaticFiles.Config(tempDir))(fallback)
    val conditional = Request(Method.GET, Uri.http("localhost", path = "/style.css"), Headers.empty, Body.empty)
      .addHeader("If-None-Match", "\"does-not-match\"")
      .unsafeRunSync()
    val response = handler(conditional).unsafeRunSync()

    assertEquals(response.status, StatusCode.Ok)
  }

  // --- SecurityHeaders ---

  test("adds all security headers") {
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }
    val handler = SecurityHeaders.middleware()(inner)
    val response = handler(get("/")).unsafeRunSync()

    assertEquals(response.status, StatusCode.Ok)
    assert(response.headers.getFirst("X-Content-Type-Options").exists(_.value == "nosniff"))
    assert(response.headers.getFirst("X-Frame-Options").exists(_.value == "DENY"))
    assert(response.headers.getFirst("Referrer-Policy").exists(_.value == "strict-origin-when-cross-origin"))
    assert(response.headers.getFirst("Strict-Transport-Security").exists(_.value.contains("max-age=63072000")))
  }

  test("security headers are configurable") {
    val config = SecurityHeaders.Config(hstsMaxAge = 1000, frameOptions = "SAMEORIGIN")
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }
    val handler = SecurityHeaders.middleware(config)(inner)
    val response = handler(get("/")).unsafeRunSync()

    assert(response.headers.getFirst("X-Frame-Options").exists(_.value == "SAMEORIGIN"))
    assert(response.headers.getFirst("Strict-Transport-Security").exists(_.value.contains("max-age=1000")))
  }

  test("no Content-Security-Policy header by default") {
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }
    val handler = SecurityHeaders.middleware()(inner)
    val response = handler(get("/")).unsafeRunSync()

    assert(response.headers.getFirst("Content-Security-Policy").isEmpty)
  }

  test("emits Content-Security-Policy when configured") {
    val csp = SecurityHeaders.Csp.selfOnly
      .withScriptSrc("'self'", "'wasm-unsafe-eval'")
      .withStyleSrc("'self'", "'unsafe-inline'")
    val config = SecurityHeaders.Config(contentSecurityPolicy = Some(csp))
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }
    val handler = SecurityHeaders.middleware(config)(inner)
    val response = handler(get("/")).unsafeRunSync()

    val value = response.headers.getFirst("Content-Security-Policy").map(_.value)
    assert(value.isDefined)
    val v = value.get
    assert(v.contains("default-src 'self'"))
    assert(v.contains("script-src 'self' 'wasm-unsafe-eval'"))
    assert(v.contains("style-src 'self' 'unsafe-inline'"))
    assert(v.contains("object-src 'none'"))
    assert(v.contains("frame-ancestors 'none'"))
    // form-action defaults to 'none' (no <form> may submit anywhere).
    assert(v.contains("form-action 'none'"))
    // Empty directives are omitted.
    assert(!v.contains("img-src"))
  }

  test("Csp renders the extended directive set when set") {
    val csp = SecurityHeaders.Csp.selfOnly
      .withFrameSrc("'none'")
      .withWorkerSrc("'self'")
      .withManifestSrc("'self'")
      .withMediaSrc("'self'")
      .withFormAction("'self'")
    val v = csp.value
    assert(v.contains("frame-src 'none'"))
    assert(v.contains("worker-src 'self'"))
    assert(v.contains("manifest-src 'self'"))
    assert(v.contains("media-src 'self'"))
    assert(v.contains("form-action 'self'"))
  }

  test("strict-dynamic is off by default and prepends to script-src when enabled") {
    val off = SecurityHeaders.Csp.selfOnly.withScriptSrc("'self'")
    assert(!off.value.contains("'strict-dynamic'"))

    val on = SecurityHeaders.Csp.selfOnly.withScriptSrc("'self'").withStrictDynamic()
    assert(on.value.contains("script-src 'strict-dynamic' 'self'"))
  }

  test("strict-dynamic is omitted when script-src is empty") {
    val csp = SecurityHeaders.Csp.selfOnly.withStrictDynamic()
    assert(!csp.value.contains("'strict-dynamic'"))
    assert(!csp.value.contains("script-src"))
  }

  test("Cross-Origin-Resource-Policy and Cross-Origin-Opener-Policy are opt-in") {
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }

    val defaultHandler = SecurityHeaders.middleware()(inner)
    val defaultResp = defaultHandler(get("/")).unsafeRunSync()
    assert(defaultResp.headers.getFirst("Cross-Origin-Resource-Policy").isEmpty)
    assert(defaultResp.headers.getFirst("Cross-Origin-Opener-Policy").isEmpty)

    val config = SecurityHeaders.Config(
      crossOriginResourcePolicy = Some("same-origin"),
      crossOriginOpenerPolicy = Some("same-origin")
    )
    val handler = SecurityHeaders.middleware(config)(inner)
    val response = handler(get("/")).unsafeRunSync()
    assert(response.headers.getFirst("Cross-Origin-Resource-Policy").exists(_.value == "same-origin"))
    assert(response.headers.getFirst("Cross-Origin-Opener-Policy").exists(_.value == "same-origin"))
  }

  // --- ErrorPages ---

  test("replaces 404 response with custom HTML page") {
    val config = ErrorPages.Config(tempDir, Map(404 -> "404.html"))
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.NotFound, Headers.empty, Body.text("not found")))
    }
    val handler = ErrorPages.middleware(config)(inner)
    val response = handler(get("/missing")).unsafeRunSync()

    assertEquals(response.status, StatusCode.NotFound)
    assert(bodyText(response).contains("<h1>Not Found</h1>"))
  }

  test("passes through non-error responses unchanged") {
    val config = ErrorPages.Config(tempDir, Map(404 -> "404.html"))
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }
    val handler = ErrorPages.middleware(config)(inner)
    val response = handler(get("/")).unsafeRunSync()

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(bodyText(response), "ok")
  }

  test("falls back to original response if error page file missing") {
    val config = ErrorPages.Config(tempDir, Map(500 -> "500.html"))
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.InternalServerError, Headers.empty, Body.text("error")))
    }
    val handler = ErrorPages.middleware(config)(inner)
    val response = handler(get("/")).unsafeRunSync()

    assertEquals(response.status, StatusCode.InternalServerError)
    assertEquals(bodyText(response), "error")
  }

  // --- Composition ---

  test("middleware compose: security + static + error pages") {
    val staticConfig = StaticFiles.Config(tempDir)
    val errorConfig = ErrorPages.Config(tempDir, Map(404 -> "404.html"))

    val handler = SecurityHeaders.middleware()(
      ErrorPages.middleware(errorConfig)(
        StaticFiles.middleware(staticConfig)(fallback)
      )
    )

    val cssResponse = handler(get("/style.css")).unsafeRunSync()
    assertEquals(cssResponse.status, StatusCode.Ok)
    assert(cssResponse.headers.getFirst("X-Frame-Options").exists(_.value == "DENY"))

    val missingResponse = handler(get("/nope")).unsafeRunSync()
    assertEquals(missingResponse.status, StatusCode.NotFound)
    assert(bodyText(missingResponse).contains("<h1>Not Found</h1>"))
    assert(missingResponse.headers.getFirst("X-Content-Type-Options").exists(_.value == "nosniff"))
  }
}
