# In-app updates

Music checks the public `everyonesoftware/music` GitHub release list for the highest numeric published stable `v0.1.<count>` Android release, independent of GitHub's Latest designation and publication order. Windows-only/no-APK releases are skipped. This is separate from Navidrome: no music-server URL, username, password or token enters the updater's HTTP client.

## Phone flow

- When a newer version is known, a download icon with a notification dot appears on the Library header and beside Queue on Now Playing. Both open **Settings → Updates**. Checking, navigation and downloading do not pause or replace the listening session.
- Updates is also available from the existing connection Settings screen. It shows the installed version, last successful check, release version/size/notes, automatic-check preference and manual **Check now**.
- Automatic checks are enabled by default and run when Music returns to the foreground, at most once per day. Attempts, including failed attempts, are throttled across process restarts. Manual checks bypass the daily interval; overlapping requests are coalesced. Turning automatic checks off cancels an in-flight automatic check, not a user-requested download.
- Downloads require a tap. Progress, Cancel and Retry are explicit. Navigation away does not cancel a download. Downloads run only while the app process exists; no permanent background scheduler or update notification permission is added. An interrupted process may require retrying the download.
- A downloaded file must pass size, SHA-256, package ID, exact expected version and current-signer preflight checks before **Install update** becomes available. A cached APK is reverified after process restart before being offered for installation.
- Installation is a separate confirmation. If music is playing, pause it explicitly first. The live controller is checked again after file verification and immediately before opening Android's installer, including buffering/play-when-ready state. Queue/index/position/repeat are checkpointed before handoff; the existing cold-start behavior restores paused.
- If Android does not yet allow Music to install APKs, the confirmed action opens **Allow from this source** for Music. Returning only refreshes permission state; the user must tap Install and confirm again. This is distinct from Android Auto's developer-mode Unknown sources setting.
- Android performs the final signature and install-policy validation and asks for its own confirmation. Cancelling that prompt does not install anything. There are no forced or silent updates. Backgrounding Music during preparation cancels the pending installation request rather than unexpectedly opening an installer later.

## Trust and limits

The client is isolated and unauthenticated, disables cookies/cache/authenticators, and requests only the fixed HTTPS GitHub release-list path with `per_page=100&page=N`. It scans sequentially through at most 10 pages before selecting a candidate; a short page establishes exhaustion unless it advertises another page. A full tenth page, failed/truncated inventory, malformed JSON or HTTP error is an error, never a successful up-to-date result or permission to use a partial inventory. API redirects cannot change the requested page, query or repository.

Drafts, prereleases and unpublished entries are ignored. Stable Android candidates must have canonical positive numeric tags (no leading zeroes, maximum count 2,100,000,000); duplicate Android versions fail closed. The highest candidate newer than the installed build must have exactly one APK, named `Music-0.1.<count>.apk`, and exactly one `release.json`. Invalid/ambiguous Android candidates, missing manifests or inconsistent metadata fail closed instead of silently falling back to an older release. Equal/older versions are never offered.

The client rejects inconsistent versions/tags/filenames, malformed manifests and assets outside the exact repository release path. Release notes are bounded plain text. Each release-list page is limited to 1 MiB and manifests to 64 KiB; APKs are limited to 150 MiB. Downloads follow only a bounded number of HTTPS redirects to GitHub's allowlisted asset hosts. Transport/parser failures expose a fixed safe message, not request URLs or exception causes. Cancellation closes the actual network call and removes the partial file.

The expected release files already produced by the workflow are:

- `Music-0.1.<count>.apk`
- `release.json`, containing `count`, `version`, `tag`, `sha`, `apk`, `sha256` and `bytes`

The app compares the APK's current signer certificate(s) with its own installed signer(s), not arbitrary signing history. Signing-key rotation is intentionally not supported in this first updater. PackageManager certificate metadata and a matching release checksum are preflight checks, not an independent proof of the APK's full cryptographic validity; Android's installer remains the final signature-verification authority.

The APK is stored in app-private `cache/updates/update.apk`. A non-exported FileProvider exposes only the updates cache subtree, and the installer receives only a temporary read URI grant. No broad filesystem sharing or credential storage changes are made. Cached metadata/preferences are app-private and existing backup exclusions remain in place. Checks contact GitHub and therefore expose ordinary network information such as the requesting IP and updater User-Agent to GitHub. A private GitHub repository is not supported; the app does not store or request a GitHub token.

The application ID remains `com.everyonesoftware.audio`; existing signing keys and data identifiers must remain unchanged. A debug APK cannot update an installation signed with the release key. A release-signed installation should use the next release-signed APK, not uninstall merely to try a debug build. A future Play-distributed variant would need its own distribution-policy-compatible update path.

## Verification

- JVM `UpdateClientTest`: real MockWebServer requests using a loopback-only transport seam, mixed-platform numeric selection, pagination/exhaustion/bounds, fail-closed ambiguous candidates and incomplete inventories, stable release/manifest validation, URL and redirect policy, bounded reads, size/checksum failure, safe errors and cancellation.
- JVM `UpdateCoordinatorTest`: daily throttling, manual checks, disabled-check behavior, restart throttling, coalescing, explicit downloads, verification failure/retry, cancellation and cached-file revalidation. These tests do not claim real playback or installation.
- Android `UpdateScreensTest`: native notification icons, existing Queue/chrome preservation, Updates routing/back behavior, preferences, pending/error/progress states, pause gate, permission explanation and consent invalidation if playback resumes.
- Android `UpdateInstallerTest`: real package parsing of the installed signed APK and policy-seam rejection tests, fixed-path/size/hash/current-signer checks, rechecks before preparing the installer, permission intent and read-only FileProvider grant.
- Android `UpdateFlowTest`: isolated local release/download fixture, app state/persistence and real higher-version debug APK preflight when the explicit fixture is supplied. No test installs onto a personal device.

Native JVM/lint/APK builds and the complete instrumentation suite remain the normal regression gates. An actual Android installer upgrade is a separate device probe: use two fixture APKs with the same disposable/debug signer and increasing version codes on the explicitly owned emulator, preserve/read back a fixture account and paused session, and never substitute a successful intent-generation test for an installation result.
