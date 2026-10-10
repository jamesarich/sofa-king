# Backend Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution, or superpowers:subagent-driven-development if James chooses delegation. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a locally runnable Kotlin server with a truthful native bootstrap API, persistent server identity and tested persistent source quarantine/recovery.

**Architecture:** Two Gradle JVM modules: `api-contract` for serializable wire DTOs and `server` for Ktor, SQLite and a pure source-health policy. This foundation contains no production ingestion, playback relay or client changes; it provides tested interfaces for the subsequent catalog/guide and playback slices.

**Tech Stack:** JDK 21; Gradle 9.3.1; Kotlin/JVM + serialization 2.3.21; Ktor Netty/test host 3.5.2; kotlinx.serialization 1.11.0; Exposed JDBC DSL 1.3.0; Xerial SQLite JDBC 3.50.2.0; kotlin-test/JUnit. These Maven artifacts were verified to exist; compilation compatibility is verified by Task 1, not assumed from artifact availability. Record these pins in `gradle/libs.versions.toml`.

**Spec:** `docs/superpowers/specs/2026-10-10-backend-design.md`.

## Global Constraints

- Sofa King is the only target client.
- Reliable live viewing, the guide, channel surfing and the existing preview-to-player experience come first.
- Neither the API nor the media protocol needs to reproduce Dispatcharr.
- No microservices, Redis, Kafka, external search server, HDHomeRun emulator or Xtream output in the initial design.
- SQLite in WAL mode on a local filesystem; Exposed JDBC DSL and versioned SQL migrations.
- Never perform network or media work inside a transaction.
- Channel UUIDs, favorites, number assignments and guide mappings survive losing an offering.
- A catalog re-sync cannot reset quarantine or manual disables.
- This slice runs locally with no production network access or credentials. Default bind is `127.0.0.1:8080`; unimplemented capabilities are reported as false.

## Review Focus

1. Restart with the same data directory must preserve server identity and health state.
2. Repeated reports for one failed upstream worker must not multiply strikes.
3. Provider/auth/rate-limit/local/device failures must not cause offering-wide quarantine.
4. Recovery results arriving early, out of order or after manual disable must not enable a source.
5. A failed database migration must not leave a partial schema advertised as ready.

## Scope and follow-on slices

The parent architecture covers several subsystems. This plan implements only the foundation. Provider discovery, catalog import, guide querying, device pairing, SSE, relay/remux, recovery probes/job scheduling, automatic archival and DVR each remain separate planned work. Source-health policy can calculate a recovery due time in this slice; no recovery network job runs yet. No production bad sources are culled by this foundation.

## File map

| Path | Responsibility |
| --- | --- |
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, Gradle wrapper | Reproducible two-module JVM build |
| `api-contract/build.gradle.kts`, `api-contract/src/main/kotlin/dev/jamesrich/sofaking/backend/api/Bootstrap.kt` | Bootstrap, capabilities and error DTOs |
| `server/build.gradle.kts` | App entry point, runtime/test dependencies |
| `server/src/main/kotlin/dev/jamesrich/sofaking/backend/Main.kt` | Validated env configuration, store/engine ownership and shutdown |
| `server/src/main/kotlin/dev/jamesrich/sofaking/backend/http/Application.kt` | Health/readiness and bootstrap routes |
| `server/src/main/kotlin/dev/jamesrich/sofaking/backend/store/DatabaseFactory.kt` | Local SQLite connection, busy timeout, WAL and transaction dispatcher |
| `server/src/main/kotlin/dev/jamesrich/sofaking/backend/store/Migrations.kt` | Transactional numbered migrations and schema-version validation |
| `server/src/main/resources/db/V001__foundation.sql` | Metadata, offering-health and observation tables |
| `server/src/main/kotlin/dev/jamesrich/sofaking/backend/store/BackendStore.kt` | Persistent identity and atomic health updates |
| `server/src/main/kotlin/dev/jamesrich/sofaking/backend/health/SourceHealth.kt` | Immutable state, observations, failure classes and policy settings |
| `server/src/main/kotlin/dev/jamesrich/sofaking/backend/health/HealthPolicy.kt` | Pure deterministic quarantine/probation reducer |
| `server/src/test/kotlin/dev/jamesrich/sofaking/backend/http/ApplicationTest.kt` | Native API integration tests |
| `server/src/test/kotlin/dev/jamesrich/sofaking/backend/store/BackendStoreTest.kt` | Real-file persistence/migration/dedup tests |
| `server/src/test/kotlin/dev/jamesrich/sofaking/backend/health/HealthPolicyTest.kt` | Time and failure-domain rules |

