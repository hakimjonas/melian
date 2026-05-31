package net.ghoula.melian.router

/** Error formatting utilities for compile-time macro diagnostics.
  *
  * Follows the Arda convention: header + numbered details + hint.
  */
object MacroErrors {

  final case class MissingInstance(field: String, fieldType: String, typeclass: String, suggestion: String)

  def formatMissingInstances(
    method: String,
    path: String,
    handlerName: String,
    missing: List[MissingInstance]
  ): String = {
    val header =
      s"Cannot bind $method $path to $handlerName: missing typeclass instances for ${missing.size} parameter(s).\n"
    val details = missing.zipWithIndex.map { case (m, i) =>
      s"  ${i + 1}. Parameter '${m.field}' of type ${m.fieldType}\n" +
        s"     Missing: ${m.typeclass}\n" +
        s"     Add: ${m.suggestion}"
    }.mkString("\n\n")
    val hint = "\n\nHint: Sarati and Valar provide derive methods for case classes."
    header + "\n" + details + hint
  }

  def formatPathMismatch(
    method: String,
    path: String,
    templateParams: List[String],
    handlerParams: List[String]
  ): String = {
    val header = s"Cannot bind $method $path: path parameters do not match handler Path[_] parameters.\n"
    val details =
      s"  Path template parameters: ${templateParams.mkString(", ")}\n" +
        s"  Handler Path parameters:  ${handlerParams.mkString(", ")}"
    val hint = "\n\nHint: Rename the parameter or update the path template."
    header + "\n" + details + hint
  }

  def formatMethodConstraint(method: String, path: String, message: String, hint: String): String =
    s"Cannot bind $method $path:\n  $message\n\nHint: $hint"
}
