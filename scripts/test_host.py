#!/usr/bin/env python3
"""Run the public HU regression subset; no hardware or private fixtures needed."""
import argparse
import os
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", "--stdlib-only", action="store_true", help="stdlib-only framing, geometry, touch, transport and CLI tests")
    args = parser.parse_args()
    names = ["test_carlife_protocol", "test_carlife_audio", "test_carlife_touch_geometry", "test_native_usb",
             "test_projection_config", "test_gui_action_schedule", "test_public_hu", "test_h264_mode"]
    if not args.core:
        names += ["test_carlife_client", "test_gui_mouse_events"]
    suite = unittest.TestSuite()
    for name in names:
        found = unittest.defaultTestLoader.discover(str(ROOT / "tests"), pattern=name + ".py")
        def flatten(tests):
            for item in tests:
                if isinstance(item, unittest.TestSuite):
                    yield from flatten(item)
                else:
                    yield item
        for case in flatten(found):
            if args.core and case.id().endswith("test_native_client_reuses_handshake_input_and_reset_without_socket"):
                continue  # Starts the real PyAV decoder worker; covered by the full suite.
            suite.addTest(case)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return not result.wasSuccessful()


if __name__ == "__main__":
    raise SystemExit(main())
