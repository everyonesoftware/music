# Windows installer and GitHub Releases

## What is available

- `scripts/build-windows-installer.py`: Windows-only orchestration of tests, a real self-contained x64 publish, pinned Velopack packaging, and integrity metadata.
- `scripts/windows-release.py`: existing commit-count versioning, package validation and checksum generation. Its legacy preview publisher is no longer used by CI.
- `apps/windows/.config/dotnet-tools.json`: pins `vpk` to **1.2.161**, matching the app's Velopack NuGet package.
- `.github/workflows/ci.yml`: exactly five jobs (`validate`, `browser-prototypes`, `android`, `windows`, `release`). Validation selects changed inputs; browser-prototype checks, Android and Windows run in parallel; one final release job waits for selected browser checks to pass and serializes platform publication, index verification and Latest promotion, and historical recovery.
- `scripts/publish-windows-main-release.mjs`: validate and add the complete Windows installer/feed/package inventory to the same-version, same-source stable **Music** release. It creates no Windows preview release.
- `scripts/attach-windows-installer.mjs`: add the installer and its dedicated checksum to the same-version, same-source `v0.1.<count>` release without replacing Android assets.
- `scripts/test-windows-installer.ps1`: exercise the packaged install → native-window launch → uninstall lifecycle on Windows, retaining diagnostic evidence.

The app calls Velopack before WinUI initialization so installer lifecycle arguments can be handled without opening the window or session database. Automatic checking and apply-on-startup remain disabled. Settings now offers explicit **Check for updates → Download update → Restart and install** actions. This implementation still requires native installed-app upgrade acceptance; a successful cross-build is not that acceptance.

### User-initiated updates

The updater reads `latest-versions.json` from GitHub's Latest Music release without credentials and uses its Windows tag to read one stable release's metadata. `releases.win-x64.json` must contain exactly one matching `MusicWindows` full package attached to that release; an EXE alone cannot supply an in-app update. An Android-only Latest release points to the existing Windows publication. Missing or malformed index/feed metadata is an error, not evidence that the installed app is up to date. Package identity, architecture, origin and downgrade checks still apply. GitHub rate limits and connectivity failures remain retryable.

Downloading does not install or restart. The explicit restart action must save the current queue and playback position successfully before invoking Velopack. Save failure blocks installation; credentials and connection files are not replaced by the updater. Loose development builds explain that installation is required. Window shutdown cancels active downloading and detaches panel handlers.

Run `dotnet test apps/windows/Music.Update.Tests/Music.Update.Tests.csproj -c Release` as well as the Core tests. The workflow runs these before packaging. Validate a real old-install → newer-package upgrade on Windows, including paused restoration, credentials, layout settings, interruption/retry, and installed icon rendering, before claiming the upgrade path is proven.

## First usable release gate

Full package builds must run on Windows. The release builder refuses Linux and refuses uncommitted release source. It also rejects compile-only payloads that lack the self-contained .NET and native Windows App SDK files. Publication requires the packaged installer to pass the Windows install/launch/uninstall smoke test. This is not proof of audio, hardware routing, authenticated use, or upgrade behavior; those still require separate acceptance testing.

Windows installers remain **unsigned**, but future GitHub publication uses the normal stable Music release, not a separate unsigned-preview prerelease. No code-signing certificate is configured. Windows security tools may warn; SHA-256 proves file integrity, not publisher identity. Stable GitHub release status is independent of signing. Existing validator metadata retains its legacy `unsigned-preview` schema field for compatibility and does not create a GitHub prerelease.

No credentials, signing passwords, or certificates belong in command arguments, this repository, or chat. Publication in Actions uses its scoped `GITHUB_TOKEN` automatically; no personal token is embedded in Music.

## Build on Windows

Requirements: Git, Python 3, and the .NET SDK selected by `apps/windows/global.json`. Use a clean checkout with complete Git history.

From the repository root:

