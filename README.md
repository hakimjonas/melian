# Melian

A web framework for Scala 3 with no runtime reflection. Routing, request extraction, validation, response encoding, and OpenAPI generation are decided at compile time.

Melian is part of the Arda ecosystem and depends on [`eru`](https://github.com/hakimjonas/eru) (effects on Java virtual threads), [`eru-http`](https://github.com/hakimjonas/eru-http) (HTTP/1.1, HTTP/2, TLS, WebSocket), [`sarati`](https://github.com/hakimjonas/sarati) (codecs), [`rumil`](https://github.com/hakimjonas/rumil) (parsers), and [`valar`](https://github.com/hakimjonas/valar) (validation).

## What it does

- A route is a function. Its parameters say where request data comes from: `Path[A]`, `Query[name, A]`, `Header[A]`, `Json[A]`, `Coded[A]`, `Form[A]`. The macro generates the extraction and the validation pipeline.
- The return type carries HTTP semantics. `Created[A]` is 201 and must have a `Location` header; `NoContent` is 204 and has no body; a GET handler cannot take a body parameter. A violation is a compile error.
- Extraction, decode, and validation errors accumulate into one RFC 9457 problem-details response. Domain errors render through an `ErrorRenderer[E]` typeclass.
- OpenAPI 3.1 is generated from the same types the compiler checks, so the specification cannot drift from the implementation.
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

## Modules

| Module | Purpose |
| --- | --- |
| `melian-core` | Markers, response types, error model, extraction typeclasses |
| `melian-router` | The compile-time router, macro, and body decoding |
| `melian-openapi` | OpenAPI 3.1 generation and Swagger UI |
| `melian-server` | `MelianServer` entry point, static files, security headers, error pages, health |
| `melian-test` | `MelianTestKit` for testing routers without a server |

CORS, request logging, request IDs, authentication, error handling, compression, and body limits are provided by `eru-http`'s `Middleware` and compose with `MelianServer.serveWith`.

## Documentation

- [DESIGN.md](DESIGN.md): architecture and design decisions
- [ROADMAP.md](ROADMAP.md): current state and plan

## License

GPL-3.0-or-later.
