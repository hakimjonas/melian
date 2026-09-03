package net.ghoula.melian

import scala.concurrent.duration.Duration

import net.ghoula.eru.http.Uri

/** Response type wrappers encoding HTTP status semantics.
  *
  * The compiler maps each to its eru-http StatusCode. Body and header constraints are delegated to
  * StatusCode's own semantic methods (allowsResponseBody, requiredHeaders) rather than duplicated.
  */

final case class Ok[A](body: A)
final case class Created[A](body: A, location: Option[Uri] = None)
final case class Accepted[A](body: A)
case object NoContent
final case class SeeOther(location: Uri)
case object NotModified
final case class EventStream[A](source: A)

/** 401 Unauthorized. The challenge string becomes the mandatory `WWW-Authenticate` header; the body
  * is encoded like [[Ok]]'s.
  */
final case class Unauthorized[A](challenge: String, body: A)

/** 429 Too Many Requests. The duration becomes the `Retry-After` header (delta-seconds, truncated
  * toward zero); the body is encoded like [[Ok]]'s.
  */
final case class TooManyRequests[A](retryAfter: Duration, body: A)

/** Any status that has no dedicated wrapper, via a phantom literal: `Status[206, Partial]`.
  *
  * The status code exists only at the type level, so the macro reads it at compile time and
  * validates it there: the code must be a literal in 100-599, it must allow a response body, and it
  * must not require headers (a status that requires `WWW-Authenticate`, `Allow`, or similar has a
  * dedicated wrapper or is a router-level response). Violations are compile errors.
  */
final case class Status[Code <: Int & Singleton, A](body: A)
