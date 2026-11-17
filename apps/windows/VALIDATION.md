# Windows implementation checkpoint — validation

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
