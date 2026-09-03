package net.ghoula.melian.router

import munit.FunSuite

import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.*
import net.ghoula.eru.http.client.{WebSocketClient, WebSocketClientConfig}
import net.ghoula.eru.http.server.{HttpServer, HttpServerConfig}
import net.ghoula.eru.http.websocket.WebSocketMessage
import net.ghoula.melian.*
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.{Decoder, Encoder, JsonDecoders, JsonEncoders}
import net.ghoula.valar.Validator

import JsonDecoders.given
import JsonEncoders.given

/** End-to-end WebSocket coverage over a real socket: a compiled `websocket` route served by a live
  * `HttpServer`, exercised with eru-http's own client. This is the integration counterpart to
  * [[WebSocketRouteSpec]]'s dispatch and fake-transport tests — no seams, the actual upgrade path
  * including the pending-handler handoff to `NativeHttpServer`.
  */
class WebSocketEndToEndSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  case class ClientMsg(text: String)
  case class ServerMsg(reply: String)

  given Decoder[JsonValue, ClientMsg] = Decoder.derived
  given Validator[ClientMsg] = Validator.derive
  given Encoder[ServerMsg, JsonValue] = Encoder.derived
  given Decoder[JsonValue, ServerMsg] = Decoder.derived

  test("a compiled websocket route echoes through the typed session over a real socket") {
    val handler: Path[String] => WebSocketEndpoint[ClientMsg, ServerMsg] =
      (sessionId: Path[String]) =>
        session =>
          for {
            msg <- session.receive()
            _ <- session.send(ServerMsg(s"$sessionId:${msg.text}"))
            _ <- session.close()
          } yield ()

    val router = Router.builder.websocket("/ws/:sessionId", handler).build.getOrElse(fail("build failed"))

    val program = HttpServer.scoped(HttpServerConfig.localhost.withPort(0))(router.toHandler) { server =>
      for {
        address <- server.start
        uri <- Uri
          .parse(s"ws://${address.host}:${address.port}/ws/room-7")
          .mapError(e => HttpError.InvalidUri(e): HttpError)
        received <- WebSocketClient
          .scoped(uri, WebSocketClientConfig.default, Headers.empty) { conn =>
            for {
              _ <- conn.sendText("""{"text":"hello"}""")
              received <- conn.receive()
            } yield received
          }
          .mapError {
            case e: net.ghoula.eru.http.websocket.WebSocketError =>
              HttpError.ProtocolError(e.toString, "WebSocket e2e"): HttpError
            case e: HttpError => e
          }
      } yield received
    }

    val received = program.attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Success(message) => message
      case net.ghoula.eru.Result.Failure(e) => fail(s"E2E WebSocket round-trip failed: $e")
    }

    val text = received match {
      case WebSocketMessage.Text(json) => json
      case other => fail(s"Expected a text message, got: $other")
    }

    val decoded = parsers.json.parseJson(text) match {
      case parser.core.Result.Success(json, _) =>
        summon[Decoder[JsonValue, ServerMsg]].decode(json) match {
          case net.ghoula.sarati.Result.Success(parsed, _) => parsed
          case other => fail(s"Could not decode the server reply: $other")
        }
      case other => fail(s"Server reply was not JSON: $other")
    }

    assertEquals(decoded, ServerMsg("room-7:hello"))
  }
}
