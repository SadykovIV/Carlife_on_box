# Native services and video candidate

[Русский](ru/NATIVE_CANDIDATE.md)

Implemented on `feature/native-services-and-video`, based on preserved `8c73aaa`. **The 2026.10.15-r6 runtime update is installed on the existing test box and applied automatically after reboot (`RESULT=APPLIED`).** The original boot image and first working runtime baseline were retained; this update does not replace the universal first-install workflow.

The installed r6 build completed a 90-second native USB Mac HU session: **2,591 decoded frames, no decoder errors**. Offline validation passed **43 MD JVM test suites, 41 installer lifecycle scenarios and 13 ZIP-builder tests**. The owner previously confirmed music and navigation ducking on Honda with r3 using the same audio code; the final r6 build has only been checked on the Mac HU so far. This is not full acceptance of every service, HU or video mode.

A separate 60-second V2 test confirmed media/context/navigation registration and voice readiness, decoded 1,782 frames at 1280×720, and received 3,147 MEDIA packets with 12,084,480 PCM bytes containing nonzero samples; no data errors or PCM drops were recorded. **The first V2 shutdown check failed** with `NATIVE_RX_DRAIN_NOT_QUIESCENT` because MEDIA continued after video PAUSE beyond the 3.5-second drain deadline. Registration and video/audio delivery are evidenced, but that first V2 run is not PASS. The earlier V1 test closed successfully. Voice readiness does not prove recognition of a real HU phrase; notification-listener registration does not prove a real Yandex maneuver.

The separate V2 host-close recheck **passed (exit 0)** after changing only the Mac HU to send PAUSE_MEDIA before VIDEO_PAUSE. It decoded 890 frames at 1280×720 over 29.564 seconds of active video and received 1,323 MEDIA packets (5,080,320 PCM bytes with nonzero samples), without errors or PCM drops. Shutdown was quiescent after 0.571 seconds, with no pending data or surviving workers. The installed MD and r6 ZIP were unchanged; the first failed run above remains part of the evidence.

Hardware rollback restored the saved baseline using r4 after a manual manager reapply. The r6 rollback ZIP was not retested on hardware, so automatic r6 rollback hardware PASS is not claimed. See [installation status and limits](MAGISK_RUNTIME_UPDATE.md).

## Implemented

| Function | Native path | Remaining device check |
| --- | --- | --- |
| Separate music/speech | One privileged AudioPolicy: usages 0/1/14 → MEDIA channel 3, 48 kHz stereo PCM16; usages 12/16 → TTS channel 4, 16 kHz mono PCM16 | OEM routing, audible separation, actual Yandex playback attributes and ducking |
| Radio/playback controls | Active MediaController with public-key fallback; MUSIC=1 queries state; idle does not send MEDIA_INIT; radio pauses music without stopping speech | HU source selection and player behavior |
| Track information | MediaSession title/artist/album/duration/progress and bounded JPEG cover; subscriptions, replay and coalescing | HU rendering and installed player |
| Yandex maneuvers | Notification listener in the existing privileged process, Navigator/Maps only; explicit RU/EN maneuver and distance text; SHOW/UPDATE/HIDE | Real notifications from the installed app and HU display |
| HU microphone | Channel 5 PCM → bounded pipe → SpeechRecognizer `EXTRA_AUDIO_SOURCE` → Android web search; VR command/keys start and stop | Recognizer support, normal permissions, HU PCM format and a real phrase |
| Video backlog | Nonblocking queue: 8 items / 256 KiB / 250 ms. Loss discards dependent frames until a fresh IDR+SPS/PPS; one recovery AU up to 512 KiB may be admitted alone | Physical latency and HU decoder buffering |
| Bitrate/FPS | 1–4 Mbit/s adaptation from shared writer pressure/throughput; EGL pacing; FPS DONE follows application of that exact request | Encoder dynamic parameters and HU acceptance |
| Resolution | `720p`, `auto` HU hint with capability fallback, or explicit `WIDTHxHEIGHT`; selected AVC level, actual SPS/crop/DPB and shared video/input geometry | Each new mode on the real encoder and HU |

The existing USB session and single writer are reused. No AA/DHU, additional APK, new device daemon or firmware flash is a prerequisite. Existing privileged display/input/routing access is required. Default build options remain audio off / services off / 720p; the candidate explicitly enables the new functions.

`--audio-source split` selects separated capture; `remote-submix` retains mixed capture. A split-route failure is logged locally and leaves video/input running. It does not silently start another captor. Shared policy ownership and synchronous release prevent overlap with the older source.

## Bugs caught during the second review

