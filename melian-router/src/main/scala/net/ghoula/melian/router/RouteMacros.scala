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

        // Generate the complete handler expression at compile time
        val handlerExpr = '{
          (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
            ${
              val exprs = extractionBuilders.map(f => f('request, 'pathParams))
              val exprsList = Expr.ofList(exprs)

              // Generate the typed handler call + response encoding
              val callAndEncode: Expr[List[Any] => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] =
                generateTypedCallAndEncode[H, b](handler, info, bodyEncoderExpr, returnsEndpoint, 'request)

              '{
                val extractions = $exprsList
                val attempted = extractions.map(_.attempt)
                net.ghoula.eru.Eru.sequence(attempted).flatMap { results =>
                  val values = results.collect { case net.ghoula.eru.Result.Success(v) => v }
                  val errors = results.collect { case net.ghoula.eru.Result.Failure(e) => e }
                  if errors.nonEmpty then {
                    val allErrors = errors.flatMap {
                      case net.ghoula.melian.RequestError.ExtractionFailed(errs) => errs.toList
                      case other => List(net.ghoula.melian.ExtractionError(
                        net.ghoula.melian.ExtractionSource.Path, "unknown", other.toString, None, None))
                    }.toVector
                    net.ghoula.eru.Eru.fail(
                      net.ghoula.melian.RequestError.ExtractionFailed(allErrors): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError)
                  } else {
                    $callAndEncode(values)
                  }
                }
              }
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

  /** Generates a typed function that takes extracted args and calls the handler + encodes response.
    * All types are known at compile time — no runtime arity dispatch or asInstanceOf on the handler.
    */
  private def generateTypedCallAndEncode[H: Type, B: Type](using q: Quotes)(
    handler: Expr[H],
    info: HandlerIntrospection.HandlerInfo,
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]],
    returnsEndpoint: Boolean,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]]
  ): Expr[List[Any] => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    import q.reflect.*

    val paramCount = info.params.size
    val responseWrapper = info.response.wrapperName

    '{ (args: List[Any]) =>
      val ctx = LiveRequestContext.from($request)
      // Call handler — typed at compile time via H
      val rawResult: Any = ${
        // Generate the handler application for the known arity
        paramCount match {
          case 1 => '{ $handler.asInstanceOf[Any => Any].apply(args(0)) }
          case 2 => '{ $handler.asInstanceOf[(Any, Any) => Any].apply(args(0), args(1)) }
          case 3 => '{ $handler.asInstanceOf[(Any, Any, Any) => Any].apply(args(0), args(1), args(2)) }
          case 4 => '{ $handler.asInstanceOf[(Any, Any, Any, Any) => Any].apply(args(0), args(1), args(2), args(3)) }
          case n => report.errorAndAbort(s"Handlers with $n parameters not supported (max 4)")
        }
      }
      // Resolve context function if handler returns Endpoint
      val eru: net.ghoula.eru.Eru[Any, Any] = ${
        if returnsEndpoint then
          '{ rawResult.asInstanceOf[Function1[net.ghoula.melian.RequestContext, net.ghoula.eru.Eru[Any, Any]]].apply(ctx) }
        else
          '{ rawResult.asInstanceOf[net.ghoula.eru.Eru[Any, Any]] }
      }
      // Encode response — wrapper type known at compile time
      eru.mapError { err =>
        net.ghoula.eru.http.HttpError.ProtocolError(err.toString, "domain"): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
      }.flatMap { responseValue =>
        ${
          generateResponseEncoding[B](bodyEncoder, responseWrapper, 'responseValue)
        }
      }
    }
  }

  /** Generates typed response encoding based on the compile-time known wrapper type. */
  private def generateResponseEncoding[B: Type](using q: Quotes)(
    encoder: Expr[net.ghoula.eru.http.BodyEncoder[B]],
    wrapperName: String,
    responseValue: Expr[Any]
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    wrapperName match {
      case "Ok" =>
        '{
          val ok = $responseValue.asInstanceOf[net.ghoula.melian.Ok[B]]
          net.ghoula.eru.http.Response.okEncoded(ok.body)(using $encoder).mapError { err =>
            net.ghoula.eru.http.HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
          }
        }
      case "Created" =>
        '{
          val created = $responseValue.asInstanceOf[net.ghoula.melian.Created[B]]
          $encoder.encode(created.body).mapError { err =>
            net.ghoula.eru.http.HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
          }.map(body => net.ghoula.eru.http.Response(net.ghoula.eru.http.StatusCode.Created, net.ghoula.eru.http.Headers.empty, body))
        }
      case "Accepted" =>
        '{
          val accepted = $responseValue.asInstanceOf[net.ghoula.melian.Accepted[B]]
          net.ghoula.eru.http.Response.acceptedEncoded(accepted.body)(using $encoder).mapError { err =>
            net.ghoula.eru.http.HttpError.BodyEncodeError(err): net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
          }
        }
      case "NoContent" =>
        '{ net.ghoula.eru.Eru.succeed(net.ghoula.eru.http.Response.noContent) }
      case other =>
        import q.reflect.*
        report.errorAndAbort(s"Unsupported response type: $other. Expected Ok, Created, Accepted, or NoContent.")
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
