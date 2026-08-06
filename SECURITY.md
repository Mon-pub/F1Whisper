# Security

## Scope

This policy covers **F1Whisper**: this repository and the Android packages published from it.

It does not cover Threema for Android, Threema's apps, Threema's servers or Threema's network.
F1Whisper is an independent fork and is not affiliated with or supported by Threema GmbH. A report
about Threema's own products belongs with Threema, via
[threema.com/contact](https://threema.com/en/contact).

If you are unsure which side a bug falls on, report it here and say so. Misrouting it to Threema
costs a researcher time and reaches people who cannot fix this code.

## Reporting a vulnerability

Please report privately and give us a chance to fix the issue before it is public.

1. Open a private security advisory at
   <https://github.com/Mon-pub/F1Whisper/security/advisories/new>.
2. If that form is not available to you, open a normal issue saying only that you have a security
   report and asking for a private channel. Do not put technical detail in a public issue.

Useful in a report: the app version (Settings, About), the device and Android version, what an
attacker gains, and the smallest reproduction you have. A proof of concept helps but is not
required.

This project runs **no bug bounty** and cannot pay for reports. Say if you want to be credited in
the release notes.

If the issue is in code inherited unchanged from upstream Threema, it very likely affects Threema's
own apps as well, and reporting it to Threema in parallel is the right thing to do.

## Cryptography

F1Whisper does not implement its own cryptography. The cryptographic core, key handling and the
`libthreema` protocol implementation are upstream Threema's, byte-for-byte unmodified against the
upstream 6.4.3 release this fork is based on, which is verifiable with `git diff` against the
upstream tag. The design is documented in Threema's
[Cryptography Whitepaper](https://threema.com/press-files/2_documentation/cryptography_whitepaper.pdf).

What this fork builds on top of that core is listed under
[What this fork adds, and how it is protected](#what-this-fork-adds-and-how-it-is-protected) below.

## Release signing and verification

Released APKs are signed with **this project's own release key**, not Threema's. The signing
certificate is:

    SHA-256: 0E:6F:08:9E:8B:01:AB:7C:1D:4D:DC:7D:A3:6F:43:40:DF:36:F3:ED:1A:B3:71:D7:BF:14:32:F0:98:0F:51:A7

Verify an APK you downloaded before installing it:

    # 1. the signer is the key above (v2/v3 scheme, no v1)
    apksigner verify --print-certs F1Whisper-<version>.apk

    # 2. the exact file was built by this repository's release workflow
    gh attestation verify F1Whisper-<version>.apk --repo Mon-pub/F1Whisper

Signing runs in a protected CI environment that performs no repository checkout, so build code
cannot reach the key. The full release procedure and the gates it enforces are in
[`RELEASING.md`](RELEASING.md).

Release integrity is established on the artifact: the signature above and the build provenance
attestation together identify exactly which file this project published and which workflow produced
it.

## What this fork adds, and how it is protected

The additions on top of upstream's protocol, for anyone reviewing the source.

1. **Three added Chat Server Protocol message types** (`0x84`, `0x85`, `0x95`) for typing indicators
   and the disappearing-messages timer. They travel inside the same end-to-end encryption as every
   other message.

2. **`f1_disappearing_timer` in the `MessageMetadata` box.** It carries the sender's timer for a
   single message, inside the end-to-end-encrypted metadata box, so the recipient honours the timer
   the sender actually set. Its encoded size is compensated in the message padding, so the encrypted
   length is identical whether or not a timer is set and whatever its value.

3. **Disappearing messages, listen-once voice messages and spoilers** are enforced by the app at
   every surface that can emit message content: the chat, notifications, the accessibility tree, the
   pinned-message banner and media playback.

4. **In-app update check.** A downloaded package is offered for installation only if it parses, its
   versionCode is strictly newer than the installed app's, and it shares a signing certificate with
   the installed app. Android's package installer enforces the signature match again at install
   time.

5. **DNS.** The platform resolver is used first. A DNS-over-TLS fallback is consulted when the
   platform resolver fails or returns no records, so name resolution keeps working on networks that
   interfere with it.

6. **No Google Play Services and no Firebase Cloud Messaging.** No push traffic and no push metadata
   goes through Google. The app maintains its own connection to the server instead, which is why it
   asks to be exempted from aggressive background restrictions on some devices.

7. **Provisioning trust anchor.** Only this project's Ed25519 key is accepted for the OnPrem
   provisioning file, so this build cannot be pointed at any other infrastructure.

8. **The server is an encrypted relay.** It cannot read message content.
