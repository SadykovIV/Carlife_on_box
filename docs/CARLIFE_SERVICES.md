# Native CarLife services: audio, navigation and voice

> This is the earlier design rationale. The implemented candidate and current limits are in [native services/video candidate](NATIVE_CANDIDATE.md).

[Русский](ru/CARLIFE_SERVICES.md) · [Audio candidate](AUDIO_CANDIDATE.md)

The mixed MEDIA source and HU audio receiver are implemented and tested offline. Device sound is not yet verified. Separated audio, navigation metadata and voice below are the next implementation steps, not installed features. The existing video/input baseline stays in place.

## Order of work

1. **Hear Android sound on the HU:** deploy the current candidate, play known media, and check radio switching, stop and reconnect. Reuse the existing USB transport and writer.
2. **Separate spoken guidance from music:** add an Android output-routing source and TTS packets. Check actual Yandex guidance over music and radio. A third VR output lane is not a prerequisite.
3. **Add controls and data:** Android MediaSession playback buttons/metadata, Yandex maneuvers, and HU microphone input for a native assistant. These can be developed independently; none requires finishing all other services first.

Boot-time work also remains independent of audio. A box/HU is needed to confirm audible sound, real app data and startup timing, but not to implement protocol handling or run host tests. No new firmware, AA/DHU session or additional framework is required as a preliminary step.

## Sources and destinations

| Function | Android producer | CarLife destination | State |
| --- | --- | --- | --- |
| Launcher/map/apps | Existing EGL/MediaCodec display source | VIDEO channel 2 | Accepted baseline |
| Music and currently mixed output | REMOTE_SUBMIX PCM | MEDIA channel 3 | Implemented candidate |
| Navigation speech and assistant replies | Separate application output routes | TTS channel 4 initially | Planned |
| Car microphone | HU channel 5 / `0x58001` | Native Android recognizer/assistant input | Planned |
| Maneuver/road/distance | **Yandex Navigator / Yandex Maps** adapter | CMD navigation messages | App data still to inspect |
| Track title/progress and playback buttons | Active Android MediaSession | CMD metadata / MediaController | Planned |

HeadSwap v1.2.0 receives separate MEDIA and SPEECH from AA, sends music to channel 3 and **both navigation speech and assistant replies to TTS channel 4**. We can use that CarLife arrangement with native Android sources. Channel 5 microphone input does not require channel 5 assistant output. A dedicated VR output stream can be added if a concrete HU/use case needs it. [Pinned AaBridge source](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/AaBridge.kt).

## Separate Android audio at the source

The candidate mechanism is a privileged **AudioPolicy**, PLAYERS **AudioMix** routes with `ROUTE_FLAG_LOOP_BACK`, and `AudioPolicy.createAudioRecordSink(mix)`. Each route gets its own address. Initial rules select MEDIA usages UNKNOWN=0 / MEDIA=1 / GAME=14, and navigation usage ASSISTANCE_NAVIGATION_GUIDANCE=12. Assistant usage ASSISTANT=16 can join the speech route or get a separate sink when needed for arbitration; both initially use TTS on the wire. This extends the existing audio worker/writer.

