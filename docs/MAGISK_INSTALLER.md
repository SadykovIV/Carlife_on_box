# TBox Ultra 1: install, update and rollback with Magisk

[Русский](ru/MAGISK_INSTALLER.md) · [Home](../README.md)

For release r6, download `Carlife_on_box-TBox-Ultra-1-first-install-2026.10.15-r6.zip`. It is the universal package described below, renamed to distinguish it from the legacy runtime updater. See the [short package selection and rollback guide](INSTALL_RELEASE.md).

**One ZIP installs native CarLife on matching TBox Ultra 1 / SM6350 Android 15 boxes.** You do not need to extract boot on a computer or rebuild the ZIP for each box. During installation, the module reads the box's own active boot, saves it and adds early CarLife startup with Magisk's local `magiskboot`. The ZIP contains **no boot image from another device**.

**Status:** the installer is checked offline; installation, update and rollback on a second physical TBox remain to be tested. The accepted development runtime and its artifacts are preserved.

## Install or update

1. Copy `Carlife_on_box-TBox-Ultra-1-universal.zip` to the box.
2. Open **Magisk → Modules → Install from storage**, select the ZIP and wait for success. Keep power connected while boot is backed up and written.
3. Reboot once, then connect to a ready CarLife head unit. **Baidu CarLife is selected by default on a fresh installation.**

For an update, install the new universal ZIP the same way. The installer recognizes its existing installation: it stages the runtime update for the next reboot and preserves your selection and first boot backup. **Runtime updates do not write boot again.** They must keep the installed early entry points; changing those is a new boot integration.

The initial installation writes only the active boot partition (`boot_a` or `boot_b`, detected automatically). Android system partitions, the other boot slot, apps, user data, existing AA/CarPlay configuration and ADB settings stay unchanged. CarLife starts before the OEM projection service takes USB, without a late `service.sh` takeover.

The ZIP checks the supported firmware's actual OEM startup files, board and Android version. A different fingerprint or a different original boot hash alone does not require another package. It requires working Magisk 30.7 or newer/root, an already unlocked bootloader and the reference firmware's existing permissive SELinux mode; it does not change these settings. An independently installed development baseline is preserved rather than adopted silently.

In CarLife mode, USB serves the head unit. For maintenance, use existing Wi-Fi ADB or a backup AA/CarPlay display. The module's **Action** button displays status. This package does not replace proprietary Settings/launcher APKs, add a third item to their AA/CarPlay menu, or change RESET-button behavior. Optional OEM UI integration remains a separate [source build](BUILD_MD.md).

## Full rollback

In an Android terminal with root, or inside an existing ADB shell, run:

```sh
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-on-box/manager.sh rollback'
```

After it reports that the original boot was restored, reboot. The module and its runtime/selection files are removed. **Original** means this box's exact boot before the first installation, including its existing Magisk. No factory reset or removal of root is performed.

Alternatively, choose **Remove** in Magisk. The removal hook restores boot at the next startup; reboot once more to load that restored image. **Disabling** the module and rebooting only returns temporarily to the existing OEM AA/CarPlay mode; it is not full rollback.

The recovery manager, `original.img`, per-device `boot.env` and logs stay at `/data/adb/carlife-on-box/`, outside the module. If another tool changed boot after installation, rollback reports the mismatch and preserves that newer image. An interrupted boot-partition write may require the device's recovery method.

If boot was written but Magisk did not finish registering the module, use the saved rollback command, reboot and repeat it to finish cleanup; there may be no removal hook left. A rejected profile or failed local patch does not write boot.

## Build the ZIP

Build once for the supported firmware profile, then use the ZIP on matching boxes. The computer needs Python 3.11+, the built [runtime and selector](BUILD_RUNTIME.md), and the profile's OEM `init.rc` and `sd-reverse.sh`. **No target boot image, fingerprint, slot selection, complete firmware archive, proprietary APK or signing key is required.** The installed Magisk provides `magiskboot`; no extra Android toolchain is installed.

```sh
python3 scripts/build_magisk_installer.py universal \
  --init /path/to/system/etc/init/hw/init.rc \
  --oem-script /path/to/system/bin/sd-reverse.sh \
  --runtime build/runtime/runtime/boot-native-runtime.tar \
  --runtime-manifest build/runtime/runtime/bundle-manifest.json \
  --selector-release build/runtime/selector \
  --output build/magisk-universal
```

The output contains `Carlife_on_box-TBox-Ultra-1-universal.zip`, `manifest.json` and `BUILD.json` with hashes. For a runtime update, run the same command with the new runtime and a fresh output directory. Choose the intended runtime explicitly: packaging an untested audio candidate does not make its audio hardware-accepted.

The module follows Magisk's [installer and boot-script lifecycle](https://topjohnwu.github.io/Magisk/guides.html). “Universal” here means one package for the supported firmware profile, not arbitrary Android boxes.
