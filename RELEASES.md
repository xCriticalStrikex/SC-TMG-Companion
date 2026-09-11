# Release verification

## SC2 TMG Companion v1.0.0

- APK: `SC2TMG_Companion_v1.0.0.apk`
- Application ID: `com.sc2tmg.soundboard`
- Android versionName: `1.0.0`
- Android versionCode: `108`
- File size: `646,710,889 bytes`
- SHA-256: `64f6391406a6122a0c2fa2a3b31bebbbaef73963480371c214519afd887adf1f`
- Signing certificate SHA-256: `1faa61d9040a030d2d1048c324431e097e6a6501cd40c9cca9072d6ef833b5e1`

The APK is distributed through [GitHub Releases](https://github.com/xCriticalStrikex/SC2-TMG-Companion/releases/latest), not committed to this repository.

### Verify on Windows

```powershell
Get-FileHash -Algorithm SHA256 .\SC2TMG_Companion_v1.0.0.apk
```

### Verify on macOS or Linux

```sh
sha256sum SC2TMG_Companion_v1.0.0.apk
```

The output must match the SHA-256 value above exactly.
