# CarLife services: current status

**This exact lab2 build has NOT been tested with an automotive head unit, including Honda.** Mac laboratory results below do not establish real-HU microphone or call support.

[Русский](ru/CARLIFE_SERVICES_STATUS.md) · [Installation](MAGISK_INSTALLER.md) · [Build](BUILD_MD.md)

Status as of 2026-10-16. The current development package is **2026.10.16-usb-call-lab2**: the call endpoint pair is now connected to the working native CarLife session. It observes an existing Android call mode and carries downlink and uplink over USB. This is not yet acceptance of real SIM/VoIP calls or Honda interoperability. The exact working **final-release3 baseline remains saved**, with a separate return procedure below.

## Current development package: USB call lab2

| Artifact | SHA256 |
| --- | --- |
| `Carlife_on_box-TBox-Ultra-2026.10.16-usb-call-lab2.zip` | `957c5b2a8a471aced1d6349c29d764f52dde95bcbf12557b67b8941381dae0b4` |
| MD, passing 56 JVM suites | `11308809fab22d01f89ee53419f12a8bb1451d2b664bc2a1bb20fe2e6715c7e6` |

- **Legacy installation and ordinary reboot: PASS.** `reboot2/state-recovered.txt` confirms `APPLIED` and installed MD `11308809…c7e6` after reboot. `boot_a` remains `f57616e9…f5ae`, CarLife remains selected and video remains `v1:auto:auto`. No boot partition or USB selector change was made. The original `deploy2/STAGED.json` is retained as staging evidence, separate from this later acceptance.
- **Integrated call owner:** ordinary MEDIA/TTS/assistant capture and the application microphone injector retire before the Android call pair opens. Downlink uses MEDIA at 48 kHz stereo; HU MIC uses 16 kHz mono. Bounded queues, Android microphone mute, source generations and session cleanup remain in force. Production code never creates a call or sets Android audio mode.
- **Control-flood fix:** the preceding lab1 Mac run exposed a MUSIC status on each call PCM packet, about 50 per second. Lab2 emits this status only on INIT/STOP transitions, while still counting all DATA packets. The regression suite checks both drained and coalesced control slots.
- **Mac direct-USB regression: PASS** (`mac-regression-06`, exact lab2 MD above). All five phases passed: separate generated MEDIA/TTS/VR tones at 440/660/880 Hz, two application-microphone runs receiving USB 997 Hz, and two local communication-mode runs carrying downlink 1777 Hz plus uplink 997 Hz. Both communication runs passed the full VR1→MEDIA_INIT→MIC_START and MIC_END→MEDIA_STOP→VR0 order, Android mute/unmute, and return to ordinary 440 Hz MEDIA. No VR output or non-idle TEL status was used for the call fixture. Mode returned to NORMAL and microphone mute was restored.
- **Video and lifecycle in run 06: PASS.** The 85.465 s run decoded 2,321 frames at 1280×720: 2,291 frames over a 76.239 s active decode interval in the main session, then 30 frames over 0.915 s after native session reopen. Video RESET produced a subsequent IDR with unchanged SPS/PPS and preserved decoder generation. Reopen passed the first-access-unit CSD guard; receiver shutdown drained cleanly. The cable remained connected: this was not physical USB detach or a cold-power test.
- **Control-rate regression: PASS.** Each communication phase had 39 module-status packets over approximately 18.3 s, with respective one-second peaks of 8 and 9, within the test budget. The former per-PCM status flood was absent.
- **Session reopen during continuing communication: PASS** (`mac-midcall-reconnect-05`). The Android fixture remained running in MODE_IN_COMMUNICATION while the Mac closed and opened a new host USB session. Distinct downlink 1777 Hz and uplink 997 Hz signals were verified before and after reopening. The new session passed control ordering, mute/mode restoration and ordinary MEDIA 440 Hz resumption. The 41.819 s run decoded 1,050 frames: 297 over 9.828 s active decode before reopening, then 753 over 25.040 s afterward. Both sessions ended without receiver error. This was a logical host-session interruption with the cable retained, not a physical USB detach or a real call.

