# Windows installer and GitHub Releases

## What is available

- `scripts/build-windows-installer.py`: Windows-only orchestration of tests, a real self-contained x64 publish, pinned Velopack packaging, and integrity metadata.
- `scripts/windows-release.py`: commit-count versioning, package validation, checksum generation, and guarded GitHub publication.
- `apps/windows/.config/dotnet-tools.json`: pins `vpk` to **1.2.161**, matching the app's Velopack NuGet package.
- `.github/workflows/windows-release.yml`: manually build an installer artifact; optionally publish a verified **unsigned Windows prerelease**.

The app calls Velopack before WinUI initialization so installer lifecycle arguments can be handled without opening the window or session database. Automatic apply-on-startup is explicitly disabled. **In-app update checking/download/restart is not enabled by this tooling change.**

## First usable release gate

Full package builds must run on Windows. The release builder refuses Linux and refuses uncommitted release source. It also rejects compile-only payloads that lack the self-contained .NET and native Windows App SDK files. The Windows application and installer still need real Windows smoke testing; packaging success alone is not proof of launch/audio/upgrade behavior.

The current pipeline deliberately creates **unsigned previews only**. No code-signing certificate has been configured. Windows security tools may flag an unsigned installer. SHA-256 checks verify file integrity, not publisher identity. Signed/stable distribution remains a separate setup and verification step; the metadata tool refuses to claim signing has happened.

No credentials, signing passwords, or certificates belong in command arguments, this repository, or chat. Publication in Actions uses its scoped `GITHUB_TOKEN` automatically; no personal token is embedded in Music.

## Build on Windows

Requirements: Git, Python 3, and the .NET SDK selected by `apps/windows/global.json`. Use a clean checkout with complete Git history.

From the repository root:

```powershell
# Inspect commands; this does not create an installer or contact GitHub.
python scripts/build-windows-installer.py --plan

# Explicitly acknowledge unsigned-preview packaging.
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

## GitHub Actions

Commit and push the tooling and application only when ready and authorized. Nothing is published merely by adding these files locally.

In GitHub Actions, choose **Package and release Windows installer**:

1. Leave **publish_preview** off to build and retain a downloadable installer artifact only.
2. Enable **publish_preview** to build, validate, and publish a Windows prerelease. Publication is restricted to `main`.

Equivalent commands after the workflow exists on GitHub:

```sh
gh workflow run windows-release.yml --ref main -f publish_preview=false
gh workflow run windows-release.yml --ref main -f publish_preview=true
```

Do not run both commands unless you intentionally want two builds. Monitor the selected run to completion and inspect its installer artifact and metadata. The general `windows-build.yml` workflow continues running code/tests on applicable pushes and pull requests; it does not automatically publish releases.

## Version and platform isolation

A full-history commit count determines `0.1.<count>`, with a Windows tag `windows-v0.1.<count>`. The count is restricted to **1–65534** because C# AssemblyVersion does not accept 65535 as a component. A shallow/unborn checkout is rejected. The app's published version and informational source SHA are set explicitly.

Windows uses package ID `MusicWindows`, runtime/channel `win-x64`, and Windows-only filenames. Android tags and APK assets are untouched. Windows publication always sets `prerelease=true` and `latest=false`; it never promotes itself over Android's existing repository-wide latest release.

## Publication safety

The publisher validates package/channel/version identity, x64 PE headers, native Windows and .NET payload completeness, package/runtime JSON metadata, feed hashes/sizes, the legacy index, NuGet metadata, and the complete asset inventory. Unsafe zip paths, symlinks, duplicate names, extra assets, missing assets, and tampering are rejected.

It creates or repairs only a matching unpublished draft, downloads and verifies the uploaded bytes, then publishes and reads back the exact release/tag/source SHA and bytes again. Published assets are immutable to this tool: reruns verify the retained original artifacts, never overwrite them. Use a new commit/version for changed binaries. Authentication/network errors do not count as a missing release.

For a previously built package, publication can be invoked separately from a clean checkout at the **same commit**:

```powershell
python scripts/windows-release.py validate --directory dist/windows/<version>/releases
python scripts/windows-release.py publish --directory dist/windows/<version>/releases --repository everyonesoftware/music
```

Replace `<version>` with the actual metadata value. A changed HEAD cannot publish an earlier package under a new source identity. No publication command was run while creating this tooling.

## Verification and remaining work

Run the tooling tests with `python -m unittest discover -s tests -p 'test_windows_*.py' -v`. Test publication uses explicit injected GitHub fixtures; it does not publish a real release.

Velopack's real Windows pack command was also exercised in Linux cross-pack mode against the actual compiled application and emitted a real Windows setup executable. That internal probe uses the compile-only Windows App SDK layout and is **not a distributable release**. Production validation correctly rejects it for missing native Windows App SDK payload files. Full Windows build, installation, uninstall, upgrade, credential/session survival and actual app runtime remain Windows acceptance gates. Signing and the in-app update UI remain unfinished.

Primary references: Velopack's Getting Started (C#), Packaging Overview, Release Channels, Windows CLI reference and Integrating Overview documentation. CLI arguments and output formats were additionally checked against the installed pinned `vpk` tool, not inferred from another version.
