# Audio, multitouch and startup candidate

[Русский](ru/AUDIO_CANDIDATE.md) · [Home](../README.md)

Status: **implemented and tested offline; not installed or hardware accepted**. The preserved reference is commit `3b344d45ae34df215166385ba0c65f195652f5d8`, MD `347c6ef1…`, runtime `a602efce…`. New work is on `feature/audio-startup-multitouch`. The box was unavailable during this work. The candidate is not the existing full-image experiment.

## What changed

The reference MD had no PCM source or audio sender; the missing sound was expected. This candidate adds:

- Android output capture through `AudioRecord` / `REMOTE_SUBMIX`, requesting and checking 48 kHz, stereo, PCM16. It uses the process's existing privileges; no microphone capture, permission grants, fake attribution context or policy changes.
- CarLife channel 3 MEDIA: INIT `0x30001`, DATA `0x30006`, STOP `0x30002`. INIT contains rate / channel **count** / bits; PCM is interleaved signed 16-bit little-endian. Twenty milliseconds is 3840 bytes.
- An independent source lease and a bounded audio queue. At most ten DATA chunks are queued; chunks older than 200 ms are dropped. INIT/STOP remain ordered across restart. Audio capture failure is reported without closing the video session.
- One transport writer: command priority and alternating ready audio/video work. MUSIC status has its own coalesced, session-tagged slot; it cannot block the writer on its own command queue. Fresh VERSION discards old PCM/status. Video RESET and PAUSE preserve music; HU MUSIC=0 / PAUSE_MEDIA stop capture and inject Android MEDIA_PAUSE. Playback callbacks resume PCM on a subsequent media-player start; continuing old PCM or an event marked NAVIGATION/ASSISTANT cannot undo radio selection. Re-registering the callback at HU mute discards older queued events.
- `AUDIO_TRANSMISSION_MODE=1` selects Bluetooth and suppresses USB PCM. This candidate does not implement Bluetooth playback. MUSIC active requires both transmitted INIT and observed Android output. The playback callback uses one session-owned HandlerThread; it is unregistered on reset/close. If the optional playback API fails, basic PCM remains available with an explicit degraded diagnostic and HU-command resumption.
- MD progress counters for source activity, nonzero PCM samples, drops and writes. DATA timestamps advance by captured sample count from a monotonic start estimate, modulo 2^32; they are not hardware capture timestamps or proof of AV synchronization.
- HU MEDIA/TTS parsing, independent lane state, malformed-audio isolation and optional bounded WAV capture (`--audio-capture`). The Mac HU does not play audio live. Raw `rx.bin` already contains received PCM even without WAV capture; treat local traces accordingly.

Audio defaults to **off**. This retains the known projection behavior until an explicit candidate run. The reference firmware statically contains the remote-submix HAL and a matching PCM profile, but that does not prove runtime permission, routing, nonzero capture, protected-content compatibility or audible output on a head unit. Remote submix may reroute local playback; restoration after release must be tested.

## HeadSwap review

