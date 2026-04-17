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

    val handlerExpr = generateHandler[H](handler, template, info, pathStr, method)

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

  // --- Verification ---

  private def verifyPathParams(using q: Quotes)(
    method: String,
    pathStr: String,
    template: PathTemplate.ParsedPath,
    info: HandlerIntrospection.HandlerInfo
  ): Unit = {
    import q.reflect.*
    if template.paramNames.size != info.pathParams.size then {
      report.errorAndAbort(MacroErrors.formatPathMismatch(
        method, pathStr, template.paramNames, info.pathParams.map(_.innerTypeShow)
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
    if !allowsBody && info.hasBody then
      report.errorAndAbort(MacroErrors.formatMethodConstraint(method, pathStr,
        s"$method does not allow a request body, but handler declares a body parameter.",
        "Use POST or PUT for endpoints that accept a request body."))
    if requiresBody && !info.hasBody then
      report.errorAndAbort(MacroErrors.formatMethodConstraint(method, pathStr,
        s"$method requires a request body, but handler declares no body parameter (Json[A], Coded[A], or Form[A]).",
        "Add a Json[A] parameter for the request body."))
  }

  // --- Handler generation ---

  private type HandlerFn = (net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) =>
    net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]

  private type EruResult = net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]

  private def generateHandler[H: Type](using q: Quotes)(
    handler: Expr[H],
    template: PathTemplate.ParsedPath,
    info: HandlerIntrospection.HandlerInfo,
    pathStr: String,
    method: String
  ): Expr[HandlerFn] = {
    import q.reflect.*

    val (paramTypes, _) = HandlerIntrospection.decomposeFunction(TypeRepr.of[H].dealias)
    val templateParamNames = template.paramNames
    val bodyTypeRepr = info.response.bodyTypeRepr.map(_.asInstanceOf[TypeRepr]).getOrElse(TypeRepr.of[Nothing])

    // Summon response body encoder upfront
    val bodyEncoderExpr = bodyTypeRepr.asType match {
      case '[b] =>
        Expr.summon[net.ghoula.eru.http.BodyEncoder[b]].getOrElse {
          report.errorAndAbort(MacroErrors.formatMissingInstances(method, pathStr, "handler", List(
            MacroErrors.MissingInstance("response", Type.show[b], s"BodyEncoder[${Type.show[b]}]",
              s"Provide Encoder[${Type.show[b]}, JsonValue] and import SaratiBridge.given")
          )))
        }
    }

    // Build the entire chain expression at macro expansion time, then splice once
    val chainBuilder: (Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]], Expr[Map[String, String]]) => Expr[EruResult] =
      (req, pp) => generateExtractionChain[H](
        handler, paramTypes, info.params, templateParamNames,
        bodyEncoderExpr, info.response.wrapperName,
        req, pp, pathStr, method, 0, '{ List.empty[Any] }
      )

    '{
      (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) => {
        ${ chainBuilder('request, 'pathParams) }
      }
    }
  }

  /** Recursively generates a flatMap chain that extracts each parameter, then calls the handler. */
  private def generateExtractionChain[H: Type](using q: Quotes)(
    handler: Expr[H],
    paramTypes: List[q.reflect.TypeRepr],
    paramInfos: List[HandlerIntrospection.ParamInfo],
    templateParamNames: List[String],
    bodyEncoder: Expr[? <: net.ghoula.eru.http.BodyEncoder[?]],
    responseWrapper: String,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathParams: Expr[Map[String, String]],
    pathStr: String,
    method: String,
    pathParamIdx: Int,
    accumulated: Expr[List[Any]]
  ): Expr[EruResult] = {
    import q.reflect.*

    // Build all extraction expressions upfront (all at the same Quotes level)
    var pIdx = pathParamIdx
    val extractExprs: List[Expr[net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]]] =
      paramTypes.zip(paramInfos).map { case (tpe, info) =>
        val expr = info.kind match {
          case HandlerIntrospection.ParamKind.PathParam =>
            val name = templateParamNames(pIdx)
            pIdx += 1
            extractPathParam(tpe, name, pathParams, pathStr, method)
          case HandlerIntrospection.ParamKind.HeaderParam =>
            extractHeader(tpe, request, pathStr, method)
          case HandlerIntrospection.ParamKind.JsonBody =>
            extractJsonBody(tpe, request, pathStr, method)
          case HandlerIntrospection.ParamKind.QueryParam =>
            extractQueryParam(tpe, request, pathStr, method)
          case other =>
            report.errorAndAbort(s"Unsupported parameter kind: $other")
        }
        expr
      }

    // Now chain them with flatMap — all at the same Quotes level
    chainFlatMaps(extractExprs, handler, bodyEncoder, responseWrapper, accumulated)
  }

  /** Chains extraction expressions into a flatMap sequence, then calls the handler. */
  private def chainFlatMaps[H: Type](using q: Quotes)(
    extractors: List[Expr[net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]]],
    handler: Expr[H],
    bodyEncoder: Expr[? <: net.ghoula.eru.http.BodyEncoder[?]],
    responseWrapper: String,
    accumulated: Expr[List[Any]]
  ): Expr[EruResult] = {
    extractors match {
      case Nil =>
        generateHandlerCall(handler, bodyEncoder, responseWrapper, accumulated)
      case head :: tail =>
        '{
          $head.flatMap { (extracted: Any) =>
            ${ chainFlatMaps(tail, handler, bodyEncoder, responseWrapper, '{ $accumulated :+ extracted }) }
          }
        }
    }
  }

  // --- Individual param extractors ---

  private def extractPathParam(using q: Quotes)(
    paramType: q.reflect.TypeRepr,
    paramName: String,
    pathParams: Expr[Map[String, String]],
    pathStr: String,
    method: String
  ): Expr[net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]] = {
    import q.reflect.*
    val innerType = paramType match { case AppliedType(_, List(inner)) => inner; case other => other }
    val nameExpr = Expr(paramName)
    innerType.asType match {
      case '[a] =>
        val fps = Expr.summon[net.ghoula.melian.extraction.FromPathSegment[a]].getOrElse {
          report.errorAndAbort(MacroErrors.formatMissingInstances(method, pathStr, "handler", List(
            MacroErrors.MissingInstance(paramName, Type.show[a], s"FromPathSegment[${Type.show[a]}]",
              s"given FromPathSegment[${Type.show[a]}] = ..."))))
        }
        '{ $fps.parse($pathParams.getOrElse($nameExpr, ""), $nameExpr).mapError { err =>
          net.ghoula.melian.RequestError.ExtractionFailed(Vector(err)): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
        }.map(v => net.ghoula.melian.Path(v): Any) }
    }
  }

  private def extractHeader(using q: Quotes)(
    paramType: q.reflect.TypeRepr,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String
  ): Expr[net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]] = {
    import q.reflect.*
    val innerType = paramType match { case AppliedType(_, List(inner)) => inner; case other => other }
    innerType.asType match {
      case '[a] =>
        val fh = Expr.summon[net.ghoula.melian.extraction.FromHeader[a]].getOrElse {
          report.errorAndAbort(MacroErrors.formatMissingInstances(method, pathStr, "handler", List(
            MacroErrors.MissingInstance("header", Type.show[a], s"FromHeader[${Type.show[a]}]",
              s"given FromHeader[${Type.show[a]}] = ..."))))
        }
        '{
          val headerName = $fh.headerName
          $request.headers.getFirst(headerName) match {
            case Some(hv) =>
              $fh.parse(hv.value).mapError { err =>
                net.ghoula.melian.RequestError.ExtractionFailed(Vector(err)): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
              }.map(v => net.ghoula.melian.Header(v): Any)
            case None =>
              net.ghoula.eru.Eru.fail(net.ghoula.melian.RequestError.ExtractionFailed(Vector(
                net.ghoula.melian.ExtractionError(net.ghoula.melian.ExtractionSource.Header, headerName,
                  s"Missing required header: $headerName", None, None)
              )): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError)
          }
        }
    }
  }

  private def extractJsonBody(using q: Quotes)(
    paramType: q.reflect.TypeRepr,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String
  ): Expr[net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]] = {
    import q.reflect.*
    val innerType = paramType match { case AppliedType(_, List(inner)) => inner; case other => other }
    innerType.asType match {
      case '[a] =>
        val bd = Expr.summon[net.ghoula.eru.http.BodyDecoder[a]].getOrElse {
          report.errorAndAbort(MacroErrors.formatMissingInstances(method, pathStr, "handler", List(
            MacroErrors.MissingInstance("body", Type.show[a], s"BodyDecoder[${Type.show[a]}]",
              s"Provide Decoder[JsonValue, ${Type.show[a]}] and import SaratiBridge.given"))))
        }
        '{
          SaratiBridge.validateContentType($request.headers).mapError { err =>
            net.ghoula.melian.RequestError.DecodeFailed(List(err.message)): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
          }.flatMap { _ =>
            $bd.decode($request.body).mapError { err =>
              net.ghoula.melian.RequestError.DecodeFailed(List(err.message)): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
            }.map(v => net.ghoula.melian.Json(v): Any)
          }
        }
    }
  }

  private def extractQueryParam(using q: Quotes)(
    paramType: q.reflect.TypeRepr,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String
  ): Expr[net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]] = {
    import q.reflect.*
    val innerType = paramType match { case AppliedType(_, List(inner)) => inner; case other => other }
    innerType.asType match {
      case '[a] =>
        Expr.summon[net.ghoula.melian.extraction.FromQueryParam[a]].getOrElse {
          report.errorAndAbort(MacroErrors.formatMissingInstances(method, pathStr, "handler", List(
            MacroErrors.MissingInstance("query", Type.show[a], s"FromQueryParam[${Type.show[a]}]",
              s"given FromQueryParam[${Type.show[a]}] = ..."))))
        }
        // TODO: query param name needs term-level introspection; for now stub
        val _ = request
        '{ net.ghoula.eru.Eru.succeed(net.ghoula.melian.Query(null.asInstanceOf[a]): Any) }
    }
  }

  // --- Handler call + response encoding ---

  private def generateHandlerCall[H: Type](using q: Quotes)(
    handler: Expr[H],
    bodyEncoder: Expr[? <: net.ghoula.eru.http.BodyEncoder[?]],
    responseWrapper: String,
    accumulated: Expr[List[Any]]
  ): Expr[EruResult] = {
    val _ = responseWrapper // used in future for compile-time response type dispatch
    '{
      val args = $accumulated
      val fn = $handler.asInstanceOf[Any]
      val result = args.size match {
        case 0 => fn.asInstanceOf[Function0[Any]].apply()
        case 1 => fn.asInstanceOf[Function1[Any, Any]].apply(args(0))
        case 2 => fn.asInstanceOf[Function2[Any, Any, Any]].apply(args(0), args(1))
        case 3 => fn.asInstanceOf[Function3[Any, Any, Any, Any]].apply(args(0), args(1), args(2))
        case 4 => fn.asInstanceOf[Function4[Any, Any, Any, Any, Any]].apply(args(0), args(1), args(2), args(3))
        case n => throw new MatchError(s"Unsupported handler arity: $n")
      }
      val eru = result.asInstanceOf[net.ghoula.eru.Eru[Any, Any]]
      eru.mapError { err =>
        net.ghoula.eru.http.HttpError.ProtocolError(err.toString, "domain"): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
      }.flatMap { responseWrapper =>
        encodeResponse(responseWrapper, $bodyEncoder)
      }
    }
  }

  private def encodeResponse(
    wrapper: Any,
    encoder: net.ghoula.eru.http.BodyEncoder[?]
  ): net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*

    wrapper match {
      case ok: net.ghoula.melian.Ok[?] =>
        Response.okEncoded(ok.body)(using encoder.asInstanceOf[BodyEncoder[Any]]).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError
        }
      case _: net.ghoula.melian.NoContent.type =>
        Eru.succeed(Response.noContent)
      case created: net.ghoula.melian.Created[?] =>
        val body = created.body
        encoder.asInstanceOf[BodyEncoder[Any]].encode(body).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError
        }.map { encodedBody =>
          Response(StatusCode.Created, Headers.empty, encodedBody)
        }
      case accepted: net.ghoula.melian.Accepted[?] =>
        Response.acceptedEncoded(accepted.body)(using encoder.asInstanceOf[BodyEncoder[Any]]).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError
        }
      case _ =>
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(wrapper.toString)))
    }
  }
}
