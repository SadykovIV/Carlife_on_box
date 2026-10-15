# Native CarLife services: separated audio, navigation and voice

[Русский](ru/CARLIFE_SERVICES.md) · [Audio candidate](AUDIO_CANDIDATE.md)

This is the implementation design following the 15 October 2026 HeadSwap review. Only the mixed MEDIA candidate and HU audio receiver are implemented at this stage. The owner selected **Yandex Navigator / Yandex Maps** as the first navigation data source. Navigation metadata must come from that application; screen pixels are not the provider.

## Streams and producers

| Function | Native Android producer | CarLife destination | Current state |
| --- | --- | --- | --- |
| Launcher/map/application picture | Existing EGL/MediaCodec display source | VIDEO channel 2 | Accepted baseline |
| Music/media | Output PCM; subsequently a MEDIA usage mix | MEDIA channel 3 | Mixed-output candidate, offline tested |
| Spoken navigation | Navigation app's separate audio usage/session | TTS channel 4 | Designed, no live split tested |
| Assistant reply | Assistant output usage/session, or our own synthesized PCM | VR channel 5 | Designed, not implemented |
| Car microphone | HU mic packets `0x58001` | Android recognizer/assistant input | Native routing design, not implemented |
| Maneuver/road/distance | Yandex application adapter | CMD navigation messages | Provider evidence still required |
| Music title/cover/progress and playback buttons | Active Android MediaSession | CMD metadata / MediaController actions | Planned separately from PCM |

HeadSwap v1.2.0 already receives distinct MEDIA and SPEECH from AA. It sends music to channel 3 and **both navigation speech and assistant replies to channel 4**; it does not currently implement a separate VR output stream. Its AA mic input is also a different endpoint from an Android recorder. The intended three-stream native architecture therefore goes beyond copying HeadSwap. [Pinned AaBridge source](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/AaBridge.kt).

## Separate output before it is mixed

