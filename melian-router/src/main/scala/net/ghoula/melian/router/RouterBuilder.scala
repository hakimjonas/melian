package net.ghoula.melian.router

/** Builder for constructing a Router via chained inline method calls.
  *
  * Each .get/.post/.put/.delete/.patch triggers a compile-time macro that verifies path parameters,
  * method constraints, and typeclass instances, then generates the extraction + encoding pipeline.
  */
final class RouterBuilder private[router] (
  private[router] val entries: Vector[RouteEntry]
) {

  private[router] def addEntry(entry: RouteEntry): RouterBuilder =
    new RouterBuilder(entries :+ entry)

  inline def get[H](path: String, handler: H): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "GET" }) }

  inline def post[H](path: String, handler: H): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "POST" }) }

  inline def put[H](path: String, handler: H): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "PUT" }) }

  inline def delete[H](path: String, handler: H): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "DELETE" }) }

  inline def patch[H](path: String, handler: H): RouterBuilder =
    ${ RouteMacros.addRoute[H]('this, 'path, 'handler, '{ "PATCH" }) }

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