```powershell
# Inspect commands; this does not create an installer or contact GitHub.
python scripts/build-windows-installer.py --plan

# Explicitly acknowledge unsigned installer packaging.
# vpk 1.2.161 reads VPK_ICON as its --icon option; inherited by the Python runner.
$env:VPK_ICON = (Resolve-Path apps/windows/Music.App/Assets/Music.ico).Path
python scripts/build-windows-installer.py --allow-unsigned
```

The default output is `dist/windows/<version>/`. Nonempty output directories are rejected, not silently overwritten. `publish/` contains the complete application and `releases/` contains:

- `MusicWindows-win-x64-Setup.exe` — installer.
- `MusicWindows-<version>-win-x64-full.nupkg` — full update package.
- `releases.win-x64.json` — Velopack update feed.
- `assets.win-x64.json` and `RELEASES-win-x64` — packaging/legacy indexes.
- `windows-release.json` — exact source SHA, version, platform, unsigned-preview status, and asset hash/size inventory.
- `SHA256SUMS` — checksums for the five Velopack artifacts.

Only a Start menu shortcut is requested. No startup or launch-at-sign-in registration is requested. Packaging includes the supported Visual C++ x64 runtime prerequisite; .NET and the Windows App SDK are bundled in the published application.

### Shared Android/Windows branding

`node scripts/generate-windows-icons.mjs` regenerates `Music.App/Assets/Music.ico`, `Music.png`, and `Music.svg` directly from Android's `ic_audio_foreground.xml` and `icon_colors.xml`. The ICO contains 16/20/24/32/40/48/64/96/128/256px 32-bit PNG frames. The app embeds the ICO into its executable and copies ICO/PNG to build and publish output. The release workflow sets the pinned Velopack `VPK_ICON` environment option so setup, uninstaller, and shortcut branding use the same icon without changing the Python orchestration. Local builders must set it as shown above; omitting it leaves Velopack's package icon unspecified.

Run `node --test tests/windows-branding.test.mjs` after changing Android icon resources or regenerating assets. Native Windows acceptance must still check the installed shortcut, taskbar, and setup/uninstall icons; source contracts and Linux cross-pack probes cannot establish shell rendering.

## Package footprint

Music references `Microsoft.WindowsAppSDK.WinUI` and its transitive Foundation and
InteractiveExperiences dependencies instead of the full Windows App SDK umbrella
package. The WinUI version is unchanged from the former umbrella dependency.
This avoids deploying unused AI, machine-learning (including ONNX), Widgets, and
DWrite components. Both .NET and the required native Windows App SDK components
remain self-contained; users do not need to install those runtimes separately.
Normal releases remain untrimmed. The opt-in experiment below preserves the
runtime-loaded WinUI metadata and uses generated WinRT/JSON support.

Run `node --test tests/windows-package-size.test.mjs` from the repository root
with the pinned .NET SDK available. It restores the real dependencies and checks
the SDK's resolved native-file inventory, including required DLLs and PRI inputs,
and rejects the optional components and full-runtime package. Both Windows
jobs run these checks. The native installer lifecycle gate still verifies
the generated merged PRI, actual launch, and uninstall before publication.

File-inventory size comparisons are not installer measurements. Measure the
actual setup executable and installed directory on Windows before reporting
download/installed savings; installer compression and its retained files affect
those totals.

### Experimental trimming (build-only)

`-p:MusicTrimmed=true` enables partial trimming, generated C#/WinRT marshalling,
and reflection-free JSON. Dynamic code generation is disabled so the linker can
remove unsupported generic marshalling fallbacks. The application and helper
types used by that generator are partial; unsafe blocks are enabled for its
generated vtables. The complete managed WinUI metadata provider is rooted for
`XamlReader.Load`. Trim warnings remain errors; they are not suppressed.

Profiles and playback snapshots use generated JSON metadata while retaining the
existing stored format. The Windows release parser also uses generated metadata.
Core compatibility tests read legacy JSON and verify the persisted shape.

From the repository root, on Windows, publish to a new/empty output directory:

