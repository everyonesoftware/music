# Selective component builds

Every main push starts one **Music CI** workflow (`.github/workflows/ci.yml`) with exactly five real jobs: **validate → browser-prototypes + android + windows → release**. After validation, the browser checks and two native jobs run in parallel; native jobs contain their own platform tests and packaging. There is no published web/container output or nested multi-job workflow. Pull requests use validation and changed-platform tests without signing or publication. Preserved browser-study checks run in `browser-prototypes` only when affected; that job installs the locked dependencies and pinned Chromium and runs the full `npm test` suite without delaying native builds. Scheduled/manual maintenance and isolated experimental trimming retain separate entry points, with no completion-triggered fan-out.

Native SDK setup, native compilation, signing, installers and publication run only for selected affected components. Lightweight pipeline/release-tool contracts always run. Validation's native compatibility checks are also gated by Windows selection; Chromium installation and browser tests no longer run in validation. A successful run with skipped platform jobs is not an app update. The final release job waits for the browser and both native jobs, publishes Android before Windows, then performs historical attachment recovery and download refresh serially. Browser checks must pass when selected; unchanged skipped browser checks permit release. Its always/not-cancelled dependency handling permits maintenance when unchanged native platforms skip or a native platform fails; failed builds still fail the overall workflow.

## Ownership rules

| Changed inputs | Android | Windows | Web studies |
| --- | --- | --- | --- |
| `android/**` code, resources, Gradle configuration | Build | Skip | Skip |
| Android launcher foreground artwork/colors used by the Windows icon generator | Build | Build | Skip |
| `apps/windows/**`, Windows build/installer scripts and release tests | Skip | Build | Skip |
| `mockups/**`, prototype builders/tests, npm manifest/lockfile | Skip | Skip | Build/test |
| A platform's workflow | That platform only | That platform only | That platform only |
| Root/platform README and release documentation, `docs/**` | Skip | Skip | Skip |
| Download-table generator/tests/workflow | Skip | Skip | Skip |
| Shared/unknown inputs or selective-build detector/tests | Build | Build | Build/test |

The rules live in `scripts/selective-builds.mjs`; unknown paths deliberately affect every component until explicitly classified. Deletions and renames retain both affected paths through `git diff --no-renames`. There is no released web player to publish: browser studies are checked in the separate `browser-prototypes` job, gated by validation's `web` output.

## Last successful publication, not last push

For Android, discovery selects the highest numeric published stable `v0.1.N` release containing its APK, checksum and `release.json`. Windows accepts stable main `v0.1.N` releases containing the complete installer/feed/package/checksum/manifest inventory, with legacy `windows-v0.1.N` previews as fallback. Stable main wins an equal-version tie. EXE-only, draft and incomplete releases cannot advance the Windows baseline. Both platforms check manifest version/source identity, uploaded asset sizes and the actual release tag's dereferenced SHA. Malformed completed inventories and API/auth/network failures fail closed.

Each component compares its own last published source tree against HEAD. If an Android build fails, a later docs-only push still sees the unpublished Android changes and retries Android rather than losing those changes. The detector never writes a release marker; only the publishers' existing verified outputs establish native baselines. A source already included in a newer publication is skipped on stale reruns, and divergent or shallow histories are rejected.

The web studies compare against the last successful main `ci.yml` push run, falling back to legacy `web-build.yml` push/manual history during migration, not pull-request checks or failed runs. Unified manual runs are deliberately excluded because targeted native/manual maintenance runs can succeed without browser validation. Successful main pushes either validate web or prove it unchanged against that prior baseline. Their first workflow deployment runs a bootstrap validation because no prior successful check exists. There is no invented web release/version.

## Releases and existing updaters

- **Android only:** publish a new APK release; do not build or attach a new Windows installer. Both download tables continue linking to the existing Windows installer.
- **Windows only:** create one stable **Music 0.1.N** release and publish the verified installer/checksum and complete Velopack update inventory there. Do not create a `windows-v` release or rebuild/copy an APK. The helper uses `latest=false` to preserve older Android clients, verifies exact source/tag and leaves existing releases untouched.
- **Both:** Android creates the shared Music release first. Windows adds its installer/checksum and complete update inventory to that same stable release, preserving the APK and unrelated metadata/assets.
- **Neither:** no native build, new native version, or native release.

Updated clients discover updates by platform assets, not GitHub's Latest badge. Android pages published stable `v0.1.N` releases and selects the highest numeric version containing its APK and `release.json`; Windows pages releases for its `releases.win-x64.json` feed and matching full update package, accepting stable `v0.1.N` releases and legacy `windows-v0.1.N` feeds. An installer EXE alone is not a Velopack in-app update. Neither client offers an equal or older version. Missing other-platform assets do not block discovery, and bounded/truncated or failed inventory requests are errors rather than evidence of being up to date.

**Legacy-client bridge:** retain published `windows-v0.1.43` and earlier releases. The Windows client at source `12efea1` (version 0.1.43) accepts stable main feeds as well as legacy feeds, so older legacy-only clients can first discover/install that retained bridge and then discover newer main releases. Future publishing creates no separate Windows preview. This is a source/feed compatibility path, not proof of a native installed-app upgrade on every old version. Older Android clients still require Latest to contain a matching APK, so Windows-only main releases remain `latest=false`; consolidation does not copy/relabel Android or promote a Windows-only release to Latest.

