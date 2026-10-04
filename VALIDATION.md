# Executed validation — 0.2.0

Verified October 3, 2026 on macOS arm64 with a workspace-local JDK 17 and Android SDK. The checksum-verified Gradle 8.13 wrapper executed:

```sh
./gradlew --no-daemon --offline testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Result: **BUILD SUCCESSFUL**. All **28 tests passed**, with zero failures or errors.

| Check | Result |
|---|---|
| Pure menu/order rules | 12 passed; every enabled offer/portion, all required-side categories, optional bakery sides, hot drink sizes, zero-based queue renumbering, bagel cap and older duplicate/over-limit notes |
| File DataStore | 7 passed; real files, concurrent updates, reopening, old schema-1 notes, Undo revision conflicts, corruption and missing-field preservation |
| ViewModel | 6 passed; buffered taps and save failures, exact earlier-meal focus, choose/clear/back return anchors, Bagel Tuesday editing at 13, SavedStateHandle restoration and Undo |
| Compose interactions | 3 passed; Android 15/API 35 in Robolectric, native graphics, 320 × 640 dp configuration |
| Android lint | Passed; zero reported code/resource issues |
| Debug APK | Version 0.2.0 / code 2; development signature verified by Android `apksigner` |
| Release APK | Built with R8 optimization and resource shrinking; unsigned |

The native UI checks exercise tapping a bagel box away from the minus button, reaching 13, disabled additions, repeated subtraction without triggering addition, folding Bagel Tuesday and reopening it from the summary, Undo, required-side gating, direct side selection and register readback. A two-Default/two-You-Pick-Two scenario verifies editing the first combo preserves the second and returning from food selection retains the original field's vertical position within 1 px. Compose saved-state recreation then retains the later bagel scroll position without replaying that old selection target.

Screens were rendered through Android views with Robolectric native graphics and visually inspected:

- [Entry](verification/entry.png)
- [Bagel grid at 13](verification/bagel-thirteen.png)
- [Bagel summary edit return](verification/bagel-edit-return.png)
- [Growing order list](verification/ordered-list.png)
- [Selection return to You Pick Two](verification/selection-return.png)
- [Register queue](verification/queue.png)
- [Local APK metadata and signature](verification/apk-verification.txt)

The local debug APK SHA-256 is `c760aa5e4e8fd921292d636845bd363f29ca0139ddf299c326f818cde0bd4f73`. CI independently builds and signs its public download; its release checksum identifies that APK.

Lint keeps code/resource warnings as errors. Four advisory checks for newer dependency/tool versions and target SDK availability remain excluded because the compatible toolchain and SDK 36 are deliberately pinned. There is one path-specific adaptive-icon folder exclusion; no broad lint baseline is used.

These are JVM/native-renderer checks, not a physical-phone or emulator test. Touch reach, large font settings, accessibility services, rotation and operating-system process termination still need phone review. Data persistence was checked by closing/reopening file storage; navigation, meal focus and expansion were checked through SavedStateHandle. The optimized APK has not been installed on a device. Café-specific menu accuracy and promotion terms remain research data requiring local verification. POS integration is outside this app's scope.
