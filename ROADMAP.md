# Melian Roadmap

Single source of truth for Melian's state and plan. Supersedes the old `NEXT_SESSION.md` and `PREREQUISITES.md`. Architecture detail lives in `DESIGN.md`.

## Current state (2026-09)

The Arda ecosystem is published to Maven Central under `net.ghoula`, GPL-3.0-or-later, on GitHub with branch protection:

| Library | Version | Purpose |
|---------|---------|---------|
| `eru` | 1.0.0-alpha | Effect system on Java virtual threads |
| `eru-http` | 1.0.0-alpha | HTTP client and server |
| `sarati` | 1.0.0-alpha | Binary codec, format ASTs, XPath evaluator |
| `rumil` | 1.0.0-alpha | Parser combinators |

Melian is part of the same ecosystem but not released:

- Dependencies resolve the published artifacts: `eru-http`, `sarati`, `rumil` at 1.0.0-alpha; `valar` at 0.6.0; `munit` at 1.3.5.
- Toolchain: sbt 2.0.7, Scala 3.8.4, JDK 25.
- Publishing is configured for Maven Central (`localStaging` + `sonaRelease`, signed by sbt-pgp), but no tag has been cut.
- `main` branch, GPL-3.0-or-later license, GitHub workflows, and governance files are in place. The repository has no remote yet.
- Documentation: `README.md` (overview and quickstart), `DESIGN.md` (architecture, with unimplemented sections marked Planned), `CHANGELOG.md`, and this roadmap.
- All modules compile under `-Werror`; the test suite passes.

## Server middleware

`melian-server` ships composable middleware on `eru-http`'s `Middleware` type: `StaticFiles`, `SecurityHeaders`, `ErrorPages`, and `Health` (liveness/readiness). CORS, request logging, request IDs, auth, error handling, compression, and body limits come from `eru-http`'s `Middleware` and compose through `MelianServer.serveWith`.

## Router surface

- Methods: `get`, `head`, `post`, `put`, `delete`, `patch`, `query`. `QUERY` is the RFC 10008 safe, idempotent method; it carries its query as request content and therefore requires a request body (like `POST`). A `HEAD` route is auto-derived from its `GET` route (RFC 9110 9.3.2): same status and headers, no body, with the `Content-Length` preserved.
- Parameter markers: `Path[A]`, `Query[N, A]`, `Header[A]` for extraction; `Json[A]`, `Coded[A]`, `Form[A]` for request bodies.
  - `Json[A]`: JSON only (Rumil parse → Sarati decode → Valar validate).
  - `Coded[A]`: Content-Type dispatch across `application/json`, `application/xml`, `application/yaml`, then the same decode + validate pipeline. The three decoders are bundled as a `CodedDecoder[A]`, derivable for case classes (`CodedDecoder.derived`); a missing/unsupported Content-Type answers 415.
  - `Form[A]`: `application/x-www-form-urlencoded`, decoded by a `FormDecoder[A]` (derivable for case classes via `FormDecoder.derived`), then Valar validated.
- Response wrappers: `Ok[A]`, `Created[A]` (Location), `Accepted[A]`, `NoContent`, `SeeOther` (Location), `NotModified`, `EventStream[A]`.

## OpenAPI

`melian-openapi` materializes an OpenAPI 3.1 spec from the same compile-time metadata that generates the extractors, so the spec cannot drift from the implementation:

- Parameters (path/query/header), request bodies with per-marker media types, and response schemas are all derived from types.
- Response headers are documented where the framework sets them (`Location` for `Created`/`SeeOther`).
- Every operation documents its standard error responses (`400`, `404`, `405`, `422`, `500`) with the RFC 9457 `ProblemDetails` schema under `application/problem+json`.
- Top-level `servers` come from `OpenApiSpec.Info`; per-operation `summary`, `description`, and `tags` come from the builder (`get("/p", h, summary = "...", description = "...", tags = "a, b")`).
- `OpenApiRoutes` serves the spec plus a Swagger UI page as composable middleware.

## Pending

### Melian release

Happens after the Planned backlog below is complete:

1. Create the GitHub repository and push `main`.
2. Cut `v1.0.0-alpha`; the release workflow publishes `melian-core`, `melian-router`, `melian-openapi`, `melian-server`, and `melian-test`.

Once the remote exists, the auto-tag workflow cuts an alpha tag per merged PR, so releases after the first are automatic.

## Planned

Features designed but not yet implemented. DESIGN.md marks the corresponding sections (referenced below); this list consolidates them.

