# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-alpha] - 2026-09

First public release. Five modules: `melian-core`, `melian-router`, `melian-openapi`, `melian-server`, `melian-test`.

### Added

- Compile-time router with `get`, `head`, `post`, `put`, `delete`, `patch`, and `query` (RFC 10008) methods.
- Typed parameter markers: `Path[A]`, `Query[name, A]`, `Header[A]`, `Json[A]`, `Coded[A]`, `Form[A]`.
- Typed response wrappers: `Ok[A]`, `Created[A]`, `Accepted[A]`, `NoContent`, `SeeOther`, `NotModified`, `EventStream[A]`.
- RFC 9457 problem-details rendering for extraction, parse, decode, and validation errors.
- `ErrorRenderer[E]` for rendering domain errors into responses.
- OpenAPI 3.1 generation from compile-time route metadata, with `servers`, per-operation `summary`/`description`/`tags`, response headers, and standard error responses.
- `HEAD` auto-derivation from `GET` routes, including `Content-Length` preservation.
- Duplicate-route detection at router-build time (a repeated path+method, or routes differing only in parameter name, fails `build`).
- `Coded[A]` content-type dispatch across JSON, XML, and YAML; `CodedDecoder.derived` and `FormDecoder.derived` for case classes.
- Decode warnings for `Json[A]` bodies, surfaced through `RequestContext.warnings` for Endpoint handlers.
- Middleware for static files (with ETag and `If-None-Match`/`If-Modified-Since` conditional requests), security headers, error pages, and health/readiness endpoints.
- `MelianTestKit` for exercising routers without a running server.
- A micro-benchmark (`RouterBenchmark`) comparing dispatch against a raw `eru-http` handler.
