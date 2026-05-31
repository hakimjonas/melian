package net.ghoula.melian.router

import java.net.InetAddress
import java.util.UUID

import net.ghoula.eru.http.{Body, Headers, Request}
import net.ghoula.melian.RequestContext

/** Concrete RequestContext built from a live HTTP request during dispatch. */
final case class LiveRequestContext(
  requestId: UUID,
  rawRequest: Request[Body],
  remoteAddress: InetAddress,
  startTime: Long,
  warnings: List[String]
) extends RequestContext {
  def rawHeaders: Headers = rawRequest.headers
}

object LiveRequestContext {
  def from(request: Request[Body], warnings: List[String] = Nil): LiveRequestContext =
    LiveRequestContext(
      requestId = UUID.randomUUID(),
      rawRequest = request,
      remoteAddress = InetAddress.getLoopbackAddress,
      startTime = System.nanoTime(),
      warnings = warnings
    )
}
