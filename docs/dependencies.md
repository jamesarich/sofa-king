# Server dependency policy and practices

Verified 2026-10-10 against official release documentation and Maven metadata. James explicitly requested bleeding-edge server-side prereleases; versions are reproducible pins, not dynamic ranges.

| Component | Selected version | Evidence |
| --- | --- | --- |
| Kotlin compiler/plugins/stdlib | 2.5.0-Beta1 | [Kotlin EAP](https://kotlinlang.org/docs/whatsnew-eap.html), Maven Central metadata |
| Ktor server/test/serialization integration | 3.6.0-eap-1682 | [Ktor EAP](https://ktor.io/eap/), official EAP metadata updated October 4 |
| kotlinx.serialization | 1.12.0-RC | [Releases](https://github.com/Kotlin/kotlinx.serialization/releases), Maven Central metadata |
| kotlinx.coroutines | 1.11.0 | Maven Central metadata; newest published version, later than its RC builds |
| Exposed JDBC/DSL | 1.5.1 | Maven Central metadata; newest published version |
| SQLite JDBC | 3.53.4.0 | Maven Central metadata; newest published version |
| Gradle | 9.9.0-milestone-2 | [Official versions registry](https://services.gradle.org/versions/all), checksum-pinned distribution |
| JVM toolchain | 21 | Explicit server toolchain/runtime; shared contract/client target JVM 17 bytecode |

Ktor artifacts are restricted to the official JetBrains EAP repository using Gradle exclusive content for `io.ktor`. All versions are locked. Do not select an obsolete RC just because it is labelled prerelease when a later stable release exists. Do not assume a prerelease combination works until the full build and runtime smoke check pass. Kotlin's published stable compatibility table does not certify this complete preview combination; local verification establishes the project's tested baseline.

## Practices applied

- Use the Kotlin JVM plugin, serialization plugin at the same compiler version, a version catalog and aligned JVM toolchains. The Kotlin BOM aligns stdlib and reflection libraries with the preview compiler. [Kotlin Gradle guidance](https://kotlinlang.org/docs/gradle-configure-project.html).
- Run Ktor as a self-contained Netty application with configurable host/port and explicit shutdown ownership. Keep the native wire DTO module separate from persistence and Android UI models. [Ktor server configuration](https://ktor.io/docs/server-create-and-configure.html).
- JDBC is blocking. Execute short Exposed transactions on an owned bounded dispatcher, supply the database explicitly, and keep network/media operations outside transactions. No ORM entities escape transaction scope. [Exposed transactions](https://www.jetbrains.com/help/exposed/transactions.html).
- Use local SQLite WAL, foreign keys, busy timeout, serialized writes and atomic versioned migrations. Failed migration or unknown future schema stops startup without erasing data. [SQLite WAL](https://sqlite.org/wal.html).
- Preserve coroutine cancellation; close owned resources on shutdown. Source-health policy accepts an explicit time and persistent incident deduplication, avoiding wall-clock sleeps and duplicate-worker strikes.
- Exercise API behavior through Ktor's test host and persistence against real disposable SQLite files. Verify the installed application over actual HTTP and across restart. [Ktor testing](https://ktor.io/docs/server-testing.html).

Experimental features are enabled only when they serve a concrete feature. Preview compiler dependencies do not require enabling every experimental language construct or HTTP/3 for LAN viewing.

## Verification and review

The contract/client target JVM 17 with JDK 21; the server targets JVM 21. Run the commands in [the README](../README.md#verification) whenever dependency pins change, after building `test :server:installDist`. Kotlin's preview metadata requires a compatible consuming compiler.

HLS behavior follows [RFC 8216](https://datatracker.ietf.org/doc/html/rfc8216); streaming responses and socket timeouts are bounded while live stream duration is unbounded. Automatic Ktor redirects are disabled so provider credentials stay within their configured origin. See [Ktor redirect configuration](https://ktor.io/docs/client-redirect.html) and [timeout configuration](https://ktor.io/docs/client-timeout.html). Server decode checks remain separate from [Media3 HLS device acceptance](https://developer.android.com/media/media3/exoplayer/hls).

Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html). Preserve cancellation in normal operations; use bounded non-cancellable cleanup only for resources that must be released. See [Kotlin cancellation guidance](https://kotlinlang.org/docs/cancellation-and-timeouts.html).

Documentation follows the [GitHub style guide](https://docs.github.com/en/contributing/style-guide-and-content-model/style-guide): clear task-oriented prose, sentence-case headings, descriptive links, language-tagged samples and uppercase placeholders. Repository Markdown does not use GitHub Docs' Liquid templates. Keep implemented behavior in usage/reference docs, future behavior in plans, and dated verification evidence in [review records](review-2026-10-10.md).
