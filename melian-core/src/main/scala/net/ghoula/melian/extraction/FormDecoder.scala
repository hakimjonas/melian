package net.ghoula.melian.extraction

import scala.deriving.Mirror
import scala.quoted.*

import net.ghoula.melian.FieldError

/** Decodes a `application/x-www-form-urlencoded` body into a typed value.
  *
  * The form is parsed to a flat `Map[String, String]` of decoded fields; this typeclass turns that
  * map into `A`. Unlike the per-parameter extractors (`FromPathSegment`, `FromQueryParam`), a form
  * body is decoded as a whole, so a [[FormDecoder]] reads every field at once and reports all
  * failures together.
  */
trait FormDecoder[A] {
  def decode(form: Map[String, String]): Either[Vector[FieldError], A]

  /** The field names this decoder reads. Form keys outside this set are reported as decode warnings
    * (not errors) by the [[net.ghoula.melian.Form]] pipeline. Empty by default, which disables the
    * check: hand-written decoders opt in by declaring the fields they consume.
    */
  def knownFields: Set[String] = Set.empty
}

object FormDecoder {

  /** Derives a [[FormDecoder]] for a case class of primitive (and `Option[primitive]`) fields.
    *
    * Field names are matched against the form keys. A missing required field is an error; a missing
    * `Option` field is `None`; an unparseable value is an error carrying the field name.
    */
  inline def derived[A](using m: Mirror.ProductOf[A]): FormDecoder[A] = ${ deriveImpl[A]('m) }

  private def deriveImpl[A: Type](m: Expr[Mirror.ProductOf[A]])(using Quotes): Expr[FormDecoder[A]] = {
    import quotes.reflect.*

    val sym = TypeRepr.of[A].typeSymbol
    val fields = sym.primaryConstructor.paramSymss.flatten
    val names = fields.map(_.name)
    val types = fields.map { f =>
      f.tree match {
        case v: ValDef => v.tpt.tpe
        case _ => report.errorAndAbort(s"FormDecoder.derived: cannot inspect field ${f.name}")
      }
    }

    val fieldFns: List[Expr[Map[String, String] => Either[FieldError, Any]]] =
      names.zip(types).map { case (name, tpe) =>
        val nameExpr = Expr(name)
        val isOption = tpe.dealias match {
          case AppliedType(base, _) => base.typeSymbol.fullName == "scala.Option"
          case _ => false
        }
        val missing: Expr[Either[FieldError, Any]] =
          if isOption then '{ Right(None): Either[FieldError, Any] }
          else '{ Left(FieldError($nameExpr, "missing required field")): Either[FieldError, Any] }
        val parse = parserFn(name, tpe)
        '{ (form: Map[String, String]) =>
          form.get($nameExpr) match {
            case None => $missing
            case Some(raw) => $parse(raw)
          }
        }
      }

    val fnsExpr = Expr.ofList(fieldFns)

    '{
      new FormDecoder[A] {
        def decode(form: Map[String, String]): Either[Vector[FieldError], A] = {
          val results: List[Either[FieldError, Any]] = $fnsExpr.map(_(form))
          val errors: List[FieldError] = results.collect { case Left(e) => e }
          if errors.nonEmpty then Left(errors.toVector)
          else {
            val values: Array[Any] = results.map(_.toOption.get).toArray
            Right($m.fromProduct(Tuple.fromArray(values)))
          }
        }
        override def knownFields: Set[String] = Set(${ Expr.ofList(names.map(Expr(_))) }*)
      }
    }
  }

  private def parserFn(using
    Quotes
  )(name: String, tpe: quotes.reflect.TypeRepr): Expr[String => Either[FieldError, Any]] = {
    import quotes.reflect.*
    val nameExpr = Expr(name)
    tpe.dealias match {
      case t if t.typeSymbol.fullName == "scala.Predef$.String" || t.typeSymbol.fullName == "java.lang.String" =>
        '{ (raw: String) => Right(raw): Either[FieldError, Any] }
      case t if t.typeSymbol.fullName == "scala.Int" =>
        '{ (raw: String) =>
          raw.toIntOption.toRight(FieldError($nameExpr, s"invalid integer: $raw")): Either[FieldError, Any]
        }
      case t if t.typeSymbol.fullName == "scala.Long" =>
        '{ (raw: String) =>
          raw.toLongOption.toRight(FieldError($nameExpr, s"invalid long: $raw")): Either[FieldError, Any]
        }
      case t if t.typeSymbol.fullName == "scala.Double" =>
        '{ (raw: String) =>
          raw.toDoubleOption.toRight(FieldError($nameExpr, s"invalid double: $raw")): Either[FieldError, Any]
        }
      case t if t.typeSymbol.fullName == "scala.Boolean" =>
        '{ (raw: String) =>
          raw.toBooleanOption.toRight(FieldError($nameExpr, s"invalid boolean: $raw")): Either[FieldError, Any]
        }
      case t if t.typeSymbol.fullName == "java.util.UUID" =>
        '{ (raw: String) =>
          scala.util
            .Try(java.util.UUID.fromString(raw))
            .toOption
            .toRight(FieldError($nameExpr, s"invalid UUID: $raw")): Either[
            FieldError,
            Any
          ]
        }
      case AppliedType(base, List(inner)) if base.typeSymbol.fullName == "scala.Option" =>
        val innerFn = parserFn(name, inner)
        '{ (raw: String) => $innerFn(raw).map(v => Some(v)): Either[FieldError, Any] }
      case other =>
        report.errorAndAbort(s"FormDecoder.derived: unsupported field type ${other.show} for field $name")
    }
  }
}
