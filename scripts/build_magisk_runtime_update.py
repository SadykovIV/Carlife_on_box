#!/usr/bin/env python3
"""Package an existing native runtime update and its device-backup rollback.

This is a delivery module for the already integrated SM6350 runtime. It never
packages a boot image or changes the universal installer's ownership/receipts.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re
import stat
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = 'boot-native-v1-20261014'
MODULE_ID = 'carlife_runtime_update'
OBSERVER = 'a109a2a1270a09e25c87199cb9fa027c90db61dc511d846cb2fc6b94806c814e'
RESOLVERS = (
    'b97b882340ec5e8f16f9e0cbd19aa18eb1cccc5e75bca1a278609d76f02d791a',
    '6add4df4d3aeb321a813e9d2262d755c7d3615f5ff0883bca08c3cb70fe36481',
)
REPLACEMENTS = {'md.jar': 0o400, 'runtime-controller.sh': 0o500, 'payload.sha256': 0o400}
SCRIPTS = ('customize.sh', 'manager.sh', 'service.sh', 'action.sh')
spec = importlib.util.spec_from_file_location('runtime_update_integration', ROOT / 'integration/sm6350/build_integration.py')
integration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(integration)
sha, require = integration.sha, integration.require


def runtime_inputs(archive: Path, manifest: Path):
    require(manifest.is_file() and not manifest.is_symlink() and manifest.stat().st_size < 128 * 1024,
            'regular bounded runtime manifest required')
    metadata, data = integration.load_runtime(archive, manifest)
    require(data['data/owner'] == b'org.carlife.boot-native.v1\n' and
            data['data/current'] == (VERSION + '\n').encode(), 'runtime owner/version bytes')
    require(sha(data['data/runtime-control.sh']) in RESOLVERS, 'unsupported runtime resolver')
    prefix = 'data/versions/' + VERSION + '/'
    require(sha(data[prefix + 'uevent-watch']) == OBSERVER, 'update cannot change USB observer')
    for name, value in data.items():
        mode = 0o700 if value is None else (0o600 if name in ('data/owner', 'data/current') else
                0o400 if name.endswith(('/md.jar', '/payload.sha256')) else 0o500)
        require(metadata['members'][name]['mode'] == mode, 'runtime mode: ' + name)
    checks = ''.join(sha(data[prefix + name]) + '  ' + name + '\n'
                     for name in ('md.jar', 'runtime-controller.sh', 'uevent-watch')).encode()
    require(data[prefix + 'payload.sha256'] == checks, 'exact runtime checksum contract')
    return metadata, {name: data[prefix + name] for name in REPLACEMENTS}


def checksums(files):
    return ''.join(f'{sha(data)}  {name}\n' for name, data in sorted(files.items())).encode()


def build(archive: Path, manifest: Path, output: Path, release='2026.10.15-r6'):
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', release), 'release name')
    require(not output.exists(), 'fresh output directory required')
    source, payload = runtime_inputs(archive, manifest)
    common = {f'payload/{name}': value for name, value in payload.items()}
    modes = {f'payload/{name}': mode for name, mode in REPLACEMENTS.items()}
    for name in SCRIPTS:
        p = ROOT / 'tools/magisk_runtime_update' / name
        require(p.is_file() and not p.is_symlink() and p.stat().st_size < 128 * 1024, 'missing script: ' + name)
        common[name], modes[name] = p.read_bytes(), 0o755
    for lang in ('EN', 'RU'):
        p = ROOT / 'tools/magisk_runtime_update' / ('INSTALL_' + lang + '.txt')
        common[p.name], modes[p.name] = p.read_bytes(), 0o644
    common.update({'allowed-resolvers.txt': ('\n'.join(RESOLVERS) + '\n').encode(),
                   'observer.sha256': (OBSERVER + '\n').encode(),
                   'skip_mount': b'', 'LICENSE': (ROOT / 'LICENSE').read_bytes()})
    payload_id = sha(payload['payload.sha256'])
    packages = []
    output.mkdir(parents=True)
    for action in ('update', 'rollback'):
        files = dict(common)
        files['manifest.env'] = (f'FORMAT=1\nMODULE_ID={MODULE_ID}\nPACKAGE_ACTION={action}\n'
                                 f'RUNTIME_VERSION={VERSION}\nPAYLOAD_ID={payload_id}\n').encode()
        files['module.prop'] = (f'id={MODULE_ID}\nname=CarLife runtime {action}\nversion={release}\n'
                               f'versionCode=20261015\nauthor=Carlife_on_box contributors\n'
                               f'description=Native CarLife {action}; applies once after reboot. Boot and selector retained.\n').encode()
        # Magisk removes customize.sh after installation. Verify all retained
        # module files again at application time, independently of that script.
        files['module.sha256'] = checksums({name: data for name, data in files.items() if name != 'customize.sh'})
        files['files.sha256'] = checksums(files)
        name = f'Carlife_on_box-TBox-Ultra-1-{action}-{release}.zip'
        with zipfile.ZipFile(output / name, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for path, data in sorted(files.items()):
                info = zipfile.ZipInfo(path, (2026, 10, 15, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | modes.get(path, 0o644)) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(info, data, compresslevel=9)
        packages.append({'action': action, 'file': name, 'sha256': sha((output / name).read_bytes()),
                         'size': (output / name).stat().st_size, 'files': {name: sha(data) for name, data in sorted(files.items())}})
    result = dict(status='OFFLINE_PACKAGE_NOT_DEVICE_ACCEPTED', runtime_version=VERSION,
                  source_runtime_sha256=source['archive_sha256'], md_sha256=sha(payload['md.jar']),
                  payload_id=payload_id, packages=packages, device_operations=0,
                  boot_writes=False, selector_changes=False, security_changes=False,
                  builder_sha256=sha(Path(__file__).read_bytes()))
    (output / 'BUILD.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--runtime', type=Path, required=True)
    p.add_argument('--runtime-manifest', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--release', default='2026.10.15-r6')
    a = p.parse_args()
    print(json.dumps(build(a.runtime, a.runtime_manifest, a.output, a.release), indent=2))


if __name__ == '__main__':
    main()
