package net.ghoula.melian.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

/** Cross-site request forgery protection via the double-submit cookie pattern.
  *
  * Safe requests (GET, HEAD, OPTIONS, TRACE, QUERY) pass through; if the browser has no CSRF cookie
  * yet, one is issued and attached to the response. State-changing requests (POST, PUT, PATCH,
  * DELETE) must present the token twice -- in the cookie and in the configured header -- and the
  * two copies must match; anything else answers 403 Forbidden.
  *
  * The comparison is constant-time, so timing cannot be used to recover the token.
  */
object Csrf {

  final case class Config(
    cookieName: String = "__csrf",
    headerName: String = "X-CSRF-Token",
    secureCookie: Boolean = true,
    sameSite: SameSite = SameSite.Strict,
    path: String = "/"
  )

  private val secureRandom = new SecureRandom()

  private def newToken: String = {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def readCookieToken(request: Request[Body], config: Config): Option[String] =
    request.headers.getFirst(HeaderNames.Cookie).flatMap { cookieHeader =>
      Cookie.parseCookie(cookieHeader.value).attempt.unsafeRunSync() match {
        case net.ghoula.eru.Result.Success(cookies) =>
          cookies.find(_.name == config.cookieName).map(_.value)
        case net.ghoula.eru.Result.Failure(_) => None
      }
    }

  /** Constant-time token comparison. */
  private def tokensMatch(a: String, b: String): Boolean =
    MessageDigest.isEqual(
      a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      b.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )

  private def forbidden: Response[Body] =
    Response(StatusCode.Forbidden, Headers.empty, Body.text("CSRF token missing or invalid"))

  private def issueCookie(value: String, config: Config): Cookie =
    Cookie(
      name = config.cookieName,
      value = value,
      path = Some(config.path),
      secure = config.secureCookie,
      httpOnly = true,
      sameSite = Some(config.sameSite)
    )

  def middleware(config: Config = Config())(
    inner: Request[Body] => Eru[HttpError, Response[Body]]
  ): Request[Body] => Eru[HttpError, Response[Body]] = { (request: Request[Body]) =>
    val cookieToken = readCookieToken(request, config)

    if request.method.isSafe then
      inner(request).flatMap { response =>
        if cookieToken.isEmpty then {
          val token = newToken
          // addHeader, not setHeader: Set-Cookie is multi-valued, and other cookie-issuing
          // middleware (e.g. Session) may already have added one.
          response
            .addHeader(HeaderNames.SetCookie, issueCookie(token, config).toSetCookieHeader)
            .mapError { case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
              HttpError.InvalidResponse(InvalidResponse(err.toString, "Set-Cookie header"))
            }
        } else Eru.succeed(response)
      }
    else {
      val headerToken = request.headers.getFirst(config.headerName).map(_.value)
      (cookieToken, headerToken) match {
        case (Some(cookie), Some(headerValue)) if tokensMatch(cookie, headerValue) => inner(request)
        case _ => Eru.succeed(forbidden)
      }
    }
  }
}
