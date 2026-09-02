package net.ghoula.melian.server

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

/** Liveness and readiness endpoints for orchestrators (Kubernetes, load balancers).
  *
  * These are plain `eru-http` handlers, not Melian macro routes, so they compose with the router
  * handler through [[Health.middleware]] or a fallback chain, the same way
  * [[net.ghoula.melian.openapi.OpenApiRoutes]] does.
  */
object Health {

  final case class Config(
    livenessPath: String = "/health",
    readinessPath: String = "/ready"
  )

  /** Serves 200 at the liveness path, so a process that accepts connections reports healthy. */
  def livenessHandler(path: String = "/health"): Request[Body] => Eru[HttpError, Option[Response[Body]]] = { request =>
    if request.method.value == "GET" && request.uri.path == path then
      Eru.succeed(Some(Response(StatusCode.Ok, Headers.empty, Body.text("ok"))))
    else Eru.succeed(None)
  }

  /** Serves 200 or 503 at the readiness path, depending on the check. */
  def readinessHandler(path: String = "/ready")(
    isReady: () => Eru[HttpError, Boolean]
  ): Request[Body] => Eru[HttpError, Option[Response[Body]]] = { request =>
    if request.method.value == "GET" && request.uri.path == path then
      isReady().map { ready =>
        Some(
          if ready then Response(StatusCode.Ok, Headers.empty, Body.text("ok"))
          else Response(StatusCode.ServiceUnavailable, Headers.empty, Body.text("not ready"))
        )
      }
    else Eru.succeed(None)
  }

  /** Combines liveness and readiness as middleware wrapping the main router handler. */
  def middleware(
    config: Config = Config(),
    isReady: () => Eru[HttpError, Boolean] = () => Eru.succeed(true)
  ): (Request[Body] => Eru[HttpError, Response[Body]]) => (Request[Body] => Eru[HttpError, Response[Body]]) = {
    val liveness = livenessHandler(config.livenessPath)
    val readiness = readinessHandler(config.readinessPath)(isReady)

    (inner: Request[Body] => Eru[HttpError, Response[Body]]) => { (request: Request[Body]) =>
      liveness(request).flatMap {
        case Some(response) => Eru.succeed(response)
        case None =>
          readiness(request).flatMap {
            case Some(response) => Eru.succeed(response)
            case None => inner(request)
          }
      }
    }
  }
}
