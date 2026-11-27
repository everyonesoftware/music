# Windows implementation checkpoint — validation

## Playlist deletion

Use a disposable Navidrome account/library. In the native Windows app:

- [ ] Open **Delete playlist** from detail **Play → Shuffle → ⋯** and from
  the exact row's **⋯** in both Playlists layouts. Exercise mouse and keyboard,
  including right-click and Shift+F10 on a row, and verify row click/Enter still
  opens it. Use same-name playlists to check that the selected ID is the target.
- [ ] Verify empty owned playlists can be deleted; foreign, read-only, unknown
  ownership and Offline mode do not offer an actionable Delete command.
- [ ] Verify the confirmation names the playlist, explains that library songs
  stay, and defaults to **Cancel**. Cancel and Escape send no delete request and
  return focus to the launching control. Explicit Delete returns to Playlists,
  focuses its heading, and announces the outcome. Back and a delayed catalog
  reply must not reopen or restore the deleted playlist.
- [ ] Keep music playing and save a fixture song before deletion. Confirm the
  queue, position, play/pause state, saved audio and unrelated playlists survive.
- [ ] Drop the successful POST reply after the server commits. Confirm only a
  read of the same ID is sent next, and that an unavailable or malformed read
  is not reported as success. A preflight ownership/name change must send no POST.
- [ ] Also fail that first same-ID read. Even if a catalog refresh omits the
  playlist, **Check deletion again** must stay available for that exact target.
  Retry with another outage, structured absence, and a still-present playlist.
  Verify GET only: another failure retains the action, absence removes the
  stale row and Back history without claiming acknowledgment, and presence
  lets you reopen or reload the playlist for review. Repeat with a pending
  response while entering Offline mode or switching accounts.

The Windows Core client was exercised on disposable Navidrome v0.64.2. It
deleted an empty playlist and reconciled a populated playlist deletion after the
committed reply was deliberately lost; the same-name sibling and library tracks
survived. The preflight and POST are separate operations, not an atomic server
precondition. Linux compilation and source checks do not prove native input,
dialog focus, or the screen-reader announcement.

## Playlist creation and Add to Playlist

Use an isolated Navidrome library for acceptance. On native Windows:

- [ ] Create an empty playlist from **Playlists → New playlist**. Check name
  validation, exact detail navigation, **Browse Library**, and Back.
- [ ] Open **Add to Playlist** with right-click and Shift+F10 on playlist, album,
  artist, and song entries in shelves, lists, grids, search and collection
  details. Verify both **New playlist** and existing writable destinations.
  Check the song ellipsis still offers the device action.
- [ ] Repeat on a single current Queue song and on Played and Up next entries.
  Verify adding does not start playback or change queue order, current song,
  playback position or play/pause state. Check focus after dismissing.
- [ ] Append a collection containing repeated tracks. Verify order and duplicate
  occurrences, that matching detail and catalog metadata update, and that the
  active page stays put. Verify incomplete sources and read-only destinations
  cannot result in a partial write.
- [ ] Change a source after the picker loads. Submitting must re-read the source
  and refuse to post if its ordered occurrences changed or cannot be verified.
  Reload the selection before trying again. Navidrome does not make this read
  and the write atomic; a track can still disappear between them.
- [ ] Insert another client's append between Music's preflight and its write.
  Readback should show both edits and keep duplicate occurrences in order. If
  the requested batch cannot be verified, don't report success or replay it.
- [ ] Test a lost write response and a failed readback after acknowledgment:
  an unknown outcome must warn against blind retry; **Check again** checks only
  the acknowledged identity and does not resubmit. An open destination stops
  offering playback and device actions based on stale contents; **Retry playlist**
  reloads the destination without resubmitting the write. Check Offline mode,
  account switching, window close and stale recycled rows.

The Core tests exercise authenticated form encoding, response validation and
read-only retry with injected HTTP responses. The source and navigation contracts
check native wiring and delayed catalog publication; a cross-build is not proof
of native gesture routing, focus or layout.

## Taskbar thumbnail playback

On Windows, hover over the running Music taskbar icon and check **Previous**,
**Play/Pause** and **Next** beneath the existing preview. Verify that:

- [ ] All three controls are inactive without a queue and work after a queue is
  loaded, including a paused restored queue and after an account is removed.
- [ ] With Music minimized, the controls use the same queue and restart/skip
  rules as the footer, without bringing Music to the foreground. Play and Pause
  icons and tooltips follow playback commands from the footer and media keys.
- [ ] The toolbar returns after restarting Explorer. Check the icons on light
  and dark taskbars and with mixed display scaling.

The source contracts and cross-build do not verify Explorer rendering or input.

## Filled footer transport and Android-style slider markers

Play, Previous and Next use filled vector triangles in their existing foreground
colors. Play/Pause changes the visibility of retained vector paths rather than
replacing them with outline font glyphs. Both playback position and volume use
the same 4 × 28 rounded vertical marker, with a gap around the track and a larger
24 × 32 native Thumb hit target. The SDK Slider template still owns dragging,
track clicks, keyboard operation, focus and normal/hover/pressed/disabled colors.

`validation/player-controls.test.mjs` is imported by the existing visual contracts
and checks this source wiring. Compilation and source checks do not establish
native rendering or input acceptance.

- [ ] On Windows, inspect filled Play/Previous/Next and the Play/Pause transition
  while playing, paused and disabled, at restored/maximized sizes and high DPI.
- [ ] Verify both marker bars at minimum, middle and maximum, including hover,
  pressed and disabled states; there should be no circular dot or gray thumb box.