These are **SELF Mac laboratory routing results**, with generated signals and no external call or physical HU microphone. ADB starts and observes the diagnostic fixture; its audio and video use the direct USB CarLife data path. Raw PCM/video were not saved. `mac-regression-04` remains **FAIL**: its Android fixture dereferenced a temporarily null `AudioDeviceInfo` while reporting a recorder route and exited with `NullPointerException`. The fixture was corrected, then the complete run was repeated as 06; the original 04 result was not rewritten. Original failed attempts 01 and 03 also remain unchanged.

The receive-drain fix used by these lifecycle tests is in the Mac Python/CLI HU source. It has not been repackaged into the already-installed Mac GUI binary; the GUI can still show the ordinary NORMAL-mode video session, but its call/lifecycle acceptance is not claimed by these CLI results.

The current local evidence root is `research/usb-call-session-20261016/`: `md-compile6/manifest.json`, `package4/FINAL.json`, `deploy2/STAGED.json`, `reboot2/state-recovered.txt`, `mac-regression-06/probe-result.json`, `mac-midcall-reconnect-05/midcall-result.json` and `review/final-review.json`. The package builder's `OFFLINE_COMPOSED_NOT_DEVICE_ACCEPTED` result remains intact; device acceptance is separate.

## Preserved working baseline: final-release3

This checkpoint passed installation on the existing legacy development box, an ordinary reboot, three-lane direct-USB PCM and the diagnostic screen-button speech test below. Those results remain specific to final-release3. Earlier final-release2, r6 and mic-test4 results and failures remain tied to their exact builds.

| Artifact | SHA256 |
| --- | --- |
| `Carlife_on_box-TBox-Ultra-2026.10.15-r8.zip` | `86b8ce02e0e1f8f6ee837cf52d91fd8a964f83e4539808bd0d1b530c5545776d` |
| Baseline MD, passing 53 JVM suites at that checkpoint | `4c54f965e5fb313914f2fd4fcdbf6c3210651b4ed47eeedaea5a2c36b651530f` |

- **Legacy update and ordinary reboot: PASS.** The same combined ZIP installed through the existing legacy branch. In boot `5d782fd1-e655-4033-90ab-e126f89ddae9`, the updater reports `APPLIED` and the installed MD matches. `boot_a` remains `f57616e9…f5ae`; the original pre-combined mic-test4 backup `10d3811e…380e` is still preserved and verified. No new boot write was needed.
- **Three-lane direct-USB PCM: PASS** (`final3-pcm-usb`). The 18.955 s generated-tone test decoded 424 frames at 1280×720; active decode interval was 14.022 s. MEDIA: 594 DATA packets / 2,280,960 bytes; TTS: 595 / 380,800; VR: 347 / 222,080. Explicit VR STOP retired the old assistant stream; one already-in-flight packet completed, and the old player did not restart it. A new assistant player resumed VR; STOP preceded module 4 IDLE. The receiver finished with no error and no surviving workers. This checks generated PCM transport, not audible output on a car HU.
- **Screen-button synthetic speech recognition: PASS** (`final3-screen-microphone-loop`). An explicit diagnostic ADB tap on the observed Alice button (display 0, 47/600) started the application flow; the HU protocol voice trigger was disabled. Known generated PCM was repeated in response to MIC START. Independent inspection of `recognition-02.png` confirms the recognized **“Найди парковку”** and Alice's **“Сделано.”** response. The run decoded 1,683 video frames in 60.630 s. This is a synthetic USB-input/app-button result, **not a live HU microphone or steering-wheel result**.

The raw microphone runner still labels recognition `NOT_VERIFIED`, because it checks transport and cannot infer recognition from byte counts; that original log was not changed. The screenshot review supplies the additional ASR evidence. Earlier attempts using incorrect harness tap coordinates or lacking recognition are retained and are not counted as passes. The production Yandex voice-button entry remains **NO_HANDLER**; foreground navigation, physical cluster rendering and SIM/VoIP calls remain pending.

## Preserved final-release2 checkpoint

The filename remains `Carlife_on_box-TBox-Ultra-2026.10.15-r8.zip` across local candidates; identify the tested file by SHA256:

