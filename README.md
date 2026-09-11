# SC2 TMG Companion

**Free fan-made Android companion for StarCraft: The Miniatures Game**

SC2 TMG Companion is a labour-of-love companion app built to make games of StarCraft: The Miniatures Game feel much more like StarCraft at the table, while also handling the practical companion-app side of things.

## Download

### [Download the latest Android release](https://github.com/xCriticalStrikex/SC2-TMG-Companion/releases/latest)

The installable APK is published through GitHub Releases. APK files are intentionally not committed to the source tree.

## Source and release integrity

**The APK published in Releases is built from the source in this repository.**

Current public release:

- Version: **1.0.0**
- Canonical internal source revision: **R18 final**
- Android versionCode: **108**
- Application ID: `com.sc2tmg.soundboard`
- APK filename: `SC2TMG_Companion_v1.0.0.apk`
- SHA-256: `64f6391406a6122a0c2fa2a3b31bebbbaef73963480371c214519afd887adf1f`

To verify a downloaded APK in PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\SC2TMG_Companion_v1.0.0.apk
```

The resulting hash should match the SHA-256 value above. Release history and verification details are also recorded in [RELEASES.md](RELEASES.md).

The canonical source/APK lineage, complete input hash manifest, R18 documentation reconciliation, and fresh-build evidence are recorded in [the V1.0.0 / R18 baseline audit](docs/release/v1.0.0-r18/BASELINE.md).

## Build from source

Requirements:

- Android Studio with its embedded JDK, or JDK 17+
- Android SDK 35
- An internet connection on the first build so Gradle can download dependencies

Clone the repository, open its root folder in Android Studio, allow Gradle sync to finish, and run the `app` configuration. From a Windows terminal, the equivalent debug build is:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The debug APK will be written beneath `app/build/outputs/apk/debug/`.

Release signing material is deliberately excluded from this repository. A locally produced unsigned or differently signed APK will not be byte-for-byte identical to the published release APK.

## Repository layout

```text
SC2-TMG-Companion/
├── app/
│   ├── build.gradle.kts
│   └── src/
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── README.md
├── RELEASES.md
└── .gitignore
```

## Features

- Match tracking and round guidance
- StarCraft unit, building, weapon and tactical-card audio / soundboard tools
- Faction-aware SC1 / Brood War / SC2 music through Jimmy's Jukebox
- Searchable Field Manual / quick rules access
- Terran, Protoss and Zerg themes and presentation
- Unit collection tools
- Cinematic game-start sequences
- Victory and GG presentation
- Far too many little StarCraft details 😅

## Security and privacy

The public repository does not include signing keys, keystore passwords, `local.properties`, API credentials, generated build directories, APKs, or Android App Bundles. If you believe a secret has been exposed, report it privately to the repository owner instead of opening a public issue containing the secret.

## Licensing and fan project disclaimer

**This project is not affiliated with or endorsed by Archon Studio or Blizzard Entertainment.**

StarCraft and related names, imagery, audio, and other game assets belong to their respective rights holders. They are included only as part of this independent, non-commercial fan-made companion project. No rights to third-party material are claimed or granted.

No separate open-source licence is currently granted for the original source code. The repository is public so that users can inspect the app before installing it. Please contact the repository owner before redistributing or modifying the source.

## Feedback

Bug reports, suggestions and general feedback are very welcome through GitHub Issues.

---

**GLHF ❤️**

*x Critical Strike x*
