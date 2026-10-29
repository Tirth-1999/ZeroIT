# ZeroIT local wire conformance

ZER-25: standalone Kotlin/JVM implementation of the manager-accepted `zeroit-local-conformance-v1` subset. This module is separate from the Android/iOS Gradle build. It loads no app repositories, Room builders, UI, platform hosts or network services.

## Run

From the project root:

```sh
JAVA_HOME=/path/to/jdk GRADLE_USER_HOME=/path/to/populated-cache ./protocol-conformance/run.sh
```

Requires JDK 17+ and the project's Gradle 8.11.1 wrapper. Supply `JAVA_HOME` and `GRADLE_USER_HOME` explicitly for reproducible execution; otherwise the Gradle wrapper uses its normal host defaults. The runner has no private `reference/` or documentation-tree dependency. Kotlin JVM plugin/compiler/stdlib 2.2.21 and resolved dependencies are pinned in the standalone build and lockfile. This run is deliberately offline: a cold cache fails rather than downloading dependencies. Populate an approved local cache separately if needed. Gradle's host daemon needs a localhost socket; the sandboxed validation used an approved escalation for that socket. No device task or Android environment script is invoked.

The `conformance` JavaExec task compiles main/test Kotlin to JVM 17 bytecode and runs an assertion-based runner with no JUnit dependency. Standalone `check` also depends on conformance. Results go to `build/reports/conformance.json`; `validation-result.json` records the October 28 portability qualification, including workspace and clean-export passes, negative fixture guards and source hashes. It does not validate later source changes; refresh it only after an actual passing run of the changed revision. Build outputs and Gradle state are ignored. No accepted contract generator is run.

## Implementation

- `Json.kt`: lossless tree with defensive immutable collection snapshots; strict UTF-8 decoding, decoded-key duplicate rejection, valid surrogate pairs, JSON syntax/byte/depth bounds and integer-only safe-counter profile. Canonicalizer produces exact accepted-profile UTF-8 and SHA-256, with ASCII property ordering, minimal escapes, no normalization, and significant array order. It is not a general floating-point RFC8785 implementation.
- `Schema.kt`: structural evaluator of exactly the vocabulary present in the accepted 50-definition schema. It supports local references, closed properties, tagged unions, conditionals, bounds, patterns and strict UTC calendar dates. Unsupported keywords, external references or formats fail closed. It is not a general-purpose JSON Schema library or metaschema validator. The accepted schema was independently validated before this assignment; the runner verifies its exact digest.
- `Wire.kt` / `Responses.kt`: named read-only DTO projections for supported commands/payloads, receipts/results, discovery/feeds, snapshots, bootstrap, routing and errors. Decimal/UUID strings are retained exactly. Schema validation precedes selected semantics and DTO projection. A canonical immutable tree accompanies each message for byte-exact hashing; consumers must not treat unvalidated manually constructed DTOs as authorized work. Token-bearing default message rendering is redacted; explicitly logging raw tree/string fields is still prohibited in future integration.
- `Semantics.kt`: separate pure checks matching the existing Python oracle: signed-bigint revisions, exact allocation sums/order/EQUAL remainders, USD/JPY/KWD fixture scales, group-create ID equality, duplicate/self-dependencies, feed contiguity/high-water/privacy and snapshot count/lease/hash. These checks are not membership authorization or transaction execution.
- `Conformance.kt` / `DtoAssertions.kt`: all 77 unchanged manifest cases with schema/semantic expectations kept separate, exact canonical/hash vector, connected-story reconstruction and balances, parser/canonical rejection checks and named DTO/immutable-tree assertions. All 80 bundled golden-file hashes (77 fixtures, schema, expectations and hash vector) are verified before and after execution, and the exact bundle file set must match `golden/manifest.json`. No golden expectation is rewritten.

## Scope and evidence

`golden/manifest.json` records the public synthetic bundle scope, revision, provenance and hashes. The golden bytes were copied unchanged from the manager-accepted October 26 local conformance revision. Private contract approval and historical review documents are not runtime inputs and are not bundled. The public bundle does not expand that accepted scope. The module tests synthetic host data only. It does not implement Ktor routes, managed Auth/session validation, database constraints/RLS/transactions, receipt-alias persistence, cursor signing, import/claims, settlements, mobile queue/cache/migrations, cloud deployment or device convergence. No production permission, entropy, operational recovery or public-release claim follows from these checks.

The release agent owns staging/commit/push. This module does not run Git mutation, access installed ledgers, contact a cloud account or start a device.
