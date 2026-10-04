# Executed validation

Verified October 3, 2026 on macOS arm64 with a workspace-local JDK 17 and Android SDK. The checked-in, checksum-verified Gradle 8.13 wrapper executed:

```sh
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Result: **BUILD SUCCESSFUL**.

| Check | Result |
|---|---|
| Pure menu/order-rule tests | 9 passed; checks every enabled offer and allowed portion |
| File DataStore tests | 6 passed; real files, concurrent updates, reopening, Undo revision conflicts, corruption and missing-field preservation |
| ViewModel tests | 4 passed; rapid handoff taps, failed writes/buffered-tap cancellation, navigation restoration, Undo/input refresh |
| Compose interaction tests | 2 passed; Android 15/API 35 in Robolectric, native graphics, 320 × 640 dp configuration |
| Android lint | Passed; no reported code/resource issues |
| Debug APK | Built; development signature verified by Android `apksigner` |
| Release APK | Built with R8 code optimization and resource shrinking; unsigned |
| Gradle wrapper | Distribution checksum pinned; wrapper JAR matches Gradle's published SHA-256 |

All **21 tests passed**, with zero failures or errors. The native screen tests exercise selecting a bagel, entering 14, seeing the over-target warning, Remove and Undo, direct side selection, Next car and preserved register readback. Screens were rendered through Android views with Robolectric native graphics and inspected:

- [Entry](verification/entry.png)
- [Bagel total above 13](verification/bagel-over-13.png)
- [Register queue](verification/queue.png)
- [APK metadata and signature verification](verification/apk-verification.txt)

Lint keeps code/resource warnings as errors. Four advisory checks for newer dependency/tool versions and target SDK availability are excluded because the compatible versions and target SDK 36 are deliberately pinned. A GitHub runner with additional SDK versions triggered `OldTargetApi` despite the same source passing locally; this environment-dependent upgrade advisory is excluded while API compatibility checks remain enabled. There is one path-specific `ObsoleteSdkInt` exclusion for the documented adaptive-icon `mipmap-anydpi-v26` folder; removing its qualifier caused AAPT2 resource linking to fail. No broad lint baseline or blanket code-check suppression is used.

These are JVM/native-renderer checks, not a physical-phone or emulator test. Touch reach, the actual phone keyboard, accessibility services, rotation and operating-system process termination still need phone review. Saved order data was verified by closing/reopening the storage instance; screen restoration was verified through SavedStateHandle. The optimized APK has not been installed or exercised on a device. Café-specific menu accuracy and promotion terms are unverified research data, and POS integration is outside this app's scope.