The existing package validator's `windows-release.json` retains legacy internal `tag: windows-v0.1.N` and `signing: unsigned-preview` fields without creating that GitHub tag/release. The stable publisher validates those original bytes before upload, and discovery verifies the actual main `v0.1.N` tag separately. These compatibility fields do not claim code signing or designate the GitHub release as a prerelease.

Versions remain the existing full-history `0.1.<commit-count>` scheme. Components advance only when actually published, so gaps and different current versions across platforms are expected. Installers retain their original source/version identity; no unchanged binary is relabeled as a newer version.

## Independent Windows publication recovery

For historical `windows-v` previews, complete packaging is not evidence that attachment to the corresponding main release succeeded. The final release job invokes recovery independently of native affectedness, including docs-only pushes. `.github/workflows/windows-publication-recovery.yml` also retains manual dispatch and a six-hour schedule. Recovery creates no new Windows preview and does not rebuild artifacts. Partial future stable-main uploads remain ineligible as build baselines and can be retried using the original retained Actions artifact and source commit.

Recovery checks every known preview's exact published source/tag/state and full inventory. Already complete same-source main installer/checksum pairs need metadata checks only. For missing attachment, it creates a detached full-history worktree at the **original preview SHA**, downloads that preview's original seven-file inventory, checks installer source/hash with the existing Node attachment tooling, and runs the existing `windows-release.py validate --root <original-worktree>` with trusted current tooling. It never builds, repackages, re-manifests, relabels at newer HEAD, replaces assets, or promotes Latest. The existing create/attach helpers then create a missing Windows-only main release with `latest=false` where appropriate and attach/read back the original verified bytes/checksum. A required but missing Android publication fails visibly for Android recovery, not an invented Windows-only replacement.

The final release job and both independent maintenance jobs share one job-level `music-release-publication` concurrency group with `cancel-in-progress: false` and `queue: max`. Builds do not hold this lock. Maintenance workflows have no workflow-level lock to deadlock a nested/waiting job. Paginated repository-wide Actions discovery conservatively defers any other active main run, including legacy reruns, without looking up deleted workflow filenames. Only the exact owning `GITHUB_RUN_ID` is excluded after native dependencies finish. Later push/manual/scheduled runs retry deferred work. Older actionlint versions may reject the documented `queue` key; ignore only that exact unsupported-key diagnostic, not unrelated lint errors.

Conflicting/draft releases, wrong/divergent source or tags, incomplete preview inventories, tampered downloads, and changed remote asset metadata fail visibly without clobbering. Missing installer/checksum assets are repairable; conflicting existing bytes are not. Completeness discovery is metadata-only for an already complete main pair, not an audit of subsequent same-size remote tampering. Keep manual/API publication writers out of these lock-protected workflows. GitHub may also refuse release writes targeting old workflow-file revisions with `GITHUB_TOKEN`; such permission failures remain visible and require operator intervention, never retargeting the source.

## Manual recovery

Manually dispatch **Music CI** and choose `component` to force the selected platform even if its inputs are unchanged:

- `all`: force all platform checks and native publication on main.
- `android`: force Android build/sign/publication on main.
- `windows`: force Windows checks, packaging and publication on main.
- `maintenance`: run recovery and download refresh without rebuilding.

Set `trimmed_experiment=true` with `component=windows` (or `all`) for build-only experimental Windows checks; Android, Windows packaging/publication, recovery and refresh are disabled for this mode. The independent experimental native trimming validation remains separate from the main push pipeline. Maintenance workflows also retain their individual manual dispatches.

A forced Windows-only run does not silently bypass unpublished Android changes. If raw affectedness requires Android publication but it was skipped/failed, a ready Windows package produces an actionable release failure directing the operator to fix Android and rerun with `component=all`. Windows packaging can complete and retain its artifact, but the run must not report green while silently dropping the requested publication.

Published version/source collisions remain errors, not permission to overwrite binaries. Rerun a failed workflow at its original source when repairing publication. To intentionally publish different bytes, or add an APK to an already published Windows-only version, use a new commit/version rather than mutating the existing release. A missing matching Android release after an Android failure is not repaired by Windows inventing an APK.

## Local verification

Read-only native decisions for committed HEAD:

```sh
node scripts/selective-builds.mjs --repository everyonesoftware/music --component android
node scripts/selective-builds.mjs --repository everyonesoftware/music --component windows
node scripts/selective-builds.mjs --repository everyonesoftware/music --component web
```

These commands deliberately ignore uncommitted edits, matching CI's committed source model. Unit tests construct actual disposable Git histories for docs-only, single-platform, shared-input, deletion, failed-publication catch-up, force and stale/diverged-source cases. All GitHub writes are mocked in publication tests.

```sh
npm run test:release-selection
npm run test:downloads
npm test
```

Native installation/signing gates remain intact. New selection and stable Windows publication orchestration use Node; existing Python packaging/integrity validators are unchanged. Workflow syntax must pass `actionlint`. Local automation checks do not establish hosted build or native installer success; publishing requires an authorized commit/push and monitoring the resulting run.
