# Melian: Architecture Design Document

## 1. Vision

Melian is a zero-reflection, compile-time web framework for Scala 3 built on the Arda ecosystem. Named after the Maia who wove the protective Girdle around Doriath, Melian acts as an impenetrable, type-safe boundary between the untrusted external web and your pure business logic.

The framework enforces correctness in both directions:

- **Inbound**: Raw HTTP requests pass through a strict extraction and validation pipeline (eru-http -> Rumil -> Sarati -> Valar). Malformed, malicious, or invalid requests are rejected before business logic executes. What reaches the developer is a guaranteed-valid, fully-typed domain object.

- **Outbound**: Response types carry HTTP protocol semantics from eru-http's typed status codes. The compiler enforces that 201 Created includes a Location header, that 204 No Content carries no body, and that GET handlers don't declare request bodies. Protocol violations are compile errors, not runtime bugs.

Melian eliminates the historical tradeoff between ergonomic developer experience and performance by leveraging Scala 3's metaprogramming (`inline`, `Quotes`, `Expr`) to generate the imperative extraction and encoding pipelines at compile time.

## 2. Foundation: eru-http's Typed Protocol Semantics

The key insight that distinguishes Melian from other web frameworks is that eru-http already encodes HTTP protocol knowledge into its type system. Melian exploits this at compile time.

### Method Semantics

eru-http's `Method` is an opaque type with semantic extension methods:

```scala
// These are properties of the HTTP method itself, not configuration
method.isSafe            // GET, HEAD, OPTIONS, TRACE
method.isIdempotent      // GET, HEAD, PUT, DELETE, OPTIONS, TRACE
method.allowsRequestBody // false for GET, HEAD, DELETE, TRACE
method.requiresRequestBody // true for POST, PUT, PATCH
method.expectsResponseBody // false for HEAD
method.isCacheable       // GET, HEAD, POST (with explicit headers)
```

Melian uses these at compile time:
- `POST /workspaces` with `requiresRequestBody = true` -> a `Json[A]` parameter is required
- `GET /workspaces/:id` with `allowsRequestBody = false` -> a `Json[A]` parameter is a compile error
- `HEAD /workspaces/:id` with `expectsResponseBody = false` -> the return type must not carry a body

### StatusCode Semantics

eru-http's `StatusCode` carries protocol constraints:

```scala
status.allowsResponseBody    // false for 204, 304, 1xx
status.requiredHeaders       // 201 -> Set("Location"), 401 -> Set("WWW-Authenticate"),
                             // 405 -> Set("Allow"), 416 -> Set("Content-Range")
status.requiredHeaderChoices // 304 -> Set("ETag", "Cache-Control", "Content-Location", "Date", "Vary")
status.isRetryable           // 408, 429, 500, 502, 503, 504
status.isCacheable           // 200, 203, 204, 206, 300, 301, 404, 405, 410, 414, 501
```

Melian maps return types to status codes and enforces their constraints at compile time. When a handler returns `Created[Workspace]`, the framework knows status 201 requires a Location header and generates the appropriate response construction.

### Response Factory Alignment

eru-http already provides protocol-correct response constructors:

```scala
Response.created(location: Uri, body: Body)           // 201 - requires Location
Response.unauthorized(challenge: String, body: Body)   // 401 - requires WWW-Authenticate
Response.methodNotAllowed(allowedMethods: Set[Method]) // 405 - requires Allow
Response.tooManyRequests(retryAfter: String, body: Body) // 429 - requires Retry-After
Response.noContent                                     // 204 - no body
```

Melian's response types compile down to these constructors, inheriting their protocol correctness.

## 3. The Developer Experience

### 3.1 Endpoint Definition

Endpoints are pure Scala functions. Type-level markers tell the compiler where each parameter comes from:

```scala
import net.ghoula.melian.*

def createWorkspace(
  workspaceId: Path[UUID],
  auth:        Header[Authorization],
  cmd:         Json[CreateWorkspaceCommand]
): Endpoint[DomainError, Created[Workspace]] = {
  Database.insert(workspaceId, cmd).map(Created(_))
}

def getWorkspace(
  workspaceId: Path[UUID],
  accept:      Header[Accept]
): Endpoint[DomainError, Ok[Workspace]] = {
  Database.findById(workspaceId).map(Ok(_))
}

def deleteWorkspace(
  workspaceId: Path[UUID],
  auth:        Header[Authorization]
): Endpoint[DomainError, NoContent] = {
  Database.delete(workspaceId).as(NoContent)
}

def listWorkspaces(
  page:  Query[Option[Int]],
  limit: Query[Option[Int]],
  auth:  Header[Authorization]
): Endpoint[DomainError, Ok[List[Workspace]]] = {
  Database.list(page.getOrElse(1), limit.getOrElse(20)).map(Ok(_))
}
```

Note what is absent: no `Request` object to destructure, no manual parsing, no status code literals, no header manipulation.

### 3.2 Source Markers

Opaque type markers instruct the compile-time extractor where to source each parameter:

```scala
opaque type Path[A]    = A   // Extracted from URI path segments
opaque type Query[A]   = A   // Extracted from URI query parameters
opaque type Header[A]  = A   // Extracted from HTTP headers
opaque type Json[A]    = A   // Body: Rumil parse -> Sarati decode -> Valar validate
opaque type Form[A]    = A   // Body: form-urlencoded extraction -> Valar validate
```

At runtime these are zero-cost (erased to `A`). At compile time the macro inspects parameter types via `TypeRepr` to determine the extraction strategy.

### 3.3 Response Types

Response types encode HTTP status semantics:

```scala
// 2xx - Success
type Ok[A]          // 200 - body required
type Created[A]     // 201 - body + Location header auto-derived from path template + ID
type Accepted[A]    // 202 - body, no Location needed
type NoContent      // 204 - no body allowed

// 3xx - Redirection
type SeeOther       // 303 - Location header required, no body
type NotModified    // 304 - no body allowed

// Streaming
type EventStream[A] // 200 - SSE stream, typed events encoded via Encoder[A, JsonValue]

// Custom status via phantom type
type Status[Code <: Int & Singleton, A]
```

The compiler maps each response type to its eru-http `StatusCode` and enforces `allowsResponseBody` and `requiredHeaders` constraints. For example:

- `Created[A]` -> status 201 -> `requiredHeaders = Set("Location")` -> Location auto-derived from the path template and resource (see Section 10.1)
- `NoContent` -> status 204 -> `allowsResponseBody = false` -> parameterized `NoContent[A]` is a compile error
- `Ok[A]` -> status 200 -> body is encoded via `Encoder[A, JsonValue]` and serialized
- `EventStream[A]` -> status 200 -> sets `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `Connection: keep-alive`

### 3.4 Router Binding

```scala
val app = Router.builder
  .post("/workspaces/:workspaceId", createWorkspace)
  .get("/workspaces/:workspaceId", getWorkspace)
  .delete("/workspaces/:workspaceId", deleteWorkspace)
  .get("/workspaces", listWorkspaces)
  .build
```

Each `.post(...)`, `.get(...)` etc. is an `inline def` that triggers the compile-time pipeline.

### 3.5 Context Functions

Ambient request state (trace IDs, raw headers, authenticated user) is threaded via Scala 3 context functions without polluting business logic signatures:

```scala
type Endpoint[E, A] = RequestContext ?=> Eru[E, A]

