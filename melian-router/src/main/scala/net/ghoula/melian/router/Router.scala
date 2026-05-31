package net.ghoula.melian.router

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.melian.RequestError

/** Compiled router that dispatches HTTP requests via a segment trie.
  *
  * Produces a handler function compatible with eru-http's RequestHandler type. RequestErrors from
  * the Girdle pipeline are rendered as 400 Bad Request responses with RFC 9457 problem details.
  */
final class Router private[router] (
  private val trie: RouteTrie,
  private val sanitizer: net.ghoula.melian.ErrorSanitizer = summon[net.ghoula.melian.ErrorSanitizer]
) {

  def toHandler: Request[Body] => Eru[HttpError, Response[Body]] = { (request: Request[Body]) =>
    trie.lookup(request.uri.path, request.method) match {
      case RouteTrie.LookupResult.NotFound =>
        Eru.succeed(Response.notFound(Body.text(s"Not Found: ${request.uri.path}")))

      case RouteTrie.LookupResult.MethodNotAllowed(allowed) =>
        Response.methodNotAllowed(allowed).mapError { e =>
          HttpError.InvalidResponse(InvalidResponse(e.toString, "RFC 9110 Section 15.5.6"))
        }

      case RouteTrie.LookupResult.Matched(entry, pathParams) =>
        entry
          .handler(request, pathParams)
          .recoverWith { case e: RequestError =>
            Eru.succeed(renderRequestError(e))
          }
          .mapError {
            case e: HttpError => e
            case e => HttpError.ProtocolError(e.toString, "unexpected")
          }
    }
  }

  private def renderRequestError(error: RequestError): Response[Body] =
    ProblemDetails.render(sanitizer.sanitize(error))
}

object Router {

  def builder: RouterBuilder = RouterBuilder()

  def fromRoutes(routes: Vector[RouteEntry])(using
    sanitizer: net.ghoula.melian.ErrorSanitizer
  ): Either[String, Router] =
    RouteTrie.build(routes).map(trie => new Router(trie, sanitizer))
}
