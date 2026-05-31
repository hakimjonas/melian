# Melian — Next Session Pickup Guide

## Current State (2026-04-17)

**35 source files across 5 modules, 57 passing tests, zero warnings.**

All code is on `master` branch. Run `sbt testAll` to verify.

### What works end-to-end:

```scala
val router = Router.builder
  .get("/users/:id", (id: Path[UUID], page: Query["page", Int]) =>
    Eru.succeed(Ok(s"user-$id page $page")))
  .post("/workspaces/:id", (id: Path[UUID], auth: Header[BearerToken], cmd: Json[CreateCommand]) =>
    Eru.succeed(Ok(Workspace(id, cmd.name))))
  .get("/events/:id", (_: Path[UUID]) =>
    Eru.succeed(EventStream(ServerSentEvent.toChunkStream(events))))
  .build
  .getOrElse(sys.error("route build failed"))

// OpenAPI spec from compile-time metadata
val spec = OpenApiSpec.generate(info, router.operationSchemas)

// Serve with middleware stack
val handler = SecurityHeaders.middleware()(
  ErrorPages.middleware(ErrorPages.Config(buildDir))(
    OpenApiRoutes.middleware(spec)(
      StaticFiles.middleware(StaticFiles.Config(buildDir))(
        router.toHandler
      )
    )
  )
)

MelianServer.serve(router) { server =>
  server.start.map(addr => println(s"Listening on $addr"))
}
```

### Complete feature set:

- **Extraction**: Path[A], Query[N, A] (type-level names), Header[A], Json[A]
- **Query params**: Required and optional (`Query["page", Option[Int]]`), multiple per route
- **Validation**: Valar integration — `Validator[A]` summoned at compile time for every Json[A]
- **Error handling**: Error accumulation across extractions, RFC 9457 ProblemDetails, 422 for validation
- **Response types**: Ok, Created (auto Location header), Accepted, NoContent, EventStream
- **SSE**: EventStream[ChunkStream] wired to eru-http Response.sse
- **ErrorRenderer[E]**: Domain error → Response typeclass
- **ErrorSanitizer**: Dev/production error detail modes
- **OpenAPI 3.1**: Compile-time schema generation, runtime dedup, recursive type support
- **Annotations**: @description, @example on case class fields → OpenAPI documentation
- **Swagger UI**: OpenApiRoutes.middleware serves /openapi.json + /docs (CDN Swagger UI)
- **Static files**: StaticFiles.middleware with media types, cache control, path traversal protection
- **Security headers**: SecurityHeaders.middleware — HSTS, X-Frame-Options, nosniff, Referrer-Policy
- **Custom error pages**: ErrorPages.middleware — serve 404.html, 500.html instead of JSON
- **Compile error tests**: 6 assertions — method constraints, path mismatches, missing typeclasses
- **Endpoint context**: RequestContext via context functions (?=>)
- **Content-Type validation**: JSON depth guard (64 levels)
- **Arbitrary arity**: Handler calls via AST Apply — no per-arity code

### Remaining asInstanceOf (2):
1. `v.asInstanceOf[a]` — Result value extraction. Blocked by Scala 3.8.3 compiler backend bug.
2. Context function boundary — `Endpoint` (`?=>`) to `Function1`. Scala language boundary, irreducible.

## What to Build Next

### 1. arda-web Migration (First Real Deployment)

Replace the hand-rolled 200-line eru-http server at `/home/hakim/google/arda-web/server/` with Melian. The site is running at ardaproject.org (also localhost:8092). Everything needed is now in place:
- StaticFiles middleware serves Rem's build output
- SecurityHeaders middleware applies production security
- ErrorPages middleware serves Rem-built 404.html
- Clean URL routing via Router

### 2. eru-sqlite (First Database Integration)

Standalone library following eru-nats patterns. See `/home/hakim/examples/eru-backend-libraries-plan.md` for full plan. Patterns: bracket lifecycle, typed error enum, `Eru.interruptibleBlocking`, `SaratiCodec`. This enables the Melian getting-started example with real persistence.

