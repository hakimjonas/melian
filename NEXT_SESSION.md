# Melian — Next Session Pickup Guide

## Current State (2026-04-17)

**24 source files across 5 modules, 11 passing tests, zero warnings.**

All code is on `master` branch. Run `sbt "router/test"` to verify.

### What works end-to-end:

```scala
val router = Router.builder
  .get("/users/:id", (id: Path[UUID]) => Eru.succeed(Ok(s"user-$id")))
  .post("/workspaces/:id", (id: Path[UUID], auth: Header[BearerToken], cmd: Json[CreateCommand]) =>
    Eru.succeed(Ok(Workspace(id, cmd.name))))
  .build
  .getOrElse(sys.error("route build failed"))

// Mount on eru-http server:
MelianServer.serve(router) { server => server.start.map(addr => println(s"Listening on $addr")) }
```

- Compile-time macro pipeline: path parsing, handler introspection, method constraints, typeclass summoning
- Arbitrary arity via `Apply(Select.unique(handler.asTerm, "apply"), args)` — no per-arity code
- Error accumulation across all extractions (attempt + sequence + partition)
- ErrorRenderer[E] for domain errors, RFC 9457 ProblemDetails for pipeline errors
- ErrorSanitizer (dev/production modes)
- SaratiBridge: BodyEncoder/BodyDecoder bridging Sarati↔eru-http
- Endpoint/RequestContext with context function support
- Content-Type validation, JSON depth guard (64 levels)
- Created[A] with auto-derived Location header from path template

### Remaining asInstanceOf (2):
1. `v.asInstanceOf[a]` — Result value extraction where `a` known from `Type[?]`. Caused by `List[MEru[Any]]` for `Eru.sequence`. Typed alternative blocked by Scala 3.8.3 compiler backend bug with deeply nested closures.
2. Context function boundary — `Endpoint` (`?=>`) to `Function1`. Scala language boundary, irreducible.

## What to Build Next

### 1. Query[A] Extraction
**Design needed.** The problem: query param names must come from somewhere. Options:
- Add `paramName: String` to `FromQueryParam` trait (like `FromHeader.headerName`)
- Require wrapper types: `case class Page(value: Int)` with `given FromQueryParam[Page]`
- Use handler `def` parameter names (requires term-level tree inspection)
- Type-level string: `Query[("page", Int)]` using named tuple syntax

Most consistent with existing design: mirror FromHeader pattern with `paramName` in the typeclass.

### 2. Valar Validator Integration
The Girdle pipeline currently skips validation (Stage 6 in DESIGN.md). Wire `Validator[T]` from Valar into the Json body extraction, after Sarati decode. The SaratiBridge `decodeWithWarnings` already returns `DecodeResult[A]` — add validation step after decode. Valar is already a dependency of melian-router.

### 3. Database Integration (following eru-nats pattern)
DESIGN.md Addendum A discusses persistence. The eru-nats pattern establishes conventions:
- **Trait-based abstractions**: `DistributedQueue[T]`, `DistributedRefMap[K, V]`
- **Eru effects** for all operations with typed error enums
- **SaratiCodec** for serialization
- **Scoped resources** via `bracket`
- **Optimistic concurrency** via CAS retry loops
- **Blocking interop** via `Eru.interruptibleBlocking`

For Melian examples and documentation, a simple persistence story is needed. Options from the design doc:
- **Option A**: Multiple specific libraries (eru-redis, eru-postgres, eru-sqlite)
- **Option B**: Shared trait library `eru-data` + backend implementations
- **Option C**: Two levels — `eru-data` (KV, queue, CRUD) + Strongbow (relational queries)

Pragmatic start: an `eru-data` module with in-memory implementations (for testing) and trait definitions that eru-nats-style backends can implement. Melian's examples use the traits, backends are pluggable.

### 4. melian-openapi
OpenAPI 3.1 spec generation from compile-time metadata. The macro already collects path templates, parameter types, request/response types. Needs:
- Schema generation via Mirror traversal (like Valar/Sarati derivation)
- Annotation support (@description, @example)
- JSON spec materialization
- Optional Swagger UI serving endpoint

### 5. EventStream[A] / SSE
eru-http has `Response.sse(events: ChunkStream)` and `ServerSentEvent`. Melian needs to wire `EventStream[A]` response type to encode events via Sarati and push through SSE.

### 6. Compile Error Tests
Use munit's `compileErrors("""...""")` to assert:
- GET with Json body → compile error
- POST without body → compile error
- Mismatched path param count → compile error
- Missing typeclass → compile error with three-part format

## Architecture Notes

- **eru-http** owns HTTP protocol — Melian delegates via `Response.*` factories and `BodyEncoder`/`BodyDecoder`
- **SaratiBridge** (60 lines) is the only integration point between Sarati/Rumil and eru-http
- **Markers are transparent type aliases** (`type Path[A] = A`) — not opaque types
- **Macro uses `Apply(Select.unique(...), args)`** for handler calls — works for any arity
- **Error accumulation uses `Eru.attempt`** — the Eru-native way to lift errors into values
- **`Type[?]` crosses quote boundaries**, `TypeRepr` does not — critical for recursive macro generation
- **Router takes `ErrorSanitizer` via given** — defaults to development mode

## Key Files

| File | Purpose |
|------|---------|
| `melian-router/…/RouteMacros.scala` | The macro engine (~250 lines) |
| `melian-router/…/SaratiBridge.scala` | Sarati↔eru-http bridge (~60 lines) |
| `melian-router/…/HandlerIntrospection.scala` | TypeRepr analysis |
| `melian-router/…/Router.scala` | Dispatch + error rendering |
| `melian-core/…/Markers.scala` | Transparent type aliases |
| `melian-core/…/ErrorRenderer.scala` | Domain error → Response typeclass |
| `melian-core/…/extraction/` | FromPathSegment, FromQueryParam, FromHeader |
| `melian-server/…/MelianServer.scala` | Bridge to eru-http HttpServer |

## Memory Files
All context is persisted in `/home/hakim/.claude/projects/-home-hakim-examples-melian/memory/`. Key entries:
- `reference_eru_http.md` — eru-http types and APIs
- `reference_sarati.md` — Sarati codecs and AST types
- `reference_rumil.md` — Rumil parser combinators
- `reference_valar.md` — Valar validation patterns and macro derivation
- `reference_eru_api.md` — Eru combinators to use
- `project_security_audit.md` — known security gaps and status
- `feedback_scala_style.md` — brace syntax, FP, pattern matching preferences
