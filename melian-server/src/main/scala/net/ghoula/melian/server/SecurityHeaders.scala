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
  *
  * `Cross-Origin-Resource-Policy` and `Cross-Origin-Opener-Policy` are likewise opt-in (via
  * [Config.crossOriginResourcePolicy] / [Config.crossOriginOpenerPolicy]): both change how the
  * resource may be embedded or how the browsing context is isolated, so the framework emits them
  * only when a consumer sets a value (e.g. `"same-origin"`). `Cross-Origin-Embedder-Policy` is
  * deliberately not offered: it is only safe once every subresource carries CORP, which is a
  * cross-origin-isolation project rather than a header to toggle.
  */
object SecurityHeaders {

  /** A typed Content-Security-Policy.
    *
    * Directives are composed as typed fields rather than a hand-written string, so a policy cannot
    * be misspelled into silence. The fields cover the directive set a static-site server
    * legitimately sets: the fetch directives (`default-src`, `script-src`, `style-src`, `img-src`,
    * `connect-src`, `font-src`, `frame-src`, `worker-src`, `manifest-src`, `media-src`,
    * `object-src`), the document directive `base-uri`, and the navigation directives
    * `frame-ancestors` and `form-action`. Each is a list of sources; empty lists are omitted from
    * the rendered header. `frameAncestors` and `formAction` default to `'none'` (deny framing and
    * deny form submission to any target), and `objectSrc` defaults to `'none'`.
    *
    * `strictDynamic` is off by default. When enabled it prepends `'strict-dynamic'` to
    * `script-src`, which tells the browser to ignore host and `'self'` allow-lists in favour of
    * trust propagated from already-trusted (hashed or nonced) scripts. Enable it only for a site
    * whose scripts load further scripts through a loader; a site that relies on `'self'` or static
    * `<script src>` / static module imports will have those silently distrusted.
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
    frameSrc: List[String] = Nil,
    workerSrc: List[String] = Nil,
    manifestSrc: List[String] = Nil,
    mediaSrc: List[String] = Nil,
    objectSrc: List[String] = List("'none'"),
    baseUri: List[String] = List("'self'"),
    frameAncestors: List[String] = List("'none'"),
    formAction: List[String] = List("'none'"),
    strictDynamic: Boolean = false
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

    /** Replaces `frame-src`. */
    def withFrameSrc(sources: String*): Csp = copy(frameSrc = sources.toList)

    /** Replaces `worker-src`. */
    def withWorkerSrc(sources: String*): Csp = copy(workerSrc = sources.toList)

    /** Replaces `manifest-src`. */
    def withManifestSrc(sources: String*): Csp = copy(manifestSrc = sources.toList)

    /** Replaces `media-src`. */
    def withMediaSrc(sources: String*): Csp = copy(mediaSrc = sources.toList)

    /** Replaces `default-src`. */
    def withDefaultSrc(sources: String*): Csp = copy(defaultSrc = sources.toList)

    /** Replaces `frame-ancestors`. */
    def withFrameAncestors(sources: String*): Csp =
      copy(frameAncestors = sources.toList)

    /** Replaces `form-action`. */
    def withFormAction(sources: String*): Csp = copy(formAction = sources.toList)

    /** Enables (or disables) the `'strict-dynamic'` source on `script-src`. See the class doc for
      * when this is safe.
      */
    def withStrictDynamic(enabled: Boolean = true): Csp = copy(strictDynamic = enabled)

    /** Renders the header value, omitting directives with no sources. */
    val value: String = {
      // 'strict-dynamic' is a script-src source, prepended when enabled. It is omitted entirely
      // when scriptSrc itself is empty: a 'strict-dynamic' with no companion sources would be a
      // policy with nothing to propagate trust from.
      val effectiveScriptSrc =
        if strictDynamic && scriptSrc.nonEmpty then "'strict-dynamic'" :: scriptSrc
        else scriptSrc
      val directives = List(
        "default-src" -> defaultSrc,
        "script-src" -> effectiveScriptSrc,
        "style-src" -> styleSrc,
        "img-src" -> imgSrc,
        "connect-src" -> connectSrc,
        "font-src" -> fontSrc,
        "frame-src" -> frameSrc,
        "worker-src" -> workerSrc,
        "manifest-src" -> manifestSrc,
        "media-src" -> mediaSrc,
        "object-src" -> objectSrc,
        "base-uri" -> baseUri,
        "frame-ancestors" -> frameAncestors,
        "form-action" -> formAction
      )
      directives.collect { case (name, sources) if sources.nonEmpty => s"$name ${sources.mkString(" ")}" }
        .mkString("; ")
    }
  }

  object Csp {

    /** A locked-down baseline: everything from `'self'`, no objects, no framing, no form
      * submission. A starting point to loosen per the site's real assets.
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
    contentSecurityPolicy: Option[Csp] = None,
    crossOriginResourcePolicy: Option[String] = None,
    crossOriginOpenerPolicy: Option[String] = None
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
        .flatMap { r =>
          config.crossOriginResourcePolicy match {
            case Some(corp) => r.setHeader("Cross-Origin-Resource-Policy", corp)
            case None => Eru.succeed(r)
          }
        }
        .flatMap { r =>
          config.crossOriginOpenerPolicy match {
            case Some(coop) => r.setHeader("Cross-Origin-Opener-Policy", coop)
            case None => Eru.succeed(r)
          }
        }
        .mapError(e => HttpError.NetworkError(s"Security header error: $e"))
    }
  }
}
