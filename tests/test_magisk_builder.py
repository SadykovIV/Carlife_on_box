"""Offline installer packaging, immutable boot hooks and exact rollback evidence."""
import argparse
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


build = load('magisk_builder_test', ROOT / 'scripts/build_magisk_installer.py')
b = build.boot


def cpio(extra=()):
    entries = [('init', b'Magisk fixture init', stat.S_IFREG | 0o750),
               ('.backup', b'', stat.S_IFDIR | 0o700),
               ('.backup/.magisk', b'KEEPVERITY=true\n', stat.S_IFREG | 0o600)]
    return b''.join(b.newc_entry(name, value, mode, index + 1)
                    for index, (name, value, mode) in enumerate(entries + list(extra))) + b.newc_entry('TRAILER!!!', b'', 0, 100)


def boot(ramdisk, avb=True):
    header = bytearray(4096)
    header[:8] = b'ANDROID!'
    kernel = bytes(range(256)) * 51
    dtb = b'\xd0\x0d\xfe\xed' + bytes(range(251)) * 15
    struct.pack_into('<10I', header, 8, len(kernel), 0x10008000, len(ramdisk), 0x11000000,
                     0, 0, 0x10000100, 4096, 2, 0x12345678)
    struct.pack_into('<IQI', header, 1632, 0, 0, 1660)
    struct.pack_into('<IQ', header, 1648, len(dtb), 0x11f00000)
    header[64:78] = b'known cmdline\0'
    components = {name: {'data': value} for name, value in [('kernel', kernel),
        ('ramdisk', ramdisk), ('second', b''), ('recovery_dtbo', b''), ('dtb', dtb)]}
    header[576:608] = b.boot_id(components, 2)
    output = bytes(header)
    for value in components.values():
        blob = value['data']
        output += blob + b'\0' * (b.align(len(blob), 4096) - len(blob))
    end = len(output)
    if avb:
        vbmeta = b'AVB0' + bytes(range(252)) * 2
        output += vbmeta
        output += b'\0' * (128 * 1024 - 64 - len(output))
        footer = bytearray(64)
        footer[:4] = b'AVBf'
        struct.pack_into('>IIQQQ', footer, 4, 1, 0, end, end, len(vbmeta))
        output += bytes(footer)
    else:
        output += b'\0' * (128 * 1024 - len(output))
    return output


