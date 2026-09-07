package net.ghoula.melian.server

import java.nio.file.{Files, Path}

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

/** Middleware that serves custom HTML error pages instead of plain text or JSON responses.
  *
  * Wraps the inner handler and replaces error responses (404, 500, etc.) with HTML pages loaded
  * from disk. Falls back to the original response if no custom page exists for that status code.
  */
object ErrorPages {

  final case class Config(
    baseDir: Path,
    pages: Map[Int, String] = Map(404 -> "404.html", 500 -> "500.html")
  )

  def middleware(config: Config)(
    inner: Request[Body] => Eru[HttpError, Response[Body]]
  ): Request[Body] => Eru[HttpError, Response[Body]] = { (request: Request[Body]) =>
    inner(request).flatMap { response =>
      config.pages.get(response.status.value) match {
        case Some(fileName) =>
          loadErrorPage(config.baseDir.resolve(fileName), response.status).flatMap {
            case Some(errorResponse) => Eru.succeed(errorResponse)
            case None => Eru.succeed(response)
          }
        case None => Eru.succeed(response)
      }
    }
  }

  private def loadErrorPage(file: Path, status: StatusCode): Eru[HttpError, Option[Response[Body]]] = {
    Eru.interruptibleBlocking {
      if !Files.exists(file) then None
      else Some(Files.readAllBytes(file))
    }.mapError(e => HttpError.NetworkError(s"Failed to read error page: $file", Some(e))).flatMap {
      case None => Eru.succeed(None)
      case Some(bytes) =>
        val mediaType = MediaType.textHtml.withCharset("utf-8")
        val body = Body.text(String(bytes, "UTF-8"), mediaType)
        val response = Response(status, Headers.empty, body)
        response
          .setHeader(HeaderNames.ContentType, mediaType.value)
          .flatMap(_.setHeader(HeaderNames.ContentLength, bytes.length.toString))
          .mapError(e => HttpError.NetworkError(s"Header error: $e"))
          .map(Some(_))
    }
  }
}
