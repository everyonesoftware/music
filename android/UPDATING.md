# In-app updates

Music checks the public `everyonesoftware/music` GitHub release list for the highest numeric published stable `v0.1.<count>` Android release, independent of GitHub's Latest designation and publication order. Windows-only/no-APK releases are skipped. This is separate from Navidrome: no music-server URL, username, password or token enters the updater's HTTP client.

## Phone flow

- When a newer version is known, a download icon with a notification dot appears on the Library header and beside Queue on Now Playing. Both open **Settings → Updates**. Checking, navigation and downloading do not pause or replace the listening session.
- Updates is also available from the existing connection Settings screen. It shows **Current Version**, manual **Check now**, and a compact offered-version panel with collapsed **What’s new**. Expanding shows Improvements / Fixes and nested original Commit details; empty groups are omitted. No release-body notes or automatic-check toggle are displayed.
- Music checks for updates in the background once per app launch, without waiting for a music account or restoring playback. Configuration changes and foreground returns don't start another launch check. After a failed check, returning to the foreground or regaining network connectivity can retry subject to backoff. Server rate-limit deadlines also apply to launch checks. **Check now** remains available for explicit retries and shares any ongoing check. Checks never download or install an update.
- Downloads require a tap. Progress, Cancel and Retry are explicit. Navigation away does not cancel a download. Downloads run only while the app process exists; no permanent background scheduler or update notification permission is added. An interrupted process may require retrying the download.
- A downloaded file must pass size, SHA-256, package ID, exact expected version and current-signer preflight checks before **Install update** becomes available. A cached APK is reverified after process restart before being offered for installation.
- Installation is a separate confirmation. If music is playing, pause it explicitly first. The live controller is checked again after file verification and immediately before opening Android's installer, including buffering/play-when-ready state. Queue/index/position/repeat are checkpointed before handoff; the existing cold-start behavior restores paused.
- If Android does not yet allow Music to install APKs, the confirmed action opens **Allow from this source** for Music. Returning only refreshes permission state; the user must tap Install and confirm again. This is distinct from Android Auto's developer-mode Unknown sources setting.
- Android performs the final signature and install-policy validation and asks for its own confirmation. Cancelling that prompt does not install anything. There are no forced or silent updates. Backgrounding Music during preparation cancels the pending installation request rather than unexpectedly opening an installer later.

## Trust and limits

The client is isolated and unauthenticated, disables cookies/cache/authenticators, and requests only the fixed HTTPS GitHub release-list path with `per_page=100&page=N`. It scans sequentially through at most 10 pages before selecting a candidate; a short page establishes exhaustion unless it advertises another page. A full tenth page, failed/truncated inventory, malformed JSON or HTTP error is an error, never a successful up-to-date result or permission to use a partial inventory. API redirects cannot change the requested page, query or repository.

Drafts, prereleases and unpublished entries are ignored. Stable Android candidates must have canonical positive numeric tags (no leading zeroes, maximum count 2,100,000,000); duplicate Android versions fail closed. The highest candidate newer than the installed build must have exactly one APK, named `Music-0.1.<count>.apk`, and exactly one `release.json`. Invalid/ambiguous Android candidates, missing manifests or inconsistent metadata fail closed instead of silently falling back to an older release. Equal/older versions are never offered.

The client rejects inconsistent versions/tags/filenames, malformed required manifest metadata and assets outside the exact repository release path. Each release-list page and offered manifest is limited to 1 MiB; APKs are limited to 150 MiB. Optional changelog errors do not reject an otherwise valid APK update. Downloads follow only a bounded number of HTTPS redirects to GitHub's allowlisted asset hosts. Transport/parser failures expose a fixed safe message, not request URLs or exception causes. Cancellation closes the actual network call and removes the partial file.

The expected release files already produced by the workflow are:

- `Music-0.1.<count>.apk`
- `release.json`, containing `count`, `version`, `tag`, `sha`, `apk`, `sha256` and `bytes`, with an additive optional `changelog` object (below)

The app compares the APK's current signer certificate(s) with its own installed signer(s), not arbitrary signing history. Signing-key rotation is intentionally not supported in this first updater. PackageManager certificate metadata and a matching release checksum are preflight checks, not an independent proof of the APK's full cryptographic validity; Android's installer remains the final signature-verification authority.

The APK is stored in app-private `cache/updates/update.apk`. A non-exported FileProvider exposes only the updates cache subtree, and the installer receives only a temporary read URI grant. No broad filesystem sharing or credential storage changes are made. Cached metadata/preferences are app-private and existing backup exclusions remain in place. Checks contact GitHub and therefore expose ordinary network information such as the requesting IP and updater User-Agent to GitHub. A private GitHub repository is not supported; the app does not store or request a GitHub token.

