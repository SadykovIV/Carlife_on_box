"""Exercise the shell console normalization, without device or block writes.

Synthetic component pins are substituted only in this host test. Production
pins remain the accepted SM6350 kernel/DTB bytes; optional local evidence checks
compare them with the retained E, f534 and f576 images (never redistributed).
"""
import hashlib
import importlib.util
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHELL = ROOT / 'tools/magisk/patch-boot.sh'
TEXT = SHELL.read_text()
BEGIN = '# BEGIN_REVIEWED_CONSOLE_PROFILE\n'
END = '# END_REVIEWED_CONSOLE_PROFILE'
PROFILE = TEXT.split(BEGIN, 1)[1].split(END, 1)[0]
KERNEL = '12f4f4aa1df602972e47fcb159354c32c83aca96d714ba702e7ed355fa4ddac0'
OLD_DTB = '7a49a4bb2c7d392af572ec00c582ceb1e200f24d832153b7b9781866bbce8210'
NEW_DTB = '38c946579a47a5b8b4e6b4237b16aaa6ffe0fac0e5a079c4ff4161f6aefee89a'
OLD_HEAD = re.search(r"cp_old_head='([^']+)'", PROFILE)[1].encode()
NEW_HEAD = re.search(r"cp_new_head='([^']+)'", PROFILE)[1].encode()
OLD_DT = b'rcupdate.rcu_expedited=1 rcu_nocbs=0-7 earlycon=msm_geni_serial,0x98c000 kpti=off\0'
NEW_DT = OLD_DT.replace(b'earlycon=msm_geni_serial,0x98c000', b' ' * 33)
OFFSETS = (5352, 398805)


def sha(value):
    return hashlib.sha256(value).hexdigest()


def replace_header(value, text):
    out = bytearray(value)
    out[64:576] = text + bytes(512 - len(text))
    return bytes(out)


class ConsoleProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.d = self.root / 'state'; self.d.mkdir()
        self.w = self.root / 'work'; self.w.mkdir()
        for name in ('before', 'repack'):
            (self.w / name).mkdir()
        self.kernel = b'synthetic console-profile kernel'
        # An intentionally non-FDT fixture: only the full component hash grants
        # normalization. Arbitrary matching strings do not grant recognition.
        dtb = bytearray(b'X' * 784332)
        for at in OFFSETS:
            dtb[at:at + len(OLD_DT)] = OLD_DT
        self.old_dtb = bytes(dtb)
        for at in OFFSETS:
            dtb[at:at + len(NEW_DT)] = NEW_DT
        self.new_dtb = bytes(dtb)
        self.profile = PROFILE.replace(KERNEL, sha(self.kernel)).replace(OLD_DTB, sha(self.old_dtb)).replace(NEW_DTB, sha(self.new_dtb))
        header = bytearray(4096)
        header[:8] = b'ANDROID!'
        struct.pack_into('<I', header, 40, 2)
        header[48:64] = bytes(range(16))
        self.old_header = replace_header(header, OLD_HEAD)
        self.new_header = replace_header(header, NEW_HEAD)

    def execute(self, header=None, dtb=None, kernel=None, profile=None):
        header = self.old_header if header is None else header
        dtb = self.old_dtb if dtb is None else dtb
        kernel = self.kernel if kernel is None else kernel
        original = header + b'preserve original image payload and AVB'
        (self.d / 'original.img').write_bytes(original)
        for name, value in [('header.raw', header), ('header.masked', header), ('dtb', dtb), ('kernel', kernel)]:
            (self.w / 'before' / name).write_bytes(value)
        (self.w / 'repack' / 'dtb').write_bytes(dtb)
        prefix = '''set -eu
fail() { echo "$*" >&2; exit 1; }
regular() { [ -f "$1" ] && [ ! -L "$1" ]; }
hash() { shasum -a 256 "$1" | cut -d ' ' -f 1; }
bytes() { wc -c < "$1" | tr -d ' '; }
u32() { od -An -tu4 -j "$2" -N 4 "$1" | tr -d ' \\n'; }
slice() { tail -c "+$(($2 + 1))" "$1" | head -c "$3" > "$4"; [ "$(bytes "$4")" = "$3" ] || fail slice; }
'''
        script = prefix + '\nD=' + shlex.quote(str(self.d)) + '\nW=' + shlex.quote(str(self.w)) + '\n'
        script += (self.profile if profile is None else profile) + '\nprepare_console_profile\nprintf "%s\\n%s\\n" "$CONSOLE_RESULT" "$REPACK_SOURCE"\n'
        result = subprocess.run(['/bin/sh'], input=script, text=True, capture_output=True, timeout=15)
        self.assertEqual((self.d / 'original.img').read_bytes(), original)
        return result, original

    def assert_ok(self, result, verdict):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout.splitlines()[0], verdict)

    def test_exact_old_pair_changes_only_reviewed_header_and_dtb_bytes(self):
        result, original = self.execute()
        self.assert_ok(result, 'CONSOLE_OFF_APPLIED')
        self.assertEqual((self.w / 'expected.header.masked').read_bytes(), self.new_header)
        self.assertEqual((self.w / 'expected.dtb').read_bytes(), self.new_dtb)
        self.assertEqual((self.w / 'repack/dtb').read_bytes(), self.new_dtb)
        template = Path(result.stdout.splitlines()[1]).read_bytes()
        self.assertEqual(template, replace_header(original, NEW_HEAD))
        self.assertEqual(len(template), len(original))

    def test_already_correct_pair_is_idempotent(self):
        result, original = self.execute(self.new_header, self.new_dtb)
        self.assert_ok(result, 'CONSOLE_OFF_ALREADY_APPLIED')
        self.assertEqual(Path(result.stdout.splitlines()[1]), self.d / 'original.img')
        self.assertFalse((self.w / 'console-template.img').exists())
        self.assertEqual((self.w / 'expected.header.masked').read_bytes(), self.new_header)
        self.assertEqual((self.w / 'expected.dtb').read_bytes(), self.new_dtb)

    def test_only_old_header_is_normalized(self):
        result, _ = self.execute(self.old_header, self.new_dtb)
        self.assert_ok(result, 'CONSOLE_OFF_APPLIED')
        self.assertEqual((self.w / 'expected.dtb').read_bytes(), self.new_dtb)
        self.assertEqual((self.w / 'expected.header.masked').read_bytes(), self.new_header)

    def test_only_old_dtb_is_normalized(self):
        result, _ = self.execute(self.new_header, self.old_dtb)
        self.assert_ok(result, 'CONSOLE_OFF_APPLIED')
        self.assertEqual(Path(result.stdout.splitlines()[1]), self.d / 'original.img')
        self.assertEqual((self.w / 'repack/dtb').read_bytes(), self.new_dtb)

    def test_unknown_header_preserves_both_components_and_succeeds(self):
        unknown = replace_header(self.old_header, OLD_HEAD + b' vendor.option=1')
        result, _ = self.execute(unknown)
        self.assert_ok(result, 'CONSOLE_UNCHANGED_UNRECOGNIZED')
        self.assertEqual((self.w / 'expected.header.masked').read_bytes(), unknown)
        self.assertEqual((self.w / 'expected.dtb').read_bytes(), self.old_dtb)
        self.assertEqual((self.w / 'repack/dtb').read_bytes(), self.old_dtb)
        self.assertFalse((self.w / 'console-template.img').exists())

    def test_unknown_dtb_with_same_bootargs_preserves_header(self):
        dtb = bytearray(self.old_dtb); dtb[0] ^= 1
        result, _ = self.execute(dtb=bytes(dtb))
        self.assert_ok(result, 'CONSOLE_UNCHANGED_UNRECOGNIZED')
        self.assertEqual((self.w / 'expected.header.masked').read_bytes(), self.old_header)
        self.assertEqual((self.w / 'repack/dtb').read_bytes(), bytes(dtb))

    def test_unknown_kernel_does_not_disable_its_console(self):
        result, _ = self.execute(kernel=b'another kernel')
        self.assert_ok(result, 'CONSOLE_UNCHANGED_UNRECOGNIZED')
        self.assertEqual((self.w / 'expected.header.masked').read_bytes(), self.old_header)
        self.assertEqual((self.w / 'repack/dtb').read_bytes(), self.old_dtb)

    def test_nonempty_header_extension_is_not_discarded(self):
        header = bytearray(self.old_header); header[608:613] = b'extra'
        result, _ = self.execute(header=bytes(header))
        self.assert_ok(result, 'CONSOLE_UNCHANGED_UNRECOGNIZED')
        self.assertEqual((self.w / 'expected.header.masked').read_bytes(), bytes(header))

    def test_hidden_bytes_after_header_nul_are_not_discarded(self):
        header = bytearray(self.old_header); header[575] = 1
        result, _ = self.execute(header=bytes(header))
        self.assert_ok(result, 'CONSOLE_UNCHANGED_UNRECOGNIZED')
        self.assertEqual((self.w / 'expected.header.masked').read_bytes(), bytes(header))

    def test_offset_content_is_checked_even_after_full_hash_recognition(self):
        dtb = bytearray(self.old_dtb); dtb[OFFSETS[1]] ^= 1
        # Deliberately lie about the known hash in this test: offset guards must
        # still reject a wrong target instead of writing it.
        profile = self.profile.replace(sha(self.old_dtb), sha(dtb))
        result, _ = self.execute(dtb=bytes(dtb), profile=profile)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('pinned console DT bytes differ', result.stderr)
        self.assertFalse((self.w / 'console-template.img').exists())

    def test_result_hash_is_checked_before_header_template_is_published(self):
        profile = self.profile.replace(sha(self.new_dtb), 'f' * 64)
        result, _ = self.execute(profile=profile)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('console DT result hash', result.stderr)
        self.assertFalse((self.w / 'console-template.img').exists())


