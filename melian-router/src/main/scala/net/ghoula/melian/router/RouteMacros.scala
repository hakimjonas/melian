package net.ghoula.melian.router

import scala.quoted.*

object RouteMacros {

  def addRoute[H: Type](
    builder: Expr[RouterBuilder],
    path: Expr[String],
    handler: Expr[H],
    methodStr: Expr[String]
  )(using q: Quotes): Expr[RouterBuilder] = {
    import q.reflect.*

    val pathStr = path.valueOrAbort
    val method = methodStr.valueOrAbort

    val template = PathTemplate.parse(pathStr) match {
      case Right(p) => p
      case Left(e) => report.errorAndAbort(s"Invalid path template: $e")
    }

    val info = HandlerIntrospection.analyze[H]

    verifyPathParams(method, pathStr, template, info)
    verifyMethodConstraints(method, pathStr, info)

    val handlerExpr = generateHandler[H](handler, template, info, pathStr)

    val methodExpr = method match {
      case "GET" => '{ net.ghoula.eru.http.Method.GET }
      case "POST" => '{ net.ghoula.eru.http.Method.POST }
      case "PUT" => '{ net.ghoula.eru.http.Method.PUT }
      case "DELETE" => '{ net.ghoula.eru.http.Method.DELETE }
      case "PATCH" => '{ net.ghoula.eru.http.Method.PATCH }
      case "HEAD" => '{ net.ghoula.eru.http.Method.HEAD }
      case "OPTIONS" => '{ net.ghoula.eru.http.Method.OPTIONS }
      case _ => report.errorAndAbort(s"Unsupported HTTP method: $method")
    }

    '{
      $builder.addEntry(RouteEntry(
        pathTemplate = ${ Expr(pathStr) },
        method = $methodExpr,
        handler = $handlerExpr
      ))
    }
  }

  private def verifyPathParams(using q: Quotes)(
    method: String,
    pathStr: String,
    template: PathTemplate.ParsedPath,
    info: HandlerIntrospection.HandlerInfo
  ): Unit = {
    import q.reflect.*

    val templateParamCount = template.paramNames.size
    val handlerParamCount = info.pathParams.size

    if templateParamCount != handlerParamCount then {
      report.errorAndAbort(MacroErrors.formatPathMismatch(
        method, pathStr,
        template.paramNames,
        info.pathParams.map(_.innerType)
      ))
    }
  }

  private def verifyMethodConstraints(using q: Quotes)(
    method: String,
    pathStr: String,
    info: HandlerIntrospection.HandlerInfo
  ): Unit = {
    import q.reflect.*

    // Mirrors Method.allowsRequestBody / requiresRequestBody from eru-http.
    // Hardcoded because Method is an opaque runtime value — can't call extensions during macro expansion.
    val allowsBody = method match {
      case "GET" | "HEAD" | "DELETE" | "TRACE" => false
      case _ => true
    }
    val requiresBody = method match {
      case "POST" | "PUT" | "PATCH" => true
      case _ => false
    }

    if !allowsBody && info.hasBody then {
      report.errorAndAbort(MacroErrors.formatMethodConstraint(
        method, pathStr,
        s"$method does not allow a request body, but handler declares a body parameter.",
        "Use POST or PUT for endpoints that accept a request body."
      ))
    }

    if requiresBody && !info.hasBody then {
      report.errorAndAbort(MacroErrors.formatMethodConstraint(
        method, pathStr,
        s"$method requires a request body, but handler declares no body parameter (Json[A], Coded[A], or Form[A]).",
        "Add a Json[A] parameter for the request body."
      ))
    }
  }

  private type HandlerFn = (net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) =>
    net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]

  private def generateHandler[H: Type](using q: Quotes)(
    handler: Expr[H],
    template: PathTemplate.ParsedPath,
    info: HandlerIntrospection.HandlerInfo,
    pathStr: String
  ): Expr[HandlerFn] = {
    import q.reflect.*

    val templateParamNames = template.paramNames
    val _ = info // used in Phase 3+ for multi-param dispatch

    TypeRepr.of[H].dealias match {
      case AppliedType(_, args) if args.size >= 2 =>
        val paramType = args.head
        val returnType = args.last

        generateSinglePathParamHandler(handler, paramType, returnType, templateParamNames, pathStr)

      case other =>
        report.errorAndAbort(s"Cannot generate handler for type: ${other.show}")
    }
  }

  private def generateSinglePathParamHandler[H: Type](using q: Quotes)(
    handler: Expr[H],
    paramTypeRepr: q.reflect.TypeRepr,
    returnTypeRepr: q.reflect.TypeRepr,
    templateParamNames: List[String],
    pathStr: String
  ): Expr[HandlerFn] = {
    import q.reflect.*

    // Extract the inner type A from Path[A]
    val innerType = paramTypeRepr.dealias match {
      case AppliedType(_, List(inner)) => inner
      case other => other // If opaque erasure already happened
    }

    // The return type is Eru[E, Ok[B]] or similar — extract the body type B
    val bodyType = returnTypeRepr.dealias match {
      case AppliedType(_, List(_, responseType)) =>
        responseType.dealias match {
          case AppliedType(_, List(body)) => body
          case _ => TypeRepr.of[String] // fallback
        }
      case _ => TypeRepr.of[String]
    }

    val paramName = templateParamNames.headOption.getOrElse("id")
    val paramNameExpr = Expr(paramName)

    // Generate code that works for any inner type A and body type B
    innerType.asType match {
      case '[a] =>
        bodyType.asType match {
          case '[b] =>
            // Summon required instances at compile time
            val fromPathExpr = Expr.summon[net.ghoula.melian.extraction.FromPathSegment[a]].getOrElse {
              report.errorAndAbort(
                MacroErrors.formatMissingInstances("?", pathStr, "handler", List(
                  MacroErrors.MissingInstance(paramName, Type.show[a], s"FromPathSegment[${Type.show[a]}]",
                    s"given FromPathSegment[${Type.show[a]}] = ...")
                ))
              )
            }

            val bodyEncoderExpr = Expr.summon[net.ghoula.eru.http.BodyEncoder[b]].getOrElse {
              report.errorAndAbort(
                MacroErrors.formatMissingInstances("?", pathStr, "handler", List(
                  MacroErrors.MissingInstance("response", Type.show[b], s"BodyEncoder[${Type.show[b]}]",
                    s"Provide a Sarati Encoder[${Type.show[b]}, JsonValue] and import SaratiBridge.given")
                ))
              )
            }

            '{
              (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) => {
                import net.ghoula.eru.Eru
                import net.ghoula.eru.http.*
                import net.ghoula.melian.*

                val _ = request // Phase 3+: query/header extraction
                val rawParam = pathParams.getOrElse($paramNameExpr, "")
                $fromPathExpr.parse(rawParam, $paramNameExpr).mapError {
                  err => RequestError.ExtractionFailed(Vector(err)): RequestError | HttpError
                }.flatMap { (parsed: a) =>
                  val pathVal: Path[a] = Path(parsed)
                  val fn = $handler.asInstanceOf[Path[a] => Eru[Any, Ok[b]]]
                  fn(pathVal).mapError { err =>
                    HttpError.ProtocolError(err.toString, "domain"): RequestError | HttpError
                  }.flatMap { (okResult: Ok[b]) =>
                    Response.okEncoded(okResult.body)(using $bodyEncoderExpr).mapError { err =>
                      HttpError.BodyEncodeError(err): RequestError | HttpError
                    }
                  }
                }
              }
            }
        }
    }
  }
}
