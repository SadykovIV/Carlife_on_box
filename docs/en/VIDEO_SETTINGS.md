# CarLife video settings

[Русский](../ru/VIDEO_SETTINGS.md)

Status: the updated Settings UI and MD configuration support have been tested on the box through native USB CarLife and CarLife touch input. Both **MD and the Settings module** must be updated. Use the [r7 installation and rollback guide](../INSTALL_VIDEO_SETTINGS.md). The r6 release does not include these controls.

The latest video4 build follows the HU request when no configuration is saved, and **Defaults → Save** correctly stores `v1:auto:auto` after an explicit 1080p selection. Native USB checks on the box:

| Check | Result |
| --- | --- |
| Auto, HU requests 1280×720 / 30 | 3,394 frames in 113.1 seconds, 30 FPS |
| Auto, next HU session requests 1920×1080 / 30 | 3,682 frames in 123.229 seconds; 29.948598 / 29.999606 FPS from PTS; RESET checked, Home and drag worked, session error was null |
| Earlier manual 1920×1080 / 30 test | 9,000 frames in approximately 300.84 seconds; 29.95–30 FPS from PTS, H.264 Level 4, RESET checked |
| Earlier active video3 check | 7,855 frames in 262.051 seconds; encoded access units up to 626 KiB passed |

The Settings UI also saved 1920×1080 / 15 FPS; after a reboot, MD read `v1:1920x1080:15` even though the HU requested 1280×720 / 30 FPS. These are Mac HU lab results, not acceptance on Honda. 60 FPS and the wide profiles have not been accepted on a physical head unit.

## Change resolution and frame rate

1. Open the OEM settings: **Settings → Original → Switch original vehicle connection mode**. On the tested Russian UI the exact path is **Настройки → Оригинал → Переключение исходного режима подключения автомобиля**.
2. Select **Baidu CarLife settings** in that dialog.
3. Choose the video resolution and target frame rate, then **Save**.
4. Reconnect CarLife. Saving does not restart the current session, change the selected protocol, or reboot the box.

The default is **Requested by head unit**. Explicit choices are 1280×720, 800×480, 1920×1080, 1280×480, 1728×648, 1920×720 and 2240×976. **Requested by head unit** uses the head unit's requested dimensions; selecting a size explicitly requests that video mode. A particular head unit or encoder may not accept every mode. The **Negotiated mode** text reads the last published negotiation status when the dialog opens. It is not a measured FPS counter or a live session indicator; an earlier value may remain briefly after a reboot. Reopen the dialog to read the latest published status.

Frame rate choices are **Requested by head unit** (default), **15**, **30** and **60 FPS**. Auto FPS uses the head unit's valid request up to 30 FPS; without a valid request, it uses 30 FPS. An explicit value is a target; a later head unit request can reduce it.

**60 FPS is experimental.** The tested box currently advertises a 30 Hz Android display mode. Setting a 60 FPS encoder target cannot make that source produce 60 distinct frames per second.

These controls change video output. They do not change Android display density, interface scale or touch calibration. Touch mapping continues to use the negotiated video geometry. A wider video mode alone does not reflow the Android desktop or guarantee removal of letterboxing.

## Defaults and restoring the proven mode

Open **Baidu CarLife settings**, select **Defaults**, then **Save** and reconnect CarLife. This selects **Requested by head unit** for both resolution and frame rate. Defaults changes the selections in the dialog; it is committed only when Save is pressed. Cancel leaves the saved configuration unchanged.

For diagnostics, the pair is stored atomically in Android `Settings.Global` as `carlife_video_config`, for example `v1:1920x1080:30`. The default is `v1:auto:auto`; an absent or invalid setting also uses this value. An explicitly saved choice is retained after an update. `carlife_video_status` is a read-only report written by MD. No separate setting is required for touch.

To restore the proven mode, explicitly select **1280×720 / 30 FPS** and save. If the head unit cannot display settings, apply this mode through the existing development access, then reconnect:

```sh
adb shell settings put global carlife_video_config v1:720p:30
```

The APK and MD update can also be reverted to the saved pre-update artifacts. This settings change does not require a new boot image or a full firmware flash.
