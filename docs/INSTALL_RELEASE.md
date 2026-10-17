# Install, update and roll back ver0.2

**Prerelease: this exact ZIP has not been installed or validated on the device.** It includes the tested C10 runtime baseline plus the C11 update-cleanup fix. The owner's box remains on C10 for in-car testing.

[Русский](ru/INSTALL_RELEASE.md) · [Detailed guide](MAGISK_INSTALLER.md) · [Service status](CARLIFE_SERVICES_STATUS.md)

**One file: `Carlife_on_box-TBox-Ultra-2026.10.17-v0.2.zip`.** Download it from the [v2026.10.17 prerelease](https://github.com/SadykovIV/Carlife_on_box/releases/tag/v2026.10.17).

SHA256: `bc25a436996a52a43932d984e7a4ffc8e04ca43d9b50a6755d5823b5bda7f6ea`.

Since [v2026.10.16](https://github.com/SadykovIV/Carlife_on_box/releases/tag/v2026.10.16): faster startup and USB reconnection; saved resolution/FPS now override startup defaults and apply through a clean reconnect; revised Yandex voice/microphone handling and earlier audio-capture preparation; a targeted OEM SIM-call uplink fix. C11 additionally waits for transient controller cgroup members to finish during update/stop cleanup.

The C10 baseline reconnects automatically in approximately **5–7 seconds**, as accepted by the owner. The preceding C9 startup baseline passed a cold-start test: first encoded frame at **21.708 seconds of boot uptime**. The unchanged MD passed **57 JVM suites**; C11 passed **167 host controller tests**. C11 cleanup has not been tested on the device. Audible phrase beginnings, assistant replies, steering-wheel behavior and real SIM/VoIP duplex remain pending in-car checks. 60 FPS previously caused severe lag on the tested Honda and has not been revalidated; use Auto or 30 FPS. Clean installation on a second physical TBox remains unverified.

1. Use a compatible **TBox Ultra 1 / SM6350 / Android 15** with working Magisk/root. First install requires Magisk 30.7+, an already unlocked bootloader and the reference firmware's existing permissive mode. The installer checks OEM files and does not change security settings.
2. Verify the ZIP against the release checksum. Keep stable power, then select it in **Magisk → Modules → Install from storage**.
3. Reboot once. The same ZIP handles first install, earlier universal installations and the supported manual integration. OEM settings are included. Fresh defaults: **Baidu CarLife**, video **Requested by head unit**; updates keep saved choices.

Only first installation saves and patches this device's active boot; runtime updates retain boot. No full flashing or computer-side boot extraction is required. Keep Wi-Fi ADB or your backup display for maintenance while USB serves CarLife.

To inspect or undo the package, run in a rooted Android terminal or existing ADB shell:

```sh
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-package/control.sh status'
su -c '/data/adb/magisk/busybox sh /data/adb/carlife-package/control.sh rollback'
```

After successful rollback, reboot. **Fresh/universal:** original boot is restored and managed CarLife removed. **Earlier manual integration:** exact runtime/UI from before the first combined ZIP returns; earlier CarLife and boot remain. Both restore the preceding OEM overlay state. This rollback follows the saved installation branch; it does **not necessarily return to the immediately preceding release or C10**. No separate rollback ZIP is needed.

Preserve `/data/adb/carlife-package/` and backend backups. Disabling/removing one module alone is not full rollback. See [the detailed guide](MAGISK_INSTALLER.md) for conflicts and recovery boundaries. Older r6/r7 multi-file instructions remain historical and do not apply to the combined package.
