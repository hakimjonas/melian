package net.ghoula.melian.router

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import net.ghoula.eru.Eru
import net.ghoula.eru.http.Body
import net.ghoula.melian.extraction.FormDecoder
import net.ghoula.melian.{FieldError, RequestError}
import net.ghoula.valar.{ValidationResult, Validator}

/** Decodes an `application/x-www-form-urlencoded` request body for [[net.ghoula.melian.Form]].
  *
  * The body is parsed to a flat field map, decoded with the user-supplied [[FormDecoder]], then
  * validated with Valar: the same decode-then-validate shape as JSON and Coded bodies.
  */
object FormBody {

  def decode[A](body: Body)(using
    formDecoder: FormDecoder[A],
    validator: Validator[A]
  ): Eru[RequestError, A] =
    if body.isEmpty then Eru.fail(RequestError.BodyMissing)
    else
      CodedBody
        .readText(body)
        .mapError(e => RequestError.DecodeFailed(List(e.message)))
        .flatMap { text =>
          parseForm(text) match {
            case Left(err) => Eru.fail(RequestError.DecodeFailed(List(err)))
            case Right(form) =>
              formDecoder.decode(form) match {
                case Left(fieldErrors) => Eru.fail(RequestError.ValidationFailed(fieldErrors))
                case Right(value) =>
                  validator.validate(value) match {
                    case ValidationResult.Valid(v) => Eru.succeed(v)
                    case ValidationResult.Invalid(errs) =>
                      Eru.fail(
                        RequestError.ValidationFailed(
                          errs.map(e => FieldError(e.fieldPath.mkString("."), e.message, e.code))
                        )
                      )
                  }
              }
          }
        }

  private def parseForm(text: String): Either[String, Map[String, String]] = {
    val pairs = text.split("&").toList.filter(_.nonEmpty)
    pairs.foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) { (acc, pair) =>
      acc.flatMap { map =>
        val idx = pair.indexOf('=')
        val (rawKey, rawValue) = if idx < 0 then (pair, "") else (pair.substring(0, idx), pair.substring(idx + 1))
        for {
          key <- urlDecode(rawKey)
          value <- urlDecode(rawValue)
        } yield map + (key -> value)
      }
    }
  }

  private def urlDecode(raw: String): Either[String, String] =
    try Right(URLDecoder.decode(raw, StandardCharsets.UTF_8))
    catch {
      case _: IllegalArgumentException => Left(s"Invalid percent-encoding in form field: $raw")
    }
}