// RequestContext provides:
trait RequestContext {
  def requestId: UUID
  def rawRequest: Request[Body]
  def rawHeaders: Headers
  def remoteAddress: InetAddress
  def startTime: Long
}

// Accessed implicitly in business logic:
def createWorkspace(...): Endpoint[DomainError, Created[Workspace]] = {
  val ctx = summon[RequestContext]
  Logger.info(s"[${ctx.requestId}] Creating workspace")
  // ...
}
```

Note that `rawRequest` is available for escape-hatch scenarios where the typed extraction model doesn't cover a use case.

## 4. The Compile-Time Pipeline

When the compiler encounters `Router.builder.post("/workspaces/:workspaceId", createWorkspace)`, an `inline def` backed by a `Quotes` macro executes the following phases:

### Phase 1: Path Verification

The macro receives the path string as a literal `Expr[String]` and the handler as an `Expr[Function]`. It:

1. Parses the path string at compile time, extracting segments and parameter placeholders (`:workspaceId`)
2. Inspects the handler's `TypeRepr` to find all `Path[T]` parameters and their names
3. Verifies a 1:1 correspondence between path placeholders and `Path[T]` parameters
4. Verifies that a `FromPathSegment[T]` given instance exists for each `Path[T]`

Mismatches are compile errors following the Arda ecosystem convention (header + details + hint):

```
error: Cannot bind POST /workspaces/:workspaceId to createWorkspace:
       path parameter ':workspaceId' has no corresponding Path[_] parameter.

  Found Path parameters: [orgId: Path[UUID]]
  Expected: workspaceId

  Hint: Rename the parameter or update the path template.
```

### Phase 2: Method Constraint Verification

The macro knows the HTTP method from the router method (`.post(...)` -> `POST`). It verifies:

- If `method.requiresRequestBody`: at least one body marker (`Json[A]`, `Form[A]`) must be present
- If `!method.allowsRequestBody`: no body markers may be present
- If `!method.expectsResponseBody`: the return type must not carry a body (`NoContent`, `SeeOther`, etc.)

Violations are compile errors:
```
error: Cannot bind GET /workspaces/:workspaceId to getWorkspace:
       GET does not allow a request body, but handler declares 'cmd: Json[CreateWorkspaceCommand]'.

  Hint: Use POST or PUT for endpoints that accept a request body.
```

### Phase 3: Instance Validation

Before generating any code, the macro validates that all required typeclass instances exist, following the upfront validation pattern used throughout the Arda ecosystem (Valar's `Derivation.scala`, Sarati's `Decoder`):

- `FromPathSegment[T]` for every `Path[T]`
- `FromQueryParam[T]` for every `Query[T]`
- `FromHeader[T]` for every `Header[T]`
- `Decoder[JsonValue, T]` for every `Json[T]`
- `Validator[T]` for every `Json[T]` and `Form[T]`
- `Encoder[A, JsonValue]` for the response body type `A`

All missing instances are collected and reported together:

```
error: Cannot bind POST /workspaces/:workspaceId to createWorkspace:
       missing typeclass instances for 2 parameters.

  1. Parameter 'cmd: Json[CreateWorkspaceCommand]'
     Missing: Decoder[JsonValue, CreateWorkspaceCommand]
     Add: given Decoder[JsonValue, CreateWorkspaceCommand] = Decoder.derived

  2. Parameter 'cmd: Json[CreateWorkspaceCommand]'
     Missing: Validator[CreateWorkspaceCommand]
     Add: given Validator[CreateWorkspaceCommand] = Validator.derive
