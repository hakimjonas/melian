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
        Eru.succeed(Response(StatusCode.NotFound, Headers.empty, Body.text(s"Not Found: ${request.uri.path}")))

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
          .flatMap { response =>
            if request.method.value == Method.HEAD.value then stripBody(response)
            else Eru.succeed(response)
          }
    }
  }

  /** RFC 9110 Section 9.3.2: a HEAD response carries the GET response's status and headers (in
    * particular its Content-Length) but no body.
    */
  private def stripBody(response: Response[Body]): Eru[HttpError, Response[Body]] = {
    val contentLength = response.body.contentLength
    val stripped = response.withBody(Body.Empty)
    contentLength match {
      case Some(len) =>
        stripped
          .setHeader(HeaderNames.ContentLength, len.toString)
          .mapError { e =>
            HttpError.InvalidResponse(InvalidResponse(e.toString, "Content-Length header"))
          }
      case None => Eru.succeed(stripped)
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
