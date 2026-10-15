# Update an existing CarLife box with Magisk

[Русский](ru/MAGISK_RUNTIME_UPDATE.md)

This package updates the already integrated `boot-native-v1-20261014` runtime on the supported TBox Ultra 1 / SM6350 Android 15 firmware, including the original manually installed test box. It is separate from the universal first-install package. It retains the existing boot image, resolver, USB observer, selector, saved mode and ADB configuration.

## Verified status — 2026.10.15-r6

The r6 update was installed on the existing test box through Magisk. After reboot it applied automatically and reported `RESULT=APPLIED`; no manual manager reapply was needed. A subsequent **90-second native USB session with the Mac HU decoded 2,591 frames without decoder errors**. The original boot image, saved first runtime baseline and existing integration were retained.

The separate 60-second V2 test confirmed media/context/navigation registration and voice readiness, decoded 1,782 frames at 1280×720 and received 3,147 MEDIA packets containing 12,084,480 PCM bytes with nonzero samples. No data errors or PCM drops were recorded. **The first V2 shutdown check failed** with `NATIVE_RX_DRAIN_NOT_QUIESCENT`: MEDIA continued after video PAUSE and exceeded the 3.5-second drain deadline. Service registration and video/audio delivery were demonstrated; that first V2 run is not marked PASS. The preceding V1 test closed successfully.

A separate V2 recheck **passed, including shutdown (exit 0)** after the Mac HU was changed to send PAUSE_MEDIA before VIDEO_PAUSE. It decoded 890 frames at 1280×720 over 29.564 seconds of active video and received 1,323 MEDIA packets (5,080,320 PCM bytes with nonzero samples), with zero errors or PCM drops. Shutdown became quiescent in 0.571 seconds, with no pending data or surviving workers. **The installed MD and r6 ZIP were unchanged.** This recheck resolves the host-close failure; the original failed run remains recorded above.

Offline checks passed: **41 installer lifecycle scenarios, 13 package-builder tests and 43 MD JVM test suites**. These results cover this supported installation; they are not acceptance of every TBox firmware or HU.

The owner confirmed audible music and navigation ducking on Honda with the earlier r3 candidate using the same audio code. **The final r6 build has so far been checked on the Mac HU, not retested on Honda.** Metadata, real Yandex maneuvers and HU microphone recognition still need their application/HU checks.

Rollback has a narrower result: the r4 package restored the saved baseline on hardware **after a manual manager reapply** following an interrupted attempt. The r6 rollback ZIP was not run again on hardware; its automatic rollback is covered by offline tests, not a claimed r6 hardware PASS.

## Install

1. Copy `Carlife_on_box-TBox-Ultra-1-update-2026.10.15-r6.zip` to the box.
2. Open **Magisk → Modules → Install from storage** and choose the ZIP.
3. Wait for successful staging, then reboot once with stable power.
4. Open **CarLife runtime update → Action** to read application status. Check video/input, music and a navigation prompt on the HU.

Staging preserves the current session. On the next boot the module checks the saved transaction, stops the old runtime, verifies that its processes have exited, applies the new MD/controller with the checksum last, and starts the existing init service when CarLife is selected. One reconnection during this first boot is possible. Later boots use the original early init path; the module's service only handles an outstanding update, then exits.

The candidate enables separate MEDIA/TTS capture, native media metadata, Yandex notification maneuvers, HU microphone recognition and bounded video queues. The initial output stays at 720p. See [candidate scope and acceptance limits](NATIVE_CANDIDATE.md). Installing successfully does not prove audible routing or that a recognizer accepts HU PCM.

## Revert the runtime update

Install `Carlife_on_box-TBox-Ultra-1-rollback-2026.10.15-r6.zip` through the same Magisk screen and reboot. It restores the device's own runtime saved **before its first update with this package**. It does not remove CarLife, restore OEM boot or erase the saved mode. The first backup and transaction evidence remain under `/data/adb/carlife-runtime-update/`.

Disabling/removing the delivery module does not revert already applied data files. Use the rollback ZIP. If staging or applying fails, retain the installation log and Action output; do not delete the backup. A partial transaction may only resume/roll back when each current file matches its recorded old or new hash; an unrelated external edit is not overwritten.

No full firmware flash, boot write, security change or universal-installer ownership migration is performed. The existing privileges required by the native runtime remain necessary. A fresh box uses the separate first-install workflow.

## Build

```sh
python3 scripts/build_magisk_runtime_update.py \
  --runtime build/runtime-native-services-20261015-r4/runtime/boot-native-runtime.tar \
  --runtime-manifest build/runtime-native-services-20261015-r4/runtime/bundle-manifest.json \
  --output build/magisk-runtime-update-20261015
python3 -m unittest discover -s tests -p 'test_magisk_runtime_update_*.py'
```

Output includes update/rollback ZIPs and `BUILD.json` with exact hashes. The rollback package uses the receiving device's backup; it contains no borrowed boot or fixed predecessor image. The [official Magisk lifecycle](https://topjohnwu.github.io/Magisk/guides.html) supplies staging, next-boot service execution and the read-only Action button. Module integrity accounts for Magisk removing `customize.sh` after installation.
