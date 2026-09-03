package net.ghoula.melian.router

import net.ghoula.eru.Eru
import net.ghoula.eru.http.{Body, MediaType, Response, StatusCode}
import net.ghoula.melian.{ExtractionError, ExtractionSource, FieldError, RequestError}
import net.ghoula.sarati.ast.json.{JsonValue, compactFormat, formatJson}

/** RFC 9457 Problem Details renderer for Melian's RequestError types.
  *
  * Produces `application/problem+json` responses from extraction, parsing, decoding, and validation
  * errors accumulated by the Girdle pipeline.
  */
object ProblemDetails {

  private val problemJsonMediaType: MediaType = MediaType("application", "problem+json")

  /* 422 and 406 ship in eru-http's StatusCode registry as of 1.0.0-alpha.2. */
  private val UnprocessableContent: StatusCode = StatusCode.UnprocessableContent
  private val NotAcceptable: StatusCode = StatusCode.NotAcceptable

  def render(error: RequestError): Response[Body] = {
    val (status, json) = error match {
      case RequestError.ExtractionFailed(errors) =>
        (StatusCode.BadRequest, extractionErrors(errors))
      case RequestError.ParseFailed(errors) =>
        (StatusCode.BadRequest, parseErrors(errors))
      case RequestError.DecodeFailed(errors) =>
        (StatusCode.BadRequest, decodeErrors(errors))
      case RequestError.ValidationFailed(errors) =>
        (UnprocessableContent, validationErrors(errors))
      case RequestError.BodyMissing =>
        (StatusCode.BadRequest, simple("Bad Request", "Request body is required"))
      case RequestError.UnsupportedMediaType(expected, actual) =>
        val detail = actual match {
          case Some(mt) => s"Expected one of ${expected.mkString(", ")}, got $mt"
          case None => s"Expected one of ${expected.mkString(", ")}, Content-Type header missing"
        }
        (StatusCode.UnsupportedMediaType, simple("Unsupported Media Type", detail))
      case RequestError.NotAcceptable(supported, acceptHeader) =>
        (
          NotAcceptable,
          simple(
            "Not Acceptable",
            s"Accept header '$acceptHeader' matches none of ${supported.mkString(", ")}"
          )
        )
    }
    Response(
      status,
      net.ghoula.eru.http.Headers.empty,
      Body.text(formatJson(json, compactFormat), problemJsonMediaType)
    )
  }

  /** The built-in global error tier: renders a domain error that no `ErrorRenderer` given and no
    * builder-level renderer claimed as a generic 500 problem+json. The error value is deliberately
    * not serialized -- `toString` of an arbitrary domain error can leak internals.
    */
  def renderDomainFallback(error: Any): Eru[Nothing, Response[Body]] = {
    val _ = error
    Eru.succeed(
      Response(
        StatusCode.InternalServerError,
        net.ghoula.eru.http.Headers.empty,
        Body.text(
          formatJson(simple("Internal Server Error", "The server failed to process the request"), compactFormat),
          problemJsonMediaType
        )
      )
    )
  }

  private def simple(title: String, detail: String): JsonValue =
    JsonValue.Object(
      Map(
        "type" -> JsonValue.Str("about:blank"),
        "title" -> JsonValue.Str(title),
        "detail" -> JsonValue.Str(detail)
      )
    )

  private def extractionErrors(errors: Vector[ExtractionError]): JsonValue =
    JsonValue.Object(
      Map(
        "type" -> JsonValue.Str("about:blank"),
        "title" -> JsonValue.Str("Bad Request"),
        "detail" -> JsonValue.Str(s"${errors.size} extraction error(s)"),
        "errors" -> JsonValue.Array(errors.toList.map { e =>
          JsonValue.Object(
            Map(
              "in" -> JsonValue.Str(sourceToString(e.source)),
              "field" -> JsonValue.Str(e.field),
              "detail" -> JsonValue.Str(e.message)
            ) ++ e.expected.map(v => "expected" -> JsonValue.Str(v))
              ++ e.actual.map(v => "actual" -> JsonValue.Str(v))
          )
        })
      )
    )

  private def parseErrors(errors: List[String]): JsonValue =
    JsonValue.Object(
      Map(
        "type" -> JsonValue.Str("about:blank"),
        "title" -> JsonValue.Str("Bad Request"),
        "detail" -> JsonValue.Str("Malformed request body"),
        "errors" -> JsonValue.Array(errors.map(e => JsonValue.Str(e)))
      )
    )

  private def decodeErrors(errors: List[String]): JsonValue =
    JsonValue.Object(
      Map(
        "type" -> JsonValue.Str("about:blank"),
        "title" -> JsonValue.Str("Bad Request"),
        "detail" -> JsonValue.Str("Invalid request body structure"),
        "errors" -> JsonValue.Array(errors.map(e => JsonValue.Str(e)))
      )
    )

  private def validationErrors(errors: Vector[FieldError]): JsonValue =
    JsonValue.Object(
      Map(
        "type" -> JsonValue.Str("about:blank"),
        "title" -> JsonValue.Str("Unprocessable Entity"),
        "detail" -> JsonValue.Str(s"${errors.size} validation error(s)"),
        "errors" -> JsonValue.Array(errors.toList.map { e =>
          JsonValue.Object(
            Map(
              "path" -> JsonValue.Str(e.path),
              "detail" -> JsonValue.Str(e.message)
            ) ++ e.code.map(c => "code" -> JsonValue.Str(c))
          )
        })
      )
    )

  private def sourceToString(source: ExtractionSource): String = source match {
    case ExtractionSource.Path => "path"
    case ExtractionSource.Query => "query"
    case ExtractionSource.Header => "header"
  }
}
