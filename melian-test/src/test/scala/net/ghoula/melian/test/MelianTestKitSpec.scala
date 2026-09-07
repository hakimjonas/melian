package net.ghoula.melian.test

import munit.FunSuite

import net.ghoula.eru.Eru
import net.ghoula.eru.EruRuntime
import net.ghoula.eru.http.*
import net.ghoula.eru.http.websocket.WebSocketMessage
import net.ghoula.melian.*
import net.ghoula.melian.extraction.FormDecoder
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.{Decoder, Encoder, JsonDecoders}
import net.ghoula.valar.Validator

import JsonDecoders.given

/** Tests for the test kit's own helpers: the form helper and the end-to-end websocket helper. */
class MelianTestKitSpec extends FunSuite {

  given runtime: EruRuntime = EruRuntime.shared

  given Encoder[String, JsonValue] with {
    def encode(value: String): JsonValue = JsonValue.Str(value)
  }

  import net.ghoula.melian.router.SaratiBridge.given

  private def bodyText(response: Response[Body]): String = response.body match {
    case Body.Text(text, _, _) => text
    case other => fail(s"Expected Text body, got: $other"); ""
  }

  // --- postForm ---

  case class LoginForm(username: String, count: Int)

  given FormDecoder[LoginForm] = FormDecoder.derived
  given Validator[LoginForm] with {
    def validate(a: LoginForm): net.ghoula.valar.ValidationResult[LoginForm] =
      net.ghoula.valar.ValidationResult.Valid(a)
  }

  test("postForm percent-encodes fields and drives a Form route") {
    val handler: Form[LoginForm] => Eru[Nothing, Ok[String]] =
      (f: Form[LoginForm]) => Eru.succeed(Ok(s"${f.username}:${f.count}"))

    val router = net.ghoula.melian.router.Router.builder.post("/login", handler).build.getOrElse(fail("build failed"))
    val response = MelianTestKit.postForm(router, "/login", "username" -> "a b&c=d", "count" -> "7")

    assertEquals(response.status, StatusCode.Ok)
    // The response body is JSON-encoded (quoted); contains verifies the round-trip of the
    // percent-encoded specials (&, =, and the form-space +).
    assert(bodyText(response).contains("a b&c=d:7"), s"Body: ${bodyText(response)}")
  }

  // --- websocket helper ---

  case class ClientMsg(text: String)
  case class ServerMsg(reply: String)

  given Decoder[JsonValue, ClientMsg] = Decoder.derived
  given Validator[ClientMsg] = Validator.derive
  given Encoder[ServerMsg, JsonValue] = Encoder.derived
  given Decoder[JsonValue, ServerMsg] = Decoder.derived

  test("the websocket helper drives a compiled route over a real socket") {
    val handler: Path[String] => WebSocketEndpoint[ClientMsg, ServerMsg] =
      (room: Path[String]) =>
        session =>
          for {
            msg <- session.receive()
            _ <- session.send(ServerMsg(s"[$room] ${msg.text}"))
            _ <- session.close()
          } yield ()

    val router = net.ghoula.melian.router.Router.builder
      .websocket("/ws/:room", handler)
      .build
      .getOrElse(
        fail("build failed")
      )

    val program = MelianTestKit.websocket(router, "/ws/lobby") { conn =>
      for {
        _ <- conn.sendText("""{"text":"hi"}""")
        received <- conn.receive()
      } yield received
    }

    val received = program.attempt.unsafeRunSync() match {
      case net.ghoula.eru.Result.Success(message) => message
      case net.ghoula.eru.Result.Failure(e) => fail(s"Websocket helper failed: $e")
    }

    val reply = received match {
      case WebSocketMessage.Text(json) =>
        parsers.json.parseJson(json) match {
          case parser.core.Result.Success(value, _) =>
            summon[Decoder[JsonValue, ServerMsg]].decode(value) match {
              case net.ghoula.sarati.Result.Success(parsed, _) => parsed
              case other => fail(s"Could not decode the server reply: $other")
            }
          case other => fail(s"Server reply was not JSON: $other")
        }
      case other => fail(s"Expected text, got: $other")
    }

    assertEquals(reply, ServerMsg("[lobby] hi"))
  }
}
