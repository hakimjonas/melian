package net.ghoula.melian

/** Controls how much detail error responses expose to clients.
  *
  * In development, full error details (type names, field paths, parser state) aid debugging. In
  * production, these details can leak internal architecture to attackers.
  */
trait ErrorSanitizer {
  def sanitize(error: RequestError): RequestError
}

object ErrorSanitizer {

  /** Development mode: pass all error details through unchanged. */
  given development: ErrorSanitizer with {
    def sanitize(error: RequestError): RequestError = error
  }

  /** Production mode: strip internal type names and parser state from errors. */
  val production: ErrorSanitizer = new ErrorSanitizer {
    def sanitize(error: RequestError): RequestError = error match {
      case RequestError.ExtractionFailed(errors) =>
        RequestError.ExtractionFailed(errors.map(e => e.copy(
          message = genericMessage(e.source),
          expected = None,
          actual = None
        )))
      case RequestError.ParseFailed(_) =>
        RequestError.ParseFailed(List("Malformed request body"))
      case RequestError.DecodeFailed(_) =>
        RequestError.DecodeFailed(List("Invalid request body structure"))
      case RequestError.ValidationFailed(errors) =>
        RequestError.ValidationFailed(errors.map(e => e.copy(code = None)))
      case other => other
    }

    private def genericMessage(source: ExtractionSource): String = source match {
      case ExtractionSource.Path => "Invalid path parameter"
      case ExtractionSource.Query => "Invalid query parameter"
      case ExtractionSource.Header => "Invalid header value"
    }
  }
}
