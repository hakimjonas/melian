package net.ghoula.melian.router

import scala.quoted.*

import net.ghoula.melian.MelianHeaders

object RouteMacros {

  def addRoute[H: Type](
    builder: Expr[RouterBuilder],
    path: Expr[String],
    handler: Expr[H],
    methodStr: Expr[String],
    operationId: Expr[String],
    summary: Expr[String],
    description: Expr[String],
    tags: Expr[String]
  )(using q: Quotes): Expr[RouterBuilder] = {
    import q.reflect.*

    val pathStr = path.valueOrAbort
    val method = methodStr.valueOrAbort
    val operationIdStr = operationId.value.getOrElse("")
    val summaryStr = summary.value.getOrElse("")
    val descriptionStr = description.value.getOrElse("")
    val tagsVec = tags.value.getOrElse("").split(",").map(_.trim).filter(_.nonEmpty).toVector

    val template = PathTemplate.parse(pathStr) match {
      case Right(p) => p
      case Left(e) => report.errorAndAbort(s"Invalid path template: $e")
    }

    val info = HandlerIntrospection.analyze[H]
    verifyPathParams(method, pathStr, template, info)
    verifyMethodConstraints(method, pathStr, info)
    val templateParamNames = template.paramNames

    val methodExpr = method match {
      case "GET" => '{ net.ghoula.eru.http.Method.GET }
      case "POST" => '{ net.ghoula.eru.http.Method.POST }
      case "PUT" => '{ net.ghoula.eru.http.Method.PUT }
      case "DELETE" => '{ net.ghoula.eru.http.Method.DELETE }
      case "PATCH" => '{ net.ghoula.eru.http.Method.PATCH }
      case "HEAD" => '{ net.ghoula.eru.http.Method.HEAD }
      case "OPTIONS" => '{ net.ghoula.eru.http.Method.OPTIONS }
      case "QUERY" => '{ net.ghoula.eru.http.Method.QUERY }
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
      case AppliedType(_, args) if args.nonEmpty => args.last
      case _ => TypeRepr.of[Nothing]
    }
    val returnsEndpoint = info.response.isEndpoint

    val paramTypes = TypeRepr.of[H].dealias match {
      case AppliedType(_, args) => args.init
      case _ => report.errorAndAbort("Cannot decompose handler type")
    }

    val isEventStream = info.response.wrapperName == "EventStream"

    // Body-less response wrappers (NoContent, SeeOther, NotModified) carry no body type, so the
    // macro must not summon a BodyEncoder for them: there is nothing to encode.
    val hasResponseBody = info.response.bodyTypeRepr.isDefined

    // Response-side content negotiation engages when the handler declares Header[Accept] and the
    // wrapper carries a body. The media types offered are exactly those with an encoder.
    val negotiatesContent =
      !isEventStream && hasResponseBody && info.params.exists { p =>
        p.kind == HandlerIntrospection.ParamKind.HeaderParam &&
        HandlerIntrospection.innerTypeOf(paramTypes(p.index)) =:=
          TypeRepr.of[net.ghoula.melian.extraction.FromHeader.Accept]
      }

    val responseStatus =
      SchemaGen.responseStatusCode(info.response.wrapperName, responseTypeRepr)
    val customStatusCode = Option.when(info.response.wrapperName == "Status")(responseStatus)

    (errorTypeRepr.asType, responseTypeRepr.asType, bodyTypeRepr.asType) match {
      case ('[e], '[r], '[b]) =>
        val bodyEncoderExpr: Option[Expr[net.ghoula.eru.http.BodyEncoder[b]]] =
          if isEventStream || !hasResponseBody || negotiatesContent then None
          else
            Some(
              summonOrAbort[net.ghoula.eru.http.BodyEncoder[b]](
                method,
                pathStr,
                "response",
                s"BodyEncoder[${Type.show[b]}]"
              )
            )

        // Typed SSE: for an EventStream[A] whose element type is not ServerSentEvent, each event
        // is JSON-encoded via Encoder[A, JsonValue]. Element type ServerSentEvent is the raw
        // passthrough mode and needs no encoder.
        val rawSse = TypeRepr.of[b].dealias =:= TypeRepr.of[net.ghoula.eru.http.ServerSentEvent]
        val sseEventEncoder: Option[Expr[net.ghoula.sarati.codec.Encoder[b, net.ghoula.sarati.ast.json.JsonValue]]] =
          if !isEventStream || rawSse then None
          else
            Some(
              summonOrAbort[net.ghoula.sarati.codec.Encoder[b, net.ghoula.sarati.ast.json.JsonValue]](
                method,
                pathStr,
                "response",
                s"Encoder[${Type.show[b]}, JsonValue] (SSE event payload)"
              )
            )

        // Negotiation needs the Sarati encoders directly: JSON is the baseline, XML and YAML are
        // opt-in via their encoders' presence.
        val negotiatedMediaTypes: Vector[String] =
          if !negotiatesContent then Vector("application/json")
          else {
            val base = Vector("application/json")
            val withXml =
              if Expr.summon[net.ghoula.sarati.codec.Encoder[b, net.ghoula.sarati.ast.xml.XmlNode]].isDefined
              then base :+ "application/xml"
              else base
            if Expr.summon[net.ghoula.sarati.codec.Encoder[b, net.ghoula.sarati.ast.yaml.YamlValue]].isDefined
            then withXml :+ "application/yaml"
            else withXml
          }

        val negotiatorExpr: Option[Expr[ResponseNegotiator[b]]] =
          if !negotiatesContent then None
          else {
            val jsonEnc = summonOrAbort[net.ghoula.sarati.codec.Encoder[b, net.ghoula.sarati.ast.json.JsonValue]](
              method,
              pathStr,
              "response",
              s"Encoder[${Type.show[b]}, JsonValue]"
            )
            val xmlEnc = Expr.summon[net.ghoula.sarati.codec.Encoder[b, net.ghoula.sarati.ast.xml.XmlNode]]
            val yamlEnc = Expr.summon[net.ghoula.sarati.codec.Encoder[b, net.ghoula.sarati.ast.yaml.YamlValue]]
            val xmlOpt = xmlEnc match {
              case Some(enc) => '{ Some($enc) }
              case None => '{ None }
            }
            val yamlOpt = yamlEnc match {
              case Some(enc) => '{ Some($enc) }
              case None => '{ None }
            }
            Some('{ ResponseNegotiator[b]($jsonEnc, $xmlOpt, $yamlOpt) })
          }

        // Error rendering tiers: an ErrorRenderer given in scope (endpoint tier) wins; otherwise
        // the builder-level renderer active at this registration (group/global tier), with the
        // built-in problem-details 500 as the last resort.
        val summonedErrorRenderer = Expr.summon[net.ghoula.melian.ErrorRenderer[e]]

        val handlerExpr: Expr[HandlerFn] = '{
          // Status[Code, A] routes resolve their phantom code once, at route construction; the
          // macro validated it at compile time, so this cannot fail. Other routes carry None.
          val hoistedStatus: Option[net.ghoula.eru.http.StatusCode] = ${
            customStatusCode match {
              case Some(code) =>
                '{ Some(net.ghoula.eru.http.StatusCode(${ Expr(code) }).unsafeRunSync()) }
              case None => '{ None }
            }
          }
          (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
            ${
              // Build typed extractions: store Type[a] (crosses quote boundaries) not TypeRepr.
              // Path parameters draw their names from templateParamNames in order; we pre-zip the
              // path params with their template names so the per-param mapping needs no counter.
              val pathParamNames: Map[Int, String] = {
                val pathParamIndices =
                  paramTypes.zip(info.params).zipWithIndex.collect {
                    case ((_, p), i) if p.kind == HandlerIntrospection.ParamKind.PathParam => i
                  }
                pathParamIndices.zip(templateParamNames).toMap
              }
              // Single-parameter routes take a fast path: extract directly (no attempt/Result
              // wrapping), so the value stays typed, the extraction error propagates naturally,
              // and the handler call needs no asInstanceOf.
              if paramTypes.size == 1 then {
                val (tpe, paramInfo) = (paramTypes.head, info.params.head)
                val innerType = HandlerIntrospection.innerTypeOf(using q)(tpe)
                innerType.asType match {
                  case '[a] =>
                    if isDecodeResultKind(paramInfo.kind) then {
                      val extraction =
                        mkDecodeBodyExtraction[a](paramInfo, 'request, pathStr, method, paramInfo.jsonStrict)
                      '{
                        $extraction.flatMap { (result: SaratiBridge.DecodeResult[a]) =>
                          ${
                            invokeHandlerAndEncode[H, e, r, b](
                              List('{ result.value }),
                              '{ result.warnings },
                              handler,
                              builder,
                              summonedErrorRenderer,
                              bodyEncoderExpr,
                              sseEventEncoder,
                              negotiatorExpr,
                              'hoistedStatus,
                              returnsEndpoint,
                              isEventStream,
                              'request,
                              'pathParams,
                              pathStr
                            )
                          }
                        }
                      }
                    } else {
                      val extraction =
                        mkExtraction[a](paramInfo, 0, pathParamNames, 'request, 'pathParams, pathStr, method)
                      '{
                        $extraction.flatMap { (value: a) =>
                          ${
                            invokeHandlerAndEncode[H, e, r, b](
                              List('{ value }),
                              '{ List.empty[String] },
                              handler,
                              builder,
                              summonedErrorRenderer,
                              bodyEncoderExpr,
                              sseEventEncoder,
                              negotiatorExpr,
                              'hoistedStatus,
                              returnsEndpoint,
                              isEventStream,
                              'request,
                              'pathParams,
                              pathStr
                            )
                          }
                        }
                      }
                    }
                }
              } else {
                val typedExtractions: List[(Type[?], Expr[MEru[Any]])] =
                  paramTypes.zip(info.params).zipWithIndex.map { case ((tpe, paramInfo), idx) =>
                    val innerType = HandlerIntrospection.innerTypeOf(using q)(tpe)
                    innerType.asType match {
                      case '[a] =>
                        if isDecodeResultKind(paramInfo.kind) then {
                          val extraction =
                            mkDecodeBodyExtraction[a](paramInfo, 'request, pathStr, method, paramInfo.jsonStrict)
                          (Type.of[SaratiBridge.DecodeResult[a]], '{ $extraction: MEru[Any] })
                        } else {
                          val extraction =
                            mkExtraction[a](paramInfo, idx, pathParamNames, 'request, 'pathParams, pathStr, method)
                          (Type.of[a], '{ $extraction: MEru[Any] })
                        }
                    }
                  }

                buildChain(
                  typedExtractions,
                  base = { (typedArgs, warningsExpr) =>
                    invokeHandlerAndEncode[H, e, r, b](
                      typedArgs,
                      warningsExpr,
                      handler,
                      builder,
                      summonedErrorRenderer,
                      bodyEncoderExpr,
                      sseEventEncoder,
                      negotiatorExpr,
                      'hoistedStatus,
                      returnsEndpoint,
                      isEventStream,
                      'request,
                      'pathParams,
                      pathStr
                    )
                  },
                  Nil
                )
              }
            }
        }

        val schemaExpr = SchemaGen.operationSchema(
          pathStr,
          method,
          info,
          paramTypes,
          templateParamNames,
          responseStatus,
          bodyTypeRepr,
          isEventStream,
          Option.when(operationIdStr.nonEmpty)(operationIdStr),
          negotiatedMediaTypes,
          Option.when(summaryStr.nonEmpty)(summaryStr),
          Option.when(descriptionStr.nonEmpty)(descriptionStr),
          tagsVec
        )

        '{
          $builder.addEntry(
            RouteEntry(
              pathTemplate = ${ Expr(pathStr) },
              method = $methodExpr,
              handler = $handlerExpr,
              schema = $schemaExpr
            )
          )
        }

      case _ => report.errorAndAbort("Failed to extract error/response/body types from handler")
    }
  }

  /** Body markers whose extraction carries decode warnings. */
  private def isDecodeResultKind(kind: HandlerIntrospection.ParamKind): Boolean =
    kind == HandlerIntrospection.ParamKind.JsonBody ||
      kind == HandlerIntrospection.ParamKind.CodedBody ||
      kind == HandlerIntrospection.ParamKind.FormBody

  /** Dispatches to the body extraction builder for each body marker kind. */
  private def mkDecodeBodyExtraction[A: Type](using
    q: Quotes
  )(
    paramInfo: HandlerIntrospection.ParamInfo,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String,
    strict: Boolean
  ): Expr[MEru[SaratiBridge.DecodeResult[A]]] = {
    import q.reflect.*
    paramInfo.kind match {
      case HandlerIntrospection.ParamKind.JsonBody => mkJsonBodyExtraction[A](request, pathStr, method, strict)
      case HandlerIntrospection.ParamKind.CodedBody => mkCodedBodyExtraction[A](request, pathStr, method, strict)
      case HandlerIntrospection.ParamKind.FormBody => mkFormBodyExtraction[A](request, pathStr, method)
      case other => report.errorAndAbort(s"Not a body marker kind: $other")
    }
  }

  /** Recursively builds e.attempt.flatMap { r => ... } chain.
    *
    * Each extraction is attempted (Eru[Nothing, Result[...]]). The flatMap never fails since
    * attempted effects always succeed. At the base case, all Result bindings are in scope and we
    * check for errors / call the handler.
    */
  private def buildChain(using
    q: Quotes
  )(
    remaining: List[(Type[?], Expr[MEru[Any]])],
    base: (List[Expr[Any]], Expr[List[String]]) => Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]],
    accumulated: List[(Type[?], Expr[net.ghoula.eru.Result[ErrType, Any]])]
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {

    remaining match {
      case Nil =>
        val errorExprs = accumulated.map(_._2)
        val errorList = Expr.ofList(errorExprs)

        val typedArgs: List[Expr[Any]] = accumulated.map { case (tpe, resultExpr) =>
          tpe match {
            case '[SaratiBridge.DecodeResult[inner]] =>
              '{
                val net.ghoula.eru.Result.Success(v) = ($resultExpr: @unchecked)
                v.asInstanceOf[SaratiBridge.DecodeResult[inner]].value // scalafix:ok DisableSyntax.asInstanceOf
              }
            case '[a] =>
              // The success payload arrives type-erased as Result[ErrType, Any]
              // (line ~114 upcasts each extraction so the heterogeneous list can
              // be chained homogeneously). Its element type is `a` but the Expr's
              // pickled type is erased, so asExprOf cannot refine it; the value
              // cast that recovers `a` is the irreducible boundary, the same
              // erase-then-recover-by-carried-Type pattern as Valar's named-tuple
              // productElement access. This block is spliced only into the branch
              // where every extraction already succeeded, so the Success pattern
              // is irrefutable by construction: an @unchecked binding states that
              // (no throw), and a MatchError would only surface a broken invariant.
              '{
                val net.ghoula.eru.Result.Success(v) = ($resultExpr: @unchecked)
                v.asInstanceOf[a] // scalafix:ok DisableSyntax.asInstanceOf
              }
          }
        }

        val warningExprs: List[Expr[List[String]]] = accumulated.flatMap { case (tpe, resultExpr) =>
          tpe match {
            case '[SaratiBridge.DecodeResult[inner]] =>
              List(
                '{
                  val net.ghoula.eru.Result.Success(v) = ($resultExpr: @unchecked)
                  v.asInstanceOf[SaratiBridge.DecodeResult[inner]].warnings // scalafix:ok DisableSyntax.asInstanceOf
                }
              )
            case _ => Nil
          }
        }
        val warningsExpr: Expr[List[String]] = '{ ${ Expr.ofList(warningExprs) }.flatten }

        '{
          val errors = $errorList.collect { case net.ghoula.eru.Result.Failure(e) => e }
          if errors.nonEmpty then {
            val extractionErrors = errors.collect { case net.ghoula.melian.RequestError.ExtractionFailed(errs) =>
              errs
            }.flatten.toVector
            val otherErrors = errors.filter {
              case _: net.ghoula.melian.RequestError.ExtractionFailed => false
              case _ => true
            }
            if otherErrors.nonEmpty then net.ghoula.eru.Eru.fail(otherErrors.head: ErrType)
            else net.ghoula.eru.Eru.fail(net.ghoula.melian.RequestError.ExtractionFailed(extractionErrors): ErrType)
          } else {
            ${ base(typedArgs, warningsExpr) }
          }
        }

      case (tpe, extractionExpr) :: tail =>
        '{
          $extractionExpr.attempt.flatMap { (result: net.ghoula.eru.Result[ErrType, Any]) =>
            ${
              buildChain(
                tail,
                base,
                accumulated :+ (tpe, 'result)
              )
            }
          }
        }
    }
  }