## Build configuration reference

The Gradle 9.3.1 distribution checksum verified from the official distribution service is `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`. Set `distributionSha256Sum` in the wrapper properties.

`settings.gradle.kts`:

```kotlin
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "sofa-king-backend"
include(":api-contract", ":server")
```

Root `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
}
allprojects {
    group = "dev.jamesrich.sofaking"
    version = "0.1.0-SNAPSHOT"
}
```

Both modules apply Kotlin JVM and serialization; `server` also applies `application` with `mainClass = "dev.jamesrich.sofaking.backend.MainKt"`. Configure `kotlin { jvmToolchain(21) }`. Add Ktor server-core/netty/content-negotiation/status-pages/serialization-kotlinx-json at 3.5.2, Exposed core/jdbc at 1.3.0 and sqlite-jdbc at 3.50.2.0 to server; add serialization-json 1.11.0 to contract/server. Use `kotlin("test-junit")` and Ktor test-host 3.5.2 for server tests. Use Gradle dependency locking and write lockfiles during the first passing dependency resolution. Avoid adding a logging framework until the runtime smoke check identifies what is needed.

## Task 1: Runnable bootstrap with persistent server identity

**Files:** Build and wrapper files, `Bootstrap.kt`, `Main.kt`, `Application.kt`, database/migration/store files and both HTTP/store test files from the map.

**Interfaces:**

```kotlin
// api-contract: strings are UUIDs/ISO UTC instants, not database objects.
@Serializable data class Capabilities(
    val catalog: Boolean = false,
    val guide: Boolean = false,
    val playback: Boolean = false,
    val events: Boolean = false,
)
@Serializable data class Bootstrap(
    val apiVersion: Int = 1,
    val serverId: String,
    val serverTime: String,
    val capabilities: Capabilities = Capabilities(),
)
@Serializable data class ApiError(val code: String, val message: String, val retryable: Boolean)

// server: exact interface implemented by BackendStore.
interface IdentityStore {
    suspend fun serverId(): String
    suspend fun ready(): Boolean
}
fun Application.backendModule(store: IdentityStore, clock: Clock)
// BackendStore.open performs migration before publishing a usable store.
suspend fun openStore(dataDirectory: Path): BackendStore
// BackendStore implements IdentityStore and AutoCloseable.
```

- [ ] **Step 1: Add build configuration and failing behavior tests.** Use the official Gradle wrapper with a distribution checksum, Java toolchain 21, version catalog and dependency locking. Tests depend on Kotlin test/JUnit and Ktor test host. Add serialization to both modules; `server` depends on `api-contract`. Add minimal interface/type shells to compile tests, with empty routes and an unpersisted identity as the initial red baseline.

Tests must include these assertions, expanded into named tests using real temporary directories:

```kotlin
@Test fun `bootstrap is native and truthful`() = testApplication {
    val dir = createTempDirectory()
    val store = openStore(dir)
    try {
        application { backendModule(store, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)) }
        val response = client.get("/api/v1/bootstrap")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<Bootstrap>(response.bodyAsText())
        UUID.fromString(body.serverId)
        assertEquals("1970-01-01T00:00:00Z", body.serverTime)
        assertEquals(Capabilities(), body.capabilities)
    } finally { store.close() }
}

@Test fun `identity survives restart`() = runBlocking {
    val dir = createTempDirectory()
    val first = openStore(dir).use { it.serverId() }
    val second = openStore(dir).use { it.serverId() }
    assertEquals(first, second)
}
```

Also test `/healthz` returns 200 without creating runtime state; `/readyz` returns 503 for an injected non-ready `IdentityStore`; unknown API paths return JSON `ApiError("NOT_FOUND", …)` and 404. Temporary directories are cleaned in test teardown.

- [ ] **Step 2: Run red tests.** Run `./gradlew :server:test --tests '*ApplicationTest' --tests '*BackendStoreTest'`. Expect route assertions to fail on 404 and restart identity assertion to fail on changing UUIDs; resolve compile/dependency errors before recording the behavior failure.

- [ ] **Step 3: Implement the runnable boundary and identity persistence.** `Main.kt` reads `SOFA_HOST`, `SOFA_PORT`, `SOFA_DATA_DIR`; defaults localhost/8080/`data`. Reject ports outside 1–65535 before binding. Run blocking JDBC work on a bounded dispatcher. Migration creates metadata/schema-version tables inside one transaction. Generate a server UUID with insert-if-absent semantics, then read the stored value; never regenerate it on restart. Install JSON content negotiation and structured status-page errors; bootstrap uses the injected clock and persistent ID. Close engine/store/dispatcher on shutdown. Use `embeddedServer(Netty, …)`; no production network calls.

