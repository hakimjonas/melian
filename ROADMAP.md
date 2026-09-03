# Melian Roadmap

Single source of truth for Melian's state and plan. Supersedes the old `NEXT_SESSION.md` and `PREREQUISITES.md`. Architecture detail lives in `DESIGN.md`.

## Current state (2026-09)

The Arda ecosystem is published to Maven Central under `net.ghoula`, GPL-3.0-or-later, on GitHub with branch protection:

| Library | Version | Purpose |
|---------|---------|---------|
| `eru` | 1.0.0-alpha.1 | Effect system on Java virtual threads |
| `eru-http` | 1.0.0-alpha.1 | HTTP client and server, RFC 6455 WebSockets |
| `sarati` | 1.0.0-alpha.2 | Binary codec, format ASTs, XPath evaluator |
| `rumil` | 1.0.0-alpha.3 | Parser combinators |
| `valar` | 0.6.0 | Validation |

Melian is part of the same ecosystem but not released:

- Dependencies resolve the published artifacts: `eru-http-server` at 1.0.0-alpha.1, `sarati` at 1.0.0-alpha.2, `rumil-parsers` at 1.0.0-alpha.3; `valar-core` at 0.6.0; `munit` at 1.3.5.
- Toolchain: sbt 2.0.7, Scala 3.8.4, JDK 25.
- Publishing is configured for Maven Central (`localStaging` + `sonaRelease`, signed by sbt-pgp); no tag is currently cut. (An earlier `v1.0.0-alpha` tag was cut at `2cfb08a` and removed along with its failed release run — nothing was ever published, so the version name is clean.)
- `main` branch, GPL-3.0-or-later license, GitHub workflows, and governance files are in place. Remote: `git@github.com:hakimjonas/melian.git`; CI runs on `main` pushes.
- Documentation: `README.md` (overview and quickstart), `DESIGN.md` (architecture, with the remaining planned sections marked), `CHANGELOG.md`, and this roadmap.
- All modules compile under `-Werror`; the test suite passes.

## Server middleware

`melian-server` ships composable middleware on `eru-http`'s `Middleware` type:

- `StaticFiles`, `SecurityHeaders`, `ErrorPages`, and `Health` (liveness/readiness).
- `Csrf`: double-submit cookie tokens, constant-time comparison, 403 on mismatch, token issuance on safe requests.
- `Session`: cookie sessions with pluggable `SessionStore` (in-memory default), typed `get`/`set` via Sarati codecs, invalidation, thread-local `Session.current` scoped to the request's virtual thread.
- `RateLimit`: fixed-window limiting per extracted key, pluggable `RateLimitStore`, 429 with `Retry-After`.

CORS, request logging, request IDs, auth, error handling, compression, and body limits come from `eru-http`'s `Middleware` and compose through `MelianServer.serveWith`.

## Router surface

- Methods: `get`, `head`, `post`, `put`, `delete`, `patch`, `query`. `QUERY` is the RFC 10008 safe, idempotent method; it carries its query as request content and therefore requires a request body (like `POST`). A `HEAD` route is auto-derived from its `GET` route (RFC 9110 9.3.2): same status and headers, no body, with the `Content-Length` preserved.
- Parameter markers: `Path[A]`, `Query[N, A]`, `Header[A]` for extraction; `Json[A]`, `Coded[A]`, `Form[A]` for request bodies; `Strict[Json[A]]` for strict (non-resilient) body parsing.
  - `Json[A]`: JSON only (Rumil parse → Sarati decode → Valar validate). Parsing is resilient by default; recovered errors become warnings.
  - `Coded[A]`: Content-Type dispatch across `application/json`, `application/xml`, `application/yaml`, then the same decode + validate pipeline. The three decoders are bundled as a `CodedDecoder[A]`, derivable for case classes (`CodedDecoder.derived`); a missing/unsupported Content-Type answers 415.
  - `Form[A]`: `application/x-www-form-urlencoded`, decoded by a `FormDecoder[A]` (derivable for case classes via `FormDecoder.derived`), then Valar validated. Form keys outside the decoder's `knownFields` become warnings.