- [ ] Drag each marker, click its track and use Tab/arrows/Home/End. Confirm
  seeking, volume, focus visibility and accessible names remain functional.

## Precomputed Windows update changelog

Settings hosts the `EveryoneSoftware.WindowsCommon` WinUI panel directly. The
shared card uses the rounded border, theme brushes and sage icon badge modeled
on YourTube’s FFmpeg card. Settings caps the leading column at 760 DIPs. Music
owns discovery, download/apply, the cached changelog and playback preservation.
Music opts into keeping the shared **Check for updates** button enabled while
downloading or ready to install. During download the controller queues the check
behind the package work; there is no separate recheck button.
The opt-in native acceptance harness checks that Settings realizes the idle
shared panel with the running assembly version and an enabled Check button.

Settings Updates keeps the collapsed native **What’s new** disclosure for the
exact offered Velopack package, including while downloading and ready to install.
**Check for updates** fetches the selected publication’s `windows-release.json`
once alongside its Windows feed. No HTTP work starts on expanding/reopening notes
or **Commit details**. The same response supplies the installed-to-offered range,
including skipped Windows publications; no historical manifests, compare API,
per-commit file requests, release bodies or other fallback are used.

The manifest’s optional `changelog` is schema 1, platform `windows`, with a
`head: {version, sha}` and numerically increasing `releases` of
`{version, sha, commits: [{sha, subject, kind, text}]}`. Versions are canonical
`0.1.N` (`1..65534`); identities retain full lowercase 40-character SHAs. The
first publication is a baseline with no commits; subsequent segments contain
exactly the difference between consecutive version counts and end at their
publication SHA. Duplicate versions, commit/source
identities, invalid types, incoherent null kind/text, multiline subjects,
blank/multiline bullets, and head/source/package/platform/artifact mismatches are rejected.
The manifest head must match the exact selected feed/package version, publication
source and full-package SHA256/size. Manifest reads have a 1 MiB acceptance cap
and production HTTP streams enforce that cap even without Content-Length, under
a 30-second budget. The parser validates the whole history before exposing notes.

An installed version missing from the exact release entries yields **unavailable**,
not partial or zero-change notes. Missing/legacy/malformed manifests and optional
asset failures do not invalidate a valid downloadable update. **Check again**
rechecks discovery, rather than initiating a separate disclosure request; it is
disabled during downloads and once Ready. Existing injected feed-only fixtures
are deliberately supported with unavailable notes.

`kind: improvement|fix` uses the producer’s cleaned `text` verbatim for grouped
bullets. Null kind/text retains the original commit only in **Commit details**.
All original subjects and SHAs are preserved, including excluded docs/CI/other-
platform subjects. Only rendering abbreviates hashes to seven characters. Empty
groups are omitted. Snapshots survive same-offer progress/Ready renders; rechecking
clears prior notes and replacement snapshots cannot reuse stale notes, even at
the same version. The native updater remains lazy at startup, disables downgrades
and delta reconstruction, and retains explicit Download → Restart/install with
session preservation required before restart.

Run the Core and Update test projects plus
`node --test apps/windows/validation/visual-contracts.test.mjs`. The latter imports
`update-changelog.test.mjs`. Executable tests cover skipped segments, originals vs
summaries, invalid manifests, missing baselines, source binding, bounded HTTP,
no disclosure network, and the real pinned Velopack `UpdateManager` check using
an explicit fixture downloader and `TestVelopackLocator`. TrimChecks’ legacy
feed-only fixtures remain download-capable with unavailable changelog metadata.
These are retrieval/policy/source/compile checks, not native acceptance.

Native Windows acceptance remains required:

- [ ] At narrow and wide widths, check that the update card aligns with the page
  heading and the Check button is reachable by Tab. Repeat during download with
  **What’s new** open; check the badge and border in high contrast.
- [ ] Offer an update across skipped releases; expand **What’s new**, verify its
  complete installed-to-offered source range and grouped bullets, then expand
  **Commit details** using keyboard and a screen reader. Inspect HTTP tracing:
  notes must be fetched during Check, never during either disclosure.
- [ ] Collapse/reopen before and after Download; retain the same notes and inspect
  the Ready to install card at restored/maximized sizes and increased font scale.
- [ ] Use the same **Check for updates** button during download and from Ready. Verify a
  queued check does not interrupt download, and Ready survives a failed recheck
  or an unchanged offer. A newer offer still requires a separate download.
- [ ] Simulate missing/malformed/rate-limited manifests and absent installed
  baseline; verify unavailable notes while the valid update remains downloadable.
  Use **Check again** before downloading; recheck/close during discovery and
  verify no late completion or same-version replacement revives old notes.
- [ ] Explicitly restart/install and verify session preservation separately.

## Collection song selection and actual Played queue — November 19, 2026

Direct album/playlist row selection starts the entire ordered collection at the
clicked occurrence (including duplicate IDs), and disables session Shuffle only
after current-account/loading/error/occurrence validation. Queue has no track
numbers. The existing real song-cover loader and its cancellation/image-release
lifecycle are reused in lazily realized Queue rows; playlist rows retain it.

Queue presents a native keyboard-accessible **Played** Expander, **Now playing**
(or **Playback finished**), then **Up next**. Both the main Queue ListView's star
viewport and the Played ListView's 240px maximum keep realization bounded; their
ItemsSource entries contain queue indices/event IDs, not pre-created artwork UI.
Same-queue handoffs reuse unchanged queue controls. Played is bounded to the most
recent 100 departed, actually started visits and is intentionally **session-only**:
no session schema changes, credentials, authenticated media URLs, or invented
history on paused restoration. New queue/account resets history.

