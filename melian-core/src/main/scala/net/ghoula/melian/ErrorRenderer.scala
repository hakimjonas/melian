package net.ghoula.melian

import net.ghoula.eru.Eru
import net.ghoula.eru.http.{Body, Response}

/** Typeclass for rendering domain errors into HTTP responses.
  *
  * Resolved as a single global given. A handler whose error type is `Nothing` cannot fail, so it is
  * covered by the no-op [[ErrorRenderer.Nothing]] instance.
  */
trait ErrorRenderer[E] {
  def render(error: E): Eru[Nothing, Response[Body]]
}

object ErrorRenderer {

  /** Handlers with error type Nothing can never fail, so this renderer is never called. */
  given ErrorRenderer[Nothing] with {
    def render(error: Nothing): Eru[Nothing, Response[Body]] = error
  }
}
