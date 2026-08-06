# F1Whisper

**Secure messaging for Android, built on Threema's cryptographic core and run against a
self-hosted server.**

F1Whisper is an independent fork of
[Threema for Android](https://github.com/threema-ch/threema-android). It keeps Threema's
end-to-end encryption exactly as it is and connects only to a self-hosted OnPrem backend.

It is not a cosmetic rebrand. F1Whisper is a separate application with its own package name, its
own release signing key, its own language set, several features upstream does not have, and a small
number of additions to the wire protocol. This document describes those differences in full.

## What is different from upstream Threema

### Identity and packaging

- **Application ID `info.f1tech.threema`** (upstream uses `ch.threema.app`). F1Whisper installs
  alongside Threema; it is a separate app with separate data, not an update to it.
- **Name and artwork**: product name F1Whisper, own logo wordmark.
- **Version scheme**: `6.4.3o-<N>`, versionCode `1148 + <N>`, tracking upstream 6.4.3 (build 1148).
- **One build flavour** (`onprem`) is maintained. Released builds carry `arm64-v8a` and
  `armeabi-v7a`.
- **No Google Play Services and no Firebase Cloud Messaging.** The app has no Google push channel
  and keeps its own connection to the server instead, which is why it asks to be exempted from
  aggressive battery management on some devices.
- **No analytics and no telemetry**, as upstream.

### Languages

29 languages ship in the app. Seven were added by this fork (Arabic, Bengali, Persian, Hindi,
Uyghur, Urdu, Uzbek), including full right-to-left layout support and a language picker on the very
first screen, before any account exists. The 21 languages inherited from upstream were extended to
cover the strings this fork added.

### Backend

- Points at a self-hosted OnPrem server instead of Threema's infrastructure.
- **The provisioning-file trust anchor is this project's own Ed25519 key.** A build of this
  repository will not accept a configuration signed by Threema, so it cannot connect to Threema's
  servers or to Threema-operated OnPrem deployments.
- Onboarding asks for a single activation key rather than a username and password.

### Features added

Disappearing messages (a shared per-conversation timer, distinct from upstream's keep-messages-for-
N-days purge), spoilers for text and media, listen-once voice messages, chat
folders, pinned messages, quote-reply for every message type, link previews, chat backgrounds,
checklists, group typing indicators, voice-message trimming, a connectivity diagnostics report, an
in-app update check, and guidance for the background-app restrictions some manufacturers apply.

### Protocol and message-format additions

F1Whisper adds, all of it inside upstream's existing end-to-end encryption:

- **Three Chat Server Protocol message types** that upstream does not define:
  `0x84` group typing indicator, `0x85` disappearing-messages timer, and `0x95` its group variant
  (`domain/src/main/java/ch/threema/domain/protocol/csp/ProtocolDefines.java`).
- **One field in the end-to-end-encrypted `MessageMetadata` box**: `f1_disappearing_timer`, field
  number 100 (`domain/protocol/src/csp-e2e.proto`). It carries the sender's timer for that single
  message, so a recipient honours the timer the sender actually set. Its encoded size is compensated
  in the message padding, so the encrypted length is identical whether or not a timer is set.
- **Extra keys in the file-data metadata map** carried inside encrypted file messages: listen-once
  state, the spoiler flag, and the quoted-message id.

## What is not different

**The cryptographic core is untouched.** F1Whisper uses upstream's `libthreema` and upstream's
crypto and key-handling code without modification: the paths `domain/.../base/crypto`,
`app/.../localcrypto` and every `libthreema` path have a zero-byte difference against the upstream
6.4.3 tag this fork is based on. Anyone can check that with a single `git diff` against upstream.

Messages remain end-to-end encrypted, and the server remains an encrypted relay that never sees
plaintext. For the cryptography design, see Threema's
[Cryptography Whitepaper](https://threema.com/press-files/2_documentation/cryptography_whitepaper.pdf).

The additions listed above are built on that core rather than around it, and they are described in
[`SECURITY.md`](SECURITY.md).

## Compatibility and limits

- **Not interoperable with Threema.** F1Whisper accounts live on the self-hosted server they were
  created on. There is no path between this app and Threema's network, in either direction.
- **Against an unmodified Threema client on the same self-hosted server**, ordinary messaging works,
  because the message types shared with upstream are unchanged. F1Whisper's own features need
  F1Whisper on both sides: a client that does not implement them ignores the added message types and
  the added metadata field.
- **Android 7.0 (API 24) or newer**; built against API 35.
- **Distribution is GitHub releases only.** F1Whisper is not on Google Play and not on F-Droid.

## Security

Vulnerability reporting, the release signing key, artifact verification and how this fork's own
additions are protected are documented in [`SECURITY.md`](SECURITY.md).

Report security issues in this fork to this project, not to Threema.

## Build

Requires the standard Threema for Android toolchain: Android SDK and NDK, the protobuf compiler,
and a Rust toolchain with the Android targets, for the `libthreema` native build.

    # installable debug build, all four ABIs
    ./gradlew assembleOnpremDebug -PnoAbiSplits

    # same, arm64 only (roughly half the size; both flags are required together)
    ./gradlew assembleOnpremDebug -PnoAbiSplits -Parm64Only

Debug builds skip the release-only lint gate, so verify changes with
`./gradlew lintVitalOnpremRelease` before proposing them.

Signed releases are produced only by the tag-triggered GitHub Actions workflow in
[`.github/workflows/release.yml`](.github/workflows/release.yml); the procedure and the gates it
enforces are documented in [`RELEASING.md`](RELEASING.md).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

F1Whisper inherits Threema for Android's license: the GNU Affero General Public License v3
(AGPL-3.0). Original work Copyright (c) Threema GmbH. See [`LICENSE.txt`](LICENSE.txt).

F1Whisper is an independent fork and is **not** affiliated with, endorsed by, or supported by
Threema GmbH. "Threema" is a trademark of Threema GmbH and is used here only to describe the origin
of this code. Bugs in F1Whisper are not Threema's, and Threema's support channels cannot help with
this app.
