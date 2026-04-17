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
    val returnsEndpoint = info.response.isEndpoint

    bodyTypeRepr.asType match {
      case '[b] =>
        val bodyEncoderExpr = summonOrAbort[net.ghoula.eru.http.BodyEncoder[b]](
          method, pathStr, "response", s"BodyEncoder[${Type.show[b]}]")

        val paramTypes = TypeRepr.of[H].dealias match {
          case AppliedType(_, args) => args.init
          case _ => report.errorAndAbort("Cannot decompose handler type")
        }

        val handlerExpr = generateHandler[H, b](
          handler, info, paramTypes, template.paramNames,
          bodyEncoderExpr, returnsEndpoint, pathStr, method)

        '{
          $builder.addEntry(RouteEntry(
            pathTemplate = ${ Expr(pathStr) },
            method = $methodExpr,
            handler = $handlerExpr
          ))
        }
    }
  }

  // --- Types ---

  private type ErrType = net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
  private type MEru[A] = net.ghoula.eru.Eru[ErrType, A]
  private type HandlerFn = (net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]

  // --- Handler generation: dispatches by arity, each fully typed ---

  private def generateHandler[H: Type, B: Type](using q: Quotes)(
    handler: Expr[H], info: HandlerIntrospection.HandlerInfo,
    paramTypes: List[q.reflect.TypeRepr], templateParamNames: List[String],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    pathStr: String, method: String
  ): Expr[HandlerFn] = {
    import q.reflect.*

    // Extract E (error type) and R (response type, e.g. Ok[B]) from the handler's return
    val (_, returnType) = HandlerIntrospection.decomposeFunction(TypeRepr.of[H].dealias)
    val (unwrappedReturn, _) = returnType.dealias match {
      case AppliedType(cf, List(_, result)) if cf.typeSymbol.fullName.contains("ContextFunction") => (result, true)
      case other => (other, false)
    }
    val (errorTypeRepr, responseTypeRepr) = unwrappedReturn.dealias match {
      case AppliedType(_, List(e, r)) => (e, r)
      case _ => (TypeRepr.of[Nothing], TypeRepr.of[Nothing])
    }

    val innerTypes = paramTypes.map {
      case AppliedType(_, List(inner)) => inner
      case other => other
    }

    // Dispatch by arity with full type parameters: H, A0..An, E, R, B
    (innerTypes, errorTypeRepr.asType, responseTypeRepr.asType) match {
      case (List(t0), '[e], '[r]) =>
        t0.asType match { case '[a0] =>
          gen1[H, a0, e, r, B](handler, info, templateParamNames, bodyEncoder, returnsEndpoint, pathStr, method)
        }
      case (List(t0, t1), '[e], '[r]) =>
        (t0.asType, t1.asType) match {
          case ('[a0], '[a1]) =>
            gen2[H, a0, a1, e, r, B](handler, info, templateParamNames, bodyEncoder, returnsEndpoint, pathStr, method)
          case _ => report.errorAndAbort("Failed to extract parameter types")
        }
      case (List(t0, t1, t2), '[e], '[r]) =>
        (t0.asType, t1.asType, t2.asType) match {
          case ('[a0], '[a1], '[a2]) =>
            gen3[H, a0, a1, a2, e, r, B](handler, info, templateParamNames, bodyEncoder, returnsEndpoint, pathStr, method)
          case _ => report.errorAndAbort("Failed to extract parameter types")
        }
      case (other, _, _) =>
        report.errorAndAbort(s"Handlers with ${other.size} parameters not yet supported (max 3)")
    }
  }

  private def gen1[H: Type, A0: Type, E: Type, R: Type, B: Type](using q: Quotes)(
    handler: Expr[H], info: HandlerIntrospection.HandlerInfo,
    templateParamNames: List[String],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    pathStr: String, method: String
  ): Expr[HandlerFn] = {
    val e0 = mkTypedExtraction[A0](info.params(0), templateParamNames, pathStr, method, 0)
    '{
      (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
        ${ e0('request, 'pathParams) }.attempt.flatMap {
          case net.ghoula.eru.Result.Success(a0) =>
            ${ callHandler1[H, A0, E, R, B](handler, 'a0, bodyEncoder, returnsEndpoint, 'request, pathStr, method) }
          case net.ghoula.eru.Result.Failure(err) =>
            net.ghoula.eru.Eru.fail(err)
        }
    }
  }

  private def gen2[H: Type, A0: Type, A1: Type, E: Type, R: Type, B: Type](using q: Quotes)(
    handler: Expr[H], info: HandlerIntrospection.HandlerInfo,
    templateParamNames: List[String],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    pathStr: String, method: String
  ): Expr[HandlerFn] = {
    val e0 = mkTypedExtraction[A0](info.params(0), templateParamNames, pathStr, method, 0)
    val e1 = mkTypedExtraction[A1](info.params(1), templateParamNames, pathStr, method, 1)
    '{
      (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
        ${ e0('request, 'pathParams) }.attempt.zip(${ e1('request, 'pathParams) }.attempt).flatMap {
          case (net.ghoula.eru.Result.Success(a0), net.ghoula.eru.Result.Success(a1)) =>
            ${ callHandler2[H, A0, A1, E, R, B](handler, 'a0, 'a1, bodyEncoder, returnsEndpoint, 'request, pathStr, method) }
          case (r0, r1) =>
            net.ghoula.eru.Eru.fail(collectErrors(r0, r1))
        }
    }
  }

  private def gen3[H: Type, A0: Type, A1: Type, A2: Type, E: Type, R: Type, B: Type](using q: Quotes)(
    handler: Expr[H], info: HandlerIntrospection.HandlerInfo,
    templateParamNames: List[String],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    pathStr: String, method: String
  ): Expr[HandlerFn] = {
    val e0 = mkTypedExtraction[A0](info.params(0), templateParamNames, pathStr, method, 0)
    val e1 = mkTypedExtraction[A1](info.params(1), templateParamNames, pathStr, method, 1)
    val e2 = mkTypedExtraction[A2](info.params(2), templateParamNames, pathStr, method, 2)
    '{
      (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
        ${ e0('request, 'pathParams) }.attempt
          .zip(${ e1('request, 'pathParams) }.attempt)
          .zip(${ e2('request, 'pathParams) }.attempt)
          .flatMap {
            case ((net.ghoula.eru.Result.Success(a0), net.ghoula.eru.Result.Success(a1)), net.ghoula.eru.Result.Success(a2)) =>
              ${ callHandler3[H, A0, A1, A2, E, R, B](handler, 'a0, 'a1, 'a2, bodyEncoder, returnsEndpoint, 'request, pathStr, method) }
            case ((r0, r1), r2) =>
              net.ghoula.eru.Eru.fail(collectErrors(r0, r1, r2))
          }
    }
  }

  // --- Typed handler calls — fully typed including return type ---

  private def callHandler1[H: Type, A0: Type, E: Type, R: Type, B: Type](using q: Quotes)(
    handler: Expr[H], a0: Expr[A0],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String, method: String
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    val eru: Expr[net.ghoula.eru.Eru[E, R]] =
      if returnsEndpoint then '{
        val ctx = LiveRequestContext.from($request)
        given net.ghoula.melian.RequestContext = ctx
        $handler.asInstanceOf[A0 => net.ghoula.melian.Endpoint[E, R]].apply($a0)
      }
      else '{ $handler.asInstanceOf[A0 => net.ghoula.eru.Eru[E, R]].apply($a0) }

    encodeTypedResponse[E, R, B](eru, bodyEncoder, pathStr, method)
  }

  private def callHandler2[H: Type, A0: Type, A1: Type, E: Type, R: Type, B: Type](using q: Quotes)(
    handler: Expr[H], a0: Expr[A0], a1: Expr[A1],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String, method: String
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    val eru: Expr[net.ghoula.eru.Eru[E, R]] =
      if returnsEndpoint then '{
        val ctx = LiveRequestContext.from($request)
        given net.ghoula.melian.RequestContext = ctx
        $handler.asInstanceOf[(A0, A1) => net.ghoula.melian.Endpoint[E, R]].apply($a0, $a1)
      }
      else '{ $handler.asInstanceOf[(A0, A1) => net.ghoula.eru.Eru[E, R]].apply($a0, $a1) }

    encodeTypedResponse[E, R, B](eru, bodyEncoder, pathStr, method)
  }

  private def callHandler3[H: Type, A0: Type, A1: Type, A2: Type, E: Type, R: Type, B: Type](using q: Quotes)(
    handler: Expr[H], a0: Expr[A0], a1: Expr[A1], a2: Expr[A2],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]], returnsEndpoint: Boolean,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String, method: String
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    val eru: Expr[net.ghoula.eru.Eru[E, R]] =
      if returnsEndpoint then '{
        val ctx = LiveRequestContext.from($request)
        given net.ghoula.melian.RequestContext = ctx
        $handler.asInstanceOf[(A0, A1, A2) => net.ghoula.melian.Endpoint[E, R]].apply($a0, $a1, $a2)
      }
      else '{ $handler.asInstanceOf[(A0, A1, A2) => net.ghoula.eru.Eru[E, R]].apply($a0, $a1, $a2) }

    encodeTypedResponse[E, R, B](eru, bodyEncoder, pathStr, method)
  }

  // --- Typed response encoding — E, R, B all known at compile time ---

  private def encodeTypedResponse[E: Type, R: Type, B: Type](using q: Quotes)(
    eru: Expr[net.ghoula.eru.Eru[E, R]],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]],
    pathStr: String, method: String
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    val errorRenderer = summonOrAbort[net.ghoula.melian.ErrorRenderer[E]](
      method, pathStr, "error", s"ErrorRenderer[${Type.show[E]}]")

    '{
      $eru.attempt.flatMap {
        case net.ghoula.eru.Result.Success(response) =>
          encodeResponse[R, B](response, $bodyEncoder)
        case net.ghoula.eru.Result.Failure(domainError) =>
          $errorRenderer.render(domainError)
      }
    }
  }

  // R is the response wrapper (Ok[B], Created[B], etc.), B is the body type
  private def encodeResponse[R, B](
    response: R,
    encoder: net.ghoula.eru.http.BodyEncoder[B]
  ): net.ghoula.eru.Eru[ErrType, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    // Pattern match is safe: R is the compile-time known response type (Ok[B], Created[B], etc.)
    (response: Any) match {
      case ok: net.ghoula.melian.Ok[B @unchecked] =>
        Response.okEncoded(ok.body)(using encoder).mapError { err =>
          HttpError.BodyEncodeError(err): ErrType }
      case _: net.ghoula.melian.NoContent.type =>
        Eru.succeed(Response.noContent)
      case created: net.ghoula.melian.Created[B @unchecked] =>
        encoder.encode(created.body).mapError { err =>
          HttpError.BodyEncodeError(err): ErrType
        }.map(body => Response(StatusCode.Created, Headers.empty, body))
      case accepted: net.ghoula.melian.Accepted[B @unchecked] =>
        Response.acceptedEncoded(accepted.body)(using encoder).mapError { err =>
          HttpError.BodyEncodeError(err): ErrType }
      case _ =>
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(response.toString)))
    }
  }

  // --- Error collection ---

  private def collectErrors(results: net.ghoula.eru.Result[ErrType, ?]*): ErrType = {
    val errors = results.collect { case net.ghoula.eru.Result.Failure(e) => e }
    val extractionErrors = errors.toList.flatMap {
      case net.ghoula.melian.RequestError.ExtractionFailed(errs) => errs.toList
      case other => List(net.ghoula.melian.ExtractionError(
        net.ghoula.melian.ExtractionSource.Path, "unknown", other.toString, None, None))
    }.toVector
    net.ghoula.melian.RequestError.ExtractionFailed(extractionErrors)
  }

  // --- Typed extraction builder ---

  private type TypedExtractionBuilder[A] = (Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]], Expr[Map[String, String]]) => Expr[MEru[A]]

  private def mkTypedExtraction[A: Type](using q: Quotes)(
    paramInfo: HandlerIntrospection.ParamInfo,
    templateParamNames: List[String],
    pathStr: String, method: String,
    paramIndex: Int
  ): TypedExtractionBuilder[A] = {
    paramInfo.kind match {
      case HandlerIntrospection.ParamKind.PathParam =>
        val name = templateParamNames.lift(paramIndex).getOrElse(s"param$paramIndex")
        (_, pp) => mkPathExtraction[A](name, pp, pathStr, method)
      case HandlerIntrospection.ParamKind.HeaderParam =>
        (req, _) => mkHeaderExtraction[A](req, pathStr, method)
      case HandlerIntrospection.ParamKind.JsonBody =>
        (req, _) => mkJsonBodyExtraction[A](req, pathStr, method)
      case other =>
        import q.reflect.*
        report.errorAndAbort(s"Unsupported parameter kind: $other")
    }
  }

  // --- Individual typed extractors ---

  private def mkPathExtraction[A: Type](using q: Quotes)(
    paramName: String,
    pathParams: Expr[Map[String, String]],
    pathStr: String, method: String
  ): Expr[MEru[A]] = {
    val nameExpr = Expr(paramName)
    val fps = summonOrAbort[net.ghoula.melian.extraction.FromPathSegment[A]](
      method, pathStr, paramName, s"FromPathSegment[${Type.show[A]}]")
    '{ $fps.parse($pathParams.getOrElse($nameExpr, ""), $nameExpr).mapError { err =>
        net.ghoula.melian.RequestError.ExtractionFailed(Vector(err)): ErrType
      }
    }
  }

  private def mkHeaderExtraction[A: Type](using q: Quotes)(
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String, method: String
  ): Expr[MEru[A]] = {
    val fh = summonOrAbort[net.ghoula.melian.extraction.FromHeader[A]](
      method, pathStr, "header", s"FromHeader[${Type.show[A]}]")
    '{
      val headerName = $fh.headerName
      $request.headers.getFirst(headerName) match {
        case Some(hv) =>
          $fh.parse(hv.value).mapError { err =>
            net.ghoula.melian.RequestError.ExtractionFailed(Vector(err)): ErrType
          }
        case None =>
          net.ghoula.eru.Eru.fail(net.ghoula.melian.RequestError.ExtractionFailed(Vector(
            net.ghoula.melian.ExtractionError(net.ghoula.melian.ExtractionSource.Header, headerName,
              s"Missing required header: $headerName", None, None)
          )): ErrType)
      }
    }
  }

  private def mkJsonBodyExtraction[A: Type](using q: Quotes)(
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String, method: String
  ): Expr[MEru[A]] = {
    val bd = summonOrAbort[net.ghoula.eru.http.BodyDecoder[A]](
      method, pathStr, "body", s"BodyDecoder[${Type.show[A]}]")
    '{
      SaratiBridge.validateContentType($request.headers).mapError { err =>
        net.ghoula.melian.RequestError.DecodeFailed(List(err.message)): ErrType
      }.flatMap { _ =>
        $bd.decode($request.body).mapError { err =>
          net.ghoula.melian.RequestError.DecodeFailed(List(err.message)): ErrType
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