- Response wrappers: `Ok[A]`, `Created[A]` (Location), `Accepted[A]`, `NoContent`, `SeeOther` (Location), `NotModified`, `EventStream[A]` (typed JSON events over a pull-based `EventSource[A]`, or raw `ServerSentEvent` passthrough), `Unauthorized[A]` (challenge → `WWW-Authenticate`), `TooManyRequests[A]` (duration → `Retry-After`), and `Status[Code, A]` for any other body-carrying status via a phantom literal (validated at compile time: in range, body allowed, no required headers).
- Error rendering, three tiers: an `ErrorRenderer[E]` given at the registration site (endpoint tier) beats the builder-level `.errorRenderer(...)` cursor (group/global tier), which beats the built-in RFC 9457 problem-details 500 fallback (no detail leakage).
- Response content negotiation: declaring a `Header[Accept]` parameter builds a `ResponseNegotiator` from the encoders that exist for the body type (`Encoder[A, JsonValue]` baseline; `Encoder[A, XmlNode]` / `Encoder[A, YamlValue]` opt-in). Q-value dispatch per RFC 9110 §12.5.1, server preference on ties, 406 problem+json when nothing matches. Routes without `Header[Accept]` keep the zero-overhead JSON path.
- Decode warnings: `X-Melian-Warnings: <n> decode warning(s)` on responses when lenient body parsing recovered anything; the same list reaches Endpoint handlers via `RequestContext.warnings`.
- WebSocket endpoints: `websocket(path, handler)` registers a typed `WebSocketEndpoint[In, Out]` (session with `receive`/`send`/`close`; inbound messages strictly decoded and validated, rejection closes with 1003). Upgrade via eru-http's RFC 6455 support under `GET`; plain GETs answer 426.

## OpenAPI

`melian-openapi` materializes an OpenAPI 3.1 spec from the same compile-time metadata that generates the extractors, so the spec cannot drift from the implementation:

- Parameters (path/query/header), request bodies with per-marker media types, and response schemas are all derived from types.
- Response headers are documented where the framework sets them (`Location` for `Created`/`SeeOther`, `WWW-Authenticate` for `Unauthorized`, `Retry-After` for `TooManyRequests`).
- Every operation documents its standard error responses (`400`, `404`, `405`, `422`, `500`) with the RFC 9457 `ProblemDetails` schema under `application/problem+json`.
- Top-level `servers` come from `OpenApiSpec.Info`; per-operation `summary`, `description`, and `tags` come from the builder (`get("/p", h, summary = "...", description = "...", tags = "a, b")`).
- `operationId` per operation: explicit via the builder's `operationId` parameter, otherwise derived deterministically from the method and path template (`getWorkspacesByWorkspaceId`). This is the contract `arda_openapi` pins.
- Negotiating routes document every offered response media type; WebSocket routes are marked with `x-websocket: true`.
- `OpenApiRoutes` serves the spec plus a Swagger UI page as composable middleware.

## Pending

### Melian release — deferred until the upstream findings land

Per the maintainer's decision (2026-09-03), the first public release waits until every Open item in
`../ecosystem-findings-from-melian.md` lands upstream, so v1 ships with a zero-workaround surface:

- eru-http: client address on `Request`; ACME → `TlsConfig`; the `Response.tooManyRequests`
  factory; `StatusCode` constants for 406/422/426; `Response.addCookie`; the WebSocket
  pending-handler registry audit; `CanEqual` givens next to the opaque types.