```powershell
dotnet publish apps/windows/Music.App/Music.App.csproj -c Release -r win-x64 --self-contained true -p:MusicTrimmed=true -o dist/windows-trim-experiment
```

The build workflow's manual `trimmed_experiment` input defaults to false and can
produce this native build-only artifact after the workflow changes are deployed.
The installer/release workflow does not opt into trimming. Neither a loose native
publish nor the Linux compile-only measurements establish install/upgrade safety.
Keep the normal release untrimmed until native launch, XAML/templates, playback,
PasswordVault/profile restoration and installed-app upgrade have passed.

The executable compatibility harness uses the real Core and Velopack libraries
with explicitly injected HTTP fixtures, JSON reflection and dynamic code disabled.
On Linux, publish and execute the trimmed harness:

```sh
dotnet publish apps/windows/Music.TrimChecks/Music.TrimChecks.csproj -c Release -r linux-x64 --self-contained true -p:PublishTrimmed=true -o apps/windows/artifacts/trim-checks
./apps/windows/artifacts/trim-checks/Music.TrimChecks
node --test tests/windows-trimming.test.mjs
```

This checks persisted-profile compatibility, SQLite reopen with a duplicate-heavy
queue, view persistence, signed-media rejection, and real Windows-feed parsing;
it is not WinUI, PasswordVault, audible playback, or native upgrade acceptance.

The isolated `windows-native-validation.yml` workflow runs on
`validation/windows-trimming`, with read-only repository permissions and no
release publication. `scripts/test-windows-trimmed-native.ps1` first packages and
tests the exact uninstrumented trimmed installer using the existing native-window
screenshot/uninstall gate. It separately builds test-only instrumented fixture
versions `0.1.32000` (untrimmed) and `0.1.32001` (trimmed) to exercise actual
PasswordVault/session state across an installed Velopack full-package upgrade.
These fixture versions are never public Music releases. Conditional native
acceptance code is not included in normal builds.

The integration transport uses explicit in-process HTTP fixtures rather than a
real Navidrome deployment. Media uses a generated PCM WAV through the native
player, not a claim that an observer heard sound. Missing hosted audio-renderer
support must be retained as a blocker/partial result, not converted into playback
success. The workflow retains detailed native results and build-size evidence
even when a gate fails; no result authorizes publishing a trimmed release.

## GitHub Actions

Commit and push the tooling and application only when ready and authorized. Nothing is published merely by adding these files locally.

Every main push starts one **Music CI** workflow. Validation compares each native platform with its last verified publication, accepting complete Windows inventories from stable main releases or historical preview feeds. Windows tests/packaging skip when inputs are unchanged; unpublished changes are retried. Unknown/shared inputs conservatively select both platforms. Publication requires packaging, integrity validation and real install/launch/uninstall success. See [selective builds](../../docs/SELECTIVE-BUILDS.md).

The stable main **Music 0.1.&lt;count&gt;** release receives the Windows installer, its dedicated checksum and all seven original Windows package/feed/integrity files. If Android changed, its verified release is published first and Windows preserves its assets/metadata. For Windows-only changes, the helper creates an exact-source stable main release with `latest=false` so older Android clients still find a matching APK at Latest; no Android binary is copied/rebuilt. No new `windows-v` tag/prerelease is created. Keep existing `windows-v0.1.43` and older feeds: the 0.1.43 client supports stable main feeds and serves as the legacy-client bridge. Feed/source compatibility is tested; actual installed-app upgrade behavior still needs native acceptance.

For an explicit manual run, choose **Music CI**, `component=windows` on `main`. It forces Windows tests, packaging and stable-main publication. For the existing build-only trimming experiment, also set `trimmed_experiment=true`; this disables packaging/publication and retains only the experimental artifact. `component=maintenance` runs historical attachment recovery and download refresh without native rebuilds.

Equivalent commands after the workflow exists on GitHub:

```sh
gh workflow run ci.yml --ref main -f component=windows
gh workflow run ci.yml --ref main -f component=windows -f trimmed_experiment=true
```