Only a source/visit-bound native PlaybackStateChanged observation of
`Playing` marks a visit started. Its tiny evidence cell is recorded thread-safely
before dispatcher work and closed atomically on departure, so a blocked dispatcher
cannot lose a real start. Source replacement invalidates older callbacks without
clearing the same visit's evidence; queued UI refreshes retain their version guard.
MediaOpened, selection, cache completion, paused
restoration and failed/unstarted sources do not. Previous uses actual back history
before its existing numeric fallback; fallback navigation itself does not create
Played entries. Forward replay and prefetch share the same PlaybackOrder plan.
Final completion archives a started last visit once and removes Now playing; late
cache handoff cannot revive it. Play/Previous can explicitly replay after finish.

TDD RED evidence: `Unstarted_selection_is_not_previous_history` expected 6 but
returned the unstarted selection 2; the large 602-occurrence queue regression
threw ArgumentOutOfRangeException at the old 100-plan limit; selecting an older
visit of the current occurrence lost its actual current visit; Previous after
finish returned unheard index 1 instead of actual index 2. All are now GREEN.
The three new Node integration contracts also failed before UI integration.
The executable `playing-callback.test.mjs` compiles the actual MainWindow native
callback with real PlaybackOrder and a held dispatcher. It failed RED with
“Playing evidence was lost before departure” and passes after the evidence fix;
source/visit replacement, paused handoff and final completion are also exercised.
It runs with the visual-contract suite (requires the documented .NET/ICU environment).
These boundary-controlled checks do not establish native WinUI/audio behavior.

Validation commands (source/logic/real-reference compile, **not native acceptance**):

```sh
dotnet test apps/windows/Music.Core.Tests/Music.Core.Tests.csproj -c Release
dotnet test apps/windows/Music.Update.Tests/Music.Update.Tests.csproj -c Release
dotnet run --project apps/windows/validation/LogicChecks.csproj -c Release
node --test apps/windows/validation/visual-contracts.test.mjs apps/windows/validation/offline-contracts.test.mjs
python3 -m unittest discover -s apps/windows/validation -p test_source_contracts.py -v
```

Linux compile gate also passes with the documented OutputType=Library,
WindowsAppSDKSelfContained=false and PrepareForRunDependsOn overrides, including
MusicNativeAcceptance=true. The opt-in native fixture understands grouped queue
entries and verifies paused restoration has no Played entries; it was **compiled,
not executed** here. No Windows runtime/audio/keyboard/screenshot acceptance or
installer is claimed.

- [ ] On Windows, click the third collection occurrence with global Shuffle on:
  verify the full ordered queue, duplicate occurrence identity and empty Played.
- [ ] Let it actually play, skip directly forward, then fail a source before start:
  only genuinely started departures belong in Played, never intervening rows.
- [ ] Expand/collapse Played with keyboard/screen reader; select duplicate visits,
  exercise Previous/forward, Repeat, Shuffle, final completion and replay.
- [ ] Restore paused; reconnect, switch to a completed local file and change
  accounts/queues; check no phantom history or resurrection after completion.
- [ ] Scroll large Queue/Played/playlist lists rapidly and reopen Queue; inspect
  realized row count, per-song covers/placeholder recycling and decoded memory.

## Startup navigation revision fence — November 18, 2026

Automatic startup Offline/Library routes now share a navigation revision captured
before the first cache-initialization await. User page navigation, collection opens,
Back restoration and repeated sidebar selections invalidate those routes without
cancelling startup Ping/catalog loading. A successful catalog still publishes for
the current account; the existing `connectGate` checks continue to fence replaced
accounts. Startup failure fallback uses the same revision fence.

Executable regression: `validation/startup-navigation.test.mjs` compiles the actual
`InitializeAsync`, `NavigateAsync`, `OpenCollectionAsync`, `GoBackAsync` and snapshot
method bodies with production `RequestGate`/`NavigationHistory`, substituting only
non-WinUI UI/storage/network boundaries. Task-completion barriers delay cache, Ping
and catalog completion and exercise success/failure, album and Settings navigation,
Back, repeated destinations, retained catalog publication, untouched startup and
account invalidation. It is imported by the existing navigation/visual suite.
The first run was RED: `cache success startup overwrote user album`; after the fix,
all scenarios are GREEN. This is executable routing/history integration evidence,
not native WinUI acceptance.

Checks passed: LogicChecks (72 assertions), Core (184 tests), updater (31 tests),
visual/offline Node suites (44 tests including the executable regression), unchanged
Python source suite (10 tests), and Windows-targeted Linux compile-only build
(0 warnings/errors). The existing VSTEST_ARTIFACTS_DIRECTORY fallback advisory
remains. Native startup interaction and rendered Windows UI remain unverified.

## Scoped mouse Back navigation — November 18, 2026

Playlist and Album details no longer show the top `← Library` link. The existing
left Play / Shuffle / icon-only Download transport is unchanged. Shell XButton1
presses now consume the routed pointer event and restore the actual previous view,
not a fixed Library destination. History keeps up to 64 in-memory metadata-only
snapshots of collection origin, query and grid/list layout. Re-selecting the active
sidebar destination preserves its search/layout and does not create a duplicate.
Settings participates in the same history, including returning to a detail view;
restored details fetch current tracks rather than retaining stale song lists.
Back never pushes the departed view back onto history. No scroll-position or
pagination-depth restoration is promised.

