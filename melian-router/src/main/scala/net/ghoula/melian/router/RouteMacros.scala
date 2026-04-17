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

    val (_, returnType) = HandlerIntrospection.decomposeFunction(TypeRepr.of[H].dealias)
    val (unwrappedReturn, _) = returnType.dealias match {
      case AppliedType(cf, List(_, result)) if cf.typeSymbol.fullName.contains("ContextFunction") => (result, true)
      case other => (other, false)
    }
    val (errorTypeRepr, responseTypeRepr) = unwrappedReturn.dealias match {
      case AppliedType(_, List(e, r)) => (e, r)
      case _ => (TypeRepr.of[Nothing], TypeRepr.of[Nothing])
    }
    val bodyTypeRepr = responseTypeRepr.dealias match {
      case AppliedType(_, List(b)) => b
      case _ => TypeRepr.of[Nothing]
    }
    val returnsEndpoint = info.response.isEndpoint

    (errorTypeRepr.asType, responseTypeRepr.asType, bodyTypeRepr.asType) match {
      case ('[e], '[r], '[b]) =>
        val bodyEncoderExpr = summonOrAbort[net.ghoula.eru.http.BodyEncoder[b]](
          method, pathStr, "response", s"BodyEncoder[${Type.show[b]}]")
        val errorRendererExpr = summonOrAbort[net.ghoula.melian.ErrorRenderer[e]](
          method, pathStr, "error", s"ErrorRenderer[${Type.show[e]}]")

        val paramTypes = TypeRepr.of[H].dealias match {
          case AppliedType(_, args) => args.init
          case _ => report.errorAndAbort("Cannot decompose handler type")
        }
        val templateParamNames = template.paramNames

        val handlerExpr: Expr[HandlerFn] = '{
          (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
            ${
              // Build typed extractions — store Type[a] (crosses quote boundaries) not TypeRepr
              var pathIdx = 0
              val typedExtractions: List[(Type[?], Expr[MEru[Any]])] =
                paramTypes.zip(info.params).map { case (tpe, paramInfo) =>
                  val innerType = tpe match {
                    case AppliedType(_, List(inner)) => inner
                    case other => other
                  }
                  innerType.asType match {
                    case '[a] =>
                      val extraction: Expr[MEru[a]] = paramInfo.kind match {
                        case HandlerIntrospection.ParamKind.PathParam =>
                          val name = templateParamNames(pathIdx)
                          pathIdx += 1
                          mkPathExtraction[a](name, 'pathParams, pathStr, method)
                        case HandlerIntrospection.ParamKind.HeaderParam =>
                          mkHeaderExtraction[a]('request, pathStr, method)
                        case HandlerIntrospection.ParamKind.JsonBody =>
                          mkJsonBodyExtraction[a]('request, pathStr, method)
                        case other =>
                          report.errorAndAbort(s"Unsupported parameter kind: $other")
                      }
                      (Type.of[a], '{ $extraction: MEru[Any] })
                  }
                }

              buildChain[H, e, r, b](
                typedExtractions, handler, bodyEncoderExpr, errorRendererExpr,
                returnsEndpoint, 'request, 'pathParams, pathStr,
                Nil
              )
            }
        }

        '{
          $builder.addEntry(RouteEntry(
            pathTemplate = ${ Expr(pathStr) },
            method = $methodExpr,
            handler = $handlerExpr
          ))
        }

      case _ => report.errorAndAbort("Failed to extract error/response/body types from handler")
    }
  }

  /** Recursively builds e.attempt.flatMap { r => ... } chain.
    *
    * Each extraction is attempted (Eru[Nothing, Result[...]]). The flatMap never fails since
    * attempted effects always succeed. At the base case, all Result bindings are in scope and
    * we check for errors / call the handler.
    */
  private def buildChain[H: Type, E: Type, R: Type, B: Type](using q: Quotes)(
    remaining: List[(Type[?], Expr[MEru[Any]])],
    handler: Expr[H],
    bodyEncoder: Expr[net.ghoula.eru.http.BodyEncoder[B]],
    errorRenderer: Expr[net.ghoula.melian.ErrorRenderer[E]],
    returnsEndpoint: Boolean,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathParams: Expr[Map[String, String]],
    pathStr: String,
    accumulated: List[(Type[?], Expr[net.ghoula.eru.Result[ErrType, Any]])]
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {

    remaining match {
      case Nil =>
        val errorExprs = accumulated.map(_._2)
        val errorList = Expr.ofList(errorExprs)

        val typedArgs: List[Expr[Any]] = accumulated.map { case (tpe, resultExpr) =>
          tpe match {
            case '[a] => '{
              $resultExpr match {
                case net.ghoula.eru.Result.Success(v) => v.asInstanceOf[a]
                case _ => throw new AssertionError("unreachable")
              }
            }
          }
        }
        val pathTemplateExpr = Expr(pathStr)

        '{
          val errors = $errorList.collect { case net.ghoula.eru.Result.Failure(e) => e }
          if errors.nonEmpty then {
            val allErrors = errors.flatMap {
              case net.ghoula.melian.RequestError.ExtractionFailed(errs) => errs.toList
              case other => List(net.ghoula.melian.ExtractionError(
                net.ghoula.melian.ExtractionSource.Path, "unknown", other.toString, None, None))
            }.toVector
            net.ghoula.eru.Eru.fail(
              net.ghoula.melian.RequestError.ExtractionFailed(allErrors): ErrType)
          } else {
            ${
              // Handler return type: Eru[E, R] or Endpoint[E, R] (= RequestContext ?=> Eru[E, R])
              // generateApply returns Expr[Any] but the actual type is known from H
              import q.reflect.*
              val typedCall = Apply(
                Select.unique(handler.asTerm, "apply"),
                typedArgs.map(_.asTerm)
              )

              // For Endpoint handlers: the Apply produces RequestContext ?=> Eru[E, R]
              // which is structurally Function1[RequestContext, Eru[E,R]] at JVM level.
              // One asInstanceOf to cross the context function boundary.
              // For plain handlers: Apply directly produces Eru[E, R], no cast needed.
              val eruExpr: Expr[net.ghoula.eru.Eru[E, R]] =
                if returnsEndpoint then '{
                  val ctx: net.ghoula.melian.RequestContext = LiveRequestContext.from($request)
                  ${ typedCall.asExprOf[Any] }.asInstanceOf[Function1[net.ghoula.melian.RequestContext, net.ghoula.eru.Eru[E, R]]](ctx)
                }
                else typedCall.asExprOf[net.ghoula.eru.Eru[E, R]]

              '{
                $eruExpr.attempt.flatMap {
                  case net.ghoula.eru.Result.Success(response) =>
                    encodeResponse[R, B](response, $bodyEncoder, $pathTemplateExpr, $pathParams)
                  case net.ghoula.eru.Result.Failure(domainError) =>
                    $errorRenderer.render(domainError)
                }
              }
            }
          }
        }

      case (tpe, extractionExpr) :: tail =>
        '{
          $extractionExpr.attempt.flatMap { (result: net.ghoula.eru.Result[ErrType, Any]) =>
            ${
              buildChain[H, E, R, B](
                tail, handler, bodyEncoder, errorRenderer,
                returnsEndpoint, request, pathParams, pathStr,
                accumulated :+ (tpe, 'result)
              )
            }
          }
        }
    }
  }

  // --- Types ---

  private type ErrType = net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
  private type MEru[A] = net.ghoula.eru.Eru[ErrType, A]
  private type HandlerFn = (net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) => MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]

  // --- Extraction builders ---

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

  // --- Response encoding ---

  private def encodeResponse[R, B](
    response: R,
    encoder: net.ghoula.eru.http.BodyEncoder[B],
    pathTemplate: String,
    pathParams: Map[String, String]
  ): net.ghoula.eru.Eru[ErrType, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    (response: Any) match {
      case ok: net.ghoula.melian.Ok[B @unchecked] =>
        Response.okEncoded(ok.body)(using encoder).mapError { err =>
          HttpError.BodyEncodeError(err): ErrType }
      case _: net.ghoula.melian.NoContent.type =>
        Eru.succeed(Response.noContent)
      case created: net.ghoula.melian.Created[B @unchecked] =>
        val locationPath = created.location match {
          case Some(uri) => uri.path
          case None => deriveLocation(pathTemplate, pathParams)
        }
        encoder.encode(created.body).mapError { err =>
          HttpError.BodyEncodeError(err): ErrType
        }.flatMap { body =>
          Uri.parse(locationPath).mapError { err =>
            HttpError.InvalidUri(err): ErrType
          }.flatMap { uri =>
            Response.created(uri, body).mapError { err =>
              HttpError.InvalidResponse(InvalidResponse(err.toString, "Location header")): ErrType
            }
          }
        }
      case accepted: net.ghoula.melian.Accepted[B @unchecked] =>
        Response.acceptedEncoded(accepted.body)(using encoder).mapError { err =>
          HttpError.BodyEncodeError(err): ErrType }
      case _ =>
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(response.toString)))
    }
  }

  private def deriveLocation(pathTemplate: String, pathParams: Map[String, String]): String =
    PathTemplate.parse(pathTemplate) match {
      case Right(parsed) =>
        parsed.segments.map {
          case PathTemplate.Segment.Literal(v) => v
          case PathTemplate.Segment.Param(name) => pathParams.getOrElse(name, name)
        }.mkString("/", "/", "")
      case Left(_) => pathTemplate
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
