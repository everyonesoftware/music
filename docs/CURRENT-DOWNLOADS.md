# Latest Music downloads and updates

The README has a permanent Downloads link to GitHub's Latest release. The release workflow does not edit or commit the README. Existing release descriptions and assets are not rewritten as part of the updater migration.

## Release index

Each new stable Music release includes `latest-versions.json`. The schema is a UTF-8 JSON object (at most 16 KiB) with numeric `schema: 1` and `android` and `windows` fields containing canonical stable Music tags, for example:

```json
{"schema":1,"android":"v0.1.123","windows":"v0.1.125"}
```

The two tags are resolved independently from verified published platform inventories. An unchanged platform keeps its original release, version and assets. Windows requires the Velopack update inventory, not just an installer. See [Selective builds](SELECTIVE-BUILDS.md) and [Embedded release changelog](RELEASE-CHANGELOG.md).

Before promoting the release to Latest, the publisher verifies that the index retrieved from its anonymous tagged download URL has the exact staged bytes. After promotion it verifies the release through the API and reads those same bytes anonymously through the clients' Latest download URL. A failed post-promotion check fails the job; it does not roll back Latest or replace the index. The index is a snapshot; old releases do not need new copies or index refreshes. Only the latest eligible Music release should advance GitHub's Latest pointer.

If index upload, promotion, or public readback fails after all required platforms have published, rerun the failed release job on the original Actions run while its artifacts are still retained. Rerunning all jobs can skip native builds because their assets already exist. If necessary, force `component=all` while the original source is still the tip of `main`, or publish a newer eligible release if the original artifacts are no longer available.

### Recover an interrupted upload

A GitHub upload `502 Bad Gateway` can leave a [zero-byte `starter` asset](https://docs.github.com/en/rest/releases/assets#upload-a-release-asset) that blocks a same-name upload. The publishers never delete or clobber assets automatically. With release writers stopped, an authorized operator can recover only that specific placeholder:

1. Identify the release by the failed run's exact `v0.1.N` tag and source SHA, not by Latest. For the Android publisher, locate the matching **draft** in the authenticated release list. Record its release ID.
2. List all assets of that release with `gh api --paginate repos/everyonesoftware/music/releases/RELEASE_ID/assets`. Confirm exactly one asset has the failed filename (`latest-versions.json` for the index, or the specific failed APK, APK checksum, or `release.json` in the Android draft), `state: starter`, and `size: 0`. Record its asset ID and recheck the metadata immediately before removal. If the asset is absent, retry without removing anything. If there are duplicates, the state is `uploaded`, the size is nonzero, or the release/source does not match, stop and investigate; do not delete anything.
3. Delete only that confirmed asset by ID (`gh api --method DELETE repos/everyonesoftware/music/releases/assets/ASSET_ID`). List the same release's assets again to confirm that only the placeholder is gone. Rerun the failed release job at its original source using the retained artifacts. Never delete an uploaded asset, delete the draft/release, change Latest manually, or use `--clobber` for this recovery.

## Updaters and rollout

Android and Windows check `https://github.com/everyonesoftware/music/releases/latest/download/latest-versions.json`, then use their platform's tagged release. The native apps do not scan the release list and retain their existing verification and explicit installation flows. The change is intentionally not backward-compatible: on an old client that cannot find the first index-enabled build, install that build manually over the existing app without uninstalling. Old assets remain available.

The historical download-table script is not called by any workflow. Do not run its old `--publish` mode as part of a release; that mode can restore the generated README table.
