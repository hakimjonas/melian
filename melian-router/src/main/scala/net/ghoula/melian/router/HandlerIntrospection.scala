package net.ghoula.melian.router

import scala.quoted.*

/** Compile-time introspection of handler function types.
  *
  * Decomposes a handler's TypeRepr to classify each parameter by its marker type and extracts the
  * return type structure (Eru[E, ResponseWrapper[A]]).
  */
object HandlerIntrospection {

  enum ParamKind derives CanEqual {
    case PathParam
    case QueryParam
    case HeaderParam
    case JsonBody
    case CodedBody
    case FormBody
    case Unknown
  }

  /** Compile-time info about a single handler parameter, carrying the actual TypeRepr. */
  case class ParamInfo(
    index: Int,
    kind: ParamKind,
    markerTypeRepr: Any,
    innerTypeRepr: Any,
    innerTypeShow: String,
    queryParamName: Option[String] = None
  )

  /** Compile-time info about the handler's response type. */
  case class ResponseInfo(
    errorTypeRepr: Any,
    wrapperName: String,
    bodyTypeRepr: Option[Any],
    bodyTypeShow: Option[String],
    isEndpoint: Boolean
  )

  case class HandlerInfo(
    params: List[ParamInfo],
    response: ResponseInfo,
    paramCount: Int
  ) {
    def pathParams: List[ParamInfo] = params.filter(_.kind == ParamKind.PathParam)

    def hasBody: Boolean = params.exists { p =>
      p.kind match {
        case ParamKind.JsonBody | ParamKind.CodedBody | ParamKind.FormBody => true
        case _ => false
      }
    }
  }

  private val markerSuffixes: Map[String, ParamKind] = Map(
    "Path" -> ParamKind.PathParam,
    "Query" -> ParamKind.QueryParam,
    "Header" -> ParamKind.HeaderParam,
    "Json" -> ParamKind.JsonBody,
    "Coded" -> ParamKind.CodedBody,
    "Form" -> ParamKind.FormBody
  )

  private val melianPackage: String = "net.ghoula.melian"

  def analyze[H: Type](using q: Quotes): HandlerInfo = {
    import q.reflect.*

    val handlerType = TypeRepr.of[H].dealias
    val (paramTypes, returnType) = decomposeFunction(handlerType)

    val params = paramTypes.zipWithIndex.map { case (tpe, idx) =>
      classifyParam(tpe, idx)
    }

    val response = classifyResponse(returnType)
    HandlerInfo(params, response, paramTypes.size)
  }

  /** Returns (paramTypes, returnType) from a FunctionN type. */
  def decomposeFunction(using
    q: Quotes
  )(
    tpe: q.reflect.TypeRepr
  ): (List[q.reflect.TypeRepr], q.reflect.TypeRepr) = {
    import q.reflect.*

    tpe.dealias match {
      case AppliedType(fn, args) if fn.typeSymbol.fullName.startsWith("scala.Function") =>
        (args.init, args.last)
      case other =>
        report.errorAndAbort(
          s"Handler must be a function type, got: ${other.show}\n\n" +
            "Hint: Endpoints should be functions like (id: Path[UUID]) => Endpoint[E, Ok[A]]"
        )
    }
  }

  private def classifyParam(using q: Quotes)(tpe: q.reflect.TypeRepr, index: Int): ParamInfo = {
    import q.reflect.*

    tpe match {
      case AppliedType(base, List(nameType, inner))
          if base.typeSymbol.fullName.startsWith(melianPackage) && base.typeSymbol.name == "Query" =>
        val paramName = nameType match {
          case ConstantType(StringConstant(name)) => name
          case other => report.errorAndAbort(s"Query parameter name must be a string literal, got: ${other.show}")
        }
        ParamInfo(index, ParamKind.QueryParam, tpe, inner, inner.show, queryParamName = Some(paramName))
      case AppliedType(base, List(inner)) =>
        val fullName = base.typeSymbol.fullName
        val simpleName = base.typeSymbol.name
        val kind =
          if fullName.startsWith(melianPackage) then markerSuffixes.getOrElse(simpleName, ParamKind.Unknown)
          else ParamKind.Unknown
        ParamInfo(index, kind, tpe, inner, inner.show)
      case _ =>
        ParamInfo(index, ParamKind.Unknown, tpe, tpe, tpe.show)
    }
  }

  private def classifyResponse(using q: Quotes)(tpe: q.reflect.TypeRepr): ResponseInfo = {
    import q.reflect.*

    val (unwrapped, isEndpoint) = unwrapEndpoint(tpe)
    unwrapped.dealias match {
      case AppliedType(eru, List(errorType, responseType)) if eru.typeSymbol.fullName == "net.ghoula.eru.Eru" =>
        responseType.dealias match {
          case AppliedType(wrapper, List(body)) =>
            ResponseInfo(errorType, wrapper.typeSymbol.name, Some(body), Some(body.show), isEndpoint)
          case terminal =>
            // A case object (NoContent, NotModified) surfaces as its module class, whose name
            // carries a trailing `$`; strip it so the wrapper name is stable.
            ResponseInfo(errorType, terminal.typeSymbol.name.stripSuffix("$"), None, None, isEndpoint)
        }
      case other =>
        ResponseInfo(TypeRepr.of[Nothing], other.show, None, None, isEndpoint)
    }
  }

  private def unwrapEndpoint(using q: Quotes)(tpe: q.reflect.TypeRepr): (q.reflect.TypeRepr, Boolean) = {
    import q.reflect.*

    tpe.dealias match {
      case AppliedType(cf, List(_, result)) if cf.typeSymbol.fullName.contains("ContextFunction") =>
        (result, true)
      case other => (other, false)
    }
  }
}
