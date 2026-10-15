# Lessons from HeadSwap releases

> This is the historical review at 8c73aaa. Several items marked pending below are now implemented offline; see [native services/video candidate](NATIVE_CANDIDATE.md).

[Русская версия](ru/HEADSWAP_LESSONS.md)

This review maps all 19 entries in the supplied HeadSwap changelog to **native Android display projection** in Carlife_on_box. The reference is [HeadSwap v1.2.0](https://github.com/YegorkaPomidorka/headswap/releases/tag/v1.2.0), source commit [`0f5502a`](https://github.com/YegorkaPomidorka/headswap/tree/0f5502ab76e2f220b68fa1e2c85b66838f01fd81), checked on 2026-10-15.

HeadSwap receives video, separate audio channels and application metadata from Android Auto. Our MD captures the box's Android display and must obtain audio and application data from Android itself. The CarLife wire lessons apply; AA startup, private input codes and AA stream handling often do not.

## Which implementation is being assessed

- **Accepted baseline `3b344d4`:** the existing native USB video/input and boot integration. Preserve its RESET fix, source lease, CSD guard, geometry and USB ownership. It is not evidence of working USB audio.
- **Audio candidate `c7e8cf3`:** adds mixed `REMOTE_SUBMIX` stereo PCM, audio negotiation and playback-state handling. It had not received physical audio acceptance. Two problems below were reproduced in its actual Session code using host-side Android/USB boundaries.
- **Current review candidate:** fixes v2 phone verification, missing public Android hardkeys, idle MEDIA activation and radio-pause handling. These are offline-tested changes; they have not been accepted on a physical HU. The accepted baseline and existing installer package are separate from this candidate.

The build combines `android-md/src` with the overlay in `tools/boot_native/md/src`; the latter replaces `MdMain`. Reading only the common laboratory `MdMain` gives the wrong picture of native compatibility.

## Release-by-release matrix

**Already** means the corresponding behavior exists in our code; it does not extend hardware acceptance to every HU. **Fixed offline** means this review changed and tested it. **Pending** means implementation or device validation remains. **AA-specific** identifies behavior that must not be copied directly.

| HeadSwap release | Experience from the release | Carlife_on_box assessment and action |
|---|---|---|
| **1.2.0** | Two-finger zoom/rotation where the HU transmits the second position; some HUs send only one. | **Already:** legacy `pointerx/pointery`, separate gesture opcodes and `ACTION_3`, stable pointer IDs and local input recovery. Actual two-point data from VW/another capable HU still needs acceptance. The physical Honda touchscreen being multitouch-capable does not prove its current CarLife stream contains both positions. Do not synthesize an unmeasured second position. |
| **1.1.11** | In-app updates preserve settings; theme selection; 1440p labelled experimental. | **Already/separate:** our delivery uses Magisk installation, data updates and rollback; there is no need to add a second app updater. Theme controls are UI-specific. **Pending:** selectable video modes; experimental resolutions must not be advertised as accepted. |
| **1.1.9** | New phone verification on Honda CR-V/Mazda; 1440p and VW reconnect fixes; navigation restored on Honda while retaining the Desay exception. | **Fixed offline:** v2 checksum response derived from the exact sent MD_INFO. Legacy RSA remains a separate unimplemented exchange. **Already:** fresh source generation/CSD and retired USB owner on reconnect. Huawei's unchanged-SPS normalizer bug is absent from our code. **Pending:** real verification session and navigation metadata; use the precise Desay rule below. |
| **1.1.6** | VW CNS3 2240×976 geometry; experimental 2560×1440. | **Pending:** output-mode selection, matching encoder capability/SPS level, EGL geometry and input. **AA-specific:** inability of the AA phone to supply 1440p is not a limit of our display source; our encoder limits must be checked independently. |
| **1.1.5** | Preserve radio, including Bluetooth cases; no autoplay on returning; metadata only while playing; keep connection alive in background. | **Fixed offline:** known idle/initially unknown playback does not open PCM; MUSIC=1 reports status instead of unmuting; explicit Play/Pause works when playback callbacks degrade. **Already:** video PAUSE keepalive and audio-independent video control. **Pending:** physical radio/BT checks, HFP-autoplay discrimination and playing-only metadata. |
| **1.1.4** | Exynos encoded video rejected by older Desay HU despite working sound/voice. | **AA/codec-specific:** their SPS repair addresses another encoder. Preserve our QCOM/OEM CSD guard; do not rewrite SPS constraints/level blindly. A new encoder/HU needs actual SPS evidence. |
| **1.1.3** | Do not close Android Auto's first-run consent wizard. | **AA-specific:** native Carlife_on_box has no AA setup dependency. Do not introduce an AA/DHU prerequisite for CarLife. |
| **1.1.2** | Stable encoding during placeholder→AA handover; fade; clean exit/return without waiting for touch. | **Already:** a single real-display source, source lease, static-frame redraw and fresh CSD/IDR handling. The two-source handover does not exist here. **Pending:** video backlog/adaptive bitrate work. Do not add a second encoder merely to display startup progress. |
| **1.1.0** | Navigation and media metadata/cover/progress reach the HU and cluster; EN/RU UI; language-independent AA startup. | **Pending:** native MediaSession metadata and a real Yandex Navigator/Maps route source, with subscription and lifecycle handling. EN/RU project instructions already exist. AA server automation and its language issue do not apply. |
| **1.0.12** | VW CarLife 2.1 service-message delivery and video start fallback; Xiaomi placeholder/crash diagnostics. | **Already:** the recorded fragmented/concatenated message patterns, idempotent negotiation and 1500 ms fallback after INIT_DONE; stage/terminal diagnostics. This does not mean arbitrary inner fragmentation is supported. Xiaomi's placeholder service is a different source. |
| **1.0.10** | NAVI/MEDIA buttons no longer interrupt audio; FPS selection; USB-aware bitrate and shorter video queue. | **Already in audio candidate:** repeated video START/RESET does not restart audio. **Pending:** hardware acceptance, runtime FPS_CHANGE, nonblocking bounded video queue and adaptive bitrate. Our current queue problem is reproduced below. |
| **1.0.8** | Mazda 1728×648 mode. | **Pending:** add as a selectable, verified video mode within one video/input geometry model; do not infer all HUs share this size. |
| **1.0.7** | HOME/MEDIA/NAVI buttons open the corresponding AA screen. | **Fixed offline:** MAIN/HOME/BACK and public Android media/DPAD keys. **Pending:** Android app actions for MEDIA/NAVI; AA's navigation key 65538 is not a native Android key. |
| **1.0.6** | Phone authentication and fragmented data on VW; Vivo Android 16 AA server startup. | **Already:** the recorded split-full-header/interleaved-tail patterns. **Fixed offline:** the newer v2 checksum branch. **Pending:** legacy RSA compatibility is not implemented by that fix. Vivo AA automation does not apply. |
| **1.0.5** | Avoid crash before Android grants app USB-accessory permission. | **Different access path:** our native runtime opens its existing accessory node with its established process rights. Preserve open/read/close diagnostics and single-owner recovery; do not import app permission dialogs or silently change rights. |
| **1.0.4** | Correct widescreen touch; 1920×720 mode. | **Already:** accepted-video coordinates map through the same viewport as the encoded display. **Pending:** widescreen output selection. Do not copy AA-specific margin correction into Android display coordinates. |
| **1.0.3** | User selection of 800×480, 1280×720, 1920×1080 and wide video. | **Pending:** our native v2 output remains 1280×720; a wider validation range alone does not implement safe mode selection. |
| **1.0.2** | Rotary controller/joystick navigation. | **Fixed offline:** public Android DPAD and center keys. **Pending:** native rotary scroll/focus semantics; HeadSwap's AA rotary message is not an Android MotionEvent implementation. |
| **1.0.1** | Voice button launches AA assistant; reliable AA head-unit server start. | **Pending:** native push-to-talk, HU microphone route and chosen Android recognizer/assistant. AA server start and its private assistant path do not apply. |

## Findings and changes in this candidate

### Phone verification is a distinct exchange

Before this review, the native session accepted positive `HU_AUTHEN_RESULT`, but terminated on `HU_AUTHEN_REQUEST` instead of answering. Sending `MD_AUTHEN_RESULT` after STATISTIC_INFO accepts the HU; it does not answer the HU's phone challenge.

The implemented **v2** response is reconstructed from the official Baidu MD: lowercase `MD5("CARLIFE" + brand + model + sdk + nonce)`. The identity is extracted from the exact MD_INFO payload sent in that logical session; the nonce is the second semicolon-separated component of `2.0;...`. Official JNI modified UTF-8 semantics are retained. Repeated requests are deterministic, reconnect creates fresh identity state, and a response does not claim that the HU accepted it. Negative HU results still fail explicitly.

The official Baidu 8.9.5 DEX and `libencryption.so` confirm the branch, identity construction, prefix and lowercase encoding. Synthetic vectors were independently checked with Python and OpenSSL. This does **not** implement legacy RSA challenges, encrypted-media negotiation, or claim certification. No RSA key, proprietary library or GPL implementation was added. Legacy support is an implementation item, not a new blanket prohibition on researching it.

### Audio must not seize the HU while idle

The `c7e8cf3` probe opened the mixed source while the Android playback snapshot was idle, then emitted MEDIA INIT and silent PCM. A second probe showed MUSIC=1 clearing a radio-selected mute and restarting capture. Both behaviors are corrected:

- Wait for actual playback, including the first asynchronous snapshot; stop capture and discard queued PCM when known idle.
- MUSIC=1 reports actual state. It is not a Play command and does not undo radio selection.
- Explicit media Play can resume; explicit Pause also stops PCM when Android playback callbacks are unavailable. A later MUSIC query must not restart it.
- Keep degraded basic-PCM operation available; callback availability is not a new installation prerequisite.

A new Android player start can still be Bluetooth/HFP autoplay rather than user intent. HeadSwap has an HFP guard. This is also a **native risk**, not exclusively an AA issue, but its timing constants should not become arbitrary native delays. Verify HFP transitions, explicit Play and player starts on the box before applying a targeted rule.

### The video queue can still add latency

The actual native Session accepted **8 AUs of 512 KiB**, retaining **4,194,464 bytes including headers**. The ninth enqueue waited about **1005 ms**, then raised `bounded send queue exhausted`. The codec drain path can block there and the error can close the session. The separate two-second age guard does not prevent this backlog.

The encoder still requests a fixed **4 Mbps**. Writer timing is recorded but does not regulate bitrate. **Adaptive bitrate and the new queue policy are not implemented in this review.** Absence of an explicit `bitrate-mode` key does not establish the codec's actual mode. Switching CBR/VBR alone is not a demonstrated fix for Honda latency.

### Fragmentation support has an exact scope

Both implementations handle an inner header sent alone, followed by same-channel body fragments; accumulated data may finish one message and contain subsequent complete messages or a partial tail. Our tests also cover interleaved channels and opaque reserved bytes. Complete Honda messages retain `min(declared, available)` compatibility.

This is **not arbitrary inner fragmentation**: an initially short inner header is ignored; an initial header plus partial body, without existing pending state, follows the compatible min-length path. HeadSwap has the same heuristic. Arbitrary fragmentation of the outer byte stream is a different layer and is buffered. Do not replace the recorded Honda length behavior with a generic strict parser without replaying its traces.

## Navigation, metadata and sound routing

The **Desay G6S rule is narrow**: HeadSwap identifies manufacturer `Desay SV Automotive` **and** model `G6S`, or firmware channel `20622116`. It suppresses **prolonged route-held NAVI=1** because that keeps radio muted. Temporary NAVI ownership around TTS remains. This is neither a ban on every Desay unit nor a blanket ban on navigation packets, and it must not disable Honda cluster guidance.

The current mixed REMOTE_SUBMIX closes when MEDIA is muted by the HU; a Yandex spoken direction mixed into it cannot independently play over radio. Split MEDIA/TTS must begin at the Android source. Duplicating one mixed PCM stream onto both CarLife channels is not separation. Route activity and current speech need independent NAVI ownership: TTS STOP must not end the route, and route end must not cut off current speech. Avoid double ducking when Android audio focus already attenuates media.

Media metadata should reflect one current MediaSession and be sent only while playing and subscribed. Idle track/progress packets can themselves change the HU's audio source. New subscriptions/reconnect need current state; stop/session end must clear stale metadata and pending cover work.

Yandex Navigator/Maps must supply real route/manoeuvre data. An SDK for a separate navigation app does not read the route of an installed Yandex app; a navigation notification category does not guarantee structured manoeuvre fields. Establish the usable source on one short real route, then handle start/update/end and subscription replay. This does not block ordinary stereo audio acceptance.

For voice, start with explicit push-to-talk: request HU recording, consume its microphone channel, and finish with explicit stop/VR state cleanup. The source must be connected either to the chosen recognizer's PCM input or a verified Android recording route for the intended assistant. AA audio acknowledgements and mandatory waits for unrelated services are unnecessary.

## Work order and acceptance

1. **Validate the current audio/protocol candidate when the box is available.** Hear real stereo, enter CarLife while radio is selected, test Play/Pause and NAVI/MEDIA, remain in background/radio for at least 60 seconds, return and reconnect. On a HU that requests v2 verification, capture request→response→HU result and verify a session beyond the previously reported 30–40 second disconnect period. These are independent checks; AA/DHU, a new firmware flash and every future feature are not prerequisites.
2. **Reduce video backlog on the current mode.** Keep the existing writer/source. Bound video by age and bytes; enqueue without blocking codec drain. After dropping an unsent reference AU, discard dependent P frames until a real current-generation IDR with current SPS/PPS arrives. Never interrupt an in-flight USB packet. Rate-limit sync requests and allow a valid large IDR through an empty queue. Then adapt bitrate from real writer/backlog observations, preserving command/PCM service, RESET fences and the CSD guard. Test slow transport and recovery before measuring touch-to-display behavior on the HU.
3. **Add split MEDIA/TTS, native metadata and Yandex/voice integrations.** Separate usages at the Android source, then test real navigation speech over music/radio. MediaSession metadata, Yandex route extraction and push-to-talk can proceed independently; none requires completion of all the others.
4. **Add runtime FPS and widescreen modes.** Use one accepted mode for INIT_DONE, encoder, actual SPS, EGL and touch. Preserve the accepted 1280×720 profile while testing each additional mode; do not weaken Level/CSD checks merely to advertise higher resolutions.

A host test proves state-machine behavior at its controlled boundaries. It does not prove audible sound, HU source selection, cold boot, all-HU multitouch, or physical end-to-end latency. Device validation remains explicit rather than being inferred from a successful build.

## Sources

- [HeadSwap handshake](https://github.com/YegorkaPomidorka/headswap/blob/0f5502a/android/app/src/main/java/com/yegorkapomidorka/headswap/Handshake.kt), [framing](https://github.com/YegorkaPomidorka/headswap/blob/0f5502a/android/app/src/main/java/com/yegorkapomidorka/headswap/Protocol.kt), [CarLife session](https://github.com/YegorkaPomidorka/headswap/blob/0f5502a/android/app/src/main/java/com/yegorkapomidorka/headswap/CarLifeSession.kt).
- [AA bridge and audio/source policy](https://github.com/YegorkaPomidorka/headswap/blob/0f5502a/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/AaBridge.kt), [metadata](https://github.com/YegorkaPomidorka/headswap/blob/0f5502a/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/AaMeta.kt), [video pacing](https://github.com/YegorkaPomidorka/headswap/blob/0f5502a/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/VideoPacing.kt), [video modes](https://github.com/YegorkaPomidorka/headswap/blob/0f5502a/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/VideoMode.kt).
- Baidu official MD 8.9.5 static evidence and independent probes are retained in the local research record `research/headswap-lessons-20261015`: `protocol-review`, `audio-review`, `video-review`, `official-phone-verification`, `audio-probe`, `video-probes` and regression results. Proprietary APK/library bytes are not part of the published runtime.

HeadSwap source was studied for protocol facts and failure scenarios. Its GPL code was not copied into this Apache-2.0 project.
