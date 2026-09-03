package net.ghoula.melian.router

import munit.FunSuite

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.eru.http.websocket.{WebSocketCloseCode, WebSocketError, WebSocketHandshake, WebSocketMessage}
import net.ghoula.melian.*
import net.ghoula.melian.extraction.FromHeader.BearerToken
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.{Decoder, Encoder, JsonDecoders, JsonEncoders}
import net.ghoula.valar.Validator

import JsonDecoders.given
import JsonEncoders.given

class WebSocketRouteSpec extends FunSuite {

  case class ClientMsg(text: String)
  case class ServerMsg(reply: String)

  given Decoder[JsonValue, ClientMsg] = Decoder.derived
  given Validator[ClientMsg] = Validator.derive
  given Encoder[ServerMsg, JsonValue] = Encoder.derived

  private def run(handler: Request[Body] => Eru[HttpError, Response[Body]], request: Request[Body]): Response[Body] =
    handler(request).unsafeRunSync()

  private def bodyText(response: Response[Body]): String = response.body match {
    case Body.Text(text, _, _) => text
    case other => fail(s"Expected Text body, got: $other"); ""
  }

  private def header(response: Response[Body], name: String): Option[String] =
    response.headers.getFirst(name).map(_.value)

  private def upgradeRequest(path: String): (Request[Body], String) = {
    val key = WebSocketHandshake.generateKey()
    val base = WebSocketHandshake.createUpgradeRequest(Uri.http("localhost", path = path), key).unsafeRunSync()
    // Real upgrade requests always carry Host (RFC 6455 Section 4.2.1 requires it); the client
    // helper builds the protocol headers only.
    val request = base.setHeader("Host", "localhost").unsafeRunSync()
    (request, key)
  }

  private val echoEndpoint: WebSocketEndpoint[ClientMsg, ServerMsg] = { session =>
    for {
      msg <- session.receive()
      _ <- session.send(ServerMsg(s"echo:${msg.text}"))
      _ <- session.close()
    } yield ()
  }

  // --- Upgrade dispatch ---

  test("a valid upgrade request answers 101 with the correct Sec-WebSocket-Accept") {
    val handler: Path[String] => WebSocketEndpoint[ClientMsg, ServerMsg] =
      (_: Path[String]) => echoEndpoint

    val router = Router.builder.websocket("/ws/:id", handler).build.getOrElse(fail("build failed"))
    val (request, key) = upgradeRequest("/ws/abc")
    val response = run(router.toHandler, request)

    assertEquals(response.status, StatusCode.SwitchingProtocols)
    assertEquals(header(response, "Sec-WebSocket-Accept"), Some(WebSocketHandshake.calculateAccept(key)))
  }

  test("a plain GET to a WebSocket path answers 426 Upgrade Required") {
    val handler: Path[String] => WebSocketEndpoint[ClientMsg, ServerMsg] =
      (_: Path[String]) => echoEndpoint

    val router = Router.builder.websocket("/ws/:id", handler).build.getOrElse(fail("build failed"))
    val response =
      run(router.toHandler, Request(Method.GET, Uri.http("localhost", path = "/ws/abc"), Headers.empty, Body.empty))

    assertEquals(response.status.value, 426)
    assertEquals(header(response, "Upgrade"), Some("websocket"))
  }

  test("extraction failures on an upgrade request answer 400 without upgrading") {
    val handler: (Path[String], Header[BearerToken]) => WebSocketEndpoint[ClientMsg, ServerMsg] =
      (_: Path[String], _: Header[BearerToken]) => echoEndpoint

    val router = Router.builder.websocket("/ws/:id", handler).build.getOrElse(fail("build failed"))
    val (request, _) = upgradeRequest("/ws/abc")
    val response = run(router.toHandler, request)

    assertEquals(response.status, StatusCode.BadRequest)
    assert(bodyText(response).contains("Authorization"), s"Body: ${bodyText(response)}")
  }

  test("WebSocket routes appear in the operation schemas marked as websockets") {
    val handler: Path[String] => WebSocketEndpoint[ClientMsg, ServerMsg] =
      (_: Path[String]) => echoEndpoint

    val builder = Router.builder.websocket("/ws/:id", handler, operationId = "liveSession")
    val schema = builder.operationSchemas.head
    assert(schema.isWebSocket, "The operation schema must be marked as a WebSocket route")
    assertEquals(schema.operationId, Some("liveSession"))
    assertEquals(schema.method, "GET")
    assertEquals(schema.responseStatus, 101)
  }

  test("an explicit GET route and a WebSocket route cannot share a path") {
    val wsHandler: Path[String] => WebSocketEndpoint[ClientMsg, ServerMsg] =
      (_: Path[String]) => echoEndpoint
    val getHandler: Path[String] => Eru[Nothing, Ok[String]] =
      (id: Path[String]) => Eru.succeed(Ok(id))

    val result = Router.builder
      .websocket("/shared/:id", wsHandler)
      .get("/shared/:id", getHandler)
      .build

    assert(result.isLeft, "A WebSocket route and a GET route on the same path must be rejected")
  }

  // --- WebSocketSession over a fake transport ---

  private final class FakeTransport(incoming: List[WebSocketMessage]) extends WebSocketTransport {
    private val index = new AtomicInteger(0)
    private val sent = new ConcurrentLinkedQueue[String]()
    private val closed = new AtomicReference[String]("")

