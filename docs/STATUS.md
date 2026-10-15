# Validation record

[Русский](ru/STATUS.md) · [Home](../README.md)

Snapshot: 14 October 2026. This is a summarized engineering record. Raw device captures and personal identifiers are kept out of the public repository.

## Hardware evidence from the development project

The tested setup was an Android 15 SM6350 TBox with a Honda head unit identified as `8A250-3A3F-2`, software `01.000.600 / RH00457A 3B4A`. Stable separate box power was used for the accepted cold start. Power cycling on the head unit's supply previously caused repeated reboots; it was not counted as a successful cold start.

The owner confirmed the real launcher/app picture, functioning Home/Back, the floating navigation control, and automatic picture/control after a full power removal with the head unit ready. Native USB carried video and input. Wi-Fi ADB was a separate development channel, not the image/input transport.

The existing 900-second S1 acceptance concerned **SELF / DEV_STREAM_OVER_ADB**. It is not described as a 900-second independent Honda/native-USB soak. Later native and cold-start checks are separate results.

The radio failure was traced to missing common OEM startup (`vendor.qcom.main.start=1`) when bypassing the OEM projection script. After restoring that bootstrap, phone/subscription services, LTE and validated cellular networking returned; both network Settings pages opened. A reboot also confirmed the fix. The serial-console boot configuration was removed in a separately verified boot image, and the owner confirmed that its warning disappeared.

The physical mode-button experiment measured one approximately 3.26-second press. Android reported `KEY_INSERT` and showed a CarPlay selection dialog. The dialog was cancelled. This establishes the button event/UI path, not an actual backend change or a three-mode button cycle.

Two-finger experiments did not establish independent coordinates for the second finger on the tested Honda. No conclusion is made about the physical touchscreen's general multitouch capability.

## Candidate prepared on 15 October 2026

The preserved publication baseline below is historical. The feature branch adds opt-in output PCM, MEDIA/TTS evidence in HU, legacy gesture multitouch and startup phase timings. It does not replace hardware acceptance: the device was unavailable and no device state changed. Current offline checks: 31 MD JVM suites, 116 full HU tests, 91 runtime tests and 9 candidate-package tests. See [implementation, build and hardware plan](AUDIO_CANDIDATE.md) and [additional CarLife services](CARLIFE_SERVICES.md).

## Source publication checks (baseline commit 3b344d4)

- All 21 production MD Java sources are unchanged from the working development baseline.
- The portable MD builder passed 26 JVM suites and reproduced JAR SHA-256 `347c6ef1fdf0fa25d0c8ae4bd333c8cfce2ed6ed6d7c70cba9b6cbd1c55b87dd` with the original toolchain.
- The public runtime builder reproduced the accepted runtime archive (`a602efcebd2ae3bbf24e20f1589ec3011da4397a749e5745e0204c31a1eeebba`) and observer (`a109a2a1270a09e25c87199cb9fa027c90db61dc511d846cb2fc6b94806c814e`). The reference system overlay also built successfully from owner-supplied OEM inputs.
- Runtime/observer regressions: 85 tests passed; saved-selector shell tests: 9 passed.
- The host HU subset passed 97 tests from a separate clean source directory; its standard-library subset passed 78 tests.
- The system provision suite uses synthetic local payloads/init input and tests the real generated provision shell. Its 21 tests cover default selection, preservation, partial install, migration and rejection of inconsistent existing state.
- The optional OEM APK builder was checked offline against the exact original inputs: expected method counts, signature verification and 599/1226 unrelated APK entries preserved.

These checks do not make a new device, altered firmware or the new portable HU entry point hardware-tested. Source build output, SDKs, private keys, APKs and media captures are ignored by Git.

## Full OTA experiment

A complete local candidate was built from the exact reference firmware. All ten partitions were independently reconstructed, payload/AVB/FEC verified, and a second build produced identical ZIP bytes. Kernel/vendor and other untouched partitions were compared. This candidate was **not installed or boot-tested**. Its system-based startup differs from the accepted runtime's Magisk-based early integration.

The candidate is not included here. Releasing a source integration is not a claim that its full image is ready to flash on another TBox.

## Remaining work

1. Accept the final integrated system image on its actual target, with matching recovery and power.
2. Validate additional head units and Android-box profiles.
3. Measure end-to-end touch-to-photon latency; do not infer it by subtracting timestamps from different clocks.
4. Add and test audio/microphone/voice, required authentication providers and confirmed multi-pointer input where available.
5. Validate a saved three-mode physical-button flow and enforcing SELinux integration.
