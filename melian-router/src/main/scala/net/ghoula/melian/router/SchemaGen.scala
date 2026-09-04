package net.ghoula.melian.router

import scala.quoted.*

import net.ghoula.melian.schema.*

/** Compile-time schema generation from TypeRepr.
  *
  * Traverses types via Mirror.ProductOf to produce TypeSchema descriptors. All work happens at
  * compile time; the output is quoted expressions that construct schema values at runtime.
  */
object SchemaGen {

  def typeSchema[A: Type](using q: Quotes): Expr[TypeSchema] = {
    import q.reflect.*
    schemaFor(TypeRepr.of[A], Set.empty)
  }

  private def schemaFor(using q: Quotes)(tpe: q.reflect.TypeRepr, visited: Set[String]): Expr[TypeSchema] = {
    import q.reflect.*

    val dealiased = tpe.dealias
    val fullName = dealiased.typeSymbol.fullName

    fullName match {
      case "scala.Predef$.String" | "java.lang.String" => '{ TypeSchema.StringSchema }
      case "scala.Int" => '{ TypeSchema.IntSchema }
      case "scala.Long" => '{ TypeSchema.LongSchema }
      case "scala.Double" => '{ TypeSchema.DoubleSchema }
      case "scala.Boolean" => '{ TypeSchema.BooleanSchema }
      case "java.util.UUID" => '{ TypeSchema.UuidSchema }
      case "scala.Unit" | "scala.Nothing" => '{ TypeSchema.UnitSchema }
      case _ if visited.contains(fullName) =>
        val name = Expr(dealiased.typeSymbol.name)
        '{ TypeSchema.Ref($name) }
      case _ =>
        dealiased match {
          case AppliedType(base, List(inner)) if base.typeSymbol.fullName == "scala.Option" =>
            val innerSchema = schemaFor(inner, visited)
            '{ TypeSchema.OptionalSchema($innerSchema) }

          case AppliedType(base, List(inner))
              if base.typeSymbol.fullName == "scala.collection.immutable.List"
                || base.typeSymbol.fullName == "scala.collection.immutable.Vector"
                || base.typeSymbol.fullName == "scala.collection.immutable.Seq" =>
            val itemSchema = schemaFor(inner, visited)
            '{ TypeSchema.ArraySchema($itemSchema) }

          case _ if dealiased.typeSymbol.flags.is(Flags.Enum) && !dealiased.typeSymbol.flags.is(Flags.Case) =>
            val enumName = dealiased.typeSymbol.name
            val children = dealiased.typeSymbol.children.map(c => Expr(c.name))
            val nameExpr = Expr(enumName)
            '{ TypeSchema.EnumSchema($nameExpr, Vector(${ Varargs(children) }*)) }

          case _ if dealiased.typeSymbol.flags.is(Flags.Case) && dealiased.typeSymbol.isClassDef =>
            objectSchema(dealiased, visited)

          case _ =>
            val name = Expr(dealiased.typeSymbol.name)
            '{ TypeSchema.Ref($name) }
        }
    }
  }

  private def objectSchema(using q: Quotes)(tpe: q.reflect.TypeRepr, visited: Set[String]): Expr[TypeSchema] = {
    import q.reflect.*

    val sym = tpe.typeSymbol
    val name = Expr(sym.name)
    val caseFields = sym.caseFields
    val nextVisited = visited + sym.fullName

    val typeDescExpr = extractAnnotation(sym.annotations, "description")

    val fieldExprs: List[Expr[FieldSchema]] = caseFields.map { field =>
      val fieldName = Expr(field.name)
      val fieldType = tpe.memberType(field)
      val isOptional = fieldType.dealias match {
        case AppliedType(base, _) => base.typeSymbol.fullName == "scala.Option"
        case _ => false
      }
      val requiredExpr = Expr(!isOptional)
      val fieldSchema = schemaFor(fieldType, nextVisited)
      val descExpr = extractAnnotation(field.annotations, "description")
      val exampleExpr = extractAnnotation(field.annotations, "example")
      '{ FieldSchema($fieldName, $fieldSchema, $requiredExpr, $descExpr, $exampleExpr) }
    }

    val fieldsExpr = '{ Vector(${ Varargs(fieldExprs) }*) }
    '{ TypeSchema.ObjectSchema($name, $fieldsExpr, $typeDescExpr) }
  }

