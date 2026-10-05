# Executed validation — 0.3.0

Verified October 4, 2026 on macOS arm64 with a workspace-local JDK 17 and Android SDK. The checksum-verified Gradle 8.13 wrapper executed:

```sh
./gradlew --no-daemon --offline testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Result: **BUILD SUCCESSFUL**. All **39 tests passed**, with zero failures or errors.

| Check | Result |
|---|---|
| Pure menu/order rules | 17 passed; every enabled offer/portion, exact Bottled roster, all Frozen selections, required drink sizes, Cup/Bowl soups, independent counts/dozens, required sides, queue numbering and legacy migration |
| File DataStore | 9 passed; concurrent writes, reopening, schema-1 migration persisted once, multiple-dozen persistence, Undo, allocation ID preservation, revision conflicts and corruption preservation |
| ViewModel | 7 passed; default collapsed sections, Hot Coffee & Tea on reopen/next car, restored exact dozen focus, Undo target, buffered save failures, navigation anchors and SavedStateHandle restoration |
| Compose interactions | 6 passed; Android 15/API 35 in Robolectric, native graphics, 320 × 640 dp configuration |
| Android lint | Passed; zero reported code/resource issues |
| Debug APK | Version 0.3.0 / code 3; development signature verified by Android `apksigner` |
| Release APK | Built with R8 optimization and resource shrinking; unsigned |

Native checks exercise whole-tile bagel addition, the 13-bagel limit and enabled subtraction, summary editing of folded sections, two numbered meal instances, exact selection return within 1 px, and saved-state recreation without replaying an old anchor. New scenarios verify independent Individual/Dozen 1/Dozen 2 counts, cream cheese Single/Tub, dozen removal and Undo, Hot Coffee & Tea, explicit meal drink sizes, size-free Bottled and Frozen selections, and Cup/Bowl You Pick Two soup capture.

Screens were rendered through Android views with Robolectric native graphics and visually inspected:

- [Six closed boxes](verification/entry.png)
- [Dozen at 13](verification/bagel-thirteen.png)
- [Dozen summary edit return](verification/bagel-edit-return.png)
- [Multiple dozen targets](verification/multiple-dozens.png)
- [Cream cheese](verification/cream-cheese.png)
- [Bottled](verification/bottled.png)
- [Frozen & Smoothies](verification/frozen.png)
- [You Pick Two soup sizes](verification/soup-bowl.png)
- [Growing order list](verification/ordered-list.png)
- [Selection return](verification/selection-return.png)
- [Register queue](verification/queue.png)
- [Local APK metadata and signature](verification/apk-verification.txt)

The APK checksum is recorded in `verification/apk-verification.txt`. CI independently builds and signs its public download; the release checksum identifies that APK.

Lint keeps code/resource warnings as errors. Four advisory dependency/tool/target-SDK checks remain excluded for the deliberately pinned toolchain. One adaptive-icon folder exclusion remains; there is no broad lint baseline.

These are JVM/native-renderer checks. A physical phone or emulator has not been used. Touch reach, large fonts, accessibility services, rotation and operating-system process termination still need phone review. File storage was closed/reopened and navigation/focus restored through SavedStateHandle. Cafe menu accuracy and promotion terms still require local verification. POS integration is outside this app's scope.
