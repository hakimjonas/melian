# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-alpha] - 2026-09

First public release. Five modules: `melian-core`, `melian-router`, `melian-openapi`, `melian-server`, `melian-test`.

### Added

- Compile-time router with `get`, `head`, `post`, `put`, `delete`, `patch`, and `query` (RFC 10008) methods.
- Typed parameter markers: `Path[A]`, `Query[name, A]`, `Header[A]`, `Json[A]`, `Coded[A]`, `Form[A]`, and the `Strict[Json[A]]` strict-parsing opt-out.
- Typed response wrappers: `Ok[A]`, `Created[A]`, `Accepted[A]`, `NoContent`, `SeeOther`, `NotModified`, `EventStream[A]`, `Unauthorized[A]` (emits `WWW-Authenticate`), `TooManyRequests[A]` (emits `Retry-After`), and `Status[Code, A]` for any other body-carrying status via a phantom literal, validated at compile time.
- Three-tier error rendering: an `ErrorRenderer[E]` given at the registration site (endpoint tier), a builder-level `.errorRenderer(...)` cursor for groups of routes (group/global tier), and a built-in RFC 9457 problem-details 500 fallback that never leaks the error value.
- Response content negotiation: a declared `Header[Accept]` parameter builds a negotiator from the encoders that exist for the body type (JSON baseline; XML and YAML opt-in), with RFC 9110 q-value dispatch, server preference on ties, and 406 problem+json when nothing matches.
- Resilient JSON body parsing by default with recovered errors surfaced as warnings; `Strict[Json[A]]` rejects on the first error instead.
- Decode warnings for all body markers: `X-Melian-Warnings: <n> decode warning(s)` on responses and `RequestContext.warnings` for Endpoint handlers. `Coded[A]` surfaces recovered parse/decode errors; `Form[A]` surfaces form keys outside the decoder's `knownFields`.
- Typed WebSocket endpoints (`websocket(path, handler)`): `WebSocketEndpoint[In, Out]` over a `WebSocketSession` with typed `receive`/`send`/`close`, inbound messages strictly decoded and validated (rejection closes with 1003), RFC 6455 upgrade via eru-http under `GET`, 426 Upgrade Required for plain GETs, and marked-parameter extraction from the upgrade request.
- `operationId` per OpenAPI operation: explicit on the route or derived deterministically from the method and path template.
- OpenAPI: response headers documented for `Unauthorized`/`TooManyRequests`, negotiating routes document every offered media type, and WebSocket routes carry `x-websocket: true`.
- Middleware for CSRF protection (double-submit cookie, constant-time comparison), cookie sessions (pluggable `SessionStore`, typed values via Sarati codecs, invalidation), and rate limiting (fixed window per key — the default key is the resolved client address — pluggable `RateLimitStore`, 429 with `Retry-After`).
- `MelianServer.start` with graceful shutdown: drain state, health-path 503 during drain (configurable), JVM shutdown hook, and explicit `RunningServer.stop`.
- RFC 9457 problem-details rendering for extraction, parse, decode, and validation errors.
- `ErrorRenderer[E]` for rendering domain errors into responses.
- OpenAPI 3.1 generation from compile-time route metadata, with `servers`, per-operation `summary`/`description`/`tags`, response headers, and standard error responses.
- `HEAD` auto-derivation from `GET` routes, including `Content-Length` preservation.
- Duplicate-route detection at router-build time (a repeated path+method, or routes differing only in parameter name, fails `build`).
- `Coded[A]` content-type dispatch across JSON, XML, and YAML; `CodedDecoder.derived` and `FormDecoder.derived` for case classes.
- Decode warnings for `Json[A]` bodies, surfaced through `RequestContext.warnings` for Endpoint handlers.
- Middleware for static files (with ETag and `If-None-Match`/`If-Modified-Since` conditional requests), security headers, error pages, and health/readiness endpoints.
- `MelianTestKit` for exercising routers without a running server, plus `postForm` (form-encoded POSTs) and `websocket` (end-to-end testing of compiled WebSocket routes over a real socket) helpers.
- A micro-benchmark (`RouterBenchmark`) comparing dispatch against a raw `eru-http` handler.
- `RequestContext.clientAddress`: the client address eru-http resolved for the request (TCP peer, PROXY-protocol-derived, or trusted-XFF-derived).
- `MelianServer.withAcme`: ACME provisioning via `eru-http-acme` (Let's Encrypt staging/production or a custom directory) — HTTP-01 challenge listener, PKCS12 keystore, renewal before expiry — wired into `HttpServerConfig.withTls`.
- Strict-equality support comes from eru-http 1.0.0-alpha.2's own `CanEqual` givens next to the types; endpoints compiled with `-language:strictEquality` compare `Method`/`MediaType`/`StatusCode` and friends directly.

### Changed

- `EventStream[A]` now wraps a pull-based `EventSource[A]` instead of a pre-built `ChunkStream`: typed events are JSON-encoded via `Encoder[A, JsonValue]` and emitted as `ServerSentEvent.data`, while a source of pre-formatted `ServerSentEvent`s passes through verbatim (construct with `EventSource.fromServerSentEvents`, `fromList`, `fromIterator`, or `fromPull`; combinators `map`/`filter`/`collect`/`++` included). A source failure terminates the SSE response mid-flight; there is no completion hook, so producer-backed sources must be bounded or self-cleaning.
- A handler whose error type has no `ErrorRenderer` given in scope no longer fails compilation outright: the route resolves through the builder-level renderer (if one is active) and then the built-in problem-details 500 fallback. Endpoints that previously relied on the compile error to surface a missing renderer should rely on a given or a builder-level renderer as before.
- `Status[Code, A]` routes resolve their status code once at route construction instead of on every request.
- Content-negotiation tie-breaking is RFC 9110 §12.5.2-exact: among entries sharing the best q-value, a more specific media range (`type/subtype`) now beats a less specific one (`type/*`, `*/*`); the server's preference order decides only after specificity. Entries with `q=0` are unacceptable and answer 406 instead of being served.
- Sessions are lazy: the session cookie is issued exactly when the session is persisted (first write) or invalidated; a request that never touches the session neither stores nor sends anything. A client-presented id the store does not know is never reused -- the session is minted under a fresh id when first written (session-fixation hygiene).

### Fixed

- `Form[A]` bodies decoded by hand-written `FormDecoder`s (which declare no `knownFields`) no longer emit a spurious `unknown form field` warning for every field; the unknown-key check only runs for decoders that declare their fields.
- `Csrf` and `Session` issue their cookies through `Response.addCookie`, which appends to `Set-Cookie`, so composing both middlewares no longer clobbers one middleware's cookie with the other's (Set-Cookie is multi-valued; RFC 9110 §5.5 exempts it from list combination).
- WebSocket routes reject body markers (`Json[A]`, `Coded[A]`, `Form[A]`) at compile time: the upgrade request is a GET and can never carry a body.
- `RunningServer` exposes `address`, the host and port the server actually bound -- a config asking for port 0 now reports the assigned ephemeral port.
- `RateLimit.InMemoryRateLimitStore` bounds its key tracking (`maxTrackedKeys`, default 65536) and opportunistically evicts windows untouched for a full window, so high-cardinality key extractors cannot grow the map without bound.
