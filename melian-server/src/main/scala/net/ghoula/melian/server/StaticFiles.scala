package net.ghoula.melian.server

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

/** Middleware that serves static files from a directory on disk.
  *
  * Handles media type detection, path traversal protection, cache control, ETag, and conditional
  * requests (`If-None-Match`, `If-Modified-Since`). Passes through to the inner handler when no
  * file matches.
  */
object StaticFiles {

  final case class Config(
    baseDir: Path,
    prefix: String = "/",
    cacheControl: String = "public, max-age=31536000, immutable",
    etag: Boolean = true
  )

  private val mediaTypes: Map[String, MediaType] = Map(
    "html" -> MediaType.textHtml.withCharset("utf-8"),
    "css" -> MediaType.textCss.withCharset("utf-8"),
    "js" -> MediaType("text", "javascript").withCharset("utf-8"),
    "mjs" -> MediaType("text", "javascript").withCharset("utf-8"),
    "json" -> MediaType.applicationJson,
    "xml" -> MediaType.applicationXml,
    "svg" -> MediaType("image", "svg+xml"),
    "png" -> MediaType.imagePng,
    "jpg" -> MediaType.imageJpeg,
    "jpeg" -> MediaType.imageJpeg,
    "webp" -> MediaType("image", "webp"),
    "gif" -> MediaType.imageGif,
    "ico" -> MediaType("image", "x-icon"),
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
            serveFile(file, config.cacheControl, config.etag, request).flatMap {
              case Some(response) => Eru.succeed(response)
              case None => inner(request)
            }
          }
      }
    }
  }

  private def serveFile(
    file: Path,
    cacheControl: String,
    useEtag: Boolean,
    request: Request[Body]
  ): Eru[HttpError, Option[Response[Body]]] = {
    Eru.interruptibleBlocking {
      if !Files.exists(file) || Files.isDirectory(file) then None
      else {
        val mediaType = mediaTypeFor(file.toString)
        val bytes = Files.readAllBytes(file)
        val lastModified = Files.getLastModifiedTime(file).toInstant
        Some((mediaType, bytes, lastModified))
      }
    }.mapError(e => HttpError.NetworkError(s"Failed to read file: $file", Some(e))).flatMap {
      case None => Eru.succeed(None)
      case Some((mediaType, bytes, lastModified)) =>
        val etag = Option.when(useEtag)(ETag.strong(etagFor(bytes)))
        val notModified =
          etag.exists(matchesIfNoneMatch(request, _)) || matchesIfModifiedSince(request, lastModified)

        if notModified then Eru.succeed(Some(Response(StatusCode.NotModified, Headers.empty, Body.Empty)))
        else {
          val body =
            if mediaType.mainType == "text" || mediaType.subType == "json" || mediaType.subType == "xml" || mediaType.subType == "svg+xml"
            then Body.text(String(bytes, "UTF-8"), mediaType)
            else Body.binary(Bytes.fromArray(bytes), mediaType)

          val withHeaders = for {
            r0 <- Eru.succeed(Response.ok(body))
            r1 <- r0.setHeader(HeaderNames.ContentType, mediaType.value)
            r2 <- r1.setHeader(HeaderNames.ContentLength, bytes.length.toString)
            r3 <- r2.setHeader(HeaderNames.CacheControl, cacheControl)
            r4 <- r3.setHeader(HeaderNames.LastModified, HttpDate.format(lastModified))
            r5 <- etag match {
              case Some(t) => r4.setHeader(HeaderNames.ETag, t.headerValue)
              case None => Eru.succeed(r4)
            }
          } yield r5

          withHeaders
            .mapError(e => HttpError.NetworkError(s"Header error: $e"))
            .map(Some(_))
        }
    }
  }

  private def etagFor(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    HexFormat.of().formatHex(digest.take(16))
  }

  private def matchesIfNoneMatch(request: Request[Body], etag: ETag): Boolean =
    request.headers.getFirst(HeaderNames.IfNoneMatch) match {
      case Some(h) =>
        ETag.parseMultiple(h.value).attempt.unsafeRunSync() match {
          case net.ghoula.eru.Result.Success(tags) => etag.matchesAny(tags, strongComparison = true)
          case net.ghoula.eru.Result.Failure(_) => false
        }
      case None => false
    }

  private def matchesIfModifiedSince(request: Request[Body], lastModified: Instant): Boolean =
    request.headers.getFirst(HeaderNames.IfModifiedSince) match {
      case Some(h) =>
        HttpDate.parse(h.value).attempt.unsafeRunSync() match {
          case net.ghoula.eru.Result.Success(ifModifiedSince) => !lastModified.isAfter(ifModifiedSince)
          case net.ghoula.eru.Result.Failure(_) => false
        }
      case None => false
    }
}
