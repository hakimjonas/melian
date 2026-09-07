package net.ghoula.melian

import net.ghoula.eru.Eru

/** An endpoint is a context function producing an Eru effect.
  *
  * RequestContext is threaded implicitly via Scala 3 context functions, keeping business logic
  * signatures clean.
  */
type Endpoint[E, A] = RequestContext ?=> Eru[E, A]
