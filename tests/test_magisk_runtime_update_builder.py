"""Offline package contract, corrupt-input rejection and Magisk retained files."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('runtime_update_builder', ROOT / 'scripts/build_magisk_runtime_update.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class RuntimeUpdateBuilderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.prefix = 'data/versions/' + builder.VERSION
        self.observer = b'offline observer fixture'
        self.observer_patch = patch.object(builder, 'OBSERVER', builder.sha(self.observer))
        self.observer_patch.start()
        self.addCleanup(self.observer_patch.stop)
        self.files = {n: None for n in ('data', 'data/logs', 'data/runtime', 'data/versions', self.prefix)}
        self.files.update({'data/owner': b'org.carlife.boot-native.v1\n',
                          'data/current': (builder.VERSION + '\n').encode(),
                          'data/runtime-control.sh': (ROOT / 'tools/boot_native/runtime/runtime-control.sh').read_bytes(),
                          self.prefix + '/md.jar': b'synthetic MD fixture',
                          self.prefix + '/runtime-controller.sh': b'#!/system/bin/sh\n',
                          self.prefix + '/uevent-watch': self.observer})
        self.files[self.prefix + '/payload.sha256'] = ''.join(
            f'{builder.sha(self.files[self.prefix + "/" + n])}  {n}\n'
            for n in ('md.jar', 'runtime-controller.sh', 'uevent-watch')).encode()
        self.archive = self.root / 'runtime.tar'
        self.manifest = self.root / 'manifest.json'

    def inputs(self, bad_mode=False):
        entries = {}
        with tarfile.open(self.archive, 'w', format=tarfile.USTAR_FORMAT) as tar:
            for name, value in self.files.items():
                info = tarfile.TarInfo(name)
                info.uid = info.gid = 0
                mode = 0o700 if value is None else (0o600 if name in ('data/owner', 'data/current') else
                    0o400 if name.endswith(('/md.jar', '/payload.sha256')) else 0o500)
                if bad_mode and name.endswith('/md.jar'):
                    mode = 0o666
                info.mode = mode
                entries[name] = {'mode': mode, 'uid': 0, 'gid': 0,
                                 'type': 'directory' if value is None else 'file'}
                if value is None:
                    info.type = tarfile.DIRTYPE
                    tar.addfile(info)
                else:
                    info.size = len(value)
                    entries[name].update(sha256=builder.sha(value), size=len(value))
                    tar.addfile(info, io.BytesIO(value))
        data = dict(schema=1, contract='BOOT_NATIVE_RUNTIME_V1', root='/data/local/carlife-native',
                    version=builder.VERSION, archive_sha256=builder.sha(self.archive.read_bytes()), members=entries)
        self.manifest.write_text(json.dumps(data))

    def build(self, out='output'):
        return builder.build(self.archive, self.manifest, self.root / out)

    def test_update_rollback_pair(self):
        self.inputs()
        result = self.build()
        self.assertEqual([p['action'] for p in result['packages']], ['update', 'rollback'])
        self.assertEqual(result['device_operations'], 0)
        for package in result['packages']:
            with zipfile.ZipFile(self.root / 'output' / package['file']) as z:
                self.assertIn(('PACKAGE_ACTION=' + package['action']).encode(), z.read('manifest.env'))
                self.assertEqual(z.read('payload/md.jar'), self.files[self.prefix + '/md.jar'])
                self.assertNotIn('payload/uevent-watch', z.namelist())
                self.assertFalse(any(n.startswith(('boot/', 'system/', 'hooks/')) or n.endswith('.img') for n in z.namelist()))
                self.assertNotIn('post-fs-data.sh', z.namelist())

    def test_complete_archive_checksums_and_retained_module(self):
        self.inputs()
        result = self.build()
        with zipfile.ZipFile(self.root / 'output' / result['packages'][0]['file']) as z:
            contents = {n: z.read(n) for n in z.namelist()}
        def parse(data):
            return dict((n, h) for h, n in (line.split('  ') for line in data.decode().splitlines()))
        checks = parse(contents['files.sha256'])
        self.assertEqual(set(checks), set(contents) - {'files.sha256'})
        for n, h in checks.items():
            self.assertEqual(builder.sha(contents[n]), h)
        retained = parse(contents['module.sha256'])
        self.assertEqual(set(retained), set(contents) - {'files.sha256', 'module.sha256', 'customize.sh'})
        del contents['customize.sh']  # Magisk finalization does this.
        for n, h in retained.items():
            self.assertEqual(builder.sha(contents[n]), h)

    def test_reproducible(self):
        self.inputs()
        a, b = self.build('first'), self.build('second')
        self.assertEqual(a, b)
        for p in a['packages']:
            self.assertEqual((self.root / 'first' / p['file']).read_bytes(), (self.root / 'second' / p['file']).read_bytes())

    def test_unix_modes_and_no_symlinks(self):
        self.inputs()
        result = self.build()
        with zipfile.ZipFile(self.root / 'output' / result['packages'][0]['file']) as z:
            for entry in z.infolist():
                self.assertTrue(stat.S_ISREG(entry.external_attr >> 16))
            self.assertEqual(stat.S_IMODE(z.getinfo('payload/md.jar').external_attr >> 16), 0o400)
            self.assertEqual(stat.S_IMODE(z.getinfo('service.sh').external_attr >> 16), 0o755)

    def test_bad_owner_rejected(self):
        self.files['data/owner'] = b'unknown\n'
        self.inputs()
        with self.assertRaisesRegex(ValueError, 'owner/version'):
            self.build()

    def test_changed_observer_rejected(self):
        self.files[self.prefix + '/uevent-watch'] = b'changed observer'
        self.inputs()
        with self.assertRaisesRegex(ValueError, 'observer'):
            self.build()

    def test_changed_resolver_rejected(self):
        self.files['data/runtime-control.sh'] = b'foreign resolver'
        self.inputs()
        with self.assertRaisesRegex(ValueError, 'resolver'):
            self.build()

    def test_payload_checksum_path_injection_rejected(self):
        self.files[self.prefix + '/payload.sha256'] += b'0' * 64 + b'  /data/other\n'
        self.inputs()
        with self.assertRaisesRegex(ValueError, 'checksum contract'):
            self.build()

    def test_modified_archive_rejected(self):
        self.inputs()
        self.archive.write_bytes(self.archive.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'archive SHA'):
            self.build()

    def test_mode_rejected(self):
        self.inputs(bad_mode=True)
        with self.assertRaisesRegex(ValueError, 'runtime mode'):
            self.build()

    def test_existing_output_not_overwritten(self):
        self.inputs()
        out = self.root / 'output'
        out.mkdir()
        (out / 'keep').write_text('keep')
        with self.assertRaisesRegex(ValueError, 'fresh output'):
            self.build()
        self.assertEqual((out / 'keep').read_text(), 'keep')

    def test_manifest_symlink_rejected(self):
        self.inputs()
        actual = self.root / 'actual.json'
        self.manifest.rename(actual)
        self.manifest.symlink_to(actual)
        with self.assertRaisesRegex(ValueError, 'manifest'):
            self.build()

    def test_release_shell_characters_rejected(self):
        self.inputs()
        with self.assertRaisesRegex(ValueError, 'release name'):
            builder.build(self.archive, self.manifest, self.root / 'output', 'bad;name')


if __name__ == '__main__':
    unittest.main()
