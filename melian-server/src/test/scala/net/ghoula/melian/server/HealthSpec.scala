package net.ghoula.melian.server

import munit.FunSuite

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

class HealthSpec extends FunSuite {

  private val fallback: Request[Body] => Eru[HttpError, Response[Body]] = { _ =>
    Eru.succeed(Response(StatusCode.NotFound, Headers.empty, Body.text("fallback")))
  }

  private def get(path: String): Request[Body] =
    Request(Method.GET, Uri.http("localhost", path = path), Headers.empty, Body.empty)

  private def bodyText(response: Response[Body]): String = response.body match {
    case Body.Text(text, _, _) => text
    case _ => ""
  }

  test("liveness returns 200 at /health") {
    val handler = Health.livenessHandler()
    val response = handler(get("/health")).unsafeRunSync()

    assertEquals(response, Some(Response(StatusCode.Ok, Headers.empty, Body.text("ok"))))
  }

  test("liveness passes through for other paths") {
    val handler = Health.livenessHandler()
    val result = handler(get("/other")).unsafeRunSync()

    assertEquals(result, None)
  }

  test("readiness returns 503 when the check fails") {
    val handler = Health.readinessHandler()(() => Eru.succeed(false))
    val response = handler(get("/ready")).unsafeRunSync()

    assertEquals(response.map(_.status), Some(StatusCode.ServiceUnavailable))
  }

  test("readiness returns 200 when the check passes") {
    val handler = Health.readinessHandler()(() => Eru.succeed(true))
    val response = handler(get("/ready")).unsafeRunSync()

    assertEquals(response.map(_.status), Some(StatusCode.Ok))
  }

  test("middleware serves liveness and readiness and falls through") {
    val handler = Health.middleware(isReady = () => Eru.succeed(false))(fallback)

    val liveness = handler(get("/health")).unsafeRunSync()
    val readiness = handler(get("/ready")).unsafeRunSync()
    val other = handler(get("/users")).unsafeRunSync()

    assertEquals(liveness.status, StatusCode.Ok)
    assertEquals(readiness.status, StatusCode.ServiceUnavailable)
    assertEquals(bodyText(other), "fallback")
  }
}