- Real-time: WebSocket endpoints on top of `eru-http`'s RFC 6455 support (DESIGN.md §9).
- Content negotiation: response-side negotiation via a declared `Header[Accept]` parameter, with q-value dispatch (§10.2).
- Error handling: three-tier `ErrorRenderer` precedence (global, per-group, per-endpoint) with a builder API (§6, "Error Rendering"); `Unauthorized` and `TooManyRequests` response wrappers (§6, "Protocol Enforcement"); a `Status[Code]` phantom type for custom statuses (§3.3).
- Parsing: resilient parsing as the Girdle default with a `Json[A, Strict]` opt-out, plus a lossless GreenNode/RedTree mode for editor-style endpoints (§5, Stage 4); an `X-Melian-Warnings` response header and decode warnings for `Coded[A]` and `Form[A]` bodies (§16.4).
- Convenience layer, as `melian-server` middleware: CSRF (§14.6), session management (§14.7), rate limiting (§14.8), and a graceful-shutdown wrapper (§14.9).
- Client generation contract: `arda_openapi` (a separate Dart project; its increment 1, the OpenAPI 3.1 reader, is complete and pushed) consumes Melian's OpenAPI 3.1 output. Melian emits a stable `operationId` per operation, and coordinates with `arda_openapi`'s increments on any further spec-shape needs (for example `securitySchemes` once auth exists). The generator itself is that project's deliverable; Melian stays frontend-agnostic, and the OpenAPI spec is the contract for any client tooling.

### Upstream (`eru-http`)

Not Melian work; tracked here so it is not lost:

- Client address on `Request`, so `RequestContext` can surface it.
- ACME provisioning producing `TlsConfig` (TLS is `eru-http`'s domain; DESIGN.md §14.1 sketches the API).

### Suggested order

1. Self-contained core work: `Unauthorized`, `TooManyRequests`, and `Status[Code]` (all touch ResponseTypes, `encodeResponse`, `SchemaGen`, and ProblemDetails); the `X-Melian-Warnings` header; decode warnings for `Coded` and `Form` bodies (extends the existing `DecodeResult` pattern).
2. Design pass before code: three-tier `ErrorRenderer` (changes the macro's given resolution model); response content negotiation (multi-`Encoder` dispatch, 406 semantics, OpenAPI multi-content responses); resilient parsing as default (`Json[A, Strict]` changes the marker arity and touches every route).
3. Feature surfaces: WebSocket (needs its own design pass for the marker and return type); CSRF, session, rate limiting, and graceful shutdown as `melian-server` middleware; `operationId` emission.
4. External: the two `eru-http` items above.

### Definition of done

Each feature ships with tests, a CHANGELOG entry, and its DESIGN.md marker flipped from Planned to implemented; `sbt check` must pass. Notes for the session: `sbt test` behaves as `testQuick`, so use `sbt "router/Test/testOnly ..."` or a clean build for a full run; scalafix `DisableSyntax` bans `asInstanceOf`, `var`, `null`, and `throw`, so the documented erase-then-recover cast pattern carries a `// scalafix:ok DisableSyntax.asInstanceOf` suppression; the CI workflows are being reworked and may differ from the current files.

## Benchmark

A micro-benchmark compares Melian's dispatch against an equivalent hand-rolled `eru-http` handler, with an interpreter breakdown:

```
sbt "router/Test/runMain net.ghoula.melian.router.RouterBenchmark"
```

Last run: the compiled router cost ~1.1 µs/op (no-param ~0.77 µs/op) against ~87 ns/op for the raw handler. The breakdown shows the overhead is **not** `flatMap` (Eru's slow path is only ~40 ns/op); it is the `attempt.flatMap` error-accumulation pattern (~204 ns/op) plus the effectful Sarati JSON encoding and the generated node count. Single-parameter routes take a fast path that skips the extraction `attempt` and its `asInstanceOf`; what remains is dominated by the response-encoding path (`withEncodedBody` + `formatJson`), which is effectful by design (encode errors and header validation are real failure modes). Worth revisiting only if per-request throughput becomes load-bearing.

## Known gaps

`asInstanceOf` remains in `RouteMacros.scala`, all in the same irreducible type-erasure category: values are erased to `Any` in the heterogeneous multi-parameter extraction chain and recovered by the `Type` carried alongside (the erase-then-recover pattern of Valar's named-tuple access). The single-parameter fast path is cast-free. The casts are, in the multi-parameter base case:

1. `v.asInstanceOf[a]`: non-body result-value recovery.
2. `v.asInstanceOf[DecodeResult[a]].value` and `.warnings`: JSON body value and decode-warning recovery.

Plus one unrelated cast: `None.asInstanceOf[A]` for an absent optional query parameter, where the macro typer does not propagate the `A =:= Option[t]` proof into the emitted term.

The former `Endpoint` (`?=>`) → `Function1` boundary is resolved with zero casts.

The client address is not exposed on `RequestContext` because `eru-http` does not put the remote address on `Request`; surfacing it is an `eru-http` follow-up, not a Melian gap.