```

### Phase 4: Extractor Generation

For each handler parameter, the macro generates extraction code based on its marker type:

**Path[T]**: Extracts the string segment from the matched URI position, passes it to `FromPathSegment[T].parse(segment)`.

**Query[T]**: Extracts from `Uri.queryParam(name)`, passes to `FromQueryParam[T].parse(value)`. `Query[Option[T]]` makes the parameter optional.

**Header[T]**: Extracts from `Headers.getFirst(name)`, passes to `FromHeader[T].parse(value)`. Header name derived from the `FromHeader[T].headerName` method.

**Json[T]**: Generates the full Girdle pipeline:
1. Read body string from `Request[Body]` via eru-http's `Body` type
2. Parse JSON via Rumil's `parseJson` -> `Result[ParseError, JsonValue]`
3. Decode AST via Sarati's `Decoder[JsonValue, T].decode` -> `Result[DecodeError, T]`
4. Validate via Valar's `Validator[T].validate` -> `ValidationResult[T]`

Each stage short-circuits on failure, accumulating errors into the unified error type.

### Phase 5: Response Encoder Generation

The macro inspects the return type (`Created[Workspace]`, `Ok[Workspace]`, `NoContent`, etc.) and generates:

1. Status code selection from the response type
2. Body encoding via Sarati's `Encoder[A, JsonValue]` (when `allowsResponseBody`)
3. Required header injection (Location for 201, etc.)
4. Content-Type header (`application/json` for JSON-encoded bodies)

### Phase 6: OpenAPI Schema Extraction

The same macro invocation that generates extractors also generates OpenAPI metadata:

1. Path template and method from the router binding
2. Request parameter schemas from `Path[T]`, `Query[T]`, `Header[T]` types via `Mirror`
3. Request body schema from `Json[T]` via `Mirror.ProductOf[T]` field traversal
4. Response schema from the response type's body parameter via `Mirror`
5. Error response schemas from the endpoint's error type (`E` in `Endpoint[E, A]`)

This metadata is accumulated into a compile-time registry and materialized as an OpenAPI 3.1 specification. Because the schema is extracted from the same types the compiler enforces, the specification cannot drift from the implementation.

Schemas can be enriched with descriptions, examples, and constraints via Scala 3 annotations:

```scala
case class CreateWorkspaceCommand(
  @description("Display name for the workspace") name: String,
  @description("Optional description") @example("My project workspace") description: Option[String]
)
```

## 5. The Girdle: Inbound Request Pipeline

The Girdle is the multi-stage validation pipeline that transforms raw HTTP bytes into validated domain objects. Each stage produces structured errors that are accumulated and unified.

### Stage 1: eru-http (Transport)

eru-http's `NativeHttpServer` handles TCP, TLS, HTTP/1.1 and HTTP/2 parsing, and dispatches `Request[Body]` on a Virtual Thread. By the time Melian receives the request, HTTP protocol parsing is complete and the request is a fully-typed eru-http value with opaque-typed `Method`, `StatusCode`, `Uri`, and `Headers`.

### Stage 2: Melian Router (Dispatch)

The compiled router matches the request path against registered route templates and selects the handler. If no route matches, the router returns 404. If the path matches but the method doesn't, the router returns 405 with an `Allow` header listing valid methods (leveraging `Response.methodNotAllowed(allowedMethods)`).

### Stage 3: Melian Extractor (Structural)

The compile-time-generated extractor pulls path segments, query parameters, and headers from the typed `Request[Body]`. These are structural extractions from eru-http's typed `Uri`, `Headers`, and `Method` -- no string parsing required beyond path segment coercion via `FromPathSegment[T]`.

### Stage 4: Rumil (Syntax)

For `Json[A]` / `Coded[A]` parameters, the request body string is parsed via Rumil:

```
String -> Result[ParseError, JsonValue]   (or XmlNode, TomlDocument, YamlDocument)
```

Rumil produces Sarati's AST types directly (they share types -- e.g., `net.ghoula.sarati.ast.json.JsonValue`). Parse errors carry line, column, and offset information via Rumil's `Location` type.

**Resilient parsing mode**: By default, the Girdle uses resilient parsing via Rumil's `recover` combinator at structural boundaries (object members, array elements). When the parser encounters a syntax error, it records the error, skips to the next recoverable point, and continues. This produces `Result.Partial(value, errors, consumed)` -- a best-effort AST *and* all syntax errors in one pass. Subsequent Sarati and Valar stages process the partial AST and add their own errors, so a single malformed request returns comprehensive feedback across all three pipeline stages.

For endpoints that require strict parsing (reject on any syntax error), developers can opt in:

```scala
def importData(
  data: Json[ImportPayload, Strict]  // strict mode: no recovery, fail on first error
): Endpoint[ImportError, Ok[ImportResult]] = { ... }
```

**Lossless parsing mode**: For advanced use cases (custom DSL endpoints, interactive editors, live validation), Rumil can produce a `GreenNode` lossless syntax tree instead of an AST value. GreenNode preserves all source text including whitespace, comments, and error regions (`TokenKind.Error`). The `RedTree` wrapper provides position-aware navigation (`nodeAt(offset)`, parent/sibling traversal, `validate` to collect all errors). This enables applications like:

- Live parsing feedback via SSE or WebSocket (parse as the user types, push errors back)
- Incremental re-parsing (only reparse the edited subtree via `findReparseRegion`)
- Source-position mapping between parse errors and the original input

GreenNode/RedTree is not part of the default Girdle pipeline -- it's an opt-in capability for endpoints that need it.

Note: Rumil parses the complete body string, not a stream. This is the standard model for structured request bodies. For streaming use cases (large file uploads, chunked transfers), developers use `Body.Stream` directly via `RequestContext.rawRequest`.

### Stage 5: Sarati (Structure)

The `JsonValue` AST is decoded into the target case class via Sarati's macro-derived `Decoder[JsonValue, T]`:

```
JsonValue -> Result[DecodeError, T]
```

Sarati reports missing fields, type mismatches, and invalid values with field paths. The `AstStruct[JsonValue]` typeclass bridges the JSON AST to Sarati's format-agnostic decoding logic, meaning the same decoder derivation works for TOML, YAML, and XML ASTs.

### Stage 6: Valar (Domain)

The decoded case class is validated via Valar's macro-derived `Validator[T]`:

```
T -> ValidationResult[T]
```

Valar accumulates all constraint violations (non-empty strings, ranges, custom business rules) with field paths and error codes. Constraints are opt-in per Valar 0.6.0 semantics -- built-in validators are pass-through by default, and constraint validators (`nonEmpty`, `inRange`, `regexMatch`, etc.) are composed explicitly.

### Error Unification

All pipeline errors are unified into a single response type following RFC 9457 (Problem Details for HTTP APIs):

```scala
enum RequestError {
  case ParseFailed(errors: List[ParseError])             // Rumil: malformed JSON
  case DecodeFailed(errors: List[DecodeError])           // Sarati: structural mismatch
  case ValidationFailed(errors: Vector[ValidationError]) // Valar: constraint violations
  case PathError(param: String, value: String, cause: String)
  case QueryError(param: String, cause: String)
  case HeaderError(header: String, cause: String)
  case BodyMissing                                        // Body required but absent
  case UnsupportedMediaType(expected: MediaType, actual: Option[MediaType])
}
```

Errors from all pipeline stages coexist in a single response. Path, query, and header extraction errors are collected in parallel. With resilient parsing enabled, body pipeline errors also accumulate across stages -- Rumil reports syntax errors, Sarati reports structural mismatches on the recovered AST, and Valar reports constraint violations on the decoded values. A single malformed request returns comprehensive feedback in one round-trip:

```json
{
  "type": "about:blank",
  "status": 400,
  "title": "Bad Request",
  "errors": [
    {"in": "path", "path": "workspaceId", "detail": "invalid UUID format"},
    {"in": "body", "path": "email", "detail": "must not be empty"},
    {"in": "body", "path": "age", "detail": "must be >= 0, got -5"}
  ]
}
```

## 6. The Reverse Girdle: Outbound Response Pipeline

### Encoding

When business logic returns a value (e.g., `Created(workspace)`), the framework:

1. Determines the status code from the response type wrapper (Created -> 201)
2. Encodes the body via Sarati's `Encoder[A, JsonValue]` -> `JsonValue`
3. Serializes the `JsonValue` to a JSON string
4. Constructs the eru-http `Response[Body]` with appropriate headers

### Protocol Enforcement

The response construction uses eru-http's protocol-correct factory methods directly:

- `Created[A]` -> `Response.created(locationUri, body)` which sets both status 201 and the Location header
- `NoContent` -> `Response.noContent` which sets status 204 with no body
- `Ok[A]` -> `Response.ok(body)` with Content-Type from the encoder

For status codes with `requiredHeaders` (401 -> WWW-Authenticate, 405 -> Allow, 429 -> Retry-After), the response type must carry the required data:

```scala
// 401 - the challenge string is part of the response type
type Unauthorized(challenge: String)

// 405 - the allowed methods are derived from the router (all methods registered for the path)
// This is automatic -- the router knows which methods are bound

// 429 - rate limiting metadata
type TooManyRequests(retryAfter: Duration)
```

### Error Rendering

Domain errors follow a three-tier precedence model:

```scala
// 1. Global default (RFC 9457 Problem Details)
val app = Router.builder
  .errorRenderer(ProblemDetailsRenderer)   // global default
  .get("/workspaces/:id", getWorkspace)
  .build

// 2. Per-group override
val admin = Router.builder
  .prefix("/admin")
  .errorRenderer(AdminErrorRenderer)       // overrides global for /admin/*
  .get("/users", listUsers)
  .build

// 3. Per-endpoint (via the Endpoint's error type)
given ErrorRenderer[DomainError] with {
  def render(error: DomainError): Eru[Nothing, Response[Body]] = error match {
    case DomainError.NotFound(id) => Eru.succeed(Response.notFound(Body.text(s"Resource $id not found")))
    case DomainError.Conflict(msg) => Eru.succeed(Response.conflict(Body.text(msg)))
  }
}
```

Precedence: endpoint-level given > group-level > global. Innermost wins.

The default `ProblemDetailsRenderer` produces RFC 9457 JSON:

```json
{
  "type": "about:blank",
  "status": 404,
  "title": "Not Found",
  "detail": "Workspace abc-123 does not exist"
}
```

## 7. Execution Model

Melian has no async state machines, reactive streams, or callback chains. The entire pipeline executes synchronously on a Virtual Thread managed by eru-http's `NativeHttpServer`:

1. eru-http accepts a TCP connection and spawns a Virtual Thread
2. HTTP request is parsed into `Request[Body]`
3. The compiled router matches the path and method
4. Melian's generated extractor runs the Girdle pipeline synchronously
5. The developer's `Eru` business logic executes, potentially forking fibers or suspending
6. The response is encoded and written back

This model scales to 100K+ concurrent connections via Virtual Threads (~10KB per thread) without the complexity of reactive programming.

### Backpressure

For endpoints that produce streaming responses (`EventStream[A]`), backpressure is inherent in the model: the Virtual Thread blocks on socket writes when the client can't keep up. No explicit flow control mechanism is needed.

For endpoints that consume large inputs, the `RequestContext.rawRequest` escape hatch provides access to `Body.Stream` with `ChunkStream.pull` for demand-driven consumption. This is an advanced pattern -- most endpoints use `Json[A]` which reads the complete body.

## 8. Typeclasses

### Extraction Typeclasses

```scala
// Parse a path segment string into a typed value
trait FromPathSegment[A] {
  def parse(segment: String): Either[String, A]
}
// Built-in: String, UUID, Int, Long, Boolean

