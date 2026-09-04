package net.ghoula.melian.router

import net.ghoula.eru.Eru
import net.ghoula.eru.http.{Body, MediaType}
import net.ghoula.melian.RequestError
import net.ghoula.sarati.ast.json.{JsonValue, compactFormat, formatJson}
import net.ghoula.sarati.ast.xml.{XmlNode, formatXml}
import net.ghoula.sarati.ast.yaml.{YamlValue, formatYaml}
import net.ghoula.sarati.codec.Encoder

/** Response-side content negotiation for endpoints that declare a `Header[Accept]` parameter.
  *
  * The macro builds a [[ResponseNegotiator]] from the encoders that exist for the body type:
  * `Encoder[A, JsonValue]` is the baseline, `Encoder[A, XmlNode]` and `Encoder[A, YamlValue]` are
  * opt-in. Endpoints without an `Accept` parameter never construct one -- zero overhead.
  *
  * Selection follows RFC 9110 Section 12.5.1: entries with q=0 are unacceptable; the remaining
  * entries are ranked by q-value (default 1). Ties break by range specificity per Section 12.5.2
  * (an exact `type/subtype` beats `type/*`, which beats `*/*`), then by the server's preference
  * order (JSON, XML, YAML). A missing Accept header defaults to JSON. No match answers
  * [[RequestError.NotAcceptable]] (406).
  */
final case class ResponseNegotiator[A](
  json: Encoder[A, JsonValue],
  xml: Option[Encoder[A, XmlNode]],
  yaml: Option[Encoder[A, YamlValue]]
) {

  /** The media types this negotiator can produce, in server preference order. */
  val supportedMediaTypes: List[MediaType] = {
    val base = List(MediaType.applicationJson)
    val withXml = if xml.isDefined then base :+ MediaType.applicationXml else base
    val withYaml = if yaml.isDefined then withXml :+ MediaType("application", "yaml") else withXml
    withYaml
  }

  /** Encodes `value` as the best acceptable media type for the request's Accept header.
    *
    * The error channel is the Girdle's [[RequestError]]: a 406 rides the same `recoverWith` path in
    * [[Router]] as extraction and validation failures.
    */
  def encode(value: A, acceptHeader: Option[String]): Eru[RequestError, Body] = {
    val selected = ResponseNegotiation.select(acceptHeader, supportedMediaTypes)
    selected match {
      case None =>
        Eru.fail(
          RequestError.NotAcceptable(
            supportedMediaTypes,
            acceptHeader.getOrElse("")
          )
        )
      case Some(mediaType) =>
        val bodyText =
          if mediaType.mainType == "application" && mediaType.subType == "xml" then formatXml(xml.get.encode(value))
          else if mediaType.mainType == "application" && mediaType.subType == "yaml" then
            formatYaml(yaml.get.encode(value))
          else formatJson(json.encode(value), compactFormat)
        Eru.succeed(Body.text(bodyText, mediaType))
    }
  }
}

object ResponseNegotiation {

  /** One `type/subtype` (wildcards allowed) with its quality value. */
  final case class AcceptEntry(pattern: MediaType, quality: Double)

  /** Picks the best supported media type for an Accept header.
    *
    * A missing or empty header accepts anything, so the server's first preference (JSON) wins.
    * Entries with q=0 are unacceptable (RFC 9110 Section 12.5.1). Among the rest, the best q-value
    * wins; ties break by range specificity (Section 12.5.2: an exact `type/subtype` beats `type/*`,
    * which beats `*/*`), then by the server's preference order.
    */
  def select(acceptHeader: Option[String], supported: List[MediaType]): Option[MediaType] =
    acceptHeader match {
      case None | Some("") => supported.headOption
      case Some(header) =>
        val candidates = parseAccept(header).filter(_.quality > 0.0).flatMap { entry =>
          // Server preference decides which supported type represents an entry: the first
          // supported type that the entry's pattern accepts.
          supported.find(s => s.matches(entry.pattern)).map(s => (entry, s))
        }
        if candidates.isEmpty then None
        else {
          val bestQ = candidates.map(_._1.quality).max
          val topQ = candidates.filter(_._1.quality == bestQ)
          val best =
            topQ.maxBy { case (entry, s) => (specificity(entry.pattern), -supported.indexOf(s)) }
          Some(best._2)
        }
    }

  /** Media-range specificity per RFC 9110 Section 12.5.2: `type/subtype` (2) beats `type/*` (1)
    * beats `*/*` (0).
    */
  private def specificity(pattern: MediaType): Int =
    if pattern.mainType == "*" && pattern.subType == "*" then 0
    else if pattern.subType == "*" then 1
    else 2

  /** Parses an Accept header into entries ordered by descending q (stable for equal q).
    *
    * Malformed entries are skipped; `q` outside 0..1 is clamped.
    */
  def parseAccept(header: String): List[AcceptEntry] =
    header
      .split(",")
      .toList
      .flatMap { raw =>
        raw.trim match {
          case "" => None
          case part =>
            val segments = part.split(";").toList.map(_.trim)
            segments.headOption.flatMap { typeSegment =>
              parseTypeSegment(typeSegment).map { pattern =>
                val q = segments.tail.collectFirst {
                  case s if s.startsWith("q=") || s.startsWith("Q=") =>
                    s.drop(2).trim.toDoubleOption.getOrElse(0.0)
                }.getOrElse(1.0)
                AcceptEntry(pattern, q.min(1.0).max(0.0))
              }
            }
        }
      }
      .sortBy(-_.quality)

  private def parseTypeSegment(segment: String): Option[MediaType] = {
    val parts = segment.split("/").toList.map(_.trim.toLowerCase)
    parts match {
      case main :: sub :: Nil if main.nonEmpty && sub.nonEmpty => Some(MediaType(main, sub))
      case _ => None
    }
  }
}
