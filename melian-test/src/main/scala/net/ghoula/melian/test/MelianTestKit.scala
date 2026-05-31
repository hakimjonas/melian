package net.ghoula.melian.test

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.melian.router.Router

/** Test utilities for exercising Melian routers without a running server.
  *
  * Provides synthetic request construction and response inspection helpers.
  */
object MelianTestKit {

  /** Run a request through a router's handler, returning the response. */
  def run(router: Router, request: Request[Body]): Response[Body] =
    router.toHandler(request).unsafeRunSync()

  /** Build a request with optional body and headers. */
  def request(
    method: Method,
    path: String,
    body: Body = Body.empty,
    headers: List[(String, String)] = Nil
  ): Request[Body] = {
    val base = Request(method = method, uri = Uri.http("localhost", path = path), headers = Headers.empty, body = body)
    headers
      .foldLeft[Eru[Any, Request[Body]]](Eru.succeed(base)) { case (acc, (name, value)) =>
        acc.flatMap(_.addHeader(name, value))
      }
      .unsafeRunSync()
  }

  /** The text body of a response, or `None` if the body is not text. */
  def bodyText(response: Response[Body]): Option[String] = response.body match {
    case Body.Text(text, _, _) => Some(text)
    case _ => None
  }

  /** Convenience for GET requests. */
  def get(router: Router, path: String, headers: List[(String, String)] = Nil): Response[Body] =
    run(router, request(Method.GET, path, headers = headers))

  /** Convenience for POST requests with JSON body. */
  def post(router: Router, path: String, jsonBody: String, headers: List[(String, String)] = Nil): Response[Body] =
    run(router, request(Method.POST, path, body = Body.text(jsonBody, MediaType.applicationJson), headers = headers))
}
