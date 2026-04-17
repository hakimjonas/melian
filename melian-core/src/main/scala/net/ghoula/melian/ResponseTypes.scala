package net.ghoula.melian

import net.ghoula.eru.http.{StatusCode, Uri}

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

/** Maps a response wrapper to its HTTP status code. */
trait ResponseStatus[R] {
  def statusCode: StatusCode
}

object ResponseStatus {

  given [A]: ResponseStatus[Ok[A]] with {
    def statusCode: StatusCode = StatusCode.Ok
  }

  given [A]: ResponseStatus[Created[A]] with {
    def statusCode: StatusCode = StatusCode.Created
  }

  given [A]: ResponseStatus[Accepted[A]] with {
    def statusCode: StatusCode = StatusCode.Accepted
  }

  given ResponseStatus[NoContent.type] with {
    def statusCode: StatusCode = StatusCode.NoContent
  }

  given ResponseStatus[SeeOther] with {
    def statusCode: StatusCode = StatusCode.SeeOther
  }

  given ResponseStatus[NotModified.type] with {
    def statusCode: StatusCode = StatusCode.NotModified
  }

  given [A]: ResponseStatus[EventStream[A]] with {
    def statusCode: StatusCode = StatusCode.Ok
  }
}