| Checkpoint | SHA256 / result |
| --- | --- |
| Tested final-release2 ZIP | `eca641db222ae60a1925df9eea5bcb716df6c1732948cd9702d8f5fe3bdc6f38` |
| Its installed MD | `f43c65c378f60b7479e45642624c6d4a23ffedc3f298861886bd7e7bd05673c2` |
| `boot_a` before and after update | Unchanged: `f57616e9823044cfb56548cae45aebad8d984091ef9b29ccf6df17d1cca2f5ae` |

- **Legacy install/reboot: PASS.** Magisk selected `ROUTE=legacy`; after one ordinary reboot the updater reported `APPLIED`, the installed MD hash matched, backup verification passed and `v1:auto:auto` remained. This is an update of the existing integration, not a fresh-device install or hardware rollback test. The preserved pre-combined MD is mic-test4 `10d3811e…380e`; rollback restores that exact saved runtime/UI while retaining the earlier CarLife boot integration.
- **Three generated PCM lanes over direct USB: PASS** (`final-pcm-usb3`). In 18.761 s, Mac decoded 406 video frames at 1280×720; active decode interval was 13.423 s. MEDIA: 596 DATA packets / 2,288,640 bytes; TTS: 597 / 382,080; VR: 333 / 213,120. All lanes contained nonzero samples, with zero receiver audio errors or dropped PCM. Explicit HU VR STOP left the continuing old player at 77 VR packets for the next three seconds; a new actual assistant player resumed VR. VR STOP preceded module 4 IDLE. The source was generated tones, not a real assistant, microphone or call; Mac's sink did not provide an audible-HU test.
- **Real Yandex navigation over direct USB: PASS** (`final-navigation-usb`). After NAVI_RUNNING and subscription, the Mac HU received SHOW turn 7 / 600 m / PNG 1,268 bytes, then UPDATE turn 3 / 200 m / PNG 1,163 bytes. HU STOP invoked the real route stop action, followed by NAVI=0 and HIDE. Video continued: 243 decoded frames, 8.018 s active decode within a 13.830 s run. No route was fabricated by the probe. This does not prove physical Honda cluster rendering.
- **Release2 screen-microphone recognition: NOT_VERIFIED.** The failed/unverified attempts and their original logs remain. In `final-screen-microphone`, USB sent 836 PCM packets / 535,040 bytes, including generated speech and silence padding, while 783 video frames decoded; delivered bytes did not establish recognition. Release3 corrects the voice-availability `SecurityException` and has the separate screen-button result above; it does not turn this older attempt into PASS or establish steering-wheel entry.

## Audio and controls

| Function | Current implementation | Verified / still to check |
| --- | --- | --- |
| Music | Android usages 0/1/14 → MEDIA channel 3, 48 kHz stereo PCM16 | The owner heard music on Honda with the earlier r3 candidate; subsequent Mac USB sessions received nonzero PCM. Retest the final combined build on Honda. |
| Navigation speech | Usage 12 → TTS channel 4, 16 kHz mono PCM16 | The owner heard a Yandex prompt with music ducking in r3. Actual app attributes determine routing. |
| Assistant output | Usage 16 → VR channel 5, 16 kHz mono PCM16; INIT `0x50002`, DATA `0x50003`, STOP `0x50004` | Generated usage-16 playback and STOP/new-player behavior passed direct USB on release2. Observed Yandex Alice output uses MEDIA/SPEECH attributes, so it is not relabelled as VR merely because Alice is visible. Real HU VR output still needs checking. |
| Media buttons and metadata | Active MediaSession, play/pause/previous/next, title/artist/album/progress and cover | Implemented. Title and artist are limited to 255 UTF-8 bytes for the inspected Honda receiver. Rendering and application behavior depend on the HU/player. |
| Home, Back, foreground, mute and app launch | Native input/Android intents; foreground wakes the current display; phone launch opens the dialer without placing a call | Implemented. HOME/foreground/mute packet behavior has JVM coverage; final-build HU acceptance is pending. |

The split source uses one AudioPolicy with disjoint mixes; it does not duplicate a shared PCM stream or infer speech from volume. The existing USB writer, bounded queues and source ownership remain in use. HU radio selection pauses MEDIA without stopping TTS/VR. A video RESET does not restart the audio sources.