The application ID remains `com.everyonesoftware.audio`; existing signing keys and data identifiers must remain unchanged. A debug APK cannot update an installation signed with the release key. A release-signed installation should use the next release-signed APK, not uninstall merely to try a debug build. A future Play-distributed variant would need its own distribution-policy-compatible update path.

## Precomputed changelog

The same offered `release.json` fetched during discovery carries the history. There are **no additional history requests**: no installed-manifest fetch, tag lookup, compare request, commit-file lookup or legacy reconstruction fallback. The offered parsed JSON is cached with the package identity; the installed-to-offered range is prepared locally before publishing the offer. Expanding/collapsing either disclosure performs no loading or network work.

```json
{
  "schema": 1,
  "platform": "android",
  "head": { "version": "0.1.42", "sha": "<40 lowercase hexadecimal characters>" },
  "releases": [
    { "version": "0.1.41", "sha": "<baseline SHA>", "commits": [] },
    { "version": "0.1.42", "sha": "<head SHA>", "commits": [
      { "sha": "<head SHA>", "subject": "fix(android): Original first line", "kind": "fix", "text": "Original first line" }
    ] }
  ]
}
```

The example placeholders are explanatory, not valid SHAs. Publication tooling must supply complete original first-line subjects (including an empty original first line) and all intervening segments, oldest/topological first. Original subjects reject LF only and are preserved verbatim, including CR, NUL, empty subjects and long originals within the overall manifest limit. Summary bullets must be nonblank and contain no LF, CR or NUL; their length is bounded by the overall manifest byte limit, not a separate character cap. The first entry is the verified published baseline with no commits. Every later segment ends at its entry SHA and has exactly `numeric(version) - numeric(previousVersion)` commits: the Git reachable-commit counter makes missing nonfinal commits detectable without a network comparison. Versions must be canonical, strictly increasing numeric `0.1.N` values. The final entry, `head` and offered version/source SHA must match. Full commit SHAs are unique across all segments (and cannot repeat the baseline SHA). `kind` is `improvement`, `fix` or explicit `null`; `text` is a nonblank single-line cleaned bullet for a nonnull kind, otherwise explicit `null`. All originals remain in Commit details, including docs/CI/other-platform changes omitted from summaries by the producer; Android does not reclassify them.

Android validates the entire history, not just selected segments, with at most 4,096 publications, 10,000 total commits and 1 MiB serialized history (within the overall 1 MiB manifest). It requires the installed version’s exact entry, then concatenates all later segments; missing, malformed, unsupported or out-of-bounds history is **Unavailable**, not “0 changes.” Zero is shown only for a validated range whose commits all have null kinds. History failure never prevents explicit download/verification/install.

App-private restoration persists the embedded JSON with release metadata (bounded to 1 MiB plus 4 KiB of cache-envelope allowance), then revalidates/reselects the range immediately on recreation without network requests. Legacy stored `notes` are ignored. Old manifests without history remain downloadable with unavailable history; **Check now** refreshes release metadata normally. There is no separate history retry or loading spinner.

## Verification

- JVM `UpdateClientTest`: real MockWebServer requests using a loopback-only transport seam, mixed-platform numeric selection, pagination/exhaustion/bounds, fail-closed ambiguous candidates and incomplete inventories, stable release/manifest validation, URL and redirect policy, bounded reads, size/checksum failure, safe errors and cancellation.
- JVM `UpdateChangelogClientTest` / `UpdateChangelogCoordinatorTest`: skipped-publication range selection, original subjects, null summary kinds, malformed identity/structure/bounds, immediate local preparation/restoration and exact discovery-only request counts.
- JVM `UpdateCoordinatorTest`: launch discovery, manual retries, recovery backoff and server cooldown, coalescing, explicit downloads, verification failure/retry, cancellation and cached-file revalidation. These tests do not claim real playback or installation.
- Android `UpdateScreensTest`: native notification icons, existing Queue/chrome preservation, Updates routing/back behavior, preferences, pending/error/progress states, pause gate, permission explanation and consent invalidation if playback resumes.
- Android `UpdateInstallerTest`: real package parsing of the installed signed APK and policy-seam rejection tests, fixed-path/size/hash/current-signer checks, rechecks before preparing the installer, permission intent and read-only FileProvider grant.
- Android `UpdateFlowTest`: isolated local release/download fixture, app state/persistence and real higher-version debug APK preflight when the explicit fixture is supplied. No test installs onto a personal device.

Native JVM/lint/APK builds and the complete instrumentation suite remain the normal regression gates. An actual Android installer upgrade is a separate device probe: use two fixture APKs with the same disposable/debug signer and increasing version codes on the explicitly owned emulator, preserve/read back a fixture account and paused session, and never substitute a successful intent-generation test for an installation result.
