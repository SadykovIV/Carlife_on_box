# Build the runtime and reference system overlay

[Русский](ru/BUILD_RUNTIME.md) · [Home](../README.md)

This produces local build artifacts. It does not connect to a device, install files or flash partitions. The controller and early init templates target the reference SM6350 firmware; read [Porting](PORTING.md) before using them on another board.

## 1. Build the MD

Follow [BUILD_MD.md](BUILD_MD.md). The expected output is `build/md/md-boot-native.jar`. The historical reference JAR at commit `3b344d45ae34df215166385ba0c65f195652f5d8` can be reproduced with JDK 17, API 35 and R8 8.3.37; it has SHA-256 `347c6ef1fdf0fa25d0c8ae4bd333c8cfce2ed6ed6d7c70cba9b6cbd1c55b87dd`.

## 2. Build the observer, runtime archive and selector

Requirements: Python 3.11+, JDK 17, Android API 35 `android.jar`, R8/D8 JAR, Clang with the AArch64 target and LLVM LLD. Supply tools from your own installation. The small observer is freestanding and does not need an Android NDK sysroot.

```sh
python3 scripts/build_runtime.py \
  --md-jar build/md/md-boot-native.jar \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  --r8-jar /path/to/r8.jar \
  --java-home "$JAVA_HOME" \
  --clang /path/to/clang \
  --ld-lld /path/to/ld.lld \
  --output build/runtime
```

Use a new output directory. Outputs include:

- `runtime/boot-native-runtime.tar` and `runtime/bundle-manifest.json`;
- `selector/selector-daemon.jar`, `selector-control.sh`, `selector-daemon.sh` and a hash manifest;
- `uevent-watch` and `BUILD.json`.

These are components, not an installable firmware. The selector daemon is a privileged local endpoint. `OemSelectorUi.java` is the reference integration source; merely building its DEX does not add a row to an OEM Settings APK. The optional OEM patch builder is described in [BUILD_MD.md](BUILD_MD.md).

For a separate audio candidate, use `--audio-source remote-submix` and a distinct `--runtime-version`; see [candidate instructions](AUDIO_CANDIDATE.md). These staging options do not make a custom resolver compatible with the pinned system migration below.

## 3. Generate the exact reference system overlay

You need your own extracted original `init.rc` and `sd-reverse.sh`. No OEM firmware/APKs or signing keys are included in this repository.

The reference source package was:

`10.05.2026_FIXCARCAR_27.03.2026_TBox_Ultra_1_2_6350_Android 15_-_TG_-_@aibox.zip`

SHA-256: `e71f6640a41df2d3456761ccf3244706c23aab04bb316555dfbc808b7d17dbf3`.

```sh
python3 integration/sm6350/build_integration.py \
  --runtime build/runtime/runtime/boot-native-runtime.tar \
  --runtime-manifest build/runtime/runtime/bundle-manifest.json \
  --selector-release build/runtime/selector \
  --init /path/to/extracted/system/etc/init/hw/init.rc \
  --oem-script /path/to/extracted/system/bin/sd-reverse.sh \
  --default-selection carlife \
  --output build/system-overlay
```

The builder verifies the exact original init SHA and the resolver migration. It produces `files/`, `overlay-map.json`, `init.rc.patch`, a read-only data preflight script and `BUILD.json`. The map includes ownership, modes and SELinux labels.

The provisioning code seeds the default only when its own data is absent. It preserves an existing valid selection. Its only built-in migration is the known resolver `b97…` → `6add…`; unknown existing runtime data is preserved and rejected. The old source resolver deliberately still requires the disabled historical pilot; the system builder transforms it for a fresh installation where that pilot does not exist. Do not deploy that raw resolver as a universal standalone installer.

## 4. Integrate with the target firmware

Use the target's supported image tooling to apply the map and the reviewed OEM selector patch. Rebuild filesystem metadata, AVB descriptors and OTA signatures consistently with the original firmware. The public source repository does not include the private experiment's complete OTA packager or a prebuilt OEM image. Do not infer a flash command from the existence of the generated overlay.

The local full-image experiment preserved kernel, vendor, recovery, DTBO, product, system_ext and odm, and changed boot, system and their AVB metadata. It restored the common Qualcomm radio bootstrap and removed the actual serial-console boot configuration. Its offline success is documented in [STATUS.md](STATUS.md); full-image boot acceptance remains outstanding.

For enforcing SELinux, define and validate the required service domains, device access and input/display permissions. These reference templates use the original experiment's init-domain services; they are not a validated enforcing policy and do not disable SELinux.

## Checks before installation

Run the offline regressions in the main README. Keep original firmware, your own boot image, the generated hashes and a device-specific recovery method. Verify that the boot dispatcher is installed before the first OEM USB owner runs. Check first power-on with the head unit already ready, then launcher/app/keyboard/input/reconnect, selection persistence and cellular networking. A runtime deployed by another boot mechanism does not prove the new system image will boot.
