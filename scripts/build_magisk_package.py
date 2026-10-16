#!/usr/bin/env python3
"""Compose one Magisk ZIP; retain the proven installer/updater as separate backends.

The outer ID is transient. Magisk finalizes the selected backend's own ID/path.
No boot images, shell execution, devices or network are involved in composition.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAIN = 'system/app/MainAiBox/MainAiBox.apk'
SETTINGS = 'system/app/SudingSettings_chelianyi_new/SudingSettings_chelianyi_new.apk'
OEM_ID = 'carlife_oem_selector_v1'
STOCK = ('09b2f141f63a7ae016a1a7a43dd52be853b044dfb985d058c348fdaca3714b85',
         '8df7ba4a1785c472fa365336597eadeefe0958ea205920359a1833d8f78230fb')
LIMIT = 300 * 1024 * 1024


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(value, message):
    if not value:
        raise ValueError(message)


def read_zip(path, expected=None):
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= LIMIT,
            'regular bounded ZIP required: ' + path.name)
    raw = path.read_bytes()
    if expected is not None:
        require(re.fullmatch('[0-9a-f]{64}', expected) and sha(raw) == expected,
                'input SHA256 mismatch: ' + path.name)
    files = {}
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        require(len(z.infolist()) < 300, 'ZIP member count')
        total = 0
        for info in z.infolist():
            n = info.filename
            require(n == str(PurePosixPath(n)) and not n.startswith('/') and
                    '..' not in PurePosixPath(n).parts and not info.is_dir() and
                    not stat.S_ISLNK(info.external_attr >> 16) and n not in files,
                    'ZIP path/type: ' + n)
            total += info.file_size
            require(info.file_size <= LIMIT and total <= LIMIT, 'ZIP expanded size')
            files[n] = z.read(info)
    return raw, files


def fields(data):
    result = {}
    for line in data.decode('utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        k, v = line.split('=', 1)
        require(k not in result, 'duplicate manifest field')
        result[k] = v
    return result


def verify_index(files, name, excluded):
    expected = ''.join(f'{sha(value)}  {key}\n' for key, value in sorted(files.items())
                       if key not in excluded).encode()
    require(files.get(name) == expected, 'complete checksums differ: ' + name)


def indexed_package(files, family):
    if family == 'universal':
        verify_index(files, 'payload.sha256', {'payload.sha256'})
        verify_index(files, 'module.sha256', {'module.sha256', 'payload.sha256', 'customize.sh'})
        env = fields(files['manifest.env'])
        require(env.get('MODULE_ID') == 'carlife_on_box' and
                env.get('PACKAGE_KIND') == 'universal' and env.get('BOOT_STRATEGY') == 'on-device',
                'universal on-device backend required')
        require(not any(n.endswith('.img') for n in files), 'universal must not carry a boot image')
    else:
        verify_index(files, 'files.sha256', {'files.sha256'})
        verify_index(files, 'module.sha256', {'files.sha256', 'module.sha256', 'customize.sh'})
        env = fields(files['manifest.env'])
        require(env.get('MODULE_ID') == 'carlife_runtime_update' and
                env.get('PACKAGE_ACTION') == family, 'legacy backend action')
    require(env.get('FORMAT') == '1' and env.get('RUNTIME_VERSION') == 'boot-native-v1-20261014',
            'runtime backend format/version')
    return env


def overlay(files):
    require(set(files) == {'module.prop', 'service.sh', MAIN, SETTINGS}, 'OEM overlay entries')
    require(fields(files['module.prop']).get('id') == OEM_ID, 'OEM overlay ID')
    for n in (MAIN, SETTINGS):
        with zipfile.ZipFile(io.BytesIO(files[n])) as z:
            require('AndroidManifest.xml' in z.namelist(), 'OEM APK ZIP format')
    return sha(files[MAIN]), sha(files[SETTINGS])


def zip_bytes(files):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 15, 0, 0, 0))
            info.create_system = 3
            mode = 0o755 if name.endswith('.sh') else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data, compresslevel=9)
    return out.getvalue()


def release_backend(files, family, release, version_code):
    """Only visible release metadata changes; all operational code stays exact."""
    result = dict(files)
    prop = result['module.prop'].decode()
    for key, value in (('version', release), ('versionCode', str(version_code))):
        prop, count = re.subn(r'^'+key+r'=.*$', key+'='+value, prop, flags=re.MULTILINE)
        require(count == 1, 'backend release metadata: '+key)
    result['module.prop'] = prop.encode()
    index = 'payload.sha256' if family == 'universal' else 'files.sha256'
    if family == 'universal':
        identity = sha(''.join(f'{n}\0{sha(v)}\n' for n,v in sorted(result.items())
            if n not in {'manifest.env', 'manifest.json', 'module.sha256', 'payload.sha256'}).encode())
        result['manifest.env'], count = re.subn(rb'^PAYLOAD_ID=.*$', b'PAYLOAD_ID='+identity.encode(),
                                                result['manifest.env'], flags=re.MULTILINE)
        require(count == 1, 'universal package identity')
        metadata = json.loads(result['manifest.json'])
        metadata['payload_id'] = identity
        result['manifest.json'] = (json.dumps(metadata, indent=2, sort_keys=True)+'\n').encode()
    result['module.sha256'] = ''.join(f'{sha(v)}  {n}\n' for n,v in sorted(result.items())
        if n not in {index, 'module.sha256', 'customize.sh'}).encode()
    result[index] = ''.join(f'{sha(v)}  {n}\n' for n,v in sorted(result.items()) if n != index).encode()
    indexed_package(result, family)
    return zip_bytes(result)


def build(a):
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', a.release), 'release syntax')
    require(isinstance(a.version_code, int) and 0 < a.version_code < 2147483647, 'version code')
    require(not a.output.exists(), 'fresh output directory required')
    incoming = {}
    parsed = {}
    for name in ('universal', 'legacy_update', 'legacy_rollback', 'oem_overlay'):
        incoming[name], parsed[name] = read_zip(getattr(a, name), getattr(a, 'expect_' + name + '_sha256'))
    u = indexed_package(parsed['universal'], 'universal')
    indexed_package(parsed['legacy_update'], 'update')
    indexed_package(parsed['legacy_rollback'], 'rollback')
    p = 'seed/runtime/versions/boot-native-v1-20261014/'
    for n in ('md.jar', 'runtime-controller.sh', 'payload.sha256'):
        require(parsed['universal'][p+n] == parsed['legacy_update']['payload/'+n],
                'fresh/update runtime payload mismatch: ' + n)
    current_pair = overlay(parsed['oem_overlay'])
    pairs = {STOCK, current_pair}
    compat = []
    for path in a.compatible_oem_overlay:
        raw, files = read_zip(path)
        pairs.add(overlay(files))
        compat.append({'file': path.name, 'sha256': sha(raw)})
    # The verified early init service owns the selector for both backends.
    # Retire the historical late fallback; its hard-coded daemon hash belongs
    # to the old manual integration and is wrong on a clean universal install.
    ui = {n: parsed['oem_overlay'][n] for n in (MAIN, SETTINGS)}
    ui['module.prop'] = (f'id={OEM_ID}\nname=CarLife settings\nversion={a.release}\n'
                         f'versionCode={a.version_code}\nauthor=Carlife_on_box contributors\n'
                         'description=AA / CarPlay / Baidu CarLife and video settings.\n').encode()
    payloads = {'universal.zip': release_backend(parsed['universal'], 'universal', a.release, a.version_code),
                'legacy-update.zip': release_backend(parsed['legacy_update'], 'update', a.release, a.version_code),
                'legacy-rollback.zip': release_backend(parsed['legacy_rollback'], 'rollback', a.release, a.version_code),
                'oem-overlay.zip': zip_bytes(ui)}
    files = {'package/payload/'+n: v for n, v in payloads.items()}
    for n in ('control.sh',):
        files['package/'+n] = (ROOT/'tools/magisk_package'/n).read_bytes()
    files['customize.sh'] = (ROOT/'tools/magisk_package/customize.sh').read_bytes()
    files['module.prop'] = (f'id=carlife_package\nname=Baidu CarLife on Android Box\nversion={a.release}\n'
                            f'versionCode={a.version_code}\nauthor=Carlife_on_box contributors\n'
                            'description=One installer for compatible TBox Ultra: install or update.\n').encode()
    files['package/compatible-ui.txt'] = ''.join(f'{x} {y}\n' for x, y in sorted(pairs)).encode()
    files['package/oem-files.sha256'] = ''.join(f'{sha(v)}  {n}\n' for n, v in sorted(ui.items())).encode()
    files['package/manifest.env'] = (f'FORMAT=1\nRELEASE={a.release}\nVERSION_CODE={a.version_code}\n'
        'PROFILE=tbox-ultra-1-sm6350-v1\n'
        f'BOOT_PROFILE_ID={u["BOOT_PROFILE_ID"]}\n'
        f'INIT_SHA256={u["INIT_SHA256"]}\nOEM_SHA256={u["OEM_SHA256"]}\n'
        f'STOCK_MAIN_SHA256={STOCK[0]}\nSTOCK_SETTINGS_SHA256={STOCK[1]}\n').encode()
    for language in ('EN', 'RU'):
        source = ROOT/'tools/magisk_package'/('INSTALL_'+language+'.txt')
        files['package/'+source.name] = source.read_bytes()
    files['package/LICENSE'] = (ROOT/'LICENSE').read_bytes()
    manifest = dict(format=1, release=a.release, version_code=a.version_code,
        status='COMPOSED_NOT_FRESH_DEVICE_ACCEPTED', inputs={n:sha(v) for n,v in incoming.items()},
        composed_payload_sha256={n:sha(v) for n,v in payloads.items()},
        backend_changes=['module.prop version/versionCode', 'universal package identity', 'checksum indexes'],
        compatible_ui=compat, md_sha256=sha(parsed['universal'][p+'md.jar']),
        boot_images_included=False, backend_ids=['carlife_on_box','carlife_runtime_update'],
        ui_id=OEM_ID, ui_service_removed=True, ui_apks_byte_identical=True,
        builder_sha256=sha(Path(__file__).read_bytes()))
    files['package/manifest.json'] = (json.dumps(manifest, indent=2, sort_keys=True)+'\n').encode()
    files['package/package.sha256'] = ''.join(f'{sha(v)}  {n[8:]}\n' for n,v in sorted(files.items())
                                              if n.startswith('package/')).encode()
    archive = zip_bytes(files)
    name = f'Carlife_on_box-TBox-Ultra-{a.release}.zip'
    a.output.mkdir(parents=True)
    (a.output/name).write_bytes(archive)
    report = dict(manifest, package=name, package_sha256=sha(archive), package_size=len(archive))
    (a.output/'BUILD.json').write_text(json.dumps(report, indent=2, sort_keys=True)+'\n')
    (a.output/'SHA256SUMS').write_text(f'{sha(archive)}  {name}\n')
    return report


def argument_parser():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('universal', 'legacy-update', 'legacy-rollback', 'oem-overlay'):
        p.add_argument('--'+name, type=Path, required=True)
        p.add_argument('--expect-'+name+'-sha256', required=True)
    p.add_argument('--compatible-oem-overlay', type=Path, action='append', default=[])
    p.add_argument('--release', required=True)
    p.add_argument('--version-code', type=int, required=True)
    p.add_argument('--output', type=Path, required=True)
    return p


if __name__ == '__main__':
    parser = argument_parser()
    try:
        print(json.dumps(build(parser.parse_args()), indent=2))
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as e:
        parser.exit(2, 'build failed: '+str(e)+'\n')
