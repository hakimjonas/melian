package net.ghoula.melian.router

import scala.quoted.*

/** Compile-time introspection of handler function types.
  *
  * Decomposes a handler's TypeRepr to classify each parameter by its marker type (Path, Query,
  * Header, Json, Coded, Form) and extracts the return type structure (Endpoint[E, R] where R is
  * Ok[A], Created[A], NoContent, etc.).
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

  case class ParamInfo(name: String, kind: ParamKind, innerType: String) derives CanEqual
  case class ResponseInfo(errorType: String, wrapperType: String, bodyType: Option[String])

  case class HandlerInfo(
    params: List[ParamInfo],
    response: ResponseInfo,
    hasBody: Boolean
  ) {
    def pathParams: List[ParamInfo] = params.collect { case p if p.kind == ParamKind.PathParam => p }
    def queryParams: List[ParamInfo] = params.collect { case p if p.kind == ParamKind.QueryParam => p }
    def headerParams: List[ParamInfo] = params.collect { case p if p.kind == ParamKind.HeaderParam => p }
    def bodyParams: List[ParamInfo] = params.collect {
      case p if p.kind == ParamKind.JsonBody => p
      case p if p.kind == ParamKind.CodedBody => p
      case p if p.kind == ParamKind.FormBody => p
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
    val (paramTypes, paramNames, returnType) = decomposeFunction(handlerType)

    val params = paramTypes.zip(paramNames).map { case (tpe, name) =>
      classifyParam(tpe, name)
    }

    val response = classifyResponse(returnType)
    val hasBody = params.exists { p =>
      p.kind match {
        case ParamKind.JsonBody | ParamKind.CodedBody | ParamKind.FormBody => true
        case _ => false
      }
    }

    HandlerInfo(params, response, hasBody)
  }

  private def decomposeFunction(using q: Quotes)(tpe: q.reflect.TypeRepr): (List[q.reflect.TypeRepr], List[String], q.reflect.TypeRepr) = {
    import q.reflect.*

    tpe.dealias match {
      case AppliedType(fn, args) if fn.typeSymbol.fullName.startsWith("scala.Function") =>
        val paramTypes = args.init
        val returnType = args.last
        val paramNames = paramTypes.zipWithIndex.map { case (_, i) => s"arg$i" }
        (paramTypes, paramNames, returnType)
      case other =>
        report.errorAndAbort(
          s"Handler must be a function type, got: ${other.show}\n\n" +
            "Hint: Endpoints should be functions like (id: Path[UUID]) => Endpoint[E, Ok[A]]"
        )
    }
  }

  private def classifyParam(using q: Quotes)(tpe: q.reflect.TypeRepr, name: String): ParamInfo = {
    import q.reflect.*

    tpe match {
      case AppliedType(base, List(inner)) =>
        val fullName = base.typeSymbol.fullName
        val simpleName = base.typeSymbol.name
        val kind =
          if fullName.startsWith(melianPackage) then markerSuffixes.getOrElse(simpleName, ParamKind.Unknown)
          else ParamKind.Unknown
        ParamInfo(name, kind, inner.show)
      case _ =>
        ParamInfo(name, ParamKind.Unknown, tpe.show)
    }
  }

  private def classifyResponse(using q: Quotes)(tpe: q.reflect.TypeRepr): ResponseInfo = {
    import q.reflect.*

    val unwrapped = unwrapEndpoint(tpe)
    unwrapped.dealias match {
      case AppliedType(eru, List(errorType, responseType)) if eru.typeSymbol.fullName == "net.ghoula.eru.Eru" =>
        responseType.dealias match {
          case AppliedType(wrapper, List(body)) =>
            ResponseInfo(errorType.show, wrapper.typeSymbol.name, Some(body.show))
          case terminal =>
            ResponseInfo(errorType.show, terminal.typeSymbol.name, None)
        }
      case other =>
        ResponseInfo("Nothing", other.show, None)
    }
  }

  private def unwrapEndpoint(using q: Quotes)(tpe: q.reflect.TypeRepr): q.reflect.TypeRepr = {
    import q.reflect.*

    tpe.dealias match {
      case AppliedType(cf, List(_, result)) if cf.typeSymbol.fullName.contains("ContextFunction") =>
        result
      case other => other
    }
  }
}
