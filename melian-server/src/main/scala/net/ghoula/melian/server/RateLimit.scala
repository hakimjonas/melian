package net.ghoula.melian.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*

/** Request rate limiting over a fixed window per key.
  *
  * Each request records a hit for its key (client identity, API key, tenant -- whatever the
  * keyExtractor derives; the default keys on the resolved client address that eru-http
  * 1.0.0-alpha.2 exposes as `Request.clientAddress`, falling back to a global key for requests
  * without one, e.g. in tests). Over-limit requests answer 429 Too Many Requests via eru-http's
  * `Response.tooManyRequests` factory, so the `Retry-After` header carries the seconds left in the
  * window.
  *
  * The in-memory store is per-process; for distributed deployments implement [[RateLimitStore]]
  * against a shared backend (the trait is deliberately tiny).
  */
object RateLimit {

  /** The outcome of recording one hit for a key. */
  enum RateLimitResult derives CanEqual {
    case Allowed(remaining: Int)
    case Limited(retryAfterSeconds: Long)
  }

  /** Storage for per-key fixed windows. Must be thread-safe; `hit` is atomic per key. */
  trait RateLimitStore {
    def hit(key: String, window: Duration, limit: Int): RateLimitResult
  }

  /** Thread-safe in-memory fixed-window store. The per-key window rolls from the first hit in the
    * window. Tracking is bounded: once `maxTrackedKeys` keys are held, a hit opportunistically
    * evicts windows untouched for a full window (they are dead -- the next hit for their key would
    * start a new one anyway), so high-cardinality key extractors cannot grow the map unboundedly.
    */
  final class InMemoryRateLimitStore(maxTrackedKeys: Int = 65_536) extends RateLimitStore {
    private final case class Window(startNanos: Long, count: Int, lastSeenNanos: Long)
    private val windows = new ConcurrentHashMap[String, Window]()

    private[server] def trackedKeys: Int = windows.size()

    def hit(key: String, window: Duration, limit: Int): RateLimitResult = {
      val now = System.nanoTime()
      val windowNanos = window.toNanos
      if windows.size() > maxTrackedKeys then evictExpired(now, windowNanos)
      // compute is atomic per key; the decision is captured through the reference so the mapping
      // function itself stays side-effect free.
      val outcome = new AtomicReference[RateLimitResult](RateLimitResult.Allowed(0))
      windows.compute(
        key,
        (_, existing) => {
          val current = existing match {
            case w: Window if now - w.startNanos < windowNanos => w
            case _ => Window(now, 0, now)
          }
          if current.count < limit then {
            outcome.set(RateLimitResult.Allowed(limit - current.count - 1))
            Window(current.startNanos, current.count + 1, now)
          } else {
            val elapsed = now - current.startNanos
            val remainingSeconds = math.max(0L, (windowNanos - elapsed + 999_999_999L) / 1_000_000_000L)
            outcome.set(RateLimitResult.Limited(remainingSeconds))
            Window(current.startNanos, current.count, now)
          }
        }
      )
      outcome.get()
    }

    private def evictExpired(now: Long, windowNanos: Long): Unit = {
      val entries = windows.entrySet().iterator()
      while entries.hasNext do {
        val entry = entries.next()
        if now - entry.getValue.lastSeenNanos > windowNanos then entries.remove()
      }
    }
  }

  final case class Config(
    limit: Int,
    window: Duration = 1.minute,
    keyExtractor: Request[Body] => String = (request: Request[Body]) =>
      request.clientAddress.fold("global")(_.hostAddress),
    store: RateLimitStore = InMemoryRateLimitStore()
  )

  def middleware(config: Config)(
    inner: Request[Body] => Eru[HttpError, Response[Body]]
  ): Request[Body] => Eru[HttpError, Response[Body]] = { (request: Request[Body]) =>
    config.store.hit(config.keyExtractor(request), config.window, config.limit) match {
      case RateLimitResult.Allowed(_) => inner(request)
      case RateLimitResult.Limited(retryAfter) =>
        // eru-http 1.0.0-alpha.2 ships the tooManyRequests factory (finding 3): it renders
        // non-negative delay-seconds and fails on negative delays, so the error channel here
        // widens to HttpError via the same InvalidResponse mapping.
        Response
          .tooManyRequests(java.time.Duration.ofSeconds(retryAfter), Body.text("Rate limit exceeded"))
          .mapError { case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
            HttpError.InvalidResponse(InvalidResponse(err.toString, "Retry-After header"))
          }
    }
  }
}
