package net.ghoula.melian.router

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Parses and percent-decodes a raw URI query string into key-value pairs.
  *
  * The query component uses percent-encoding per RFC 3986, where `+` is a literal plus character.
  * This differs from `application/x-www-form-urlencoded`, where `+` means space; form bodies are
  * decoded by [[net.ghoula.melian.router.FormBody]] with the form semantics instead.
  */
object QueryStringParser {

  def parse(query: String): Map[String, String] = query match {
    case "" => Map.empty
    case q =>
      q.split("&")
        .flatMap { pair =>
          pair.indexOf('=') match {
            case -1 => Some((decode(pair), ""))
            case idx =>
              val key = decode(pair.substring(0, idx))
              val value = decode(pair.substring(idx + 1))
              if key.isEmpty then None
              else Some((key, value))
          }
        }
        .toMap
  }

  private def decode(s: String): String =
    try URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)
    catch { case _: IllegalArgumentException => s }
}
