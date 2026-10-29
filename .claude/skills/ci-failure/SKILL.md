---
name: ci-failure
description: Find why a Sofa King GitHub Actions run failed, from a cloud container without the gh CLI, and tell a known flake from a real break before re-running or fixing.
---

# Read a failed CI run

Use this when a pull request or `main` shows a red check, before re-running anything.

## 1. Find the failing job and step

In a cloud container, `curl` reaches `api.github.com` through the proxy, which adds the credentials; the GitHub MCP tools work too.

```sh
repo=jamesarich/sofa-king
# Latest runs of a branch (or event=push&branch=main)
curl -sS "https://api.github.com/repos/$repo/actions/runs?branch=<branch>&per_page=5" \
  | python3 -c 'import json,sys; [print(r["id"], r["name"], r["conclusion"], r["run_attempt"], r["created_at"]) for r in json.load(sys.stdin)["workflow_runs"]]'
# Failing jobs and steps of one run
curl -sS "https://api.github.com/repos/$repo/actions/runs/<run>/jobs?filter=latest" \
  | python3 -c 'import json,sys; [print(j["id"], j["name"], [s["name"] for s in j["steps"] if s["conclusion"]=="failure"]) for j in json.load(sys.stdin)["jobs"] if j["conclusion"]=="failure"]'
# The job's log
curl -sSL "https://api.github.com/repos/$repo/actions/jobs/<job>/logs" -o job.log
grep -n -E 'What went wrong|FAILED$|^e: |error: -\[|Test Case .* failed|##\[error\]' job.log | head -40
```

A job that hit its `timeout-minutes` shows as canceled and keeps no log; read its step timestamps instead.

## 2. Decide: break or known flake

Compare with the same job on `main` (`event=push&branch=main`). A failure that is red on `main` too, or in the list below, is not the pull request's. Re-run a known flake once (`POST .../actions/runs/<run>/rerun-failed-jobs`); a second failure in the same test is a break until shown otherwise.

Known flakes (re-run once, then investigate):

- `Test (Apple)`, iOS `TouchTests`: most walk failures from 2026-10-25 to 10-26 ("The app stopped", "Lost connection to the application") were a real crash, fixed by #380; a walk that stops is a crash until the app's stdout in the `.xcresult` says otherwise (grep `Uncaught Kotlin exception`). `xcodebuild: error: Unable to find a device matching` before any test ran is CoreSimulator not listing the booted iPhone; the step retries it. `testPlayChannel` "Timed out waiting for Sofa Sports to open full screen" with the guide still on screen was the app's main thread held for 40 s on a loaded runner; the wait is now a minute and the Gradle daemons stop before the tests. A tap on a `combinedClickable` that reaches the app late still reads as a long press, which `play()` closes and retaps.
- tvOS `RemoteTests` walk "The app stopped": treat it as a crash first. Download the `reports-apple-tv` artifact and read `crashes/*.ips` (plain JSON; `lastExceptionBacktrace`, `termination`). A FrontBoard `0x8BADF00D` watchdog under full runner CPU is a flake; an exception is a bug.
- tvOS `RemoteTests` focus waits on a loaded runner: `testStartOnDvr` ("Remote presses never focused record.circle Button", "Timed out waiting for the start to reopen DVR") and `testPlayerTransportMenu` ("Could not reach Channels Cell") fail now and then on `main` and unrelated pull requests and pass on a re-run. A focus failure in a test of a screen the change reordered is a break: `testPlayPauseOnSettingsFields` presses only Down, so it must visit the Settings fields in their order on screen.
- "The runner has received a shutdown signal": GitHub stopped the hosted runner; re-run the job.
- Registry blips: a yarn `502` in `:kotlinNpmInstall`, a Maven Central or plugin portal timeout, a slow Ubuntu mirror (apt steps go through `.github/actions/apt-install`, which caches the packages).
- `deploy-pages` 404 "Ensure GitHub Pages has been enabled": a repository setting, not the change.

Not flakes:

- `Check the formatting, analyze, test and build the distribution` (backend) or `Test, lint and build` (app) failing on `e:` compiler errors, `spotlessCheck`, `detekt` or a named test: run the same task locally (the `cloud-build` skill) before pushing again.
- `testDebugScreenshotTestDefaultTestSuite`: a screen changed on purpose needs references Linux drew, from `:androidApp:updateDebugScreenshotTestDefaultTestSuite` on Linux or the run's `reports-linux` artifact (`results/rendered/`), looked at and committed; a new preview whose reference came from a Mac fails the same way. Otherwise it is a regression.
- A `[js, node]` failure in a common test that passes on the JVM: JS computes a `Float` as a double, so equality with a folded float constant can fail; compare with a tolerance.

## 3. Reading an Apple test failure without macOS

The `reports-apple` (iOS) and `reports-apple-tv` artifacts hold the `.xcresult` bundle and a screen recording. `xcresult/Data/*` files are zstd-compressed (`pip install zstandard`), and the log blocks inside are LZ4 (`bv41`, `pip install lz4`, `lz4.block.decompress`); grep the decoded text for `Terminating app` or the failing assertion. Pull frames from the recording with `ffmpeg -ss <t> -i recording.mp4 -frames:v 1 frame.png` at the test's `t =` time to see what was on screen.

## 4. Report

Say which job, step and test failed, whether `main` shows it too, and what you did: the fix you pushed, the one re-run of a listed flake, or why it is outside the change.
