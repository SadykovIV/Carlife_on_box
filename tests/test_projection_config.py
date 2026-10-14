"""Projection decisions and provenance; no ADB, network or display mutation."""
import json
from pathlib import Path
import tempfile
import unittest

from hu_lab.projection import (build_projection_config, load_hu_profile, HuCaps,
                               EncoderCaps, ProjectionError)


class ProjectionConfigTests(unittest.TestCase):
    def build(self, **kwargs):
        values = dict(requested_width=1280, requested_height=720, fps=20,
                      source_size=(1920, 1080), source_density=238)
        values.update(kwargs)
        return build_projection_config(**values)

    def profile(self, **changes):
        value = {'schema_version': 1,
                 'identity': {'id': 'fixture-model-7', 'version': '2.1', 'kind': 'vendor_model_firmware'},
                 'panel_native_pixels': [1280, 720],
                 'source_geometry': {'logical_pixels': [1280, 720], 'rotation_degrees': 0},
                 'video_mode': [1280, 720, 20], 'isolated_density_dpi': 213}
        value.update(changes)
        return value

    def caps(self, **changes):
        value = dict(identity='fixture-model-7', version='2.1', identity_kind='vendor_model_firmware',
                     identity_confirmed=True, panel_size=(1280, 720), provenance='runtime_measurement',
                     evidence='fixture-runtime.json')
        value.update(changes)
        return HuCaps(**value)

    def test_unknown_density_and_ppi_remain_null(self):
        result = self.build(source_density=0).to_dict()
        self.assertIsNone(result['android_source']['density_dpi']['value'])
        self.assertEqual(result['android_source']['density_dpi']['provenance'], 'unknown')
        self.assertIsNone(result['hu']['physical_ppi']['value'])
        self.assertIsNone(result['hu']['panel_native_pixels']['value'])
        self.assertIsNone(result['hu']['rotation']['value'])
        self.assertIsNone(result['requested_video']['wire_density_field'])
        self.assertEqual(result['requested_video']['wire_fields'], ['width', 'height', 'frameRate'])
        self.assertIsNone(result['accepted_encoder'])
        self.assertNotIn('160', json.dumps(result))

    def test_existing_density_is_layout_metadata_and_does_not_apply(self):
        result = self.build().to_dict()
        self.assertEqual(result['android_source']['density_dpi']['value'], 238)
        self.assertEqual(result['android_source']['density_dpi']['unit'], 'Android_logical_density_dpi')
        self.assertFalse(result['ui_layout']['primary_density_change'])
        self.assertIsNone(result['ui_layout']['density_to_apply_to_owned_display'])

    def test_density_changes_only_owned_isolated_display(self):
        result = self.build(isolated_size=(1280, 720), isolated_density=213).to_dict()
        self.assertEqual(result['android_source']['logical_pixels']['value'], (1280, 720))
        self.assertIsNone(result['android_source']['display_id']['value'])
        self.assertEqual(result['ui_layout']['density_to_apply_to_owned_display'], 213)
        self.assertEqual(result['ui_layout']['primary_density_before']['value'], 238)
        self.assertFalse(result['ui_layout']['primary_density_change'])
        self.assertEqual(result['android_source']['density_dpi']['provenance'], 'manual_setting')

    def test_auto_and_unverified_primary_change_are_explicitly_rejected(self):
        with self.assertRaisesRegex(ProjectionError, 'AUTO_DENSITY_UNSUPPORTED'):
            self.build(ui_scale='auto')
        with self.assertRaisesRegex(ProjectionError, 'PRIMARY_DENSITY_RECOVERY_NOT_VERIFIED'):
            self.build(primary_density=320)
        with self.assertRaisesRegex(ProjectionError, 'PRIMARY_DENSITY_APPLY_UNIMPLEMENTED'):
            self.build(primary_density=320, primary_recovery_verified=True)

    def test_unknown_encoder_caps_do_not_claim_target_support(self):
        result = self.build().to_dict()
        self.assertEqual(result['status'], 'PLANNED_NOT_NEGOTIATED')
        self.assertIsNone(result['encoder_caps']['max_fps']['value'])
        self.assertIsNone(result['hu']['decoder_modes']['value'])

    def test_known_hu_and_encoder_caps_each_constrain_mode(self):
        with self.assertRaisesRegex(ProjectionError, 'HU_DECODER_MODE_NOT_CONFIRMED'):
            self.build(hu_caps=HuCaps(supported_modes=((800, 480, 20),)))
        with self.assertRaisesRegex(ProjectionError, 'ENCODER_FPS_EXCEEDED'):
            self.build(encoder_caps=EncoderCaps(max_fps=15))
        with self.assertRaisesRegex(ProjectionError, 'ENCODER_ALIGNMENT_MISMATCH'):
            self.build(requested_width=1026, encoder_caps=EncoderCaps(width_alignment=8))
        self.build(encoder_caps=EncoderCaps(supported_modes=((1280,720,20),(3840,2160,120))))

    def test_invalid_numbers_fail_before_any_setup(self):
        for key, value in [('requested_width', 0), ('requested_height', -1), ('fps', True),
                           ('source_density', False), ('source_density', 900), ('generation', -1)]:
            with self.subTest(key=key, value=value), self.assertRaises(ProjectionError):
                self.build(**{key: value})
        for value in (0, 100, 119, 641):
            with self.subTest(density=value), self.assertRaises(ProjectionError):
                self.build(isolated_size=(1280, 720), isolated_density=value)

    def test_profile_is_not_self_authenticating_or_bound_to_bluetooth_name(self):
        with self.assertRaisesRegex(ProjectionError, 'NOT_INDEPENDENTLY_CONFIRMED'):
            self.build(hu_profile=self.profile(), isolated_size=(1280, 720))
        p = self.profile(identity={'id': 'Car', 'version': '1', 'kind': 'bluetooth_name'})
        with self.assertRaisesRegex(ProjectionError, 'IDENTITY_KIND_UNSUPPORTED'):
            load_hu_profile(p)

    def test_profile_requires_identity_version_panel_and_source_match(self):
        for change in ({'version':'3'}, {'identity':'other'}, {'panel_size':(800,480)}):
            with self.subTest(change=change), self.assertRaises(ProjectionError):
                self.build(hu_profile=self.profile(), hu_caps=self.caps(**change), isolated_size=(1280,720))
        with self.assertRaisesRegex(ProjectionError, 'GEOMETRY_MISMATCH'):
            self.build(hu_profile=self.profile(), hu_caps=self.caps(), isolated_size=(1024,600))

    def test_profile_load_hash_and_application_are_recorded(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'hu.json'
            path.write_text(json.dumps(self.profile()))
            result = self.build(hu_profile=path, hu_caps=self.caps(), isolated_size=(1280,720)).to_dict()
        self.assertEqual(result['ui_layout']['density_to_apply_to_owned_display'], 213)
        self.assertEqual(len(result['manual_profile']['sha256']), 64)
        self.assertEqual(result['manual_profile']['validation'], 'IDENTITY_VERSION_PANEL_SOURCE_MATCH')
        json.dumps(result, allow_nan=False)

    def test_profile_cannot_silently_change_requested_mode_or_density(self):
        with self.assertRaisesRegex(ProjectionError, 'VIDEO_MODE_MISMATCH'):
            self.build(hu_profile=self.profile(), hu_caps=self.caps(), isolated_size=(1280,720), fps=30)
        with self.assertRaisesRegex(ProjectionError, 'DENSITY_CONFLICT'):
            self.build(hu_profile=self.profile(), hu_caps=self.caps(), isolated_size=(1280,720), isolated_density=320)
        with self.assertRaisesRegex(ProjectionError, 'REQUIRES_OWNED_DISPLAY_GEOMETRY'):
            self.build(hu_profile=self.profile(), hu_caps=self.caps(), source_size=(1280,720))

    def test_profile_unknown_schema_and_spelling_rejected(self):
        with self.assertRaisesRegex(ProjectionError, 'UNSUPPORTED_SCHEMA_VERSION'):
            load_hu_profile(self.profile(schema_version=2))
        with self.assertRaisesRegex(ProjectionError, 'UNKNOWN_FIELDS'):
            load_hu_profile(self.profile(density=160))

    def test_encoder_observation_requires_actual_geometry_and_evidence(self):
        config = self.build()
        measured = config.with_encoder_observation(width=1280, height=720, fps=20,
            coded_size=(1280,720), visible_crop=(0,0,1280,720), profile=66, level=31,
            evidence='SPS plus measured PTS and INIT_DONE fixture')
        self.assertIsNone(config.accepted_encoder)
        self.assertEqual(measured.to_dict()['status'], 'ENCODER_OBSERVED')
        self.assertEqual(measured.to_dict()['accepted_encoder']['level']['provenance'], 'runtime_measurement')
        with self.assertRaisesRegex(ProjectionError, 'ACCEPTED_MODE_MISMATCH'):
            config.with_encoder_observation(width=800,height=480,fps=20,coded_size=(800,480),
                visible_crop=(0,0,800,480),profile=66,level=31,evidence='fixture')

    def test_panel_ppi_is_never_derived_from_pixels_or_android_density(self):
        result = self.build(hu_caps=self.caps()).to_dict()
        self.assertIsNone(result['hu']['physical_ppi']['value'])
        result = self.build(hu_caps=self.caps(physical_ppi=120)).to_dict()
        self.assertEqual(result['hu']['physical_ppi']['value'], 120)
        with self.assertRaises(ProjectionError):
            self.build(hu_caps=self.caps(physical_ppi=float('nan')))

    def test_secondary_source_density_is_not_primary_density(self):
        result = self.build(source_display=4).to_dict()
        self.assertEqual(result['ui_layout']['baseline_source_density']['value'], 238)
        self.assertIsNone(result['ui_layout']['primary_density_before']['value'])





if __name__ == '__main__':
    unittest.main()
