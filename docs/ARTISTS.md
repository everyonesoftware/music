# Artists

## Approved experience

Artists is the third Library shelf, after Playlists and Albums, in the native Android and Windows apps. Artists also has a dedicated collection destination and appears in library search results.

Opening an artist shows the artist's albums and an All songs view. Opening an album from that page retains the artist as its browsing origin. Browsing, changing tabs, searching and going Back must not replace or restart playback.

Artist detail actions are icon-only **Play → Shuffle**, not playlist creation or a saved shuffle preference:

- **Play** starts a fresh temporary queue in the retrieved album/track order.
- **Shuffle** immediately starts a fresh randomized queue, including its first song.
- Every retrieved track occurrence is preserved, including repeated song IDs across albums. No deduplication by title or song ID.
- Incomplete or failed retrieval must not start a partial queue or replace the existing session.
- No artist-level Download action is introduced.

Windows uses the existing collection pattern: a 56px round sage Play button, then a 48px neutral circular Shuffle button, with an 8px gap. Android uses a 56dp round sage Play button and a 48dp plain-icon Shuffle target; artist name, summary and the action group are horizontally centered. Accessible names remain even though the actions have no visible text labels.

## Data boundaries

Artist identity comes from the configured server's ID3-tag artist index (`getArtists`), not a case-insensitive grouping of display names. `getArtist` returns that artist's albums; `getAlbum` supplies their ordered tracks. This is the server's library representation, not an external streaming catalog or a promise of an entire commercial discography.

Artist retrieval, detail publication and playback must remain bound to the active account and selected artist. A delayed response after navigation or account replacement must not revive an abandoned page or start playback. Loading, failed, empty and offline states must be explicit; unknown song counts must not be shown as complete totals.

Existing playlist/album downloads, offline collection playback and the Android Auto root are outside this feature's expansion scope.

## Design and verification

The approved interactive design is `mockups/artists/index.html`. Its sample songs, original placeholder artwork, viewer tabs and simulated playback belong only to the design study, never production.

Run `npm run test:artists` for browser design checks or `npm test` for the full browser/regression suite. The artist study verifies Library order, artist search, album/song browsing, ordered and shuffled temporary queues, icon-only platform controls, centered Android geometry, navigation, filtering and narrow layouts.

Native protocol, policy, service and UI tests are separate evidence. Android APK assembly does not establish emulator behavior; Windows compilation does not establish WinUI rendering, audio or installer acceptance. Release acceptance includes the existing signed Android publication and packaged Windows install/window/uninstall checks for the exact source revision.
