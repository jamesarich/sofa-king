# Sofa King

Three Gradle builds: the app in `app/` (rules in `app/CLAUDE.md`), the server in `backend/` (rules in `backend/AGENTS.md`) and the wire types both include in `contract/` (rules in `contract/README.md`). The root `settings.gradle.kts` only includes them for one IDE import; build each from its own directory through `/home/james/.claude/bin/gradle-queue -C app --`, `-C backend --` or `-C contract --` from the repository root. `gradle/build-cache.settings.gradle.kts` is the remote build cache every build applies.

A wire change goes in `contract/` and lands with both sides in one pull request; the server must keep every field of the fixtures in `contract/src/jvmTest/resources/wire`, because the TV runs an older app than the server it talks to.

CI: `.github/workflows/ci.yml` (calls `snapshot.yml`) runs for changes under `app/` or `contract/`, `backend.yml` for `backend/` or `contract/`, Markdown excluded; a push to `main` that changes the server publishes `ghcr.io/jamesarich/sofa-king-backend:main`, which the home server installs within about 20 minutes. Commits use Conventional Commits; squash-merge pull requests after CI is green at the head.
