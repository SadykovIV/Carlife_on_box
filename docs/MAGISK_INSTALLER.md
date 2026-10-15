# TBox Ultra 1: install, update and rollback with Magisk

[Русский](ru/MAGISK_INSTALLER.md) · [Home](../README.md)

The installer adds native CarLife to the supported **TBox Ultra 1 / SM6350 Android 15** profile without replacing the complete firmware or erasing applications and user data. The package is built for the target box's own Magisk-patched boot image. It is not a boot image copied from another box.

**Status:** installer and recovery workflows are checked offline. Installation, update and rollback on a second physical TBox have not been tested. The previously accepted development runtime and its artifacts are retained.

## Install

1. Obtain the `Carlife_on_box-TBox-Ultra-1-install.zip` built for your current firmware, boot image and active slot.
2. Open **Magisk → Modules → Install from storage**, select the ZIP and wait for successful completion. Keep power connected while it saves and writes boot.
3. Reboot once. **Baidu CarLife is the default**; connect the box to a ready CarLife head unit.

The installer verifies the device profile before writing. It saves the original boot, installs the runtime and adds early init hooks to the current boot partition. Android system partitions, the other boot slot, existing AA/CarPlay configuration and ADB settings are not changed. The hooks select CarLife before the OEM projection service takes USB; this does not use a late `service.sh` takeover.

In CarLife mode, USB serves the head unit; this does not promise simultaneous USB ADB. Use an existing Wi-Fi ADB connection or your backup AA/CarPlay display for maintenance.

This profile requires working Magisk 30.7 or newer/root, an already unlocked bootloader and the reference firmware's existing permissive SELinux mode. The installer does not change these settings. Existing independently installed CarLife files are preserved; use their existing updater instead of installing over the development baseline.

The module's **Action** button displays status. It does not switch USB or reboot. This minimal package does not modify proprietary Settings/launcher APKs, so it does not add a third entry to their AA/CarPlay menu or change RESET-button behavior. Optional OEM UI integration remains a separate [source build](BUILD_MD.md).

## Update

Install the matching `Carlife_on_box-TBox-Ultra-1-update.zip` through Magisk, then reboot. The current session keeps running until reboot. The update replaces the runtime payload, preserves the saved selection and original boot backup, and does **not** write boot again.

Updates must retain the boot-pinned resolver and selector entry points. A change to those entry points is a new boot integration, not a runtime update. Ordinary MD, controller and observer updates use the existing integration.

## Full rollback

For the shortest rollback, run this command in an Android terminal with root, or inside an existing ADB shell:

```sh
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-on-box/manager.sh rollback'
```

After it reports that the original boot was restored, reboot. The module is removed and its runtime/selection files are cleaned up. **Original** means the exact boot saved before this installer, including the Magisk installation that was already present. It does not factory-reset the box or remove root.

You can also choose **Remove** in Magisk. Magisk runs the removal hook on the next boot; that hook restores boot. Reboot once more to actually load the restored image.

Turning the module **off** in Magisk and rebooting temporarily returns to the existing OEM AA/CarPlay mode. This leaves the CarLife boot hooks and recovery files in place; it is not full rollback.

Recovery records are retained outside the module at `/data/adb/carlife-on-box/`, including `original.img`, the manifests, recovery manager and `lifecycle.log`. A later Magisk removal cannot delete the only boot backup. If boot was changed independently after installation, rollback preserves it rather than overwriting that newer image; inspect the reported mismatch using the saved backup. An interrupted boot-partition write may require the device's recovery method.

If initial installation failed before Magisk registered the module, run the saved rollback command, reboot, then run the same command again to finish cleaning its files. There may be no module removal hook in that case.

## Build a package

Package building runs on the computer and does not access a device. Python 3.11+ is enough once the [runtime and selector](BUILD_RUNTIME.md) have been built. Supply the target's current Magisk boot, original `init.rc` and `sd-reverse.sh`, firmware fingerprint and active slot. No complete firmware archive, proprietary APK or signing key is needed by this builder.

With an existing authorized ADB connection, collect those inputs without changing the box:

```sh
adb shell getprop ro.boot.slot_suffix
adb shell getprop ro.build.fingerprint
# Use the slot reported above; this example is for _a.
adb exec-out su -c 'cat /dev/block/by-name/boot_a' > target-magisk-boot.img
adb pull /system/etc/init/hw/init.rc target-init.rc
adb pull /system/bin/sd-reverse.sh target-sd-reverse.sh
```

Compute the image's SHA-256 (`shasum -a 256 target-magisk-boot.img` on macOS, or `sha256sum` on Linux) and use the collected values below.

```sh
python3 scripts/build_magisk_installer.py install \
  --current-image /path/to/target-magisk-boot.img \
  --expect-current-sha256 YOUR_BOOT_SHA256 \
  --init /path/to/system/etc/init/hw/init.rc \
  --oem-script /path/to/system/bin/sd-reverse.sh \
  --fingerprint 'TARGET_RO_BUILD_FINGERPRINT' \
  --slot-suffix _a \
  --runtime build/runtime/runtime/boot-native-runtime.tar \
  --runtime-manifest build/runtime/runtime/bundle-manifest.json \
  --selector-release build/runtime/selector \
  --output build/magisk-install
```

Use the actual slot (`_a` or `_b`). The output contains the ZIP, `manifest.json` and `BUILD.json` with hashes. Preserve the install ZIP: later updates refer to its boot integration.

```sh
python3 scripts/build_magisk_installer.py update \
  --base-package build/magisk-install/Carlife_on_box-TBox-Ultra-1-install.zip \
  --runtime build/runtime-new/runtime/boot-native-runtime.tar \
  --runtime-manifest build/runtime-new/runtime/bundle-manifest.json \
  --selector-release build/runtime-new/selector \
  --output build/magisk-update
```

An update ZIP contains no boot image. Building the installer does not make an untested audio candidate hardware-accepted; choose the intended runtime explicitly. Use a fresh output directory for each build.

The module follows Magisk's [installer and boot-script lifecycle](https://topjohnwu.github.io/Magisk/guides.html). Initial installation and complete rollback write only the selected boot partition; runtime updates are data-only.
