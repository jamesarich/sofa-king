## Why

<!-- Why the change is needed. The diff already shows what changed. -->

## Checklist

- [ ] The title is a Conventional Commit, `type(scope): summary`, scoped by area
- [ ] Behavior changes come with tests, named for the behavior they check
- [ ] The docs describe any changed behavior, control, route, setting or variable
- [ ] No tokens, cookies, device identifiers, server addresses or private URLs in code, fixtures, logs, screenshots or this description

App (`app/`):

- [ ] The build and test checks in `app/docs/development.md` pass, including the Native test compile after a change to common code
- [ ] A change to playback, focus or the remote passed the checks on a TV, listed below
- [ ] A visible change has before and after screenshots
- [ ] Dependency changes update the version catalog, the lockfiles and `app/docs/dependencies.md`

Server (`backend/`):

- [ ] `./gradlew test :server:installDist` passes in `backend/`, and the smoke scripts pass when playback, imports or the lineup changed
- [ ] New or changed variables are in the configuration table in `backend/docs/live-tv.md`
- [ ] Dependency changes update the version catalog, the lockfiles and `backend/gradle/verification-metadata.xml`, regenerated with `backend/scripts/update-verification-metadata.sh`, which also regenerates `contract/gradle/verification-metadata.xml`
- [ ] A new or changed native provider has sanitized fixtures, a `ProviderFixture` test, `backend/scripts/provider-smoke.py` output below, and its section in `backend/docs/providers.md`

Contract (`contract/`):

- [ ] The app and the server change together in this pull request
- [ ] No fixture in `contract/src/jvmTest/resources/wire` is edited or deleted
- [ ] `./gradlew jvmTest assemble` passes in `contract/`
