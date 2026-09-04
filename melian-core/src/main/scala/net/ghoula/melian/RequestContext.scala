package net.ghoula.melian

import java.util.UUID

import net.ghoula.eru.http.{Body, ClientAddress, Headers, Request}

/** Ambient request state threaded via Scala 3 context functions.
  *
  * Available implicitly in endpoint handlers without polluting business logic signatures.
  */
trait RequestContext {
  def requestId: UUID
  def rawRequest: Request[Body]
  def rawHeaders: Headers

  /** The client address eru-http resolved for this request (TCP peer, PROXY-protocol-derived, or
    * trusted-XFF-derived). `None` only for requests that never went through a live server.
    */
  def clientAddress: Option[ClientAddress]
  def startTime: Long
  def warnings: List[String]
}
