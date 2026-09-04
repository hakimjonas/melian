package net.ghoula.melian

/** Header names Melian itself emits. eru-http owns the standard names; this object carries only the
  * framework-specific ones.
  */
object MelianHeaders {

  /** Signals that a request body was decoded leniently and produced warnings. The value is the
    * warning count in words, e.g. `2 decode warnings`; the same list is available to Endpoint
    * handlers through `RequestContext.warnings`. Only present when there is at least one warning.
    */
  val Warnings: String = "X-Melian-Warnings"
}
