package net.ghoula.melian

import net.ghoula.eru.Eru
import net.ghoula.eru.http.ServerSentEvent

/** A pull-based source of typed events for an [[EventStream]] response.
  *
  * The framework converts the source into the response's byte stream: each pulled event becomes one
  * `ServerSentEvent` (JSON-encoded via `Encoder[A, JsonValue]` for typed streams, or passed through
  * verbatim when `A` is [[ServerSentEvent]]).
  *
  * `None` ends the stream normally. A `pull` failure terminates the SSE response mid-flight — the
  * status and headers are already sent, so the client observes a truncated stream; sources should
  * therefore surface terminal conditions as `None` rather than errors.
  *
  * There is no completion or disconnect hook: when the response ends, the writer simply stops
  * pulling. Sources backed by producers (queues, subscriptions) must be bounded or self-cleaning.
  *
  * Pulls are sequential (one SSE writer), so a source does not need to be thread-safe.
  */
trait EventSource[A] { self =>

  /** The next event, or `None` when the stream is exhausted. */
  def pull: Eru[Throwable, Option[A]]

  final def map[B](f: A => B): EventSource[B] = new EventSource[B] {
    def pull: Eru[Throwable, Option[B]] = self.pull.map(_.map(f))
  }

  final def filter(p: A => Boolean): EventSource[A] = self.collect(a => Option.when(p(a))(a))

  final def collect[B](f: A => Option[B]): EventSource[B] = {
    def step(source: EventSource[A]): EventSource[B] = new EventSource[B] {
      def pull: Eru[Throwable, Option[B]] =
        source.pull.flatMap {
          case None => Eru.succeed(None)
          case Some(a) =>
            f(a) match {
              case Some(b) => Eru.succeed(Some(b))
              case None => step(source).pull
            }
        }
    }
    step(self)
  }

  /** Emits this source's events, then `that`'s. */
  final def ++[B >: A](that: EventSource[B]): EventSource[B] = new EventSource[B] {
    def pull: Eru[Throwable, Option[B]] =
      self.pull.flatMap {
        case None => that.pull
        case some => Eru.succeed(some)
      }
  }
}

object EventSource {

  /** An exhausted source. */
  def empty[A]: EventSource[A] = fromPull(Eru.succeed(None))

  /** A source backed by a pull effect; the effect is re-evaluated on every pull, so stateful pulls
    * (queue takes, broker receives, iterator advances) advance naturally.
    */
  def fromPull[A](source: => Eru[Throwable, Option[A]]): EventSource[A] = new EventSource[A] {
    def pull: Eru[Throwable, Option[A]] = source
  }

  /** A source over a lazy iterator of events. */
  def fromIterator[A](events: Iterator[A]): EventSource[A] = fromPull {
    Eru.succeed(Option.when(events.hasNext)(events.next()))
  }

  /** A source over a finite list of events. */
  def fromList[A](events: List[A]): EventSource[A] = fromIterator(events.iterator)

  /** A raw source over pre-formatted events — full control over `event:`, `id:`, and comments. */
  def fromServerSentEvents(events: List[ServerSentEvent]): EventSource[ServerSentEvent] =
    fromList(events)
}

/** A typed Server-Sent-Events response.
  *
  * The macro generates the stream from the [[EventSource]]: for a typed element type each event is
  * encoded via `Encoder[A, JsonValue]` and emitted as `ServerSentEvent.data`; when the element type
  * is [[ServerSentEvent]] itself, events pass through verbatim (raw mode).
  */
final case class EventStream[A](source: EventSource[A])
