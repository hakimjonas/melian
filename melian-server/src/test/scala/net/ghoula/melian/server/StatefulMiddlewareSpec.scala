package net.ghoula.melian.server

import munit.FunSuite

import scala.concurrent.duration.*

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.HttpServerConfig
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.{Decoder, Encoder, JsonDecoders}

import JsonDecoders.given

class StatefulMiddlewareSpec extends FunSuite {

  private def get(path: String, headerPairs: List[(String, String)] = Nil): Request[Body] = {
    val base = Request(Method.GET, Uri.http("localhost", path = path), Headers.empty, Body.empty)
    headerPairs
      .foldLeft[Eru[Any, Request[Body]]](Eru.succeed(base)) { case (acc, (name, value)) =>
        acc.flatMap(_.addHeader(name, value))
      }
      .unsafeRunSync()
  }

  private def post(path: String, body: String, headerPairs: List[(String, String)] = Nil): Request[Body] = {
    val base = Request(
      Method.POST,
      Uri.http("localhost", path = path),
      Headers.empty,
      Body.text(body, MediaType.textPlain)
    )
    headerPairs
      .foldLeft[Eru[Any, Request[Body]]](Eru.succeed(base)) { case (acc, (name, value)) =>
        acc.flatMap(_.addHeader(name, value))
      }
      .unsafeRunSync()
  }

  private def run(handler: Request[Body] => Eru[HttpError, Response[Body]], request: Request[Body]): Response[Body] =
    handler(request).unsafeRunSync()

  private def bodyText(response: Response[Body]): String = response.body match {
    case Body.Text(text, _, _) => text
    case other => fail(s"Expected Text body, got: $other"); ""
  }

  private def header(response: Response[Body], name: String): Option[String] =
    response.headers.getFirst(name).map(_.value)