// Parse a query parameter string into a typed value
trait FromQueryParam[A] {
  def parse(value: String): Either[String, A]
}
// Built-in: String, Int, Long, Boolean, Double, UUID, Option[A], List[A]

// Parse a header value into a typed value
trait FromHeader[A] {
  def headerName: String
  def parse(value: String): Either[String, A]
}
// Built-in: Authorization, Accept, ContentType, custom header types
```

### Body Pipeline Typeclasses (from Arda ecosystem)

These already exist and are reused directly:

```scala
// Rumil: String -> AST
parseJson(input: String): Result[ParseError, JsonValue]

// Sarati: AST -> typed value (macro-derived)
trait Decoder[From, +To] {
  def decode(value: From): Result[DecodeError, To]
}

// Sarati: typed value -> AST (macro-derived)
trait Encoder[-A, To] {
  def encode(value: A): To
}

// Valar: typed value -> validated typed value (macro-derived)
trait Validator[A] {
  def validate(a: A): ValidationResult[A]
}
```

### Error Rendering Typeclass

```scala
trait ErrorRenderer[E] {
  def render(error: E): Eru[Nothing, Response[Body]]
}
```

## 9. Streaming and Real-Time Endpoints

### Server-Sent Events

eru-http has first-class SSE support with `ServerSentEvent`, `Response.sse`, and `ChunkStream`. Melian provides a typed wrapper:

```scala
def orderUpdates(
  orderId: Path[UUID],
  auth:    Header[Authorization]
): Endpoint[DomainError, EventStream[OrderEvent]] = {
  EventSource.subscribe(orderId).map(EventStream(_))
}
```

The generated response code:
1. Sets status 200 with `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `Connection: keep-alive`
2. Each event is encoded via `Encoder[A, JsonValue]` and wrapped as a `ServerSentEvent.data`
3. Events are pushed through eru-http's `ChunkStream` using `ServerSentEvent.toChunk`

The `EventStream[A]` type wraps an `Eru` that produces events. Backpressure is natural -- the Virtual Thread blocks on writes when the client falls behind.

SSE endpoints enable any push-based pattern: live notifications, progress tracking, dashboard updates, streaming computation results, or real-time validation feedback.

### WebSocket Endpoints

eru-http provides full RFC 6455 WebSocket support with `WebSocketHandshake`, `WebSocketFrame`, `WebSocketMessage`, and `WebSocketHandler`. Melian provides typed WebSocket endpoints:

```scala
def liveSession(
  sessionId: Path[UUID],
  auth:      Header[Authorization]
): WebSocketEndpoint[ClientMessage, ServerMessage] = {
  conn => sessionService.handleConnection(sessionId, conn)
}
```

The framework handles the HTTP upgrade handshake and frame protocol via eru-http. The developer works with typed messages -- `ClientMessage` decoded from incoming frames via Sarati, `ServerMessage` encoded to outgoing frames.

WebSocket endpoints support bidirectional, low-latency communication for interactive applications: collaborative editing, live dashboards, streaming queries, or any use case where request-response is insufficient.

### Combining Parsing with Real-Time

Rumil's resilient parser and lossless syntax trees (GreenNode/RedTree) compose naturally with SSE and WebSocket. An application can accept user input over WebSocket, parse it resiliently on each keystroke, and push structured feedback (errors, completions, partial results) back to the client in real time. The framework provides the transport and typed encoding -- the application defines what gets parsed and how results are streamed back.

## 10. Design Decisions

### 10.1 Location Header for 201 Created

**Decision: Auto-derive with explicit override.**

The macro knows the path template (e.g., `/workspaces/:workspaceId`) and the response body type. For `Created[Workspace]`, the framework:

1. Extracts the resource identifier from the response body (via a `HasId` typeclass or the first `Path[T]` parameter)
2. Substitutes it into the path template to produce the Location URI
3. Passes it to `Response.created(locationUri, body)`

For cases where auto-derivation doesn't apply (the resource URI differs from the creation path), developers provide an explicit Location:

```scala
def createWorkspace(...): Endpoint[DomainError, Created[Workspace]] = {
  Database.insert(workspaceId, cmd).map { ws =>
    Created(ws, location = uri"/workspaces/${ws.id}/dashboard")
  }
}
```

### 10.2 Content Negotiation

**Decision: JSON by default, opt-in multi-format via overloaded encoders.**

The vast majority of API endpoints serve JSON. Making content negotiation the default adds complexity without benefit for most users. The design:

- **Default**: All endpoints encode responses as `application/json` via `Encoder[A, JsonValue]`
- **Opt-in**: Endpoints that need multiple formats declare it via an `Accept` header parameter:

```scala
def getWorkspace(
  workspaceId: Path[UUID],
  accept:      Header[Accept]    // Triggers content negotiation
): Endpoint[DomainError, Ok[Workspace]] = { ... }
```

When `Header[Accept]` is present, the macro looks for `Encoder[A, JsonValue]`, `Encoder[A, XmlNode]`, etc. and generates a runtime `MediaType.matches` dispatch based on the Accept header quality values. eru-http already provides `parseAcceptEncoding` with q-value sorting that can be adapted for content types.

If no `Header[Accept]` parameter is declared, content negotiation is skipped entirely -- zero overhead.

### 10.3 Middleware Interop

**Decision: Compiled Melian routes produce eru-http `RequestHandler` values. Middleware wraps at the eru-http level.**

A compiled `Router` produces a `RequestHandler` (i.e., `Request[Body] => Eru[HttpError, Response[Body]]`). This is the exact type eru-http middleware operates on. Therefore:

```scala
val melianApp: RequestHandler = Router.builder
  .post("/workspaces/:workspaceId", createWorkspace)
  .get("/workspaces/:workspaceId", getWorkspace)
  .build
  .toHandler

// eru-http middleware wraps normally
val app = Middleware.logging(println)
  .andThen(Middleware.corsPermissive)
  .andThen(Middleware.requestId("X-Request-ID"))
  .andThen(Middleware.compressionDefault)
  .apply(melianApp)

// Mount on eru-http server
HttpServer.scoped(config)(app) { server => ... }
```

Melian does not introduce its own middleware abstraction. eru-http's `Middleware` type is simple, composable, and already has conditional application (`forPath`, `forMethod`, `when`). Duplicating this would add complexity without value.

For typed cross-cutting concerns (authentication, authorization), Melian uses context functions and the `Header[T]` extraction mechanism rather than middleware:

```scala
// Authentication is extracted and validated like any other parameter
def createWorkspace(
  auth: Header[BearerToken]  // Extracted, validated, typed
): Endpoint[DomainError, Created[Workspace]] = {
  val user = auth.claims  // Already validated by FromHeader[BearerToken]
  // ...
}
```

### 10.4 405 Method Not Allowed

**Decision: Automatic, derived from the router.**

When a request matches a path template but not the registered method, the router automatically returns 405 with the correct `Allow` header. This is free because the router knows all registered methods per path:

