# Rebuild the video-settings OEM overlay

This offline recipe reproduces the **r7 Settings-only overlay**. It preserves the existing MainAiBox APK, module metadata and service script. It does not build or install the MD runtime ZIP. The OEM APKs remain third-party binaries; see [third-party notices](../THIRD_PARTY_NOTICES.md).

## Inputs

- The source at the `v2026.10.15-r7` release tag.
- `Carlife_on_box-OEM-selector-v5-baseline.zip` from the same release's rollback assets. Its expected SHA256 is `cb3799ad6a4b4c166eb12d4847730f8860b580d67ab5d979ff09c77d9362b5a0`.
- A fresh, verified `scripts/build_oem_selector.py` output directory built from that source. Follow [Build the Android MD → Optional OEM selector APK integration](BUILD_MD.md#optional-oem-selector-apk-integration). Supply the exact original OEM APKs and matching signing certificate/private key locally; signing keys are not release assets.
- Python 3.9 or newer for composition; no Android toolchain is needed for this final ZIP step.

For r7, the newly built `SudingSettings-selector.apk` must have SHA256 `db86c671989857f1b9233905e12ed8abfbea80d6e06464252ba2d3e89403820a`. The tested build uses JDK 17.0.16, Android API 35 and R8 8.3.37; different build dependencies may produce different APK bytes. The compositor checks the output hash against the build manifest, its recorded signature verification and the expected OEM certificate, and independently compares all non-DEX/non-signature APK entries with the baseline. It does not perform APK signature verification again.

## Compose and check

From the repository root, use a new output directory:

```sh
python3 scripts/build_video_settings_overlay.py \
  --base-module /path/to/Carlife_on_box-OEM-selector-v5-baseline.zip \
  --ui-build /path/to/verified-oem-selector-build \
  --output build/video-settings-overlay \
  --archive-name Carlife_on_box-OEM-video-settings-2026.10.15.zip

shasum -a 256 build/video-settings-overlay/Carlife_on_box-OEM-video-settings-2026.10.15.zip
```

Expected r7 ZIP SHA256:

```text
de1bec80939668bfdc5af304db2e7390e74d18dfcfd61658456800a7c197a507
```

The exact r7 baseline and Settings APK reproduce that ZIP byte for byte. `composition-manifest.json` records input/output hashes and the three preserved entries. Only `system/app/SudingSettings_chelianyi_new/SudingSettings_chelianyi_new.apk` may change. The newly built MainAiBox APK is deliberately unused. Invalid input is rejected before creating output, and an existing output directory is never overwritten.

The default baseline digest pins the released v5 archive. `--baseline-sha256` exists for separately reviewed baseline adaptations, not to make another firmware compatible; it must be a trusted expected digest, not a hash blindly calculated from an unknown ZIP.

Run the independent packaging regression tests:

```sh
python3 -m unittest discover -s tests -p test_video_settings_overlay.py -v
```

These seven tests use synthetic APK-shaped ZIPs, contain no OEM code or keys, and check unchanged payload/metadata, deterministic output, wrong baseline hashes, tampered APKs, resource changes, certificate/verification records, unexpected module files and preservation of existing output. They are packaging checks, not hardware acceptance. The r7 UI was additionally checked locally with 48 configuration cases and four status-formatting cases; native USB acceptance is described in [Video settings](en/VIDEO_SETTINGS.md).

## Кратко по-русски

Возьмите ZIP отката OEM selector v5 из релиза r7 и новый каталог сборки `build_oem_selector.py`. Выполните приведённую команду: она заменит только Settings APK, сохранив MainAiBox и служебные файлы. Ожидаемые SHA256 указаны выше. Ключ подписи остаётся локальным; перепрошивка, установка на устройство и сборка MD этим скриптом не выполняются. Инструкции по применению и откату находятся в релизе.
