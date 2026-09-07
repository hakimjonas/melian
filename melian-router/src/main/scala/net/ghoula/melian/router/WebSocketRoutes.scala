package net.ghoula.melian.router

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.eru.http.server.{ServerWebSocketConnection, WebSocketHandler, WebSocketServer, WebSocketServerConfig}
import net.ghoula.eru.http.websocket.{WebSocketCloseCode, WebSocketError, WebSocketHandshake, WebSocketMessage}
import net.ghoula.melian.WebSocketTransport

/** Runtime support for compiled WebSocket routes.
  *
  * A WebSocket route is registered under `Method.GET` (the upgrade request is a GET). When the
  * matched request carries a valid RFC 6455 upgrade, [[dispatch]] hands the upgraded connection to
  * the route's handler through eru-http's pending-handler registry; a plain GET to a WebSocket path
  * answers 426 Upgrade Required.
  */
object WebSocketRoutes {

  /* 426 ships in eru-http's StatusCode registry as of 1.0.0-alpha.2. */
  private val UpgradeRequired: StatusCode = StatusCode.UpgradeRequired

  private def upgradeRequiredResponse: Eru[HttpError, Response[Body]] =
    Response(UpgradeRequired, Headers.empty, Body.text("WebSocket upgrade required"))
      .setHeader(HeaderNames.Upgrade, "websocket")
      .mapError { case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
        HttpError.InvalidResponse(InvalidResponse(err.toString, "Upgrade header"))
      }

  /** Dispatches a matched request for a WebSocket route. */
  def dispatch(request: Request[Body], config: WebSocketServerConfig)(
    wsHandler: WebSocketHandler
  ): Eru[HttpError, Response[Body]] =
    if WebSocketHandshake.isUpgradeRequest(request) then
      WebSocketServer
        .upgradeHandler(config)(wsHandler)(_ => upgradeRequiredResponse)(request)
    else upgradeRequiredResponse

  /** Adapts eru-http's server connection to melian-core's transport interface. */
  private[router] def transportFor(conn: ServerWebSocketConnection): WebSocketTransport =
    new WebSocketTransport {
      def receive(): Eru[WebSocketError, WebSocketMessage] = conn.receive()
      def sendText(text: String): Eru[WebSocketError, Unit] = conn.sendText(text)
      def close(code: WebSocketCloseCode, reason: Option[String]): Eru[WebSocketError, Unit] =
        conn.close(code, reason)
      def isOpen: Boolean = conn.isOpen
      def subprotocol: Option[String] = conn.subprotocol
      def upgradeRequest: Request[Body] = conn.upgradeRequest
    }
}
