# r7 video settings validation

Project sources and runtime update; tested on a compatible SM6350 Android 15 box with Mac HU over direct USB/AOA. ADB was used for maintenance, not for video or input. The final runtime package is the unchanged `video4` build.

| Test | Live decoded frames | Measured rate | Result |
|---|---:|---|---|
| Final default, no saved setting, HU requests 720p30 | 3394 | 30.000 FPS | 1280×720, Baseline L3.1 |
| Final saved `auto:auto`, HU requests 1080p30 | 3682 | 29.949–30.000 FPS | 1920×1080, Baseline L4.0, RESET/Home/touch |
| Preceding motion build with the same AU/queue fix | 7855 | 29.988–30.000 FPS | 262.051 s, 2008 input events, RESET; no terminal error |

The final 1080p test lasted 123.229 s. Two PTS intervals reached 133 ms before RESET; they were not discarded from the result. PTS remained increasing. Full retained H.264 decoded independently to 3396/3685 frames; 2/3 extra tail frames arrived after the live client entered PAUSED. Video3's retained raw prefix covered only 2330 frames because of its 32 MiB capture limit; the 7855 count comes from live decoding.

The motion test passed real access units (AUs) up to 641483 bytes. The old 512 KiB limit caused a prior 550784-byte P-frame failure. The fix permits one complete AU up to 2 MiB while retaining the normal 256 KiB/250 ms queue and IDR recovery after dependent-frame loss. Native USB v1 now uses that bounded queue too. Source lease, generation fencing and OEM CSD guard remain in place.

The guard's additional Level 4.0 exception is tied to the existing exact OEM library hash and captured correct output-format CSD; it is not arbitrary SPS rewriting. The HU now validates the actual accepted mode rather than requiring Level 3.1 for every stream.

45 JVM suites and 129 host tests passed. The final code uses `auto:auto` for missing/invalid settings and the Defaults button. Auto FPS follows valid 1–30 FPS requests, otherwise 30; manual 60 FPS remains experimental, and the reference source display reports 30 Hz. UI status is the last published negotiation when opened, not live measured FPS.

Physical Honda 1080p, 60 FPS, wide modes, new-device first installation and full rollback were not accepted by this test. The supplied rollback packages retain exact r6 runtime and selector v5 bytes. Existing r6 audio/controller integration is preserved; audio was not requalified in this video task.

Русский: итоговые 720p и 1080p по запросу ГУ, сохранение настроек, Home/тач/RESET и независимое декодирование прошли на реальном боксе с Mac HU. Результат не является приёмкой Honda или 60 FPS. Крупные кадры больше прежнего предела 512 KiB передаются; отдельные интервалы PTS в 133 мс явно учтены. Точный откат подготовлен, но не устанавливался заново.
