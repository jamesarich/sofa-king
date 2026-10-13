# Sofa King

The app is the Gradle build in `app/`; its rules are in `app/CLAUDE.md`. Run Gradle through `/home/james/.claude/bin/gradle-queue -C app --` from the repository root. CI is `.github/workflows/ci.yml` (calls `snapshot.yml`) and runs only for changes under `app/` other than Markdown. Commits use Conventional Commits; squash-merge pull requests after CI is green at the head.
