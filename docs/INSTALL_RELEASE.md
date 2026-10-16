# Install, update and roll back usb-call-lab2

**THIS BUILD HAS NOT BEEN TESTED WITH AN AUTOMOTIVE HEAD UNIT.** This is a prerelease for testing; earlier Honda results do not establish acceptance of this build.

[Русский](ru/INSTALL_RELEASE.md) · [Detailed guide](MAGISK_INSTALLER.md) · [Service status](CARLIFE_SERVICES_STATUS.md)

**One file: `Carlife_on_box-TBox-Ultra-2026.10.16-usb-call-lab2.zip`.** Download it from the [prerelease](<https://github.com/SadykovIV/Carlife_on_box/releases/tag/v2026.10.16-usb-call-lab2>).

SHA256: `957c5b2a8a471aced1d6349c29d764f52dde95bcbf12557b67b8941381dae0b4`.

This build integrates USB-only call audio without a Bluetooth dependency and reduces redundant status commands. The development-box update, 56 JVM suites, 133 host tests and Mac synthetic duplex/mute/reconnect checks passed. Real SIM/VoIP calls, physical HU microphone and this build's Honda acceptance remain pending. Clean installation on a second physical TBox is unverified.

1. Use a compatible **TBox Ultra 1 / SM6350 / Android 15** with working Magisk/root. First install requires Magisk 30.7+, an already unlocked bootloader and the reference firmware's existing permissive mode. The installer checks OEM files and does not change security settings.
2. Verify the ZIP against the release checksum. Keep stable power, then select it in **Magisk → Modules → Install from storage**.
3. Reboot once. The same ZIP handles first install, earlier universal installations and the supported manual integration. OEM settings are included. Fresh defaults: **Baidu CarLife**, video **Requested by head unit**; updates keep saved choices.

Only first installation saves and patches this device's active boot; runtime updates retain boot. No full flashing or computer-side boot extraction is required. Keep Wi-Fi ADB or your backup display for maintenance while USB serves CarLife.

To inspect or undo the package, run in a rooted Android terminal or existing ADB shell:

```sh
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-package/control.sh status'
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-package/control.sh rollback'
```

After successful rollback, reboot. **Fresh/universal:** original boot is restored and managed CarLife removed. **Earlier manual integration:** exact runtime/UI from before the first combined ZIP returns; earlier CarLife and boot remain. Both restore the preceding OEM overlay state. No separate rollback ZIP is needed.

Preserve `/data/adb/carlife-package/` and backend backups. Disabling/removing one module alone is not full rollback. See [the detailed guide](MAGISK_INSTALLER.md) for conflicts and recovery boundaries. Older r6/r7 multi-file instructions remain historical and do not apply to the combined package.
