package net.ghoula.melian.test

import parser.core.Result as RumilResult
import parsers.json.parseJson

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import net.ghoula.eru.Eru
import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.{WebSocketClient, WebSocketClientConfig, WebSocketConnection}
import net.ghoula.eru.http.server.{HttpServer, HttpServerConfig}
import net.ghoula.eru.http.websocket.WebSocketError
import net.ghoula.melian.router.Router
import net.ghoula.sarati.ast.json.JsonValue

/** Test utilities for exercising Melian routers without a running server.
  *
  * Provides synthetic request construction, per-method helpers, and response inspection. Requests
  * are run directly through the router handler, so the full Girdle pipeline (extraction, decoding,
  * validation, encoding) is exercised without opening a socket.
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

  /** Send a request to a router with a specific method, optional body, and headers. */
  def send(
    router: Router,
    method: Method,
    path: String,
    body: Body = Body.empty,
    headers: List[(String, String)] = Nil
  ): Response[Body] =
    run(router, request(method, path, body, headers))

  /** Convenience for GET requests. */
  def get(router: Router, path: String, headers: List[(String, String)] = Nil): Response[Body] =
    send(router, Method.GET, path, headers = headers)

  /** Convenience for HEAD requests. */
  def head(router: Router, path: String, headers: List[(String, String)] = Nil): Response[Body] =
    send(router, Method.HEAD, path, headers = headers)

  /** Convenience for DELETE requests. */
  def delete(router: Router, path: String, headers: List[(String, String)] = Nil): Response[Body] =
    send(router, Method.DELETE, path, headers = headers)

  /** Convenience for POST requests with a JSON body. */
  def post(router: Router, path: String, jsonBody: String, headers: List[(String, String)] = Nil): Response[Body] =
    send(router, Method.POST, path, body = Body.text(jsonBody, MediaType.applicationJson), headers = headers)

  /** Convenience for PUT requests with a JSON body. */
  def put(router: Router, path: String, jsonBody: String, headers: List[(String, String)] = Nil): Response[Body] =
    send(router, Method.PUT, path, body = Body.text(jsonBody, MediaType.applicationJson), headers = headers)

  /** Convenience for PATCH requests with a JSON body. */
  def patch(router: Router, path: String, jsonBody: String, headers: List[(String, String)] = Nil): Response[Body] =
    send(router, Method.PATCH, path, body = Body.text(jsonBody, MediaType.applicationJson), headers = headers)

  /** Convenience for QUERY requests with a JSON body. */
  def query(router: Router, path: String, jsonBody: String, headers: List[(String, String)] = Nil): Response[Body] =
    send(router, Method.QUERY, path, body = Body.text(jsonBody, MediaType.applicationJson), headers = headers)

  /** The text body of a response, or `None` if the body is not text. */
  def bodyText(response: Response[Body]): Option[String] = response.body match {
    case Body.Text(text, _, _) => Some(text)
    case _ => None
  }

  /** The JSON body of a response, or `None` if the body is not a parseable JSON document. */
  def bodyJson(response: Response[Body]): Option[JsonValue] =
    bodyText(response).flatMap { text =>
      parseJson(text) match {
        case RumilResult.Success(json, _) => Some(json)
        case RumilResult.Partial(json, _, _) => Some(json)
        case RumilResult.Failure(_, _) => None
      }
    }

  /** Sends a POST with an `application/x-www-form-urlencoded` body built from `fields` (values are
    * percent-encoded with form semantics), for exercising [[net.ghoula.melian.Form]] routes.
    */
  def postForm(router: Router, path: String, fields: (String, String)*): Response[Body] = {
    val body = fields.map { case (name, value) =>
      s"${URLEncoder.encode(name, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }
      .mkString("&")
    send(
      router,
      Method.POST,
      path,
      body = Body.text(body, MediaType("application", "x-www-form-urlencoded"))
    )
  }

  /** Runs `router` on a live `HttpServer` bound to an ephemeral localhost port, connects a
    * WebSocket client to `path`, and hands the connection to `use` — the fastest way to exercise a
    * compiled [[net.ghoula.melian.WebSocketEndpoint]] end-to-end, over the real upgrade path.
    *
    * WebSocket errors from `use` or the connection are surfaced as `HttpError.ProtocolError`
    * carrying the original error's message.
    */
  def websocket[A](
    router: Router,
    path: String,
    headers: List[(String, String)] = Nil
  )(use: WebSocketConnection => Eru[WebSocketError | HttpError, A])(using
    runtime: EruRuntime
  ): Eru[HttpError, A] =
    HttpServer.scoped(HttpServerConfig.localhost.withPort(0))(router.toHandler) { server =>
      for {
        address <- server.start
        uri <- Uri.parse(s"ws://${address.host}:${address.port}$path").mapError(e => HttpError.InvalidUri(e): HttpError)
        clientHeaders <- headers
          .foldLeft[Eru[HttpError, Headers]](Eru.succeed(Headers.empty)) { case (acc, (name, value)) =>
            acc.flatMap(
              _.add(name, value).mapError(e => HttpError.InvalidRequest(InvalidRequest(e.toString, "header")))
            )
          }
        result <- WebSocketClient.scoped(uri, WebSocketClientConfig.default, clientHeaders)(use).mapError {
          case e: WebSocketError => HttpError.ProtocolError(e.toString, "websocket"): HttpError
          case e: HttpError => e
        }
      } yield result
    }
}