Connection activation/replacement, successful account removal, and Offline mode
transitions clear the history and retained detail identity. Startup and forced
post-account/offline routes do not record synthetic history. Ordinary navigation
and Back keep the existing request cancellation/client-generation fences and
bounded virtualized detail list. History and navigation do not alter playback.
XButton1 is consumed without shell navigation when a popup is open, an editor has
focus or is the event source, the account is changing, or the window is closing.
No new keyboard accelerators were added. The handler uses the SDK's actual
`PointerUpdateKind.XButton1Pressed`, `UIElement.AddHandler(..., true)`, XamlRoot
popup enumeration, and focused-element APIs; installed projection XML and primary
Microsoft PointerPressed/PointerUpdateKind documentation were inspected.

Evidence: first logic run failed because the history types were missing; after
basic traversal passed, an executable regression failed on repeated-sidebar
navigation before de-duplication was implemented. New source wiring/removal checks
failed 5/5 before integration and pass afterward. Final checks: LogicChecks 72
assertions (19 navigation assertions), visual/offline Node suites 43 tests
(including imported navigation/transport contracts), Core 184 tests, updater 31
tests, unchanged Python runtime/source checks 10 tests, and Windows-targeted Linux
compile-only build with 0 warnings/errors. `git diff --check -- apps/windows` passes.
Existing test-SDK VSTEST_ARTIFACTS_DIRECTORY fallback advisory remains.

Native mouse routing, popup/focus behavior, UI restoration and unchanged audible
playback remain **unverified in WSL**; cross-compilation/source tests are not
native Windows acceptance. No installer, commit, push or publication was made.

- [ ] On Windows: Library → Playlists → playlist → XButton1 → Playlists → XButton1
  → Library; repeat through Albums, with filtered list/grid origins.
- [ ] Navigate detail → Settings → XButton1; confirm the same collection reopens,
  then Back returns to its original collection/query/layout.
- [ ] Back while detail is loading, then allow the response to finish; abandoned
  content must not reappear. Repeated active-sidebar clicks and empty-history Back
  must not manufacture navigation or alter search/layout.
- [ ] Exercise XButton1 over cards, rows, background and buttons; verify focused
  text/password/number editors and open dialogs/menus do not unexpectedly navigate.
- [ ] Replace/remove account and toggle Offline; old account details must never
  return. Confirm playback queue, index, play/pause and shuffle remain unchanged
  during ordinary navigation/Back (account/offline lifecycle behavior is separate).

## Compact album and playlist transport — November 18, 2026

Both native detail types now share a left-aligned group in Play, Shuffle, Download
order. Download is icon-only, with its collection-specific accessible name and
tooltip retained. The independent shuffle mode toggle keeps its sage icon/background
and selected dot; Play remains round and sage.
Mode defaults to ordered and is remembered in memory per account, collection type
and ID; it is not a persisted player/session setting. Toggling changes only the
future-start mode. Play always creates a fresh queue at index zero, retaining every
song occurrence; shuffled starts use Fisher–Yates on a copy. The active session's
shuffle is cleared only after a valid queue is prepared, avoiding a second shuffle
on Next/prefetch. Repeated Play restarts instead of resuming the prior queue.

The shared transport checks loading, detail error, empty songs, offline mode,
account-change state, page, collection identity, song-list identity and connection
lease before playback or download effects. Existing bounded native ListView,
indexed occurrences, lazy row realization, downloaded-song playback and cache
behavior remain unchanged. Android PlaylistDetails and its action guards were
inspected read-only; no Android, mockup or release files were changed here.

Evidence: observed executable RED for missing scoped mode policy, then GREEN;
observed executable RED for missing safe queue planning, then GREEN; new rendering
and wiring contracts failed before UI implementation and passed afterward.
LogicChecks now passes 53 assertions, Core 184 tests, updater 31 tests, existing
Python runtime/source checks 10 tests (unchanged), visual/offline Node contracts 38
tests. New transport source contracts are imported by the existing visual suite.
Windows-targeted Linux compile-only build passes with 0 warnings and 0 errors.
The left-group/icon-only refinement first failed its updated source regression
(missing left-aligned group), then passed after the layout change. Revalidation:
visual/offline Node contracts 38 passed (including the imported transport checks),
LogicChecks 53 assertions passed, Core 184 passed, unchanged Python source checks
10 passed, and Linux compile-only build 0 warnings/errors.
The test SDK prints its existing VSTEST_ARTIFACTS_DIRECTORY fallback advisory.

Native WinUI execution is unavailable in this WSL session: no Windows executable
host is exposed. No installed-app, screenshot, runtime automation or audible
playback acceptance is claimed; no installer was built or release published.

- [ ] On Windows, compare album and playlist transport at restored/maximized sizes
  and high DPI; inspect sage checked/hover/focus/disabled states and selected dot.
- [ ] Verify native ToggleButton accessibility/keyboard state and changing Play's
  accessible mode label, without interrupting a playing or paused session.
- [ ] Exercise ordered and shuffled Play repeatedly with duplicate occurrences;
  verify fresh index-zero playback, sequential traversal of the prepared queue,
  full download list and unchanged virtualized realization behavior.
- [ ] Switch account/collection, navigate away, enable Offline, fail or delay a
  request; verify old/loading/error/empty handlers cannot replace playback.

## Song artwork in collection details and Queue — November 17, 2026

