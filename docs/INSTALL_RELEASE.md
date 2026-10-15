# Install or roll back r6

[Русский](ru/INSTALL_RELEASE.md) · [Download release](https://github.com/SadykovIV/Carlife_on_box/releases/tag/v2026.10.15-r6)

**Choose your installer family; do not mix packages.**

| Release file | Use |
| --- | --- |
| `Carlife_on_box-TBox-Ultra-1-first-install-2026.10.15-r6.zip` | First installation on a compatible box; also updates installations created by this installer. |
| `Carlife_on_box-TBox-Ultra-1-update-2026.10.15-r6.zip` | Runtime update for the existing manually integrated reference installation. |
| `Carlife_on_box-TBox-Ultra-1-rollback-2026.10.15-r6.zip` | Reverts that runtime update; **not** a full uninstall or first-install rollback. |

The first-install family appears in Magisk as **Baidu CarLife on Android Box** (`carlife_on_box`); reuse its first-install ZIP for updates. The legacy module is **CarLife runtime update** (`carlife_runtime_update`).

## First installation

Supported profile: TBox Ultra 1, SM6350, Android 15 / SDK 35, matching OEM startup files, working Magisk **30.7+**, unlocked bootloader and an **already permissive** reference firmware. The installer checks compatibility; it does not unlock the device or change security settings.

1. Download the first-install ZIP; check SHA256 against the release's `SHA256SUMS`.
2. Keep stable power connected. In **Magisk → Modules → Install from storage**, select the ZIP and wait for success.
3. Reboot once and connect to a ready CarLife head unit. **Baidu CarLife is the initial default**; later saved choices are retained.

The ZIP saves and patches this box's own active boot on-device. It contains no borrowed boot image and does not flash the full firmware. Subsequent runtime updates preserve the first backup and do not rewrite boot. Existing ADB settings remain; USB is used for projection, so retain Wi-Fi ADB or your backup display for maintenance. This ZIP does not add a third item to the OEM AA/CarPlay menu.

## Existing manually integrated installation

Install the update ZIP through **Magisk → Modules → Install from storage** and reboot. **CarLife runtime update → Action** shows the result. This package retains the existing boot integration.

## Rollback

**First-install family:** run in a rooted Android terminal or existing ADB shell:

```sh
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-on-box/manager.sh rollback'
```

After successful restoration, reboot. This restores this box's exact pre-install boot, including Magisk, and removes CarLife. Alternatively, use **Magisk → Remove**, reboot to run removal, then reboot again into the restored boot. Keep `/data/adb/carlife-on-box/` backups.

**Legacy installation:** install the rollback ZIP through Magisk and reboot. This restores its first saved runtime; CarLife integration and boot remain. Disabling/removing the delivery module alone does not undo applied files.

## Validation

First installation is **offline-tested; hardware acceptance is pending**. r6 runtime update and native USB video/PCM passed on the development box with Mac HU; final r6 Honda recheck remains pending. See the detailed [first-install guide](MAGISK_INSTALLER.md) and [runtime-update results](MAGISK_RUNTIME_UPDATE.md).
