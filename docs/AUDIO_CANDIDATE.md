# Audio, multitouch and startup candidate

[Русский](ru/AUDIO_CANDIDATE.md) · [Home](../README.md)

Status: **implemented and tested offline; not installed or hardware accepted**. The preserved reference is commit `3b344d45ae34df215166385ba0c65f195652f5d8`, MD `347c6ef1…`, runtime `a602efce…`. New work is on `feature/audio-startup-multitouch`. The box was unavailable during this work. The candidate is not the existing full-image experiment.

## What changed

The reference MD had no PCM source or audio sender; the missing sound was expected. This candidate adds:

- Android output capture through `AudioRecord` / `REMOTE_SUBMIX`, requesting and checking 48 kHz, stereo, PCM16. It uses the process's existing privileges; no microphone capture, permission grants, fake attribution context or policy changes.
- CarLife channel 3 MEDIA: INIT `0x30001`, DATA `0x30006`, STOP `0x30002`. INIT contains rate / channel **count** / bits; PCM is interleaved signed 16-bit little-endian. Twenty milliseconds is 3840 bytes.
- An independent source lease and a bounded audio queue. At most ten DATA chunks are queued; chunks older than 200 ms are dropped. INIT/STOP remain ordered across restart. Audio capture failure is reported without closing the video session.
- One transport writer: command priority and alternating ready audio/video work. MUSIC status has its own coalesced, session-tagged slot; it cannot block the writer on its own command queue. Fresh VERSION discards old PCM/status. Video RESET and PAUSE preserve music; HU MUSIC=0 / PAUSE_MEDIA suppress PCM until explicit resumption. This does **not** pause the Android player itself yet.
- `AUDIO_TRANSMISSION_MODE=1` selects Bluetooth and suppresses USB PCM. This candidate does not implement Bluetooth playback. MUSIC active follows successful transmission of INIT, not merely a capture request.
- MD progress counters for source activity, nonzero PCM samples, drops and writes. DATA timestamps advance by captured sample count from a monotonic start estimate, modulo 2^32; they are not hardware capture timestamps or proof of AV synchronization.
- HU MEDIA/TTS parsing, independent lane state, malformed-audio isolation and optional bounded WAV capture (`--audio-capture`). The Mac HU does not play audio live. Raw `rx.bin` already contains received PCM even without WAV capture; treat local traces accordingly.

Audio defaults to **off**. This retains the known projection behavior until an explicit candidate run. The reference firmware statically contains the remote-submix HAL and a matching PCM profile, but that does not prove runtime permission, routing, nonzero capture, protected-content compatibility or audible output on a head unit. Remote submix may reroute local playback; restoration after release must be tested.

## HeadSwap review

