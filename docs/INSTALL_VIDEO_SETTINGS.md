# Install or roll back the r7 video settings update

[Русский](ru/INSTALL_VIDEO_SETTINGS.md) · [Download r7](https://github.com/SadykovIV/Carlife_on_box/releases/tag/v2026.10.15-r7)

This update is for the **existing supported OEM integration** on TBox Ultra 1 / SM6350 / Android 15, using the `carlife_runtime_update` and `carlife_oem_selector_v1` modules. It preserves the current boot and startup controller. It is not the installer for a clean box or the separate `carlife_on_box` module family.

For first installation, use the separate [r6 first-install package and guide](https://github.com/SadykovIV/Carlife_on_box/releases/tag/v2026.10.15-r6). Do not install the r7 legacy updater or its OEM overlay over that family: this combination has not been integrated or tested. The r7 source includes the video changes for further integration.

## Update an existing supported installation

1. Download **Carlife_on_box-OEM-video-settings-2026.10.15.zip** and **Carlife_on_box-TBox-Ultra-1-update-2026.10.15-video4.zip**. Check them against `SHA256SUMS`.
2. In **Magisk → Modules → Install from storage**, install the OEM video settings ZIP first. Do not reboot yet.
3. Install the runtime `video4` ZIP. After both installations succeed, reboot once and allow the runtime update to finish.

The filename `video4` identifies the exact tested package published in r7; its bytes are unchanged from the accepted device build. Both ZIPs are needed for the settings UI and MD support. Keep the saved backups and existing maintenance access.

Open **Settings → Original → Original car connection mode → Baidu CarLife settings**. Choose a resolution and FPS, save, then reconnect CarLife. Saving does not interrupt the current session. **Defaults → Save** selects **Requested by head unit** for both fields; missing or invalid settings use the same default. Auto FPS accepts the HU request up to 30 FPS; other hints use 30 FPS. Manual 60 FPS and wide profiles remain experimental. Video size changes do not change Android DPI.

## Return to 720p or roll back the update

To keep the update but restore the previously tested mode, select **1280×720 / 30 FPS** and reconnect. With existing ADB access:

```sh
adb shell settings put global carlife_video_config v1:720p:30
```

For the exact previous r6 runtime and OEM selector v5:

1. Install **Carlife_on_box-OEM-selector-v5-baseline.zip** through Magisk; do not reboot yet.
2. Install **Carlife_on_box-TBox-Ultra-1-update-2026.10.15-r6.zip**, then reboot once. The `update` package intentionally restores the exact r6 bytes.
3. Remove the two added settings through existing ADB:

```sh
adb shell settings delete global carlife_video_config
adb shell settings delete global carlife_video_status
```

Do not substitute the generic runtime `rollback` ZIP: it may restore an older first backup rather than r6. Disabling the delivery module does not undo files already applied. This is rollback of the video update, not removal of the original CarLife integration. The prepared full r6+selector rollback was not reapplied during this test.

## Validation and contents

The final build passed direct USB tests on the reference box with Mac HU: automatic 720p and 1080p, UI defaults/save, Home, touch, RESET and independent H.264 decoding. 1080p30 delivery measured about 29.95–30 FPS. Physical Honda acceptance at 1080p, 60 FPS and wide modes remains pending; audio was not requalified in this video-only test. See [video settings](en/VIDEO_SETTINGS.md) and [r7 validation](RELEASE_R7_VALIDATION.md).

The optional OEM overlay contains modified OEM applications for the exact tested firmware; those binaries are separate from Apache-2.0 project source. No signing keys, full firmware, boot image or device captures are distributed. See [third-party notices](../THIRD_PARTY_NOTICES.md).
