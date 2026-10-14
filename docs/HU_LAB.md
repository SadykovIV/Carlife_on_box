# Experimental CarLife HU Lab

[Русский](ru/HU_LAB.md) · [Project](../README.md)

This desktop head-unit simulator receives video from the Android MD, decodes H.264, and sends CarLife touch, Home and Back messages. The native USB path contains no ADB tunnel. The optional TCP path is a clearly labelled development transport. This is a development tool, not a certified vehicle head unit.

## Install

Run from the repository root. Python 3.11+ is recommended. The native window was exercised on macOS; Linux portability has not been accepted on hardware. Windows is not supported by the current POSIX USB ownership lock.

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements-hu.txt
# macOS, if libusb is not installed:
brew install libusb
python -m hu_lab --help
```

Dependencies: PySide6 for the window, PyAV for H.264 decoding, Pillow for saved frame images, and a system libusb 1.0 library for native USB. None is installed on Android by these commands. The included tests also use PyAV's libx264 encoder to generate anonymous test pictures; private screen recordings are unnecessary.

## Native USB

First build/install and select the Android native MD using the board integration instructions. The box must expose an AOA-capable USB device port; the computer is the USB host. The HU does not install the MD or configure Android's gadget.

List connected USB descriptors:

```sh
python -m hu_lab discover
```

Use the exact `physical_key` for your box. `1:2.3` below is an example, not a universal device identifier. Discovery does not open or claim devices. Starting a selected session reads that device's serial descriptor, stores its hash, and verifies the same port and serial again when opening the transport.

For a device already in accessory mode (`18d1:2d00` or `18d1:2d01`):

```sh
python -m hu_lab usb --device '1:2.3' \
  --width 1280 --height 720 --fps 30 --duration 600
```

For a device waiting for a host-initiated AOA transition:

```sh
python -m hu_lab usb --device '1:2.3' --aoa-start \
  --width 1280 --height 720 --fps 30 --duration 600
```

`--aoa-start` explicitly permits one AOA request sequence. It can remove USB ADB, so retain your board's independent development/recovery route. There is no automatic USB-mode rollback, reboot, driver detach, device reset or configuration change. A failed or partially completed start is recorded and not replayed within that session. Reconnect attaches to accessory mode without issuing a second AOA start. A physical-port lock prevents two HU processes owning the same device.

The default output is a new `runs/<UTC timestamp>` directory. `--output runs/my-trial` selects a new directory; existing directories are never overwritten. Close the window to stop, or use a bounded `--duration`. For decoding without a window, add `--headless`.

## Window and input

The mouse sends a single contact through CarLife. The Home, Back, Pause, Resume, Reset and Reconnect controls also use the current protocol session. Reset discards stale queued media by generation and waits for fresh SPS/PPS and IDR before decoding. Reconnect retains errors from earlier sessions in the final report.

`--width`, `--height` and `--fps` request a video mode. By default, a different MD-announced size fails visibly; `--allow-mode-fallback` accepts a valid returned mode. Input follows the accepted video geometry. `--display-policy fit` preserves the whole picture; `crop` fills the desktop window while cropping its edges. Neither option changes Android DPI. `--rotation 0|90|180|270` rotates the window/input transform together.

`--touch-profile legacy-single` is the default. `legacy-dual` (pointerx/pointery) and `action3-multi` are explicit laboratory profiles for matching MD implementations. Supporting those formats in this simulator does not establish that a particular vehicle HU transmits coordinates for its second contact. No pseudo-multitouch is invented.

## Development TCP

For the MD's single multiplexed TCP stream, forward the actual device port separately if needed:

```sh
adb -s YOUR_DEVICE forward tcp:LOCAL_PORT tcp:MD_PORT
python -m hu_lab tcp --host 127.0.0.1 --port LOCAL_PORT --duration 60
```

Replace all uppercase placeholders. The HU never invokes ADB, starts Android processes or chooses a device on your behalf. This transport is labelled `DEV_STREAM_OVER_ADB` and is not a native USB acceptance result. It is not the legacy six-socket CarLife transport.

## Evidence and offline tests

Runs retain bounded local `trace.jsonl`, `rx.bin`, `tx.bin`, `video.h264`, frame PNGs and `summary.json`; window runs add `gui-summary.json`. USB runs also retain selection and transport events. Captures can include your display, touches and device metadata: review them before sharing. Raw traces are not included in this repository.

```sh
python scripts/test_host.py --core  # 78 stdlib-only tests, no hardware
python scripts/test_host.py         # 97 tests including Qt/PyAV, no hardware
python -m hu_lab replay runs/YOUR_RUN/rx.bin
```

The exported suite covers literal wire bytes, fragmentation, bounded media queues, geometry, touch lifecycle, native USB partial transfers/start/reconnect, RESET generation fencing, and decoding the first complete static frame before EOF. Video fixtures are generated at test time. These are offline regressions, not proof of compatibility with every box or vehicle.

The authentication profile is `SYNTHETIC_NO_AUTH_V1`, with `SELF` interoperability scope for the two project endpoints. Unknown authentication/encryption requirements stop explicitly. Audio, microphone, voice integration and the legacy six-socket transport are not implemented. Encoder PTS, host receipt and Qt paint completion are separate metrics; none alone measures physical touch-to-display latency.
