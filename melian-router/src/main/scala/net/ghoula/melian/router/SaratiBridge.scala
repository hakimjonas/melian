package net.ghoula.melian.router

import parser.core.Result as RumilResult
import parsers.json.parseJson

import net.ghoula.eru.Eru
import net.ghoula.eru.http.{Body, BodyDecoder, BodyEncoder, DecodeError, EncodeError, Headers, MediaType}
import net.ghoula.sarati.Result as SaratiResult
import net.ghoula.sarati.ast.json.{JsonValue, compactFormat, formatJson}
import net.ghoula.sarati.codec.{Decoder, Encoder}

/** Bridges Sarati's AST codecs to eru-http's BodyEncoder/BodyDecoder.
  *
  * Two levels of integration:
  *   - BodyEncoder/BodyDecoder: standard eru-http givens for simple encode/decode
  *   - decodeJsonBody: richer function preserving partial-success warnings from Rumil and Sarati,
  *     surfaced to Endpoint handlers through RequestContext.warnings
  */
object SaratiBridge {

  val MaxJsonDepth: Int = 64

  final case class DecodeResult[A](value: A, warnings: List[String]) derives CanEqual

  // --- BodyEncoder: A → JsonValue → JSON string → Body ---

  given jsonBodyEncoder[A](using sarati: Encoder[A, JsonValue]): BodyEncoder[A] with {
    def encode(value: A, mediaType: Option[MediaType] = None): Eru[EncodeError, Body] = {
      val jsonStr = formatJson(sarati.encode(value), compactFormat)
      Eru.succeed(Body.text(jsonStr, mediaType.getOrElse(defaultMediaType)))
    }
    def defaultMediaType: MediaType = MediaType.applicationJson
  }

  // --- BodyDecoder: Body → String → JsonValue → A (standard eru-http, drops warnings) ---

  given jsonBodyDecoder[A](using sarati: Decoder[JsonValue, A]): BodyDecoder[A] with {
    def decode(body: Body): Eru[DecodeError, A] =
      decodeJsonBody(body).map(_.value)

    def supportedMediaTypes: List[MediaType] = List(MediaType.applicationJson)
  }

  // --- Content-Type validation ---

  def validateContentType(headers: Headers): Eru[DecodeError, Unit] =
    headers.contentTypeRaw match {
      case None => Eru.succeed(())
      case Some(ct) =>
        MediaType.parse(ct) match {
          case _ if ct.contains("json") => Eru.succeed(())
          case _ =>
            Eru.fail(
              DecodeError(
                s"Unsupported Content-Type: $ct. Expected application/json.",
                None
              )
            )
        }
    }

  // --- Richer decode preserving warnings (Girdle pipeline) ---

  def decodeJsonBody[A](body: Body)(using
    sarati: Decoder[JsonValue, A]
  ): Eru[DecodeError, DecodeResult[A]] =
    CodedBody.readText(body).flatMap { text =>
      parseJson(text) match {
        case RumilResult.Success(json, _) =>
          guardDepth(json).flatMap(j => decodeSarati(j, Nil))
        case RumilResult.Partial(json, parseErrors, _) =>
          val warnings = parseErrors.map(e => s"parse warning: $e")
          guardDepth(json).flatMap(j => decodeSarati(j, warnings))
        case RumilResult.Failure(errors, _) =>
          Eru.fail(DecodeError(s"JSON parse failed: ${errors.mkString("; ")}", None))
      }
    }

  // --- JSON depth guard (security: prevents stack overflow from nested payloads) ---

  private def guardDepth(json: JsonValue): Eru[DecodeError, JsonValue] =
    if depth(json) > MaxJsonDepth then Eru.fail(DecodeError(s"JSON nesting depth exceeds limit of $MaxJsonDepth", None))
    else Eru.succeed(json)

  private def depth(json: JsonValue): Int = json match {
    case JsonValue.Array(elements) =>
      if elements.isEmpty then 1 else 1 + elements.iterator.map(depth).max
    case JsonValue.Object(fields) =>
      if fields.isEmpty then 1 else 1 + fields.valuesIterator.map(depth).max
    case _ => 0
  }

  // --- Sarati decode with warning accumulation ---

  private def decodeSarati[A](json: JsonValue, priorWarnings: List[String])(using
    sarati: Decoder[JsonValue, A]
  ): Eru[DecodeError, DecodeResult[A]] =
    sarati.decode(json) match {
      case SaratiResult.Success(value, _) =>
        Eru.succeed(DecodeResult(value, priorWarnings))
      case SaratiResult.Partial(value, decodeErrors, _) =>
        val decodeWarnings = decodeErrors.map(e => s"decode warning: $e")
        Eru.succeed(DecodeResult(value, priorWarnings ++ decodeWarnings))
      case SaratiResult.Failure(errors, _) =>
        Eru.fail(DecodeError(s"Decode failed: ${errors.mkString("; ")}", None))
    }
}