VR module status combines a verified voice session, active assistant playback, the verified application recorder and the call owner. The application recorder now requests VR status before MIC START; this recording admission does not create a VR PCM output stream. Ending microphone recording alone does not cut off a spoken reply. An explicit HU voice STOP stops the VR capture, sends its STOP before clearing VR status, and does not auto-resume the same continuing assistant player. A new actual assistant playback start or explicit voice START can resume it; music and navigation speech are unaffected. This is source-level behavior, not a claim that every assistant labels its output with usage 16.

## Microphone and steering-wheel voice entry

HU microphone PCM (`0x58001`, 16 kHz mono PCM16) is injected into the actual Android application recorder through an input AudioPolicy matched to its UID, source and session. The microphone is requested only after the recorder is verified on the route's own remote-submix address. Queues and route generations are bounded; Android recording permissions and microphone privacy remain in force.

The preserved mic-test4 device run recognized generated USB speech after Yandex's on-screen microphone was tapped. Two consecutive USB sessions also verified process exit and discovery rearm. **This proves the synthetic input path and app-button flow, not a real HU microphone or steering-wheel entry.** The earlier failed reconnect and PCM-drop runs are retained in [microphone evidence](MICROPHONE_FIX.md).

The current voice-entry implementation handles module 4 START/STOP and voice hardkeys, attempts to launch the installed app on the captured Android display, and requires a new verified recorder before reporting VR active. A launched activity alone is insufficient. The current Yandex steering-wheel result is **NO_HANDLER**; reliable entry is still being corrected and checked. Release3's diagnostic screen-button recognition is independently confirmed above; it neither relies on the old mic-test4 PASS nor closes the production-button path. Google, Android Auto and an additional assistant APK are not prerequisites.

## Navigation and instrument-cluster data

The native adapter reads real navigation notifications from installed Yandex Navigator/Maps, including bounded traversal of nested RemoteViews. It sends `NAVI_NEXT_TURN_INFO` (`0x10030`) only for a supported active subscription. Functional NAVI status is separate from subscription module 0, avoiding a wait cycle between route status and the HU's START request. Both legacy time and modern icon wire types are supported at field 6.

On the reference box, the installed Navigator's **background** notification supplied a real left turn (`nextTurn=7`), 20 m / 40 m distance samples, a 1,268-byte PNG and an available `GuidanceStopRouting` PendingIntent. Background location permission was required in that measured configuration. Route STOP uses the app's validated PendingIntent when available and waits for the actual source to confirm the route ended; it does not force-stop Yandex or pretend cancellation succeeded.

The later release2 USB run above also confirmed SHOW/UPDATE and actual route STOP→NAVI=0/HIDE. Neither observation proves **physical cluster/HUD rendering**. The inspected Navigator version stops that maneuver producer when its map is in the foreground. A working foreground maneuver source has not been established. Unknown road names, ETA and full-route data are not invented; no OCR or AA host was added. Other Yandex versions and Maps still need their own real samples.

## Phone calls

The requirement clarified on 2026-10-16 is **USB-only SIM and Internet/VoIP call audio**. The box does not connect to this HU over Bluetooth. The previous HFP/SCO recommendation did not meet that requirement and is withdrawn; Bluetooth pairing is neither a solution nor a prerequisite.

**Lab2 implements the USB call owner; final-release3 is the preserved pre-call baseline.** Communication usages 2/3 remain excluded from ordinary playback mixes. A separate `NativeCallAudio` pair uses Android call downlink extraction and uplink injection APIs, and `NativeCallSession` coordinates its ownership with the existing MEDIA writer, app microphone and assistant. The reference box exposes the necessary APIs and reports `isPstnCallAudioInterceptable=true`; these capabilities alone do not establish a working conversation.

The call sequence is `VR status=1 → new MEDIA_INIT → MIC_RECORD_RECOG_START`, with MEDIA downlink and MIC uplink. Teardown is `MIC_RECORD_END → MEDIA_STOP → VR status=0`. The call owner does not emit `VR_INIT`, because VR PCM disables the microphone in the inspected Honda path. It also omits PHONE/TEL non-idle status: that receiver activates priority-3 PHONE and disables lower-priority MEDIA/VR. This is an implemented interoperability candidate, not a claimed dedicated standard phone PCM channel or proof that the owner's Honda firmware behaves identically.

