package net.ghoula.melian.server

import java.nio.file.{Files, Path}

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

/** Middleware that serves static files from a directory on disk.
  *
  * Handles media type detection, path traversal protection, and cache control. Passes through to
  * the inner handler when no file matches.
  */
object StaticFiles {

  final case class Config(
    baseDir: Path,
    prefix: String = "/",
    cacheControl: String = "public, max-age=31536000, immutable"
  )

  private val mediaTypes: Map[String, MediaType] = Map(
    "html" -> MediaType.textHtml.withCharset("utf-8"),
    "css" -> MediaType.textCss.withCharset("utf-8"),
    "js" -> MediaType.textJavascript.withCharset("utf-8"),
    "mjs" -> MediaType.textJavascript.withCharset("utf-8"),
    "json" -> MediaType.applicationJson,
    "xml" -> MediaType.applicationXml,
    "svg" -> MediaType.imageSvgXml,
    "png" -> MediaType.imagePng,
    "jpg" -> MediaType.imageJpeg,
    "jpeg" -> MediaType.imageJpeg,
    "webp" -> MediaType.imageWebp,
    "gif" -> MediaType.imageGif,
    "ico" -> MediaType.imageIcon,
    "wasm" -> MediaType("application", "wasm"),
    "woff" -> MediaType("font", "woff"),
    "woff2" -> MediaType("font", "woff2"),
    "ttf" -> MediaType("font", "ttf"),
    "otf" -> MediaType("font", "otf"),
    "pdf" -> MediaType.applicationPdf,
    "zip" -> MediaType.applicationZip,
    "md" -> MediaType.textPlain.withCharset("utf-8"),
    "txt" -> MediaType.textPlain.withCharset("utf-8")
  )

  private def mediaTypeFor(path: String): MediaType = {
    val ext = path.lastIndexOf('.') match {
      case -1 => ""
      case i => path.substring(i + 1).toLowerCase
    }
    mediaTypes.getOrElse(ext, MediaType.applicationOctetStream)
  }

  def middleware(config: Config)(
    inner: Request[Body] => Eru[HttpError, Response[Body]]
  ): Request[Body] => Eru[HttpError, Response[Body]] = { (request: Request[Body]) =>
    if request.method.value != "GET" && request.method.value != "HEAD" then inner(request)
    else {
      val path = request.uri.path
      val normalizedPrefix = if config.prefix.endsWith("/") then config.prefix else config.prefix + "/"

      val relativePathOpt: Option[String] =
        if config.prefix == "/" then Some(path.stripPrefix("/"))
        else if path.startsWith(normalizedPrefix) then Some(path.stripPrefix(normalizedPrefix))
        else if path == config.prefix.stripSuffix("/") then Some("")
        else None

      relativePathOpt match {
        case None => inner(request)
        case Some(rel) if rel.contains("..") =>
          Eru.succeed(Response.badRequest(Body.text("Bad Request")))
        case Some(rel) =>
          val file = config.baseDir.resolve(rel).normalize()
          if !file.startsWith(config.baseDir) then Eru.succeed(Response.badRequest(Body.text("Bad Request")))
          else {
            serveFile(file, config.cacheControl).flatMap {
              case Some(response) => Eru.succeed(response)
              case None => inner(request)
            }
          }
      }
    }
  }

  private def serveFile(file: Path, cacheControl: String): Eru[HttpError, Option[Response[Body]]] = {
    Eru.interruptibleBlocking {
      if !Files.exists(file) || Files.isDirectory(file) then None
      else {
        val mediaType = mediaTypeFor(file.toString)
        val bytes = Files.readAllBytes(file)
        Some((mediaType, bytes))
      }
    }.mapError(e => HttpError.NetworkError(s"Failed to read file: $file", Some(e))).flatMap {
      case None => Eru.succeed(None)
      case Some((mediaType, bytes)) =>
        val body =
          if mediaType.isText || mediaType.subType == "svg+xml" then Body.text(String(bytes, "UTF-8"), mediaType)
          else Body.binary(Bytes.fromArray(bytes), mediaType)
        val response = Response.ok(body)
        response
          .setHeader(HeaderNames.ContentType, mediaType.value)
          .flatMap(_.setHeader(HeaderNames.ContentLength, bytes.length.toString))
          .flatMap(_.setHeader(HeaderNames.CacheControl, cacheControl))
          .mapError(e => HttpError.NetworkError(s"Header error: $e"))
          .map(Some(_))
    }
  }
}
