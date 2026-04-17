package net.ghoula.melian

/** Source markers for endpoint parameters.
  *
  * Opaque types that instruct the compile-time extractor where to source each parameter. At runtime
  * these erase to A — zero allocation, zero overhead. At compile time the macro inspects parameter
  * types via TypeRepr to determine the extraction strategy.
  */

opaque type Path[A] = A

object Path {
  def apply[A](value: A): Path[A] = value
  extension [A](p: Path[A]) { def value: A = p }
}

opaque type Query[A] = A

object Query {
  def apply[A](value: A): Query[A] = value
  extension [A](q: Query[A]) { def value: A = q }
}

opaque type Header[A] = A

object Header {
  def apply[A](value: A): Header[A] = value
  extension [A](h: Header[A]) { def value: A = h }
}

/** Body decoded via Rumil JSON parse -> Sarati decode -> Valar validate. JSON only. */
opaque type Json[A] = A

object Json {
  def apply[A](value: A): Json[A] = value
  extension [A](j: Json[A]) { def value: A = j }
}

/** Body decoded via Rumil -> Sarati -> Valar pipeline with Content-Type dispatch. */
opaque type Coded[A] = A

object Coded {
  def apply[A](value: A): Coded[A] = value
  extension [A](c: Coded[A]) { def value: A = c }
}

/** Body decoded from form-urlencoded data -> Valar validate. */
opaque type Form[A] = A

object Form {
  def apply[A](value: A): Form[A] = value
  extension [A](f: Form[A]) { def value: A = f }
}