Compared [v1.2.0 / 0f5502a](https://github.com/YegorkaPomidorka/headswap/releases/tag/v1.2.0) with [6835e50…0f5502a](https://github.com/YegorkaPomidorka/headswap/compare/6835e50dbc5cd753f8ecfd7e851ad2ac320577ad...0f5502ab76e2f220b68fa1e2c85b66838f01fd81): 51 commits. The new release adds legacy two-contact input; v1.1.11 also adds updater checks and experimental 1440p.

HeadSwap receives already separated MEDIA/SPEECH PCM and navigation/voice events from **Android Auto**. It does not supply a native Android-box output-capture implementation. Its useful CarLife behavior is INIT before PCM, MUSIC state, radio/source switching, independent VIDEO_PAUSE, TTS/VR sequencing and bounded audio. AAP ACK, HFP autoplay suppression and AA warm-session reuse belong to its other side and are not copied into this project. See [AaBridge](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/aa/AaBridge.kt) and [CarLifeSession](https://github.com/YegorkaPomidorka/headswap/blob/0f5502ab76e2f220b68fa1e2c85b66838f01fd81/android/app/src/main/java/com/yegorkapomidorka/headswap/CarLifeSession.kt).

HeadSwap is GPL-3.0; this implementation independently uses the public Baidu wire contract and the project's own session/queue/input design. No Kotlin application source was copied. [Baidu audio schema](https://github.com/milesking/apollo-DuerOS/blob/6a07419818f46aadda34c5f55e63d4a1fc8136ef/CarLife-Vehicle-Lib/LibSource/protobuf/proto/CarlifeMusicInitProto.proto).

Additional functions—separate navigation/voice audio, microphone/assistant, maneuver metadata, media controls and covers—require native producers and coordinated focus. See [CarLife services design](CARLIFE_SERVICES.md). They are not claimed by the mixed MEDIA candidate.

## Re-review corrections

A second source review on 15 October confirmed that upstream still points to v1.2.0 / `0f5502a`. The Baidu MEDIA wire IDs, header and INIT format match the candidate. Further corrections:

- Start the sample-count clock after AudioRecord startup, so source-open time is not added to the timestamp error.
- A temporary occupied source lease must not mark capture as permanently attempted.
- Accept the first fresh DOWN after a legacy/ACTION3 format change using the existing CANCEL→DOWN path.
- Keep the current runtime resolver/version for the audio update; neither a resolver migration nor a Mac HU pre-test is required.
- Use MEDIA + TTS first and the standard protobuf-compatible navigation BOTH format; separate VR output and an advance reference capture are not prerequisites.

Also compared native capture with [scrcpy AudioDirectCapture](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/audio/AudioDirectCapture.java) and [AudioRecordReader](https://github.com/Genymobile/scrcpy/blob/a60891aea193d92e7e5c3942700eca63f9d19a5f/server/src/main/java/com/genymobile/scrcpy/audio/AudioRecordReader.java). Its shell attribution and Android 11 foreground workarounds are not automatically needed by this Android 15 privileged runtime. Actual AudioRecord errors should determine any adaptation. Hardware capture timestamps are a later synchronization improvement, not a condition for basic PCM output. scrcpy is a source reference, not a runtime dependency.

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
  --output build/runtime-audio-candidate
python3 scripts/test_host.py
python3 -m unittest discover -s tests -p 'test_boot_native_*.py'
python3 -m unittest discover -s tests -p test_build_runtime_options.py
```

Keep the default runtime version for this update: the existing resolver, selector, boot hook and observer need no migration. Build into a new local output directory and retain the baseline archive. Update the stopped runtime through the existing payload-update path (MD and launch controller), then start its one existing owner. The optional custom-version flag is for a separate port, not an audio prerequisite. No full firmware image or selector reinstall is needed.

The MD option is `--audio-source remote-submix`. Selecting the retained baseline restores the complete prior runtime; `off` disables only the audio source. The installed reference still needs runtime verification of audio permissions and routing, not a new SDK or Android Auto on the box.

## First hardware experiment

Start on the actual car HU: play a known local stereo file on the box and verify audible left/right sound while video and touch continue. Record MD source/USB counters. A Mac HU run or WAV capture is an optional diagnostic when sound is absent or distorted, not a prerequisite for trying the car. For that diagnostic, `--audio-capture --audio-capture-limit-mib 8` records PCM format, channels, timestamps and drops; the Mac sink itself does not play audio.

Then check radio→CarLife, VIDEO_PAUSE, RESET, reconnect and capture release/local-route restoration. Check that the Android player actually handles MEDIA_PAUSE and that its next Play resumes sound; offline callback/key tests do not establish behavior in every player. Follow with a mixed video/audio/touch run and normal applications.

Startup timing and real multi-pointer compatibility can be tested independently of audio. With the HU already ready, record power-on and the visible screen alongside the existing phase logs. Navigation metadata and microphone work can also proceed independently; see the ordered [services plan](CARLIFE_SERVICES.md).