Migration metadata SQL:

```sql
CREATE TABLE schema_version(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL);
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
```

Readiness checks usable migrated storage; liveness does not claim provider/media health. Document the local run command and the four implemented routes.

- [ ] **Step 4: Verify green and packaging.** Run `./gradlew test :server:installDist`. Start the installed distribution with a temporary data directory and verified-free local port, request `/healthz`, `/readyz`, `/api/v1/bootstrap`, stop/restart and compare server IDs. Stop the process and delete only the task's temporary directory.

- [ ] **Step 5: Commit.** `git add` the exact build/source/test/README changes; commit `feat: add native bootstrap and persistent server identity`.

## Task 2: Deterministic source quarantine and recovery policy

**Files:** `SourceHealth.kt`, `HealthPolicy.kt`, `HealthPolicyTest.kt`.

**Consumes:** `java.time.Instant` and a caller-provided decision time. No HTTP/database dependencies.

**Produces:**

```kotlin
enum class HealthStatus { UNVERIFIED, HEALTHY, SUSPECT, QUARANTINED, PROBATION, ARCHIVED }
enum class FailureClass { OFFERING, PROVIDER, AUTH, RATE_LIMIT, LOCAL, DEVICE }
data class Observation(
    val incidentId: String,
    val observedAt: Instant,
    val qualifiedSuccess: Boolean,
    val failureClass: FailureClass? = null,
)
data class HealthState(
    val status: HealthStatus = HealthStatus.UNVERIFIED,
    val manuallyDisabled: Boolean = false,
    val failures: List<Observation> = emptyList(),
    val lastQualifiedAt: Instant? = null,
    val probationStartedAt: Instant? = null,
    val nextCheckAt: Instant? = null,
    val quarantineAttempts: Int = 0,
)
class HealthPolicy {
    fun apply(state: HealthState, observation: Observation, now: Instant): HealthState
}
```

Observation invariants: nonblank incident ID; exactly one of qualified success or a failure class; no future observation relative to `now`. Invalid input throws `IllegalArgumentException`. Reducer state stores bounded failure history, not a permanently growing incident ledger; the persistent store owns exact deduplication in Task 3.

- [ ] **Step 1: Write failing policy tests.** Create the types and a compiling baseline reducer returning unchanged state; test the observable transitions, not private counters. Use fixed UTC times and no sleeps.

```kotlin
@Test fun `three independent failures quarantine an offering`() {
    val policy = HealthPolicy()
    val start = Instant.parse("2026-10-10T00:00:00Z")
    var state = HealthState(status = HealthStatus.HEALTHY)
    for (minutes in listOf(0L, 5L, 10L)) {
        val at = start.plusSeconds(minutes * 60)
        state = policy.apply(state, Observation("failure-$minutes", at, false, FailureClass.OFFERING), at)
    }
    assertEquals(HealthStatus.QUARANTINED, state.status)
    assertEquals(start.plusSeconds(25 * 60), state.nextCheckAt)
}
```

Add separate named tests for: one failure becomes suspect; three failures under ten minutes stay suspect; failures older than six hours expire; a qualified success clears the failure streak; duplicate incident IDs in current history do not count twice; each non-offering failure class leaves offering strikes unchanged; future/blank/inconsistent observations reject; old observations preceding the last qualified success do not resurrect strikes; first quarantine success starts probation; second success before ten minutes keeps probation; second at ten minutes restores healthy; manual disable survives success; probation failure returns to quarantine; recovery failure schedules increasing bounded backoff.

- [ ] **Step 2: Run red tests.** `./gradlew :server:test --tests '*HealthPolicyTest'`. Expect state assertions to fail against the unchanged-state reducer.

- [ ] **Step 3: Implement pure transitions.** Count only distinct offering-failure incidents after the latest qualified success within `[now−6h, now]`. Quarantine requires three incidents and a ten-minute first-to-last span. First recovery due is 15 minutes after quarantine; unsuccessful recovery attempts schedule one hour, six hours, then daily. A qualified quarantine success enters probation; another success at least ten minutes later restores healthy. Preserve manual disable on every path. Probation failure re-quarantines and carries prior recovery history. Device/provider/auth/rate-limit/local observations are not offering strikes. Archived/manual-disabled states do not become healthy automatically in this slice; archive recovery will be a later explicit policy action.

