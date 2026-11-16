# Selective component builds

Every main push starts one **Music CI** workflow (`.github/workflows/ci.yml`), with platform checks, builds, publication, Windows attachment recovery and download refresh as dependent jobs in that same run. Pull requests use the same entry point for web and Windows checks without signing or publication. The platform YAML files are reusable `workflow_call` implementations, not separate push-triggered runs. Scheduled/manual maintenance and the isolated experimental trimming branch retain their own entry points; no `workflow_run` completion events fan out additional runs.

Native SDK setup, tests, compilation, signing, installers and publication run only for affected components. Lightweight pipeline contracts always run. A successful workflow containing skipped platform build jobs is not an app update. Windows packaging waits for Windows checks and for this run's Android publication to finish (or skip) before attaching its installer to the shared release. Recovery and refresh use explicit always/not-cancelled dependencies so unchanged platforms and failed builds do not suppress maintenance.

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

The rules live in `scripts/selective-builds.mjs`; unknown paths deliberately affect every component until explicitly classified. Deletions and renames retain both affected paths through `git diff --no-renames`. There is no released web player to publish: the web workflow builds and validates the preserved browser studies only.

## Last successful publication, not last push

For Android, discovery selects the highest numeric published stable `v0.1.N` release containing its APK, checksum and `release.json`. For Windows, it selects the highest numeric published `windows-v0.1.N` preview with the full installer/feed/package/checksum/manifest inventory. It checks manifest version/source identity, uploaded asset sizes, and the dereferenced tag SHA. Drafts and incomplete platform inventories do not advance the baseline. A malformed completed inventory fails closed, as do API/auth/network errors.

Each component compares its own last published source tree against HEAD. If an Android build fails, a later docs-only push still sees the unpublished Android changes and retries Android rather than losing those changes. The detector never writes a release marker; only the publishers' existing verified outputs establish native baselines. A source already included in a newer publication is skipped on stale reruns, and divergent or shallow histories are rejected.

The web studies compare against the last successful main `ci.yml` push run, falling back to legacy `web-build.yml` push/manual history during migration, not pull-request checks or failed runs. Unified manual runs are deliberately excluded because targeted native/manual maintenance runs can succeed without browser validation. Successful main pushes either validate web or prove it unchanged against that prior baseline. Their first workflow deployment runs a bootstrap validation because no prior successful check exists. There is no invented web release/version.

## Releases and existing updaters

- **Android only:** publish a new APK release; do not build or attach a new Windows installer. Both download tables continue linking to the existing Windows installer.
- **Windows only:** publish the verified Windows feed/preview and create a matching main **Music 0.1.N** release for the installer/checksum. Do not rebuild or copy an APK. The helper creates this release with `latest=false`, verifies its exact source/tag, and leaves existing releases untouched.
- **Both:** Android creates the shared Music release as before. Windows waits for that exact source/tag, then attaches only its verified installer/checksum.
- **Neither:** no native build, new native version, or native release.

Updated clients discover updates by platform assets, not GitHub's Latest badge. Android pages published stable `v0.1.N` releases and selects the highest numeric version containing its APK and `release.json`; Windows pages releases for its `releases.win-x64.json` feed and matching full update package, accepting stable `v0.1.N` releases and legacy `windows-v0.1.N` feeds. An installer EXE alone is not a Velopack in-app update. Neither client offers an equal or older version. Missing other-platform assets do not block discovery, and bounded/truncated or failed inventory requests are errors rather than evidence of being up to date.

**Client support is not a release-policy migration.** Already-installed older Android apps still use GitHub's `/releases/latest` and require that tag's matching APK; older Windows apps still require `windows-v` feeds. The current publishers therefore retain the legacy split feed and `latest=false` Windows-only policy above. The download refresh continues updating both the newest stable release description and the Android-compatible Latest description, plus the README. Consolidating future publication into one Music release and promoting every newest release to Latest requires a separate rollout for those older installed clients; these discovery changes do not delete old releases, move Latest, or change automatic publication.

Versions remain the existing full-history `0.1.<commit-count>` scheme. Components advance only when actually published, so gaps and different current versions across platforms are expected. Installers retain their original source/version identity; no unchanged binary is relabeled as a newer version.

## Independent Windows publication recovery

The complete `windows-v` preview is the **packaging** baseline, not evidence that attachment to `v` succeeded. `.github/workflows/windows-publication-recovery.yml` is called after native jobs inside Music CI on main pushes (including docs-only), and remains independently available through manual dispatch and a six-hour schedule. A failed main-release creation or attachment therefore does not require a native rebuild. Rerunning all jobs at the original SHA can skip packaging safely and still execute recovery.

Recovery checks every known preview's exact published source/tag/state and full inventory. Already complete same-source main installer/checksum pairs need metadata checks only. For missing attachment, it creates a detached full-history worktree at the **original preview SHA**, downloads that preview's original seven-file inventory, checks installer source/hash with the existing Node attachment tooling, and runs the existing `windows-release.py validate --root <original-worktree>` with trusted current tooling. It never builds, repackages, re-manifests, relabels at newer HEAD, replaces assets, or promotes Latest. The existing create/attach helpers then create a missing Windows-only main release with `latest=false` where appropriate and attach/read back the original verified bytes/checksum. A required but missing Android publication fails visibly for Android recovery, not an invented Windows-only replacement.

The recovery workflow holds the original Windows publisher's concurrency group at workflow level and Android's publication group at job level. It also defers if another Music CI run or legacy native workflow is visibly active (including builds). The caller's own run ID is excluded only because its native jobs have already finished; independent invocations still detect active publishers. Later push/manual/scheduled retries repair deferred work. Both publishers and recovery use GitHub's documented [`queue: max`](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency), rather than letting recovery replace pending publication jobs. GitHub bounds this queue at 100 pending entries. Older actionlint releases may reject the documented `queue` key; treat only that specific diagnostic as a tool-version limitation, not permission to ignore other lint errors.

Conflicting/draft releases, wrong/divergent source or tags, incomplete preview inventories, tampered downloads, and changed remote asset metadata fail visibly without clobbering. Missing installer/checksum assets are repairable; conflicting existing bytes are not. Completeness discovery is metadata-only for an already complete main pair, not an audit of subsequent same-size remote tampering. Keep manual/API publication writers out of these lock-protected workflows. GitHub may also refuse release writes targeting old workflow-file revisions with `GITHUB_TOKEN`; such permission failures remain visible and require operator intervention, never retargeting the source.

## Manual recovery

Manually dispatch **Music CI** and choose `component` to force the selected platform even if its inputs are unchanged:

- `all`: force all platform checks and native publication on main.
- `android`: force Android build/sign/publication on main.
- `windows`: force Windows checks, packaging and publication on main.
- `web`: force browser build/tests, without publishing a web app.
- `maintenance`: run recovery and download refresh without rebuilding.

Set `trimmed_experiment=true` with `component=windows` (or `all`) for build-only experimental Windows checks; Android, Windows packaging/publication, recovery and refresh are disabled for this mode. The independent experimental native trimming validation remains separate from the main push pipeline. Maintenance workflows also retain their individual manual dispatches.

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

The release publishers and native installation/signing gates are unchanged. New orchestration is Node, not new Python code. Workflow syntax must also pass `actionlint`. Local automation checks are not evidence that GitHub-hosted builds or native installers have run; publishing requires an authorized commit/push and monitoring the resulting runs.
