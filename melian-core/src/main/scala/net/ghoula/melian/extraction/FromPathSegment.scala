package net.ghoula.melian.extraction

import net.ghoula.eru.Eru
import net.ghoula.melian.{ExtractionError, ExtractionSource}

import java.util.UUID

/** Parse a URI path segment string into a typed value. */
trait FromPathSegment[A] {
  def parse(segment: String, paramName: String): Eru[ExtractionError, A]
}

object FromPathSegment {

  def apply[A](using ev: FromPathSegment[A]): FromPathSegment[A] = ev

  private val src: ExtractionSource = ExtractionSource.Path

  given FromPathSegment[String] with {
    def parse(segment: String, paramName: String): Eru[ExtractionError, String] = Eru.succeed(segment)
  }

  given FromPathSegment[Int] with {
    def parse(segment: String, paramName: String): Eru[ExtractionError, Int] =
      ParsePrimitive.int(segment, src, paramName)
  }

  given FromPathSegment[Long] with {
    def parse(segment: String, paramName: String): Eru[ExtractionError, Long] =
      ParsePrimitive.long(segment, src, paramName)
  }

  given FromPathSegment[Boolean] with {
    def parse(segment: String, paramName: String): Eru[ExtractionError, Boolean] =
      ParsePrimitive.boolean(segment, src, paramName)
  }

  given FromPathSegment[UUID] with {
    def parse(segment: String, paramName: String): Eru[ExtractionError, UUID] =
      ParsePrimitive.uuid(segment, src, paramName)
  }
}
