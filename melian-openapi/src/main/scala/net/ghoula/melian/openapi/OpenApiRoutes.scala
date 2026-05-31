package net.ghoula.melian.openapi

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.sarati.ast.json.{JsonValue, compactFormat, formatJson, prettyFormat}

/** Provides HTTP handlers for serving the OpenAPI spec and a Swagger UI page.
  *
  * These are plain eru-http handlers, not Melian macro routes. Compose them with the router handler
  * via middleware or a fallback chain.
  */
object OpenApiRoutes {

  /** Handler that serves the OpenAPI spec as JSON at the given path. */
  def specHandler(
    spec: JsonValue,
    path: String = "/openapi.json",
    pretty: Boolean = false
  ): Request[Body] => Eru[HttpError, Option[Response[Body]]] = { (request: Request[Body]) =>
    if request.method.value == "GET" && request.uri.path == path then {
      val format = if pretty then prettyFormat else compactFormat
      val json = formatJson(spec, format)
      Eru.succeed(Some(Response(StatusCode.Ok, Headers.empty, Body.text(json, MediaType.applicationJson))))
    } else {
      Eru.succeed(None)
    }
  }

  /** Handler that serves a Swagger UI HTML page loading from CDN. */
  def swaggerUiHandler(
    specPath: String = "/openapi.json",
    uiPath: String = "/docs"
  ): Request[Body] => Eru[HttpError, Option[Response[Body]]] = { (request: Request[Body]) =>
    if request.method.value == "GET" && request.uri.path == uiPath then {
      val html = swaggerHtml(specPath)
      Eru.succeed(Some(Response(StatusCode.Ok, Headers.empty, Body.text(html, MediaType.textHtml))))
    } else {
      Eru.succeed(None)
    }
  }

  /** Combines spec + Swagger UI handlers as middleware wrapping the main router handler. */
  def middleware(
    spec: JsonValue,
    specPath: String = "/openapi.json",
    uiPath: String = "/docs"
  ): (Request[Body] => Eru[HttpError, Response[Body]]) => (Request[Body] => Eru[HttpError, Response[Body]]) = {
    val specH = specHandler(spec, specPath)
    val uiH = swaggerUiHandler(specPath, uiPath)

    (inner: Request[Body] => Eru[HttpError, Response[Body]]) => { (request: Request[Body]) =>
      specH(request).flatMap {
        case Some(response) => Eru.succeed(response)
        case None =>
          uiH(request).flatMap {
            case Some(response) => Eru.succeed(response)
            case None => inner(request)
          }
      }
    }
  }

  private def swaggerHtml(specPath: String): String =
    s"""<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |  <meta charset="UTF-8">
      |  <title>API Documentation</title>
      |  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
      |</head>
      |<body>
      |  <div id="swagger-ui"></div>
      |  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
      |  <script>
      |    SwaggerUIBundle({ url: "$specPath", dom_id: '#swagger-ui' });
      |  </script>
      |</body>
      |</html>""".stripMargin
}
