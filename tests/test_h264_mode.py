"""SPS validation uses independent codec CSD fixtures and accepted INIT_DONE geometry."""
import copy
from pathlib import Path
import unittest

from hu_lab.h264 import parse_sps, validate_sps_mode

FIXTURES = Path(__file__).resolve().parents[1] / "android-md/tests/fixtures"


def fixture(name):
    data = bytes.fromhex((FIXTURES / name).read_text())
    # Fixture CSD begins with one SPS, then the PPS. parse_sps takes RBSP payload.
    return parse_sps(data[5:data.index(b"\x00\x00\x00\x01", 4)])


class SpsModeValidationTests(unittest.TestCase):
    def setUp(self):
        self.full_hd = fixture("mode-qcom-captured-1920x1080-l40.csd.hex")
        self.accepted = {"width": 1920, "height": 1080, "fps": 30}

    def test_exact_device_1080p30_accepts_cropped_1088_level40(self):
        self.assertEqual(self.full_hd["coded_height"], 1088)
        result = validate_sps_mode(self.full_hd, self.accepted, True)
        self.assertEqual(result["status"], "PASS_BASELINE_L40_MODE_LIMITS")
        self.assertEqual(result["coded_macroblocks"], 8160)
        self.assertEqual(result["accepted_mode_macroblocks_per_second"], 244800)
        self.assertEqual(result["level_max_mbps"], 245760)
        self.assertFalse(result["failures"])

    def test_existing_720p_summary_is_preserved(self):
        sps = fixture("mode-qcom-captured-1280x720-l31.csd.hex")
        result = validate_sps_mode(sps, {"width": 1280, "height": 720, "fps": 30}, True)
        self.assertEqual(result["status"], "PASS_BASELINE_L31_MAXFS")
        self.assertEqual(result["level_max_mbps"], 108000)

    def test_accepted_geometry_mismatch_fails_despite_valid_level(self):
        result = validate_sps_mode(self.full_hd, dict(self.accepted, width=1280), True)
        self.assertEqual(result["status"], "FAIL_ACCEPTED_GEOMETRY")

    def test_requested60_cannot_use_1080_level40(self):
        result = validate_sps_mode(self.full_hd, dict(self.accepted, fps=60), True)
        self.assertEqual(result["status"], "FAIL_LEVEL_MAXMBPS")

    def test_level10_label_does_not_hide_invalid_1080_macroblocks(self):
        invalid = dict(self.full_hd, level_idc=10)
        result = validate_sps_mode(invalid, self.accepted, True)
        self.assertEqual(result["status"], "FAIL_LEVEL_MAXFS")

    def test_valid_high_profile_is_not_reported_as_baseline(self):
        result = validate_sps_mode(dict(self.full_hd, profile_idc=100), self.accepted, True)
        self.assertEqual(result["status"], "FAIL_REQUESTED_BASELINE")

    def test_dpb_overflow_is_not_accepted(self):
        result = validate_sps_mode(dict(self.full_hd, max_num_ref_frames=5), self.accepted, True)
        self.assertEqual(result["status"], "FAIL_LEVEL_MAXDPB")

    def test_unknown_level_is_partial_not_false_pass(self):
        result = validate_sps_mode(dict(self.full_hd, level_idc=99), self.accepted, True)
        self.assertEqual(result["status"], "PARTIAL_UNKNOWN_LEVEL")
        self.assertIsNone(result["level_max_mbps"])

    def test_wide_independent_x264_mode_has_its_own_limits(self):
        sps = fixture("mode-baseline-2240x976-l42.csd.hex")
        result = validate_sps_mode(sps, {"width": 2240, "height": 976, "fps": 30}, True)
        self.assertEqual(result["status"], "PASS_BASELINE_L42_MODE_LIMITS")
        self.assertEqual(result["level_max_mbps"], 522240)

    def test_diagnostics_do_not_mutate_source_or_invent_measured_fps(self):
        before = copy.deepcopy(self.full_hd)
        result = validate_sps_mode(self.full_hd, self.accepted, True)
        self.assertEqual(self.full_hd, before)
        self.assertEqual(self.accepted, {"width": 1920, "height": 1080, "fps": 30})
        self.assertNotIn("measured_fps", result)
        self.assertIn("PTS", result["remaining_checks"])


if __name__ == "__main__":
    unittest.main()
