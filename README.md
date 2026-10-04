# Drive-through Order Pad

A native Kotlin / Jetpack Compose Android app implementing the cashier prototype. Android 8.0 (API 26) or later. It opens with an empty Car 1; it does not seed demonstration orders.

## Cashier workflow

- **Default:** food, optional drink, side. Applicable sizes are available in the picker and on the food row.
- **You Pick Two:** two eligible foods, optional drink, side. Offers enforce the allowed portion; standard sandwiches use Half.
- **Bagel Tuesday:** collapsed above Mix & Match. Choose a bagel and type a positive whole-number quantity. Each filled row has Remove. One blank row remains available. Totals above 13 show a prominent warning and remain saveable; neither quantity entry nor additional rows are capped at 13.
- **Mix & Match:** collapsed, ten optional slots filtered to the configured roster.
- **Sides:** Apple, Chips and Baguette in Default and You Pick Two.
- **Next car:** the only bottom action on the entry screen. Each edit is saved on device; Next car queues the current car and opens the next empty car. Missing selections remain notes with review prompts.
- **Queue:** oldest first, including the current nonempty draft. Edit any saved meal; mark a car Entered at register after copying it to the register. Undo restores the previous committed change. “+ Another” preserves earlier meals of the same program.

## Install the review build

On the cashier's Android phone, open [GitHub Releases](https://github.com/sherrywang31/panera-drive-through-order-pad/releases), choose the newest Android prototype, and download **panera-order-pad.apk** from Assets. Open the downloaded APK and allow installation from that browser/download source if Android requests it. No Android Studio, ZIP extraction or GitHub sign-in is needed for the public release download. Android 8.0 or later is required.

Every successful `main` build publishes a numbered prototype release with the APK and its SHA-256 checksum. Pull requests produce an APK under the workflow run's **Artifacts → panera-order-pad-apk** instead; GitHub requires sign-in to download Actions artifacts, which arrive as a ZIP. Verification reports are a separate artifact. Failed checks never publish a new prototype APK. The workflow can also be run manually from Actions.

The APK is signed with a development key for prototype review. A different CI runner or local computer can generate a different development key; replacing a previous build may require uninstalling the old app, which removes its saved order notes. Preserve any needed notes before uninstalling. Production signing credentials are not configured or checked into this repository. The locally delivered `drive-through-order-pad-debug.apk` can also be transferred to a phone or installed with `adb install -r drive-through-order-pad-debug.apk`.

This is a working offline review build, not a Play Store release. It does not send orders to a POS. The included 249-item menu is the research profile from the prototype, dated October 3, 2026. **The Glenview café roster, current promotion eligibility and availability still need café verification before live use.** Mix & Match side capture remains a register review prompt, following the approved layout. Removing the app or clearing its data removes its notes.

## Build

Open this folder in Android Studio, or use a JDK 17 installation and the Android SDK. Versions are pinned in `gradle/libs.versions.toml`; the Gradle 8.13 wrapper verifies its distribution checksum. Install `platforms;android-36` and `build-tools;36.0.0` with the Android SDK manager. Point `ANDROID_HOME` to that SDK, or add `sdk.dir=/absolute/sdk/path` to an untracked `local.properties`.

The app uses a verified stable toolchain compatible with SDK 36. Lint treats code/resource warnings as errors; advisory checks for newer dependency/tool releases and target SDK availability are disabled so a new upstream release or a runner with extra SDKs cannot break CI. Dependency and target SDK upgrades should be explicit changes validated with the same tests and lint.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Outputs:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Optimized unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Test report: `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint report: `app/build/reports/lint-results-debug.html`

A production release needs a privately managed signing key, a confirmed application ID, and verified café menu data. No signing secrets belong in source control. The checked-in CI workflow runs the same checks, verifies the debug APK's signature, uploads it separately from reports, and publishes a public prototype download after a successful `main` build. Only the publishing job receives repository write permission; pull requests only run verification.

## Engineering structure

| Layer | Responsibility |
|---|---|
| `domain/Menu.kt` | Canonical item IDs, explicit program offers, portions, contextual category filtering and readback review rules |
| `domain/OrderEngine.kt` | Pure immutable order transitions; rejects ineligible offers/portions, preserves incomplete orders, adds/removes bagel rows, makes repeated Next car taps harmless |
| `data/NotebookRepository.kt` | App-scoped typed DataStore; atomic writes, monotonic revisions, revision-checked Undo, serialization and corruption errors |
| `ui/OrderPadViewModel.kt` | Immutable StateFlow, a sequential event channel, SavedStateHandle navigation/expansion, save-error handling |
| `ui/OrderPadApp.kt` | Stateless Material 3 screens and touch controls; lifecycle-aware state collection only at the app boundary |
| `OrderPadApplication.kt` | Explicit application-scoped dependency construction; asset loading off the main thread |

DataStore is appropriate for a small active note queue stored as one consistent document. Entered cars are removed rather than accumulated as an unbounded history. The latest Undo snapshot exists only in memory; Undo is intentionally unavailable after a full app restart. If persistent history, cross-device sync, or large searchable datasets are added, use a database-backed repository without changing the pure order engine.

The native note format has its own schema version (`1`) and does not import the browser widget's storage. Every selection snapshots its display name and portion label, so later menu removal cannot erase or rename a heard request. Removed/disabled choices are retained on readback with a review prompt. The bundled catalog is separate from order storage and retains source provenance. Menu updates belong in `app/src/main/assets/menu.json`; changing category membership alone never grants combo eligibility.

Writes must finish before the screen reports a saved change. The app preserves the last committed state on an I/O error and visibly reports the failure; numeric editors reset to committed values. It does not silently replace corrupt or unsupported order files with blank notes. There is no network permission, analytics, login, or remote service. Android backup/transfer rules exclude local order notes.

The UI follows the system light/dark theme, Android back navigation, keyboard/inset behavior and font scaling. Controls use native Compose semantics and at least 48 dp touch targets. Pickers and readback scroll vertically; all menu choices remain available without pagination. UI control strings and the menu currently target English.

## Verification

See `VALIDATION.md` for the executed build and test results. Tests cover program/portion integrity against every enabled offer, incomplete requests, optional drinks, repeated meals, soft bagel totals, dynamic row removal, rapid Next car events, Undo, concurrent storage writes, reopening storage, corruption preservation, SavedStateHandle restoration, save failures and native Compose interactions at a 320 dp width.

Before a café pilot, review the app on the cashier's actual phone: one-handed reach, keypad behavior, large text, screen rotation, background/relaunch, and copying two or three queued cars into the register. The app has no customer-identification fields and should be used for order notes only.

## References and menu provenance

The implementation follows the [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations), [DataStore documentation](https://developer.android.com/topic/libraries/architecture/datastore), [Compose accessibility guidance](https://developer.android.com/develop/ui/compose/accessibility), and [AGP 8.13 compatibility notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

Menu sources carried from the prototype include Panera's [September 2026 nutrition guide](https://www.panerabread.com/content/dam/panerabread/documents/c8-26-nutrition-guide.pdf), [allergen guide](https://www.panerabread.com/content/dam/panerabread/documents/c8-26-allergen-guide.pdf), and [published Mix & Match roster](https://www.panerabread.com/en-us/press/press-room/panera-and-jake-shane-launch-the-pass-that-panera-mix-and-match-meal.html). These establish research evidence, not a verified local selling menu. This project uses no Panera logo and is not an official Panera app.
