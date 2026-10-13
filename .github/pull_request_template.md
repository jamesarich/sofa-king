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

- [ ] `./gradlew test :server:installDist` passes, and the smoke scripts pass when playback, imports or the lineup changed
- [ ] New or changed variables are in the configuration table in `backend/docs/live-tv.md`
- [ ] Dependency changes update the version catalog, the lockfiles and `gradle/verification-metadata.xml`, regenerated with `scripts/update-verification-metadata.sh`
- [ ] A new or changed native provider has sanitized fixtures, a `ProviderFixture` test, `scripts/provider-smoke.py` output below, and its section in `backend/docs/providers.md`
