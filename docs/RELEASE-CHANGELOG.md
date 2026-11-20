# Embedded release changelog

Native updater manifests (`release.json` for Android, `windows-release.json` for Windows) gain one additive `changelog` object. Existing packaging, source identity, hashes, signing checks, platform discovery, Latest policy and immutable historical assets remain unchanged. Clients read this embedded object; they do not request GitHub compare history or interpret release-description/download-table markdown as change history.

## Schema 1

```ts
type Revision = { version: string /* canonical 0.1.N */, sha: string /* 40 lowercase hex */ };
type Commit = {
  sha: string;
  subject: string; // original first message line, including mechanical prefixes
  kind: 'improvement' | 'fix' | null;
  text: string | null; // cleaned original description, never invented claims
};
type Changelog = {
  schema: 1;
  platform: 'android' | 'windows';
  head: Revision;
  releases: (Revision & { commits: Commit[] })[];
};
```

Releases increase numerically, not lexically. The earliest verified actual platform publication is a baseline with `commits: []`; nothing before it is claimed. Every later entry contains **all** Git commits since the preceding publication, topological oldest-first, including side branches and merges. Its final commit is its source SHA. Clients require its commit count to equal the difference between consecutive version counts, detecting omissions even when the final commit remains. No commit SHA appears twice across segments. The final release equals the offered manifest's version and source. Skipped Android or Windows versions do not become fictitious platform publications. A first-ever platform publication establishes only its new baseline.

Clients find the exact installed version/source publication, concatenate every later segment through the offered head, retain original subjects/SHAs as details and show only non-null kind/text pairs in Improvements and Fixes. Missing installed baseline, malformed/incomplete history, or a mismatched offered head means **changelog unavailable**, not zero changes; independently verified updates remain downloadable. A matching historical baseline with no subsequent relevant commits is legitimately empty.

## Evidence and production

`scripts/release-changelog.mjs` discovers all published platform releases using the same verifier as `scripts/selective-builds.mjs`, not arbitrary local tags or release bodies. Release and asset APIs are paginated. Android requires its uploaded APK, checksum and manifest. Windows requires its complete seven-file Velopack inventory (and installer checksum for stable-main publications). Drafts/incomplete platform inventories do not establish publications. Completed but malformed inventories fail closed.

For every candidate, verification checks version/count/manifest/source, uploaded asset sizes and hashes recorded by the manifest, and the actual GitHub release tag, recursively dereferencing annotated tags. Stable and legacy Windows same-version publications must agree on source; stable wins the tie. These discovery checks establish published provenance, not a fresh download/hash audit of every historical binary. Artifact publishers retain that byte-level verification boundary.

Each invocation caches repeated API reads; immutable manifest-asset IDs also use `gh api --cache 24h`. Mutable release inventories and tag refs are never cached by this producer. This avoids repeatedly downloading old manifest bodies without trusting a cached mutable tag. There is no client compare call, GitHub compare pagination/truncation, checked-in historical mapping, or release-body parsing.

Complete local, non-shallow Git history supplies version commit counts, ancestry, raw first message lines and per-commit changed paths. Merge parent diffs are included; rename detection is disabled so both old and new names participate. Docs, workflow-only and clearly other-platform/browser-only commits remain in details with both summary fields null. Shared and unknown inputs are retained conservatively. Mechanical `[verified]` and conventional type/scope prefixes are removed only from summary text; CR (`\r`) and NUL (`\0`) characters in that cleaned text are replaced with spaces **before trimming**, so relevant changes remain valid native-client bullets rather than being skipped or invalidating cumulative history. Original `subject` strings retain the exact raw first message line, including CR/NUL, through JSON serialization. Conventional `fix` or clear fix verbs select Fixes, other retained changes select Improvements. Empty messages never produce fabricated text.

Missing objects, divergent/conflicting sources, invalid versions/counts, API failures, corrupt payloads and size overflow abort publication rather than produce partial notes.

## Publication ordering and Python compatibility

The existing five-job CI topology and `music-release-publication` lock remain unchanged:

1. Native jobs package and retain their **original** manifests and artifacts using the unchanged Python tools.
2. The final release job invokes the Node producer on `dist/android` before `android-release.py publish`. Android's existing validator accepts the additive field, verifies unchanged APK/checksum bytes, and compares/readbacks the enriched manifest during draft upload before publication.
3. The Windows final publisher receives `--changelog true`. It runs the unchanged strict Python validator on the original directory and original private snapshot. It then generates/verifies the cumulative payload **before any release creation/write**.
4. The attachment helper again validates the original full snapshot, stages original artifacts, and changes **only** the staged `windows-release.json` by adding the independently Git-validated object. It computes the enriched manifest's own expected size/hash and verifies those exact bytes on existing-asset checks and post-upload readback. Original inventory bytes and binary/hash checks are not projected away or weakened. Python never sees an enriched manifest.
5. Historical Windows attachment recovery continues using original published legacy inventories and their original-SHA validation. It neither enriches old assets nor rebuilds/relabels them. Existing completed pre-changelog publications are preserved on rerun. Partial new uploads retry against the same deterministic enriched payload; conflicts fail, never clobber published bytes.

The optional Windows flag exists to preserve older callers and historical recovery, not as a CI escape hatch: CI explicitly requires it. Existing Windows packaging fields such as `tag: windows-v0.1.N` and `signing: unsigned-preview` remain exactly as before; actual stable-main tag provenance is checked separately.

## Size and old-client compatibility

The schema's hard maximum is **1 MiB for the entire UTF-8 manifest**, not merely its changelog. Selective-build publication-manifest readers accept that bound and count UTF-8 bytes. Current-download table tooling does not read manifest contents, so has no such reader limit to update. Native reader updates are maintained with their respective clients.

Production serializers are compact and currently impose the stricter **65,536-byte legacy-client bound**. Overflow aborts before remote writes with an explicit compatibility error. Never truncate segments, discard originals, reset the verified baseline, or silently ship a manifest old installed updaters reject. Raising this publication guard requires an explicit installed-client migration decision, independently of the 1 MiB schema ceiling. Historical manifests are not rewritten to add notes.

## Local verification

```sh
node --test tests/release-changelog.test.mjs tests/windows-main-release.test.mjs
npm run test:release-selection
npm run test:downloads
npm test
```

Tests use disposable real Git repositories, including merges/renames, skipped publications, exact raw subjects, incomplete/tampered evidence and byte-size bounds. A fixture-backed subprocess executes the actual Android workflow CLI with read-only fake GitHub responses. Windows integration tests ensure every strict Python-validator invocation receives original bytes, only the staged manifest changes, enriched readback detects tampering, and published assets are never clobbered. Existing Python test suites remain executable and unchanged.

Read-only GitHub preview (provide a local manifest and complete checkout at that exact source):

```sh
node scripts/release-changelog.mjs --platform android \
  --directory /path/to/original/android-package --repository everyonesoftware/music \
  --output "$TMPDIR/music-android-enriched-preview.json"
node scripts/release-changelog.mjs --platform windows \
  --directory /path/to/original/windows-package --repository everyonesoftware/music \
  --output "$TMPDIR/music-windows-enriched-preview.json"
```

`--output` writes a local preview only and never publishes. Without it, the Android command enriches its local package directory for the existing publisher. Direct in-place Windows enrichment is rejected: use the validated Node Windows publisher, never feed an enriched manifest back into the unchanged Python exact-equality validator. No hosted release/installation success is implied by local tests or previews.
