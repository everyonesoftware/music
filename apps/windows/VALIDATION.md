# Windows implementation checkpoint — validation

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
dotnet build Music.App/Music.App.csproj -c Release -p:WindowsAppSDKSelfContained=false -p:PrepareForRunDependsOn=CopyFilesToOutputDirectory
dotnet list Music.App/Music.App.csproj package --vulnerable --include-transitive
dotnet list Music.Core/Music.Core.csproj package --vulnerable --include-transitive
```

The compile-only properties deliberately bypass Windows manifest/resource processing. Do not pass them to a real Windows release build. Linux cannot execute the Windows app or validate WinUI object creation just by parsing the XML.

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

The push workflow runs these checks on Linux and Windows and publishes a self-contained native Windows build artifact; consult its run history for the latest result. The first Windows run caught a test-only SQLite file-sharing issue: raw database inspection must happen after disposing the store. The signed-media rejection test now closes the store before reading its file. Installer/release tooling is implemented, but installer packaging, installation, actual Windows release publication and upgrade/apply/restart behavior still require separate validation. Signing and in-app update checks are not enabled. Song rating API support exists in Core, but the native rating UI and a full dedicated Now Playing screen are not included yet.