    def receive(): Eru[WebSocketError, WebSocketMessage] = {
      val i = index.getAndIncrement()
      if i < incoming.length then Eru.succeed(incoming(i))
      else Eru.fail(WebSocketError.ConnectionClosed(None, None, clean = false))
    }
    def sendText(text: String): Eru[WebSocketError, Unit] = { val _ = sent.add(text); Eru.succeed(()) }
    def close(code: WebSocketCloseCode, reason: Option[String]): Eru[WebSocketError, Unit] = {
      closed.set(s"${code.value}:${reason.getOrElse("")}")
      Eru.succeed(())
    }
    def isOpen: Boolean = index.get() < incoming.length
    def subprotocol: Option[String] = None
    def upgradeRequest: Request[Body] =
      Request(Method.GET, Uri.http("localhost", path = "/ws"), Headers.empty, Body.empty)

    def sentTexts: List[String] = sent.iterator().asScala.toList
    def closeInfo: String = closed.get()
  }

  // The decode pipeline here mirrors the macro-generated one; the session behavior under it is
  // what this spec exercises.
  private def decodeClientMsg(message: WebSocketMessage): Either[String, ClientMsg] = {
    val text = message match {
      case WebSocketMessage.Text(value) => value
      case WebSocketMessage.Binary(bytes) => bytes.asString(Charset.UTF8)
    }
    parsers.json.parseJson(text) match {
      case parser.core.Result.Success(json, _) =>
        summon[Decoder[JsonValue, ClientMsg]].decode(json) match {
          case net.ghoula.sarati.Result.Success(value, _) => Right(value)
          case other => Left(s"decode failed: $other")
        }
      case other => Left(s"parse failed: $other")
    }
  }

  private def encodeServerMsg(out: ServerMsg): String =
    net.ghoula.sarati.ast.json.formatJson(
      summon[Encoder[ServerMsg, JsonValue]].encode(out),
      net.ghoula.sarati.ast.json.compactFormat
    )

  test("WebSocketSession decodes inbound text messages and encodes outbound ones") {
    val transport = FakeTransport(List(WebSocketMessage.Text("""{"text":"hello"}""")))
    val session = WebSocketSession[ClientMsg, ServerMsg](transport, decodeClientMsg, encodeServerMsg)

    val received = session.receive().unsafeRunSync()
    assertEquals(received, ClientMsg("hello"))

    session.send(ServerMsg("hi there")).unsafeRunSync()
    assertEquals(transport.sentTexts, List("""{"reply":"hi there"}"""))
  }

  test("WebSocketSession rejects an undecodable message: closes 1003 and fails") {
    val transport = FakeTransport(List(WebSocketMessage.Text("not json")))
    val session = WebSocketSession[ClientMsg, ServerMsg](transport, decodeClientMsg, encodeServerMsg)

    val failure = session.receive().attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Failure(e) => e
      case other => fail(s"Expected a failed receive, got: $other")
    }

    assert(failure.toString.contains("rejected"), s"Error: $failure")
    assert(transport.closeInfo.startsWith("1003"), s"Close info: ${transport.closeInfo}")
  }

  test("WebSocketSession rejects a message failing validation: closes 1003 and fails") {
    case class GuardedMsg(text: String)

    given Decoder[JsonValue, GuardedMsg] = Decoder.derived
    given Validator[GuardedMsg] with {
      def validate(a: GuardedMsg): net.ghoula.valar.ValidationResult[GuardedMsg] =
        if a.text.isEmpty then
          net.ghoula.valar.ValidationResult.Invalid(
            Vector(
              net.ghoula.valar.ValidationErrors
                .ValidationError(message = "text must not be empty", fieldPath = List("text"))
            )
          )
        else net.ghoula.valar.ValidationResult.Valid(a)
    }

    def decodeGuarded(message: WebSocketMessage): Either[String, GuardedMsg] = {
      val text = message match {
        case WebSocketMessage.Text(value) => value
        case WebSocketMessage.Binary(bytes) => bytes.asString(Charset.UTF8)
      }
      parsers.json.parseJson(text) match {
        case parser.core.Result.Success(json, _) =>
          summon[Decoder[JsonValue, GuardedMsg]].decode(json) match {
            case net.ghoula.sarati.Result.Success(value, _) =>
              summon[Validator[GuardedMsg]].validate(value) match {
                case net.ghoula.valar.ValidationResult.Valid(v) => Right(v)
                case net.ghoula.valar.ValidationResult.Invalid(errors) =>
                  Left("validation failed: " + errors.map(_.message).mkString("; "))
              }
            case other => Left(s"decode failed: $other")
          }
        case other => Left(s"parse failed: $other")
      }
    }

    val transport = FakeTransport(List(WebSocketMessage.Text("""{"text":""}""")))
    val session = WebSocketSession[GuardedMsg, ServerMsg](transport, decodeGuarded, encodeServerMsg)

    val failure = session.receive().attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Failure(e) => e
      case other => fail(s"Expected a failed receive, got: $other")
    }

    assert(failure.toString.contains("validation failed"), s"Error: $failure")
    assert(transport.closeInfo.startsWith("1003"), s"Close info: ${transport.closeInfo}")
  }

  test("a WebSocket route cannot declare a body marker") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.{Decoder, Encoder, JsonDecoders, JsonEncoders}
      import net.ghoula.valar.Validator
      import JsonDecoders.given
      import JsonEncoders.given

      case class Msg(text: String)
      given Decoder[JsonValue, Msg] = Decoder.derived
      given Validator[Msg] = Validator.derive
      given Encoder[Msg, JsonValue] = Encoder.derived

      val ws: Json[Msg] => WebSocketEndpoint[Msg, Msg] = _ => _ => Eru.succeed(())

      Router.builder.websocket("/ws", ws)
    """)
    assert(errors.replaceAll("\\s+", " ").contains("cannot carry a body"), s"Errors: $errors")
  }
}
