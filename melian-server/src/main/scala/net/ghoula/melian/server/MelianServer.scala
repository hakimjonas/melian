package net.ghoula.melian.server

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import scala.concurrent.duration.Duration

import net.ghoula.eru.Eru
import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.{HttpServer, HttpServerConfig, Middleware, RequestHandler, ServerAddress}
import net.ghoula.melian.router.Router

/** Draining behaviour for [[MelianServer.start]]. */
final case class ShutdownConfig(
  /** Bound on the shutdown wait for in-flight requests, applied as eru-http's
    * `gracefulShutdownTimeout`.
    */
  gracePeriod: Duration = 30.seconds,

  /** When `false` (the default), the path at `healthPath` answers 503 while the server drains, so
    * orchestrators stop routing traffic before the process exits. When `true`, Melian leaves the
    * health path untouched and the operator's own readiness logic governs.
    */
  healthDuringDrain: Boolean = false,

  /** The path that reports 503 during drain (when `healthDuringDrain` is `false`). */
  healthPath: String = "/health"
)

/** A started server with explicit lifecycle control.
  *
  * Unlike `serve`/`serveWith` (bracket-scoped: the server stops when the scope exits), a
  * `RunningServer` lives until [[stop]] is called or the JVM shuts down, whichever comes first.
  */
final class RunningServer private[server] (
  private val server: HttpServer,
  private val boundAddress: ServerAddress,
  private val draining: AtomicBoolean,
  private val hook: Thread
) {

  /** The address the server actually bound. Resolved from the running server, so a config asking
    * for port 0 reports the ephemeral port that was assigned.
    */
  def address: ServerAddress = boundAddress

  /** `true` once draining has begun: health (per [[ShutdownConfig]]) reports 503. */
  def isDraining: Boolean = draining.get()

  def isRunning: Boolean = server.isRunning

  /** Begins draining (health flips to 503 unless configured otherwise), stops accepting new
    * connections, and waits for in-flight requests up to the grace period. Idempotent via the
    * server's own shutdown semantics.
    */
  def stop: Eru[HttpError, Unit] =
    Eru.succeed {
      draining.set(true)
      // Removing the hook from within the hook (JVM-shutdown path) throws; that race is benign --
      // the shutdown is already running.
      try Runtime.getRuntime.removeShutdownHook(hook)
      catch { case _: IllegalStateException => () }
    }.flatMap(_ => server.shutdown)
}

/** Entry point for running a Melian router as an HTTP server.
  *
  * Bridges Router to eru-http's HttpServer, composing with eru-http's middleware stack. Melian does
  * not define its own middleware; it uses eru-http's Middleware type directly.
  *
  * Bracket-scoped external resources (database pools, message brokers) compose naturally by
  * nesting: the outer bracket acquires the resource, the inner bracket runs the server. On
  * shutdown, the server closes first, then the resource: correct ordering by construction.
  *
  * @example
  *   {{{
  *   // Basic server
  *   MelianServer.serve(router) { server =>
  *     server.start.map(addr => println(s"Listening on $addr"))
  *   }
  *
  *   // With bracket-scoped resources (e.g. database pool)
  *   EruPostgres.scoped(dbConfig) { db =>
  *     val router = Router.builder
  *       .get("/users/:id", userHandler(db))
  *       .build.getOrElse(sys.error("route build failed"))
  *     MelianServer.serve(router) { server =>
  *       server.start.map(addr => println(s"Listening on $addr"))
  *     }.mapError(e => DbOrHttpError.Http(e))
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

  /** Starts a server with connection draining on process termination.
    *
    * Stops accepting new connections on [[RunningServer.stop]] or JVM shutdown, lets in-flight
    * requests complete (up to the grace period), then shuts down. While draining, the health path
    * answers 503 unless `healthDuringDrain` is set.
    */
  def start(
    config: HttpServerConfig,
    router: Router,
    shutdown: ShutdownConfig = ShutdownConfig()
  )(using runtime: EruRuntime): Eru[HttpError, RunningServer] = {
    val draining = new AtomicBoolean(false)
    val baseHandler = router.toHandler
    val handler: RequestHandler =
      if shutdown.healthDuringDrain then baseHandler
      else
        (request: Request[Body]) =>
          if draining.get() && request.method.value == "GET" && request.uri.path == shutdown.healthPath then
            Eru.succeed(Response(StatusCode.ServiceUnavailable, Headers.empty, Body.text("draining")))
          else baseHandler(request)

    val effectiveConfig = config.copy(gracefulShutdownTimeout = shutdown.gracePeriod)

    HttpServer.create(effectiveConfig, handler).flatMap { server =>
      server.start.map { address =>
        val hook = new Thread(
          () => {
            draining.set(true)
            val _ = server.shutdown.attempt.unsafeRunSync()
          },
          "melian-shutdown"
        )
        Runtime.getRuntime.addShutdownHook(hook)
        RunningServer(server, address, draining, hook)
      }
    }
  }
}