Native list rows now show 40 × 40 artwork beside the title/artist, using the
approved sage mockup in `mockups/windows-song-artwork/index.html`. The same cell
is used by Queue with a 10px gap (12px in the main list). Queue rows are 60px;
main rows retain the existing 56px minimum and proportional columns. Title
headings are inset to align with the text rather than the artwork. Shared list
rendering also brings artwork to album details, Songs list mode, and offline
song rows without changing their playback or download actions.

A musical-note placeholder remains when artwork is missing or cannot load.
Artwork loading starts on the cover's Loaded event, not while the full collection
is materialized. Unloaded cancels that row's work and clears its decoded image so
retained queue items do not accumulate bitmaps; Loaded restores it when realized
again. Linked caller/connection tokens invalidate superseded requests. The existing bounded authenticated-byte loader is
reused, with no signed URLs assigned to image properties. Offline mode does not
start artwork requests; this change does not introduce persistent offline artwork.
Queue selection keeps a direct reference to its title instead of assuming the
title is the first visual child, and same-queue playback changes reuse the rows.

Local evidence:

- New list/queue rendering-source tests failed before their implementation, then
  passed. Visual/offline source contracts: 36 passed; native-driver source
  contracts: 7 passed. These are source checks, not native rendering tests.
- Core: 184 passed; updater: 31 passed; LogicChecks: 23 assertions passed; existing
  runtime-XAML/source contracts: 10 passed.
- Windows-targeted compile-only build: 0 warnings, 0 errors. The output is a
  library for compilation validation, not a distributable installer.
- `npm run test` passed with an external preload selecting installed Chromium;
  no unrelated test source was changed to select a browser.

Native Windows acceptance remains required; this WSL session has no Windows
executable host. No installer, publication, or installed-app validation is claimed.

- [ ] Compare playlist and Queue with the approved mockup at restored/maximized
  sizes and high DPI. Check 40px covers, title/artist ellipses, aligned headings,
  current-track color, selected background, and the missing/failed-art placeholder.
- [ ] Rapidly scroll/recycle a large playlist; navigate away while covers load.
  Confirm covers belong to the correct songs and requests follow realized rows.
- [ ] Open/close Queue, select duplicate song occurrences, use Next/Previous and
  local playback handoff; confirm the right queue index and selected title update.
- [ ] Switch/remove accounts during artwork loading and toggle Offline mode.
  Confirm no old-account artwork appears and offline rows retain safe placeholders.

## Large playlist/album opening — November 17, 2026

Collection details now use a dedicated, height-constrained ListView outside
MainScroll. Its ItemsSource contains lightweight indexed song records; native
ContainerContentChanging creates song controls only for realized containers and
clears recycled content. All songs and repeated playlist entries remain in server
order, and clicking a row starts the full collection at that occurrence's index.
The action/column header remains above the scrolling rows.

Opening a collection renders its title and Back action before awaiting the server.
Static `Loading songs…` text replaces the animated BusyBar for this path; an empty
ItemContainerTransitions collection removes the detail rows' entrance transitions.
Play/Download stay disabled until songs arrive. Navigation and connection changes
clear the detail host; request-generation checks reject superseded responses.
Retrieval still requests the full collection: this removes eager native control
creation, not server/network latency, and introduces no stale collection cache.

Local evidence:

- New rendering-source assertions failed before implementing the bounded list and
  immediate header, then passed. Visual/offline Node contracts: 33 passed.
- Existing runtime-XAML/source checks: 10 passed. Core tests: 184 passed;
  LogicChecks: 23 assertions passed.
- Windows-targeted compile-only build: 0 warnings, 0 errors. No installer was built
  or published, and no native timing improvement is claimed from these checks.
- Repository `npm run test` passed using an external Playwright preload to select
  the installed Chromium (no unrelated test-source edits).

Native Windows acceptance remains required (no Windows executable host is exposed
in this WSL session):

- [ ] Open a representative large playlist and album, cold and warm; record click
  to header, response completion, and first usable rows separately. Compare with
  the prior build using the same server/collection. Confirm no loading bar or row
  entrance animation on this path.
- [ ] Inspect realized containers with a 10,000-song fixture: container/control
  count should follow the viewport/cache, not the collection length. Resize,
  maximize, scroll rapidly and jump to the end; check row content and geometry.
- [ ] Click repeated occurrences of one song and verify queue index, full queue
  order, Play, Download, context menus, downloaded badges, keyboard navigation and
  screen-reader names. Recycled rows must not retain the previous song's actions.
- [ ] Back out while a request is pending; navigate to Settings/another collection,
  replace/remove the account, toggle Offline mode, and simulate a failed response.
  Late responses must not restore abandoned detail views or old account handlers.

## Library linked headings and header-arrow shelves — November 17, 2026

Native source now uses sage Playlists/Albums heading links (UI Automation level-2
headings), with compact, right-aligned, named previous/next buttons instead of
separate View all links and full-width horizontal scrollbars. Dedicated collection
layouts, playlist-first order, query-aware Songs visibility, and offline rendering
are unchanged.

Wheel routing is deliberately attached with `AddHandler(..., handledEventsToo:
true)` to the internal ScrollViewer's **content**, below the ScrollViewer in the
bubble route. A ListView-level handler would be too late to stop the internal
viewer from converting the wheel first. Each row registers one wheel handler,
marks the event handled even at a boundary, and detaches on unload. Ordinary
vertical wheel updates MainScroll; Shift+wheel and genuine horizontal wheel
update the row without moving focus. Pending targets preserve successive small
wheel deltas while ChangeView is pending; horizontal snap points are disabled.
Touch/precision-touchpad DirectManipulation and native keyboard/bring-into-view
remain untouched; precision gestures are not necessarily PointerWheelChanged
input. Arrow states use actual offset/scrollable width on view, size, and layout
changes, including extent changes without viewport resizing.