class ConsoleReferenceEvidenceTests(unittest.TestCase):
    def test_recorded_e_f534_f576_match_the_production_profile(self):
        workspace = ROOT.parent.parent
        paths = [workspace / 'research/images/e71f6640a41d/boot.img',
                 workspace / 'research/runtime/boot-native-20261014T035044Z/boot/candidate/boot_a.carlife-native.candidate.img',
                 workspace / 'research/runtime/honda-latency-20261014T165657Z/firmware-kit/console-review/installed-f534-candidate/boot.consoleoff.img']
        if not all(p.is_file() for p in paths):
            self.skipTest('private reference firmware is not part of the source repository')
        spec = importlib.util.spec_from_file_location('console_boot_reference', ROOT / 'tools/magisk/boot_image.py')
        boot = importlib.util.module_from_spec(spec); spec.loader.exec_module(boot)
        for i, path in enumerate(paths):
            with self.subTest(profile=path.name):
                raw = path.read_bytes(); parsed = boot.parse_boot(raw)
                self.assertEqual(sha(parsed['components']['kernel']['data']), KERNEL)
                dtb = parsed['components']['dtb']['data']
                self.assertEqual(sha(dtb), OLD_DTB if i < 2 else NEW_DTB)
                self.assertEqual(raw[64:576], (OLD_HEAD if i < 2 else NEW_HEAD).ljust(512, b'\0'))
                self.assertEqual(raw[608:1632], bytes(1024))
                for offset in OFFSETS:
                    self.assertEqual(dtb[offset:offset + 82], OLD_DT if i < 2 else NEW_DT)
                self.assertEqual(raw[576:608], boot.boot_id(parsed['components'], 2))


if __name__ == '__main__':
    unittest.main()
