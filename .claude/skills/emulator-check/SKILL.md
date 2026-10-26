---
name: emulator-check
description: Check a merged Sofa King change on an Android TV emulator, Apple TV or iPhone simulator, or headless browser against the fixture server, headless and muted; or turn the check into a Maestro flow CI runs nightly.
---

# Check a change on an emulator

A change to playback, focus or the remote needs a walk on a device. Threads run emulator and simulator checks themselves; only real hardware and the live home server are the owner's.

## First choice: make it a flow CI runs

`tv-monkey.yml` runs every flow in `app/maestro/` nightly on the debug app in a headless Android TV emulator against `app/scripts/fixture_server.py`, then presses random remote keys. A check written as a flow runs every night from then on, where a manual check runs once.

1. Copy the shape of an existing flow (`app/maestro/guide.yaml`, `library_player_keys.yaml`, `library_up_next.yaml`): remote keys (`pressKey: Remote Dpad Center`, `Back`), elements by test tag (`id: "player"`) or text, `extendedWaitUntil` with a timeout for anything that loads. `app/scripts/tv-monkey.py` runs the flows one after another in one app session, so a flow starts where the one before it left the app; say in its header comment where it starts and ends.
2. Push; the workflow also runs on pull requests that change `app/maestro/**`, so the pull request shows the flow passing. Read its artifact (screenshots and logcat) if it fails.
3. Remove the check's line from the owed list once the flow is green.

Use a JVM screen test in `ui`'s `jvmTest` instead when the behavior is a focus or key decision; it runs on every pull request.

## A one-off walk on the owner's Mac (Remote Control)

Only when a flow cannot express it (Apple TV, iPhone, a screen recording). Brief the session with these rules:

- Work in a separate worktree of the branch (`git worktree add ~/sofa-king-checks origin/<branch>`) and remove it afterward; never touch the owner's checkout.
- Headless and muted: `emulator -avd <throwaway> -no-window -no-audio -no-boot-anim`; mute every simulator; `--mute-audio` for browsers. No foreground windows. Shut each one down when its check ends.
- Never the real TV unless the owner asks. Address the emulator with `adb -s emulator-5554`, since a TV can be on adb too.
- Serve `python3 app/scripts/fixture_server.py --shows --movies --quiet --out build/fixture`: 8 channels, a show with intros and credits, 84 movies. Its token is a fixed test value. Intro detection needs an FFmpeg with the `chromaprint` muxer (nixpkgs `ffmpeg-full`; Homebrew's lacks it).
- Pair the Android emulator with `sofaking://pair?server=http://10.0.2.2:<port>&code=<code>`; get a code with `POST /api/v1/admin/pairing-codes` and the token in `build/fixture/server.env`. Read focus from `uiautomator dump` (`focused="true"`) and the screen from `screencap`.
- Apple TV: a throwaway simulator and `xcodebuild test -only-testing:SofaKingTVUITests/RemoteTests/<name>` with `TEST_RUNNER_SOFAKING_SERVER` and `TEST_RUNNER_SOFAKING_TOKEN`.
- The guide's 10-second rest opens the player; press Back before a guide screenshot.

Report each check as passed or failed with the screenshot or the dump line that shows it.