Compared [v1.2.0 / 0f5502a](https://github.com/YegorkaPomidorka/headswap/releases/tag/v1.2.0) with [6835e50…0f5502a](https://github.com/YegorkaPomidorka/headswap/compare/6835e50dbc5cd753f8ecfd7e851ad2ac320577ad...0f5502ab76e2f220b68fa1e2c85b66838f01fd81): 51 commits. The new release adds legacy two-contact input; v1.1.11 also adds updater checks and experimental 1440p.

HeadSwap receives already separated MEDIA/SPEECH PCM and navigation/voice events from **Android Auto**. It does not supply a native Android-box output-capture implementation. Its useful CarLife behavior is INIT before PCM, MUSIC state, radio/source switching, independent VIDEO_PAUSE, TTS/VR sequencing and bounded audio. AAP ACK, HFP autoplay suppression and AA warm-session reuse belong to its other side and are not copied into this project. See [AaBridge](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/AaBridge.kt) and [CarLifeSession](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/CarLifeSession.kt).

HeadSwap is GPL-3.0; this implementation independently uses the public Baidu wire contract and the project's own session/queue/input design. No Kotlin application source was copied. [Baidu audio schema](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeMusicInitProto.proto).

Additional functions—separate navigation/voice audio, microphone/assistant, maneuver metadata, media controls and covers—require native producers and coordinated focus. See [CarLife services design](CARLIFE_SERVICES.md). They are not claimed by the mixed MEDIA candidate.

## Multitouch

The native `recognized` profile now accepts legacy gesture-stream opcodes as well as transparent `pointerx/pointery`. It retains previously measured raw coordinates when optional MOVE/UP fields are omitted, preserves the surviving pointer ID, then applies video-to-display geometry once. `(0,0)` remains a valid coordinate. A new finger without any supplied position cancels only the input gesture; it does not invent a second contact or kill video.

Existing ACTION3 multi-pointer decoding, geometry, asynchronous injection, RESET handling and CSD guard remain. No car-brand whitelist is introduced. Honda's previous empty-request A/B did not reveal independent second-pointer coordinates. This candidate is not a hardware multitouch PASS for Honda, VW or another head unit.

## Startup findings

The saved accepted ordinary reboot had media capabilities ready at 14.496 s, first HU VERSION at 39.030 s, VIDEO_START at 39.634 s and first encoded AU at 41.968 s after Android started. That is **not** a physical cold-start measurement or the visible-screen timestamp. The accepted cold test had START at 37.417 s but no retained first-AU sample.

There is no mandatory 50–60-second sleep. Much of the measured interval precedes handshake: USB preparation, bind/re-enumeration, controller checks and HU response. Their individual costs are not yet isolated. The controller now annotates existing phase logs and UDC bind start/end; it adds no waits, gates or processes. `/proc/uptime` includes suspend, Java uptime does not; the clock is labelled explicitly.

HeadSwap's 1500 ms missing-START fallback already exists here. Its splash screen begins after CarLife is established; it cannot explain away the preceding USB wait. Warm AA reuse cannot survive physical power loss. No measured startup speedup is claimed.

## Reproduce locally

Use the toolchain in [BUILD_MD](BUILD_MD.md) and choose fresh output directories:

```sh
python3 scripts/build_md.py --output build/md-audio-candidate
python3 scripts/build_runtime.py \
  --md-jar build/md-audio-candidate/md-boot-native.jar \
  --android-jar "$ANDROID_SDK_ROOT/platforms/android-35/android.jar" \
  --r8-jar /path/to/r8.jar --java-home "$JAVA_HOME" \
  --clang /path/to/clang --ld-lld /path/to/ld.lld \
  --audio-source remote-submix \
  --runtime-version boot-native-audio-candidate-20261015 \
  --output build/runtime-audio-candidate
python3 scripts/test_host.py
python3 -m unittest discover -s tests -p 'test_boot_native_*.py'
python3 -m unittest discover -s tests -p test_build_runtime_options.py
```

The runtime builder changes only its staging copy. `--audio-source off` is the default. A custom version updates archive/current and both resolver/controller version guards together. It preserves the old version's source/artifacts. A new custom resolver is **not** automatically compatible with the exact resolver migration in `integration/sm6350`; this is a runtime candidate, not an approved full firmware image or a universal install command.

For the first device run, use the existing reviewed single-owner runtime deployment path, retaining its old version, selector and recovery access. Do not launch a second MD beside the boot controller. The MD option itself is `--audio-source remote-submix`; `off` or selecting the retained baseline is the audio rollback.

## First hardware experiment

Play a known local stereo test file on the box. Capture the direct-USB MEDIA INIT/DATA with the HU (`--audio-capture --audio-capture-limit-mib 8`) and compare sample rate, channel order, nonzero samples, duration, timestamps and drops. On the actual car HU verify audible left/right sound while video/touch continue. A captured WAV or a successful write alone is not proof of the car speakers playing.

Then test HU radio→CarLife, VIDEO_PAUSE, RESET, disconnect/reconnect, source failure/release, Android local routing restoration and a ten-minute mixed video/audio/touch run. Include navigation and streaming applications before accepting capture coverage. Cold boot timing follows with the HU already ready and stable power; record the physical power event and visible screen alongside protocol logs. Separate TTS/VR and assistant acceptance follow the services plan.
