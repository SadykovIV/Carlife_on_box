"""Offline packaging regression tests; fixtures are synthetic, never OEM code or keys."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("overlay_builder", ROOT / "scripts/build_video_settings_overlay.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def fake_apk(dex, resource=b"unchanged resource"):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("AndroidManifest.xml", b"synthetic manifest")
        z.writestr("resources.arsc", resource)
        z.writestr("lib/arm64-v8a/libfixture.so", b"synthetic native fixture")
        z.writestr("classes.dex", dex)
        z.writestr("META-INF/CERT.RSA", b"synthetic signature; NOT a signed APK")
    return buf.getvalue()


class VideoSettingsOverlayTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "baseline.zip"
        self.ui = self.root / "ui"
        self.ui.mkdir()
        self.out = self.root / "out"
        with zipfile.ZipFile(self.base, "w") as z:
            for name, data in [
                ("module.prop", b"id=carlife_oem_selector_v1\nversion=v5\n"),
                ("service.sh", b"#!/system/bin/sh\n# synthetic baseline\n"),
                (builder.MAIN, fake_apk(b"main")),
                (builder.SETTINGS, fake_apk(b"old settings")),
            ]:
                info = zipfile.ZipInfo(name, (2026, 10, 14, 3, 5, 44))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100755 << 16
                z.writestr(info, data)
        self.digest = builder.sha(self.base.read_bytes())
        self.write_ui(fake_apk(b"new settings"))

    def write_ui(self, data, **record_updates):
        (self.ui / "SudingSettings-selector.apk").write_bytes(data)
        record = dict(name="SudingSettings", output="SudingSettings-selector.apk",
                      output_sha256=builder.sha(data), signature_verified=True,
                      manifest_resources_native_unchanged=True,
                      certificate_sha256=builder.OEM_CERT_SHA256)
        record.update(record_updates)
        (self.ui / "manifest.json").write_text(json.dumps({"apk_outputs": [record], "source_files": {}}))

    def run_build(self, **kwargs):
        return builder.compose(self.base, self.ui, self.out, kwargs.pop("baseline_sha256", self.digest), **kwargs)

    def test_only_settings_changes_and_rebuild_is_byte_identical(self):
        original = self.base.read_bytes()
        report = self.run_build()
        self.assertEqual(report["changed_entries"], [builder.SETTINGS])
        result = (self.out / "carlife-oem-selector-v1.zip").read_bytes()
        with zipfile.ZipFile(io.BytesIO(original)) as old, zipfile.ZipFile(io.BytesIO(result)) as new:
            self.assertEqual(old.namelist(), new.namelist())
            for name in old.namelist():
                if name != builder.SETTINGS:
                    self.assertEqual(old.read(name), new.read(name))
                for field in ("date_time", "compress_type", "external_attr", "extra", "comment"):
                    self.assertEqual(getattr(old.getinfo(name), field), getattr(new.getinfo(name), field))
        builder.compose(self.base, self.ui, self.root / "again", self.digest)
        self.assertEqual(result, (self.root / "again/carlife-oem-selector-v1.zip").read_bytes())
        self.assertEqual(original, self.base.read_bytes())

    def test_bad_baseline_hash_fails_without_output(self):
        with self.assertRaisesRegex(ValueError, "Baseline module SHA256 mismatch"):
            self.run_build(baseline_sha256="0" * 64)
        self.assertFalse(self.out.exists())

    def test_modified_apk_after_verification_fails_without_output(self):
        (self.ui / "SudingSettings-selector.apk").write_bytes(fake_apk(b"tampered"))
        with self.assertRaisesRegex(ValueError, "differs from verified build"):
            self.run_build()
        self.assertFalse(self.out.exists())

    def test_changed_resources_rejected_even_with_claimed_verified_manifest(self):
        self.write_ui(fake_apk(b"new settings", b"changed resources"))
        with self.assertRaisesRegex(ValueError, "non-DEX entries changed"):
            self.run_build()
        self.assertFalse(self.out.exists())

    def test_wrong_certificate_or_missing_signature_verification_fails(self):
        for change in ({"certificate_sha256": "0" * 64}, {"signature_verified": False}):
            with self.subTest(change=change):
                self.write_ui(fake_apk(b"new settings"), **change)
                with self.assertRaises(ValueError):
                    self.run_build()
                self.assertFalse(self.out.exists())

    def test_extra_module_payload_rejected_even_if_hash_supplied(self):
        with zipfile.ZipFile(self.base, "a") as z:
            z.writestr("unreviewed.sh", b"unexpected")
        self.digest = builder.sha(self.base.read_bytes())
        with self.assertRaisesRegex(ValueError, "Unexpected baseline module entries"):
            self.run_build()
        self.assertFalse(self.out.exists())

    def test_existing_output_is_preserved(self):
        self.out.mkdir()
        marker = self.out / "existing.zip"
        marker.write_bytes(b"keep")
        with self.assertRaises(FileExistsError):
            self.run_build()
        self.assertEqual(marker.read_bytes(), b"keep")


if __name__ == "__main__":
    unittest.main()
