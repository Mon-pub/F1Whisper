# Releasing F1Whisper

**Public** F1Whisper releases are built, gated, signed, attested, and published exclusively by the
release workflow (`.github/workflows/release.yml`), triggered by pushing a version tag. Local
release-signed builds exist too, with a different, narrower role — see
[Local signed test builds](#local-signed-test-builds).

## Version scheme

| Item | Rule |
| --- | --- |
| Tag | `v6.4.3-<N>` (annotated — enforced by the workflow), `<N>` a positive integer, no leading zero |
| versionCode | `1148 + <N>` (base 1148 = the upstream 6.4.3 build this fork tracks) |
| versionName | `6.4.3o-<N>` |

The workflow rejects: a tag that does not match the format, a non-annotated tag, a tag whose
commit is not the **current `origin/main` HEAD**, an `<N>` not strictly greater than the latest
published release, a versionCode beyond Android's limit, and a built artifact whose versionCode or
versionName does not match the tag.

## Release chain (least-privilege jobs)

The workflow is split so repository-controlled build code never runs with signing capability:

1. **build-and-gate** — `contents: read`, no secrets, `persist-credentials: false`. Validates the
   tag policy (annotated, == `origin/main` HEAD, monotonic `<N>`), runs the full quality gates
   (unit tests for `:app` onprem debug+release, `:domain`, `:common`, `:commonAndroid`
   debug+release, `:lint-rules`; `ktlintCheck`; full `:app:lintOnpremRelease` against the reviewed
   baseline `app/lint-baseline-onprem.xml`; `lintVitalOnpremRelease`; libthreema
   `cargo test --locked`), builds the **unsigned** APK, asserts artifact identity (package
   `info.f1tech.threema`, versionCode, versionName, exact ABI set arm64-v8a + armeabi-v7a, no
   GMS/Firebase/c2dm manifest entries), asserts the artifact really is unsigned, and hands a
   digest-pinned artifact forward.
2. **sign** — runs in the protected `release` environment (required reviewers). **No checkout**:
   the unsigned APK is digest-verified, `zipalign`ed, and signed with the `apksigner` CLI only
   (v2+v3, no v1). The keystore is decoded with mode 0600 immediately before signing and deleted
   immediately after (plus an `if: always()` cleanup). The signer certificate is asserted against
   the pinned release fingerprint.
3. **attest** — `id-token`/`attestations: write` only; generates build provenance for the exact
   signed APK.
4. **publish** — `contents: write` only; digest-verified upload of the exact APK + `.sha256` with
   `overwrite_files: false` and `fail_on_unmatched_files: true`, refusing to publish if a release
   for the tag already exists. Release runs are serialized by a concurrency group, so two tags can
   never race numbering or publication.

## Cutting a release

1. Confirm the release commit is the current `main` tip and the exact state has been confirmed on
   real devices by the maintainer. Never tag an untested commit.
2. Optional but recommended (S2-12 rehearsal): run the workflow via **workflow_dispatch** on
   `main` first — this executes the full gate battery and an unsigned build on a clean runner
   without touching the signing environment or publishing anything.
3. Create and push the annotated tag:

   ```bash
   git tag -a v6.4.3-<N> -m "F1Whisper v6.4.3-<N>"
   git push origin v6.4.3-<N>
   ```

4. Approve the `release` environment when the sign job requests it.
5. Any failed gate aborts the release before signing/publication. Fix on `main`, then tag the
   next `<N>` (never reuse or move a tag).

## Local signed test builds

Release-signed **test** APKs are intentionally buildable on the development machine via `F1W_*`
credentials in `local.properties`: the on-device confirmation gate installs release-signed builds
at the *installed* versionCode (e.g. `-Pf1wReleaseName=v6.4.3-37` while 1185 is installed) before
anything is published. These builds are never published; every public release artifact comes from
the CI chain above, where signing requires the protected environment approval. Keep the keystore
and `local.properties` out of the repository (both are gitignored) and keep offline backups of the
keystore — losing it, or rotating the certificate, changes the signer fingerprint and makes
existing installations unable to upgrade.

## Required environment secrets

Settings → Environments → `release` (environment-scoped ONLY — keep no repository-scoped copies,
so a workflow run outside the protected environment can never see them):

| Secret | Content |
| --- | --- |
| `F1W_KEYSTORE_BASE64` | base64 (no newlines) of the release keystore (`.jks`) |
| `F1W_KEYSTORE_PASSWORD` | keystore (store) password |
| `F1W_KEY_ALIAS` | key alias (`f1whisper`) |
| `F1W_KEY_PASSWORD` | key password |

## Repository settings that cannot be enforced from source

These must be configured once in the GitHub UI and re-checked when repository administration
changes; the workflow assumes them but cannot create them:

1. **Tag protection ruleset** (Settings → Rules → Rulesets): a ruleset targeting `v*` tags that
   restricts creation to repository maintainers and forbids updating or deleting existing tags.
   This is what makes "tag ⇒ release" a privileged operation.
2. **Protected `release` environment** (Settings → Environments → `release`): required reviewers
   enabled; the four signing secrets live HERE and only here.
3. **No repository-scoped signing secrets** (Settings → Secrets and variables → Actions): delete
   any repository-level copies of the `F1W_*` secrets if present.
4. **Actions permissions** (Settings → Actions → General): default workflow token permissions =
   read-only (jobs declare their own writes); optionally restrict allowed actions to the pinned
   set used by the workflow.
5. **Attestations** (Settings → Actions → General): artifact attestations enabled (default for
   public repositories).
6. **Immutable releases** (Settings → General, if offered for the repository): enable, so
   published release assets cannot be replaced even with `contents: write`.

## Verifying a published APK

```bash
# 1. Digest matches the published .sha256 file:
sha256sum -c F1Whisper-<...>.apk.sha256

# 2. Valid signature from the F1Whisper release certificate:
apksigner verify --print-certs F1Whisper-<...>.apk
# Signer #1 certificate SHA-256 digest must be:
# 0e6f089e8b01ab7c1d4ddc7da36f4340df36f3ed1ab371d7bf1432f0980f51a7

# 3. Build provenance (proves the exact APK was built by this repository's release workflow):
gh attestation verify F1Whisper-<...>.apk --repo Mon-pub/F1Whisper
```

## Upgrade path

Every release is signed with the same certificate and carries a strictly increasing versionCode,
which makes in-place upgrades *eligible* on any older release. What is actually **tested** is the
adjacent-release upgrade performed during the on-device confirmation gate before each release
(install over the previously published version, data preserved including database migrations).
Skipping several releases at once is expected to work for the same reasons but is not part of the
per-release test evidence. Downgrades are not supported by Android and not tested. The in-app
self-updater additionally validates a downloaded APK (package name, strictly newer versionCode,
signer certificate shared with the installed app) before offering installation.

## Known gaps

- Lint: `:app:lintOnpremRelease` gates against a reviewed baseline of inherited (pre-fork) lint
  debt (`app/lint-baseline-onprem.xml`). The policy is zero NEW errors; baseline entries are
  burn-down debt, not accepted forever.
- Rust: the gate runs `cargo test --locked` for vendored libthreema; `cargo clippy` is NOT a hard
  gate because upstream libthreema currently carries ~82 clippy warnings — restyling vendored
  upstream code would bloat the fork delta and complicate rebases.
- Each release run executes on a pinned GitHub-hosted runner image (`ubuntu-24.04`) with restored
  Gradle/Cargo caches; it is a fresh runner per run, not a hermetic/reproducible-build proof. The
  `workflow_dispatch` rehearsal covers the "does this exact source pass all gates on a clean
  runner" question without publishing.
- The sign job uses the pinned `android-actions/setup-android` action to obtain `zipalign`/
  `apksigner`; it runs no repository code, but the pinned action itself executes in the
  environment-protected job.
