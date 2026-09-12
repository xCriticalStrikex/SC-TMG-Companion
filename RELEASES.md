# Release verification

## SC TMG Companion v1.0.0

- Canonical internal source revision: `R18 final`
- Canonical Git tag: `v1.0.0`
- Signed annotated tag object: `46055474f8faa5e9e9a0024ba069f25f3898ef2d`
- Canonical Git commit: `d50a49f2a311482cc91712fb0b1391196c209867`
- Canonical Git tree: `612e6c1abaa2bd050ae1172cb920f1ae03db5eeb`
- APK: `SC2TMG_Companion_v1.0.0.apk`
- Application ID: `com.sc2tmg.soundboard`
- Android versionName: `1.0.0`
- Android versionCode: `108`
- File size: `646,710,889 bytes`
- SHA-256: `64f6391406a6122a0c2fa2a3b31bebbbaef73963480371c214519afd887adf1f`
- Signing certificate SHA-256: `1faa61d9040a030d2d1048c324431e097e6a6501cd40c9cca9072d6ef833b5e1`

The APK is distributed through [GitHub Releases](https://github.com/xCriticalStrikex/SC-TMG-Companion/releases/latest), not committed to this repository.

The source baseline and R18 reconciliation are documented in [docs/release/v1.0.0-r18/BASELINE.md](docs/release/v1.0.0-r18/BASELINE.md). The clean local release-variant build is documented separately in [docs/release/v1.0.0-r18/BUILD_RECORD.md](docs/release/v1.0.0-r18/BUILD_RECORD.md). That fresh artifact is unsigned because the permanent release key is not stored in source control.

### Verify on Windows

```powershell
Get-FileHash -Algorithm SHA256 .\SC2TMG_Companion_v1.0.0.apk
```

### Verify on macOS or Linux

```sh
sha256sum SC2TMG_Companion_v1.0.0.apk
```

The output must match the SHA-256 value above exactly.
