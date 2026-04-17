package net.ghoula.melian.router

import net.ghoula.eru.Eru
import net.ghoula.eru.http.{Body, BodyDecoder, BodyEncoder, DecodeError, EncodeError, MediaType}
import net.ghoula.sarati.ast.json.{JsonValue, formatJson, compactFormat}
import net.ghoula.sarati.codec.{Decoder, Encoder}
import net.ghoula.sarati.{Result as SaratiResult}

import parser.core.{Result as RumilResult}
import parsers.json.parseJson

/** Bridges Sarati's AST codecs to eru-http's BodyEncoder/BodyDecoder.
  *
  * Composes eru-http's existing body handling (text extraction, content-type, streaming) with
  * Sarati's format-agnostic codecs and Rumil's parsers. Melian's macro summons these bridge
  * instances so that Response.okEncoded and request body decoding just work with domain types
  * that have Sarati codecs.
  */
object SaratiBridge {

  /** Encodes A → JsonValue → JSON string → Body via eru-http's BodyEncoder. */
  given jsonBodyEncoder[A](using sarati: Encoder[A, JsonValue]): BodyEncoder[A] with {
    def encode(value: A, mediaType: Option[MediaType] = None): Eru[EncodeError, Body] = {
      val jsonStr = formatJson(sarati.encode(value), compactFormat)
      Eru.succeed(Body.text(jsonStr, mediaType.getOrElse(defaultMediaType)))
    }
    def defaultMediaType: MediaType = MediaType.applicationJson
  }

  /** Decodes Body → String → JsonValue → A by chaining eru-http's BodyDecoder[String] with
    * Rumil's parseJson and Sarati's Decoder.
    */
  given jsonBodyDecoder[A](using
    sarati: Decoder[JsonValue, A],
    textDecoder: BodyDecoder[String]
  ): BodyDecoder[A] with {
    def decode(body: Body): Eru[DecodeError, A] =
      textDecoder.decode(body).flatMap { text =>
        parseJson(text) match {
          case RumilResult.Success(json, _) => decodeSarati(json)
          case RumilResult.Partial(json, _, _) => decodeSarati(json)
          case RumilResult.Failure(errors, _) =>
            Eru.fail(DecodeError(s"JSON parse failed: ${errors.mkString("; ")}", None))
        }
      }

    def supportedMediaTypes: List[MediaType] = List(MediaType.applicationJson)
  }

  private def decodeSarati[A](json: JsonValue)(using sarati: Decoder[JsonValue, A]): Eru[DecodeError, A] =
    sarati.decode(json) match {
      case SaratiResult.Success(value, _) => Eru.succeed(value)
      case SaratiResult.Partial(value, _, _) => Eru.succeed(value)
      case SaratiResult.Failure(errors, _) =>
        Eru.fail(DecodeError(s"Decode failed: ${errors.mkString("; ")}", None))
    }
}
