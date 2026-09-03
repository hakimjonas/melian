package net.ghoula.melian.server

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.concurrent.duration.Duration

import net.ghoula.eru.Eru
import net.ghoula.eru.http.*
import net.ghoula.sarati.ast.json.JsonValue
import net.ghoula.sarati.codec.{Decoder, Encoder}

/** Cookie-based session management with pluggable storage.
  *
  * The middleware resolves the session (from the session cookie, or freshly created), scopes it to
  * the request's virtual thread via [[Session.current]], and persists changes after the handler
  * completes. Handlers read and write typed values through the handle; storage is delegated to a
  * [[SessionStore]] -- the in-memory default for development, with distributed backends slotting in
  * behind the same trait.
  *
  * The thread-local is the only channel that can carry session state into a compiled Melian
  * handler: the RequestContext is built inside the generated route closure, which runs after the
  * middleware has resolved the session. Each request executes on its own virtual thread, so the
  * scoping is per-request by construction.
  */
object Session {

  final case class Config(
    cookieName: String = "sid",
    maxAge: Duration = 24.hours,
    secure: Boolean = true,
    httpOnly: Boolean = true,
    sameSite: SameSite = SameSite.Lax,
    store: SessionStore = InMemorySessionStore()
  )

  /** Session payload: a flat map of JSON-encoded values. */
  final case class SessionData(values: Map[String, String] = Map.empty) {
    def get(key: String): Option[String] = values.get(key)

    /** Reads a JSON-encoded value. */
    def getAs[A](key: String)(using decoder: Decoder[JsonValue, A]): Option[A] =
      values.get(key).flatMap { raw =>
        parsers.json.parseJson(raw) match {
          case parser.core.Result.Success(json, _) =>
            decoder.decode(json) match {
              case net.ghoula.sarati.Result.Success(value, _) => Some(value)
              case _ => None
            }
          case _ => None
        }
      }

    /** Stores a JSON-encoded value. */
    def set[A](key: String, value: A)(using encoder: Encoder[A, JsonValue]): SessionData =
      copy(values =
        values + (key -> net.ghoula.sarati.ast.json.formatJson(
          encoder.encode(value),
          net.ghoula.sarati.ast.json.compactFormat
        ))
      )

    def remove(key: String): SessionData = copy(values = values - key)
  }

  /** Storage backend for session payloads. All operations are effects on the HttpError channel so a
    * failing store fails the request like any other infrastructure error.
    */
  trait SessionStore {
    def load(id: String): Eru[HttpError, Option[SessionData]]
    def save(id: String, data: SessionData, ttl: Duration): Eru[HttpError, Unit]
    def remove(id: String): Eru[HttpError, Unit]
  }

  /** Thread-safe in-memory store. Expired entries are evicted lazily on access; a restart loses
    * sessions by design -- swap in a persistent [[SessionStore]] for production.
    */
  final class InMemorySessionStore extends SessionStore {
    private final case class Entry(data: SessionData, expiresAtNanos: Long)
    private val sessions = new ConcurrentHashMap[String, Entry]()
    private val random = new SecureRandom()

    private def newId: String = {
      val bytes = new Array[Byte](24)
      random.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }

    def load(id: String): Eru[HttpError, Option[SessionData]] = Eru.effect {
      Option(sessions.get(id)).flatMap { entry =>
        if System.nanoTime() > entry.expiresAtNanos then {
          sessions.remove(id)
          None
        } else Some(entry.data)
      }
    }.mapError(e => HttpError.NetworkError(s"Session store failure: ${e.getMessage}", Some(e)))

    def save(id: String, data: SessionData, ttl: Duration): Eru[HttpError, Unit] = Eru.effect {
      val _ = sessions.put(id, Entry(data, System.nanoTime() + ttl.toNanos))
    }.mapError(e => HttpError.NetworkError(s"Session store failure: ${e.getMessage}", Some(e)))

    def remove(id: String): Eru[HttpError, Unit] = Eru.effect {
      val _ = sessions.remove(id)
    }.mapError(e => HttpError.NetworkError(s"Session store failure: ${e.getMessage}", Some(e)))

    /** A fresh session id; exposed for the middleware so ids share the store's entropy source. */
    private[server] def generateId: String = newId
  }

