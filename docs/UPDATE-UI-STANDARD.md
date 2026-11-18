# Native update-page standard

Approved for Dan’s projects on native Windows and Android. This document records the shared design; it does **not** mean existing applications have been migrated. Native applications keep their own shell, theme, verified updater, installation consent, signing checks, and session/playback safeguards. Do not ship the HTML study as a WebView replacement.

## Reference

- [Approved compact mockup](../mockups/update-changelog-combined/index.html)
- [Original three-option comparison](../mockups/update-changelog/index.html)

The studies use historical version examples and real commit subjects from those ranges. All update actions are simulated; neither study detects installed apps or checks current releases. Viewer controls and phone calibration are mockup-only.

## Layout

1. Show **Current Version: <installed>** as the section heading, with **Check for updates** on Windows or **Check now** on Android. Do not repeat an Installed version line.
2. In the available-update panel, place an enlarged **Update available** label at top left and **<App name> <offered version>** at top right. Keep text readable on phones and at native font scaling.
3. Provide a collapsed **What’s new** disclosure with a change count. Expand in place into **Improvements** and **Fixes** bullets, omitting empty groups. Optional **Commit details** retains original subjects, hashes, and source range.
4. Omit the installed-to-offered arrow row, divider above What’s new, platform subtitles, “Everything since your installed version,” and helper/security paragraphs below the download/install action. Omit Android’s **Automatic update checks** section. State changes must not restore removed copy.
5. Keep download and installation separate: **Download update → Restart and install** on Windows; **Download update → Install update** on Android. Preserve real progress, verification, error handling, and required OS confirmation. Do not infer a change to existing background-check behavior solely from removing its settings section.

### No-update state

- Use the approved **Matched card** treatment on both platforms: the current-version heading and check action sit above a compact **Up to date** card.
- Supporting copy is **No newer Windows version is available.** on Windows and **No newer Android version is available.** on Android.
- Omit Preview details, source/verification disclosures, offered-version labels, changelogs, and download/install actions from this state. Reclaim the removed space rather than reserving empty rows.
- Only a successful completed check with no newer release may show this card. Initial/not-checked, checking, and failed checks must remain distinct; retry must not claim the app is current before it succeeds.
- Preserve the native shell and Android mini-player. Removing the Android automatic-check controls does not change its existing background-check policy or weaken download verification and installation consent.

## Changelog contract

- Map each platform’s actual installed and offered packages independently to verified published source revisions. Collect every commit in that exact range, including skipped releases. Do not assume version strings are Git tags or use another platform’s latest release.
- Derive bullets from real commit descriptions without invented claims. Mechanical prefixes may be removed. Keep docs/CI-only and clearly other-platform-only changes out of the summary, retaining originals in Commit details; keep relevant shared changes and do not discard uncertain changes silently.
- A GitHub release-description body, including its managed Current downloads markdown table, is **not** a changelog. Use structured change data or verified source comparison with complete pagination.
- Bind notes to the offered package identity. Handle missing source mappings, stale requests, rate limits, incomplete history, and retry honestly. Never claim zero changes or complete history when retrieval failed or was truncated. A valid update remains downloadable if only its changelog is unavailable.
- Preserve platform update security, credential isolation, no downgrades, and user-approved download/install. Windows retains its existing Velopack updater, not a manual browser-installer flow.

## Verification and rollout

Verify collapsed/expanded grouped notes, keyboard/touch access, skipped versions, multi-page history, relevance filtering, missing/truncated data, stale notes, all update stages, and phone scrolling/font scaling. Native runtime acceptance is separate from browser-study tests or compilation.

For a cross-project rollout, inventory repositories and native release channels, identify already-compliant clients, migrations needed, and clients without a published updater. Report each platform’s verified result separately. This approval does not authorize inventing release availability or claiming an unperformed migration.

Run the approved reference’s portable browser checks from the repository root:

```sh
node mockups/update-changelog-combined/verify.mjs
```

Set `CHROMIUM_PATH` if Playwright’s bundled Chromium is unavailable.
