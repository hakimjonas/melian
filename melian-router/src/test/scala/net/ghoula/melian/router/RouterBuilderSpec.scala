package net.ghoula.melian.router

import munit.FunSuite
import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.melian.*
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.Encoder

import java.util.UUID

class RouterBuilderSpec extends FunSuite {

  given Encoder[String, JsonValue] with {
    def encode(value: String): JsonValue = JsonValue.Str(value)
  }

  private def runHandler(handler: Request[Body] => Eru[HttpError, Response[Body]], request: Request[Body]): Response[Body] =
    handler(request).unsafeRunSync()

  private def bodyText(response: Response[Body]): String = response.body match {
    case Body.Text(text, _, _) => text
    case other => fail(s"Expected Text body, got: $other"); ""
  }

  test("GET with path param extracts UUID and returns JSON response") {
    val handler: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"user-${id.value}"))

    val router = Router.builder
      .get("/users/:id", handler)
      .build
      .getOrElse(fail("Router build failed"))

    val testId = "550e8400-e29b-41d4-a716-446655440000"
    val request = Request(
      method = Method.GET,
      uri = Uri.http("localhost", path = s"/users/$testId"),
      headers = Headers.empty,
      body = Body.empty
    )

    val response = runHandler(router.toHandler, request)
    assertEquals(response.status, StatusCode.Ok)
    assert(bodyText(response).contains(s"user-$testId"), s"Body was: ${bodyText(response)}")
  }

  test("GET to unknown path returns 404") {
    val handler: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"user-${id.value}"))

    val router = Router.builder
      .get("/users/:id", handler)
      .build
      .getOrElse(fail("Router build failed"))

    val request = Request(
      method = Method.GET,
      uri = Uri.http("localhost", path = "/unknown"),
      headers = Headers.empty,
      body = Body.empty
    )

    val response = runHandler(router.toHandler, request)
    assertEquals(response.status, StatusCode.NotFound)
  }

  test("POST to GET-only route returns 405") {
    val handler: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"user-${id.value}"))

    val router = Router.builder
      .get("/users/:id", handler)
      .build
      .getOrElse(fail("Router build failed"))

    val request = Request(
      method = Method.POST,
      uri = Uri.http("localhost", path = "/users/550e8400-e29b-41d4-a716-446655440000"),
      headers = Headers.empty,
      body = Body.empty
    )

    val response = runHandler(router.toHandler, request)
    assertEquals(response.status, StatusCode.MethodNotAllowed)
  }

  test("multiple routes dispatch correctly") {
    val getUser: Path[UUID] => Eru[Nothing, Ok[String]] =
      (id: Path[UUID]) => Eru.succeed(Ok(s"get-${id.value}"))

    val getItem: Path[Int] => Eru[Nothing, Ok[String]] =
      (id: Path[Int]) => Eru.succeed(Ok(s"item-${id.value}"))

    val router = Router.builder
      .get("/users/:id", getUser)
      .get("/items/:id", getItem)
      .build
      .getOrElse(fail("Router build failed"))

    val userRequest = Request(
      method = Method.GET,
      uri = Uri.http("localhost", path = "/users/550e8400-e29b-41d4-a716-446655440000"),
      headers = Headers.empty,
      body = Body.empty
    )

    val itemRequest = Request(
      method = Method.GET,
      uri = Uri.http("localhost", path = "/items/42"),
      headers = Headers.empty,
      body = Body.empty
    )

    val userBody = bodyText(runHandler(router.toHandler, userRequest))
    val itemBody = bodyText(runHandler(router.toHandler, itemRequest))

    assert(userBody.contains("get-550e8400"), s"User body: $userBody")
    assert(itemBody.contains("item-42"), s"Item body: $itemBody")
  }
}
