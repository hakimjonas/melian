package net.ghoula.melian.router

import scala.annotation.tailrec

import net.ghoula.eru.http.*
import net.ghoula.eru.{Eru, Result}
import net.ghoula.melian.*
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.Encoder

/** Micro-benchmark comparing Melian's router dispatch against equivalent hand-rolled `eru-http`
  * handlers, with a breakdown of the interpreter and pipeline costs.
  *
  * Not a test; run it manually:
  *
  * {{{
  *   sbt "router/Test/runMain net.ghoula.melian.router.RouterBenchmark"
  * }}}
  */
object RouterBenchmark {

  given Encoder[String, JsonValue] with {
    def encode(value: String): JsonValue = JsonValue.Str(value)
  }

  import SaratiBridge.given

  private val id: String = "550e8400-e29b-41d4-a716-446655440000"

  private val iterations = 200_000
  private val warmup = 20_000

  def main(args: Array[String]): Unit = {
    val handler: Path[String] => Eru[Nothing, Ok[String]] =
      (id: Path[String]) => Eru.succeed(Ok(id))

    val noParamHandler: () => Eru[Nothing, Ok[String]] =
      () => Eru.succeed(Ok("fixed"))

    val router = Router.builder.get("/users/:id", handler).build.getOrElse(sys.error("route build failed"))
    val noParamRouter = Router.builder.get("/fixed", noParamHandler).build.getOrElse(sys.error("route build failed"))
    val melian = router.toHandler
    val melianNoParam = noParamRouter.toHandler

    val raw: Request[Body] => Eru[HttpError, Response[Body]] = { request =>
      val extracted = request.uri.path.stripPrefix("/users/")
      Eru.succeed(
        Response(StatusCode.Ok, Headers.empty, Body.text(s"""{"value":"$extracted"}""", MediaType.applicationJson))
      )
    }

    val request = Request(Method.GET, Uri.http("localhost", path = s"/users/$id"), Headers.empty, Body.empty)
    val fixedRequest = Request(Method.GET, Uri.http("localhost", path = "/fixed"), Headers.empty, Body.empty)

    val succeedBaseline = () => Eru.succeed(1).unsafeRunSync(): Any
    val mapBaseline = () => Eru.succeed(1).map(_ + 1).unsafeRunSync(): Any
    val flatMapBaseline = () => Eru.succeed(1).flatMap(i => Eru.succeed(i + 1)).unsafeRunSync(): Any
    val attemptFlatMapBaseline = () =>
      Eru
        .succeed(1)
        .attempt
        .flatMap {
          case Result.Success(i) => Eru.succeed(i)
          case Result.Failure(_) => Eru.succeed(0)
        }
        .unsafeRunSync(): Any

    val all: List[(String, () => Any)] = List(
      "Eru.succeed (fast path)" -> succeedBaseline,
      "Eru.map (fast path)" -> mapBaseline,
      "Eru.flatMap (slow path)" -> flatMapBaseline,
      "Eru.attempt.flatMap" -> attemptFlatMapBaseline,
      "raw handler" -> (() => raw(request).unsafeRunSync(): Any),
      "melian no-param" -> (() => melianNoParam(fixedRequest).unsafeRunSync(): Any),
      "melian 1 path param" -> (() => melian(request).unsafeRunSync(): Any)
    )

    // Warm up every benchmark first so JIT does not pollute the first timed run.
    all.foreach { case (_, f) => repeat(warmup)(f()) }

    val results = all.map { case (name, f) =>
      val elapsed = time(iterations)(f())
      (name, elapsed / iterations.toDouble)
    }

    val rawNs = results.find(_._1 == "raw handler").map(_._2).getOrElse(0.0)
    val melianNs = results.find(_._1 == "melian 1 path param").map(_._2).getOrElse(0.0)

    results.foreach { case (name, ns) => println(f"$name%-28s ${ns}%.0f ns/op") }
    println(f"Melian overhead vs raw: ${melianNs - rawNs}%.0f ns/op (${melianNs / rawNs}%.2fx)")
  }

  private def time(n: Int)(f: => Any): Long = {
    val start = System.nanoTime()
    repeat(n)(f)
    System.nanoTime() - start
  }

  @tailrec
  private def repeat(n: Int)(f: => Any): Unit =
    if n > 0 then {
      val _ = f
      repeat(n - 1)(f)
    }
}