The worker observes the actual Android mode, opens both endpoints transactionally, applies Android microphone mute and drops old queued PCM under pressure. Normal audio ownership is released after call teardown; partial-open failure, USB session loss and a new logical session retire both endpoints. Cleanup uncertainty fences reuse. A same-version retransmission and video RESET preserve the current audio session. Independent review corrected start/stop publication and late-writer races; 109 call-session checks now cover ownership, ordering, mute, mode-family changes, failure, bounded input, RESET and control-rate regression.

**Still required:** separate real SIM and VoIP calls with both directions heard over the cable, no Bluetooth audio connection, and recovery of music/navigation afterward. A Mac-generated communication-mode fixture checks Android/USB routing without placing an external call; it cannot prove a modem conversation, a real app's call behavior, acoustic echo performance or a physical Honda microphone. Honda MEDIA+MIC duplex acceptance also remains pending. Earlier HFP capability reports remain historical evidence; their routing recommendation is superseded.

### Exact return to final-release3

The saved baseline runtime/UI archive and verified original ZIP are recorded in `research/usb-call-session-20261016/checkpoint/CHECKPOINT.json`. On this development box, reinstall the saved final3 ZIP through Magisk, then perform one ordinary reboot and verify MD SHA `4c54f965…530f` and the USB session:

```sh
adb -s CURRENT_IP_PORT shell "su -c '/debug_ramdisk/magisk --install-module /sdcard/Download/Carlife_on_box-TBox-Ultra-2026.10.15-r8-86b8ce02e0e1.zip'"
adb -s CURRENT_IP_PORT reboot
```

The generic package `rollback` command targets the older pre-combined mic-test4 backup; it is **not** this exact final3 return. Both backups are preserved. This return procedure is prepared and its files verified; a hardware rollback has not been executed in this stage.

## Validation and boundaries

The latest completed local MD run passed **56 JVM suites**, including 109 call-session checks, paired call-endpoint tests, three-lane audio ownership/order, explicit VR STOP/resume, session RESET, microphone handover and navigation parsing. Repeat with the API 35/JDK 17 toolchain from [BUILD_MD](BUILD_MD.md):

```sh
python3 scripts/build_md.py --test-only --sdk "$ANDROID_SDK_ROOT" \
  --java-home "$JAVA_HOME" --output build/services-tests
```

Preserved pre-call evidence is retained under `research/unified-magisk-r8-20261015/`. Final3 checkpoint: `final-release3/FINAL.json`, `deployment3/RESULT.json`, `reboot3/after-state.txt`, `services/final3-pcm-usb/pcm-result.json`, and `services/final3-screen-microphone-loop/{manual-trigger.json,mic-probe-result.json,recognition-02.png}`. Earlier: `final-release2/FINAL.json`, `deployment/magisk-install.txt`, `reboot/after-state.txt`, `services/final-pcm-usb3/pcm-result.json`, `services/final-navigation-usb/navigation-result.json`, and failed/unverified `services/final-screen-microphone*` logs. Each USB run also has its receiver summary. Research remains in `honda-services`, `navigation-evidence`, `phone-services`; offline results include `honda-vr-stop-tests` and `final-md3/manifest.json`. Honda RH00468A is not assumed identical to the owner's RH00457A firmware. Vendor sources and device dumps are not bundled with the installer.

Independent multitouch still requires the HU to send independent pointer coordinates. Honda's 60 FPS mode previously lagged severely; the default remains **Requested by head unit**. New cold-boot speedup, foreground navigation, physical cluster output, SIM/VoIP calls, fresh-device install and full automotive acceptance remain unverified. The scoped release2/release3 PASS results above do not close them. The earlier [services design](CARLIFE_SERVICES.md), [first audio candidate](AUDIO_CANDIDATE.md) and [r6/mic-test4 record](NATIVE_CANDIDATE.md) are historical documents, not the current feature checklist.

The former source-only 55-suite endpoint candidate has been superseded by the integrated 56-suite lab2 package above. Its historical report remains unchanged. The completed Mac regression and continuous-fixture reopen have separate exact results; later Honda/real-call acceptance must retain its own hashes and verdicts.
