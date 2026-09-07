package net.ghoula.melian.extraction

import net.ghoula.eru.Eru
import net.ghoula.melian.{ExtractionError, ExtractionSource}

/** Parse an HTTP header value into a typed value.
  *
  * Also carries the header name, since the mapping from Scala type to HTTP header name is part of
  * the typeclass (e.g., BearerToken -> "Authorization").
  */
trait FromHeader[A] {
  def headerName: String
  def parse(value: String): Eru[ExtractionError, A]
}

object FromHeader {

  def apply[A](using ev: FromHeader[A]): FromHeader[A] = ev

  private def headerError(header: String, message: String, expected: String, actual: String): ExtractionError =
    ExtractionError(ExtractionSource.Header, header, message, Some(expected), Some(actual))

  final case class Authorization(value: String) derives CanEqual

  given FromHeader[Authorization] with {
    def headerName: String = "Authorization"
    def parse(value: String): Eru[ExtractionError, Authorization] =
      Eru.succeed(Authorization(value))
  }

  final case class BearerToken(token: String) derives CanEqual

  given FromHeader[BearerToken] with {
    def headerName: String = "Authorization"
    def parse(value: String): Eru[ExtractionError, BearerToken] =
      value match {
        case s"Bearer $token" => Eru.succeed(BearerToken(token))
        case _ => Eru.fail(headerError("Authorization", "expected Bearer token", "Bearer <token>", value.take(20)))
      }
  }

  final case class Accept(value: String) derives CanEqual

  given FromHeader[Accept] with {
    def headerName: String = "Accept"
    def parse(value: String): Eru[ExtractionError, Accept] =
      Eru.succeed(Accept(value))
  }

  final case class ContentType(value: String) derives CanEqual

  given FromHeader[ContentType] with {
    def headerName: String = "Content-Type"
    def parse(value: String): Eru[ExtractionError, ContentType] =
      Eru.succeed(ContentType(value))
  }
}
