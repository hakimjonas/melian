package net.ghoula.melian

import net.ghoula.eru.Eru
import net.ghoula.eru.http.{Body, Response}

/** Typeclass for rendering domain errors into HTTP responses.
  *
  * Three-tier precedence: endpoint-level given > group-level > global. Innermost wins.
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