class BootParserTests(unittest.TestCase):
    def test_additions_preserve_all_original_entries(self):
        original = cpio()
        output, before, after = b.patch_cpio(original, [
            ('overlay.d', b'', stat.S_IFDIR | 0o755),
            ('overlay.d/example.rc', b'service example /system/bin/true\n', stat.S_IFREG | 0o644)])
        old = {e['name']: e['raw'] for e in before}
        self.assertEqual(old, {e['name']: e['raw'] for e in after if e['name'] in old})
        self.assertEqual(len(after), 5)

    def test_duplicate_traversal_symlink_parent_refused(self):
        for name in ('init', '../evil', '/absolute'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                b.parse_cpio(cpio([(name, b'x', stat.S_IFREG | 0o600)]))
        with self.assertRaisesRegex(ValueError, 'already exists'):
            b.patch_cpio(cpio([('overlay.d', b'/bad', stat.S_IFLNK | 0o777)]),
                         [('overlay.d', b'', stat.S_IFDIR | 0o755)])

    def test_existing_carlife_or_oem_override_refused(self):
        for service in (b'sd-reverse', b'carlife-native'):
            with self.subTest(service=service), self.assertRaisesRegex(ValueError, 'conflicting'):
                b.patch_cpio(cpio([('overlay.d', b'', stat.S_IFDIR | 0o755),
                    ('overlay.d/conflict.rc', b'service ' + service + b' /bad\n', stat.S_IFREG | 0o644)]), [])

    def test_repack_preserves_kernel_vbmeta_and_cmdline(self):
        original = boot(gzip.compress(cpio(), mtime=0))
        ramdisk = gzip.compress(cpio([('big', os.urandom(16000), stat.S_IFREG | 0o600)]), mtime=0)
        output, old, new, changed = b.repack(original, ramdisk)
        self.assertEqual(len(output), len(original))
        self.assertGreater(new['avb_footer']['vbmeta_offset'], old['avb_footer']['vbmeta_offset'])
        self.assertEqual(new['avb_footer']['vbmeta_sha256'], old['avb_footer']['vbmeta_sha256'])
        for name in ('kernel', 'second', 'recovery_dtbo', 'dtb'):
            self.assertEqual(old['components'][name]['data'], new['components'][name]['data'])
        self.assertEqual(output[64:576], original[64:576])
        self.assertEqual(output[576:608], b.boot_id(new['components'], 2))
        self.assertLessEqual(set(changed), set(range(16, 20)) | set(range(576, 608)))

    def test_unknown_trailer_padding_checksum_and_gap_refused(self):
        image = bytearray(boot(gzip.compress(cpio(), mtime=0), avb=False))
        parsed = b.parse_boot(image)
        image[parsed['component_end']] = 1
        with self.assertRaisesRegex(ValueError, 'unknown retained trailer'):
            b.repack(bytes(image), os.urandom(8000))
        image = bytearray(boot(gzip.compress(cpio(), mtime=0)))
        parsed = b.parse_boot(image)
        kernel = parsed['components']['kernel']
        image[kernel['offset'] + kernel['size']] = 1
        with self.assertRaisesRegex(ValueError, 'padding'):
            b.parse_boot(image)
        image = bytearray(boot(gzip.compress(cpio(), mtime=0)))
        image[576] ^= 1
        with self.assertRaisesRegex(ValueError, 'SHA1'):
            b.repack(bytes(image), gzip.compress(cpio(), mtime=0))
        image = bytearray(boot(gzip.compress(cpio(), mtime=0)))
        avb = b.parse_boot(image)['avb_footer']
        image[avb['vbmeta_offset'] + avb['vbmeta_size'] + 1] = 1
        with self.assertRaisesRegex(ValueError, 'around AVB'):
            b.parse_boot(image)

    def test_overlay_is_deterministic_and_has_no_boot_execution(self):
        image = boot(gzip.compress(cpio(), mtime=0))
        payloads = build.render_hooks('a' * 64, 'b' * 64, 'c' * 64)
        first, audit = b.overlay_image(image, payloads)
        second, _ = b.overlay_image(image, payloads)
        self.assertEqual(first, second)
        self.assertEqual(audit['existing_cpio_entries_preserved'], 3)
        parsed = b.parse_boot(first)
        entries, _ = b.parse_cpio(gzip.decompress(parsed['components']['ramdisk']['data']))
        self.assertEqual({e['name'] for e in entries} - {'init', '.backup', '.backup/.magisk'},
                         {'overlay.d', 'overlay.d/sbin', 'overlay.d/carlife-owner.rc',
                          'overlay.d/sbin/carlife-early-dispatch.sh', 'overlay.d/sbin/carlife-native-entry.sh',
                          'overlay.d/sbin/carlife-selector-entry.sh'})


class HookTests(unittest.TestCase):
    def test_rendered_shell_valid_and_barriers_unchanged(self):
        scripts = build.render_hooks('a' * 64, 'b' * 64, 'c' * 64)
        self.assertEqual(scripts['carlife-owner.rc'], (ROOT / 'tools/boot_native/boot/carlife-owner.rc.in').read_bytes())
        for name, value in scripts.items():
            if name.endswith('.sh'):
                result = subprocess.run(['/bin/sh', '-n'], input=value, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
        early = scripts['carlife-early-dispatch.sh'].decode()
        self.assertLess(early.index('installer_gate; installer_rc=$?'), early.index('    read_selection;'))
        self.assertIn('INSTALLER_OEM_BYPASS', early)
        self.assertIn('oem_file_ok ||', early)
        self.assertNotIn('mkdir /data/adb/modules/carlife_native_pilot_v3', early)
        self.assertEqual(scripts['carlife-native-entry.sh'].count(b'installer_gate || exit 70'), 2)
        selector = scripts['carlife-selector-entry.sh'].decode()
        self.assertIn('regular() {', selector)
        self.assertIn('exact_lf() {', selector)
        self.assertIn('10) sleep 1;;', selector)
        self.assertLess(selector.index('installer_gate; installer_rc=$?'), selector.index('[ -d "$S" ] &&'))
        self.assertLess(selector.index('installer_gate; installer_rc=$?'), selector.index('exec /system/bin/sh'))


    def test_module_gate_install_update_disable_and_rollback_states(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            module, update, state = root / 'module', root / 'update', root / 'state'
            gate = build.module_gate().replace('/data/adb/modules/carlife_on_box', str(module))
            gate = gate.replace('/data/adb/modules_update/carlife_on_box', str(update))
            gate = gate.replace('/data/adb/carlife-on-box', str(state))
            helpers = '''\nb() { if [ "$1" = stat ]; then echo 0:0:700; else "$@"; fi; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
exact_lf() { [ "$(cat "$1"; printf .)" = "$(printf '%s\\n.' "$2")" ]; }
installer_gate
'''
            def check(expected):
                self.assertEqual(subprocess.run(['/bin/sh'], input=(gate + helpers).encode()).returncode, expected)
            check(20)  # Module absent: original OEM, even after owned state is removed.
            module.mkdir(); state.mkdir(); (state / 'phase').write_text('installed\n')
            check(0)
            (state / 'pending').write_text('candidate\n'); check(10); (state / 'pending').unlink()
            update.mkdir(); check(10); update.rmdir()
            for phase, code in [('prepared', 10), ('updating', 10), ('failed', 10), ('rollback', 20), ('unknown', 21)]:
                (state / 'phase').write_text(phase + '\n'); check(code)
            (state / 'phase').write_text('installed\n')
            for flag in ('disable', 'remove'):
                (module / flag).touch(); check(20); (module / flag).unlink()
            (state / 'phase').write_text('installed\n\n'); check(21)


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        stage = self.root / 'inputs'
        for path in ('runtime', 'observer/build', 'md/build'):
            (stage / path).mkdir(parents=True)
        for name in ('runtime-control.sh', 'runtime-controller.sh'):
            (stage / 'runtime' / name).write_bytes((ROOT / 'tools/boot_native/runtime' / name).read_bytes())
        (stage / 'observer/build/uevent-watch').write_bytes(b'offline observer')
        (stage / 'md/build/md-boot-native.jar').write_bytes(b'offline MD')
        package = load('magisk_runtime_fixture', ROOT / 'tools/boot_native/build_package.py')
        package.HERE = stage
        package.build(self.root / 'runtime')
        self.selector = self.root / 'selector'; self.selector.mkdir()
        payload = {}
        for name in build.SELECTOR_FILES:
            value = ('offline selector ' + name).encode()
            (self.selector / name).write_bytes(value); payload[name] = b.sha(value)
        (self.selector / 'manifest.json').write_text(json.dumps({'payload': payload}))
        self.image = self.root / 'current.img'; self.image.write_bytes(boot(gzip.compress(cpio(), mtime=0)))
        self.init = self.root / 'init.rc'
        self.init.write_bytes(b'on early-init\n    start sd-reverse\n    exec_start apexd-bootstrap\nservice sd-reverse /system/bin/sd-reverse.sh\n')
        self.oem = self.root / 'sd-reverse.sh'; self.oem.write_bytes(b'#!/system/bin/sh\nCHANNEL=sd_msm6350\n')
        self.patcher = patch.object(build.integration, 'INIT_SHA', b.sha(self.init.read_bytes()))
        self.patcher.start(); self.addCleanup(self.patcher.stop)
        # Only lifecycle script bytes are fixtures; actual archive/init validators
        # and boot/ramdisk parser/repacker execute unmodified.
        self.original_reader = build.regular_bytes
        def reader(path, limit=b.LIMIT):
            if path.parent == ROOT / 'tools/magisk' and path.name in build.SCRIPTS + ('patch-boot.sh',):
                return b'#!/system/bin/sh\n# offline lifecycle fixture\nexit 0\n'
            return self.original_reader(path, limit)
        self.reader_patch = patch.object(build, 'regular_bytes', side_effect=reader)
        self.reader_patch.start(); self.addCleanup(self.reader_patch.stop)

    def args(self, output='install', **changes):
        values = dict(command='install', runtime=self.root / 'runtime/boot-native-runtime.tar',
            runtime_manifest=self.root / 'runtime/bundle-manifest.json', selector_release=self.selector,
            output=self.root / output, current_image=self.image, expect_current_sha256=b.sha(self.image.read_bytes()),
            init=self.init, oem_script=self.oem, fingerprint='manufacturer/product/device:15/ID/123:userdebug/test-keys',
            slot_suffix='_a')
        values.update(changes)
        return argparse.Namespace(**values)

    def install(self, output='install'):
        result = build.build(self.args(output))
        return self.root / output / result['package']

    def test_install_exact_rollback_deterministic_and_seed_modes(self):
        first, second = self.install('a'), self.install('b')
        self.assertEqual(first.read_bytes(), second.read_bytes())
        manifest, files = build.read_package(first)
        self.assertEqual(files['boot/original.img'], self.image.read_bytes())
        self.assertEqual(b.sha(files['boot/candidate.img']), manifest['boot']['candidate_sha256'])
        self.assertEqual(manifest['default_selection'], 'carlife')
        self.assertFalse(manifest['proprietary_apks_included'])
        self.assertEqual(files['seed/selector/selected'], b'carlife\n')
        self.assertIn(b'400\tseed/runtime/versions/' + build.VERSION.encode() + b'/md.jar\n', files['payload-files.tsv'])
        self.assertIn(b'700\tseed/selector/logs\n', files['payload-dirs.tsv'])
        self.assertEqual(b.sha(files['seed/runtime/runtime-control.sh']), build.NEW_RESOLVER)
        self.assertIn(b'if [ -e "$old" ] || [ -L "$old" ]; then', files['seed/runtime/runtime-control.sh'])
        self.assertIn(b'BASE_PAYLOAD_ID=none\n', files['manifest.env'])
        self.assertEqual(manifest['device_operations'], 0)

    def test_activated_checksum_survives_magisk_customize_removal(self):
        _, files = build.read_package(self.install())
        del files['customize.sh']
        checks = dict((name, digest) for digest, name in
                      (line.split('  ', 1) for line in files['module.sha256'].decode().splitlines()))
        self.assertEqual(set(checks), set(files) - {'module.sha256', 'payload.sha256'})
        self.assertTrue(all(b.sha(files[name]) == digest for name, digest in checks.items()))

    def test_update_nonversion_seed_change_refused(self):
        original = self.install()
        value = self.selector / 'selector-control.sh'
        value.write_bytes(b'new helper without changing pinned entry')
        manifest = json.loads((self.selector / 'manifest.json').read_text())
        manifest['payload'][value.name] = b.sha(value.read_bytes())
        (self.selector / 'manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'immutable seed'):
            build.build(self.args('update', command='update', base_package=original))

    def test_update_has_no_boot_and_keeps_initial_install_anchor(self):
        original = self.install()
        original_manifest, _ = build.read_package(original)
        first_args = self.args('update1', command='update', base_package=original)
        first = build.build(first_args)
        first_path = first_args.output / first['package']
        second_args = self.args('update2', command='update', base_package=first_path)
        second = build.build(second_args)
        for path in (first_path, second_args.output / second['package']):
            manifest, files = build.read_package(path)
            self.assertFalse(any(name.startswith('boot/') for name in files))
            self.assertEqual(manifest['base_payload_id'], original_manifest['payload_id'])
            self.assertEqual(manifest['boot'], original_manifest['boot'])

    def test_runtime_payload_update_is_accepted_without_boot_mutation(self):
        original = self.install()
        (self.root / 'inputs/md/build/md-boot-native.jar').write_bytes(b'updated MD payload')
        package = load('magisk_updated_runtime_fixture', ROOT / 'tools/boot_native/build_package.py')
        package.HERE = self.root / 'inputs'
        package.build(self.root / 'runtime-new')
        args = self.args('new-payload', command='update', base_package=original,
                        runtime=self.root / 'runtime-new/boot-native-runtime.tar',
                        runtime_manifest=self.root / 'runtime-new/bundle-manifest.json')
        result = build.build(args)
        manifest, files = build.read_package(args.output / result['package'])
        initial, initial_files = build.read_package(original)
        self.assertEqual(files['seed/runtime/versions/' + build.VERSION + '/md.jar'], b'updated MD payload')
        self.assertEqual(manifest['boot'], initial['boot'])
        self.assertEqual(files['seed/runtime/runtime-control.sh'], initial_files['seed/runtime/runtime-control.sh'])
        self.assertFalse(any(name.startswith('boot/') for name in files))

    def test_wrong_boot_hash_fingerprint_and_init_refused_before_output(self):
        for changes in ({'expect_current_sha256': '0' * 64}, {'fingerprint': "bad'$(id)"}):
            args = self.args(**changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                build.build(args)
            self.assertFalse(args.output.exists())
        self.init.write_bytes(self.init.read_bytes() + b'# changed\n')
        with self.assertRaisesRegex(ValueError, 'unsupported OEM init'):
            build.build(self.args())

    def test_archive_content_drift_refused_before_output(self):
        archive = self.root / 'runtime/boot-native-runtime.tar'
        with archive.open('ab') as stream:
            stream.write(b'drift')
        with self.assertRaisesRegex(ValueError, 'archive SHA'):
            build.build(self.args())
        self.assertFalse((self.root / 'install').exists())

    def test_update_entry_pin_change_refused(self):
        original = self.install()
        value = self.selector / 'selector-daemon.sh'
        value.write_bytes(b'changed entry')
        manifest = json.loads((self.selector / 'manifest.json').read_text())
        manifest['payload'][value.name] = b.sha(value.read_bytes())
        (self.selector / 'manifest.json').write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'boot-pinned'):
            build.build(self.args('update', command='update', base_package=original))

    def test_base_package_tampering_or_duplicate_refused(self):
        original = self.install()
        with zipfile.ZipFile(original) as archive:
            contents = {name: archive.read(name) for name in archive.namelist()}
        bad = self.root / 'tampered.zip'
        with zipfile.ZipFile(bad, 'w') as archive:
            for name, value in contents.items():
                archive.writestr(name, b'bad' if name == 'manager.sh' else value)
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            build.read_package(bad)
        with zipfile.ZipFile(bad, 'w') as archive:
            archive.writestr('../outside', b'bad')
        with self.assertRaisesRegex(ValueError, 'path'):
            build.read_package(bad)

    def universal_args(self, output='universal'):
        return argparse.Namespace(command='universal', runtime=self.root / 'runtime/boot-native-runtime.tar',
            runtime_manifest=self.root / 'runtime/bundle-manifest.json', selector_release=self.selector,
            output=self.root / output, init=self.init, oem_script=self.oem)

    def universal(self, output='universal'):
        args = self.universal_args(output)
        result = build.build(args)
        return args.output / result['package']

    def test_universal_build_does_not_read_any_device_boot(self):
        self.image.unlink()
        first = self.universal('first')
        second = self.universal('second')
        self.assertEqual(first.read_bytes(), second.read_bytes())
        manifest, files = build.read_package(first)
        self.assertEqual(manifest['package_kind'], 'universal')
        self.assertEqual(manifest['boot_strategy'], 'on-device')
        self.assertFalse(manifest['device_specific_inputs_required'])
        self.assertFalse(any(name.startswith('boot/') or name.endswith(('.img', '.apk')) for name in files))
        self.assertIn('patch-boot.sh', files)
        self.assertEqual(files['LICENSE'], (ROOT / 'LICENSE').read_bytes())
        self.assertEqual({name for name in files if name.startswith('hooks/')},
                         {'hooks/' + name for name in build.BOOT_FILES})
        for field in ('original_sha256', 'candidate_sha256', 'size', 'slot_suffix', 'fingerprint'):
            self.assertEqual(manifest['boot'][field], 'auto')
        for field in ('SLOT_SUFFIX', 'BOOT_SIZE', 'BOOT_ORIGINAL_SHA256', 'BOOT_CANDIDATE_SHA256'):
            self.assertIn((field + '=auto\n').encode(), files['manifest.env'])
        self.assertIn(b"EXPECTED_FINGERPRINT='auto'\n", files['manifest.env'])
        self.assertIn(b'BOOT_STRATEGY=on-device\n', files['manifest.env'])
        self.assertNotIn(str(self.root).encode(), files['manifest.json'])
        self.assertNotIn(str(self.root).encode(), files['manifest.env'])
        self.assertEqual(manifest['device_operations'], 0)

    def test_universal_has_the_same_reviewed_hooks_as_personalized_install(self):
        personal, personal_files = build.read_package(self.install())
        universal, files = build.read_package(self.universal())
        self.assertEqual(universal['boot_profile_id'], personal['boot_profile_id'])
        image = b.parse_boot(personal_files['boot/candidate.img'])
        cpio_entries, _ = b.parse_cpio(gzip.decompress(image['components']['ramdisk']['data']))
        cpio_files = {item['name']: item['data'] for item in cpio_entries}
        for name in build.BOOT_FILES:
            path = 'overlay.d/' + (name if name.endswith('.rc') else 'sbin/' + name)
            self.assertEqual(files['hooks/' + name], cpio_files[path])
            self.assertEqual(universal['hook_sha256'][name], b.sha(files['hooks/' + name]))

    def test_universal_update_chain_uses_profile_and_contains_no_images(self):
        original = self.universal()
        initial, _ = build.read_package(original)
        for number in (1, 2):
            args = self.args('universal-update' + str(number), command='update', base_package=original)
            result = build.build(args)
            original = args.output / result['package']
            manifest, files = build.read_package(original)
            self.assertEqual(manifest['base_payload_id'], 'profile')
            self.assertEqual(manifest['boot_strategy'], 'on-device')
            self.assertEqual(manifest['boot_profile_id'], initial['boot_profile_id'])
            self.assertEqual(manifest['boot'], initial['boot'])
            self.assertFalse(any(name.startswith('boot/') for name in files))
            self.assertIn('patch-boot.sh', files)
            self.assertIn(b'BASE_PAYLOAD_ID=profile\n', files['manifest.env'])

    def test_new_universal_payload_keeps_boot_profile_while_hooks_change_it(self):
        first, first_files = build.read_package(self.universal('old-runtime'))
        (self.root / 'inputs/md/build/md-boot-native.jar').write_bytes(b'new universal MD payload')
        package = load('universal_new_runtime_fixture', ROOT / 'tools/boot_native/build_package.py')
        package.HERE = self.root / 'inputs'
        package.build(self.root / 'runtime-new')
        args = self.universal_args('new-runtime')
        args.runtime = self.root / 'runtime-new/boot-native-runtime.tar'
        args.runtime_manifest = self.root / 'runtime-new/bundle-manifest.json'
        result = build.build(args)
        second, _ = build.read_package(args.output / result['package'])
        self.assertEqual(first['boot_profile_id'], second['boot_profile_id'])
        self.assertNotEqual(first['payload_id'], second['payload_id'])
        hooks = {name: first_files['hooks/' + name] for name in build.BOOT_FILES}
        hooks['carlife-owner.rc'] += b'# changed early integration\n'
        changed = build.boot_profile_id(first['boot'], first['runtime_entry_sha256'],
                                        first['selector_entry_sha256'], hooks, first_files)
        self.assertNotEqual(changed, first['boot_profile_id'])

    def test_universal_profile_binds_nonversion_payloads(self):
        original, _ = build.read_package(self.universal('before-selector-change'))
        path = self.selector / 'selector-control.sh'
        path.write_bytes(b'new selector helper')
        manifest = json.loads((self.selector / 'manifest.json').read_text())
        manifest['payload'][path.name] = b.sha(path.read_bytes())
        (self.selector / 'manifest.json').write_text(json.dumps(manifest))
        changed, _ = build.read_package(self.universal('after-selector-change'))
        self.assertNotEqual(original['boot_profile_id'], changed['boot_profile_id'])
        self.assertEqual(original['selector_entry_sha256'], changed['selector_entry_sha256'])

    def test_universal_update_preserves_base_hooks_when_local_templates_change(self):
        original = self.universal()
        _, initial_files = build.read_package(original)
        with patch.object(build, 'render_hooks', side_effect=AssertionError('must use original hooks')):
            args = self.args('update', command='update', base_package=original)
            result = build.build(args)
        _, files = build.read_package(args.output / result['package'])
        for name in build.BOOT_FILES:
            self.assertEqual(files['hooks/' + name], initial_files['hooks/' + name])

    def test_universal_activated_checksums_include_patcher_and_hooks(self):
        _, files = build.read_package(self.universal())
        del files['customize.sh']
        checks = dict((name, digest) for digest, name in
                      (line.split('  ', 1) for line in files['module.sha256'].decode().splitlines()))
        self.assertEqual(set(checks), set(files) - {'module.sha256', 'payload.sha256'})
        self.assertIn('patch-boot.sh', checks)
        self.assertTrue(all('hooks/' + name in checks for name in build.BOOT_FILES))
        self.assertTrue(all(b.sha(files[name]) == digest for name, digest in checks.items()))

    def test_cli_universal_requires_firmware_profile_not_device_inputs(self):
        args = build.argument_parser().parse_args(['universal', '--runtime', 'a', '--runtime-manifest', 'b',
            '--selector-release', 'c', '--init', 'd', '--oem-script', 'e', '--output', 'f'])
        self.assertEqual(args.command, 'universal')
        for name in ('current_image', 'expect_current_sha256', 'fingerprint', 'slot_suffix', 'base_package'):
            self.assertFalse(hasattr(args, name), name)

    def test_cli_update_needs_no_boot_inputs(self):
        args = build.argument_parser().parse_args(['update', '--runtime', 'a', '--runtime-manifest', 'b',
            '--selector-release', 'c', '--base-package', 'd', '--output', 'e'])
        self.assertEqual(args.command, 'update')
        self.assertFalse(hasattr(args, 'current_image'))


if __name__ == '__main__':
    unittest.main()
