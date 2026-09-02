package net.ghoula.melian

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
