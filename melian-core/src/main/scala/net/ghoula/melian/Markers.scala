package net.ghoula.melian

/** Source markers for endpoint parameters.
  *
  * Transparent type aliases that instruct the compile-time macro where to source each parameter. At
  * compile time the macro inspects TypeRepr to find these markers and determine extraction
  * strategy. At runtime they are identity: Path[UUID] IS UUID, no wrapping or unwrapping.
  *
  * This eliminates asInstanceOf at the handler call boundary: the macro extracts a UUID, and the
  * handler receives a UUID directly (since Path[UUID] = UUID).
  */

type Path[A] = A
type Query[N <: String & Singleton, A] = A
type Header[A] = A

/** Body decoded via Rumil JSON parse -> Sarati decode -> Valar validate. JSON only.
  *
  * Parsing is resilient by default: a syntax error recovered at a structural boundary surfaces as a
  * warning (`RequestContext.warnings`, `X-Melan-Warnings`) rather than a rejection. Wrap the marker
  * in [[Strict]] to opt out: `Strict[Json[A]]` rejects the request on the first error instead.
  */
type Json[A] = A

/** Strict-parsing marker for body markers: `Strict[Json[A]]` (or `Strict[Coded[A]]`).
  *
  * No recovery -- the first syntax error (Rumil) or structural mismatch (Sarati) fails the request.
  * The wrapper is a transparent alias, so the handler parameter type stays the decoded type itself
  * and the marker exists only for the compile-time extractor.
  */
type Strict[Body] = Body

/** Body decoded via Rumil -> Sarati -> Valar pipeline with Content-Type dispatch. */
type Coded[A] = A

/** Body decoded from form-urlencoded data -> Valar validate. */
type Form[A] = A
