# Fresh Android release build record

Date: 2026-09-11

## Build

Command:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleRelease --rerun-tasks --no-daemon --stacktrace
```

Result: `BUILD SUCCESSFUL`; 49 tasks executed.

Toolchain:

- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Kotlin Android/Compose plugin 2.0.21
- Android compile/target SDK 35; minimum SDK 26
- JetBrains Runtime 21.0.10; JVM bytecode target 17
- Windows 10 amd64

The compiler emitted existing deprecation warnings for non-auto-mirrored Compose icons and two existing `MusicPlayer.kt` always-true-condition warnings. There were no errors. Lint vital completed successfully.

## Fresh artifact

- File: `artifacts/v1.0.0-r18/SC2TMG_Companion_v1.0.0-unsigned.apk`
- Bytes: `646702697`
- SHA-256: `8e2c8f99890fd8c2869c3d9e9aee8e68467a06bb7bc7791552bf5139356e03ab`
- Package: `com.sc2tmg.soundboard`
- versionName: `1.0.0`
- versionCode: `108`
- Signing status: unsigned release variant

The permanent public signing key is intentionally absent from Git and was not found in the local SC2TMG project locations. The fresh artifact was not signed with a substitute key because doing so would prevent upgrade installation over the public app. The APK itself remains ignored by Git; `SHA256SUMS.txt` records its digest.

## Public-to-fresh DEX comparison

| Entry | Public APK | Fresh canonical build |
| --- | --- | --- |
| `classes.dex` | `32310912` bytes; `50fe8b172cedc952477bd539cd4ea0f9d508e676f60d63029f0180ff31117f77` | exact match |
| `classes2.dex` | `11462508` bytes; `3acfcde240c0b5382ec7433bc5dd355595defab83b74f1998819a1387d51c930` | `11457888` bytes; `e3b117ffe4b0bef05e3b331aa46094c9a5ffacf5284109f7ee3e69153501e76a` |

The different `classes2.dex` binary is the recorded reproducibility mismatch. Descriptor, method-signature, and unique-string inventories match exactly, and the public binary contains the final R18 feature fingerprints listed in `BASELINE.md`.

No install, upgrade, or physical-device QA was performed in this stage.
