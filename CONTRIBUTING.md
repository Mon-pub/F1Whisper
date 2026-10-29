# Contributing to F1Whisper

F1Whisper is an independent fork of
[Threema for Android](https://github.com/threema-ch/threema-android). Threema GmbH does not maintain
this fork and its support channels cannot help with it, so please bring everything about **this app**
here rather than to Threema.

## Bug reports and feature requests

Open an issue in this repository. Useful in a report: the app version (Settings, About), the device
and Android version, what you did, and what happened instead.

**Do not report security issues in a public issue.** Follow [`SECURITY.md`](SECURITY.md) instead.

## Patches

Pull requests are welcome. Before opening one:

- Keep the change focused on a single thing.
- Match the surrounding code. This is a fork of a large existing codebase, and consistency with it
  matters more than personal style.
- Run the gates that CI will run anyway:

      ./gradlew :app:testOnpremDebugUnitTest :app:testOnpremReleaseUnitTest
      ./gradlew :app:ktlintCheck
      ./gradlew :app:lintVitalOnpremRelease

  A debug build skips the release-only lint gate, so a debug-only check can pass work the release
  build rejects.
- Contributions are accepted under the project's license, the GNU Affero General Public License v3.
  See [`LICENSE.txt`](LICENSE.txt).

Changes that belong upstream, in code this fork inherited unchanged, are better sent to
[Threema for Android](https://threema.com/open-source/contributions) so that everyone gets them.

## Translations

F1Whisper ships 29 languages and adds seven that upstream does not have, so translation help is
genuinely useful here.

Translations live in this repository as Android string resources under `app/src/main/res/values-*/`,
not on Threema's Crowdin. Open a pull request against those files, or an issue if you would rather
send the text and have someone else wire it in.

Note that every user-facing string this fork adds is expected to be translated into **all** shipped
languages before it is considered done, so a partial translation of a new string is a starting point
rather than a finished change.
