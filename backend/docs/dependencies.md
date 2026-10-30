# Dependencies

Library versions are exact pins in [the version catalog](../../gradle/libs.versions.toml), which this table does not repeat; [CONTRIBUTING](../../CONTRIBUTING.md#dependencies) has the update policy and steps. The versions the catalog does not hold:

| Component | Version |
| --- | --- |
| htmx | 2.0.11 |
| Gradle | 9.9.0-milestone-2 |
| Foojay toolchain resolver | 1.0.0 |
| JDK toolchain | 21 |

The wrapper's version is in [the Gradle wrapper settings](../gradle/wrapper/gradle-wrapper.properties). Every library comes from Maven Central.

The image's programs come from its Ubuntu 24.04 (noble) base as distribution packages, at the versions its archive holds: FFmpeg 6.1, Comskip, `sqlite3`, `curl`, `tini`, and the VA-API drivers (Mesa, and on amd64 Intel's media driver and Quick Sync runtime). Renovate updates the base image by digest, which brings their security updates.

The tests run on JUnit 6 (JUnit Jupiter and its platform launcher, the catalog's `junit`) through `kotlin-test-junit5`, which follows the Kotlin version, with `kotlinx-coroutines-test` for virtual time.

The [MCP endpoint](api.md#mcp) runs on the official Model Context Protocol SDK, `io.modelcontextprotocol:kotlin-sdk-server` (MIT), the catalog's `mcp`. It is built against an older Ktor and Kotlin than the catalog's and runs on the catalog's, so a Ktor or SDK update is checked by `McpApiTest`, which speaks the protocol to the server.

CI's API fuzz (`scripts/api-fuzz.py`) installs [Schemathesis](https://schemathesis.readthedocs.io/) 4.32.4 with `pipx`, pinned in `.github/workflows/backend.yml`. Renovate does not track it; update the pin by hand.

The server targets JVM 21. The [contract](../../contract/README.md) it shares with the app compiles to JVM 17 bytecode for the server and Android, and to every other target the app builds; it pins the same Kotlin and kotlinx.serialization versions as the server.

## Comskip

The image installs Comskip from noble's package. Ubuntu dropped that package after 24.10 because upstream does not build against FFmpeg 8, so a release without it builds Comskip from source with `scripts/build-comskip.sh [PREFIX]`: the script downloads upstream [erikkaashoek/Comskip](https://github.com/erikkaashoek/Comskip) at the commit it pins, checks the tarball's SHA-256, applies `comskip/ffmpeg-8.patch`, and runs `autoreconf`, `configure --disable-gui`, `make` and `make install`. It needs `build-essential autoconf automake libtool pkg-config libargtable2-dev libavformat-dev libavcodec-dev libavutil-dev libswscale-dev`.

The patch derives from upstream pull request [#187](https://github.com/erikkaashoek/Comskip/pull/187), which removes the uses of `AVCodecContext.ticks_per_frame` that FFmpeg 8 deletes. It computes 2 for field-coded codecs and 1 otherwise, the values the field held, and builds against FFmpeg 6.1 as well. To move the pin, set `commit` and `sha256` in the script (`curl -sL https://github.com/erikkaashoek/Comskip/archive/<commit>.tar.gz | sha256sum`), check that the patch still applies, and run the `comskip` job in `backend.yml`, which builds on Ubuntu 26.04 and runs the Comskip test. Comskip is GPL v2 or later and runs as its own program.

## Vendored htmx

The [admin portal](admin.md) serves htmx from `server/src/main/resources/admin/htmx.min.js`, the `dist/htmx.min.js` of the npm package `htmx.org` 2.0.11, unchanged. Its SHA-256 is `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717`. To update it, take `dist/htmx.min.js` from the new release's npm tarball, check the tarball against the registry's `integrity` value, and record the new version and SHA-256 here.

## Changing versions

Follow the steps in [CONTRIBUTING](../../CONTRIBUTING.md#dependencies), then run the [build and test](development.md#build-and-test) checks and both smoke scripts. Renovate follows Kotlin, kotlinx.serialization and kotlinx.coroutines prereleases, as the dependency policy in `AGENTS.md` asks; Ktor follows releases, matching the app. Run the smoke scripts before merging a major bump to Ktor or the base image.

Published versions are listed on the official [Kotlin EAP](https://kotlinlang.org/docs/whatsnew-eap.html), [Ktor](https://github.com/ktorio/ktor/releases) and [Gradle distribution](https://services.gradle.org/distributions/) pages.