Do not run both commands unless you intentionally want two builds. Monitor the selected run and inspect its artifact/metadata. Pull requests use the same five-job entry point but cannot publish or access signing material.

The smoke test runs the actual setup executable, not the loose publish output, and fails closed when the native window cannot be verified. Its logs, screenshot, and structured result are retained in the `music-windows-smoke-<sha>` Actions artifact even when the test fails. Only a successful package job provides the installer artifact to the publish job. Do not bypass a failed smoke test to publish.

Run the workflow and smoke-script contract checks locally with `node --test tests/windows-*.test.mjs`. These checks validate the automation wiring; they do not execute a Windows installation on Linux. To exercise an already-built installer on Windows, use:

```powershell
./scripts/test-windows-installer.ps1 -InstallerPath dist/windows/<version>/releases/MusicWindows-win-x64-Setup.exe -EvidenceDirectory dist/windows-smoke
```

## Version and platform isolation

A full-history commit count determines `0.1.<count>`, published under the shared `v0.1.<count>` tag. The existing package manifest retains its internal `windows-v0.1.<count>` packaging label without creating that GitHub tag. The count is restricted to **1–65534** because C# AssemblyVersion does not accept 65535. Shallow/unborn checkouts are rejected. Version and source SHA are explicit.

Windows retains package ID `MusicWindows`, runtime/channel `win-x64` and platform-specific filenames. Adding Windows output to an existing stable Music release preserves its Android assets, title, notes and publication/latest policy. Existing historical Windows preview releases remain untouched.

## Publication safety

The publisher validates package/channel/version identity, x64 PE headers, native Windows and .NET payload completeness, package/runtime JSON metadata, feed hashes/sizes, the legacy index, NuGet metadata, and the complete asset inventory. Unsafe zip paths, symlinks, duplicate names, extra assets, missing assets, and tampering are rejected.

The stable Node publisher validates the complete original package with the existing validator before network writes, verifies the stable main tag/source, stages immutable bytes, uploads only missing assets without replacement, downloads every owned asset and verifies exact bytes/hash/source and untouched metadata again. Reruns verify retained original artifacts; conflicting bytes fail rather than overwrite. Use a new commit/version for changed binaries. Authentication/network failures do not count as a missing release.

The main-release attachment helper adds only missing installer/checksum assets without `--clobber`. Matching existing bytes are verified, conflicting bytes are rejected, and a partial attachment can be resumed. It downloads the uploaded installer/checksum to verify their exact bytes and reads back the source identity and existing unrelated assets before reporting success. It does not rebuild an older version under a newer commit identity.

For a previously built package, publication can be invoked separately from a clean checkout at the **same commit**:

```powershell
python scripts/windows-release.py validate --directory dist/windows/<version>/releases
node scripts/publish-windows-main-release.mjs --directory dist/windows/<version>/releases --repository everyonesoftware/music --android-changed false
```

Replace `<version>` with the actual metadata value. A changed HEAD cannot publish an earlier package under a new source identity. No publication command was run while creating this tooling.

## Verification and remaining work

Run the tooling tests with `python -m unittest discover -s tests -p 'test_windows_*.py' -v`. Test publication uses explicit injected GitHub fixtures; it does not publish a real release.

Velopack's real Windows pack command was also exercised in Linux cross-pack mode against the actual compiled application and emitted a real Windows setup executable. That internal probe uses the compile-only Windows App SDK layout and is **not a distributable release**. Production validation correctly rejects it for missing native Windows App SDK payload files. The workflow now gates publication on a native Windows build and install/launch/uninstall smoke test; consult the exact run and its evidence rather than treating local contract checks as a Windows pass. Upgrade/apply/restart, credential/session survival, playback, and physical device behavior remain acceptance gates. The user-initiated update UI is implemented; signing remains unconfigured.

Primary references: Velopack's Getting Started (C#), Packaging Overview, Release Channels, Windows CLI reference and Integrating Overview documentation. CLI arguments and output formats were additionally checked against the installed pinned `vpk` tool, not inferred from another version.
