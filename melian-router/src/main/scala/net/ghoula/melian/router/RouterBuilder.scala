package net.ghoula.melian.router

import net.ghoula.eru.Eru
import net.ghoula.eru.http.{Body, Response}

/** Builder for constructing a Router via chained inline method calls.
  *
  * Each .get/.post/.put/.delete/.patch triggers a compile-time macro that verifies path parameters,
  * method constraints, and typeclass instances, then generates the extraction + encoding pipeline.
  *
  * `errorRenderer` sets the error-rendering tier for the routes registered after it. It overrides
  * the built-in problem-details fallback for those routes; an `ErrorRenderer[E]` given in scope at
  * a route's registration site still wins (endpoint tier over group tier).
  */
final class RouterBuilder private[router] (
  private[router] val entries: Vector[RouteEntry],
  private[router] val groupErrorRenderer: Option[PartialFunction[Any, Eru[Nothing, Response[Body]]]] = None
) {

  private[router] def addEntry(entry: RouteEntry): RouterBuilder =
    new RouterBuilder(entries :+ entry, groupErrorRenderer)

  /** Sets the error renderer applied to routes registered after this call (a per-group or global
    * tier, depending on position). Partial over the error type: errors the function does not
    * declare fall through to the built-in problem-details 500.
    */
  def errorRenderer(renderer: PartialFunction[Any, Eru[Nothing, Response[Body]]]): RouterBuilder =
    new RouterBuilder(entries, Some(renderer))

  inline def get[H](
    path: String,
    handler: H,
    operationId: String = "",
    summary: String = "",
    description: String = "",
    tags: String = ""
  ): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "GET" }, 'operationId, 'summary, 'description, 'tags) }

  inline def head[H](
    path: String,
    handler: H,
    operationId: String = "",
    summary: String = "",
    description: String = "",
    tags: String = ""
  ): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "HEAD" }, 'operationId, 'summary, 'description, 'tags) }

  inline def post[H](
    path: String,
    handler: H,
    operationId: String = "",
    summary: String = "",
    description: String = "",
    tags: String = ""
  ): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "POST" }, 'operationId, 'summary, 'description, 'tags) }

  inline def put[H](
    path: String,
    handler: H,
    operationId: String = "",
    summary: String = "",
    description: String = "",
    tags: String = ""
  ): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "PUT" }, 'operationId, 'summary, 'description, 'tags) }

  inline def delete[H](
    path: String,
    handler: H,
    operationId: String = "",
    summary: String = "",
    description: String = "",
    tags: String = ""
  ): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "DELETE" }, 'operationId, 'summary, 'description, 'tags) }

  inline def patch[H](
    path: String,
    handler: H,
    operationId: String = "",
    summary: String = "",
    description: String = "",
    tags: String = ""
  ): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "PATCH" }, 'operationId, 'summary, 'description, 'tags) }

  inline def query[H](
    path: String,
    handler: H,
    operationId: String = "",
    summary: String = "",
    description: String = "",
    tags: String = ""
  ): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "QUERY" }, 'operationId, 'summary, 'description, 'tags) }

  /** Registers a typed WebSocket endpoint at `path`.
    *
    * The handler receives its marked parameters extracted from the upgrade request and must return
    * `WebSocketEndpoint[In, Out]` -- a function from the typed session to
    * `Eru[WebSocketError | HttpError, Unit]`. The route is served under GET: upgrade requests
    * perform the RFC 6455 handshake (via eru-http), plain GETs answer 426 Upgrade Required.
    */
  inline def websocket[H](
    path: String,
    handler: H,
    wsConfig: net.ghoula.eru.http.server.WebSocketServerConfig =
      net.ghoula.eru.http.server.WebSocketServerConfig.default,
    operationId: String = "",
    summary: String = "",
    description: String = "",
    tags: String = ""
  ): RouterBuilder =
    ${
      RouteMacros.addWebSocketRoute[H]('this, 'path, 'handler, 'wsConfig, 'operationId, 'summary, 'description, 'tags)
    }

  def build(using
    sanitizer: net.ghoula.melian.ErrorSanitizer = net.ghoula.melian.ErrorSanitizer.development
  ): Either[String, Router] =
    RouteTrie.build(entries).map(trie => new Router(trie, sanitizer))

  def operationSchemas: Vector[net.ghoula.melian.schema.OperationSchema] =
    entries.map(_.schema)
}

object RouterBuilder {
  def apply(): RouterBuilder = new RouterBuilder(Vector.empty)
}