```scala
// Router knows: GET, POST, DELETE are bound to /workspaces/:workspaceId
// PUT /workspaces/abc-123 -> 405 with Allow: GET, POST, DELETE
```

This uses `Response.methodNotAllowed(allowedMethods: Set[Method])` from eru-http, which already sets the `Allow` header correctly.

## 11. Macro Conventions

Melian's macros follow the patterns established across the Arda ecosystem (Valar, Sarati, Rumil):

### Field Extraction

Use `Mirror.ProductOf[T]` with type-level recursive tuple descent:

```scala
Type.of[Labels] match {
  case '[EmptyTuple] => Nil
  case '[label *: rest] =>
    TypeRepr.of[label] match {
      case ConstantType(StringConstant(name)) =>
        name :: extractLabels[rest]
    }
}
```

### Instance Summoning

Validate ALL required instances upfront before generating any code. Collect all missing instances and report them together:

```scala
val missing = fields.flatMap { field =>
  Expr.summon[Decoder[JsonValue, field.tpe]] match {
    case Some(_) => None
    case None => Some(MissingInstance(field.name, field.tpe, suggestion))
  }
}
if missing.nonEmpty then
  report.errorAndAbort(formatError(missing), Position.ofMacroExpansion)
```

### Error Messages

Three-part format: header + numbered details + hint:

```
error: Cannot bind POST /path to handler: missing instances for N parameters.

  1. Field 'name' of type String
     Missing: Validator[String]
     Add: given Validator[String] = Validator.derive

  2. ...

Hint: Sarati and Valar provide derive methods for case classes.
```

### Reconstruction

Use the standard Arda pattern for assembling case classes from macro-extracted fields:

```scala
mirror.fromProduct(Tuple.fromArray(fieldValues.toArray))
```

## 12. Module Structure

```
melian/
  melian-core/       Opaque type markers (Path, Query, Header, Json, Form),
                     response types (Ok, Created, NoContent, EventStream, ...),
                     Endpoint type alias, RequestContext trait,
                     RequestError enum, ErrorRenderer typeclass,
                     extraction typeclasses (FromPathSegment, FromQueryParam, FromHeader)

  melian-router/     Compile-time macro engine: inline Router methods,
                     path parsing, method constraint verification,
                     instance validation, extractor code generation,
                     response encoder generation, OpenAPI metadata extraction

  melian-openapi/    OpenAPI 3.1 spec materialization from compile-time metadata,
                     JSON Schema generation via Mirror traversal,
                     annotation support (@description, @example),
                     Swagger UI serving endpoint

  melian-server/     Bridge to eru-http: Router.toHandler conversion,
                     RFC 9457 ProblemDetailsRenderer,
                     server lifecycle helpers

  melian-test/       Test harness for calling endpoints directly without
                     a running server, RequestContext simulation,
                     assertion helpers for typed responses
```

### Dependency Graph

```
melian-core  (depends on: eru-http-core)
     |
     v
melian-router  (depends on: melian-core, rumil-parsers, sarati, valar-core)
     |
     +---> melian-openapi  (depends on: melian-core, sarati)
     |
     +---> melian-server  (depends on: melian-core, melian-router, eru-http-server)
     |
     +---> melian-test  (depends on: melian-core, melian-router)
```

## 13. Non-Goals (Initial Release)

- **Templating / HTML rendering**: Melian is a general-purpose API/backend framework. Frontend rendering belongs to Rem (Dart) or any SPA framework. Static file serving is handled via the convenience layer (Section 16).
- **Compatibility with other effect systems**: Melian is built for Eru. No cats-effect or ZIO bridges.

## 15. Convenience Layer

Melian is the ergonomic way to use eru-http. Beyond the core Girdle (extraction, validation, encoding), a real web project needs infrastructure that isn't strictly HTTP but that every production deployment requires. Melian provides these as composable helpers that produce eru-http's canonical types — never wrapping or replacing them.

### 15.1 Let's Encrypt / ACME Provisioning

Automatic TLS certificate provisioning and renewal:

```scala
MelianServer.withAcme(
  domain = "api.example.com",
  contactEmail = "admin@example.com",
  staging = false,        // true for Let's Encrypt staging
  storePath = "./certs",  // persistent certificate storage
  app = router,
)
```

Under the hood: ACME HTTP-01 challenge responder runs on port 80, certificates stored to disk, auto-renewed before expiration. The TLS configuration feeds directly into eru-http's `TlsConfig` — Melian doesn't manage TLS itself.

### 15.2 CORS Configuration

Declarative cross-origin resource sharing:

```scala
val app = Router.builder
  .cors(CorsConfig(
    allowOrigins = List("https://example.com", "https://app.example.com"),
    allowMethods = List(Method.GET, Method.POST, Method.PUT, Method.DELETE),
    allowHeaders = List("Authorization", "Content-Type"),
    maxAge = 3600.seconds,
  ))
  .post("/api/users", createUser)
  .build
```

Produces eru-http middleware that handles preflight `OPTIONS` requests and sets the appropriate `Access-Control-*` headers. Uses eru-http's `Method` and `HeaderName` types directly.

### 15.3 Health and Readiness Endpoints

Standard health check endpoints for orchestrators (Kubernetes, Docker, load balancers):

```scala
val app = Router.builder
  .health("/health", () => Eru.succeed(HealthStatus.healthy))
  .ready("/ready", () => for {
    db <- checkDatabase()
    cache <- checkRedis()
  } yield ReadinessStatus(db, cache))
  .post("/api/users", createUser)
  .build
```

Health returns 200/503. Readiness returns 200 with component status or 503 with details. Both produce `Response[Body]` via eru-http's factories.

### 15.4 Structured Request Logging

Automatic request/response logging with structured output:

```scala
val app = Router.builder
  .requestLogging(LogConfig(
    format = LogFormat.json,          // or LogFormat.common, LogFormat.combined
    includeHeaders = List("User-Agent", "Accept"),
    excludePaths = List("/health"),   // don't log health checks
    slowThreshold = 500.millis,       // flag slow requests
  ))
  .post("/api/users", createUser)
  .build
```

Logs request method, path, status, duration, request ID. Produces eru-http middleware — logging wraps the handler, not replaces it.

### 15.5 Static File Serving

Serve static assets with cache headers and ETag support:

```scala
val app = Router.builder
  .staticFiles("/assets", Path.of("./static"),
    cacheControl = "public, max-age=31536000, immutable",
    etag = true,
  )
  .get("/api/data", getData)
  .build
```

Produces eru-http handler for matching paths. Sets `Content-Type` from file extension, `Cache-Control`, `ETag`, `Last-Modified`. Handles conditional requests (`If-None-Match`, `If-Modified-Since`) returning 304 when appropriate.

### 15.6 CSRF Protection

Cross-site request forgery protection for state-changing endpoints:

```scala
val app = Router.builder
  .csrf(CsrfConfig(
    tokenHeader = "X-CSRF-Token",
    cookieName = "__csrf",
    secureCookie = true,
  ))
  .post("/api/users", createUser)
  .build
```

Generates tokens, sets cookies, validates on state-changing methods (POST, PUT, DELETE, PATCH). Skips for safe methods (GET, HEAD, OPTIONS). Returns 403 on mismatch.

### 15.7 Session Management

Cookie-based session management with configurable storage:

