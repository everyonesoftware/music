# Queue shuffle

The Windows and Android players use **Shuffle Up next** as an immediate queue action, not a playback mode.

## Playback behavior

- **Play** on an album or playlist starts a fresh queue in the collection's order, beginning with its first song.
- **Shuffle Play** starts a fresh randomized collection queue, including a random first song.
- The icon-only **Shuffle Up next** button is on the right of the Queue view's **Up next** heading. Each activation randomizes the remaining occurrences again; there is no selected state, toggle, or automatic return to an earlier order.
- Queue shuffling leaves the current song, playback position, playing/paused state, existing listening history, and already-traversed order alone. Repeated songs remain separate queue occurrences; none are removed or added.
- The shuffled remaining order is the order used by Next and prefetch and retained for restoration. Repeat is independent. With Repeat off, playback finishes when the remaining queue ends.
- The queue action is disabled when fewer than two songs remain. It does not start playback or replace an empty/finished listening session.

## Presentation

- Neither platform has a Shuffle toggle among its Now Playing transport controls. Previous, Play/Pause, Next, and Repeat remain.
- Windows Played is a transparent disclosure row with a leading chevron, **Played**, a trailing song count, and a bottom divider. Its expanded history remains independently scrollable and virtualized.
- Windows has no playback-panel volume control. Its enlarged Queue icon/label stays on the right and shares the transport buttons' vertical center; seeking remains below the transport row.
- Android retains its existing artwork, song details, rating/download controls, seeking, Media output, and Queue shortcut. The queue action has an icon-only 48dp touch target and an accessible **Shuffle Up next** name.

## Approved design reference

`mockups/queue-shuffle/index.html` is the approved interactive Windows/Android study. Android's Now Playing and Queue views can be switched using the study tabs or the simulated in-app navigation. Viewer modes, physical-size calibration, example songs, and simulated actions belong only to the study, not the native apps.

The study is self-contained and makes no account connection. Run `node tests/queue-shuffle.mjs` (or `npm test`) to exercise its queue behavior, navigation, alignment, calibration, responsive layout, and script-disabled initial rendering. Set `CHROMIUM_PATH` only when using a locally installed browser instead of Playwright's pinned browser.

Native unit/source tests and compilation are separate from runtime acceptance. Native checks must exercise actual queue mutation and playback continuity, including duplicate occurrences, repeated actions, Previous/Next, paused restoration, and history preservation. A browser study test does not verify native audio or an installed-app upgrade.
