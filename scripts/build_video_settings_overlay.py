#!/usr/bin/env python3
"""Compose a Settings-only Magisk overlay from a baseline ZIP and a verified OEM UI build.

Offline: does not install, sign APKs, contact devices or alter the input files.
"""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile

BASELINE_SHA256 = "cb3799ad6a4b4c166eb12d4847730f8860b580d67ab5d979ff09c77d9362b5a0"
OEM_CERT_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
SETTINGS = "system/app/SudingSettings_chelianyi_new/SudingSettings_chelianyi_new.apk"
MAIN = "system/app/MainAiBox/MainAiBox.apk"
ENTRIES = {"module.prop", "service.sh", MAIN, SETTINGS}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def apk_unchanged_entries(data):
    """APK DEX and signing blocks may differ; all other ZIP entries must stay identical."""
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        return {
            name: sha(archive.read(name)) for name in names
            if not re.fullmatch(r"classes(?:[2-9][0-9]*)?\.dex", name)
            and not re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", name.upper())
        }


def compose(base_module, ui_build, output, baseline_sha256=BASELINE_SHA256,
            archive_name="carlife-oem-selector-v1.zip"):
    base_module, ui_build, output = map(Path, (base_module, ui_build, output))
    if Path(archive_name).name != archive_name or not archive_name.endswith(".zip"):
        raise ValueError("Archive name must be a ZIP basename")
    baseline = base_module.read_bytes()
    if not re.fullmatch(r"[0-9a-f]{64}", baseline_sha256) or sha(baseline) != baseline_sha256:
        raise ValueError("Baseline module SHA256 mismatch")
    manifest_bytes = (ui_build / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    records = [item for item in manifest["apk_outputs"] if item.get("name") == "SudingSettings"]
    if len(records) != 1:
        raise ValueError("Expected exactly one Settings build record")
    record = records[0]
    if record.get("output") != "SudingSettings-selector.apk":
        raise ValueError("Unexpected Settings output filename")
    settings = (ui_build / record["output"]).read_bytes()
    if sha(settings) != record.get("output_sha256"):
        raise ValueError("Settings APK differs from verified build")
    if record.get("certificate_sha256") != OEM_CERT_SHA256:
        raise ValueError("OEM certificate mismatch in build record")
    if record.get("signature_verified") is not True or record.get("manifest_resources_native_unchanged") is not True:
        raise ValueError("Settings build signature/resources verification missing")
    with zipfile.ZipFile(io.BytesIO(baseline)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != ENTRIES:
            raise ValueError("Unexpected baseline module entries")
        module = archive.read("module.prop").decode("utf-8")
        if re.findall(r"^id=(.*)$", module, flags=re.MULTILINE) != ["carlife_oem_selector_v1"]:
            raise ValueError("Unexpected baseline module ID")
        if apk_unchanged_entries(archive.read(SETTINGS)) != apk_unchanged_entries(settings):
            raise ValueError("Settings manifest/resources/native or other non-DEX entries changed")
        if archive.read(SETTINGS) == settings:
            raise ValueError("Settings APK is unchanged")
        preserved = {name: sha(archive.read(name)) for name in names if name != SETTINGS}
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as target:
            target.comment = archive.comment
            for info in archive.infolist():
                target.writestr(copy.copy(info), settings if info.filename == SETTINGS else archive.read(info.filename))
    result = buffer.getvalue()
    with zipfile.ZipFile(io.BytesIO(baseline)) as old, zipfile.ZipFile(io.BytesIO(result)) as new:
        changed = [name for name in old.namelist() if old.read(name) != new.read(name)]
        if changed != [SETTINGS]:
            raise ValueError("Not a Settings-only module update")
    # Do not create a partial output for rejected input, or overwrite a previous build.
    output.mkdir(parents=True, exist_ok=False)
    (output / archive_name).write_bytes(result)
    report = {
        "schema": 1,
        "verdict": "SETTINGS_ONLY_OVERLAY_COMPOSITION_PASS",
        "archive": archive_name,
        "zip_sha256": sha(result),
        "baseline_zip_sha256": sha(baseline),
        "settings_apk_sha256": sha(settings),
        "oem_certificate_sha256": OEM_CERT_SHA256,
        "verified_ui_build_manifest_sha256": sha(manifest_bytes),
        "changed_entries": changed,
        "preserved_entries": preserved,
        "source_files": manifest.get("source_files", {}),
        "signature_verification": "Recorded by build_oem_selector.py; this compositor checks hashes, not APK signatures anew.",
        "device_operations": 0,
        "licensing": "OEM APKs are third-party binaries, not relicensed under the project Apache-2.0 license.",
    }
    (output / "composition-manifest.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-module", type=Path, required=True, help="Exact selector v5 baseline ZIP")
    parser.add_argument("--ui-build", type=Path, required=True, help="Verified build_oem_selector.py output directory")
    parser.add_argument("--output", type=Path, required=True, help="New output directory")
    parser.add_argument("--baseline-sha256", default=BASELINE_SHA256,
                        help="Expected trusted baseline digest; defaults to the released reference v5 ZIP")
    parser.add_argument("--archive-name", default="carlife-oem-selector-v1.zip")
    args = parser.parse_args()
    report = compose(args.base_module, args.ui_build, args.output, args.baseline_sha256, args.archive_name)
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        sys.exit(1)
