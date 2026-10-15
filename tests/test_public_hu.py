"""Standalone CLI and explicit USB selection, using only fake host devices."""
from dataclasses import asdict, replace
from contextlib import redirect_stdout, redirect_stderr
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from hu_lab.__main__ import main, parser
from hu_lab.public import prepare_usb, select_port
from hu_lab.usb import RecoveryBlocked, USBIdentity


class PublicHUTests(unittest.TestCase):
    def setUp(self):
        self.ident = USBIdentity(1, (2, 3), 0x1234, 0x5678, 4)
        self.item = {"identity": asdict(self.ident), "physical_key": "1:2.3"}

    def test_selection_requires_one_exact_port(self):
        self.assertIs(select_port([self.item], "1:2.3"), self.item)
        for devices in ([], [self.item, self.item]):
            with self.assertRaises(ValueError):
                select_port(devices, "1:2.3")

    def test_prepare_reads_serial_without_claim_or_start_and_pins_it(self):
        events = []
        item = self.item
        class Handle:
            def __enter__(self): return self
            def __exit__(self, *_): events.append("handle_close")
            def serial_sha256(self): events.append("read_serial"); return "a" * 64
        class USB:
            def __enter__(self): return self
            def __exit__(self, *_): events.append("usb_close")
            def discover(self): events.append("discover"); return [item]
            def open_exact(self, identity): events.append("open"); return Handle()
        with tempfile.TemporaryDirectory() as output:
            factory = prepare_usb("1:2.3", output, usb_factory=USB)
            self.assertFalse(factory.allow_aoa_start)
            self.assertEqual(events, ["discover", "open", "read_serial", "handle_close", "usb_close"])
            factory.admission.require(factory.identity)
            factory.admission.require(replace(factory.identity, vid=0x18d1, pid=0x2d00))
            with self.assertRaises(RecoveryBlocked):
                factory.admission.require(replace(factory.identity, ports=(2, 4)))
            with self.assertRaises(RecoveryBlocked):
                factory.admission.require(replace(factory.identity, serial_sha256="b" * 64))
            selection = Path(output) / "usb-selection.json"
            self.assertFalse(json.loads(selection.read_text())["automatic_recovery_verified"])
            selection.write_text("changed")
            with self.assertRaises(RecoveryBlocked):
                factory.admission.require(factory.identity)
            with self.assertRaises(FileExistsError):
                prepare_usb("1:2.3", output, usb_factory=USB)

    def test_aoa_switch_is_explicit_and_default_is_attach_only(self):
        args = parser().parse_args(["usb", "--device", "1:2.3"])
        self.assertFalse(args.aoa_start)
        args = parser().parse_args(["usb", "--device", "1:2.3", "--aoa-start"])
        self.assertTrue(args.aoa_start)

    def test_audio_wav_is_opt_in_and_budget_is_validated(self):
        args = parser().parse_args(["tcp", "--port", "7240"])
        self.assertFalse(args.audio_capture)
        self.assertEqual(args.audio_capture_limit_mib, 8)
        for value in ("0", "-1", "nan", "inf", "1025"):
            with self.subTest(value=value), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                parser().parse_args(["tcp", "--port", "7240", "--audio-capture-limit-mib", value])

    def test_audio_cli_passes_options_to_client_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "new"
            with patch("hu_lab.__main__.headless", return_value={}) as run, \
                 patch("hu_lab.__main__.importlib.import_module"), redirect_stdout(io.StringIO()):
                result = main(["tcp", "--port", "7240", "--headless", "--output", str(output),
                               "--audio-capture", "--audio-capture-limit-mib", "2"])
            self.assertEqual(result, 0)
            self.assertTrue(run.call_args.args[-1]["audio_capture"])
            self.assertEqual(run.call_args.args[-1]["audio_capture_limit_mib"], 2)

    def test_invalid_video_mode_has_no_usb_side_effect(self):
        with patch("hu_lab.__main__.prepare_usb", side_effect=AssertionError("USB must not be touched")):
            with self.assertRaises(ValueError):
                main(["usb", "--device", "1:2.3", "--width", "0"])

    def test_missing_decoder_fails_before_usb_preparation(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "new"
            with patch("hu_lab.__main__.prepare_usb", side_effect=AssertionError("USB must not be touched")), \
                 patch("hu_lab.__main__.importlib.import_module", side_effect=ImportError("test")):
                with self.assertRaisesRegex(RuntimeError, "requirements-hu.txt"):
                    main(["usb", "--device", "1:2.3", "--aoa-start", "--headless", "--output", str(output)])
            self.assertFalse(output.exists())

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "keep"
            marker.write_text("baseline")
            with patch("hu_lab.__main__.prepare_usb", side_effect=AssertionError("USB must not be touched")):
                with self.assertRaises(FileExistsError):
                    main(["usb", "--device", "1:2.3", "--output", directory])
            self.assertEqual(marker.read_text(), "baseline")


if __name__ == "__main__":
    unittest.main()
