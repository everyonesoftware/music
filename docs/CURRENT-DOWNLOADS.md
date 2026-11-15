# Current platform downloads

The root README, the newest stable GitHub release, and the Android-compatible Latest release have the same generated **Current downloads** table. Links point directly to version-specific release assets; installers are not rebuilt, reuploaded, or copied to maintain these tables. Existing README prose and release notes outside the managed HTML-comment markers are preserved under the serialized-writer/manual-editing policy below; release-body preservation is not atomic against concurrent human edits. Other older release descriptions remain historical snapshots.

## Source of truth

`scripts/current-downloads.mjs` discovers the existing published GitHub release assets. It selects Windows and Android independently, in descending numeric `0.1.N` version order (the project's existing commit-count version scheme). A candidate requires both a nonempty uploaded installer and its uploaded SHA-256 sidecar (or the Windows feed preview's real `SHA256SUMS` inventory), at their expected repository/tag URLs. It paginates releases and asset inventories rather than relying on GitHub's embedded asset list.

Stable `v0.1.N` releases can supply either platform. Explicit `windows-v0.1.N` previews can supply Windows; other prereleases and all drafts are excluded. Equal-version shared releases are preferred over Windows-only previews. This uses the current release naming/signing conventions; changing version formats or shipping signed Windows releases requires updating discovery/status labels and tests. The table checks published metadata and availability, not binary integrity; the existing publishers perform artifact byte/hash verification.

There is currently no released Music web player. Browser design studies are not advertised as a production web app.

## Preview without changing GitHub

```sh
node scripts/current-downloads.mjs --repository everyonesoftware/music --output-directory "$TMPDIR/music-download-preview"
```

The output directory contains a complete `README.md`, `release-notes.md` for the newest stable release, and the shared `downloads.json` inventory. Preview mode uses the local README text and makes only read-only GitHub calls. It requires an authenticated `gh` CLI. This inventory is for download discovery; it does not replace the native apps' existing in-app update feeds.

## Automatic refresh

`.github/workflows/current-downloads.yml` runs after either platform's release workflow or independent Windows recovery completes, on manual dispatch, and every six hours as a repair path. It executes trusted default-branch code, not artifacts/code from the triggering run. It defers if either platform publisher is still active on main: Windows attachment verifies immutable release metadata, so editing its release body mid-publication would break that gate. The other completion event or scheduled repair refreshes the tables later. Failed builds do not erase previously published installers.

Publication edits the managed tables in the newest stable release and the Android-compatible Latest release (one edit when they are the same), then updates the default branch's README with an optimistic blob-SHA check. Windows-only main releases remain `latest=false` so existing Android updaters still find their matching APK. It preserves all other text, performs no asset writes or release/latest-policy changes, reads every changed target back, and is idempotent. A concurrent README edit fails the conditional update rather than being overwritten; a rerun safely repairs partial completion. Branch protection must permit the workflow's README update, otherwise the job fails visibly and needs repository-policy configuration.

The workflow uses the repository's `GITHUB_TOKEN`, not a personal token. Its README-only commits therefore do not recursively trigger push builds. See GitHub's [GITHUB_TOKEN documentation](https://docs.github.com/en/actions/concepts/security/github_token), [workflow_run documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run), and [Contents API documentation](https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents).

For an intentional live refresh (writes release notes and creates a README commit on the default branch):

```sh
node scripts/current-downloads.mjs --repository everyonesoftware/music --publish
```

Do not run live publication with a personal token merely to preview the layout: unlike `GITHUB_TOKEN`, that README commit can trigger platform builds.

## Release-description editing policy (not atomic)

Automated refreshes must use the serialized `music-current-downloads` workflow; do not run competing CLI `--publish` processes or other release-description bots. Refresh defers when a native publisher or independent Windows recovery is visibly active. Completion events and the schedule retry deferred work.

**Manual release-description editing must not overlap a refresh.** Wait for the refresh to finish (or disable/pause the workflow before editing). The fresh pre-PATCH GET rejects observable body/tag/state changes, and post-PATCH GET verifies the result. This is **best-effort preservation for human edits, not an atomic compare-and-swap**: a human edit between GET and PATCH can be overwritten without detection. The README uses the Contents API's blob-SHA conditional update; release descriptions do not have that protection.

The official [conditional-request guidance](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests) explicitly says conditional unsafe methods (including PATCH) are unsupported unless the endpoint documents an exception. [Update a release](https://docs.github.com/en/rest/releases/releases#update-a-release) documents no such exception or body revision/CAS parameter. We do not infer supported `If-Match` semantics from GET ETags and do not send an unproven conditional header. Do not claim atomic preservation against arbitrary concurrent release-description writers.

## Scope

Download discovery is paired with [selective component builds](SELECTIVE-BUILDS.md). Components retain their existing commit-count version scheme and native update feeds; unchanged components are not rebuilt or copied into a new release.

## Tests

```sh
node --test tests/current-downloads.test.mjs
```
