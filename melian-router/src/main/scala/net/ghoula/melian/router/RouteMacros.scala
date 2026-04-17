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

    bodyTypeRepr.asType match {
      case '[b] =>
        val bodyEncoderExpr = summonOrAbort[net.ghoula.eru.http.BodyEncoder[b]](
          method, pathStr, "response", s"BodyEncoder[${Type.show[b]}]")

        // Build extraction list — closures that take (request, pathParams) and produce Eru[..., Any]
        var pathIdx = 0
        val extractionBuilders: List[(Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]], Expr[Map[String, String]]) => Expr[MEru[Any]]] =
          info.params.map { paramInfo =>
            val tpe = TypeRepr.of[H].dealias match {
              case AppliedType(_, args) => args(paramInfo.index)
              case _ => report.errorAndAbort("Cannot decompose handler type")
            }
            paramInfo.kind match {
              case HandlerIntrospection.ParamKind.PathParam =>
                val name = templateParamNames(pathIdx)
                pathIdx += 1
                (_, pp) => mkPathExtraction(tpe, name, pp, pathStr, method)
              case HandlerIntrospection.ParamKind.HeaderParam =>
                (req, _) => mkHeaderExtraction(tpe, req, pathStr, method)
              case HandlerIntrospection.ParamKind.JsonBody =>
                (req, _) => mkJsonBodyExtraction(tpe, req, pathStr, method)
              case other =>
                report.errorAndAbort(s"Unsupported parameter kind: $other")
            }
          }

        val handlerExpr: Expr[(net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] =
          '{
            (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
              ${
                val exprs = extractionBuilders.map(f => f('request, 'pathParams))
                nestAndCall[H, b](handler, exprs, bodyEncoderExpr)
              }
          }

        '{
          $builder.addEntry(RouteEntry(
            pathTemplate = ${ Expr(pathStr) },
            method = $methodExpr,
            handler = $handlerExpr
          ))
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
    // Mirrors Method.allowsRequestBody / requiresRequestBody from eru-http.
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

  // --- Types ---

  private type MEru[A] = net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, A]

  // --- Extraction builders ---

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

  // --- Handler call with error-accumulating extraction ---
  // Uses Eru.attempt to run ALL extractions independently, then combines errors or calls handler.
  // The handler.asInstanceOf is safe: opaque types erase at runtime.

  private def nestAndCall[H: Type, B: Type](using q: Quotes)(
    handler: Expr[H],
    extractions: List[Expr[MEru[Any]]],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]]
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    val extractionExprs = Expr.ofList(extractions)
    '{
      accumulateAndCall($extractionExprs, $handler, $bodyEncoder.asInstanceOf[net.ghoula.eru.http.BodyEncoder[Any]])
    }
  }

  // Runtime: runs all extractions via attempt, accumulates errors, then calls handler or fails.
  private def accumulateAndCall[H](
    extractions: List[net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]],
    handler: H,
    encoder: net.ghoula.eru.http.BodyEncoder[Any]
  ): net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.{Result as EruResult}

    // Run all extractions, converting failures to values via attempt
    val attempted: List[Eru[Nothing, EruResult[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, Any]]] =
      extractions.map(_.attempt)

    // Sequence the attempts (they never fail, so flatMap is fine here)
    sequenceEru(attempted).flatMap { results =>
      // Partition into successes and failures
      val values = results.collect { case EruResult.Success(v) => v }
      val errors = results.collect { case EruResult.Failure(e) => e }

      if errors.nonEmpty then {
        // Accumulate all extraction errors into a single ExtractionFailed
        val allExtractionErrors = errors.flatMap {
          case net.ghoula.melian.RequestError.ExtractionFailed(errs) => errs.toList
          case other => List(net.ghoula.melian.ExtractionError(
            net.ghoula.melian.ExtractionSource.Path, "unknown", other.toString, None, None))
        }.toVector
        Eru.fail(net.ghoula.melian.RequestError.ExtractionFailed(allExtractionErrors): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError)
      } else {
        // All succeeded — call handler
        callHandler(handler, values, encoder)
      }
    }
  }

  private def sequenceEru[A](effects: List[net.ghoula.eru.Eru[Nothing, A]]): net.ghoula.eru.Eru[Nothing, List[A]] =
    effects.foldRight(net.ghoula.eru.Eru.succeed(List.empty[A])) { (effect, acc) =>
      effect.flatMap(a => acc.map(rest => a :: rest))
    }

  private def callHandler[H](
    handler: H,
    args: List[Any],
    encoder: net.ghoula.eru.http.BodyEncoder[Any]
  ): net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    val result = args.size match {
      case 1 => handler.asInstanceOf[Any => Eru[Any, Any]].apply(args(0))
      case 2 => handler.asInstanceOf[(Any, Any) => Eru[Any, Any]].apply(args(0), args(1))
      case 3 => handler.asInstanceOf[(Any, Any, Any) => Eru[Any, Any]].apply(args(0), args(1), args(2))
      case 4 => handler.asInstanceOf[(Any, Any, Any, Any) => Eru[Any, Any]].apply(args(0), args(1), args(2), args(3))
      case n => Eru.fail(net.ghoula.eru.http.HttpError.ProtocolError(s"Unsupported handler arity: $n", "internal"))
    }
    result.mapError { err =>
      net.ghoula.eru.http.HttpError.ProtocolError(err.toString, "domain"): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
    }.flatMap(resp => encodeResponse(resp, encoder))
  }

  // --- Response encoding ---

  // Runtime response encoding. The encoder is BodyEncoder[Any] via safe cast from the compile-time
  // typed BodyEncoder[B]. The response wrapper (Ok, Created, etc.) is pattern-matched at runtime.
  private def encodeResponse(
    wrapper: Any,
    encoder: net.ghoula.eru.http.BodyEncoder[Any]
  ): net.ghoula.eru.Eru[net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    wrapper match {
      case ok: net.ghoula.melian.Ok[?] =>
        Response.okEncoded(ok.body)(using encoder).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError }
      case _: net.ghoula.melian.NoContent.type =>
        Eru.succeed(Response.noContent)
      case created: net.ghoula.melian.Created[?] =>
        encoder.encode(created.body).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError
        }.map(body => Response(StatusCode.Created, Headers.empty, body))
      case accepted: net.ghoula.melian.Accepted[?] =>
        Response.acceptedEncoded(accepted.body)(using encoder).mapError { err =>
          HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | HttpError }
      case _ =>
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(wrapper.toString)))
    }
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
