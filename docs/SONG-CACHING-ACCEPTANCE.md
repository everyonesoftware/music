# Song caching acceptance

This checklist defines the intended Android and Windows behavior. It is a test plan, not a claim that native checks have passed. Platform implementation notes and actual command results must distinguish automated policy/transport tests, compile checks, and native playback evidence.

## Defaults and settings

- Automatically save played songs: enabled.
- Automatic storage budget: 2 GB. Offer 500 MB, 1 GB, 2 GB, 5 GB, and a validated custom limit.
- Prepare upcoming songs: next 3 in the actual playback order; offer off, 1, 3, 5, and 10.
- Additional mobile/metered prefetch: disabled. Explain that fetching the current streamed song ahead is separate from speculative queue downloads.
- Offline mode: disabled. When enabled, playback/browsing must not initiate server requests.
- Show automatic-cache and explicit-download usage and complete-song counts separately.
- Clear automatic cache without deleting pinned downloads or interrupting active playback.

## Playback and retention

1. Start a song promptly while connected; complete the current-song download as quickly as practical without delaying initial playback.
2. Drop the connection after the song is complete locally. It must continue without a new server request.
3. Drop the connection before completion. Report a missing section honestly; partial content must never carry a complete/offline-ready badge.
4. Advance, disconnect, then press Previous. A fully saved previous song must play and seek from local storage without a server lookup.
5. Restart the app disconnected. Saved queue, metadata and complete-song availability must survive; restoration must not unexpectedly start playback.
6. Prefetch uses the real shuffled/repeated queue, prioritizing current, next, recent partial, then additional upcoming songs.
7. Evict least-recently-used automatic content to respect the budget, protecting active playback and a bounded recent history where space allows.
8. Explicit song/album/playlist downloads remain pinned until removed; clearing or shrinking automatic storage does not remove them.
9. On reconnection, retry at the previous position only when the user's latest playback intention is still Play. A later pause, queue replacement or account switch must invalidate recovery.
10. Recently played remains browsable offline, with an offline-only filter and complete-only availability indicators.

## Isolation and failure cases

- Scope cache identity to server/account, song and audio representation. Never persist signed media URLs, passwords or transport exceptions.
- Verify account ownership on local reads, not only network requests.
- Changing accounts cancels old work; late completions must not publish entries into the new account.
- Removing an account removes that account's local content, without deleting another account's content.
- Reject invalid custom limits, truncated responses, inconsistent ranges, corrupt metadata and invalid cache paths safely.
- Disk-full and cache I/O failures must be actionable and must not silently label incomplete content offline-ready.
- Verify metered-network policy, cancellation, process restart, eviction, pin/unpin and storage clearing against real temporary files and controlled transport fixtures.

## Current automated evidence

After stopping the earlier test work, the parent session restarted validation from a clean Android build and independently reran both platforms:

- Android after the artwork/collection extension: 136 JVM tests passed with zero failures/errors/skips; lintDebug, assembleDebug and assembleDebugAndroidTest passed. Existing Android source/release checks: 19 passed.
- Fresh task-owned API 29 emulator with wiped data at 412×915 / mdpi: 39 instrumentation tests passed with no assumption skips. Groups: artwork 5, collection storage/browsing/resolution 6, production offline collection flow 1, session artwork 11, audio-cache regressions 13, existing UI/real-service regressions 3. The new activity flow reopens saved playlist hierarchy, preserves duplicate track order, plays saved audio, verifies visible cached cover pixels and asserts zero additional server requests. Artwork tests reopen persistent storage offline and use StrictMode to reject main-thread disk reads/writes in UI lookup. Earlier replay/seek, pause, activity recreation and account-removal checks remain covered. Evidence is under `/opt/data/cache/scratch/music-artwork-collections-evidence/`; `screenshots/offline-collection-artwork-player.png` was inspected. The emulator was stopped afterward and `adb devices -l` was empty.
- Windows: Core 143 passed, Update 31 passed, logic checks 23 assertions passed, visual/offline Node contracts 21 passed, existing source contracts 10 passed. The Windows-targeted Linux cross-build succeeded with zero warnings/errors. These include shuffled Previous/Next playback-history coverage; they do not establish native Windows audio or rendered UI acceptance.
- `git diff --check` passed. No commit, push or release publication was performed.