Keep selection eligibility separate: manually disabled, quarantined, probation and archived are ineligible; unverified eligibility requires a later qualifying path. The reducer does not reorder sources or hide channels.

- [ ] **Step 4: Verify green.** `./gradlew test`. Ensure every failure-domain and time-boundary test passes and no wall-clock waiting was introduced.

- [ ] **Step 5: Commit.** Commit `feat: define reversible source quarantine policy`.

## Task 3: Persistent atomic health observations

**Files:** `BackendStore.kt`, new `V002__offering_health.sql`, `Migrations.kt`, `BackendStoreTest.kt`.

**Consumes:** `HealthPolicy`, `HealthState`, `Observation` and the database factory from earlier tasks.

**Produces:**

```kotlin
// Members of BackendStore, called by future adapters/jobs, not unauthenticated HTTP.
suspend fun registerOffering(offeringId: String)
suspend fun health(offeringId: String): HealthState?
suspend fun recordObservation(offeringId: String, observation: Observation, now: Instant): HealthState
suspend fun setManuallyDisabled(offeringId: String, disabled: Boolean): HealthState
```

- [ ] **Step 1: Write failing real-file tests.** Add compiling method shells without persistence, then assert register/reopen/observe behavior and exact deduplication. Never use a mocked database.

```kotlin
@Test fun `registration cannot clear a persisted quarantine`() = runBlocking {
    val dir = createTempDirectory()
    val at = Instant.parse("2026-10-10T00:00:00Z")
    openStore(dir).use { store ->
        store.registerOffering("provider:station")
        for (minute in listOf(0L, 5L, 10L)) {
            val time = at.plusSeconds(minute * 60)
            store.recordObservation("provider:station", Observation("incident-$minute", time, false, FailureClass.OFFERING), time)
        }
    }
    openStore(dir).use { store ->
        store.registerOffering("provider:station")
        assertEquals(HealthStatus.QUARANTINED, store.health("provider:station")!!.status)
    }
}
```

Also test: replaying the same incident after restart is a no-op; two concurrent reports for the same offering/incident cannot double count; registering another offering does not alter existing state; a success persists probation; manual disable survives registration/restart/qualified success; unknown offering observation rejects rather than inventing identity; failed migration leaves its version unapplied and startup fails; an unknown newer schema version fails startup with an actionable message. Tests clean temporary files after store closure.

- [ ] **Step 2: Run red tests.** `./gradlew :server:test --tests '*BackendStoreTest'`. Expect quarantine/restart/dedup assertions to fail against nonpersistent shells.

- [ ] **Step 3: Implement migration and atomic updates.** Use tables equivalent to:

```sql
CREATE TABLE offering_health(
    offering_id TEXT PRIMARY KEY,
    state_json TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE health_observation(
    offering_id TEXT NOT NULL REFERENCES offering_health(offering_id),
    incident_id TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    observation_json TEXT NOT NULL,
    PRIMARY KEY(offering_id, incident_id)
);
```

Serialize internal state through explicit storage DTOs; UUID/instant handling must not depend on platform object serialization. Registration is insert-if-absent. In one short serialized write transaction, check the incident ledger, load state, reduce with `HealthPolicy`, insert the observation and persist the resulting state. A duplicate returns the persisted state without reducing again. Manual override changes only its flag. Enable SQLite foreign keys and busy timeout; keep migration and schema version update atomic. An unexpected version is an error, never a request to recreate/drop data. Observation retention/pruning requires its own policy later; bounded test workload avoids premature unsafe deletion.

- [ ] **Step 4: Verify green and repeat smoke test.** Run `./gradlew test :server:installDist`. Smoke test localhost startup/readiness/bootstrap and graceful shutdown again. Inspect test report counts and process/resource cleanup. README clearly states no source fetching, scheduled recovery checks or media playback exists yet.

- [ ] **Step 5: Commit.** Commit `feat: persist source health and deduplicate failure incidents`.

## Completion and review

- [ ] Confirm the foundation implements only capabilities it advertises.
- [ ] Run the complete suite and installed-distribution smoke test with real SQLite.
- [ ] Review configuration, cancellation/shutdown, SQL transactions, manual-disable precedence, timestamps and duplicate-event handling.
- [ ] Record exact verification results and remaining limits in README/completion report.
- [ ] Keep production OMV state and Sofa King client untouched; no deployment/push is part of this plan.

Recommended execution: native in this new repository. The three tasks share store/policy interfaces, and a single implementer can run the full deterministic suite without parallel-edit conflicts. Independent review can follow the implementation if James selects that workflow.
