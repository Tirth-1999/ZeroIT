# ZeroIT

ZeroIT is a free, open-source expense app for Android and iOS, built with Kotlin and Compose Multiplatform. It currently works locally on one device; real accounts and cross-device synchronization are not implemented.

Groups and direct expenses support five split modes, multiple payers, exact integer currency amounts, separate currency balances, manual settlement records, edit/delete/restore and audit history. A settlement records a payment made elsewhere; the app does not transfer money. Light, dark and system appearance are available. Sample data is synthetic.

## Build Android

Use JDK21 and Android SDK Platform35 with Build Tools35.0.0. Set ANDROID_HOME or an ignored local.properties containing sdk.dir.

```sh
./gradlew :app:testDebugUnitTest :shared:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Device tests can uninstall an application and delete its data. Use only disposable devices with synthetic data after reviewing the fail-closed tooling in scripts/android-test-disposable.py. Do not run connected tests against a device holding real ledgers. The local wrapper scripts use ignored reference directories for caches; do not commit their contents.

## Build iOS

Use full Xcode with an iOS SDK on an Apple Silicon Mac. Open iosApp/iosApp.xcodeproj and select the Fairshare scheme, or run:

```sh
bash scripts/ios-build.sh
```

This builds an unsigned simulator app. Physical devices require your own development signing configuration. Store distribution is not configured.

## Source and compatibility

shared contains the Compose UI, domain arithmetic, Room storage and portable tests. app hosts Android; iosApp hosts iOS. Existing org.fairshare.app identifiers, Fairshare scheme/framework names and fairshare.db are compatibility identifiers from the earlier project name. Preserve them during upgrades. Never uninstall an app containing important local data; backup/restore and migrations require independent verification.

Money stays in integer minor units; currencies are never added together or automatically converted. Greedy settlement suggestions preserve balances but do not guarantee the fewest payments.

## Status and verification

This is a reviewed source baseline, not a public production release or complete Splitwise parity claim. Hosted CI configuration includes Android unit/lint/debug build and iOS shared tests/simulator build. This initial publication does not establish a fresh combined regression, successful hosted execution, safe backup/restore, store signing or release qualification. Historical workspace evidence and private research captures are intentionally not published. Reproduce and verify the exact revision for your intended use.

## License

Apache2.0; see LICENSE and NOTICE. ZeroIT is independently implemented and uses original branding. It is not affiliated with Splitwise. Third-party dependencies retain their licenses; complete distribution attribution must be reviewed for a release. No proprietary reference APKs, research captures, credentials or private ledgers are included.
