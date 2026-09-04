package net.ghoula.melian.server

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
import scala.concurrent.duration.Duration

import net.ghoula.eru.Eru
import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.*
import net.ghoula.eru.http.acme.{AcmeConfig, AcmeHttp01, AcmeProvisioner}
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
    * connections, and waits for in-flight requests up to the grace period. Safe to call more than
    * once and from concurrent paths (an explicit stop racing the JVM shutdown hook):
    * `NativeHttpServer.shutdown` guards its teardown with `running.compareAndSet(true, false)`, so
    * exactly one caller performs it and the rest return immediately.
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

  /** ACME settings for [[MelianServer.withAcme]] (DESIGN.md Section 14.1).
    *
    * @param domain
    *   the DNS identifier to certify
    * @param contactEmail
    *   ACME account contact
    * @param staging
    *   use Let's Encrypt staging (the safe default; production rate limits are unforgiving)
    * @param storePath
    *   where the account key, issued material, and PKCS12 keystore persist
    * @param http01Port
    *   port the built-in HTTP-01 challenge listener binds (ACME validates on 80)
    * @param directoryUrl
    *   explicit ACME directory override (e.g. a local Pebble instance); wins over `staging`
    */
  final case class AcmeSettings(
    domain: String,
    contactEmail: String,
    staging: Boolean = true,
    storePath: Path,
    http01Port: Int = 80,
    directoryUrl: Option[String] = None,
    keyStorePassword: String = "changeit"
  )

  /** What [[withAcme]] hands to the use-callback alongside the TLS server. */
  final case class AcmeHandle(
    provisioner: AcmeProvisioner,
    responder: AcmeHttp01,
    challengeAddress: ServerAddress
  )

  /** Serves `router` over TLS with certificates provisioned by ACME (DESIGN.md Section 14.1).
    *
    * Provisioning is eru-http's domain ([[AcmeProvisioner]]); Melian wires the produced `TlsConfig`
    * into `HttpServerConfig.withTls` and runs the HTTP-01 challenge listener on `acme.http01Port`.
    * Bracket-scoped like `serve`: both servers stop and the renewal loop ends when the scope exits.
    * A still-valid stored certificate is reused without ACME traffic, so restarts are cheap.
    */
  def withAcme[A](
    acme: AcmeSettings,
    config: HttpServerConfig,
    router: Router
  )(
    use: (HttpServer, AcmeHandle) => Eru[HttpError, A]
  )(using runtime: EruRuntime): Eru[HttpError, A] = {
    val responder = AcmeHttp01.create()
    val acmeConfig = AcmeConfig(
      domains = List(acme.domain),
      contactEmail = acme.contactEmail,
      staging = acme.staging,
      directoryUrl = acme.directoryUrl,
      storePath = acme.storePath,
      keyStorePassword = acme.keyStorePassword,
      http01Port = acme.http01Port
    )

    AcmeProvisioner
      .start(acmeConfig, responder)
      .mapError(e => HttpError.NetworkError(e.getMessage, None))
      .flatMap { provisioner =>
        val challengeHandler = AcmeHttp01.challengeHandler(responder)
        val challengeConfig = HttpServerConfig(host = config.host, port = acme.http01Port)
        HttpServer
          .scoped(challengeConfig)(challengeHandler) { challengeServer =>
            challengeServer.start.flatMap { challengeAddress =>
              val tlsConfig = provisioner.tlsConfig
              HttpServer.scoped(config.withTls(tlsConfig))(router.toHandler) { server =>
                use(server, AcmeHandle(provisioner, responder, challengeAddress))
              }
            }
          }
          .ensure(provisioner.stop().attempt.map(_ => ()))
      }
  }

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
          if draining.get() && request.method == Method.GET && request.uri.path == shutdown.healthPath then
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
