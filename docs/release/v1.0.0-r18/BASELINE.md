# V1.0.0 / R18 canonical source baseline

Status: established and verified on 2026-09-11 without changing application source, assets, resources, or build configuration.

## Canonical Git identity

- Release tag: `v1.0.0`
- Release commit: `46055474f8faa5e9e9a0024ba069f25f3898ef2d`
- Release tree: `612e6c1abaa2bd050ae1172cb920f1ae03db5eeb`
- Public identity: `com.sc2tmg.soundboard`, versionName `1.0.0`, versionCode `108`
- Internal source revision: R18 final, including the post-R18 finishing pass described below

The release tag and the pre-audit `main` HEAD (`d7c745cc509d6ecab65d979a1b06722a3cf7b22a`) resolve to the same Git tree. The extra `main` commit changes no files.

`CANONICAL_INPUTS_SHA256.csv` records SHA-256 and byte length for all 4,577 tagged application/build inputs: every file under `app/src/` plus the Gradle build scripts and wrapper files used by the release build. Its own SHA-256 is:

`30e35e1dce06a161fa596f9a411885bea9f3df119ae0df317c1944a1aaa15e87`

## Released APK correspondence

Reference public APK:

- Filename: `SC2TMG_Companion_v1.0.0.apk`
- Bytes: `646710889`
- SHA-256: `64f6391406a6122a0c2fa2a3b31bebbbaef73963480371c214519afd887adf1f`
- Signing certificate SHA-256: `1faa61d9040a030d2d1048c324431e097e6a6501cd40c9cca9072d6ef833b5e1`
- APK Signature Scheme: v2

Exact package-to-tree findings:

- All 4,531 application assets in the APK match `app/src/main/assets/` by relative path, byte length, and SHA-256.
- There are no source-only or changed assets. The APK has two additional generated profile entries: `assets/dexopt/baseline.prof` and `assets/dexopt/baseline.profm`.
- The canonical tree matches all 400 files contained in `SC2TMG_MASUME_SYNC_2026-09-10_142628.zip` byte-for-byte. That preserved handoff has SHA-256 `06ca9dec285f3a80e719bf14564c3f98958c109b7b5a8be643c483bb3290c0b3` and predates the signed APK by approximately 18 minutes.
- The public DEX contains the late finishing-pass fingerprints `playLaunchAmbience`, `fadeInMenuFromSilence`, `StartGameDoorRevealOverlay`, `welcome_corridor_ambience.ogg`, and `welcome_spark_crackle.wav`.
- A clean rebuild has the same 7,698 class descriptors, 74,752 method signatures, and 63,804 unique DEX strings as the public APK. No class, method, or string exists on only one side.
- The public APK embeds Android Gradle Plugin 8.7.3 metadata, but its version-control record says `NO_SUPPORTED_VCS_FOUND`; it was built from the preserved non-Git working tree and therefore contains no commit identifier.

The APK's compiled payload is not byte-reproducible from a clean rebuild: `classes.dex` is identical, while `classes2.dex` differs in byte length and SHA-256. This is recorded as a reproducibility mismatch, not hidden as a pass. The preserved sync, exact packaged assets, matching compiled inventory, and late-feature fingerprints identify the tagged tree as the corresponding released source, but there is no signed build attestation capable of proving every compiled instruction back to a Git object.

## R18 documentation reconciliation

The legacy R18 postcondition manifest describes the earlier R18 finished worktree, not the final source used for V1.0.0. It is stale for three files:

| File | Legacy R18 bytes / SHA-256 | Canonical V1.0.0 bytes / SHA-256 |
| --- | --- | --- |
| `MainActivity.kt` | `482316` / `8e03b1e92052a5a8e034bd8c2befac89e5cc11af947f48666dfd8a3fa437a87f` | `484446` / `86d78b0b839817f452df66c87f0e83d3be60b17a4fbaa9e2ee859d079dba761c` |
| `MusicPlayer.kt` | `33980` / `30ae80d9aa585aee463aacf43c6464cafd59eb4df7ab27a2b708527c6cb762f2` | `35364` / `9ea0c9c1419b835b07372e2c1e03763f0e65b94c3b28bd196c1ac889a617b1b4` |
| `SoundPlayer.kt` | `59612` / `05669c90d1230dce75109b706ae255f78b8ec4d21b8e1c7961a1424fc32a3c89` | `63407` / `9999d856549bf22f48a9aaf9545f91995f6787a67dac8992ecf40d080391e021` |

The final differences are intentional release finishing work already present in the public APK:

- continuous, full-viewport START/GAME door motion and faction-badge sizing corrections;
- cold-launch title music fade-in;
- dedicated low-level corridor ambience, timed fade, and restrained spark cue.

The old root README in the working project also named R12. Git's release README and this record supersede that stale patch-era statement. No application file was changed to perform this reconciliation.

## Stage boundary

This baseline contains Android V1.0.0 only. No Windows implementation, platform abstraction, layout redesign, or behavior change is included.
