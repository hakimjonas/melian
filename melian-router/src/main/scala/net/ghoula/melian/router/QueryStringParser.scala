package net.ghoula.melian.router

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Parses and URL-decodes a raw URI query string into key-value pairs. */
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
    URLDecoder.decode(s, StandardCharsets.UTF_8)
}
