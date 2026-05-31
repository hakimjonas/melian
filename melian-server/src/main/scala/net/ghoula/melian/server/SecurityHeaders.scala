package net.ghoula.melian.server

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

/** Middleware that applies standard security headers to every response.
  *
  * Configurable per-header, with sensible production defaults.
  *
  * `Content-Security-Policy` is opt-in: it is the one security header that can break a site if
  * applied with a policy that does not match its assets, so the framework never imposes one. A
  * consumer supplies a [Csp] (built with the typed builder) via [Config.contentSecurityPolicy];
  * only then is the header emitted.
  */
object SecurityHeaders {

  /** A typed Content-Security-Policy.
    *
    * Directives are composed as typed fields rather than a hand-written string, so a policy cannot
    * be misspelled into silence. Each field is a list of sources; empty lists are omitted from the
    * rendered header. `frameAncestors` defaults to `'none'` (the CSP equivalent of
    * `X-Frame-Options: DENY`), and `objectSrc` defaults to `'none'`.
    *
    * Build with [Csp.selfOnly] or the `with*` combinators, e.g.:
    * {{{
    * Csp.selfOnly
    *   .withScriptSrc("'self'", "'wasm-unsafe-eval'")
    *   .withStyleSrc("'self'", "'unsafe-inline'")
    * }}}
    */
  final case class Csp(
    defaultSrc: List[String] = List("'self'"),
    scriptSrc: List[String] = Nil,
    styleSrc: List[String] = Nil,
    imgSrc: List[String] = Nil,
    connectSrc: List[String] = Nil,
    fontSrc: List[String] = Nil,
    objectSrc: List[String] = List("'none'"),
    baseUri: List[String] = List("'self'"),
    frameAncestors: List[String] = List("'none'")
  ) {

    /** Replaces `script-src`. */
    def withScriptSrc(sources: String*): Csp = copy(scriptSrc = sources.toList)

    /** Replaces `style-src`. */
    def withStyleSrc(sources: String*): Csp = copy(styleSrc = sources.toList)

    /** Replaces `img-src`. */
    def withImgSrc(sources: String*): Csp = copy(imgSrc = sources.toList)

    /** Replaces `connect-src`. */
    def withConnectSrc(sources: String*): Csp = copy(connectSrc = sources.toList)

    /** Replaces `font-src`. */
    def withFontSrc(sources: String*): Csp = copy(fontSrc = sources.toList)

    /** Replaces `default-src`. */
    def withDefaultSrc(sources: String*): Csp = copy(defaultSrc = sources.toList)

    /** Replaces `frame-ancestors`. */
    def withFrameAncestors(sources: String*): Csp =
      copy(frameAncestors = sources.toList)

    /** Renders the header value, omitting directives with no sources. */
    val value: String = {
      val directives = List(
        "default-src" -> defaultSrc,
        "script-src" -> scriptSrc,
        "style-src" -> styleSrc,
        "img-src" -> imgSrc,
        "connect-src" -> connectSrc,
        "font-src" -> fontSrc,
        "object-src" -> objectSrc,
        "base-uri" -> baseUri,
        "frame-ancestors" -> frameAncestors
      )
      directives.collect { case (name, sources) if sources.nonEmpty => s"$name ${sources.mkString(" ")}" }
        .mkString("; ")
    }
  }

  object Csp {

    /** A locked-down baseline: everything from `'self'`, no objects, no framing. A starting point
      * to loosen per the site's real assets.
      */
    val selfOnly: Csp = Csp()
  }

  final case class Config(
    hstsMaxAge: Long = 63072000,
    hstsIncludeSubDomains: Boolean = true,
    hstsPreload: Boolean = true,
    frameOptions: String = "DENY",
    contentTypeOptions: String = "nosniff",
    referrerPolicy: String = "strict-origin-when-cross-origin",
    contentSecurityPolicy: Option[Csp] = None
  ) {
    val hstsValue: String = {
      val base = s"max-age=$hstsMaxAge"
      val sub = if hstsIncludeSubDomains then s"$base; includeSubDomains" else base
      if hstsPreload then s"$sub; preload" else sub
    }
  }

  val production: Config = Config()

  def middleware(config: Config = production)(
    inner: Request[Body] => Eru[HttpError, Response[Body]]
  ): Request[Body] => Eru[HttpError, Response[Body]] = { (request: Request[Body]) =>
    inner(request).flatMap { response =>
      response
        .setHeader("X-Content-Type-Options", config.contentTypeOptions)
        .flatMap(_.setHeader("X-Frame-Options", config.frameOptions))
        .flatMap(_.setHeader("Referrer-Policy", config.referrerPolicy))
        .flatMap(_.setHeader("Strict-Transport-Security", config.hstsValue))
        .flatMap { r =>
          config.contentSecurityPolicy match {
            case Some(csp) => r.setHeader("Content-Security-Policy", csp.value)
            case None => Eru.succeed(r)
          }
        }
        .mapError(e => HttpError.NetworkError(s"Security header error: $e"))
    }
  }
}
