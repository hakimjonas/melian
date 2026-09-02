package net.ghoula.melian

import java.util.UUID

import net.ghoula.eru.http.{Body, Headers, Request}

/** Ambient request state threaded via Scala 3 context functions.
  *
  * Available implicitly in endpoint handlers without polluting business logic signatures.
  */
trait RequestContext {
  def requestId: UUID
  def rawRequest: Request[Body]
  def rawHeaders: Headers
  def startTime: Long
  def warnings: List[String]
}
