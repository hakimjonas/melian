package net.ghoula.melian.extraction

import java.util.UUID

import net.ghoula.eru.Eru
import net.ghoula.melian.{ExtractionError, ExtractionSource}

/** Parse a URI query parameter string into a typed value. */
trait FromQueryParam[A] {
  def parse(value: String, paramName: String): Eru[ExtractionError, A]
}

object FromQueryParam {

  def apply[A](using ev: FromQueryParam[A]): FromQueryParam[A] = ev

  private val src: ExtractionSource = ExtractionSource.Query

  given FromQueryParam[String] with {
    def parse(value: String, paramName: String): Eru[ExtractionError, String] = Eru.succeed(value)
  }

  given FromQueryParam[Int] with {
    def parse(value: String, paramName: String): Eru[ExtractionError, Int] =
      ParsePrimitive.int(value, src, paramName)
  }

  given FromQueryParam[Long] with {
    def parse(value: String, paramName: String): Eru[ExtractionError, Long] =
      ParsePrimitive.long(value, src, paramName)
  }

  given FromQueryParam[Boolean] with {
    def parse(value: String, paramName: String): Eru[ExtractionError, Boolean] =
      ParsePrimitive.boolean(value, src, paramName)
  }

  given FromQueryParam[Double] with {
    def parse(value: String, paramName: String): Eru[ExtractionError, Double] =
      ParsePrimitive.double(value, src, paramName)
  }

  given FromQueryParam[UUID] with {
    def parse(value: String, paramName: String): Eru[ExtractionError, UUID] =
      ParsePrimitive.uuid(value, src, paramName)
  }

  given [A](using inner: FromQueryParam[A]): FromQueryParam[Option[A]] with {
    def parse(value: String, paramName: String): Eru[ExtractionError, Option[A]] =
      inner.parse(value, paramName).map(Some(_))
  }
}
