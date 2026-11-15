# Music for Windows — first native implementation

A Windows-only WinUI 3/C# client for the same Navidrome server used by the Android app. The approved visual reference is `../../mockups/windows-native-v3.html`; the production app does not embed that HTML, mockup artwork, example credentials, or a demo music library.

## Projects

- `Music.App/`: native WinUI/XAML shell, Windows audio playback, protected credentials, connection and update settings.
- `Music.Core/`: platform-independent Navidrome protocol, music models, SQLite playback/view persistence.
- `Music.Core.Tests/`: executable protocol and persistence tests with isolated fixtures. These tests do not contact a personal server.

Android remains in the existing `android/` directory. This work does not reorganize or change its implementation or release workflow.

## Prerequisites and commands

Use the .NET SDK selected by `global.json` (10.0.404, allowing later servicing patches). Windows 10 version 2004 or newer and x64 are the initial target. Run the native application on Windows, not Linux/WSL.

From this directory:

```powershell
# Protocol, persistence, and collection behavior tests (Windows or Linux).
dotnet test Music.Core.Tests/Music.Core.Tests.csproj -c Release

# Runtime-XAML source contracts and platform-independent request/view policy checks.
python -m unittest discover -s validation -p test_source_contracts.py -v
dotnet run --project validation/LogicChecks.csproj -c Release

# Build on Windows.
dotnet build Music.App/Music.App.csproj -c Release

# Publish a self-contained Windows folder on Windows.
dotnet publish Music.App/Music.App.csproj -c Release -r win-x64 --self-contained true -o artifacts/windows/win-x64

# Launch the published app on Windows.
./artifacts/windows/win-x64/Music.exe
```

Keep the entire published folder together. This raw folder is **not an installer or a signed release**. Use the [installer and release tooling](RELEASING.md) to create a Velopack setup package from a clean committed checkout on Windows.

The interface XAML is embedded and loaded by WinUI's native `XamlReader`, with event handlers attached by C#. It remains native WinUI, not a WebView. This also allows the real Windows-targeted C# code to be cross-compiled without the Windows-only XAML compilation tools. A cross-build does **not** verify runtime XAML loading, actual playback, device switching, or a complete distributable package:

```sh
dotnet build Music.App/Music.App.csproj -c Release -p:WindowsAppSDKSelfContained=false -p:PrepareForRunDependsOn=CopyFilesToOutputDirectory
```

The second override skips Windows SDK resource processing for the Linux compile gate only. Never apply it to the Windows release build. Full self-contained deployment uses Windows SDK manifest tools and must be built on Windows. `.github/workflows/windows-build.yml` runs core tests on Linux and Windows and publishes an x64 Windows build artifact; it does not publish GitHub Releases. Adding the workflow locally does not mean it has run.

## Product constraints

- Familiar sage palette, album-first Library, and desktop-only layout.
- Sidebar: Library, Playlists, Albums, Songs (musical note), with Settings separately anchored.
- Playlists and Albums default to grids. View preferences belong to each collection; dedicated collection pages do not link to “View all” themselves.
- Search hides result sections without matches, including Playlists.
- Settings is a main-view page with actual connection fields and an Updates section.
- There is no option to forget playback position and no launch-at-sign-in feature.
- Browsing a collection must not replace the active queue. Playback starts only through an explicit action.
- Queue order, duplicate song occurrences, selected occurrence, and position are scoped to a connection. Cold restore must remain paused.
- Media output is bottom left and Queue is bottom right, beneath transport.

## Security and data

Only connect to a trusted HTTPS Navidrome endpoint. Unit-test loopback HTTP support is explicit and must not enable arbitrary insecure production servers. Credentials must not appear in logs, error details, SQLite, view models bound to XAML, artwork references, or saved stream URLs. The Android client is the compatibility reference for salted Subsonic token authentication and safe error handling; the server still receives authenticated requests over HTTPS.

Windows credentials are protected using the Windows credential facility rather than stored as plaintext configuration. Connection metadata and listening history remain local application data. Failed connection attempts must not discard the previously working connection/session. No real credentials are included in this repository or required to run the core tests.

## Updates and remaining release work

Velopack installer packaging and guarded Windows GitHub prerelease tooling are now available; see [RELEASING.md](RELEASING.md). The app handles installer lifecycle arguments before initializing WinUI, and automatic apply-on-startup is disabled. The release workflow validates hashes, source identity and self-contained Windows payloads and keeps Windows releases separate from Android. No real release has been published by this change. Signing, in-app update discovery/download/restart controls, and actual Windows installation/upgrade verification remain unfinished.

No forced restart during playback, no startup registration, and no audio auto-resume at cold launch are intended product behavior.

## Validation boundary

Actual check results and the Windows smoke-test checklist are recorded in [VALIDATION.md](VALIDATION.md).

Core tests and Windows-targeted C# compilation can run in the development container. The container does not expose a Windows desktop or mounted Windows toolchain. Native UI launch, runtime XAML resolution, audio decoding/seek/restore, media keys, Bluetooth/device selection, credential storage, installation, and upgrade survival must be exercised on Windows before this is called a verified usable Windows release. Testing against a real Navidrome library is separate from isolated protocol fixtures.
