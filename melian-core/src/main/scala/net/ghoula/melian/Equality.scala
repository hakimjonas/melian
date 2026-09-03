package net.ghoula.melian

import net.ghoula.eru.http.{MediaType, Method, SameSite}

/** `CanEqual` instances for eru-http's opaque and enum types, so code compiled with
  * `-language:strictEquality` (as melian is) can compare them directly instead of through `.value`
  * strings.
  *
  * Transitional by design: when eru-http ships its own instances next to the types, this file is
  * removed — two identical givens in one scope (here and there) would make comparisons ambiguous
  * for consumers importing both.
  */
given CanEqual[Method, Method] = CanEqual.derived
given CanEqual[MediaType, MediaType] = CanEqual.derived
given CanEqual[SameSite, SameSite] = CanEqual.derived
