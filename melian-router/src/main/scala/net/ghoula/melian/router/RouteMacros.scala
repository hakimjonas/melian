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

    val bodyTypeRepr = info.response.bodyTypeRepr.map(_.asInstanceOf[TypeRepr]).getOrElse(TypeRepr.of[Nothing])
    val templateParamNames = template.paramNames
    val returnsEndpoint = info.response.isEndpoint

    bodyTypeRepr.asType match {
      case '[b] =>
        val bodyEncoderExpr = summonOrAbort[net.ghoula.eru.http.BodyEncoder[b]](
          method, pathStr, "response", s"BodyEncoder[${Type.show[b]}]")

        val handlerExpr = generateTypedHandler[H, b](
          handler, info, templateParamNames, bodyEncoderExpr, returnsEndpoint, pathStr, method)

        '{
          $builder.addEntry(RouteEntry(
            pathTemplate = ${ Expr(pathStr) },
            method = $methodExpr,
            handler = $handlerExpr
          ))
        }
    }
  }

  // --- Typed handler generation ---

  private type MEru[A] = net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, A]

  private def generateTypedHandler[H: Type, B: Type](using q: Quotes)(
    handler: Expr[H],
    info: HandlerIntrospection.HandlerInfo,
    templateParamNames: List[String],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]],
    returnsEndpoint: Boolean,
    pathStr: String,
    method: String
  ): Expr[(net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    import q.reflect.*

    val paramTypes = TypeRepr.of[H].dealias match {
      case AppliedType(_, args) => args.init
      case _ => report.errorAndAbort("Cannot decompose handler type")
    }

    info.params.size match {
      case 1 => generateFor1[H, B](handler, info, paramTypes, templateParamNames, bodyEncoder, returnsEndpoint, pathStr, method)
      case 2 => generateFor2[H, B](handler, info, paramTypes, templateParamNames, bodyEncoder, returnsEndpoint, pathStr, method)
      case 3 => generateFor3[H, B](handler, info, paramTypes, templateParamNames, bodyEncoder, returnsEndpoint, pathStr, method)
      case n => report.errorAndAbort(s"Handlers with $n parameters not yet supported (max 3)")
    }
  }

  private def generateFor1[H: Type, B: Type](using q: Quotes)(
    handler: Expr[H], info: HandlerIntrospection.HandlerInfo,
    paramTypes: List[q.reflect.TypeRepr], templateParamNames: List[String],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    pathStr: String, method: String
  ): Expr[(net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    val e0 = mkExtraction(paramTypes(0), info.params(0), templateParamNames, pathStr, method, 0)
    '{
      (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
        ${ e0('request, 'pathParams) }.attempt.flatMap {
          case net.ghoula.eru.Result.Success(a0) =>
            ${ invokeHandler[H, B](handler, List('a0), bodyEncoder, returnsEndpoint, 'request) }
          case net.ghoula.eru.Result.Failure(err) =>
            net.ghoula.eru.Eru.fail(err)
        }
    }
  }

  private def generateFor2[H: Type, B: Type](using q: Quotes)(
    handler: Expr[H], info: HandlerIntrospection.HandlerInfo,
    paramTypes: List[q.reflect.TypeRepr], templateParamNames: List[String],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    pathStr: String, method: String
  ): Expr[(net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    val e0 = mkExtraction(paramTypes(0), info.params(0), templateParamNames, pathStr, method, 0)
    val e1 = mkExtraction(paramTypes(1), info.params(1), templateParamNames, pathStr, method, 1)
    '{
      (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
        ${ e0('request, 'pathParams) }.attempt.zip(${ e1('request, 'pathParams) }.attempt).flatMap {
          case (net.ghoula.eru.Result.Success(a0), net.ghoula.eru.Result.Success(a1)) =>
            ${ invokeHandler[H, B](handler, List('a0, 'a1), bodyEncoder, returnsEndpoint, 'request) }
          case (r0, r1) =>
            net.ghoula.eru.Eru.fail(collectErrors(List(r0, r1)))
        }
    }
  }

  private def generateFor3[H: Type, B: Type](using q: Quotes)(
    handler: Expr[H], info: HandlerIntrospection.HandlerInfo,
    paramTypes: List[q.reflect.TypeRepr], templateParamNames: List[String],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    pathStr: String, method: String
  ): Expr[(net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    val e0 = mkExtraction(paramTypes(0), info.params(0), templateParamNames, pathStr, method, 0)
    val e1 = mkExtraction(paramTypes(1), info.params(1), templateParamNames, pathStr, method, 1)
    val e2 = mkExtraction(paramTypes(2), info.params(2), templateParamNames, pathStr, method, 2)
    '{
      (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
        ${ e0('request, 'pathParams) }.attempt
          .zip(${ e1('request, 'pathParams) }.attempt)
          .zip(${ e2('request, 'pathParams) }.attempt)
          .flatMap {
            case ((net.ghoula.eru.Result.Success(a0), net.ghoula.eru.Result.Success(a1)), net.ghoula.eru.Result.Success(a2)) =>
              ${ invokeHandler[H, B](handler, List('a0, 'a1, 'a2), bodyEncoder, returnsEndpoint, 'request) }
            case ((r0, r1), r2) =>
              net.ghoula.eru.Eru.fail(collectErrors(List(r0, r1, r2)))
          }
    }
  }

  // --- Typed handler invocation ---

  private def invokeHandler[H: Type, B: Type](using q: Quotes)(
    handler: Expr[H],
    args: List[Expr[Any]],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]],
    returnsEndpoint: Boolean,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]]
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    import q.reflect.*

    val handlerCallExpr: Expr[Any] = args match {
      case List(a0) => '{ $handler.asInstanceOf[Any => Any].apply($a0) }
      case List(a0, a1) => '{ $handler.asInstanceOf[(Any, Any) => Any].apply($a0, $a1) }
      case List(a0, a1, a2) => '{ $handler.asInstanceOf[(Any, Any, Any) => Any].apply($a0, $a1, $a2) }
      case _ => report.errorAndAbort(s"Unsupported arity: ${args.size}")
    }

    val eruExpr: Expr[net.ghoula.eru.Eru[Any, Any]] =
      if returnsEndpoint then '{
        val ctx = LiveRequestContext.from($request)
        $handlerCallExpr.asInstanceOf[Function1[net.ghoula.melian.RequestContext, net.ghoula.eru.Eru[Any, Any]]].apply(ctx)
      }
      else '{ $handlerCallExpr.asInstanceOf[net.ghoula.eru.Eru[Any, Any]] }

    val wrapperName = Expr(HandlerIntrospection.analyze[H].response.wrapperName)

    '{
      $eruExpr.mapError { err =>
        net.ghoula.eru.http.HttpError.ProtocolError(err.toString, "domain"): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
      }.flatMap { responseValue =>
        encodeResponse(responseValue, $bodyEncoder, $wrapperName)
      }
    }
  }

  // --- Error collection ---

  private def collectErrors(
    results: List[net.ghoula.eru.Result[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]]
  ): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError = {
    val errors = results.collect { case net.ghoula.eru.Result.Failure(e) => e }
    val extractionErrors = errors.flatMap {
      case net.ghoula.melian.RequestError.ExtractionFailed(errs) => errs.toList
      case other => List(net.ghoula.melian.ExtractionError(
        net.ghoula.melian.ExtractionSource.Path, "unknown", other.toString, None, None))
    }.toVector
    net.ghoula.melian.RequestError.ExtractionFailed(extractionErrors)
  }

  // --- Response encoding (runtime, dispatches on wrapper name) ---

  private def encodeResponse[B](
    wrapper: Any,
    encoder: net.ghoula.eru.http.BodyEncoder[B],
    wrapperName: String
  ): net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    // The cast wrapper.body → B is safe: B is the compile-time body type from the response wrapper
    wrapperName match {
      case "Ok" =>
        val ok = wrapper.asInstanceOf[net.ghoula.melian.Ok[B]]
        Response.okEncoded(ok.body)(using encoder).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError }
      case "Created" =>
        val created = wrapper.asInstanceOf[net.ghoula.melian.Created[B]]
        encoder.encode(created.body).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError
        }.map(body => Response(StatusCode.Created, Headers.empty, body))
      case "Accepted" =>
        val accepted = wrapper.asInstanceOf[net.ghoula.melian.Accepted[B]]
        Response.acceptedEncoded(accepted.body)(using encoder).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError }
      case "NoContent" =>
        Eru.succeed(Response.noContent)
      case _ =>
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(wrapper.toString)))
    }
  }

  // --- Extraction builder ---

  private type ExtractionBuilder = (Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]], Expr[Map[String, String]]) => Expr[MEru[Any]]

  private def mkExtraction(using q: Quotes)(
    paramType: q.reflect.TypeRepr,
    paramInfo: HandlerIntrospection.ParamInfo,
    templateParamNames: List[String],
    pathStr: String, method: String,
    paramIndex: Int
  ): ExtractionBuilder = {
    paramInfo.kind match {
      case HandlerIntrospection.ParamKind.PathParam =>
        val name = templateParamNames.lift(paramIndex).getOrElse(s"param$paramIndex")
        (_, pp) => mkPathExtraction(paramType, name, pp, pathStr, method)
      case HandlerIntrospection.ParamKind.HeaderParam =>
        (req, _) => mkHeaderExtraction(paramType, req, pathStr, method)
      case HandlerIntrospection.ParamKind.JsonBody =>
        (req, _) => mkJsonBodyExtraction(paramType, req, pathStr, method)
      case other =>
        import q.reflect.*
        report.errorAndAbort(s"Unsupported parameter kind: $other")
    }
  }

  // --- Individual extractors ---

  private def mkPathExtraction(using q: Quotes)(
    paramType: q.reflect.TypeRepr, paramName: String,
    pathParams: Expr[Map[String, String]], pathStr: String, method: String
  ): Expr[MEru[Any]] = {
    import q.reflect.*
    val innerType = paramType match { case AppliedType(_, List(inner)) => inner; case other => other }
    val nameExpr = Expr(paramName)
    innerType.asType match {
      case '[a] =>
        val fps = summonOrAbort[net.ghoula.melian.extraction.FromPathSegment[a]](
          method, pathStr, paramName, s"FromPathSegment[${Type.show[a]}]")
        '{ $fps.parse($pathParams.getOrElse($nameExpr, ""), $nameExpr).mapError { err =>
            net.ghoula.melian.RequestError.ExtractionFailed(Vector(err)): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
          }.map(v => net.ghoula.melian.Path(v): Any)
        }
    }
  }

  private def mkHeaderExtraction(using q: Quotes)(
    paramType: q.reflect.TypeRepr,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String, method: String
  ): Expr[MEru[Any]] = {
    import q.reflect.*
    val innerType = paramType match { case AppliedType(_, List(inner)) => inner; case other => other }
    innerType.asType match {
      case '[a] =>
        val fh = summonOrAbort[net.ghoula.melian.extraction.FromHeader[a]](
          method, pathStr, "header", s"FromHeader[${Type.show[a]}]")
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

  private def mkJsonBodyExtraction(using q: Quotes)(
    paramType: q.reflect.TypeRepr,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String, method: String
  ): Expr[MEru[Any]] = {
    import q.reflect.*
    val innerType = paramType match { case AppliedType(_, List(inner)) => inner; case other => other }
    innerType.asType match {
      case '[a] =>
        val bd = summonOrAbort[net.ghoula.eru.http.BodyDecoder[a]](
          method, pathStr, "body", s"BodyDecoder[${Type.show[a]}]")
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

  // --- Verification ---

  private def verifyPathParams(using q: Quotes)(
    method: String, pathStr: String,
    template: PathTemplate.ParsedPath, info: HandlerIntrospection.HandlerInfo
  ): Unit = {
    import q.reflect.*
    if template.paramNames.size != info.pathParams.size then
      report.errorAndAbort(MacroErrors.formatPathMismatch(
        method, pathStr, template.paramNames, info.pathParams.map(_.innerTypeShow)))
  }

  private def verifyMethodConstraints(using q: Quotes)(
    method: String, pathStr: String, info: HandlerIntrospection.HandlerInfo
  ): Unit = {
    import q.reflect.*
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

  // --- Summon helper ---

  private def summonOrAbort[T: Type](using q: Quotes)(
    method: String, pathStr: String, paramName: String, typeclassName: String
  ): Expr[T] =
    Expr.summon[T].getOrElse {
      import q.reflect.*
      report.errorAndAbort(MacroErrors.formatMissingInstances(method, pathStr, "handler", List(
        MacroErrors.MissingInstance(paramName, Type.show[T], typeclassName, s"given $typeclassName = ..."))))
    }
}
