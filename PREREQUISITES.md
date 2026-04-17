# Melian Prerequisites: Sarati & Rumil Upstream Changes

Implementation plan for the upstream changes described in DESIGN.md Addendum B.
All work is done on feature branches. Each phase ends with a published release to local Forgejo Maven.

## Phase 1 — Sarati

**Branch**: `feature/melian-prerequisites` on `/home/hakim/examples/sarati`

### 1.1 Move `formatJson` from Rumil into Sarati

- Copy `formatJson`, `formatJsonValue` from Rumil's `parsers/json/JsonParser.scala` into Sarati's `ast/json/JsonTypes.scala` (or a new `JsonFormat.scala` in the same package)
- `JsonFormatConfig`, `compactFormat`, `prettyFormat` are already in Sarati — the formatter joins them
- Ensure the function signature is identical: `def formatJson(value: JsonValue, config: JsonFormatConfig = compactFormat): String`

### 1.2 Move `formatXml` from Rumil into Sarati

- Copy `formatXml`, `formatXmlDocument`, `formatQName`, `escapeText`, `escapeAttr` from Rumil's `parsers/xml/XmlParser.scala` into Sarati's `ast/xml/XmlTypes.scala` (or a new `XmlFormat.scala`)
- Signatures: `def formatXml(node: XmlNode, indent: Int = 2, depth: Int = 0): String` and `def formatXmlDocument(doc: XmlDocument, indent: Int = 2): String`

### 1.3 Add `formatYaml` to Sarati

- New formatter in `ast/yaml/YamlTypes.scala` (or `YamlFormat.scala`)
- Handles all `YamlValue` cases: Null, Boolean, Integer, Float, String, Sequence, Mapping
- Block style by default (indentation-based), with optional flow style

### 1.4 Add `AstBuilder` trait and given instances

- New file: `codec/AstBuilder.scala`
- Trait with 6 methods: `createObject`, `createArray`, `fromString`, `fromNumber`, `fromBoolean`, `fromNull`
- Given instances for `JsonValue`, `YamlValue`, `TomlValue`, `XmlNode`
- XML default: fields as child elements, primitives as text content

### 1.5 Make `Encoder.derived` format-agnostic

- Replace hardcoded JSON check in `Encoder.scala` with `Expr.summon[AstBuilder[To]]`
- Generated encoder uses `astBuilder.createObject(fields)` instead of `JsonValue.Object(fields)`
- Existing `Encoder[A, JsonValue]` derivations must still compile and produce identical results

### 1.6 Wire `FieldTransformer` into `Decoder.derived` and `Encoder.derived`

- Add `given defaultFieldTransformer: FieldTransformer = IdentityFieldTransformer` to codec package
- Add `ft: FieldTransformer` as a using parameter to both `Decoder.derived` and `Encoder.derived`
- Decoder: `struct.getField(value, ft.transformFieldName(fieldName))`
- Encoder: field map keys use `ft.transformFieldName(fieldName)`
- All existing derivations continue to work unchanged (default is identity)

### 1.7 Verify and publish

- `sbt compile` — zero warnings, zero errors
- `sbt test` — all existing tests pass
- Add tests for new functionality:
  - `AstBuilder` round-trip: encode via `Encoder.derived` then decode via `Decoder.derived` for all 4 formats
  - `FieldTransformer`: derive with SnakeCase, verify field names in encoded output
  - `formatYaml`: basic formatting tests
- Bump version in `build.sbt` (non-snapshot release)
- `sbt publish` to local Forgejo Maven

---

## Phase 2 — Rumil

**Branch**: `feature/melian-prerequisites` on `/home/hakim/examples/rumil`
**Depends on**: Phase 1 published Sarati version

### 2.1 Update Sarati dependency

- Update `saratiVersion` in `build.sbt` to the version published in Phase 1

### 2.2 Remove relocated formatters

- Remove `formatJson`, `formatJsonValue` from `parsers/json/JsonParser.scala`
- Remove `formatXml`, `formatXmlDocument`, `formatQName`, `escapeText`, `escapeAttr` from `parsers/xml/XmlParser.scala`

### 2.3 Update Rumil's own tests

- `parsers/json/JsonParserTests.scala`: change `formatJson` import from `parsers.json` to `net.ghoula.sarati.ast.json`
- `parsers/xml/XmlParserTests.scala`: change `formatXml`/`formatXmlDocument` imports from `parsers.xml` to `net.ghoula.sarati.ast.xml`
- Any benchmarks importing formatters: update similarly

### 2.4 Add `parseJsonResilient`

- New function in `parsers/json/JsonParser.scala` (or a new `JsonParserResilient.scala`)
- Uses `recover` combinators at structural boundaries:
  - Object member level: on parse error within a member, skip to next `,` or `}`, record error, continue
  - Array element level: on parse error within an element, skip to next `,` or `]`, record error, continue
- Returns `Result.Partial(bestEffortJsonValue, allErrors, consumed)` when recovery occurs
- Returns `Result.Success` when no errors (identical behavior to strict parser on valid input)
- Strict `parseJson` remains unchanged

### 2.5 Verify and publish

- `sbt compile` — zero warnings, zero errors
- `sbt test` — all existing tests pass (with updated imports)
- Add tests for `parseJsonResilient`:
  - Valid JSON produces `Result.Success` (same as `parseJson`)
  - Missing comma in object: recovers, reports error, parses remaining fields
  - Malformed array element: recovers, reports error, parses remaining elements
  - Multiple errors accumulated in single parse
- Bump version in `build.sbt` (non-snapshot release)
- `sbt publish` to local Forgejo Maven

---

## Phase 3 — Downstream Import Fixes

**Depends on**: Phase 2 published Rumil version

### 3.1 Aule

**Branch**: `feature/sarati-imports` on `/home/hakim/examples/aule`

Update `saratiVersion` and `rumilVersion` in `build.sbt`. Change all `import parsers.json.formatJson` to `import net.ghoula.sarati.ast.json.formatJson` in:

- `src/main/scala/net/ghoula/aule/sentinel/feedback/FeedbackRegistry.scala`
- `src/main/scala/net/ghoula/aule/llm/provider/MistralProvider.scala`
- `src/main/scala/net/ghoula/aule/tools/ForgejoTool.scala`
- `src/main/scala/net/ghoula/aule/mcp/McpHttpBridge.scala`
- `src/main/scala/net/ghoula/aule/person/PersonStore.scala`
- `src/main/scala/net/ghoula/aule/lsp/JsonRpcClient.scala`
- `src/main/scala/net/ghoula/aule/persistence/Memory.scala`
- `src/main/scala/net/ghoula/aule/turn/TurnPipeline.scala`
- `src/test/scala/net/ghoula/aule/Phase4Spec.scala`
- `src/test/scala/net/ghoula/aule/Phase5Spec.scala`
- `src/test/scala/net/ghoula/aule/Phase5Step2Spec.scala`
- `src/test/scala/net/ghoula/aule/Phase6Spec.scala`
- `src/test/scala/net/ghoula/aule/BridgeIntegrationSpec.scala`

Verify: `sbt compile`

### 3.2 Strongbow

**Branch**: `feature/sarati-imports` on `/home/hakim/examples/strongbow`

Update `saratiVersion` and `rumilVersion` in `build.sbt`. In `strongbow-core/src/main/scala/net/ghoula/strongbow/interpreter/ExprInterpreter.scala`:

- Change `import parsers.json.{formatJson, parseJson}` to:
  ```scala
  import net.ghoula.sarati.ast.json.formatJson
  import parsers.json.parseJson
  ```

Verify: `sbt compile`