  /** Invokes the handler with the recovered arguments and encodes/renders the result.
    *
    * `typedArgs` are `Expr[Any]` only in their list type; each element's underlying term keeps its
    * real `a` type, so `Apply` sees correctly-typed arguments. Shared by the multi-parameter base
    * case and the single-parameter fast path.
    */
  private def invokeHandlerAndEncode[H: Type, E: Type, R: Type, B: Type](using
    q: Quotes
  )(
    typedArgs: List[Expr[Any]],
    warnings: Expr[List[String]],
    handler: Expr[H],
    builder: Expr[RouterBuilder],
    summonedErrorRenderer: Option[Expr[net.ghoula.melian.ErrorRenderer[E]]],
    bodyEncoder: Option[Expr[net.ghoula.eru.http.BodyEncoder[B]]],
    sseEventEncoder: Option[Expr[net.ghoula.sarati.codec.Encoder[B, net.ghoula.sarati.ast.json.JsonValue]]],
    negotiator: Option[Expr[ResponseNegotiator[B]]],
    hoistedStatus: Expr[Option[net.ghoula.eru.http.StatusCode]],
    returnsEndpoint: Boolean,
    isEventStream: Boolean,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathParams: Expr[Map[String, String]],
    pathStr: String
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    import q.reflect.*
    val pathTemplateExpr = Expr(pathStr)

    val typedCall = Apply(
      Select.unique(handler.asTerm, "apply"),
      typedArgs.map(_.asTerm)
    )

    // For Endpoint handlers the Apply produces an Endpoint[E, R] =
    // RequestContext ?=> Eru[E, R]. Rather than cast to Function1, we refine the Expr to its true
    // type and let the context function auto-apply against a given RequestContext brought into
    // scope: zero cast. Plain handlers are already Eru[E, R].
    val eruExpr: Expr[net.ghoula.eru.Eru[E, R]] =
      if returnsEndpoint then {
        val endpointExpr = typedCall.asExprOf[net.ghoula.melian.Endpoint[E, R]]
        '{
          val ctx: net.ghoula.melian.RequestContext =
            LiveRequestContext.from($request, $warnings)
          $endpointExpr(using ctx)
        }
      } else typedCall.asExprOf[net.ghoula.eru.Eru[E, R]]

