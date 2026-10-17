# TBox Ultra: one Magisk ZIP for installation and updates

**Development prerelease:** Honda bench startup/reconnect acceptance applies to the preceding runtime baseline. This ZIP also includes a host-tested cleanup change; complete in-car audio/call and exact-ZIP device acceptance is still pending.

[Русский](ru/MAGISK_INSTALLER.md) · [Short instructions](INSTALL_RELEASE.md) · [Service status](CARLIFE_SERVICES_STATUS.md)

The current [prerelease](<https://github.com/SadykovIV/Carlife_on_box/releases/tag/v2026.10.17>) is **`Carlife_on_box-TBox-Ultra-2026.10.17-v0.2.zip`**. Use this one file for initial installation or update. It detects the supported installation already on the box and includes OEM settings integration; no separate settings or rollback ZIP is needed.

SHA256: `bc25a436996a52a43932d984e7a4ffc8e04ca43d9b50a6755d5823b5bda7f6ea`.

The MD is byte-identical to the tested Honda baseline. **57 JVM suites, 167 boot-controller tests and package rollback regressions passed.** Cold baseline: first IDR at21.7 seconds of Android uptime; hot reconnect: owner estimate5–7 seconds after plugging in. The final release adds bounded controller cleanup, tested offline. See the [validation record](RELEASE_0_2_VALIDATION.md). Real SIM/VoIP duplex, complete assistant speech and clean installation on a second physical box remain unverified.

## Supported devices

The supported profile is **TBox Ultra 1 / SM6350 (lito) / Android 15**, with the reviewed OEM startup files and MainAiBox/Settings APK pair. A matching model name alone is insufficient; the installer checks actual files. It is not an installer for every Android box.

Initial installation requires working **Magisk 30.7+ / root**, an already unlocked bootloader and the reference firmware's existing permissive SELinux mode. The installer does not unlock the device or change those settings. The ZIP contains no borrowed boot image: local `magiskboot` patches this box's own active boot. Computer-side extraction or a per-device ZIP build is unnecessary.

## Install or update

1. Copy `Carlife_on_box-TBox-Ultra-2026.10.17-v0.2.zip` to the box. When downloaded from a release, verify SHA256 against its `SHA256SUMS`.
2. Keep stable power connected. Open **Magisk → Modules → Install from storage**, select the ZIP and wait for success.
3. Reboot once. The existing OEM settings provide **Android Auto / CarPlay / Baidu CarLife** and CarLife resolution/FPS. A fresh installation defaults to **Baidu CarLife** and video **Requested by head unit**; updates preserve saved choices.
4. Connect to a ready CarLife HU. Current audio, voice, navigation and call limitations are in [service status](CARLIFE_SERVICES_STATUS.md).

The first installation saves the original active `boot_a` or `boot_b` and adds the early CarLife entry before OEM projection claims USB. The recognized TBox serial-console configuration is also normalized; unknown UART configurations are preserved and recorded. Full firmware, other partitions and the other boot slot are not flashed.

Existing universal installations receive runtime updates without rewriting boot. The supported earlier manual integration also keeps its boot integration and gets an exact pre-combined runtime/UI backup. Later updates never replace the first backup. Incompatible or unfinished transactions are reported and preserved, not deleted to make installation proceed.

The included OEM overlay provides protocol selection and the resolution/FPS page beside Baidu CarLife. Original system APK files are not overwritten. Only reviewed APK pairs are accepted; their binary distribution remains separate from the Apache-2.0 project source. The package does not add a three-mode RESET-button cycle.

ADB settings are retained. In CarLife mode USB serves the HU; use existing Wi-Fi ADB or a backup display for maintenance.

## Status and complete package rollback

Run in a rooted Android terminal or existing ADB shell:

```sh
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-package/control.sh status'
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-package/control.sh rollback'
```

Use `status` to inspect the saved branch/backup. Use `rollback` when you intend to undo the package, wait for success, then reboot. No second download is needed.

| Detected installation | What rollback restores |
| --- | --- |
| Fresh or previously universal-managed | This box's exact boot before its first universal installation, including existing Magisk; removes the managed CarLife runtime. The OEM overlay returns to its state before the first combined ZIP, or is removed if absent then. |
| Earlier manual/native integration | Exact runtime and OEM overlay **before the first combined ZIP**. Earlier CarLife integration and boot remain; this does not uninstall a pre-existing manual boot modification. |

Disabling or individually removing a delivery module is not complete package rollback. Use the command above to handle runtime and settings overlay together. This is not a factory reset and does not remove root or user applications.

Preserve `/data/adb/carlife-package/` and original backend recovery directories: `/data/adb/carlife-on-box/` for managed boot, `/data/adb/carlife-runtime-update/` for the older updater. Independently changed boot/files are reported as conflicts instead of overwritten. An interrupted boot write can require the box's recovery method; stable power is necessary during first installation.

## Build and historical packages

[scripts/build_magisk_package.py](../scripts/build_magisk_package.py) checks matching runtime payloads, embeds the existing universal/update/rollback backends and reviewed OEM overlay, and produces one ZIP plus manifest/checksum. Internal backends are not separate downloads for users of the combined package. Toolchain instructions remain in [BUILD_MD](BUILD_MD.md) and [BUILD_RUNTIME](BUILD_RUNTIME.md).

The [r6 update record](MAGISK_RUNTIME_UPDATE.md) and [r7 settings record](INSTALL_VIDEO_SETTINGS.md) describe older packages. Their results remain historical evidence; do not use their multi-package selection instructions for the current combined package.
