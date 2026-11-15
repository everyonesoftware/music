# Windows implementation checkpoint — validation

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
No trimmed installer has been published or native trimmed UI exercised here.

Local checks:

- Core: 100 tests passed with JSON reflection disabled, including legacy JSON
  compatibility and exact persistence shape. Update-source suite: 12 passed.
- Windows Node/visual checks: 138 passed, 3 PowerShell-dependent tests skipped.
- Actual self-contained trimmed Linux compatibility executable passed; it uses
  the real Core and pinned Velopack libraries with explicit HTTP fixtures.
- Normal Windows-targeted C# compile check: 0 warnings, 0 errors.
- Existing release regressions: 28 Windows and 19 Android tests passed.
- The approved existing Python assertion now checks the generated-metadata
  serialization call while retaining its PasswordVault/password-persistence
  safeguards. All 10 runtime-XAML/source checks pass; that CI blocker is removed.
- The browser prototype suite still fails at `tests/readable-player.mjs:93`
  (library-setup vertical overflow), also reproduced on unchanged HEAD with the
  same Chromium and dependencies.

Native acceptance remains required for runtime XAML/templates, authenticated
connection and PasswordVault, browse/search, real media playback/device routing,
profile/session restoration, and old-install → newer-trimmed-package upgrades.
The manual build-only workflow input prepares a native artifact, not acceptance.

## UI, branding, and user-initiated updater changes

Executed locally for this change (not native Windows acceptance):

- Core protocol/persistence/updater tests: **95 passed**. Includes a 602-song queue with repeated saves and reopen, safe session-save failure codes, explicit updater actions, error/retry, downgrade refusal, and save-before-restart gating.
- Update-source/XAML tests: **12 passed**, using the real pinned Velopack library with injected HTTP fixtures. Covers mixed Windows/Android releases, pagination, numeric selection, wrong origins/versions/architectures, missing packages, and empty/unrelated feeds.
- Windows Node suites plus visual-source contracts: **131 passed, 3 skipped**. Skips require PowerShell, which is unavailable locally. These are source, asset, and automation checks, not rendered WinUI geometry checks.
- Existing runtime-XAML source contracts: **10 passed**. Executable logic harness: **17 assertions passed**.
- Integrated Windows-targeted C# cross-build: **0 warnings, 0 errors**. Both workflow YAMLs passed actionlint 1.7.12; the configured NuGet audit reported no known vulnerable packages for Music.App.
- Existing release regressions: **28 Windows and 19 Android tests passed**. No Python source was added or modified.
- `npm run test`, with the installed Chromium selected using an external preload, passes mockup/workflow/onboarding/phone-size stages but fails `tests/readable-player.mjs:93`: the phone library-setup screen reports vertical overflow. The identical command fails at the identical assertion in an isolated `git archive HEAD` baseline with the same browser and dependencies. This prototype failure is not introduced by the Windows changes; the whole browser suite is not green.

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

The repository-wide `npm test` was executed with the installed Chromium selected through a temporary preload outside the repository. Mockup, workflow, onboarding, and phone-size stages passed. The existing readable-player stage failed at `tests/readable-player.mjs:93` with a popup overflow assertion. No tracked browser or Android source was changed by this work. The browser suite is therefore **not reported green**.

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