Earlier failed Android runs were superseded by this fresh passing run, not counted as passing evidence. The browser-prototype failure below is historical: newer main retired that study before integration.

## Integration with current main

The feature was combined with main at `116f84bee06fe5158b688337a28f141fb161fa9b`, preserving its searchable album/playlist collection pages, grid/list preferences and centralized navigation. Offline Back behavior is included in that same navigation resolver and has an executable regression.

Merged-tree local gates after review fixes: 144 Android JVM tests passed with zero failures/errors/skips; lint and both APK builds passed. Windows Core 145, Update 31, logic 23 assertions, visual/offline contracts 22 and existing source contracts 10 passed; the Windows-targeted cross-build had zero warnings/errors. Existing Windows release-tool checks 28 and current Android source/release checks 16 passed. The full current `npm test` passed using the installed Chromium through an external scratch preload (145 orchestration tests plus all preserved browser-study suites). No retired browser study was restored or its old assertion weakened. Targeted review fixes cover complete Settings/Saved music navigation, resumption of pending pins after automatic clear, and live Windows selection protection against stale-worker eviction.

Merged Android native validation: 41 tests passed without assumption skips at 412×915 / mdpi on a task-owned API 29 emulator. Groups: UI/real-service regressions 3, artwork 5, collection storage/browsing/resolution 6, production offline collection flow 1, session artwork 11, audio cache 15 (including both pending-pin clear regressions). The first five groups passed under `/opt/data/cache/scratch/music-main-caching-final-native-evidence/`; the full audio-cache suite passed under `/opt/data/cache/scratch/music-main-caching-cache-rerun-evidence/` after correcting its authenticated test server to serve repeatable responses during callback-driven transfer replacement. Completion, authentication, privacy, clear-cache assertions and the 15-second deadline were retained. Earlier failed runs are not passing evidence. The emulator was stopped and `adb devices -l` was empty. Hosted build/publication and native Windows acceptance are not established by these local checks.

## Implementation limits and remaining acceptance

- Initial uncached playback may use separate foreground/background transfers on both platforms; shared persistent storage is not a deduplicated progressive transport. Windows switches to the completed local source with position/intent guards.
- Android now persists bounded artwork and independently browsable album/playlist snapshots with ordered song occurrences, collection metadata and shared pin ownership. The automatic artwork budget is a separate 50 MB; downloaded covers remain pinned. Old song-only downloads cannot reconstruct their former collection grouping: downloading the album/playlist once online creates a snapshot while reusing already-complete audio. Interrupted Android downloads resume on app/account activation, not through an OS-guaranteed download service after process death.
- Windows interrupted downloads restart from zero. Automatic limits may be exceeded temporarily by protected tracks. Windows native launch, rendering, codec playback, offline handoff and installed-app acceptance still require a Windows runtime.
- Hardware signal/radio transitions and a separate force-stop cold-restoration probe were not exercised. Cached-storage reopen and activity recreation tests are not equivalent to those hardware/process checks.

## Native acceptance

### Android

Exercise actual Media3 playback in a task-owned emulator where available, including airplane-mode replay, seek, service/background playback, process restart, account removal and the Settings controls. JVM tests and APK assembly alone do not establish native playback.

### Windows

Exercise the actual WinUI/MediaPlayer app on Windows, including local-source decoding, progressive playback, previous/seek disconnected, restart and Settings. Linux .NET tests and a Windows-targeted cross-build establish logic and compilation, not native audio, XAML rendering or installed-app behavior.

## Shared regression baseline

The session clone was created from `126793a799dbb0d85ac8f7a320f01e24a73f3fc3` on branch `feature/song-caching`. The original checkout is not an input for uncommitted changes.

Before feature completion, `npm test` with the installed Chromium selected through an external scratch preload passed 145 orchestration tests, the 255-check mockup suite, workflow, onboarding and phone-size suites. It then failed the unchanged `tests/readable-player.mjs:93` assertion: the simple readable-player surface reported `overflow: true`. This is a baseline browser-prototype failure, not native caching acceptance. The initial default-browser run also required an environment-only browser override because the pinned headless-shell executable was absent. No browser-test or prototype sources were changed to bypass either result.