Pure LOOP_BACK redirects selected playback; LOOP_BACK|RENDER copies it and has different capture restrictions. Public AudioPlaybackCapture is limited to eligible MEDIA/GAME/UNKNOWN playback. `allowPrivilegedPlaybackCapture(true)` has low-bandwidth constraints and does not solve full-quality navigation routing. [Android capture documentation](https://developer.android.com/media/platform/av-capture), [AudioPolicy](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioPolicy.java), [native selection rules](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp).

The supplied OEM framework implements these APIs and includes remote submix. Register and test with the existing privileged runtime; the relevant routing permission is `MODIFY_AUDIO_ROUTING`. Static API presence does not establish live routing, but there is no reason to assume a firmware/HAL patch beforehand. A short test with two distinguishable tones from one UID and different usages checks whether each sink gets only its assigned audio.

Use actual Yandex playback attributes to refine the rules. An app may label speech as MEDIA or delegate playback to another service. UID/session matching is useful when it identifies the intended sound; volume guessing and copying the same PCM into two channels do not separate audio. AudioMix matches different rule dimensions with AND, and values within one dimension with OR. Explicit application device selection may take precedence over a dynamic output mix, so test real playback as well as the two tones.

Keep one owner for registered routes and release them on stop. Stop the current address-0 capture before selecting split routing. If registration fails, retain video/input and report the audio error; return to mixed capture after releasing any created routes. Extend the existing bounded audio queues and single USB writer.

For playback behavior:

- MUSIC reflects active media. The candidate stops capture on HU radio selection and sends Android MEDIA_PAUSE; a new media-playback event or explicit MEDIA_PLAY can resume PCM. MUSIC=1 is a status query and cannot undo radio selection. Known idle produces no MEDIA_INIT/PCM. Public media/DPAD hardkeys are now mapped; targeted MediaController control and metadata remain follow-up work. Repeated VIDEO_START does not mean Play.
- Send speech as INIT → PCM → STOP, without permanently holding an active silent TTS stream. Serialize navigation and assistant speech initially.
- A spoken prompt ending must not cancel a still-active route. Keep those two reasons for NAVI status separate in existing session state; a new coordinator framework is not required.
- Account for Android's own audio-focus ducking before adding HU-side attenuation. Resume only previously playing media that the user has not stopped.

## HU microphone and native voice input

Voice entry arrives as VR module 4 in `MODULE_CONTROL 0x18028`, or a supported VR hardkey. MD requests mic recording with `0x10024`; HU returns channel 5 / `0x58001`; MD ends it with `0x10023`. The initial format is 16 kHz mono PCM16, to confirm against the HU packets. AA ACK windows and packet skipping are unnecessary here.

For an existing assistant/keyboard, the candidate is an INJECTOR AudioMix with LOOP_BACK and `AudioPolicy.createAudioTrackSource(mix)`. Match the actual recorder UID and capture preset; add session ID if needed to distinguish concurrent recorders. The OEM framework implements this path. Discover the actual recording service, register the route before its recorder opens, and invoke the normal Android voice action. A late route attachment is not assumed to redirect every existing recorder. [AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioMixingRule.java).

This mic route can be tested by itself, before split output exists. It can later share the policy owner. Injected tracks use `USAGE_VIRTUAL_SOURCE=15`, excluded from output sinks. A bounded worker writes PCM without blocking USB reception; cancel/disconnect ends HU recording and releases the track and route. First verify delivery with a target recorder and a control recorder, then a real spoken phrase and assistant reply through TTS. Start with push-to-talk; echo/barge-in is a separate improvement.

For an application-owned voice UI, API 33+ [RecognizerIntent.EXTRA_AUDIO_SOURCE](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE) accepts a PCM pipe and format. It is a simpler alternative if the chosen recognizer supports it; verify that it consumes the supplied PCM. It does not replace the input of arbitrary apps. App recording permissions and microphone privacy still apply. An owned TTS engine can provide PCM through [UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener); avoid playing and recapturing the same synthesis twice.

## Navigation from Yandex Navigator / Maps

Map video, spoken guidance and semantic maneuver data are independent features. The first metadata adapter reads the installed **Yandex Navigator / Yandex Maps**, through an available app interface or its navigation notifications. Inspect a small real route sample and implement the fields it exposes; add other maneuvers and app versions incrementally. A complete fixture catalog is not needed before the first working adapter. Missing data stays unknown.

`NotificationListenerService` exposes notifications after notification access is granted; `CATEGORY_NAVIGATION` does not define mandatory maneuver/distance fields. Yandex NaviKit provides guidance inside an application using its SDK; it is not a subscription to routes in the installed Navigator/Maps. AndroidX `NavigationManager.updateTrip` is likewise a producer API, not a reader of another app's route. [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService), [Yandex guidance](https://yandex.com/maps-api/docs/mapkit/android/generated/navigation/guidance.html), [NavigationManager](https://developer.android.com/reference/androidx/car/app/navigation/NavigationManager).

Keep the first implementation small: a Yandex adapter supplies current route/maneuver/distance to the existing CarLife session and CMD writer. Coalesce distance updates, preserve new turns and route end, clear stale data on source loss, and resend a fresh current snapshot after reconnect and subscription. A separate provider/coordinator framework is unnecessary until multiple actual providers need it.

CarLife subscriptions use `0x18043` / reply `0x10044`, START `0x18045`, STOP `0x18046`. Navigation subscription ID is **0**, while functional NAVI module ID is **2**. Maneuver output is `NAVI_NEXT_TURN_INFO=0x10030`; the HU decides where to display it. An active subscription alone does not establish cluster/HUD output.

The old Baidu schema uses required **int32 time** at field 6; newer Baidu analysis uses **bytes turnIconData** there. HeadSwap defaults to **BOTH**. Use the same compatibility choice: emit both wire types when an icon is available and retain the time field. Standard protobuf parsers consume their known type and skip the other; the local review verified BOTH against both required-field schemas in either order. TIME/ICON overrides are for observed HU incompatibilities. A separate known-good peer capture is not a prerequisite for implementation. [Old Baidu schema](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeNaviNextTurnInfoProto.proto), [HeadSwap schema research](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/docs/cluster-metadata-recon.md), [protobuf wire format](https://protobuf.dev/programming-guides/encoding/).

Advertise navigation metadata once a real provider and encoder are connected, not merely because the HU subscribes. Verify actual maneuvers, route cancellation and reconnect on the HU; check cluster/HUD separately when present. These checks do not delay the first usable music output.
