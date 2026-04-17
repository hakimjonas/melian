package net.ghoula.melian.server

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.{HttpServer, HttpServerConfig, Middleware}
import net.ghoula.eru.EruRuntime
import net.ghoula.melian.router.Router

/** Entry point for running a Melian router as an HTTP server.
  *
  * Bridges Router to eru-http's HttpServer, composing with eru-http's middleware stack. Melian does
  * not define its own middleware — it uses eru-http's Middleware type directly.
  *
  * @example
  *   {{{
  *   val router = Router.builder
  *     .get("/users/:id", getUser)
  *     .post("/users", createUser)
  *     .build
  *     .getOrElse(sys.error("route build failed"))
  *
  *   given EruRuntime = EruRuntime.shared
  *
  *   MelianServer.serve(router) { server =>
  *     server.start.flatMap { address =>
  *       Eru.succeed(println(s"Listening on $address"))
  *     }
  *   }
  *   }}}
  */
object MelianServer {

  /** Serve a router with default config and no middleware. */
  def serve[A](router: Router)(
    use: HttpServer => Eru[HttpError, A]
  )(using runtime: EruRuntime): Eru[HttpError, A] =
    HttpServer.scoped(router.toHandler)(use)

  /** Serve a router with custom config and no middleware. */
  def serve[A](config: HttpServerConfig, router: Router)(
    use: HttpServer => Eru[HttpError, A]
  )(using runtime: EruRuntime): Eru[HttpError, A] =
    HttpServer.scoped(config)(router.toHandler)(use)

  /** Serve a router with middleware applied. */
  def serveWith[A](router: Router, middleware: Middleware)(
    use: HttpServer => Eru[HttpError, A]
  )(using runtime: EruRuntime): Eru[HttpError, A] =
    HttpServer.scoped(middleware(router.toHandler))(use)

  /** Serve a router with custom config and middleware. */
  def serveWith[A](config: HttpServerConfig, router: Router, middleware: Middleware)(
    use: HttpServer => Eru[HttpError, A]
  )(using runtime: EruRuntime): Eru[HttpError, A] =
    HttpServer.scoped(config)(middleware(router.toHandler))(use)
}
