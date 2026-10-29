# Sofa King contract

The request and response types of the server's `/api/v1` (live TV, recordings, media libraries, pairing, viewer preferences and crash reports), shared by the server in [`backend/`](../backend/README.md) and the app in [`app/`](../app/README.md). A Kotlin Multiplatform build with `kotlinx.serialization` types and the `Wire` constants only: no `Json` instance and no Ktor. Both builds include it, so a change here compiles and tests both, and lands with both sides in one pull request.

Released apps and servers outlive any one commit, so the types change only in ways both sides can read:

- Add a field with a default. Give a field the app does not read a default. Never rename or remove one.
- Timestamps stay ISO-8601 strings. Intents, statuses and error codes stay strings; a client reads an unknown value as its fallback.
- `src/jvmTest/resources/wire/v1` holds real server responses. The contract's tests decode them with a client's settings, and the server's `LiveTvTest` fails when a response drops or retypes one of their fields. Never edit or delete a fixture; a new API version gets a new directory.

`AdRun` is not JSON: it is the `#EXT-X-SOFA-AD` tag the server adds to the live HLS playlists it relays to the app, marking the ad segments, and the app reads it back with `AdRun.parse`.

The server's admin, policy and status models stay in `backend/server`. Build and test from this directory with `./gradlew jvmTest assemble`, or as part of either consumer's build.

Kotlin and kotlinx.serialization come from the repository's one catalog, `../gradle/libs.versions.toml`, which the app and the server load too, so all three always compile with the same pins.

Its versions are the catalog's exact pins; resolved versions are not locked.