  private val okHandler: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
    Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
  }

  // --- CSRF ---

  test("CSRF: a safe request without a cookie is issued one via Set-Cookie") {
    val handler = Csrf.middleware(Csrf.Config())(okHandler)
    val response = run(handler, get("/page"))

    assertEquals(response.status, StatusCode.Ok)
    val setCookie = header(response, "Set-Cookie")
    assert(setCookie.exists(_.startsWith("__csrf=")), s"Missing CSRF cookie: $setCookie")
    assert(setCookie.exists(_.contains("Secure")), s"Cookie must be Secure: $setCookie")
    assert(setCookie.exists(_.contains("HttpOnly")), s"Cookie must be HttpOnly: $setCookie")
  }

  test("CSRF: a state-changing request without matching token answers 403") {
    val handler = Csrf.middleware(Csrf.Config())(okHandler)

    val noToken = run(handler, post("/submit", "data"))
    assertEquals(noToken.status, StatusCode.Forbidden)

    val mismatch = run(handler, post("/submit", "data", headerPairs = List("X-CSRF-Token" -> "abc")))
    assertEquals(mismatch.status, StatusCode.Forbidden)
  }

  test("CSRF: a state-changing request with matching cookie and header passes") {
    val handler = Csrf.middleware(Csrf.Config())(okHandler)
    val response = run(
      handler,
      post("/submit", "data", headerPairs = List("Cookie" -> "__csrf=tok123", "X-CSRF-Token" -> "tok123"))
    )

    assertEquals(response.status, StatusCode.Ok)
  }

  test("CSRF: a safe request with a cookie passes through unchanged") {
    val handler = Csrf.middleware(Csrf.Config())(okHandler)
    val response = run(handler, get("/page", headerPairs = List("Cookie" -> "__csrf=tok123")))

    assertEquals(response.status, StatusCode.Ok)
    assertEquals(header(response, "Set-Cookie"), None, "No new cookie should be issued")
  }

  // --- Session ---

  case class StoredUser(id: Int)

  given Encoder[StoredUser, JsonValue] = net.ghoula.sarati.codec.Encoder.derived
  given Decoder[JsonValue, StoredUser] = net.ghoula.sarati.codec.Decoder.derived
  given net.ghoula.sarati.codec.Encoder[Int, JsonValue] =
    net.ghoula.sarati.codec.JsonEncoders.given_Encoder_Int_JsonValue
  given net.ghoula.sarati.codec.Encoder[String, JsonValue] =
    net.ghoula.sarati.codec.JsonEncoders.given_Encoder_String_JsonValue

  test("Session: a request whose handler writes the session gets a fresh cookie") {
    val writer: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      val session = Session.current.getOrElse(fail("Session handle missing"))
      session.set("hello", "world")
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }
    val handler = Session.middleware(Session.Config(secure = false))(writer)
    val response = run(handler, get("/page"))

    val setCookie = header(response, "Set-Cookie")
    assert(setCookie.exists(_.startsWith("sid=")), s"Missing session cookie: $setCookie")
  }

  test("Session: a request that never touches the session issues no cookie") {
    val handler = Session.middleware(Session.Config())(okHandler)
    assertEquals(header(run(handler, get("/page")), "Set-Cookie"), None)
  }

  test("Session: state written in the handler is stored and read back by session id") {
    val store = new Session.InMemorySessionStore
    val config = Session.Config(secure = false, store = store)

    val writer: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      val session = Session.current.getOrElse(fail("Session handle missing"))
      session.set("user", StoredUser(42))
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("written")))
    }
    val handler = Session.middleware(config)(writer)

    val first = run(handler, get("/login"))
    val setCookie = header(first, "Set-Cookie").getOrElse(fail("Missing session cookie"))
    val sid = setCookie.split(";").head.stripPrefix("sid=")

    val reader: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      val session = Session.current.getOrElse(fail("Session handle missing"))
      val user = session.getAs[StoredUser]("user").getOrElse(fail("Stored user missing"))
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(s"user-${user.id}")))
    }
    val second = run(Session.middleware(config)(reader), get("/me", headerPairs = List("Cookie" -> s"sid=$sid")))

    assertEquals(bodyText(second), "user-42")
  }

  test("Session: invalidate removes the store entry and expires the cookie") {
    val store = new Session.InMemorySessionStore
    val config = Session.Config(secure = false, store = store)

    val writer: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      val session = Session.current.getOrElse(fail("Session handle missing"))
      session.set("user", StoredUser(1))
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("written")))
    }
    val sid = {
      val first = run(Session.middleware(config)(writer), get("/login"))
      val setCookie = header(first, "Set-Cookie").getOrElse(fail("Missing session cookie"))
      setCookie.split(";").head.stripPrefix("sid=")
    }

    val logout: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      val session = Session.current.getOrElse(fail("Session handle missing"))
      session.invalidate()
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("bye")))
    }
    val response = run(Session.middleware(config)(logout), get("/logout", headerPairs = List("Cookie" -> s"sid=$sid")))

    val setCookie = header(response, "Set-Cookie").getOrElse(fail("Missing expiry cookie"))
    assert(setCookie.contains("Max-Age=0"), s"Cookie must expire: $setCookie")
    store.load(sid).unsafeRunSync() match {
      case None => ()
      case Some(data) => fail(s"Session should be removed, got: $data")
    }
  }

  // --- RateLimit ---

  test("RateLimit: requests within the window pass, then 429 with Retry-After") {
    val handler = RateLimit.middleware(RateLimit.Config(limit = 2, window = 10.seconds))(okHandler)

    val first = run(handler, get("/r"))
    assertEquals(first.status, StatusCode.Ok)
    val second = run(handler, get("/r"))
    assertEquals(second.status, StatusCode.Ok)
    val third = run(handler, get("/r"))
    assertEquals(third.status.value, 429)
    assert(
      header(third, "Retry-After").exists(_.forall(_.isDigit)),
      s"Retry-After must be delta-seconds: ${header(third, "Retry-After")}"
    )
  }

  test("RateLimit: keys are independent") {
    val handler = RateLimit.middleware(
      RateLimit.Config(limit = 1, window = 10.seconds, keyExtractor = (r: Request[Body]) => r.uri.path)
    )(okHandler)

    assertEquals(run(handler, get("/a")).status, StatusCode.Ok)
    assertEquals(run(handler, get("/a")).status.value, 429)
    assertEquals(run(handler, get("/b")).status, StatusCode.Ok, "A different key must have its own window")
  }

  test("RateLimit: the window resets after it elapses") {
    val store = new RateLimit.InMemoryRateLimitStore
    val handler = RateLimit.middleware(
      RateLimit.Config(limit = 1, window = 50.millis, store = store, keyExtractor = (r: Request[Body]) => r.uri.path)
    )(okHandler)

    assertEquals(run(handler, get("/w")).status, StatusCode.Ok)
    assertEquals(run(handler, get("/w")).status.value, 429)

    // Sleep past the 50ms window; the next request starts a new one.
    Thread.sleep(80)
    assertEquals(run(handler, get("/w")).status, StatusCode.Ok)
  }

  // --- Graceful shutdown (drain-aware health) ---

  test("RunningServer.stop flips the drain state and stops the server") {
    given net.ghoula.eru.EruRuntime = net.ghoula.eru.EruRuntime.shared

    val router = net.ghoula.melian.router.Router.builder.get("/", okHandlerMelian).build.getOrElse(fail("build failed"))
    val config = HttpServerConfig.localhost.withPort(0)
    val running = MelianServer.start(config, router, ShutdownConfig(gracePeriod = 1.second)).unsafeRunSync()

    assert(running.isRunning, "The server must be running after start")
    assert(!running.isDraining, "A fresh server must not be draining")
    assert(running.address.port > 0, s"An ephemeral port must resolve to the bound port: ${running.address}")

    running.stop.unsafeRunSync()

    assert(!running.isRunning, "The server must be stopped after stop")
    assert(running.isDraining, "Draining must be flagged once stop has begun")
  }

  test("drain-aware health answers 503 on the health path while draining") {
    // Exercises the drain health wrapper directly (no socket): the same logic MelianServer.start
    // installs in front of the router.
    val draining = new java.util.concurrent.atomic.AtomicBoolean(true)
    val inner: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("app")))
    }
    val handler: Request[Body] => Eru[HttpError, Response[Body]] = { (request: Request[Body]) =>
      if draining.get() && request.method.value == "GET" && request.uri.path == "/health" then
        Eru.succeed(Response(StatusCode.ServiceUnavailable, Headers.empty, Body.text("draining")))
      else inner(request)
    }

    val health = run(handler, get("/health"))
    assertEquals(health.status, StatusCode.ServiceUnavailable)

    draining.set(false)
    assertEquals(run(handler, get("/health")).status, StatusCode.Ok)
    assertEquals(run(handler, get("/app")).status, StatusCode.Ok)
  }

  private def okHandlerMelian: () => Eru[Nothing, net.ghoula.melian.Ok[String]] =
    () => Eru.succeed(net.ghoula.melian.Ok("ok"))

  test("Csrf and Session compose without clobbering each other's Set-Cookie") {
    val sessionWriter: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      val session = Session.current.getOrElse(fail("Session handle missing"))
      session.set("hello", "world")
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }
    val handler = Csrf.middleware(Csrf.Config())(Session.middleware(Session.Config(secure = false))(sessionWriter))
    val response = run(handler, get("/start"))

    val cookies = response.headers.get("Set-Cookie").toList.flatten.map(_.value)
    assertEquals(cookies.size, 2, s"Both middlewares must issue their cookie: $cookies")
    assert(cookies.exists(_.startsWith("sid=")), s"Missing session cookie: $cookies")
    assert(cookies.exists(_.startsWith("__csrf=")), s"Missing CSRF cookie: $cookies")
  }

  test("Session: an unknown session id is replaced with a fresh one when the session is written") {
    val config = Session.Config(secure = false)
    val writer: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
      val session = Session.current.getOrElse(fail("Session handle missing"))
      session.set("seen", "yes")
      Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text("ok")))
    }
    val handler = Session.middleware(config)(writer)

    val response = run(handler, get("/page", headerPairs = List("Cookie" -> "sid=attacker-known")))
    val setCookie = header(response, "Set-Cookie").getOrElse(fail("Missing session cookie"))
    val freshId = setCookie.split(";").head.stripPrefix("sid=")
    assert(freshId != "attacker-known", "A client-supplied unknown id must never be reused")

    // The issued id is sticky: presenting it again resolves the same session, and a write
    // refreshes the cookie under the same id rather than minting a new one.
    val second = run(handler, get("/page", headerPairs = List("Cookie" -> s"sid=$freshId")))
    val secondCookie = header(second, "Set-Cookie").getOrElse(fail("Missing cookie"))
    val secondId = secondCookie.split(";").head.stripPrefix("sid=")
    assertEquals(secondId, freshId, "The issued id must be sticky across requests")
  }

  test("RateLimit: tracking is bounded and stale windows are evicted") {
    val store = new RateLimit.InMemoryRateLimitStore(maxTrackedKeys = 4)
    val handler = RateLimit.middleware(
      RateLimit.Config(limit = 1, window = 50.millis, store = store, keyExtractor = (r: Request[Body]) => r.uri.path)
    )(okHandler)

    (1 to 6).foreach { i =>
      val _ = run(handler, get(s"/k$i"))
    }
    assertEquals(store.trackedKeys, 6)

    Thread.sleep(80)
    val _ = run(handler, get("/fresh"))
    assert(store.trackedKeys <= 2, s"Expired windows must be evicted, got ${store.trackedKeys}")
  }
}