  /** A per-request session handle. Mutations are recorded and flushed by the middleware. */
  final class Handle private[server] (
    val id: String,
    initial: SessionData
  ) {
    private val data = new AtomicReference(initial)
    private val modified = new AtomicBoolean(false)
    private val invalidated = new AtomicBoolean(false)

    def get(key: String): Option[String] = data.get().get(key)

    /** Reads a JSON-encoded value. */
    def getAs[A](key: String)(using decoder: Decoder[JsonValue, A]): Option[A] = data.get().getAs(key)

    /** Writes a JSON-encoded value; persisted after the response is produced. */
    def set[A](key: String, value: A)(using encoder: Encoder[A, JsonValue]): Unit = {
      val _ = data.updateAndGet(_.set(key, value))
      modified.set(true)
    }

    def remove(key: String): Unit = {
      val _ = data.updateAndGet(_.remove(key))
      modified.set(true)
    }

    /** Drops the session entirely: the store entry is removed and the cookie is expired. */
    def invalidate(): Unit = invalidated.set(true)

    private[server] def isModified: Boolean = modified.get()
    private[server] def isInvalidated: Boolean = invalidated.get()
    private[server] def snapshot: SessionData = data.get()
  }

  private val currentHandle: ThreadLocal[Option[Handle]] = ThreadLocal.withInitial(() => None)

  /** The session handle for the request executing on the current thread, if the session middleware
    * is installed. {{{val session = Session.current session.foreach(_.set("user", user))}}}
    */
  def current: Option[Handle] = currentHandle.get()

  def middleware(config: Config = Config())(
    inner: Request[Body] => Eru[HttpError, Response[Body]]
  ): Request[Body] => Eru[HttpError, Response[Body]] = { (request: Request[Body]) =>
    val store = config.store

    def cookieId: Option[String] = request.headers.getFirst(HeaderNames.Cookie).flatMap { cookieHeader =>
      Cookie.parseCookie(cookieHeader.value).attempt.unsafeRunSync() match {
        case net.ghoula.eru.Result.Success(cookies) => cookies.find(_.name == config.cookieName).map(_.value)
        case net.ghoula.eru.Result.Failure(_) => None
      }
    }

    def setCookie(response: Response[Body], value: String, expired: Boolean): Eru[HttpError, Response[Body]] = {
      val cookie = Cookie(
        name = config.cookieName,
        value = value,
        path = Some("/"),
        maxAge = Some(if expired then 0 else config.maxAge.toSeconds),
        secure = config.secure,
        httpOnly = config.httpOnly,
        sameSite = Some(config.sameSite)
      )
      // addHeader, not setHeader: Set-Cookie is multi-valued, and other cookie-issuing middleware
      // (e.g. Csrf) may add its own cookie to the same response.
      response
        .addHeader(HeaderNames.SetCookie, cookie.toSetCookieHeader)
        .mapError { case err: (HeaderName.InvalidHeaderName | HeaderValue.InvalidHeaderValue) =>
          HttpError.InvalidResponse(InvalidResponse(err.toString, "Set-Cookie header"))
        }
    }

    // A presented id that the store does not know must never be reused for the fresh session:
    // an attacker who pre-seeds a known sid would otherwise get the victim's session stored under
    // it (session fixation). The store-miss path generates a new id, and the client receives it
    // via Set-Cookie (the handle's id is no longer the requested one).
    val resolve: Eru[HttpError, Handle] = cookieId match {
      case Some(id) =>
        store.load(id).flatMap {
          case Some(data) => Eru.succeed(Handle(id, data))
          case None => freshHandle(store)
        }
      case None => freshHandle(store)
    }

    resolve.flatMap { handle =>
      currentHandle.set(Some(handle))
      inner(request).attempt.flatMap { result =>
        currentHandle.remove()
        result match {
          case net.ghoula.eru.Result.Failure(err) => Eru.fail(err)
          case net.ghoula.eru.Result.Success(response) =>
            val persisted =
              if handle.isInvalidated then
                store.remove(handle.id).flatMap { _ =>
                  setCookie(response, "", expired = true)
                }
              else if handle.isModified then
                store.save(handle.id, handle.snapshot, config.maxAge).flatMap { _ =>
                  setCookie(response, handle.id, expired = false)
                }
              else Eru.succeed(response)
            // Sessions are lazy: the cookie is issued exactly when the session is persisted
            // (first write, or a write refreshing an existing one) or invalidated. A request that
            // never touches the session neither stores nor sends anything.
            persisted
        }
      }
    }
  }

  private val idRandom = new SecureRandom()

  private def newId(): String = {
    val bytes = new Array[Byte](24)
    idRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def freshHandle(store: SessionStore): Eru[HttpError, Handle] = {
    val id = store match {
      case mem: InMemorySessionStore => mem.generateId
      case _ => newId()
    }
    Eru.succeed(Handle(id, SessionData()))
  }
}