### 3. eru-postgres (Production Database)

Same shape as eru-sqlite, production-grade. After this, Melian has a complete CRUD story.

### 4. Further eru-* backends

Redis (caching), MongoDB (documents), DynamoDB (ERM+ scheduler alternative), Neo4j (graph/Strongbow integration). Each 200-500 lines. See plan doc.

### 5. Melian Polish

- OpenAPI: Content-Type negotiation, response headers in spec
- Router: HEAD method auto-generation from GET routes
- Testing: MelianTestKit improvements for easier endpoint testing
- Performance: Benchmark against raw eru-http overhead

## Architecture Notes

- **Markers are transparent type aliases** (`type Path[A] = A`, `type Query[N, A] = A`)
- **Query[N, A]** uses type-level string literal for param name — `Query["page", Int]`
- **Macro uses `Apply(Select.unique(...), args)`** for handler calls — any arity
- **SchemaGen** traverses TypeRepr at compile time with visited set for recursive types
- **OpenAPI schemas** are TypeSchema ADTs — macro generates them, runtime deduplicates
- **Error accumulation uses `Eru.attempt`** — the Eru-native error-as-value pattern
- **`Type[?]` crosses quote boundaries**, `TypeRepr` does not
- **Middleware composes as handler wrappers** — `(Request => Eru[E, Response]) => (Request => Eru[E, Response])`
- **Router takes `ErrorSanitizer` via given** — defaults to development mode
- **Bracket nesting** for resource lifecycle — outer acquires DB, inner runs server

## Key Files

| File | Purpose |
|------|---------|
| `melian-router/…/RouteMacros.scala` | The macro engine (~460 lines) |
| `melian-router/…/SchemaGen.scala` | Compile-time OpenAPI schema generation |
| `melian-router/…/SaratiBridge.scala` | Sarati↔eru-http bridge (~110 lines) |
| `melian-router/…/HandlerIntrospection.scala` | TypeRepr analysis |
| `melian-router/…/Router.scala` | Dispatch + error rendering |
| `melian-core/…/Markers.scala` | Transparent type aliases |
| `melian-core/…/schema/TypeSchema.scala` | Schema descriptor ADTs |
| `melian-core/…/schema/OperationSchema.scala` | Route metadata types |
| `melian-core/…/schema/Annotations.scala` | @description, @example |
| `melian-core/…/extraction/` | FromPathSegment, FromQueryParam, FromHeader |
| `melian-openapi/…/OpenApiSpec.scala` | OpenAPI 3.1 JSON generation |
| `melian-openapi/…/OpenApiRoutes.scala` | Spec + Swagger UI endpoint handlers |
| `melian-openapi/…/ComponentRegistry.scala` | Schema deduplication |
| `melian-server/…/MelianServer.scala` | Bridge to eru-http HttpServer |
| `melian-server/…/StaticFiles.scala` | Static file serving middleware |
| `melian-server/…/SecurityHeaders.scala` | Security headers middleware |
| `melian-server/…/ErrorPages.scala` | Custom error page middleware |

## Ecosystem Context

- **Rem** (Dart) builds HTML; **Melian** (Scala) serves it. Independent projects that compose at deployment.
- **Strongbow** — type-safe columnar query engine. Future: Melian endpoint → Strongbow plan → interpreter.
- **eru-nats** — template for all eru-* backend integrations (bracket, typed errors, interruptibleBlocking).
- **Valar** — validation, already integrated into Melian's Json body pipeline.
- **Persistence**: Standalone eru-* libraries per backend (ZIO pattern). No shared abstraction layer.

## Memory Files
All context persisted in `/home/hakim/.claude/projects/-home-hakim-examples-melian/memory/`.
Ecosystem-level plan at `/home/hakim/examples/eru-backend-libraries-plan.md`.
