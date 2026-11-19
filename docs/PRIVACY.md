# Music privacy policy

**Effective date: November 19, 2026**

This policy describes the Android version of **Music**, developed by **Everyone Software (Dan Schulte)**, with application ID `com.everyonesoftware.audio`. Music is a client for a Navidrome-compatible music server that you choose. It does not create an Everyone Software account or use an Everyone Software-operated music backend.

Privacy contact: [contact@everyonesoftware.net](mailto:contact@everyonesoftware.net).

## Information Music handles

- **Connection information:** your music-server address, username, connection name, selected libraries, and password. These are used to connect to your server. Saved passwords are encrypted using AES-GCM with a key managed by Android Keystore; other connection information is stored in app-private storage.
- **Library information and requests:** song, artist, album and playlist information, artwork, search terms, requested item identifiers, streamed or downloaded audio, and ratings you submit. Music exchanges the information needed for these operations directly with your configured server.
- **Playback and offline information:** queue contents, playback position, shuffle and repeat settings, local listening history, downloaded collection information, cached audio and artwork, and app preferences. Music stores this information in app-private storage to support playback, restoration, browsing and offline listening.

Release versions require HTTPS for music-server connections. Authentication requests include your username and a password-derived token with a random salt, rather than your plaintext password. Your server and its hosting or network providers can receive ordinary request information, including your IP address, request timing and the resources requested. Authentication values are included in request URLs, so server operators should protect their logs.

## Who receives information

**Your music-server operator.** The server you configure receives authentication information and the browsing, search, artwork, streaming, download and rating requests necessary to provide the features you use. Its operator controls server-side accounts, logs, retention and any integrations, such as server-managed listening tracking. Music does not itself submit listening history to a separate scrobbling service. Contact your server operator about its privacy practices or deletion of server-side information.

**GitHub update services.** The current direct-download Android version checks for updates automatically when eligible and also supports manual checks. Checks and user-requested update downloads contact GitHub and its release-asset hosts. Those services receive ordinary network request information, such as your IP address and the updater's user-agent. The updater uses a separate client and does not receive your music-server address, username, password, authentication tokens, searches or listening history. Installed-version comparison is performed locally. Offline music mode blocks music-server traffic; it does not disable update-service traffic. GitHub's handling of its requests is described in the [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

**Android and connected media systems.** Music provides library information, track metadata, artwork, playback state and controls to Android and trusted media controllers, including Android Auto and connected playback systems. This enables lock-screen, notification, car and other system playback controls. What is displayed and how it is handled also depends on Android and the connected system. Music does not provide those controllers with your saved password.

**Support correspondence and this webpage.** If you email the privacy contact, your email provider and ours process the message, sender address and any details you choose to include. We use correspondence to respond to your inquiry and retain it as needed for that inquiry, follow-up and applicable legal obligations. Do not send passwords or authentication tokens. This policy webpage is hosted using Cloudflare, which processes web requests, including IP addresses and technical request information, to deliver and protect the site. The page does not add advertising, analytics scripts or tracking cookies.

Music does not include advertising, third-party analytics or automatic third-party crash-reporting SDKs. It does not sell your personal information or use it for targeted advertising. Diagnostics collected independently by Android, your device manufacturer or your app store are governed by those providers' settings and policies.

## Permissions

Music uses internet and network-state access to connect to services, foreground media playback and notification capabilities for background listening and playback controls, and nearby-device permissions for supported system audio-output discovery and switching. Nearby-device access is not used to determine your location. The direct-download version can request permission to open Android's package installer for an update you choose; installation requires your confirmation.

Music does not request microphone, camera, contacts or location permissions. Voice requests can be delivered by Android or a car host; Music does not itself record microphone audio.

## Retention and deletion

Connection information, settings, playback state and local history remain in the app's storage until removed or reset. Automatic audio and artwork caches are managed using storage limits. Music can automatically save audio during playback and prefetching according to your storage settings. Explicitly saved offline downloads are protected from automatic cache cleanup.

You can use Music's storage controls to clear the automatic cache or remove downloads. Clearing the automatic cache does not erase credentials, settings or listening history, and keeps pinned downloads and currently active playback resources. Removing one download does not necessarily remove bytes still needed by another saved collection or active playback. Downloaded update packages can remain in app-private cache for verification and reuse.

To reset all local Music information, use Android's app settings to clear Music's app storage, or uninstall the app. This removes local connection information, saved passwords, settings, playback history and app-private downloads; you will need to reconnect afterward. Music disables Android backup and excludes app data from its configured backup and device-transfer rules, although device behavior can vary.

Clearing local data or uninstalling does not delete your remote music-server account, server logs, music library or submitted ratings. Music does not create or administer that account. Ask the server operator to delete information it holds. GitHub, Cloudflare, your email provider and system providers control retention of information processed by their services.

For privacy questions or requests concerning information you have sent directly to Everyone Software, email [contact@everyonesoftware.net](mailto:contact@everyonesoftware.net). We cannot access or erase data held only on your device or on a server you operate or choose.

## Changes to this policy

We will publish revisions on this page and update the effective date when this policy changes. Material changes to the app's data handling will be reflected in the policy and any required in-app or store disclosures.
