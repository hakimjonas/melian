package net.ghoula.melian.router

import munit.FunSuite

class CompileErrorSpec extends FunSuite {

  test("GET with Json body does not compile") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.valar.Validator
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.{Decoder, Encoder}

      case class Payload(x: Int)
      given Decoder[JsonValue, Payload] = ???
      given Encoder[String, JsonValue] = ???
      given Validator[Payload] = ???
      import SaratiBridge.given

      Router.builder.get("/items", (p: Json[Payload]) => Eru.succeed(Ok("ok")))
    """)
    assert(errors.contains("GET"), s"Should mention GET: $errors")
    assert(errors.contains("does not allow a request body"), s"Should mention body constraint: $errors")
  }

  test("POST without body does not compile") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.Encoder

      given Encoder[String, JsonValue] = ???
      import SaratiBridge.given

      Router.builder.post("/items", () => Eru.succeed(Ok("ok")))
    """)
    assert(errors.contains("POST"), s"Should mention POST: $errors")
    assert(errors.contains("requires a request body"), s"Should mention body required: $errors")
  }

  test("mismatched path param count does not compile") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.Encoder

      given Encoder[String, JsonValue] = ???
      import SaratiBridge.given

      Router.builder.get("/users/:id/:name", (id: Path[String]) => Eru.succeed(Ok(id)))
    """)
    assert(errors.contains("path parameters do not match"), s"Should mention mismatch: $errors")
  }

  test("missing FromPathSegment does not compile with diagnostic") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.Encoder

      case class CustomId(value: String)
      given Encoder[String, JsonValue] = ???
      import SaratiBridge.given

      Router.builder.get("/users/:id", (id: Path[CustomId]) => Eru.succeed(Ok("ok")))
    """)
    assert(errors.contains("FromPathSegment"), s"Should mention missing typeclass: $errors")
  }

  test("missing Validator for Json body does not compile") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.melian.extraction.FromHeader.BearerToken
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.{Decoder, Encoder}

      case class Cmd(name: String)
      given Decoder[JsonValue, Cmd] = ???
      given Encoder[String, JsonValue] = ???
      import SaratiBridge.given

      Router.builder.post("/items", (auth: Header[BearerToken], cmd: Json[Cmd]) => Eru.succeed(Ok("ok")))
    """)
    assert(errors.contains("Validator"), s"Should mention missing Validator: $errors")
  }

  test("missing BodyDecoder for Json body does not compile") {
    val errors = compileErrors("""
      import net.ghoula.eru.Eru
      import net.ghoula.eru.http.*
      import net.ghoula.melian.*
      import net.ghoula.melian.extraction.FromHeader.BearerToken
      import net.ghoula.valar.Validator
      import net.ghoula.sarati.ast.json.JsonValue
      import net.ghoula.sarati.codec.Encoder

      case class Cmd(name: String)
      given Validator[Cmd] = ???
      given Encoder[String, JsonValue] = ???
      import SaratiBridge.given

      Router.builder.post("/items", (auth: Header[BearerToken], cmd: Json[Cmd]) => Eru.succeed(Ok("ok")))
    """)
    assert(errors.contains("BodyDecoder"), s"Should mention missing BodyDecoder: $errors")
  }
}
