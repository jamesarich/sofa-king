## Why

<!-- Why the change is needed. The diff already shows what changed. -->

## Checklist

- [ ] The title is a Conventional Commit, `type(scope): summary`, scoped by package or area (`providers`, `policy`, `hdhr`, `logos`, `playback`, `docs`)
- [ ] Behavior changes come with tests, named for the behavior they check
- [ ] `./gradlew test :server:installDist` passes, and the smoke scripts pass when playback, imports or the lineup changed
- [ ] The docs describe any changed route, variable or behavior; variables are in the configuration table in `docs/live-tv.md`
- [ ] Dependency changes update the version catalog, the lockfiles and `gradle/verification-metadata.xml`, regenerated with `scripts/update-verification-metadata.sh`
- [ ] A new or changed native provider has sanitized fixtures, a `ProviderFixture` test, `scripts/provider-smoke.py` output below, and its section in `docs/providers.md`
- [ ] No tokens, cookies, device identifiers or private URLs in code, fixtures, logs or this description