```scala
val app = Router.builder
  .session(SessionConfig(
    cookieName = "sid",
    maxAge = 24.hours,
    secure = true,
    httpOnly = true,
    sameSite = SameSite.Strict,
    store = InMemorySessionStore(),  // or RedisSessionStore, JdbcSessionStore
  ))
  .get("/api/me", getMe)
  .build
```

Session data accessible in endpoints via `RequestContext`:

```scala
def getMe(session: Header[SessionId]): Endpoint[DomainError, Ok[User]] = {
  val ctx = summon[RequestContext]
  ctx.session.get[User]("user")
    .flatMap {
      case Some(user) => Eru.succeed(Ok(user))
      case None => Eru.fail(DomainError.Unauthorized)
    }
}
```

### 15.8 Rate Limiting

Request rate limiting with configurable strategies:

```scala
val app = Router.builder
  .rateLimit(RateLimitConfig(
    limit = 100,
    window = 1.minute,
    keyExtractor = _.remoteAddress,   // or by API key, user ID, etc.
    store = InMemoryRateLimitStore(), // or Redis for distributed
  ))
  .post("/api/users", createUser)
  .build
```

Returns 429 Too Many Requests with `Retry-After` header when limit exceeded. Uses eru-http's `Response.tooManyRequests(retryAfter, body)` factory.

### 15.9 Graceful Shutdown

Connection draining on process termination:

```scala
MelianServer.start(
  config = serverConfig,
  app = router,
  shutdown = ShutdownConfig(
    gracePeriod = 30.seconds,     // time to finish in-flight requests
    healthDuringDrain = false,    // /health returns 503 during drain
  ),
)
```

Hooks into JVM shutdown signals. Stops accepting new connections, lets in-flight requests complete (up to grace period), then shuts down. Uses Eru's structured concurrency for orderly fiber cleanup.

### Design principle

Every convenience feature in this section:
- **Produces eru-http types** (`Response[Body]`, middleware, `RequestHandler`) — never wraps them
- **Is composable** via `.builder` chaining — features combine without interference
- **Is optional** — none are required, none have side effects at import time
- **Has sensible defaults** — zero-config works for development, explicit config for production

## 16. Ecosystem Integration

### Melian + Rem

Melian is the backend, Rem is the frontend. They share the Arda ecosystem but have zero dependency on each other.

**Independent use**:
- Melian serves any frontend (React, Vue, htmx, mobile apps) via standard HTTP + JSON
- Rem works with any backend (Melian, shelf, Express, Rails) or no backend (static site generation)

**Integrated use**:
- Melian generates OpenAPI 3.1 from endpoint types at compile time
- A Dart code generator reads the OpenAPI spec and produces typed client functions
- Rem components consume the typed data directly
- The API contract cannot drift from the implementation — both sides derived from the same types

```
Melian endpoint types  ──compile──>  OpenAPI 3.1 spec
                                          │
                                     code generator
                                          │
                                     Dart client    ──>  Rem components
```

The integration point is the OpenAPI spec — a standard format that any tool can consume. The Arda-specific optimization is that both ends use Rumil parsers and Sarati codecs, so the type mappings are exact. But a React frontend reading the same OpenAPI spec works equally well.

### Melian + eru-http

Melian is the ergonomic layer over eru-http. It consumes eru-http's types directly:

- `Request[Body]` — not a Melian request type
- `Response[Body]` — not a Melian response type
- `Method`, `StatusCode`, `Uri`, `Headers` — eru-http's opaque types, used as-is
- `RequestHandler` — Melian routes compile to this type, enabling seamless middleware interop
- `TlsConfig` — Melian's ACME provisioner produces this, eru-http's server consumes it

Melian adds extraction, validation, encoding, and convenience on top. It does not add a new HTTP abstraction.

### Shared parsing (Rumil)

Both ecosystems use Rumil parsers for the same formats:

| Format | Scala (Rumil) | Dart (rumil_parsers) |
|--------|--------------|---------------------|
| JSON | `parseJson` | `parseJson` |
| YAML | `parseYaml` | `parseYaml` |
| TOML | `parseToml` | `parseToml` |
| XML | `parseXml` | `parseXml` |
| Markdown | — | `parseMarkdown` (652/652 CommonMark) |

The parsers produce typed ASTs (`JsonValue`, `YamlDocument`, `MdDocument`, etc.) that both ecosystems consume directly. Sarati decodes these ASTs into application types on both sides.

## 14. Additional Design Decisions (Resolved via Codebase Analysis)

### 14.1 Format-Agnostic Body Decoding

**Discovery: Sarati's `Decoder` is format-agnostic by design.**

`Decoder.derived[From, To]` works for any AST type with an `AstStruct[From]` instance. Sarati provides `AstStruct` for four formats:

- `AstStruct[JsonValue]` -- field access from `Object(Map[String, JsonValue])`
- `AstStruct[TomlValue]` -- field access from `InlineTable(Map[String, TomlValue])`
- `AstStruct[YamlValue]` -- field access from `Mapping(Map[String, YamlValue])`
- `AstStruct[XmlNode]` -- field access from child `Element` by `localName`

Rumil has parsers producing each of these AST types:

- `parsers.json.parseJson` -> `Result[ParseError, JsonValue]`
- `parsers.xml.parseXml` -> `Result[ParseError, XmlDocument]`
- `parsers.toml.parseToml` -> `Result[ParseError, TomlDocument]`
- `parsers.yaml.parseYaml` -> `Result[ParseError, YamlDocument]`

And both ecosystems have formatters for the reverse direction (`formatJson`, `formatXml`, `formatTomlValue`).

**Decision: Replace `Json[A]` with a unified `Coded[A]` marker.**

`Coded[A]` tells the framework: "decode this body using the Rumil -> Sarati -> Valar pipeline, dispatching on Content-Type." At runtime:

```
Content-Type: application/json -> parseJson -> Decoder[JsonValue, A]
Content-Type: application/xml  -> parseXml  -> Decoder[XmlNode, A]
Content-Type: application/toml -> parseToml -> Decoder[TomlValue, A]
Content-Type: application/yaml -> parseYaml -> Decoder[YamlValue, A]
```

At compile time, the macro verifies that `Decoder[JsonValue, A]` exists (JSON is always required as the baseline). If the user also provides `Decoder[XmlNode, A]`, `Decoder[YamlValue, A]`, etc., those formats are automatically supported. Since `Decoder.derived` uses the same macro for all formats, the user can derive multiple decoders with minimal boilerplate:

```scala
case class CreateWorkspaceCommand(name: String, description: Option[String])

// One derivation per format -- same macro, different AST type
given Decoder[JsonValue, CreateWorkspaceCommand] = Decoder.derived
given Decoder[XmlNode, CreateWorkspaceCommand] = Decoder.derived   // opt-in XML support
given Decoder[YamlValue, CreateWorkspaceCommand] = Decoder.derived  // opt-in YAML support
```

The `Json[A]` marker is retained as an alias for `Coded[A]` that restricts to JSON only (skips Content-Type dispatch). This preserves the zero-overhead path for JSON-only APIs.

For response encoding, the same principle applies: `Encoder[A, JsonValue]` is the baseline. If `Encoder[A, XmlNode]` exists and the client sends `Accept: application/xml`, the framework dispatches accordingly. Rumil's `formatJson`/`formatXml` handle serialization to strings.

Sarati's `FieldTransformer` (SnakeCase, KebabCase, ScreamingSnakeCase) is also available for field name mapping when the wire format uses a different convention than Scala camelCase.