  def operationSchema(using
    q: Quotes
  )(
    pathStr: String,
    method: String,
    info: HandlerIntrospection.HandlerInfo,
    paramTypes: List[q.reflect.TypeRepr],
    templateParamNames: List[String],
    responseStatus: Int,
    responseBodyType: q.reflect.TypeRepr,
    isEventStream: Boolean,
    operationId: Option[String],
    responseMediaTypes: Vector[String],
    summary: Option[String],
    description: Option[String],
    tags: Vector[String],
    isWebSocket: Boolean = false
  ): Expr[OperationSchema] = {
    import q.reflect.*

    val pathExpr = Expr(pathStr)
    val methodExpr = Expr(method)
    val statusExpr = Expr(responseStatus)
    val isEventStreamExpr = Expr(isEventStream)
    val isWebSocketExpr = Expr(isWebSocket)
    val operationIdExpr = operationId match {
      case Some(id) => '{ Some(${ Expr(id) }) }
      case None => '{ None }
    }
    val responseMediaTypesExpr =
      '{ Vector(${ Varargs(responseMediaTypes.map(Expr(_))) }*) }
    val summaryExpr = summary match {
      case Some(s) => '{ Some(${ Expr(s) }) }
      case None => '{ None }
    }
    val descriptionExpr = description match {
      case Some(d) => '{ Some(${ Expr(d) }) }
      case None => '{ None }
    }
    val tagsExpr = '{ Vector(${ Varargs(tags.map(Expr(_))) }*) }

    // Path parameters draw their names from templateParamNames in order; pre-zip the path-param
    // positions with their names so the per-param mapping needs no counter.
    val pathParamName: Map[Int, String] = {
      val pathParamIndices = paramTypes.zip(info.params).zipWithIndex.collect {
        case ((_, p), i) if p.kind == HandlerIntrospection.ParamKind.PathParam => i
      }
      pathParamIndices.zip(templateParamNames).toMap
    }

    val paramExprs: List[Expr[ParameterSchema]] =
      paramTypes.zip(info.params).zipWithIndex.flatMap { case ((tpe, paramInfo), idx) =>
        val innerType = HandlerIntrospection.innerTypeOf(tpe)

        paramInfo.kind match {
          case HandlerIntrospection.ParamKind.PathParam =>
            val name = Expr(pathParamName.getOrElse(idx, "unknown"))
            val schema = schemaFor(innerType, Set.empty)
            Some('{ ParameterSchema($name, ParameterLocation.Path, $schema, required = true) })

          case HandlerIntrospection.ParamKind.QueryParam =>
            val name = Expr(paramInfo.queryParamName.getOrElse("unknown"))
            val schema = schemaFor(innerType, Set.empty)
            val isOptional = innerType.dealias match {
              case AppliedType(base, _) => base.typeSymbol.fullName == "scala.Option"
              case _ => false
            }
            val requiredExpr = Expr(!isOptional)
            Some('{ ParameterSchema($name, ParameterLocation.Query, $schema, $requiredExpr) })

          case HandlerIntrospection.ParamKind.HeaderParam =>
            val schema = schemaFor(innerType, Set.empty)
            val name = Expr(innerType.typeSymbol.name)
            Some('{ ParameterSchema($name, ParameterLocation.Header, $schema, required = true) })

          case _ => None
        }
      }

    val paramsExpr = '{ Vector(${ Varargs(paramExprs) }*) }

    val bodyParam: Option[(q.reflect.TypeRepr, HandlerIntrospection.ParamKind)] =
      paramTypes.zip(info.params).collectFirst {
        case (tpe, paramInfo)
            if paramInfo.kind == HandlerIntrospection.ParamKind.JsonBody
              || paramInfo.kind == HandlerIntrospection.ParamKind.CodedBody
              || paramInfo.kind == HandlerIntrospection.ParamKind.FormBody =>
          (tpe, paramInfo.kind)
      }

    val requestBodyExpr: Expr[Option[TypeSchema]] = bodyParam match {
      case Some((tpe, _)) =>
        val innerType = HandlerIntrospection.innerTypeOf(tpe)
        val schema = schemaFor(innerType, Set.empty)
        '{ Some($schema) }
      case None => '{ None }
    }

    val requestMediaTypesExpr: Expr[Vector[String]] = bodyParam match {
      case Some((_, HandlerIntrospection.ParamKind.CodedBody)) =>
        '{ Vector("application/json", "application/xml", "application/yaml") }
      case Some((_, HandlerIntrospection.ParamKind.FormBody)) =>
        '{ Vector("application/x-www-form-urlencoded") }
      case _ => '{ Vector("application/json") }
    }

    val responseHeadersExpr: Expr[Vector[ResponseHeaderSchema]] = info.response.wrapperName match {
      case "Created" =>
        '{ Vector(ResponseHeaderSchema("Location", Some("URI of the created resource"), required = true)) }
      case "SeeOther" =>
        '{ Vector(ResponseHeaderSchema("Location", Some("URI to redirect to"), required = true)) }
      case "Unauthorized" =>
        '{ Vector(ResponseHeaderSchema("WWW-Authenticate", Some("Authentication challenge"), required = true)) }
      case "TooManyRequests" =>
        '{ Vector(ResponseHeaderSchema("Retry-After", Some("Seconds to wait before retrying"), required = true)) }
      case _ => '{ Vector.empty[ResponseHeaderSchema] }
    }

    val responseBodyExpr: Expr[Option[TypeSchema]] =
      if isEventStream then '{ None }
      else {
        responseBodyType.dealias match {
          case tpe if tpe.typeSymbol.fullName == "scala.Nothing" => '{ None }
          case tpe =>
            val schema = schemaFor(tpe, Set.empty)
            '{ Some($schema) }
        }
      }

    '{
      OperationSchema(
        pathTemplate = $pathExpr,
        method = $methodExpr,
        parameters = $paramsExpr,
        requestBody = $requestBodyExpr,
        requestMediaTypes = $requestMediaTypesExpr,
        responseStatus = $statusExpr,
        responseBody = $responseBodyExpr,
        responseHeaders = $responseHeadersExpr,
        isEventStream = $isEventStreamExpr,
        isWebSocket = $isWebSocketExpr,
        operationId = $operationIdExpr,
        responseMediaTypes = $responseMediaTypesExpr,
        summary = $summaryExpr,
        description = $descriptionExpr,
        tags = $tagsExpr
      )
    }
  }

  private def extractAnnotation(using
    q: Quotes
  )(
    annotations: List[q.reflect.Term],
    annotationName: String
  ): Expr[Option[String]] = {
    import q.reflect.*
    annotations.collectFirst {
      case Apply(Select(New(tpt), _), List(Literal(StringConstant(value))))
          if tpt.tpe.typeSymbol.name == annotationName =>
        value
    } match {
      case Some(value) =>
        val v = Expr(value)
        '{ Some($v) }
      case None => '{ None }
    }
  }

  /** The status code for a route's response wrapper, resolved at compile time.
    *
    * `Status[Code, A]` reads its code from the literal type argument and is validated here: the
    * code must be a valid status (100-599) that allows a body and requires no headers -- a status
    * with mandatory headers has a dedicated wrapper or belongs to the router.
    */
  def responseStatusCode(using q: Quotes)(wrapperName: String, responseTypeRepr: q.reflect.TypeRepr): Int = {
    import q.reflect.*
    import net.ghoula.eru.http.StatusCode
    wrapperName match {
      case "Ok" => 200
      case "Created" => 201
      case "Accepted" => 202
      case "NoContent" => 204
      case "SeeOther" => 303
      case "NotModified" => 304
      case "EventStream" => 200
      case "Unauthorized" => 401
      case "TooManyRequests" => 429
      case "Status" =>
        val code = responseTypeRepr.dealias match {
          case AppliedType(_, args) if args.nonEmpty =>
            args.head.dealias match {
              case ConstantType(IntConstant(c)) => c
              case other =>
                report.errorAndAbort(
                  s"Status[Code, A] requires a literal Int status code, got: ${other.show}. Use e.g. Status[206, A]."
                )
            }
          case other =>
            report.errorAndAbort(s"Cannot read Status[Code, A] status code from: ${other.show}")
        }
        if code < 100 || code >= 600 then
          report.errorAndAbort(s"Status[$code, A]: status codes must be between 100 and 599.")
        val statusCode = StatusCode(code).unsafeRunSync()
        if !statusCode.allowsResponseBody then
          report.errorAndAbort(
            s"Status[$code, A]: status $code does not allow a response body. Use NoContent or NotModified."
          )
        if statusCode.requiredHeaders.nonEmpty then
          report.errorAndAbort(
            s"Status[$code, A]: status $code requires the ${statusCode.requiredHeaders.mkString(", ")} header(s), " +
              "which the Status wrapper cannot carry. Use a dedicated response wrapper for that status."
          )
        code
      case other =>
        report.errorAndAbort(
          s"Unknown response wrapper type: $other. Handlers must return Ok[A], Created[A], Accepted[A], NoContent, " +
            "SeeOther, NotModified, EventStream[A], Unauthorized[A], TooManyRequests[A], or Status[Code, A]."
        )
    }
  }
}
