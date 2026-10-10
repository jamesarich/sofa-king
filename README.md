# Sofa King Backend

A Kotlin server for the household Sofa King Android TV client: native channel and guide APIs, managed live playback, and automatic quarantine/recovery of bad sources.

## Project status

Repository initialized with the architecture proposal and a concrete foundation implementation plan. Server code, build configuration and runtime dependencies have not been added yet. No IPTV production services or Sofa King client code have changed.

- [Architecture and migration roadmap](docs/superpowers/specs/2026-10-10-backend-design.md)
- [Foundation implementation plan](docs/superpowers/plans/2026-10-10-foundation.md)

## Direction

One Kotlin/JVM Ktor application with local SQLite persistence, durable jobs and provider adapters. A typed native API serves Sofa King; playback uses supervised sessions and native HLS relay, with FFmpeg for media paths that need it. Bad source offerings are quarantined, retested and recovered without deleting channel identities or favorites.

Live TV is the first release. Timeshift and scheduled DVR are stretch goals. The API and media protocols are free to evolve jointly with Sofa King; Plex integration is outside scope.

Implementation proceeds in small verified slices while the existing OMV stack remains available for household viewing and rollback.
