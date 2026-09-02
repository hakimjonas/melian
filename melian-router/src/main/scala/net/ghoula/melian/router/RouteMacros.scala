package net.ghoula.melian.router

import scala.quoted.*

object RouteMacros {

  def addRoute[H: Type](
    builder: Expr[RouterBuilder],
    path: Expr[String],
    handler: Expr[H],
    methodStr: Expr[String],
    summary: Expr[String],
    description: Expr[String],
    tags: Expr[String]
  )(using q: Quotes): Expr[RouterBuilder] = {
    import q.reflect.*

    val pathStr = path.valueOrAbort
    val method = methodStr.valueOrAbort
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
      case AppliedType(_, List(b)) => b
      case _ => TypeRepr.of[Nothing]
    }
    val returnsEndpoint = info.response.isEndpoint

    val isEventStream = info.response.wrapperName == "EventStream"

    // Body-less response wrappers (NoContent, SeeOther, NotModified) carry no body type, so the
    // macro must not summon a BodyEncoder for them: there is nothing to encode.
    val hasResponseBody = info.response.bodyTypeRepr.isDefined

    (errorTypeRepr.asType, responseTypeRepr.asType, bodyTypeRepr.asType) match {
      case ('[e], '[r], '[b]) =>
        val bodyEncoderExpr: Option[Expr[net.ghoula.eru.http.BodyEncoder[b]]] =
          if isEventStream || !hasResponseBody then None
          else
            Some(
              summonOrAbort[net.ghoula.eru.http.BodyEncoder[b]](
                method,
                pathStr,
                "response",
                s"BodyEncoder[${Type.show[b]}]"
              )
            )
        val errorRendererExpr =
          summonOrAbort[net.ghoula.melian.ErrorRenderer[e]](method, pathStr, "error", s"ErrorRenderer[${Type.show[e]}]")

        val paramTypes = TypeRepr.of[H].dealias match {
          case AppliedType(_, args) => args.init
          case _ => report.errorAndAbort("Cannot decompose handler type")
        }
        val templateParamNames = template.paramNames

        val handlerExpr: Expr[HandlerFn] = '{
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
                val innerType = tpe match {
                  case AppliedType(_, args) if args.nonEmpty => args.last
                  case other => other
                }
                innerType.asType match {
                  case '[a] =>
                    if paramInfo.kind == HandlerIntrospection.ParamKind.JsonBody then {
                      val extraction = mkJsonBodyExtraction[a]('request, pathStr, method)
                      '{
                        $extraction.flatMap { (result: SaratiBridge.DecodeResult[a]) =>
                          ${
                            invokeHandlerAndEncode[H, e, r, b](
                              List('{ result.value }),
                              '{ result.warnings },
                              handler,
                              bodyEncoderExpr,
                              errorRendererExpr,
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
                              bodyEncoderExpr,
                              errorRendererExpr,
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
                    val innerType = tpe match {
                      case AppliedType(_, args) if args.nonEmpty => args.last
                      case other => other
                    }
                    innerType.asType match {
                      case '[a] =>
                        if paramInfo.kind == HandlerIntrospection.ParamKind.JsonBody then {
                          val extraction = mkJsonBodyExtraction[a]('request, pathStr, method)
                          (Type.of[SaratiBridge.DecodeResult[a]], '{ $extraction: MEru[Any] })
                        } else {
                          val extraction =
                            mkExtraction[a](paramInfo, idx, pathParamNames, 'request, 'pathParams, pathStr, method)
                          (Type.of[a], '{ $extraction: MEru[Any] })
                        }
                    }
                  }

                buildChain[H, e, r, b](
                  typedExtractions,
                  handler,
                  bodyEncoderExpr,
                  errorRendererExpr,
                  returnsEndpoint,
                  isEventStream,
                  'request,
                  'pathParams,
                  pathStr,
                  Nil
                )
              }
            }
        }

        val responseStatus = SchemaGen.responseStatusCode(info.response.wrapperName)
        val schemaExpr = SchemaGen.operationSchema(
          pathStr,
          method,
          info,
          paramTypes,
          templateParamNames,
          responseStatus,
          bodyTypeRepr,
          isEventStream,
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

  /** Recursively builds e.attempt.flatMap { r => ... } chain.
    *
    * Each extraction is attempted (Eru[Nothing, Result[...]]). The flatMap never fails since
    * attempted effects always succeed. At the base case, all Result bindings are in scope and we
    * check for errors / call the handler.
    */
  private def buildChain[H: Type, E: Type, R: Type, B: Type](using
    q: Quotes
  )(
    remaining: List[(Type[?], Expr[MEru[Any]])],
    handler: Expr[H],
    bodyEncoder: Option[Expr[net.ghoula.eru.http.BodyEncoder[B]]],
    errorRenderer: Expr[net.ghoula.melian.ErrorRenderer[E]],
    returnsEndpoint: Boolean,
    isEventStream: Boolean,
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
            ${
              invokeHandlerAndEncode[H, E, R, B](
                typedArgs,
                warningsExpr,
                handler,
                bodyEncoder,
                errorRenderer,
                returnsEndpoint,
                isEventStream,
                request,
                pathParams,
                pathStr
              )
            }
          }
        }

      case (tpe, extractionExpr) :: tail =>
        '{
          $extractionExpr.attempt.flatMap { (result: net.ghoula.eru.Result[ErrType, Any]) =>
            ${
              buildChain[H, E, R, B](
                tail,
                handler,
                bodyEncoder,
                errorRenderer,
                returnsEndpoint,
                isEventStream,
                request,
                pathParams,
                pathStr,
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
    bodyEncoder: Option[Expr[net.ghoula.eru.http.BodyEncoder[B]]],
    errorRenderer: Expr[net.ghoula.melian.ErrorRenderer[E]],
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
            encodeEventStream(response)
          case net.ghoula.eru.Result.Failure(domainError) =>
            $errorRenderer.render(domainError)
        }
      }
    else {
      val enc: Expr[Option[net.ghoula.eru.http.BodyEncoder[B]]] = bodyEncoder match {
        case Some(e) => '{ Some($e) }
        case None => '{ None }
      }
      '{
        $eruExpr.attempt.flatMap {
          case net.ghoula.eru.Result.Success(response) =>
            encodeResponse[R, B](response, $enc, $pathTemplateExpr, $pathParams)
          case net.ghoula.eru.Result.Failure(domainError) =>
            $errorRenderer.render(domainError)
        }
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
      case HandlerIntrospection.ParamKind.CodedBody =>
        mkCodedBodyExtraction[A](request, pathStr, method)
      case HandlerIntrospection.ParamKind.FormBody =>
        mkFormBodyExtraction[A](request, pathStr, method)
      case other =>
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
    method: String
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
              .decodeJsonBody[A]($request.body)(using $jsonDec)
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
    method: String
  ): Expr[MEru[A]] = {
    val coded = summonCodedDecoder[A](method, pathStr)
    val validator = summonOrAbort[net.ghoula.valar.Validator[A]](method, pathStr, "body", s"Validator[${Type.show[A]}]")
    '{
      net.ghoula.melian.router.CodedBody.decode[A]($request.headers, $request.body)(using $coded, $validator)
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
  ): Expr[MEru[A]] = {
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

  private def encodeEventStream[R](
    response: R
  ): net.ghoula.eru.Eru[ErrType, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    (response: Any) match {
      case es: net.ghoula.melian.EventStream[ChunkStream @unchecked] =>
        Response.sse(es.source).mapError { err =>
          HttpError.InvalidResponse(InvalidResponse(err.toString, "SSE headers")): ErrType
        }
      case other =>
        Eru.fail(HttpError.ProtocolError(s"Expected EventStream, got: ${other.getClass.getName}", "response"): ErrType)
    }
  }

  private def encodeResponse[R, B](
    response: R,
    encoder: Option[net.ghoula.eru.http.BodyEncoder[B]],
    pathTemplate: String,
    pathParams: Map[String, String]
  ): net.ghoula.eru.Eru[ErrType, net.ghoula.eru.http.Response[net.ghoula.eru.http.Body]] = {
    import net.ghoula.eru.Eru
    import net.ghoula.eru.http.*
    (response: Any) match {
      case ok: net.ghoula.melian.Ok[B @unchecked] =>
        Response.ok(Body.Empty).withEncodedBody(ok.body)(using encoder.get).mapError {
          case err: EncodeError => HttpError.BodyEncodeError(err): ErrType
          case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
            HttpError.InvalidResponse(InvalidResponse(err.toString, "Content-Type header")): ErrType
        }
      case _: net.ghoula.melian.NoContent.type =>
        Eru.succeed(Response.noContent)
      case created: net.ghoula.melian.Created[B @unchecked] =>
        val locationPath = created.location match {
          case Some(uri) => uri.path
          case None => deriveLocation(pathTemplate, pathParams)
        }
        encoder.get
          .encode(created.body)
          .mapError { err =>
            HttpError.BodyEncodeError(err): ErrType
          }
          .flatMap { body =>
            Uri
              .parse(locationPath)
              .mapError { err =>
                HttpError.InvalidUri(err): ErrType
              }
              .flatMap { uri =>
                Response.created(uri, body).mapError { err =>
                  HttpError.InvalidResponse(InvalidResponse(err.toString, "Location header")): ErrType
                }
              }
          }
      case accepted: net.ghoula.melian.Accepted[B @unchecked] =>
        Response(StatusCode.Accepted, Headers.empty, Body.Empty)
          .withEncodedBody(accepted.body)(using encoder.get)
          .mapError {
            case err: EncodeError => HttpError.BodyEncodeError(err): ErrType
            case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
              HttpError.InvalidResponse(InvalidResponse(err.toString, "Content-Type header")): ErrType
          }
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