- rumil: the lossless JSON parse entry point (unblocks melian's GreenNode/RedTree mode).

(Finding 6 — shutdown concurrency — was withdrawn after source review: `NativeHttpServer.shutdown`
is CAS-guarded idempotent and the drain is tested in the hostile suite.)

Melian's half of the split findings is complete (see the findings document); the interim local
`CanEqual` givens are explicitly transitional and will be removed when eru-http ships its own.

Sequence when the upstream work is published:

1. Bump pins, adopt the new APIs, drop the workarounds; `sbt check` + full tests.
2. Re-cut `v1.0.0-alpha` on the final commit and push it (the earlier tag and its failed release run
   were removed — nothing was ever published, so the version name is clean).
3. Set `SONATYPE_USERNAME`/`SONATYPE_PASSWORD`; `release.yml` tests and publishes.
4. Set `RELEASE_TOKEN` last: from that moment any merged PR auto-cuts the next alpha tag and
   auto-publishes.

PGP secrets are already configured on the repository. While waiting, direct pushes to `main` are
safe: CI only — nothing publishes without a tag push, and tags come only from the sequence above.

## Planned

### Lossless parsing mode (blocked on upstream Rumil)

DESIGN.md §5, Stage 4 describes a GreenNode/RedTree mode for editor-style endpoints (live parse feedback, incremental reparse, source-position mapping). The lossless machinery (`GreenNodeOf`, `RedTree`, `IncrementalParser`) ships in `rumil-core`, but the published JSON parser does not expose a lossless entry point; the mode needs a Rumil increment (a `Language` instance and batch lossless parse for JSON) before Melian can wire the marker and endpoint mode.

### Upstream (`eru-http`)

Not Melian work; tracked here so it is not lost. The full findings -- evidence against the published
artifacts and suggested shapes -- live in `../ecosystem-findings-from-melian.md`:

- Client address on `Request`, so `RequestContext` can surface it (and `RateLimit` can default its key to the client address).
- ACME provisioning producing `TlsConfig` (TLS is `eru-http`'s domain; DESIGN.md §14.1 sketches the API).
- A `Response.tooManyRequests` factory, `StatusCode` constants for 406/422/426, a cookie-aware response API, a documented shutdown idempotency contract, a testable WebSocket upgrade seam, and `CanEqual` givens for the opaque types (details in the findings document).

### Definition of done

Each feature ships with tests, a CHANGELOG entry, and its DESIGN.md marker flipped from Planned to implemented; `sbt check` must pass. Notes for the session: `sbt test` behaves as `testQuick`, so use `sbt "router/Test/testOnly ..."` or a clean build for a full run; scalafix `DisableSyntax` bans `asInstanceOf`, `var`, `null`, and `throw`, so the documented erase-then-recover cast pattern carries a `// scalafix:ok DisableSyntax.asInstanceOf` suppression.

## Benchmark

A micro-benchmark compares Melian's dispatch against an equivalent hand-rolled `eru-http` handler, with an interpreter breakdown:

```
sbt "router/Test/runMain net.ghoula.melian.router.RouterBenchmark"
```

Last run (re-baselined after the planned-work cycle): the compiled router costs ~1.13 µs/op (no-param ~0.80 µs/op) against ~81 ns/op for the raw handler. The interpreter breakdown still shows the overhead is **not** `flatMap` (Eru's slow path is only ~39 ns/op); the multi-parameter `attempt.flatMap` error-accumulation pattern costs ~200 ns/op, and the rest of the gap is the effectful Sarati JSON encoding and the generated node count. Single-parameter routes take a fast path that skips the extraction `attempt` and its `asInstanceOf`; what remains is dominated by the response-encoding path (`withEncodedBody` + `formatJson`), which is effectful by design (encode errors and header validation are real failure modes). The response-encoding path gained an `X-Melian-Warnings` header check, which short-circuits to a no-op when there are no warnings. Worth revisiting only if per-request throughput becomes load-bearing.

## Known gaps

`asInstanceOf` remains in `RouteMacros.scala`, all in the same irreducible type-erasure category: values are erased to `Any` in the heterogeneous multi-parameter extraction chain and recovered by the `Type` carried alongside (the erase-then-recover pattern of Valar's named-tuple access). The single-parameter fast path is cast-free. The casts are, in the multi-parameter base case:

1. `v.asInstanceOf[a]`: non-body result-value recovery.
2. `v.asInstanceOf[DecodeResult[a]].value` and `.warnings`: JSON body value and decode-warning recovery.

Plus one unrelated cast: `None.asInstanceOf[A]` for an absent optional query parameter, where the macro typer does not propagate the `A =:= Option[t]` proof into the emitted term.

The former `Endpoint` (`?=>`) → `Function1` boundary is resolved with zero casts. The `Strict[Json[A]]` marker is likewise cast-free: the wrapper alias normalizes to the decoded type, so the handler parameter needs no marker conjunct recovery.

The client address is not exposed on `RequestContext` because `eru-http` does not put the remote address on `Request`; surfacing it is an `eru-http` follow-up, not a Melian gap.