- Radio selection rebased the playback observer and temporarily erased active speech, stopping TTS. Speech now survives until the fresh snapshot arrives.
- A completed old TTS packet could enqueue NAVI state in a new logical session. Completion and epoch checks are now atomic.
- Delayed initial module status could send NAVI/VR=0 during a prompt/recognition. The writer builds it from current state immediately before writing.
- Rapid FPS changes 30→15→30 could acknowledge the last value from an older frame-rate state. Immutable request identity now reaches the EGL owner before DONE.
- Old queue-pressure counters could lower bitrate after a source reset. A new controller starts from the current counter baseline.
- Early USB negotiation could reach optional Android services before they existed. Only failed registrations retry asynchronously; successful listeners remain single, and permission/API failures are reported without repeated attempts. Optional service logs use the existing bounded log ring.

RESET, source lease, original CSD handling and existing multitouch decoding remain covered by regression tests. Test failures retain output and time out instead of hanging on an orphaned worker.

## Scope and limits

Yandex notifications are not a guaranteed full-route API. Unknown text emits no invented maneuver. Road name, total route distance, ETA and arbitrary icons are not inferred. NaviKit is not used to read another app's route. The exact Desay G6S profile avoids holding NAVI active for a route alone; spoken TTS still activates it.

Voice is push-to-talk recognition/search, not microphone injection into arbitrary assistants or keyboards. Assistant replies marked with usage 16 use the TTS route. Calls, Bluetooth microphone bridging and echo cancellation are not claimed. An installed recognition provider must support external PCM; Android's normal permission checks remain.

The mode policy implements AVC levels through 4.2: 2240×976 has independent SPS fixtures; 2560×1440 exceeds that frame budget. Runtime FPS cannot exceed the selected mode's initial ceiling. Android source density is unchanged. The original OEM CSD exception remains only the proven `10 → 31` case; higher levels require matching original CSD, never byte substitution.

Modern v2 phone checksum support is retained; legacy RSA/media encryption remains unsupported. Independent multitouch coordinates still depend on what the HU sends. The reported 50–60 second cold boot has not been remeasured or declared fixed: existing phase diagnostics are retained, with no speculative boot sleeps or replacement video backend.

## Reproduce

Use API 35, JDK 17 and the existing SDK/D8 setup in [BUILD_MD](BUILD_MD.md):

```sh
python3 scripts/build_md.py --sdk "$ANDROID_SDK_ROOT" --java-home "$JAVA_HOME" \
  --r8-jar "$R8_JAR" --output build/native-services-md
python3 scripts/build_runtime.py --md-jar build/native-services-md/md-boot-native.jar \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  --java-home "$JAVA_HOME" --r8-jar "$R8_JAR" --clang clang --ld-lld ld.lld \
  --audio-source split --native-services on --video-mode 720p \
  --output build/native-services-runtime
python3 -m unittest discover -s tests -p test_build_runtime_options.py
python3 scripts/test_host.py --stdlib-only
```

Runtime options modify staging only. The first audio candidate keeps `720p`, the accepted output geometry. Select `--video-mode auto` or a specific size for the separate mode test; new resolution acceptance is not a prerequisite for sound. The optional direct MD argument `--voice-recognizer package/.Service` selects a provider; otherwise Android's default is used. The build creates a runtime archive, not a boot-flashing command. For the current box, keep the default runtime version: no resolver, selector or boot-hook migration is needed. The current box received the candidate through the [Magisk runtime update](MAGISK_RUNTIME_UPDATE.md), with its first working payload retained for rollback. The separate custom-version build remains a packaging test, not the installed device configuration. The existing selector and boot integration were retained.

## One next device task

Retest the installed r6 build on Honda: known stereo media and a Yandex prompt, nonzero PCM on channels 3/4, audible separation, radio pausing only music, and local routing restored after release. The earlier r3 owner confirmation is useful evidence, but does not replace this final-build check. Then verify metadata/reconnect and a real notification/VR phrase. Record unsupported app/provider data without stopping video. Measure latency and boot stages separately; these checks are not prerequisites for ordinary sound.

Sources: [HeadSwap v1.2.0](https://github.com/YegorkaPomidorka/headswap/releases/tag/v1.2.0), pinned `0f5502ab76e2f220b68fa1e2c85b66838f01fd81`; Baidu public schemas and independently inspected official client; AOSP 15 [AudioPolicy](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioPolicy.java), [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager) and [RecognizerIntent.EXTRA_AUDIO_SOURCE](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE). No HeadSwap GPL implementation is copied. Old/new navigation field 6 is verified with independent schema decoding; Yandex test text is explicitly synthetic.
