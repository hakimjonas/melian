package net.ghoula.melian.router

import net.ghoula.eru.http.{Charset, ChunkStream, HttpError, ServerSentEvent}
import net.ghoula.eru.{Eru, Result}
import net.ghoula.melian.EventSource
import net.ghoula.sarati.ast.json.{JsonValue, compactFormat, formatJson}
import net.ghoula.sarati.codec.Encoder

/** Converts an [[EventSource]] into the response's `ChunkStream`.
  *
  * Raw mode (element type `ServerSentEvent`): events pass through verbatim. Typed mode: each event
  * is encoded via `Encoder[A, JsonValue]` and emitted as a `ServerSentEvent.data` (compact JSON, so
  * payloads stay on one SSE `data:` line). A source failure terminates the stream with a transport
  * error — the response status and headers are already on the wire by then.
  */
object SseBridge {

  private val charset: Charset = Charset.UTF8

  /** Pre-formatted events, emitted verbatim. */
  def raw(source: EventSource[ServerSentEvent]): ChunkStream = loop(source, identity)

  /** Typed events, JSON-encoded as `ServerSentEvent.data`. */
  def typed[A](source: EventSource[A], eventEncoder: Encoder[A, JsonValue]): ChunkStream =
    loop(source, (event: A) => ServerSentEvent.data(formatJson(eventEncoder.encode(event), compactFormat)))

  private def loop[A](source: EventSource[A], encode: A => ServerSentEvent): ChunkStream =
    ChunkStream.eval {
      source.pull.attempt.flatMap {
        case Result.Failure(error) =>
          Eru.succeed(
            ChunkStream.fail(HttpError.ProtocolError(s"Event source failed: $error", "event source"))
          )
        case Result.Success(None) => Eru.succeed(ChunkStream.empty)
        case Result.Success(Some(event)) =>
          // The recursive call sits inside the suspend thunk, so each level is built at pull
          // time and the construction is lazy for infinite sources.
          Eru.succeed(
            ChunkStream.single(encode(event).toChunk(charset)) ++ loop(source, encode)
          )
      }
    }
}
