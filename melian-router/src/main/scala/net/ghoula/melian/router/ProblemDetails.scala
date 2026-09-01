package net.ghoula.melian.router

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

  /* 422 Unprocessable Content is not in eru-http's StatusCode set, so it is
   * constructed through the validating `apply` (the code is constant and valid). */
  private val UnprocessableContent: StatusCode = StatusCode(422).unsafeRunSync()

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
          case Some(mt) => s"Expected $expected, got $mt"
          case None => s"Expected $expected, Content-Type header missing"
        }
        (StatusCode.UnsupportedMediaType, simple("Unsupported Media Type", detail))
    }
    Response(
      status,
      net.ghoula.eru.http.Headers.empty,
      Body.text(formatJson(json, compactFormat), problemJsonMediaType)
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
