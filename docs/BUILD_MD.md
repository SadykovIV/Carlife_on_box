# Build the Android MD

[English](BUILD_MD.md) · [Русский](ru/BUILD_MD.md)

The MD is the Android side of the connection. The native build combines shared code in `android-md/src` with `tools/boot_native/md/src`; the latter replaces the shared `MdMain` and adds early-boot readiness and native-session handling. Building only the shared layer does not reproduce the installed native runtime.

## Requirements

- Python 3.9 or later; only the standard library is used.
- JDK 17 (`java` and `javac`).
- Android SDK platform 35 (`android.jar`).
- D8, either from installed Android SDK Build Tools or an R8 JAR exposing `com.android.tools.r8.D8`.

Install these dependencies through their upstream distribution and accept applicable licenses yourself. The build script downloads nothing and does not call ADB, install an APK, change USB or touch a device.

## Build and test

From the repository root:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
python3 scripts/build_md.py
```

The script locates platform 35 and the newest installed Build Tools D8 under that SDK. To select exact files:

```sh
python3 scripts/build_md.py \
  --java-home /path/to/jdk-17 \
  --android-jar /path/to/android-sdk/platforms/android-35/android.jar \
  --r8-jar /path/to/r8-8.3.37.jar
```

`--d8 /path/to/build-tools/VERSION/d8` is an alternative to `--r8-jar`. `JAVA_HOME`, `ANDROID_SDK_ROOT` (or `ANDROID_HOME`), `D8` and `R8_JAR` are supported. Choose only one D8 mechanism. Paths may contain spaces when shell-quoted.

Output:

- `build/md/md-boot-native.jar`: DEX archive, entry point `org.carlife.hulab.MdMain`.
- `build/md/manifest.json`: source/tool hashes and the test result.
- `build/md/*.stdout.txt`, `*.stderr.txt`, `steps.json`: local build evidence.

Use `--output /path/to/output` to keep a separate build. Test-only mode compiles both source layers and runs all 57 JVM suites without requiring D8:

```sh
python3 scripts/build_md.py --test-only
```

The DEX minimum API is 21; this is a bytecode setting, **not** a claim that the display-mirroring and privileged-input integrations work on every Android version. The measured runtime target is Android 15 on the documented SM6350 firmware.

## What the tests establish

The host suite covers wire framing and fragmented packets, RESET and writer/watchdog races, encoder teardown/draining, source lease ownership, SPS/PPS and access-unit handling, the OEM CSD guard, geometry/touch mapping, accessory ownership/recovery, PAUSE/heartbeat, asynchronous touch dispatch, feature/touch diagnostics and video logging policy. Android API stubs under test directories are host-only and never enter the DEX.

Three synthetic gray-image libx264 fixtures are stored as `.h264.hex` text. Their decoded SHA256 values are checked against `android-md/tests/fixtures/provenance.json` before tests. They contain no device recording. The baseline publication changed only the fixture loader. The current audio/multitouch candidate also changes production source; see [candidate status](AUDIO_CANDIDATE.md).

The original publication at commit `3b344d45ae34df215166385ba0c65f195652f5d8` passed all **26 suites**. Using the existing JDK 17.0.16, API 35 and R8 8.3.37 toolchain produced the exact installed baseline:

```text
347c6ef1fdf0fa25d0c8ae4bd333c8cfce2ed6ed6d7c70cba9b6cbd1c55b87dd  md-boot-native.jar
```

For **v0.2**, all **57 JVM suites** passed and the packaged MD was reproduced byte for byte with JDK 17.0.16, API 35 and R8/D8 8.3.37 (compiler JAR SHA256 `900dfbc649519969fc5a4c7520d6b7355338e565fa1249874e0190b8d61b1199`):

```text
a1ad080b9606390841c10aef756aa6cf90a8636e778fa1f9fd0b87a7f8cbf06a  md-boot-native.jar
```

The earlier `347c6ef1…` hash is historical. D8 9.4.14 also builds the source, but produces different DEX bytes and was not used for the packaged MD.

A different JDK or D8 version can change artifact bytes; compare the generated manifest. A successful host build does not prove USB, display capture, injection permissions or a particular car head unit works.

## Runtime integration

Pass the resulting JAR to the board-specific runtime build/deployment described in the main guide. This script deliberately creates no signed APK. The retained `android-md/AndroidManifest.xml` and `manifest_binary.py` describe the historical neutral test Activity; neither is needed by the native JAR and neither installs CarLife as an ordinary phone app.

The tested native path mirrors the actual Android display through EGL into a MediaCodec Surface, sends H.264 through direct USB accessory I/O, and injects CarLife input with the process's existing privileges. It requires the board's USB/accessory support, compatible mirroring APIs and appropriate input/capture permissions. Do not remove the source lease, RESET generation checks or OEM CSD guard to make an incompatible board appear supported. The exceptional CSD source selection is tied to a measured OEM library hash; it must be evaluated for the target firmware.

An opt-in output-audio candidate is [implemented and tested offline](AUDIO_CANDIDATE.md). USB microphone/voice, separate TTS and call paths are implemented; current acceptance is recorded in [service status](CARLIFE_SERVICES_STATUS.md). Mandatory encrypted-media/legacy-authentication profiles remain unsupported. Multi-pointer parsers exist, but the tested Honda head unit did not provide confirmed second-pointer coordinates in the observed CarLife mode. These are separate from a successful build.

## Optional: OEM selector APK integration

[`scripts/build_oem_selector.py`](../scripts/build_oem_selector.py) adds the existing CarLife choice to the two **exact tested OEM APKs**. It reuses the project-owned `PatchDex.java` and `SignPlatform.java`; OEM applications, SDK/dependency JARs and signing credentials are not included. Use the source for adapting another firmware, not a forced hash bypass.

Supply your own matching input files and certificate/private key. The private key must genuinely match the firmware certificate; the tool verifies both and verifies the final APK signature. It does not replace the APK signer with an unrelated development key.

```sh
python3 scripts/build_oem_selector.py \
  --main-apk /private/firmware/MainAiBox.apk \
  --settings-apk /private/firmware/SudingSettings_chelianyi_new.apk \
  --android-jar /path/to/android-sdk/platforms/android-35/android.jar \
  --r8-jar /path/to/r8-8.3.37.jar \
  --dexlib-jar /path/to/smali-dexlib2-3.0.9.jar \
  --guava-jar /path/to/guava-33.4.0-jre.jar \
  --apksig-jar /path/to/apksig-8.3.2.jar \
  --java-home /path/to/jdk-17 \
  --signing-reference /private/signing-reference \
  --output build/oem-selector
```

`--signing-reference` contains `platform.x509.pem` and `platform.pk8`. Use a fresh output directory. The exact checked SHA256 values are:

| Input | SHA256 |
| --- | --- |
| MainAiBox APK | `09b2f141f63a7ae016a1a7a43dd52be853b044dfb985d058c348fdaca3714b85` |
| SudingSettings APK | `8df7ba4a1785c472fa365336597eadeefe0958ea205920359a1833d8f78230fb` |
| Certificate, DER encoding | `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8` |

The tool checks the expected wrapped-method count and that unrelated APK entries, including manifests, resources and native libraries, remain byte-identical. It writes `MainAiBox-selector.apk`, `SudingSettings-selector.apk` and a local build manifest. Both output signatures were verified during publication preparation. This is an offline patch build; installation and target-device compatibility are separate. The root selector daemon/runtime bundle is built separately with `scripts/build_runtime.py`.

## r7 exact runtime reproduction

The r7 runtime was rebuilt from the release source with JDK 17.0.16, Android API 35 and **R8/D8 8.3.37**. Its JAR matches the tested `video4` artifact byte for byte:

```text
921134a31f6bc403ae6b8e66f4c93741d327a0138349068e1e8f52d9aa861754  md-boot-native.jar
```

Use `--r8-jar` to select that compiler explicitly. The R8 bundled in a newer SDK may produce different DEX bytes even when the source and all tests match. The r7 package contains the exact tested artifact. For the accompanying Settings-only module, see [Build the video settings overlay](BUILD_VIDEO_SETTINGS.md).
