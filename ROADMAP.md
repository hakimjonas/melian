# Melian Roadmap

Single source of truth for Melian's state and plan. Supersedes the old `NEXT_SESSION.md` and `PREREQUISITES.md`. Architecture detail lives in `DESIGN.md`; the backend-library plan lives in `eru-backend-libraries-plan.md`.

## Current state (2026-09)

The Arda ecosystem is published to Maven Central under `net.ghoula`, GPL-3.0-or-later, on GitHub with branch protection:

| Library | Version | Purpose |
|---------|---------|---------|
| `eru` | 1.0.0-alpha | Effect system on Java virtual threads |
| `eru-http` | 1.0.0-alpha | HTTP client and server |
| `sarati` | 1.0.0-alpha | Binary codec, format ASTs, XPath evaluator |
| `rumil` | 1.0.0-alpha | Parser combinators |

Melian is aligned with the ecosystem but not released:

- Dependencies resolve the published artifacts: `eru-http`, `sarati`, `rumil` at 1.0.0-alpha; `valar` at 0.6.0; `munit` at 1.3.5.
- Toolchain: sbt 2.0.7, Scala 3.8.4, JDK 25.
- Publishing is configured for Maven Central (`localStaging` + `sonaRelease`, signed by sbt-pgp), but no tag has been cut.
- `main` branch, GPL-3.0-or-later license, GitHub workflows, and governance files are in place. The repository has no remote yet.
- All modules compile under `-Werror`; the test suite passes.

## Done — former PREREQUISITES.md

The upstream work Melian depended on is shipped in the released artifacts:

- **sarati**: `formatJson` / `formatXml` / `formatYaml`, the `AstBuilder` trait, and `FieldTransformer` wiring in `Encoder.derived` / `Decoder.derived`.
- **rumil**: sarati dependency bumped, relocated formatters removed, `parseJsonResilient` added.

## Pending

### Downstream dependency updates

- **aule** — still on pre-release artifacts (`eru-http 0.9.0+68`, `rumil 0.3.0+91`, `sarati 0.2.2`) and the old `parsers.json.formatJson` import. Needs a version bump and the `net.ghoula.sarati.ast.json.formatJson` import fix.
- **strongbow** — versions already at 1.0.0-alpha, but `strongbow-core` still imports `parsers.json.{formatJson, parseJson}`. Rumil removed that formatter, so the import must move to `net.ghoula.sarati.ast.json.formatJson` or the module will not compile.

### Melian release

Blocked on the deployment and polish work below. When ready:

1. Create the GitHub repository and push `main`.
2. Cut `v1.0.0-alpha`; the release workflow publishes `melian-core`, `melian-router`, `melian-openapi`, `melian-server`, and `melian-test`.

### First deployment — arda-web

Replace the hand-rolled ~200-line eru-http server in `/home/hakim/google/arda-web/server/` with Melian. `StaticFiles`, `SecurityHeaders`, `ErrorPages`, and `Router` cover the current site's needs.

### Backend libraries

Standalone `eru-*` libraries, one per backend (see `eru-backend-libraries-plan.md`), in priority order: `eru-sqlite`, `eru-postgres`, `eru-redis`, `eru-mongodb`, `eru-dynamodb`, `eru-neo4j`. Melian stays HTTP-only; persistence composes at the application level through bracket nesting.

### Melian polish

- OpenAPI: content negotiation and response headers in the spec
- Router: HEAD auto-generation from GET routes
- Testing: `MelianTestKit` ergonomics for endpoint tests
- Performance: benchmark against raw `eru-http`

## Known gaps

Two `asInstanceOf` remain in `RouteMacros.scala`:

1. Result value extraction (`v.asInstanceOf[a]`) — was blocked by a Scala 3.8.3 compiler bug; re-check whether 3.8.4 resolves it.
2. The `Endpoint` (`?=>`) to `Function1` boundary — a Scala language boundary, expected to be irreducible.
