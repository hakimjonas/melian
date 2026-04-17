package net.ghoula.melian

/** Source markers for endpoint parameters.
  *
  * Transparent type aliases that instruct the compile-time macro where to source each parameter.
  * At compile time the macro inspects TypeRepr to find these markers and determine extraction
  * strategy. At runtime they are identity — Path[UUID] IS UUID, no wrapping or unwrapping.
  *
  * This eliminates asInstanceOf at the handler call boundary: the macro extracts a UUID, and the
  * handler receives a UUID directly (since Path[UUID] = UUID).
  */

type Path[A] = A
type Query[A] = A
type Header[A] = A

/** Body decoded via Rumil JSON parse -> Sarati decode -> Valar validate. JSON only. */
type Json[A] = A

/** Body decoded via Rumil -> Sarati -> Valar pipeline with Content-Type dispatch. */
type Coded[A] = A

/** Body decoded from form-urlencoded data -> Valar validate. */
type Form[A] = A
