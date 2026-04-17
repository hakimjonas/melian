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