### 14.2 Router Data Structure

**Decision: Radix trie adapted from Rumil's `RadixNode`.**

Rumil already implements a `RadixNode` -- a compressed trie with bit-masked hashing that does O(m) string matching. The same structure can be adapted for path segment matching, where `:param` segments become wildcard nodes:

```
Router trie for:
  GET  /workspaces/:workspaceId
  POST /workspaces/:workspaceId
  GET  /workspaces
  GET  /users/:userId/posts/:postId

         root
        /    \
  "workspaces"  "users"
     /    \        \
   [end]  :workspaceId  :userId
   GET    GET,POST,DELETE   \
                          "posts"
                             \
                           :postId
                           GET
```

Each node stores the set of (method, handler) pairs. Path matching is a trie walk. The trie is built at compile time by `Router.build`, giving O(m) dispatch at runtime (m = number of path segments, independent of total route count).

### 14.3 Compile-Time Route Conflict Detection

**Decision: Yes, as a natural consequence of the trie.**

Building the trie at compile time naturally detects conflicts. When two routes produce the same trie path, the macro reports it:

```
error: Route conflict detected:
  GET /users/:id     (getUser)
  GET /users/me      (getCurrentUser)

  Path '/users/me' is shadowed by '/users/:id' -- the parameter ':id' matches 'me'.

  Hint: Register literal routes before parameterized routes,
        or use a guard to distinguish them.
```

Cross-route analysis is feasible because `Router.build` sees all routes together. This is the same phase that generates the trie, so conflict detection is free.

### 14.4 Partial Body Decoding

**Decision: Treat `Result.Partial` as success with warnings.**

Both Rumil and Sarati support `Result.Partial(value, errors, consumed)` -- a result that decoded successfully but encountered non-fatal issues. Sarati's derived decoder even reconstructs the full product type from partial data (a missing non-Option field generates an error but doesn't prevent other fields from decoding).

The framework surfaces partial results as follows:

- The decoded value is passed to business logic normally
- Non-fatal errors are attached to the `RequestContext` as warnings
- A response header (`X-Melian-Warnings: 2 decode warnings`) signals the client
- The warnings are included in OpenAPI documentation as possible response metadata

This enables lenient APIs (e.g., ignoring unknown fields, coercing types) while still giving developers visibility into data quality issues. For strict APIs, developers can check `ctx.warnings` and reject explicitly.

### 14.5 Rate Limiting

**Decision: Delegate to eru-http middleware.**

Rate limiting is a cross-cutting infrastructure concern, not a per-endpoint type-level concern. eru-http's `Middleware.when` and `Middleware.forPath` already support conditional middleware application:

```scala
val rateLimiter: Middleware = req => handler => {
  checkRateLimit(req).flatMap {
    case Allowed => handler(req)
    case Limited(retryAfter) =>
      Eru.succeed(Response.tooManyRequests(retryAfter.toString, Body.text("Rate limited")))
  }
}

val app = rateLimiter
  .andThen(Middleware.corsPermissive)
  .apply(melianRouter.toHandler)
```

This uses eru-http's `Response.tooManyRequests(retryAfter, body)` which already sets the `Retry-After` header correctly. No Melian-specific abstraction needed.

## 15. Resolved: Upstream Changes (formerly Open Questions)

The three open questions from Section 15 have been resolved through codebase analysis and cross-ecosystem research. All three are upstream changes in Sarati/Rumil, documented in Addendum B.

## Addendum A: Persistence and the Arda Ecosystem

Melian itself is persistence-agnostic -- endpoint business logic returns `Eru[E, A]` regardless of where state lives. However, the broader Arda ecosystem needs a persistence story, and Melian's documentation and examples should assume one.

### The eru-nats Pattern

eru-nats establishes the convention for accessing external state in the Arda ecosystem:

- **Trait-based abstractions**: `DistributedQueue[T]`, `DistributedRefMap[K, V]` -- generic interfaces, nothing NATS-specific in the signatures
- **Eru effects** for all operations with typed error enums (`NatsError`)
- **Sarati codecs** for serialization (`SaratiCodec[T]` for binary encoding)
- **Scoped resources** via `bracket` (`NatsClient.scoped(url)(use)`)
- **Optimistic concurrency** via CAS retry loops (`NatsRefMap.update`)
- **Blocking Java interop** via `Eru.interruptibleBlocking`

Any persistence library in the ecosystem should follow these conventions.

### Open Question: One Library or Many?

**Option A: Multiple specific libraries** (extend the eru-nats model)

```
eru-nats/       NATS JetStream + KV (exists)
eru-redis/      Redis (strings, hashes, lists, streams)
eru-postgres/   PostgreSQL (SQL, transactions, connection pooling)
eru-sqlite/     SQLite (embedded, local-first)
```

Each library wraps a specific backend. Simple, focused, no abstraction tax. But traits like `DistributedQueue[T]` get duplicated or aren't shared.

**Option B: Shared trait library + backend implementations**

```
eru-data/        Shared traits + in-memory implementations (for testing)
  Queue[T]
  KeyValueStore[K, V]
  Repository[T, ID]
  Transaction[A]

eru-nats/        NATS implementations of eru-data traits
eru-redis/       Redis implementations of eru-data traits
eru-postgres/    PostgreSQL implementations of eru-data traits
```

`eru-data` would be thin -- traits, error types, in-memory implementations. Same role `eru-http-core` plays for HTTP types. Backends are swappable; tests use in-memory.

**Option C: Two levels -- simple state + query DSL**

- **eru-data**: Key-value, queue, simple CRUD (the eru-nats pattern generalized)
- **Strongbow**: Relational query DSL (`Dataset[T]`, `Expr[Row, A]`) -- could gain an RDBMS interpreter alongside the existing in-memory and Spark interpreters

These serve different use cases: "store this, fetch by ID" vs "join, aggregate, filter."

### Relationship to Melian

Regardless of which option is chosen:

- Melian core has zero dependency on persistence
- Melian's documentation and examples use the blessed persistence library (like Phoenix uses Ecto)
- The persistence library follows Arda conventions (Eru effects, Sarati codecs, typed errors, bracket scoping)
- The choice does not block Melian's design or implementation

## Addendum B: Sarati and Rumil Upstream Changes

Melian depends on capabilities that need to be added or reorganized in Sarati and Rumil before framework implementation begins. This addendum captures the finalized plan for those changes.

### B.1 Formatter Relocation: Rumil -> Sarati

