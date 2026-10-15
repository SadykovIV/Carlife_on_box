"""CarLife HU Lab: explicit native USB, development TCP and offline trace replay."""
import argparse
from datetime import datetime, timezone
import json
import importlib
from pathlib import Path
import time

from .client import HUClient, replay
from .usb import LibUSB
from .public import prepare_usb


def positive(value):
    number = float(value)
    if not 0 < number <= 86400:
        raise argparse.ArgumentTypeError("duration must be greater than zero and at most 86400 seconds")
    return number


def audio_budget(value):
    number = float(value)
    if not 0 < number <= 1024:
        raise argparse.ArgumentTypeError("audio capture budget must be greater than zero and at most 1024 MiB")
    return number


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("discover", help="list USB descriptors; does not claim interfaces or change modes")
    offline = commands.add_parser("replay", help="count/validate a saved rx.bin without contacting a device")
    offline.add_argument("capture", type=Path)
    for command in ("usb", "tcp"):
        run = commands.add_parser(command, help="native accessory USB" if command == "usb" else "development single-stream TCP")
        run.add_argument("--output", type=Path, help="new evidence directory (default: runs/TIMESTAMP)")
        run.add_argument("--duration", type=positive, default=3600)
        run.add_argument("--width", type=int, default=1280)
        run.add_argument("--height", type=int, default=720)
        run.add_argument("--fps", type=int, default=30)
        run.add_argument("--touch-profile", choices=("legacy-single", "legacy-dual", "action3-multi"), default="legacy-single")
        run.add_argument("--display-policy", choices=("fit", "crop"), default="fit")
        run.add_argument("--rotation", type=int, choices=(0, 90, 180, 270), default=0)
        run.add_argument("--allow-mode-fallback", action="store_true", help="accept a different valid video mode announced by MD")
        run.add_argument("--trace-mode", choices=("full", "sampled"), default="sampled")
        run.add_argument("--headless", action="store_true", help="decode and save evidence without a window")
        run.add_argument("--audio-capture", action="store_true", help="also save bounded MEDIA/TTS PCM as WAV; no live audio playback")
        run.add_argument("--audio-capture-limit-mib", type=audio_budget, default=8,
                         help="combined PCM WAV limit per HU session (default: 8 MiB); raw rx.bin has its own existing budget")
        if command == "usb":
            run.add_argument("--device", required=True, help="exact physical_key from discover, e.g. 1:2.3")
            run.add_argument("--aoa-start", action="store_true", help="explicitly permit one AOA switch; it may disconnect USB ADB")
        else:
            run.add_argument("--host", default="127.0.0.1")
            run.add_argument("--port", type=int, required=True)
    return result


def headless(host, port, output, duration, options):
    client = HUClient(host, port, output, **options)
    try:
        client.start()
        deadline = time.monotonic() + duration
        while time.monotonic() < deadline and not client.error:
            time.sleep(.05)
    except KeyboardInterrupt:
        pass
    finally:
        client.stop()
    return client.summary()


def main(argv=None):
    arguments = parser().parse_args(argv)
    if arguments.command == "discover":
        with LibUSB() as usb:
            print(json.dumps(usb.discover(), indent=2))
        return 0
    if arguments.command == "replay":
        print(json.dumps(replay(arguments.capture), indent=2))
        return 0
    if not (0 < arguments.width <= 4096 and 0 < arguments.height <= 4096 and 0 < arguments.fps <= 120):
        raise ValueError("video mode must be 1..4096 pixels and 1..120 fps")
    if arguments.command == "tcp" and not 1 <= arguments.port <= 65535:
        raise ValueError("TCP port must be 1..65535")
    output = arguments.output or Path("runs") / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    if output.exists():
        raise FileExistsError(f"Output directory already exists: {output}")
    # Fail before any AOA transition if the local decoder/window is unavailable.
    for module in (["av", "PIL"] if arguments.headless else ["av", "PIL", "PySide6.QtWidgets"]):
        try:
            importlib.import_module(module)
        except ImportError as error:
            raise RuntimeError("Install the host dependencies: python -m pip install -r requirements-hu.txt") from error
    output.mkdir(parents=True, exist_ok=False)
    factory = prepare_usb(arguments.device, output, aoa_start=arguments.aoa_start) if arguments.command == "usb" else None
    host, port = (None, None) if factory else (arguments.host, arguments.port)
    options = dict(width=arguments.width, height=arguments.height, fps=arguments.fps,
        touch_profile=arguments.touch_profile, trace_mode=arguments.trace_mode,
        display_policy=arguments.display_policy, rotation=arguments.rotation,
        allow_mode_fallback=arguments.allow_mode_fallback, transport_factory=factory,
        backend="DIRECT_USB_AOA" if factory else "DEV_STREAM_OVER_ADB",
        audio_capture=arguments.audio_capture, audio_capture_limit_mib=arguments.audio_capture_limit_mib)
    print(f"Evidence: {output.resolve()}", flush=True)
    if arguments.headless:
        summary = headless(host, port, output, arguments.duration, options)
    else:
        from .gui import run_gui
        summary = run_gui(host, port, output, duration=arguments.duration,
                          title="CarLife on Box — experimental HU", **options)
    print(json.dumps(summary, indent=2))
    return 1 if summary.get("error") or summary.get("any_session_error") or summary.get("gui_action_errors") else 0


if __name__ == "__main__":
    raise SystemExit(main())
