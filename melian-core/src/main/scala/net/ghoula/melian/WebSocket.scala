package net.ghoula.melian

import net.ghoula.eru.Eru
import net.ghoula.eru.http.websocket.{WebSocketCloseCode, WebSocketError, WebSocketMessage}
import net.ghoula.eru.http.{Body, HttpError, Request}

/** The transport face of a server-side WebSocket connection. eru-http's `ServerWebSocketConnection`
  * is adapted to this interface by the router, keeping melian-core independent of the server
  * runtime.
  */
trait WebSocketTransport {
  def receive(): Eru[WebSocketError, WebSocketMessage]
  def sendText(text: String): Eru[WebSocketError, Unit]
  def close(code: WebSocketCloseCode, reason: Option[String]): Eru[WebSocketError, Unit]
  def isOpen: Boolean
  def subprotocol: Option[String]
  def upgradeRequest: Request[Body]
}

/** A typed WebSocket session: incoming messages are decoded to `In` (strictly -- no recovery), and
  * outgoing `Out` values are encoded to JSON text frames. The decoding function is supplied by the
  * compile-time route machinery from the handler's codec instances; the session itself is
  * transport-only.
  *
  * A message that fails decoding or validation closes the connection with 1003 (Unsupported Data)
  * and fails [[receive]] with a `WebSocketError.ProtocolViolation` carrying the detail, so a
  * malformed peer cannot feed garbage into business logic unbounded.
  */
final class WebSocketSession[In, Out](
  private val transport: WebSocketTransport,
  private val decodeMessage: WebSocketMessage => Either[String, In],
  private val encodeMessage: Out => String
) {

  /** Receives the next message from the client, decoded and validated. */
  def receive(): Eru[WebSocketError, In] =
    transport.receive().flatMap { message =>
      decodeMessage(message) match {
        case Right(value) => Eru.succeed(value)
        case Left(detail) =>
          transport
            .close(WebSocketCloseCode.UnsupportedData, Some(detail))
            .attempt
            .flatMap { _ =>
              Eru.fail(
                WebSocketError.ProtocolViolation(
                  s"Message rejected: $detail",
                  WebSocketCloseCode.UnsupportedData
                )
              )
            }
      }
    }

  /** Encodes `message` and sends it as a text frame. */
  def send(message: Out): Eru[WebSocketError, Unit] =
    transport.sendText(encodeMessage(message))

  /** Completes the close handshake. */
  def close(
    code: WebSocketCloseCode = WebSocketCloseCode.NormalClosure,
    reason: Option[String] = None
  ): Eru[WebSocketError, Unit] = transport.close(code, reason)

  def isOpen: Boolean = transport.isOpen

  def subprotocol: Option[String] = transport.subprotocol

  /** The HTTP request that triggered the upgrade (typed path/header extraction already ran on it).
    */
  def upgradeRequest: Request[Body] = transport.upgradeRequest
}

/** A typed WebSocket endpoint: the route macro extracts the handler's marked parameters from the
  * upgrade request, then hands the session to this function when the connection is established.
  *
  * The error channel is eru-http's: transport and protocol failures surface as `WebSocketError`,
  * dispatch-time (non-upgrade) handling as `HttpError`.
  */
type WebSocketEndpoint[In, Out] = WebSocketSession[In, Out] => Eru[WebSocketError | HttpError, Unit]
