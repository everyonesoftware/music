# Windows song storage and offline playback

## Implemented path

The native window now instantiates Storage & offline settings and Offline & recent navigation. Song rows expose **Download song** and **Remove download** in their native context menu; album/playlist details expose a collection Download button. Choosing a collection persists all pin intentions before downloading. Pinned media is separate from the automatic cache and survives automatic cache clearing.

- Defaults: save played songs on, 2 GiB automatic cache, next three on unmetered Wi-Fi, mobile/metered prefetch off. Settings expose 500 MiB / 1 / 2 / 5 GiB / custom MiB limits and off / 1 / 3 / 5 / 10 upcoming songs.
- Account identity uses the normalized server endpoint and case-sensitive username, not the credential entry ID or a signed media URL. Hashed per-account directories and hashed original-representation song names live below the existing private LocalApplicationData profile. Credentials remain in PasswordVault. Indexes contain unsigned metadata, byte counts, MIME type, completion, pin state and last access, never signed stream URLs.
- A complete local song opens a random-access file stream with its persisted MIME type. A missing song starts MediaPlayer's network source immediately while a separate full-file request saves the original representation. This deliberately does not wait for a full download before starting playback. It uses two server transfers during an initial streamed play, not a shared progressive proxy.
- Completed current-song downloads hand off to local playback using the current playback generation, live position and latest play/pause intent. Asynchronous file opens recheck the generation and dispose stale streams. Repeats, previous-track playback, seeks and restored sessions resolve complete local files first. Local codecs still depend on Windows media support.
- Media is published only after a successful nonempty full response, content-type and declared-length checks, file close, and atomic rename. Interrupted files remain explicitly incomplete and cannot acquire an Offline badge. Incomplete transfers retry from the beginning, rather than trusting range concatenation across representations.
- Prefetch, Next and Previous consume the same resolved playback order, including shuffle and repeat wrap. Previous returns through up to 100 actual track-selection departures, rather than a numeric neighbor after a shuffled jump; Next then replays that forward path before consuming the remaining prefetched plan. Same-track local handoff, reconnect and restart do not add duplicate history. Shuffle/repeat changes preserve traversed history and invalidate only unplayed guesses. Without history, Previous retains the sequential predecessor/first-track clamp/repeat-wrap behavior. The existing greater-than-three-seconds Previous action still restarts the current song. History is in-memory, resets on queue replacement/restore/account stop, and does not guarantee every historical song remains cached: missing files still obey the offline-unavailable rule. Transfer priority is current, immediate next, up to three recent incomplete songs, remaining selected upcoming songs, then explicit pending pins. Changing tracks cancels and drains the previous transfer generation before the next generation writes.
- LRU excludes pinned files and protects the current and previous selected song. Live protection is updated under the cache's deletion lock before asynchronous local-track opening, so an older worker's captured list cannot evict a newly selected song. This is a soft automatic-cache limit: protected media can exceed it. Partial bytes are included; startup reconciles actual files and removes unindexed media.
- Offline mode cancels server browse/artwork/download work, blocks server calls at the client boundary, removes the remote playback source, and uses only complete local media. Startup restores the local queue and cached song list before any library ping. Search/filter on Offline & recent is local. Missing/partial tracks show an honest unavailable message.
- Network changes and bounded timer retries check connectivity. Interrupted playback only resumes if play is still intended; a pause, source generation change or account change invalidates stale completion work. Cache/download errors produce fixed actionable notices, not exception messages, paths or authenticated URLs.
- Removing the account confirms deletion, drains transfers, stops playback, purges media and saved queue, and removes its vault/profile entry. Replacing a different account purges the replaced account's media and queue; replacing credentials for the same endpoint/user retains the same media scope.

## Automated validation

Run from `apps/windows` with the repository's documented .NET SDK/ICU environment:

```sh
dotnet test Music.Core.Tests/Music.Core.Tests.csproj -c Release
dotnet test Music.Update.Tests/Music.Update.Tests.csproj -c Release
dotnet run --project validation/LogicChecks.csproj -c Release
python3 -m unittest discover -s validation -p test_source_contracts.py -v
node --test validation/visual-contracts.test.mjs validation/offline-contracts.test.mjs
dotnet build Music.App/Music.App.csproj -c Release -p:OutputType=Library -p:WindowsAppSDKSelfContained=false -p:PrepareForRunDependsOn=CopyFilesToOutputDirectory
```

New tests were introduced in red/green slices for disk persistence/account separation, partial failures, LRU/pins/clear/purge, truncated files and repair, durable pin intentions, interrupted-process reconciliation, stale account leases, MIME persistence, offline network denial, transfer integrity, defaults, priority, shared shuffle/repeat order, and intent-aware handoff. Node contracts verify native-window call-site wiring; they do not instantiate WinUI.

## Native acceptance still required

This WSL environment has no Windows mount, PowerShell executable, Wine or native Windows runtime. Cross-build success is compilation evidence, not proof of native launch, decoding, audible playback or installed-app behavior. On Windows verify:

1. MP3/FLAC and other supported originals start promptly while downloading; completion handoff retains position and latest pause/play choice. Disconnect immediately after completion and seek/repeat/previous without server requests.
2. Close/reopen with a saved queue in Offline mode; verify local metadata, filtering, seek and restart, with server access monitored. Verify incomplete tracks never display Offline or play as complete.
3. Verify prefetch on unmetered Wi-Fi, metered/mobile blocking and explicit opt-in; shuffled Next and repeat-wrap select the same songs that were prefetched. After a nonadjacent shuffled jump to a completed local song, disconnect and press Previous within three seconds: it must reopen the actual prior song locally, not its numeric neighbor. Exercise consecutive Back/Next, direct queue selection, and a same-track download handoff/reconnect without duplicate history; after three seconds Previous must still restart the current track.
4. Fill storage or deny writes and verify actionable notices and honest completion/usage. Check active-file cleanup and account removal using actual Windows sharing semantics.
5. Verify all native controls, context menus, keyboard access, settings persistence, collection download/pin removal, and soft-limit/clear behavior. Pausing during reconnect or handoff must prevent automatic playback.

API choices were checked against Microsoft's MediaSource.CreateFromStream(IRandomAccessStream, string), MediaSource local-file playback, ConnectionProfile network-cost documentation, and the OpenSubsonic stream endpoint's `format=raw` definition.
