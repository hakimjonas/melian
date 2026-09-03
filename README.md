# Melian

A web framework for Scala 3 with no runtime reflection. Routing, request extraction, validation, response encoding, and OpenAPI generation are decided at compile time.

Melian is part of the Arda ecosystem and depends on [`eru`](https://github.com/hakimjonas/eru) (effects on Java virtual threads), [`eru-http`](https://github.com/hakimjonas/eru-http) (HTTP/1.1, HTTP/2, TLS, WebSocket), [`sarati`](https://github.com/hakimjonas/sarati) (codecs), [`rumil`](https://github.com/hakimjonas/rumil) (parsers), and [`valar`](https://github.com/hakimjonas/valar) (validation).

## What it does

- A route is a function. Its parameters say where request data comes from: `Path[A]`, `Query[name, A]`, `Header[A]`, `Json[A]`, `Coded[A]`, `Form[A]`. The macro generates the extraction and the validation pipeline. JSON bodies parse resiliently by default (recovered errors become warnings); `Strict[Json[A]]` rejects on the first error.
- The return type carries HTTP semantics. `Created[A]` is 201 and must have a `Location` header; `NoContent` is 204 and has no body; `Unauthorized[A]` emits `WWW-Authenticate`; `TooManyRequests[A]` emits `Retry-After`; `Status[Code, A]` serves any other status via a phantom literal, checked at compile time. A violation is a compile error.
- Extraction, decode, and validation errors accumulate into one RFC 9457 problem-details response. Domain errors render through a three-tier error model: an `ErrorRenderer[E]` given (endpoint), a builder-level renderer (group), and a built-in problem-details fallback.
- Declaring a `Header[Accept]` parameter enables response content negotiation (JSON by default, XML/YAML by encoder presence, RFC 9110 q-value dispatch, 406 when nothing matches).
- Typed WebSocket endpoints via `Router.builder.websocket`: a `WebSocketSession` with typed `receive`/`send`, strictly validated inbound messages, and the RFC 6455 upgrade handled by eru-http.
- OpenAPI 3.1 is generated from the same types the compiler checks, so the specification cannot drift from the implementation; every operation carries a stable `operationId`.
- `HEAD` is derived from `GET` (RFC 9110 §9.3.2). `QUERY` (RFC 10008) is supported.

## Requirements

- JDK 25
- sbt 2.0.7 or later
- Scala 3.8.4

## Quickstart

```scala
// build.sbt
libraryDependencies += "net.ghoula" %% "melian-server" % "1.0.0-alpha"
```

```scala
import net.ghoula.eru.{Eru, EruRuntime}
import net.ghoula.melian.*
import net.ghoula.melian.router.{Router, SaratiBridge}
import net.ghoula.melian.server.MelianServer
import net.ghoula.sarati.codec.JsonEncoders

object Main {
  import JsonEncoders.given
  import SaratiBridge.given

  def main(args: Array[String]): Unit = {
    given runtime: EruRuntime = EruRuntime.shared

    val hello: Path[String] => Eru[Nothing, Ok[String]] =
      (name: Path[String]) => Eru.succeed(Ok(s"hello, $name"))

    val router = Router.builder
      .get("/hello/:name", hello)
      .build
      .getOrElse(sys.error("route build failed"))

    MelianServer
      .serve(router) { server =>
        server.start.map(addr => println(s"listening on $addr"))
      }
      .unsafeRunSync()
  }
}
```

`Ok[String]` needs a `BodyEncoder[String]`, which comes from `SaratiBridge` once an `Encoder[String, JsonValue]` is in scope (`JsonEncoders.given`). For a case class, derive it:

```scala
import net.ghoula.melian.*
import net.ghoula.melian.extraction.FormDecoder
import net.ghoula.sarati.codec.{Decoder, Encoder}
import net.ghoula.valar.Validator

case class CreateUser(name: String, age: Int)

given Decoder[net.ghoula.sarati.ast.json.JsonValue, CreateUser] = Decoder.derived
given Encoder[CreateUser, net.ghoula.sarati.ast.json.JsonValue] = Encoder.derived
given Validator[CreateUser] = Validator.derive

val create: Json[CreateUser] => Eru[Nothing, Created[CreateUser]] =
  (user: Json[CreateUser]) => Eru.succeed(Created(user))
```

A `Coded[A]` body accepts JSON, XML, and YAML and dispatches on `Content-Type`. A `Form[A]` body decodes `application/x-www-form-urlencoded`; derive `FormDecoder.derived` for case classes.

## Beyond hello world

**WebSockets** are typed sessions. The upgrade request's parameters go through the same extraction pipeline, inbound messages are decoded and validated (a rejected message closes the connection with 1003), and outbound messages are encoded to JSON text frames:

```scala
case class ClientMsg(text: String)
case class ServerMsg(reply: String)
// given Decoder[JsonValue, ClientMsg], Validator[ClientMsg], Encoder[ServerMsg, JsonValue]

val live: Path[String] => WebSocketEndpoint[ClientMsg, ServerMsg] =
  (room: Path[String]) => session =>
    for {
      msg <- session.receive()
      _ <- session.send(ServerMsg(s"[$room] ${msg.text}"))
    } yield ()

Router.builder.websocket("/ws/:room", live)
```

**Content negotiation**: declare a `Header[Accept]` parameter and provide the encoders you support; the response format is selected per RFC 9110 q-values (JSON is the baseline, XML and YAML are opt-in, no match answers 406):

```scala
given Encoder[Report, net.ghoula.sarati.ast.xml.XmlNode] = Encoder.derived // adds application/xml

val report: (Path[UUID], Header[Accept]) => Eru[Nothing, Ok[Report]] =
  (id, accept) => Eru.succeed(Ok(buildReport(id)))
```

**Protocol-correct wrappers**: `Unauthorized[A]` emits `WWW-Authenticate`, `TooManyRequests[A]` emits `Retry-After`, and `Status[Code, A]` serves any other body-carrying status via a phantom literal that the compiler validates:

```scala
val deny: Header[BearerToken] => Eru[Nothing, Unauthorized[String]] =
  _ => Eru.succeed(Unauthorized("Bearer realm=\"api\"", "denied"))
```

**Cross-cutting middleware** composes at the eru-http level and wraps the router via `MelianServer.serveWith`:

```scala
import net.ghoula.melian.server.{Csrf, RateLimit}

val app = Csrf.middleware() andThen RateLimit.middleware(RateLimit.Config(limit = 100))
MelianServer.serveWith(router, app) { server => server.start.map(println) }
```

## Modules

| Module | Purpose |
| --- | --- |
| `melian-core` | Markers, response types, error model, extraction typeclasses, WebSocket session |
| `melian-router` | The compile-time router, macro, body decoding, and WebSocket upgrade dispatch |
| `melian-openapi` | OpenAPI 3.1 generation and Swagger UI |
| `melian-server` | `MelianServer` entry points, static files, security headers, error pages, health, CSRF, sessions, rate limiting |
| `melian-test` | `MelianTestKit` for testing routers without a server |

CORS, request logging, request IDs, authentication, error handling, compression, and body limits are provided by `eru-http`'s `Middleware` and compose with `MelianServer.serveWith`.

## Documentation

- [DESIGN.md](DESIGN.md): architecture and design decisions
- [ROADMAP.md](ROADMAP.md): current state and plan

## License

GPL-3.0-or-later.
