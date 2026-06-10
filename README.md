# F1Whisper

**Secure messaging app for Android, with broader language support, using the same Threema core.**

F1Whisper is a minimal, rebranded fork of
[Threema for Android](https://github.com/threema-ch/threema-android) that connects to a
self-hosted OnPrem backend. It exists to add wider language coverage and a streamlined
self-hosted setup — nothing more.

## What we changed

- **Branding** — renamed to F1Whisper (product name and logo wordmark). The application ID is
  unchanged.
- **Languages** — added or completed 10 localizations (Arabic, Persian, Urdu, Uyghur, Hindi,
  Bengali, Uzbek, plus French / Russian / Turkish gap-fills) with full right-to-left (RTL)
  support and a language picker on the first screen.
- **Onboarding** — a single "activation key" field for self-hosted setup.
- **Backend** — points at a self-hosted OnPrem server instead of the official infrastructure.

## What we did NOT change

**The security and the protocol are Threema's, untouched.** F1Whisper uses the exact same
cryptographic core (`libthreema`) and the exact same Chat Server Protocol as upstream Threema.
No encryption, key handling, message format, or other security-relevant code was modified.
Messages stay end-to-end encrypted; the server is only an encrypted relay and never sees
plaintext.

This is a client-side rebrand and localization layer over upstream Threema for Android (6.4.3).
For the cryptography design, see Threema's
[Cryptography Whitepaper](https://threema.com/press-files/2_documentation/cryptography_whitepaper.pdf).

## Build

Standard Threema for Android toolchain (Android SDK + NDK, protobuf compiler, Rust with the
Android targets). Build the `onprem` flavor:

    # installable debug build
    ./gradlew assembleOnpremDebug -PnoAbiSplits

Signed releases are produced by the GitHub Actions workflow in
[`.github/workflows/release.yml`](.github/workflows/release.yml).

## License

F1Whisper inherits Threema for Android's license: the GNU Affero General Public License v3
(AGPL-3.0). Original work Copyright (c) Threema GmbH. See [`LICENSE.txt`](LICENSE.txt).

F1Whisper is an independent fork and is **not** affiliated with or endorsed by Threema GmbH.
"Threema" is a trademark of Threema GmbH and is used here only to describe the origin of this code.