**Problem**: Formatting functions are split inconsistently. `formatTomlValue` lives in Sarati (correct), but `formatJson` and `formatXml` live in Rumil (wrong -- they operate on Sarati's types, and the config types like `JsonFormatConfig` are already defined in Sarati).

**Change**: Move formatters to Sarati, next to the AST types they operate on.

| Function | Current location | Target location |
|----------|-----------------|-----------------|
| `formatJson` | `parsers.json.JsonParser` (Rumil) | `net.ghoula.sarati.ast.json` (Sarati) |
| `formatXml`, `formatXmlDocument` | `parsers.xml.XmlParser` (Rumil) | `net.ghoula.sarati.ast.xml` (Sarati) |
| `formatTomlValue` | `net.ghoula.sarati.ast.toml` (Sarati) | No change |
| `formatYaml` | Does not exist | Add to `net.ghoula.sarati.ast.yaml` (Sarati) |

**Downstream impact**:

- **Aule** -- 10+ source files import `parsers.json.formatJson`. Mechanical import change to `net.ghoula.sarati.ast.json.formatJson`. No logic changes.
- **Strongbow** -- 1 file (`ExprInterpreter.scala`) imports `parsers.json.{formatJson, parseJson}`. Split into two imports: `formatJson` from Sarati, `parseJson` from Rumil.
- **Rumil** -- own tests import the formatters. Update imports.
- **eru-diagnostics** -- no impact (uses `rumil-core` only, no formatters).
- **eru-nats** -- no impact (uses `SaratiCodec` only).

### B.2 AstBuilder: Format-Agnostic Encoding

**Problem**: Sarati's `Decoder` is format-agnostic via `AstStruct[AST]`, but `Encoder.derived` is hardcoded to `JsonValue`. This asymmetry prevents Melian's format-agnostic response encoding.

**Change**: Add `AstBuilder[AST]` trait to Sarati as the encoding mirror of `AstStruct[AST]`.

```scala
// net.ghoula.sarati.codec.AstBuilder
trait AstBuilder[AST] {
  def createObject(fields: Map[String, AST]): AST
  def createArray(elements: List[AST]): AST
  def fromString(s: String): AST
  def fromNumber(n: Double): AST
  def fromBoolean(b: Boolean): AST
  def fromNull: AST
}
```

**Given instances** for all four AST types:

- `AstBuilder[JsonValue]` -- direct mapping (Object, Array, Str, Number, Bool, Null)
- `AstBuilder[YamlValue]` -- direct mapping (Mapping, Sequence, String, Float, Boolean, Null)
- `AstBuilder[TomlValue]` -- direct mapping (InlineTable, Array, String, Float, Boolean). Note: TOML has no null.
- `AstBuilder[XmlNode]` -- sensible default: fields as child elements, primitives as text content

**XML default mapping**:

```scala
given AstBuilder[XmlNode] with {
  def createObject(fields: Map[String, XmlNode]) =
    XmlNode.Element(qname("object"), List.empty,
      fields.map { case (k, v) =>
        XmlNode.Element(qname(k), List.empty, List(v))
      }.toList)
  def createArray(elements: List[XmlNode]) =
    XmlNode.Element(qname("array"), List.empty, elements)
  def fromString(s: String) = XmlNode.Text(s)
  def fromNumber(n: Double) = XmlNode.Text(if n.isWhole then n.toLong.toString else n.toString)
  def fromBoolean(b: Boolean) = XmlNode.Text(b.toString)
  def fromNull = XmlNode.Text("")
}
```

This default handles config files, data exchange, and simple API payloads. For XML-specific concerns (attributes, namespaces, text vs element content), the encoder can be hand-written or extended in the future with Scala 3 annotations (`@xmlAttribute`, `@xmlTextContent`). This follows the same evolutionary path as JAXB, serde_xml_rs, and Go's encoding/xml -- all of which ship a sensible default and add annotation-driven customization based on demand.

**Cross-ecosystem validation**: Format-agnostic encoding that includes XML is a proven pattern in JAXB (Java), Jackson XML (Java), serde + serde_xml_rs (Rust), encoding/xml (Go), kotlinx.serialization (Kotlin), and System.Xml.Serialization (C#/.NET).

### B.3 Encoder.derived Becomes Format-Agnostic

**Change**: Replace the hardcoded JSON encoder generation with `AstBuilder`-based generation.

Current (`Encoder.scala` lines 36-44):
```scala
TypeRepr.of[To].typeSymbol.fullName match {
  case "net.ghoula.sarati.ast.json.JsonValue" =>
    generateJsonEncoder[A](...)
  case other =>
    report.errorAndAbort(s"Encoder derivation supports JsonValue. Got $other")
}
```

Target:
```scala
val astBuilderExpr = Expr.summon[AstBuilder[To]].getOrElse(
  report.errorAndAbort(
    s"Encoder derivation requires an AstBuilder[${TypeRepr.of[To].show}] instance. " +
      "Supported types: JsonValue, TomlValue, YamlValue, XmlNode."
  )
)
// Generate using astBuilder.createObject, astBuilder.fromString, etc.
```

The generated encoder calls `astBuilder.createObject(fields)` instead of `JsonValue.Object(fields)`. Same macro pattern, generic over AST type.

### B.4 FieldTransformer Integration

**Problem**: `FieldTransformer` (SnakeCase, KebabCase, ScreamingSnakeCase) is defined in Sarati but unused by `Decoder.derived` and `Encoder.derived`. Field name matching is always exact.

**Change**: Wire `FieldTransformer` into both `Decoder.derived` and `Encoder.derived` via a given parameter with a default.

```scala
// Default given in sarati.codec package
given defaultFieldTransformer: FieldTransformer = IdentityFieldTransformer

// Decoder.derived picks it up
inline def derived[From, To](
  using m: Mirror.ProductOf[To], ft: FieldTransformer
): Decoder[From, To]

// Encoder.derived picks it up
inline def derived[A, To](
  using m: Mirror.ProductOf[A], ft: FieldTransformer
): Encoder[A, To]
```

In the generated decoder: `struct.getField(value, ft.transformFieldName(fieldName))`.
In the generated encoder: the field map uses `ft.transformFieldName(fieldName)` as keys.

Users who work with snake_case APIs override the given in scope:

```scala
given FieldTransformer = FieldTransformers.SnakeCase

// All derived codecs in this scope now transform camelCase <-> snake_case
given Decoder[JsonValue, MyApiResponse] = Decoder.derived
given Encoder[MyApiResponse, JsonValue] = Encoder.derived
```

### B.5 Rumil: Resilient JSON Parser Variant

**Problem**: The current `parseJson` stops at the first syntax error. Melian's Girdle needs accumulated errors across all pipeline stages.

**Change**: Add `parseJsonResilient` to Rumil's `parsers.json` package. Uses `recover` combinators at object-member and array-element boundaries to continue past syntax errors.

```scala
// Existing -- strict, fails on first error
def parseJson(input: String): Result[ParseError, JsonValue]

// New -- resilient, accumulates errors via Result.Partial
def parseJsonResilient(input: String): Result[ParseError, JsonValue]
```

The resilient variant wraps structural boundaries (object members, array elements) with Rumil's `recover` combinator. On syntax error, it records the error, skips to the next recoverable point (`,` or closing delimiter), and continues. Returns `Result.Partial(bestEffortAst, allErrors, consumed)`.

The same pattern can be applied to XML, TOML, and YAML parsers as demand arises.

### B.6 Summary: Change Scope

| Change | Library | Size | Breaks downstream? |
|--------|---------|------|-------------------|
| Move formatters | Sarati (gain), Rumil (lose) | ~150 lines moved | Import changes only (Aule, Strongbow) |
| Add `AstBuilder` trait + 4 givens | Sarati | ~60 lines new | No |
| Make `Encoder.derived` generic | Sarati | ~30 lines changed | No (existing `Encoder[A, JsonValue]` derivations still work) |
| Wire `FieldTransformer` into codecs | Sarati | ~10 lines changed | No (default is `IdentityFieldTransformer`) |
| Add `parseJsonResilient` | Rumil | ~50 lines new | No (additive) |
| Add `formatYaml` | Sarati | ~40 lines new | No (additive) |

Total: ~340 lines of changes across Sarati and Rumil, no breaking changes to downstream consumers beyond import path updates for the formatter relocation.