A general REMOTE_SUBMIX recording is already mixed: it cannot reliably be separated afterward by amplitude, silence, content-type guesses or multiple recorders on the same address. Public MediaProjection/AudioPlaybackCapture also is not a universal substitute: normal playback capture is limited to MEDIA/GAME/UNKNOWN and obeys application capture policy. It does not by itself supply navigation-guidance or assistant usage streams. [Android capture documentation](https://developer.android.com/media/platform/av-capture).

The concrete native candidate is one privileged **AudioPolicy** with distinct **PLAYERS AudioMix** routes, each `ROUTE_FLAG_LOOP_BACK`, and an `AudioRecord` sink per registered address:

| Lane | Initial disjoint rule | Output |
| --- | --- | --- |
| MEDIA | usages UNKNOWN=0, MEDIA=1, GAME=14 | 48 kHz stereo PCM16 → channel 3 |
| NAV | ASSISTANCE_NAVIGATION_GUIDANCE=12 | measured format → TTS channel 4 |
| ASSISTANT | ASSISTANT=16 | measured format → VR channel 5 |

Use `AudioPolicy.createAudioRecordSink(mix)`. Pure LOOP_BACK routing differs from LOOP_BACK|RENDER playback capture in native policy selection. Do not turn on `allowPrivilegedPlaybackCapture(true)` as a blanket fix: AOSP imposes low-bandwidth privileged-capture constraints, including 16 kHz/mono, and it is not a general high-fidelity bypass. [AudioPolicy](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioPolicy.java), [AudioMix](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioMix.java), [native selection rules](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp).

The supplied OEM Android 15 framework contains these implemented methods and the remote-submix HAL. This makes it a concrete experiment without assuming a firmware/HAL patch. Registration with the existing runtime identity, `MODIFY_AUDIO_ROUTING`, SELinux access and simultaneous mix addresses still need a device test. No existing protection is changed by the prepared source.

Applications may label spoken directions as MEDIA or use a different audio service UID. Measure Yandex/assistant AudioPlaybackConfiguration and attributes first. Refine a rule with actual UID/session where supported; do not route all audio from one navigation-app UID to TTS if that UID also plays unrelated media. Missing attributes remain an observed compatibility issue, not an excuse to duplicate the same mixed PCM into three channels.

One policy owner serializes register/attach/detach/unregister. Each sink has its own source lease, epoch, sample clock, bounded queue and cleanup. Mixed address-0 capture is released before policy mode is enabled. Registration failure keeps the existing video and selected baseline available; a partial failure must remove all successfully registered routes. Fallback to mixed MEDIA is explicit and reported as mixed, not as successful separation.

## Focus, navigation speech and radio

An AudioCoordinator owns MUSIC, NAVI and VR state and the transitions on the single USB writer. Audio policy routing is separate from taking over the entire system's audio-focus policy; the latter is not required initially.

- MUSIC follows actual source/stream state. HU radio selection suppresses the PCM tail and pauses the selected Android MediaSession when that integration is added. A repeated VIDEO_START must not restart the user's music.
- NAVI has two independent owners: a live route and an active spoken prompt. Ending a prompt cannot clear the route; ending the route cannot cut an unfinished prompt. Whether NAVI is held throughout a route or only during prompts is an interoperability profile to test against the HU's radio behavior.
- TTS owns INIT → PCM → STOP for each meaningful prompt session. Do not send permanent silence while announcing an active prompt. Any stop debounce is measured against actual prompt boundaries; HeadSwap's model-specific timers are not universal requirements.
- VR listening/reply has priority over ordinary media; navigation and assistant speech must not be mixed together by accident. Initially serialize conflicting speech. Drop stale audio rather than resume an old queue.
- Android may already duck or pause music when a prompt gains focus. Measure where attenuation occurs; do not apply both Android attenuation and another arbitrary reduction at the HU.
- On reply/route termination, resume only a source that was playing before the temporary interruption and that the HU has not explicitly stopped. Calls/HFP are a separate integration.

The first separation proof is two distinguishable test tones produced by the **same UID**, one MEDIA and one NAVIGATION_GUIDANCE. Two sink recordings must contain only their assigned tone; then test real Yandex guidance over a music app, radio switching and release/reconnect. A total nonzero byte count is not proof of separation.

## Car microphone into a native assistant

CarLife voice entry is HU MODULE_CONTROL `0x18028` with VR module 4, or a supported VR hardkey. MD requests mic capture using CMD `0x10024`; HU sends channel 5 / `0x58001`; MD ends it with `0x10023`. Initial format candidate is 16 kHz mono PCM16, to be measured on the actual HU. Do not import AA ACK windows or discard the first twenty packets—HeadSwap's current skip count is zero.

The primary native route is a recorder **INJECTOR AudioMix**, `ROUTE_FLAG_LOOP_BACK`, and `AudioPolicy.createAudioTrackSource(mix)`. Injected PCM is matched to the actual recognizer **UID AND capture preset**, with the actual audio session ID when known. Multiple recorders with the same UID/preset require session selection or a single active voice session. The OEM framework contains this implementation and its permission checks. [AudioMixingRule](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/media/java/android/media/audiopolicy/AudioMixingRule.java).

Determine the recorder identity through privileged recording configuration, register before it opens, then invoke the resolved Android assistant action. The UI package can differ from the recognition-service UID. Do not claim a late route attachment always redirects an already-open recorder. App recording permissions and microphone privacy remain in effect.

Use the same policy owner as output routing. The injector's `USAGE_VIRTUAL_SOURCE=15` is excluded from output sinks, preventing a capture/injection loop. The USB reader enqueues bounded PCM; a separate worker writes AudioTrack. Stop/cancel/disconnect invalidates the voice epoch, ends HU capture, releases the track, detaches the targeted mix and releases VR ownership. Unrelated Android recorders must retain their own inputs.

The alternative for an application-owned voice UI is API 33+ [RecognizerIntent.EXTRA_AUDIO_SOURCE](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE): pass a PCM pipe and explicit rate/channels/encoding to SpeechRecognizer. Recognition providers may ignore that extra, so prove it uses the supplied PCM rather than the box microphone. This is not a universal input replacement for any assistant/keyboard. Our own TTS can provide PCM through [UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener); avoid both playing and recapturing the same synthesis.

The voice-routing probe uses a known PCM pattern, a targeted ordinary AudioRecord and a second control recorder. Prove delivery to the target, isolation from the control and removal of the route. Then validate a real HU→assistant→HU reply. Begin with push-to-talk; simultaneous listening/reply and wake-word support need measured echo cancellation and their own lifecycle.

## Navigation from Yandex Navigator / Maps

There are three distinct results: map pixels in the accepted video stream; spoken guidance in TTS; and semantic maneuvers for the HU/cluster/HUD. The owner explicitly wants the latter from the navigation application.

The first adapter should inspect the **actual installed Yandex app/version**, its supported external interfaces and navigation notifications. `NotificationListenerService` can expose notifications after the owner's access grant, but `CATEGORY_NAVIGATION` defines no standard mandatory maneuver/distance/route schema. Capture real left/right/roundabout/distance/reroute/end samples before implementing an app-specific parser. If data is absent, report that field as unknown; do not invent turn instructions. [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

Yandex NaviKit provides typed guidance when integrated into **our own navigation application**. It is not an API to read routes from the installed Yandex Navigator/Maps app. AndroidX `NavigationManager.updateTrip` likewise is a producer API for a participating navigation app and car host, not a generic subscription to another app's route. [Yandex guidance API](https://yandex.com/maps-api/docs/mapkit/android/generated/navigation/guidance.html), [AndroidX NavigationManager](https://developer.android.com/reference/androidx/car/app/navigation/NavigationManager).

Planned boundary:

```text
Yandex app / verified app adapter
  → NavigationProvider: route state, maneuver, road, distance, source/sequence/time
  → NavigationCoordinator: current route + HU subscription + peer schema
  → bounded latest-distance / preserved turn-and-end events
  → CarLife CMD writer
```

CarLife subscriptions use `0x18043` / reply `0x10044`, START `0x18045`, STOP `0x18046`. Navigation subscription ID is **0**; functional NAVI module ID is **2**. They are different namespaces. Maneuver output is `NAVI_NEXT_TURN_INFO=0x10030`. The HU decides whether to render it on its own screen, forward to a cluster/HUD, or ignore it. A subscription is not proof of visible cluster support.

A real schema conflict must be resolved with peer evidence: the public old Baidu proto has required **int32 time** at field 6; HeadSwap's newer Baidu analysis uses **bytes turnIconData** at that same field number. HeadSwap offers TIME/ICON/BOTH/NONE; emitting both types is not automatically a safe universal choice. Keep explicit profile-specific encoding and validate against a known working session on the target HU. [Old Baidu schema](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeNaviNextTurnInfoProto.proto), [HeadSwap schema research](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/docs/cluster-metadata-recon.md).

A provider must distinguish no route, active route, reroute, completion and source loss. Source loss is not arrival. Reconnect can replay only a fresh real snapshot after subscription. The existing MD keeps metadata support false until an actual provider and validated encoder exist. No placeholder route is sent during normal driving.

## Implementation order and acceptance

1. **Current candidate:** prove real mixed MEDIA sound through USB while preserving video/input; record release and reconnect behavior.
2. **Policy routing:** two disjoint output sinks, then real Yandex speech/music and assistant usage. Establish per-lane PTS, INIT/STOP, focus and no duplicate PCM. Extend the existing audio worker/writer; preserve the video architecture.
3. **Yandex provider and metadata:** collect the actual app contract, implement typed provider/coordinator and the peer's navigation schema; test real maneuvers on the HU and separately on a cluster/HUD if present. Add MediaSession control/metadata using the same genuine producer rule.
4. **Voice:** targeted recorder-route proof, then HU button/mic→native recognizer→VR reply; cancel, repeated requests, route removal and MEDIA restoration. Validate simultaneous navigation afterward.
5. **Combined acceptance:** real music + guidance + voice + touch, radio transitions, disconnect/reconnect and cold power-on, with bounded queues and no retained audio routes. A successful API call, packet or synthetic test is recorded separately from a heard/seen user result.

No firmware modification is assumed necessary for these routes. If a concrete runtime rejection requires one, first identify the exact failing API/policy and prepare a scoped change; static method availability alone cannot establish runtime success.