    if isEventStream then
      '{
        $eruExpr.attempt.flatMap {
          case net.ghoula.eru.Result.Success(response) =>
            ${
              sseEventEncoder match {
                case Some(enc) =>
                  '{ encodeTypedEventStream[R, B](response, $enc, $warnings) }
                case None =>
                  '{ encodeRawEventStream[R](response, $warnings) }
              }
            }
          case net.ghoula.eru.Result.Failure(domainError) =>
            ${ renderError[E]('domainError, builder, summonedErrorRenderer) }
        }
      }
    else {
      val enc: Expr[Option[net.ghoula.eru.http.BodyEncoder[B]]] = bodyEncoder match {
        case Some(e) => '{ Some($e) }
        case None => '{ None }
      }
      val neg: Expr[Option[ResponseNegotiator[B]]] = negotiator match {
        case Some(n) => '{ Some($n) }
        case None => '{ None }
      }

      '{
        $eruExpr.attempt.flatMap {
          case net.ghoula.eru.Result.Success(response) =>
            encodeResponse[R, B](
              response,
              $enc,
              $neg,
              $hoistedStatus,
              $pathTemplateExpr,
              $pathParams,
              $warnings,
              $request
            )
          case net.ghoula.eru.Result.Failure(domainError) =>
            ${ renderError[E]('domainError, builder, summonedErrorRenderer) }
        }
      }
    }
  }

  /** The failure branch of a compiled route: an `ErrorRenderer` given in scope (endpoint tier)
    * wins; otherwise the builder-level renderer active at this route's registration (group/global
    * tier); the built-in problem-details 500 is the last resort.
    */
  private def renderError[E: Type](using
    q: Quotes
  )(
    domainError: Expr[E],
    builder: Expr[RouterBuilder],
    summonedErrorRenderer: Option[Expr[net.ghoula.melian.ErrorRenderer[E]]]
  ): Expr[net.ghoula.eru.Eru[Nothing, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] =
    summonedErrorRenderer match {
      case Some(renderer) => '{ $renderer.render($domainError) }
      case None =>
        '{
          $builder.groupErrorRenderer match {
            case Some(r) if r.isDefinedAt($domainError) => r($domainError)
            case _ => ProblemDetails.renderDomainFallback($domainError)
          }
        }
    }

  /** Compiles a WebSocket route binding.
    *
    * The handler's marked parameters (Path/Query/Header) are extracted from the upgrade request by
    * the same Girdle machinery as HTTP routes. Its return type must be `WebSocketEndpoint[In, Out]`
    * -- a function from the typed [[net.ghoula.melian.WebSocketSession]] to
    * `Eru[WebSocketError | HttpError, Unit]`. The route is registered under `Method.GET`; the RFC
    * 6455 upgrade itself is performed by eru-http's `WebSocketServer`.
    *
    * Inbound messages are decoded strictly via `Decoder[JsonValue, In]` and `Validator[In]`; a
    * rejected message closes the connection with 1003. Outbound messages are encoded via
    * `Encoder[Out, JsonValue]`.
    */
  def addWebSocketRoute[H: Type](
    builder: Expr[RouterBuilder],
    path: Expr[String],
    handler: Expr[H],
    wsConfig: Expr[net.ghoula.eru.http.server.WebSocketServerConfig],
    operationId: Expr[String],
    summary: Expr[String],
    description: Expr[String],
    tags: Expr[String]
  )(using q: Quotes): Expr[RouterBuilder] = {
    import q.reflect.*

    val pathStr = path.valueOrAbort
    val operationIdStr = operationId.value.getOrElse("")
    val summaryStr = summary.value.getOrElse("")
    val descriptionStr = description.value.getOrElse("")
    val tagsVec = tags.value.getOrElse("").split(",").map(_.trim).filter(_.nonEmpty).toVector

    val template = PathTemplate.parse(pathStr) match {
      case Right(p) => p
      case Left(e) => report.errorAndAbort(s"Invalid path template: $e")
    }

    val info = HandlerIntrospection.analyze[H]
    verifyPathParams("WEBSOCKET", pathStr, template, info)
    // The upgrade request is a GET: body markers can never be satisfied and would only fail at
    // runtime with BodyMissing, so reject them where everything else is rejected -- compile time.
    if info.hasBody then
      report.errorAndAbort(
        MacroErrors.formatMethodConstraint(
          "WEBSOCKET",
          pathStr,
          "WebSocket upgrade requests are GETs and cannot carry a body, but the handler declares a body parameter (Json[A], Coded[A], or Form[A]).",
          "Extract request data from path, query, and header parameters only."
        )
      )

    // The return type must be WebSocketEndpoint[In, Out] = WebSocketSession[In, Out] => Eru[...].
    val (_, returnType) = HandlerIntrospection.decomposeFunction(TypeRepr.of[H].dealias)
    val (wsFunction, isEndpoint) = returnType.dealias match {
      case AppliedType(cf, List(_, result)) if cf.typeSymbol.fullName.contains("ContextFunction") =>
        (result, true)
      case other => (other, false)
    }
    val (sessionTypeRepr, _) = HandlerIntrospection.decomposeFunction(wsFunction)
    val (inTypeRepr, outTypeRepr) = sessionTypeRepr.headOption match {
      case Some(AppliedType(base, List(inT, outT))) if base.typeSymbol.name == "WebSocketSession" =>
        (inT, outT)
      case other =>
        report.errorAndAbort(
          MacroErrors.formatMethodConstraint(
            "WEBSOCKET",
            pathStr,
            s"WebSocket handler must return WebSocketEndpoint[In, Out], got: ${other.map(_.show).getOrElse("?")}.",
            "Declare the return type as WebSocketEndpoint[ClientMessage, ServerMessage]."
          )
        )
    }

    val paramTypes = TypeRepr.of[H].dealias match {
      case AppliedType(_, args) => args.init
      case _ => report.errorAndAbort("Cannot decompose handler type")
    }
    val templateParamNames = template.paramNames

    (inTypeRepr.asType, outTypeRepr.asType) match {
      case ('[in], '[out]) =>
        val decoderExpr =
          summonOrAbort[net.ghoula.sarati.codec.Decoder[net.ghoula.sarati.ast.json.JsonValue, in]](
            "WEBSOCKET",
            pathStr,
            "message",
            s"Decoder[JsonValue, ${Type.show[in]}]"
          )
        val validatorExpr =
          summonOrAbort[net.ghoula.valar.Validator[in]]("WEBSOCKET", pathStr, "message", s"Validator[${Type.show[in]}]")
        val encoderExpr = summonOrAbort[net.ghoula.sarati.codec.Encoder[out, net.ghoula.sarati.ast.json.JsonValue]](
          "WEBSOCKET",
          pathStr,
          "message",
          s"Encoder[${Type.show[out]}, JsonValue]"
        )

        val pathParamNames: Map[Int, String] = {
          val pathParamIndices =
            paramTypes.zip(info.params).zipWithIndex.collect {
              case ((_, p), i) if p.kind == HandlerIntrospection.ParamKind.PathParam => i
            }
          pathParamIndices.zip(templateParamNames).toMap
        }

        val handlerExpr: Expr[HandlerFn] = '{
          (request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], pathParams: Map[String, String]) =>
            ${
              val typedExtractions: List[(Type[?], Expr[MEru[Any]])] =
                paramTypes.zip(info.params).zipWithIndex.map { case ((tpe, paramInfo), idx) =>
                  val innerType = HandlerIntrospection.innerTypeOf(using q)(tpe)
                  innerType.asType match {
                    case '[a] =>
                      if isDecodeResultKind(paramInfo.kind) then {
                        val extraction = mkDecodeBodyExtraction[a](paramInfo, 'request, pathStr, "WEBSOCKET", false)
                        (Type.of[SaratiBridge.DecodeResult[a]], '{ $extraction: MEru[Any] })
                      } else {
                        val extraction =
                          mkExtraction[a](paramInfo, idx, pathParamNames, 'request, 'pathParams, pathStr, "WEBSOCKET")
                        (Type.of[a], '{ $extraction: MEru[Any] })
                      }
                  }
                }

              buildChain(
                typedExtractions,
                base = { (typedArgs, _) =>
                  buildWebSocketBase[H, in, out](
                    typedArgs,
                    handler,
                    isEndpoint,
                    decoderExpr,
                    validatorExpr,
                    encoderExpr,
                    wsConfig,
                    'request
                  )
                },
                Nil
              )
            }
        }

        val schemaExpr = SchemaGen.operationSchema(
          pathStr,
          "GET",
          info,
          paramTypes,
          templateParamNames,
          responseStatus = 101,
          responseBodyType = TypeRepr.of[Nothing],
          isEventStream = false,
          Option.when(operationIdStr.nonEmpty)(operationIdStr),
          Vector.empty,
          Option.when(summaryStr.nonEmpty)(summaryStr),
          Option.when(descriptionStr.nonEmpty)(descriptionStr),
          tagsVec,
          isWebSocket = true
        )

        '{
          $builder.addEntry(
            RouteEntry(
              pathTemplate = ${ Expr(pathStr) },
              method = net.ghoula.eru.http.Method.GET,
              handler = $handlerExpr,
              schema = $schemaExpr
            )
          )
        }

      case _ => report.errorAndAbort("Failed to extract WebSocket message types")
    }
  }

  /** The base case of a WebSocket route's extraction chain: wraps the extracted arguments into the
    * typed session function and performs the upgrade dispatch.
    */
  private def buildWebSocketBase[H: Type, In: Type, Out: Type](using
    q: Quotes
  )(
    typedArgs: List[Expr[Any]],
    handler: Expr[H],
    isEndpoint: Boolean,
    decoderExpr: Expr[net.ghoula.sarati.codec.Decoder[net.ghoula.sarati.ast.json.JsonValue, In]],
    validatorExpr: Expr[net.ghoula.valar.Validator[In]],
    encoderExpr: Expr[net.ghoula.sarati.codec.Encoder[Out, net.ghoula.sarati.ast.json.JsonValue]],
    wsConfig: Expr[net.ghoula.eru.http.server.WebSocketServerConfig],
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]]
  ): Expr[MEru[net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]]] = {
    import q.reflect.*

    val typedCall = Apply(
      Select.unique(handler.asTerm, "apply"),
      typedArgs.map(_.asTerm)
    )
    val endpointExpr: Expr[net.ghoula.melian.WebSocketEndpoint[In, Out]] =
      if isEndpoint then
        // (params) => RequestContext ?=> WebSocketSession => Eru — apply the context function with
        // a fresh RequestContext built from the upgrade request.
        '{
          val ctx: net.ghoula.melian.RequestContext = LiveRequestContext.from($request)
          ${ typedCall.asExprOf[net.ghoula.melian.RequestContext ?=> net.ghoula.melian.WebSocketEndpoint[In, Out]] }(
            using ctx
          )
        }
      else typedCall.asExprOf[net.ghoula.melian.WebSocketEndpoint[In, Out]]

    '{
      val wsHandler: net.ghoula.eru.http.server.WebSocketHandler = {
        (conn: net.ghoula.eru.http.server.ServerWebSocketConnection) =>
          val session = new net.ghoula.melian.WebSocketSession[In, Out](
            WebSocketRoutes.transportFor(conn),
            (message: net.ghoula.eru.http.websocket.WebSocketMessage) =>
              ${ webSocketDecode[In]('message, decoderExpr, validatorExpr) },
            (out: Out) =>
              net.ghoula.sarati.ast.json.formatJson($encoderExpr.encode(out), net.ghoula.sarati.ast.json.compactFormat)
          )
          $endpointExpr(session)
      }
      WebSocketRoutes.dispatch($request, $wsConfig)(wsHandler)
    }
  }

  /** The strict inbound message pipeline: Rumil parse -> Sarati decode -> Valar validate. Any
    * failure becomes a rejection detail (the session closes with 1003).
    */
  private def webSocketDecode[In: Type](using
    q: Quotes
  )(
    message: Expr[net.ghoula.eru.http.websocket.WebSocketMessage],
    decoderExpr: Expr[net.ghoula.sarati.codec.Decoder[net.ghoula.sarati.ast.json.JsonValue, In]],
    validatorExpr: Expr[net.ghoula.valar.Validator[In]]
  ): Expr[Either[String, In]] = {
    import parser.core.Result as RumilResult
    '{
      val text: String = $message match {
        case net.ghoula.eru.http.websocket.WebSocketMessage.Text(value) => value
        case net.ghoula.eru.http.websocket.WebSocketMessage.Binary(bytes) =>
          bytes.asString(net.ghoula.eru.http.Charset.UTF8)
      }
      parsers.json.parseJson(text) match {
        case RumilResult.Success(json, _) =>
          $decoderExpr.decode(json) match {
            case net.ghoula.sarati.Result.Success(value, _) =>
              $validatorExpr.validate(value) match {
                case net.ghoula.valar.ValidationResult.Valid(validated) => Right(validated)
                case net.ghoula.valar.ValidationResult.Invalid(errors) =>
                  Left(
                    "validation failed: " + errors
                      .map(e => s"${e.fieldPath.mkString(".")}: ${e.message}")
                      .mkString("; ")
                  )
              }
            case net.ghoula.sarati.Result.Partial(_, errors, _) =>
              Left("decode failed: " + errors.map(_.toString).mkString("; "))
            case net.ghoula.sarati.Result.Failure(errors, _) =>
              Left("decode failed: " + errors.map(_.toString).mkString("; "))
          }
        case RumilResult.Partial(_, errors, _) =>
          Left("parse failed: " + errors.map(_.toString).mkString("; "))
        case RumilResult.Failure(errors, _) =>
          Left("parse failed: " + errors.map(_.toString).mkString("; "))
      }
    }
  }

  // --- Types ---

  private type ErrType = net.ghoula.melian.RequestError | net.ghoula.eru.http.HttpError
  private type MEru[A] = net.ghoula.eru.Eru[ErrType, A]
  private type HandlerFn = (net.ghoula.eru.http.Request[net.ghoula.eru.http.Body], Map[String, String]) => MEru[
    net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]
  ]

  // --- Extraction builders ---

  private def mkExtraction[A: Type](using
    q: Quotes
  )(
    paramInfo: HandlerIntrospection.ParamInfo,
    idx: Int,
    pathParamNames: Map[Int, String],
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathParams: Expr[Map[String, String]],
    pathStr: String,
    method: String
  ): Expr[MEru[A]] = {
    import q.reflect.*
    paramInfo.kind match {
      case HandlerIntrospection.ParamKind.PathParam =>
        mkPathExtraction[A](pathParamNames(idx), pathParams, pathStr, method)
      case HandlerIntrospection.ParamKind.HeaderParam =>
        mkHeaderExtraction[A](request, pathStr, method)
      case HandlerIntrospection.ParamKind.QueryParam =>
        val name = paramInfo.queryParamName.getOrElse(
          report.errorAndAbort("Query parameter missing name; use Query[\"name\", Type]")
        )
        mkQueryExtraction[A](name, request, pathStr, method)
      case other =>
        // Body markers never reach here: the route macros dispatch them through
        // mkDecodeBodyExtraction, which carries decode warnings.
        report.errorAndAbort(s"Unsupported parameter kind: $other")
    }
  }

  private def mkPathExtraction[A: Type](using
    q: Quotes
  )(
    paramName: String,
    pathParams: Expr[Map[String, String]],
    pathStr: String,
    method: String
  ): Expr[MEru[A]] = {
    val nameExpr = Expr(paramName)
    val fps = summonOrAbort[net.ghoula.melian.extraction.FromPathSegment[A]](
      method,
      pathStr,
      paramName,
      s"FromPathSegment[${Type.show[A]}]"
    )
    '{
      $fps.parse($pathParams.getOrElse($nameExpr, ""), $nameExpr).mapError { err =>
        net.ghoula.melian.RequestError.ExtractionFailed(Vector(err)): ErrType
      }
    }
  }

  private def mkHeaderExtraction[A: Type](using
    q: Quotes
  )(
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String
  ): Expr[MEru[A]] = {
    val fh = summonOrAbort[net.ghoula.melian.extraction.FromHeader[A]](
      method,
      pathStr,
      "header",
      s"FromHeader[${Type.show[A]}]"
    )
    '{
      val headerName = $fh.headerName
      $request.headers.getFirst(headerName) match {
        case Some(hv) =>
          $fh.parse(hv.value).mapError { err =>
            net.ghoula.melian.RequestError.ExtractionFailed(Vector(err)): ErrType
          }
        case None =>
          net.ghoula.eru.Eru.fail(
            net.ghoula.melian.RequestError.ExtractionFailed(
              Vector(
                net.ghoula.melian.ExtractionError(
                  net.ghoula.melian.ExtractionSource.Header,
                  headerName,
                  s"Missing required header: $headerName",
                  None,
                  None
                )
              )
            ): ErrType
          )
      }
    }
  }

  private def mkJsonBodyExtraction[A: Type](using
    q: Quotes
  )(
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String,
    strict: Boolean
  ): Expr[MEru[SaratiBridge.DecodeResult[A]]] = {
    val jsonDec = summonOrAbort[net.ghoula.sarati.codec.Decoder[net.ghoula.sarati.ast.json.JsonValue, A]](
      method,
      pathStr,
      "body",
      s"Decoder[JsonValue, ${Type.show[A]}]"
    )
    val validator = summonOrAbort[net.ghoula.valar.Validator[A]](method, pathStr, "body", s"Validator[${Type.show[A]}]")
    '{
      if $request.body.isEmpty then net.ghoula.eru.Eru.fail(net.ghoula.melian.RequestError.BodyMissing: ErrType)
      else
        SaratiBridge
          .validateContentType($request.headers)
          .mapError { err =>
            net.ghoula.melian.RequestError.DecodeFailed(List(err.message)): ErrType
          }
          .flatMap { _ =>
            SaratiBridge
              .decodeJsonBody[A]($request.body, ${ Expr(strict) })(using $jsonDec)
              .mapError { err =>
                net.ghoula.melian.RequestError.DecodeFailed(List(err.message)): ErrType
              }
              .flatMap { result =>
                $validator.validate(result.value) match {
                  case net.ghoula.valar.ValidationResult.Valid(value) =>
                    net.ghoula.eru.Eru.succeed(SaratiBridge.DecodeResult(value, result.warnings))
                  case net.ghoula.valar.ValidationResult.Invalid(errors) =>
                    net.ghoula.eru.Eru.fail(
                      net.ghoula.melian.RequestError.ValidationFailed(
                        errors.map { e =>
                          net.ghoula.melian.FieldError(e.fieldPath.mkString("."), e.message, e.code)
                        }
                      ): ErrType
                    )
                }
              }
          }
    }
  }

  private def mkCodedBodyExtraction[A: Type](using
    q: Quotes
  )(
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String,
    strict: Boolean
  ): Expr[MEru[SaratiBridge.DecodeResult[A]]] = {
    val coded = summonCodedDecoder[A](method, pathStr)
    val validator = summonOrAbort[net.ghoula.valar.Validator[A]](method, pathStr, "body", s"Validator[${Type.show[A]}]")
    '{
      net.ghoula.melian.router.CodedBody
        .decode[A]($request.headers, $request.body, ${ Expr(strict) })(using $coded, $validator)
    }
  }

  /** Summons a [[net.ghoula.melian.router.CodedDecoder]] for `A`, falling back to the three
    * individual Sarati decoders when no bundle is provided.
    */
  private def summonCodedDecoder[A: Type](using
    q: Quotes
  )(method: String, pathStr: String): Expr[net.ghoula.melian.router.CodedDecoder[A]] = {
    Expr.summon[net.ghoula.melian.router.CodedDecoder[A]] match {
      case Some(coded) => coded
      case None =>
        val jsonDec = summonOrAbort[net.ghoula.sarati.codec.Decoder[net.ghoula.sarati.ast.json.JsonValue, A]](
          method,
          pathStr,
          "body",
          s"Decoder[JsonValue, ${Type.show[A]}]"
        )
        val xmlDec = summonOrAbort[net.ghoula.sarati.codec.Decoder[net.ghoula.sarati.ast.xml.XmlNode, A]](
          method,
          pathStr,
          "body",
          s"Decoder[XmlNode, ${Type.show[A]}]"
        )
        val yamlDec = summonOrAbort[net.ghoula.sarati.codec.Decoder[net.ghoula.sarati.ast.yaml.YamlValue, A]](
          method,
          pathStr,
          "body",
          s"Decoder[YamlValue, ${Type.show[A]}]"
        )
        '{ net.ghoula.melian.router.CodedDecoder.fromDecoders($jsonDec, $xmlDec, $yamlDec) }
    }
  }

  private def mkFormBodyExtraction[A: Type](using
    q: Quotes
  )(
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String
  ): Expr[MEru[SaratiBridge.DecodeResult[A]]] = {
    val formDecoder = summonOrAbort[net.ghoula.melian.extraction.FormDecoder[A]](
      method,
      pathStr,
      "body",
      s"FormDecoder[${Type.show[A]}]"
    )
    val validator = summonOrAbort[net.ghoula.valar.Validator[A]](method, pathStr, "body", s"Validator[${Type.show[A]}]")
    '{
      net.ghoula.melian.router.FormBody.decode[A]($request.body)(using $formDecoder, $validator)
    }
  }

  private def mkQueryExtraction[A: Type](using
    q: Quotes
  )(
    paramName: String,
    request: Expr[net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]],
    pathStr: String,
    method: String
  ): Expr[MEru[A]] = {
    val nameExpr = Expr(paramName)
    val fqp = summonOrAbort[net.ghoula.melian.extraction.FromQueryParam[A]](
      method,
      pathStr,
      paramName,
      s"FromQueryParam[${Type.show[A]}]"
    )

    val onMissing: Expr[MEru[A]] = Type.of[A] match {
      // A missing optional query parameter is absence, not an error. The quote
      // pattern '[Option[t]] binds the element type t, but the macro typer does
      // not propagate the proof A =:= Option[t] into term-level expected types
      // (None: Option[t] fails as `Found: Option[t], Required: A`). The value
      // cast inside the emitted quote is the irreducible boundary, the same
      // category as Strongbow's ExprCompiler casts after a type-driven match.
      case '[Option[t]] =>
        '{ net.ghoula.eru.Eru.succeed(None.asInstanceOf[A]) } // scalafix:ok DisableSyntax.asInstanceOf
      case _ =>
        '{
          net.ghoula.eru.Eru.fail(
            net.ghoula.melian.RequestError.ExtractionFailed(
              Vector(
                net.ghoula.melian.ExtractionError(
                  net.ghoula.melian.ExtractionSource.Query,
                  $nameExpr,
                  s"Missing required query parameter: ${$nameExpr}",
                  None,
                  None
                )
              )
            ): ErrType
          )
        }
    }

    '{
      val queryParams = $request.uri.query.fold(Map.empty[String, String])(QueryStringParser.parse)
      queryParams.get($nameExpr) match {
        case Some(raw) =>
          $fqp.parse(raw, $nameExpr).mapError { err =>
            net.ghoula.melian.RequestError.ExtractionFailed(Vector(err)): ErrType
          }
        case None => $onMissing
      }
    }
  }

  // --- Response encoding ---

  /** Raw SSE: the stream's element type is `ServerSentEvent`, so events pass through verbatim. */
  private def encodeRawEventStream[R](
    response: R,
    warnings: List[String]
  ): net.ghoula.eru.Eru[ErrType, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    val sse: Eru[ErrType, Response[Body]] = (response: Any) match {
      case es: net.ghoula.melian.EventStream[ServerSentEvent @unchecked] =>
        Response.sse(SseBridge.raw(es.source)).mapError { err =>
          HttpError.InvalidResponse(InvalidResponse(err.toString, "SSE headers")): ErrType
        }
      case other =>
        Eru.fail(HttpError.ProtocolError(s"Expected EventStream, got: ${other.getClass.getName}", "response"): ErrType)
    }
    sse.flatMap(attachWarnings(_, warnings))
  }

  /** Typed SSE: each event is JSON-encoded and emitted as a `ServerSentEvent.data`. */
  private def encodeTypedEventStream[R, B](
    response: R,
    eventEncoder: net.ghoula.sarati.codec.Encoder[B, net.ghoula.sarati.ast.json.JsonValue],
    warnings: List[String]
  ): net.ghoula.eru.Eru[ErrType, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    val sse: Eru[ErrType, Response[Body]] = (response: Any) match {
      case es: net.ghoula.melian.EventStream[B @unchecked] =>
        Response.sse(SseBridge.typed(es.source, eventEncoder)).mapError { err =>
          HttpError.InvalidResponse(InvalidResponse(err.toString, "SSE headers")): ErrType
        }
      case other =>
        Eru.fail(HttpError.ProtocolError(s"Expected EventStream, got: ${other.getClass.getName}", "response"): ErrType)
    }
    sse.flatMap(attachWarnings(_, warnings))
  }

  /** Encodes the handler's response wrapper into an eru-http Response, then attaches the
    * `X-Melian-Warnings` header when the Girdle produced decode warnings.
    */
  private def encodeResponse[R, B](
    response: R,
    encoder: Option[net.ghoula.eru.http.BodyEncoder[B]],
    negotiator: Option[ResponseNegotiator[B]],
    hoistedStatus: Option[net.ghoula.eru.http.StatusCode],
    pathTemplate: String,
    pathParams: Map[String, String],
    warnings: List[String],
    request: net.ghoula.eru.http.Request[net.ghoula.eru.http.Body]
  ): net.ghoula.eru.Eru[ErrType, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*

    // The Accept header is only read on negotiating routes.
    val acceptHeader: Option[String] =
      if negotiator.isDefined then request.headers.getFirst(HeaderNames.Accept).map(_.value) else None

    // Encodes a wrapper body: through the negotiator when the route negotiates, otherwise through
    // the BodyEncoder given. The negotiated media type becomes the Content-Type header, mirroring
    // withEncodedBody's behavior on the plain path.
    def encodeBody(body: B): Eru[ErrType, Body] = negotiator match {
      case Some(n) => n.encode(body, acceptHeader)
      case None =>
        encoder.get.encode(body).mapError { err =>
          HttpError.BodyEncodeError(err): ErrType
        }
    }

    def withContentType(response: Response[Body], body: Body): Eru[ErrType, Response[Body]] =
      body.mediaType match {
        case Some(mt) =>
          response.withContentType(mt).mapError {
            case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
              HttpError.InvalidResponse(InvalidResponse(err.toString, "Content-Type header")): ErrType
          }
        case None => Eru.succeed(response)
      }

    val encoded: Eru[ErrType, Response[Body]] = (response: Any) match {
      case ok: net.ghoula.melian.Ok[B @unchecked] =>
        negotiator match {
          case Some(_) =>
            encodeBody(ok.body).flatMap(body => withContentType(Response(StatusCode.Ok, Headers.empty, body), body))
          case None =>
            Response.ok(Body.Empty).withEncodedBody(ok.body)(using encoder.get).mapError {
              case err: EncodeError => HttpError.BodyEncodeError(err): ErrType
              case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
                HttpError.InvalidResponse(InvalidResponse(err.toString, "Content-Type header")): ErrType
            }
        }
      case _: net.ghoula.melian.NoContent.type =>
        Eru.succeed(Response.noContent)
      case created: net.ghoula.melian.Created[B @unchecked] =>
        val locationPath = created.location match {
          case Some(uri) => uri.path
          case None => deriveLocation(pathTemplate, pathParams)
        }
        encodeBody(created.body).flatMap { body =>
          Uri
            .parse(locationPath)
            .mapError { err =>
              HttpError.InvalidUri(err): ErrType
            }
            .flatMap { uri =>
              withContentType(Response(StatusCode.Created, Headers.empty, body), body)
                .flatMap(_.withLocation(uri))
                .mapError {
                  case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
                    HttpError.InvalidResponse(InvalidResponse(err.toString, "Location header")): ErrType
                  case err: ErrType => err
                }
            }
        }
      case accepted: net.ghoula.melian.Accepted[B @unchecked] =>
        negotiator match {
          case Some(_) =>
            encodeBody(accepted.body).flatMap(body =>
              withContentType(Response(StatusCode.Accepted, Headers.empty, body), body)
            )
          case None =>
            Response(StatusCode.Accepted, Headers.empty, Body.Empty)
              .withEncodedBody(accepted.body)(using encoder.get)
              .mapError {
                case err: EncodeError => HttpError.BodyEncodeError(err): ErrType
                case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
                  HttpError.InvalidResponse(InvalidResponse(err.toString, "Content-Type header")): ErrType
              }
        }
      case unauth: net.ghoula.melian.Unauthorized[B @unchecked] =>
        encodeBody(unauth.body).flatMap { body =>
          withContentType(Response(StatusCode.Unauthorized, Headers.empty, body), body)
            .flatMap(_.setHeader(HeaderNames.WWWAuthenticate, unauth.challenge))
            .mapError {
              case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
                HttpError.InvalidResponse(InvalidResponse(err.toString, "WWW-Authenticate header")): ErrType
              case err: ErrType => err
            }
        }
      case tooMany: net.ghoula.melian.TooManyRequests[B @unchecked] =>
        encodeBody(tooMany.body).flatMap { body =>
          withContentType(Response(StatusCode.TooManyRequests, Headers.empty, body), body)
            .flatMap(_.setHeader(HeaderNames.RetryAfter, retryAfterSeconds(tooMany.retryAfter)))
            .mapError {
              case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
                HttpError.InvalidResponse(InvalidResponse(err.toString, "Retry-After header")): ErrType
              case err: ErrType => err
            }
        }
      case custom: net.ghoula.melian.Status[Int @unchecked, B @unchecked] =>
        // The macro validated the phantom code at compile time and resolved the StatusCode once
        // at route construction; Some is guaranteed exactly for Status routes.
        val status: StatusCode = hoistedStatus.get
        encodeBody(custom.body).flatMap(body => withContentType(Response(status, Headers.empty, body), body))
      case seeOther: net.ghoula.melian.SeeOther =>
        Response(StatusCode.SeeOther, Headers.empty, Body.Empty)
          .withLocation(seeOther.location)
          .mapError { case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
            HttpError.InvalidResponse(InvalidResponse(err.toString, "Location header")): ErrType
          }
      case _: net.ghoula.melian.NotModified.type =>
        Eru.succeed(Response(StatusCode.NotModified, Headers.empty, Body.Empty))
      case _ =>
        Eru.succeed(Response(StatusCode.Ok, Headers.empty, Body.text(response.toString)))
    }

    encoded.flatMap(attachWarnings(_, warnings))
  }

  /** Attaches the warnings header; absent when there are no warnings (the clean common path). */
  private def attachWarnings(
    response: net.ghoula.eru.http.Response[net.ghoula.eru.http.Body],
    warnings: List[String]
  ): net.ghoula.eru.Eru[ErrType, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    if warnings.isEmpty then Eru.succeed(response)
    else {
      val count = warnings.size
      val value = s"$count decode warning${if count == 1 then "" else "s"}"
      response
        .setHeader(MelianHeaders.Warnings, value)
        .mapError { case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
          HttpError.InvalidResponse(InvalidResponse(err.toString, s"${MelianHeaders.Warnings} header")): ErrType
        }
    }
  }

  /** Retry-After carries delta-seconds; non-finite durations clamp to zero. */
  private def retryAfterSeconds(retryAfter: scala.concurrent.duration.Duration): String = {
    val seconds = if retryAfter.isFinite then retryAfter.toSeconds else 0L
    seconds.max(0L).toString
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

  private def verifyPathParams(using
    q: Quotes
  )(
    method: String,
    pathStr: String,
    template: PathTemplate.ParsedPath,
    info: HandlerIntrospection.HandlerInfo
  ): Unit = {
    import q.reflect.*
    if template.paramNames.size != info.pathParams.size then
      report.errorAndAbort(
        MacroErrors.formatPathMismatch(method, pathStr, template.paramNames, info.pathParams.map(_.innerTypeShow))
      )
  }

  private def verifyMethodConstraints(using
    q: Quotes
  )(
    method: String,
    pathStr: String,
    info: HandlerIntrospection.HandlerInfo
  ): Unit = {
    import q.reflect.*
    val allowsBody = method match {
      case "GET" | "HEAD" | "DELETE" | "TRACE" => false
      case _ => true
    }
    val requiresBody = method match {
      case "POST" | "PUT" | "PATCH" | "QUERY" => true
      case _ => false
    }
    if !allowsBody && info.hasBody then
      report.errorAndAbort(
        MacroErrors.formatMethodConstraint(
          method,
          pathStr,
          s"$method does not allow a request body, but handler declares a body parameter.",
          "Use POST or PUT for endpoints that accept a request body."
        )
      )
    if requiresBody && !info.hasBody then
      report.errorAndAbort(
        MacroErrors.formatMethodConstraint(
          method,
          pathStr,
          s"$method requires a request body, but handler declares no body parameter (Json[A], Coded[A], or Form[A]).",
          "Add a Json[A] parameter for the request body."
        )
      )
  }

  // --- Summon helper ---

  private def summonOrAbort[T: Type](using
    q: Quotes
  )(
    method: String,
    pathStr: String,
    paramName: String,
    typeclassName: String
  ): Expr[T] =
    Expr.summon[T].getOrElse {
      import q.reflect.*
      report.errorAndAbort(
        MacroErrors.formatMissingInstances(
          method,
          pathStr,
          "handler",
          List(MacroErrors.MissingInstance(paramName, Type.show[T], typeclassName, s"given $typeclassName = ..."))
        )
      )
    }
}