Executed locally:

- Test-first red: new policy tests failed to compile because the policy did not
  exist; then **16 executable policy cases passed**, covering ordinary/Shift/
  horizontal wheel direction, repeated 20-unit deltas, clamping, and boundaries.
- Rendering-source red: **3 failed, 11 passed** before native implementation
  (linked headings, hidden scrollbars, and shelf event/arrow wiring). A further
  heading-state assertion failed before adding sage hover/pressed resources,
  after inspecting the pinned WinUI `DefaultHyperlinkButtonStyle` template.
- Final visual/offline source contracts: **31 passed, 0 failed, 0 skipped**.
- Full Core suite: **184 passed, 0 failed, 0 skipped**. TRX:
  `artifacts/test-results/library-shelves-core.trx`.
- LogicChecks: **23 assertions passed**.
- Real Windows-targeted cross-build: **0 warnings, 0 errors**. Artifact:
  `Music.App/bin/Release/net10.0-windows10.0.19041.0/win-x64/Music.dll`;
  its PE signature and AMD64 machine header were inspected. This is a
  **compile-only library**, not a distributable app or installer. Existing Linux
  overrides were supplied only on the command line; release defaults are intact.
- `git diff --check` passed. No Python source, release tooling, commits, pushes,
  publications, or hosted workflows were changed/run for this implementation.

Primary API references checked against Microsoft Learn:

- [Routed AddHandler and handledEventsToo](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.uielement.addhandler)
- [PointerWheelChanged and class handling](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.uielement.pointerwheelchanged)
- [MouseWheelDelta direction](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.input.pointerpointproperties.mousewheeldelta)
- [ScrollBarVisibility.Hidden retains scrolling](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.controls.scrollbarvisibility)
- [ChangeView offsets and animation flag](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.controls.scrollviewer.changeview)
- [UI Automation heading level](https://learn.microsoft.com/en-us/windows/windows-app-sdk/api/winrt/microsoft.ui.xaml.automation.automationproperties.setheadinglevel)

**Native runtime acceptance is not executed.** This WSL environment exposes
neither `cmd.exe` nor `powershell.exe`. Source contracts establish wiring, not
actual event order, template realization, rendered geometry, or device behavior.
Before release, on an audio-capable Windows machine:

- [ ] At restored/maximized sizes and high DPI, inspect the approved sage layout,
  heading hover/focus, header-arrow alignment, and absence of horizontal bars.
- [ ] Hover artwork without focusing it: ordinary wheel moves only MainScroll;
  Shift+wheel and horizontal wheel move only that shelf. Repeat five small deltas
  in each direction, rapidly and with pauses; verify accumulation and no snapping.
- [ ] Verify first/end/no-overflow arrows, resize from overflowing to fitting and
  back, keyboard activation, focus navigation, and touch/precision-trackpad pans.
- [ ] Navigate via each heading, search/clear, change dedicated grid/list choices,
  and reconnect/re-render repeatedly; verify no duplicate wheel handling or stale
  handlers and no empty-search Songs section.
- [ ] Verify existing Offline & recent filtering/playback and native audio gates.


## Opt-in trimming experiment

The normal build/release remains untrimmed; `MusicTrimmed=true` is experimental.
The local Windows compile-only publish completed with strict trim warnings and
generated WinRT marshalling. Comparing clean outputs of the same source:

| Compile-only publish files | Untrimmed | Trimmed |
| --- | ---: | ---: |
| Total bytes | 120,934,287 | 36,678,470 |
| File count | 231 | 90 |

This is an 84,255,817-byte reduction in the managed/.NET publish subset. Both
outputs deliberately omit native WinUI payload and merged PRI processing; these
figures are **not** an installer size, full application size, or installed footprint.
No trimmed installer has been publicly released. Native hosted evidence is below.

Local checks:

- Core: 100 tests passed with JSON reflection disabled, including legacy JSON
  compatibility and exact persistence shape. Update-source suite: 12 passed.
- Windows Node/visual checks: 153 passed, 0 skipped, with real PowerShell available.
- Actual self-contained trimmed Linux compatibility executable passed; it uses
  the real Core and pinned Velopack libraries with explicit HTTP fixtures.
- Normal Windows-targeted C# compile check: 0 warnings, 0 errors.
- Existing release regressions: 28 Windows and 19 Android tests passed.
- The approved existing Python assertion now checks the generated-metadata
  serialization call while retaining its PasswordVault/password-persistence
  safeguards. All 10 runtime-XAML/source checks pass; that CI blocker is removed.
- At this checkpoint, a legacy browser study had library-setup vertical overflow,
  also reproduced on unchanged HEAD with the same Chromium and dependencies.
  That study and its tests have since been removed; this is historical evidence,
  not an outstanding failure in the active browser suite.

### Native hosted evidence — November 15, 2026

Run `43689468756` tested source `26cdee86e53ec20bc0ab68636212bc4c2e7217ca`
on `windows-2025`, using internal fixture versions `0.1.32000` (untrimmed) and
`0.1.32001` (trimmed). These are build-only fixtures, not public releases.
Normal releases remain untrimmed.

The full native builds used real Windows publish/PRI processing, self-contained
.NET and WinUI, and strict trimming warnings. The uninstrumented builds measured:

| Full native output | Untrimmed bytes | Trimmed bytes | Reduction |
| --- | ---: | ---: | ---: |
| Setup executable | 79,890,858 | 46,537,629 | 42% |
| Publish directory | 174,700,277 | 90,443,858 | 48% |

The actual fresh trimmed installation contained **135,113,984 bytes**:
90,287,459 bytes in `current`, 39,019,421 bytes in retained package cache, and
5,807,104 bytes in updater/stub/other files. Publish-directory size is not the
same as the installed footprint; both were measured separately.

Verified on Windows:

- Exact uninstrumented trimmed setup installed, displayed a responsive rendered
  native window with screenshot evidence, closed, and uninstalled cleanly.
- Separately instrumented fixture builds used the real WinUI controls and
  production client behind an explicit HTTP fixture handler. Library, search,
  album/playlist detail, and queue checks passed, including realized fixture text.
- Real PasswordVault storage/retrieval passed. A real installed untrimmed-to-trimmed
  package upgrade retained the legacy-compatible profile, vault credential,
  duplicate queue, logical saved position, shuffle/repeat, and view preferences.
- The production restore path restored the saved state paused. Fixture credential
  cleanup, upgraded-install uninstall, owned-shortcut removal, and directory
  cleanup passed with no cleanup errors.
- Core and update suites passed on Windows; the trimmed compatibility executable
  and source/security contracts passed on Linux.

**Acceptance is partial, not passed.** The runner had no enabled audio renderer.
Both native media probes explicitly reported `renderer-unavailable`; actual
source opening, decoder seek, playback-clock advancement, pause, audible output,
and hardware routing were not verified. The workflow deliberately failed rather
than approving incomplete acceptance. Logical position restoration is not proof
of a native decoder seek. Real HTTPS/server authentication also remains outside
the injected fixture transport. Audio-capable Windows acceptance is still needed
before enabling trimming for normal releases.

## UI, branding, and user-initiated updater changes

Executed locally for this change (not native Windows acceptance):

- Core protocol/persistence/updater tests: **95 passed**. Includes a 602-song queue with repeated saves and reopen, safe session-save failure codes, explicit updater actions, error/retry, downgrade refusal, and save-before-restart gating.
- Update-source/XAML tests: **12 passed**, using the real pinned Velopack library with injected HTTP fixtures. Covers mixed Windows/Android releases, pagination, numeric selection, wrong origins/versions/architectures, missing packages, and empty/unrelated feeds.
- Windows Node suites plus visual-source contracts: **131 passed, 3 skipped**. Skips require PowerShell, which is unavailable locally. These are source, asset, and automation checks, not rendered WinUI geometry checks.
- Existing runtime-XAML source contracts: **10 passed**. Executable logic harness: **17 assertions passed**.
- Integrated Windows-targeted C# cross-build: **0 warnings, 0 errors**. Both workflow YAMLs passed actionlint 1.7.12; the configured NuGet audit reported no known vulnerable packages for Music.App.
- Existing release regressions: **28 Windows and 19 Android tests passed**. No Python source was added or modified.
- At this checkpoint, `npm run test` passed mockup/workflow/onboarding/phone-size stages but failed a legacy browser study's library-setup overflow assertion, also reproduced in an unchanged baseline. That study and its tests have since been removed. This historical result is not a statement about the active suite.

The reported session-save failure has **not been reproduced**. Its cause is not claimed fixed: errors now expose only safe category codes, dismissed identical warnings stay dismissed while retries continue, and a later successful save clears the corresponding notice. No credentials, exception messages, or signed media URLs enter those diagnostics.

Native acceptance still required:

- [ ] Inspect playlist columns, compact header, navigation, transport icons, and sage sliders at normal and maximized sizes, including the supplied screenshot's DPI and long labels.
- [ ] Verify the shared Android equalizer artwork in the titlebar, taskbar, installed shortcut, setup, and uninstaller.
- [ ] Install this updater-enabled build manually first; 0.1.27 cannot update itself from its placeholder panel.
- [ ] Exercise Check → Download → Restart and install to a newer real package; verify credentials, per-collection layout, queue occurrence/position, and paused restoration survive.
- [ ] Test network failure/retry, download interruption, and session-save failure blocking restart.
- [ ] Reproduce the original save warning and use its safe category code to isolate the actual cause.

No updated distributable was published or hosted workflow triggered during these local changes.

## Prior implementation-checkpoint evidence

## Executed in the development container

- `dotnet test Music.Core.Tests/Music.Core.Tests.csproj -c Release`: **73 passed, 0 failed, 0 skipped**. The TRX is generated at `artifacts/test-results/windows-core.trx` when using the results-directory arguments below.
- `dotnet run --project validation/LogicChecks.csproj -c Release`: **17 executable C# assertions passed**, covering request cancellation/currentness, collection policies, and normalized connection identity.
- `python3 -m unittest discover -s validation -p test_source_contracts.py -v`: **10 source-contract checks passed**, including installer-hook ordering. These check embedded XML/named controls/security boundaries and source guards, not native UI execution.
- Native Windows-targeted C# cross-build: **0 warnings, 0 errors**. `Music.exe` was inspected and is an x64 Windows PE apphost. The generated app is not a standalone installer.
- NuGet audit of `Music.App` and `Music.Core`, including transitive dependencies: **no known vulnerable packages reported by the configured NuGet source**. This is not a comprehensive security audit.
- `git diff --check`: passed.

Commands from `apps/windows/`:

```sh
dotnet test Music.Core.Tests/Music.Core.Tests.csproj -c Release --logger 'trx;LogFileName=windows-core.trx' --results-directory artifacts/test-results
dotnet run --project validation/LogicChecks.csproj -c Release
python3 -m unittest discover -s validation -p test_source_contracts.py -v
dotnet build Music.App/Music.App.csproj -c Release -p:OutputType=Library -p:WindowsAppSDKSelfContained=false -p:PrepareForRunDependsOn=CopyFilesToOutputDirectory
dotnet list Music.App/Music.App.csproj package --vulnerable --include-transitive
dotnet list Music.Core/Music.Core.csproj package --vulnerable --include-transitive
```

The compile-only properties deliberately bypass Windows manifest/resource processing. With component-only SDK dependencies, compile as a library on Linux to avoid requiring the full runtime package and its executable bootstrapper; this checks C# integration, not an executable or deployable payload. Do not pass these properties to a real Windows release build. Linux cannot execute the Windows app or validate WinUI object creation just by parsing the XML.

## Review corrections included

1. Switching connections clears old collection/detail controls and invalidates captured connection leases, preventing old handlers from sending song IDs to the replacement server.
2. Replacement searches invalidate pagination generations immediately. Pagination captures its rendered query/offset and cannot cancel a newer replacement search or append old-query pages.
3. Reconnecting the same normalized HTTPS endpoint/account stages a copy of the previous playback snapshot under the replacement profile before committing credentials; it restores paused after activation. Different accounts/endpoints never inherit that session. Credential writes retain the existing unique-ID rollback strategy.

The source-contract checks cover integration guards; the C# harness tests the underlying request/identity rules. Native interaction reproductions still belong to the Windows smoke test below.

## Installer/release tooling checks

- **26 Python Windows installer/release tests passed**, including package completeness, source/version isolation, immutable publication/readback, tampering, and real CLI invocation against explicit unit fixtures. Publication tests inject GitHub responses and do not create real releases.
- **12 existing Android release tests passed**; Android publication tooling and workflow were not changed.
- Both Windows workflow files pass `actionlint 1.7.12` (shellcheck/pyflakes integrations disabled).
- The pinned real `vpk 1.2.161` command verified the application's startup hook and produced a Windows x64 setup executable and update package in an internal Linux cross-pack probe. This is not a production release: the payload deliberately uses the Linux compile-only Windows App SDK layout. Production validation rejects it because `Microsoft.UI.Xaml.dll` is absent.
- Actual Velopack output revealed a UTF-8 BOM in the legacy index. The validator now handles it, and a regression test covers this real-tool format.
- The version helper/installer plan CLI runs against actual Git history. Release builds require Windows and committed source; unsigned previews require explicit acknowledgement. No hosted build or publication command was run.

See [RELEASING.md](RELEASING.md) for the native Windows build and opt-in GitHub prerelease workflow.

## Existing browser regression suite

At this checkpoint, the repository-wide `npm test` passed mockup, workflow, onboarding, and phone-size stages but failed the now-retired readable-player study. No tracked browser or Android source was changed by that Windows work. The legacy study and its tests have since been removed; this record does not describe the active browser suite.

## Windows acceptance — not yet executed

The installer release workflow now includes an automated install → responsive native-window launch with screenshot → uninstall gate before publication. Relevant `main` pushes publish an unsigned Windows prerelease only when that gate succeeds. Read the exact hosted run and its `music-windows-smoke-<sha>` evidence for results; the local contract suite is not a native acceptance pass.

Prior automation checks for this gate included **11 Node tests passed, 0 skipped** with PowerShell available, plus Windows/Android tooling tests. Those historical results are not the current run. See the UI/branding/updater section above for current results, skips, and the exact independently reproduced browser failure. No browser assertions were weakened.

- [ ] Default self-contained Windows build/publish succeeds without the Linux overrides.
- [ ] Blank first launch creates a native window; all runtime XAML and default control templates load.
- [ ] Connect to a trusted HTTPS Navidrome server using credentials typed locally; restart retrieves the password securely.
- [ ] Albums and playlists use grids by default; Songs starts in a list; layout preferences survive restart.
- [ ] Search hides empty groups; dedicated pages omit redundant View all links; rapid search plus pagination cannot mix results.
- [ ] Browse an album/playlist without replacing playback; explicitly play it and check real audio, seek, volume, previous/next, and media keys.
- [ ] Output enumeration reflects actual devices; selected-device switching and hot-unplug behavior are verified with hardware.
- [ ] Pause/close/reopen preserves duplicate queue occurrences, selected occurrence and position, remaining paused.
- [ ] Reconnecting the same endpoint/account preserves the session; failed or cancelled replacement connections keep working credentials and playback.
- [ ] Switching accounts during pending requests does not display old artwork or use old song IDs.
- [ ] Minimize, resize, long labels, large libraries, unavailable streams, and shutdown during in-flight operations behave correctly.

The build workflow runs code checks on Linux and Windows and publishes a self-contained native Windows build artifact; the release workflow additionally packages and smoke-tests the installer before publishing. Consult their exact run histories for verified outcomes. The first Windows run caught a test-only SQLite file-sharing issue: raw database inspection must happen after disposing the store. The signed-media rejection test now closes the store before reading its file. Upgrade/apply/restart behavior, authenticated use, playback and hardware behavior still require separate validation. Signing is not configured; explicit in-app update actions are implemented but have not passed native upgrade acceptance. Song rating API support exists in Core, but the native rating UI and a full dedicated Now Playing screen are not included yet.
