# Sofa King

Two Gradle builds: the app in `app/` (rules in `app/CLAUDE.md`) and the server in `backend/` (rules in `backend/AGENTS.md`). The root `settings.gradle.kts` only includes both for one IDE import; build each from its own directory through `/home/james/.claude/bin/gradle-queue -C app --` or `-C backend --` from the repository root. `gradle/build-cache.settings.gradle.kts` is the remote build cache every build applies.

CI: `.github/workflows/ci.yml` (calls `snapshot.yml`) runs for changes under `app/`, `backend.yml` for changes under `backend/`, Markdown excluded; a push to `main` that changes `backend/` publishes `ghcr.io/jamesarich/sofa-king-backend:main`, which the home server installs within about 20 minutes. Commits use Conventional Commits; squash-merge pull requests after CI is green at the head.
