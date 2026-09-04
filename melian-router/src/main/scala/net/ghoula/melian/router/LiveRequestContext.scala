package net.ghoula.melian.router

import java.util.UUID

import net.ghoula.eru.http.{Body, ClientAddress, Headers, Request}
import net.ghoula.melian.RequestContext

/** Concrete RequestContext built from a live HTTP request during dispatch. */
final case class LiveRequestContext(
  requestId: UUID,
  rawRequest: Request[Body],
  startTime: Long,
  warnings: List[String]
) extends RequestContext {
  def rawHeaders: Headers = rawRequest.headers

  /** Surfaced from the request: eru-http 1.0.0-alpha.2 resolves it per connection/proxy policy. */
  def clientAddress: Option[ClientAddress] = rawRequest.clientAddress
}

object LiveRequestContext {
  def from(request: Request[Body], warnings: List[String] = Nil): LiveRequestContext =
    LiveRequestContext(
      requestId = UUID.randomUUID(),
      rawRequest = request,
      startTime = System.nanoTime(),
      warnings = warnings
    )
}
