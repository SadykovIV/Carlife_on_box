"""Compare production read_is with its prior command-substitution contract.
All scalar reads use private fixtures; no sysfs, configfs, Android or device I/O.
"""
import pathlib
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'tools/boot_native/runtime/runtime-controller.sh').read_text()
LEGACY_READ_IS = 'read_is() { ri=$(readf "$1") || return 1; [ "$ri" = "$2" ]; }'
NODE = '/sys/class/udc/a600000.dwc3/state'
SHELLS = list(dict.fromkeys(path for path in (shutil.which('sh'), shutil.which('dash')) if path))


def function(name):
    match = re.search(r'^' + name + r'\(\) \{[^\n]*\}\n', SOURCE, re.M) or re.search(r'^' + name + r'\(\) \{.*?^\}', SOURCE, re.M | re.S)
    if not match:
        raise AssertionError(name)
    return match.group()


NEW = function('read_is')


class ReadIsTests(unittest.TestCase):
    def compare(self, content, expected, allowed=True, kind='file', fallback_rc=None, fail_read=0, node=NODE):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            path = root / 'scalar'
            if kind == 'directory':
                path.mkdir()
            elif content is not None:
                path.write_bytes(content)
            outputs = {}
            for shell in SHELLS:
                for variant, body in [('before', LEGACY_READ_IS), ('after', NEW)]:
                    calls = root / 'calls'
                    calls.unlink(missing_ok=True)
                    selected = str(path) if allowed else str(path) + '-not-selected'
                    definitions = (function('readf') + '\n' + body).replace(node, selected)
                    fallback = 'command "$@"' if fallback_rc is None else 'printf fallback-output; return ' + str(fallback_rc)
                    script = '\n'.join([
                        'set -u', definitions, 'READS=0', 'FAIL_READ=' + str(fail_read),
                        'read() { READS=$((READS+1)); [ "$READS" != "$FAIL_READ" ] || return 2; command read "$@"; }',
                        'b() { printf "%s\\n" "$*" >> ' + shlex.quote(str(calls)) + '; ' + fallback + '; }',
                        'ri=sentinel', 'read_is ' + shlex.quote(str(path)) + ' ' + shlex.quote(expected),
                        'result=$?', 'printf "RESULT=%s\\nRI=<%s>\\nREADS=%s\\n" "$result" "$ri" "$READS"',
                    ])
                    proc = subprocess.run([shell, '-c', script], capture_output=True, timeout=5)
                    self.assertEqual(proc.returncode, 0, (shell, variant, proc.stderr))
                    reads = int(re.search(rb'READS=(\d+)\n$', proc.stdout)[1])
                    stable = re.sub(rb'READS=\d+\n$', b'', proc.stdout)
                    outputs[shell, variant] = {'stdout': stable, 'stderr': proc.stderr, 'reads': reads, 'calls': calls.read_bytes() if calls.exists() else b''}
                # read_is rc, final ri including LF stripping, stderr and fallback count must match.
                for key in ('stdout', 'stderr', 'calls'):
                    self.assertEqual(outputs[shell, 'before'][key], outputs[shell, 'after'][key], (shell, key, content, expected))
            return outputs

    def test_scalar_matches_and_mismatches_use_current_shell_only(self):
        for content in [b'configured\n', b'not attached\n', b'0x18d1\n', b'\n', b'  a\\b\t \n', b'a\r\n']:
            for expected in [content[:-1].decode(), 'different']:
                with self.subTest(content=content, expected=expected):
                    out = self.compare(content, expected)
                    for shell in SHELLS:
                        self.assertEqual(out[shell, 'before']['reads'], 0)  # readf ran inside substitution.
                        self.assertEqual(out[shell, 'after']['reads'], 2)  # Both reads ran in caller shell.
                        self.assertEqual(out[shell, 'after']['calls'], b'')

    def test_eof_without_lf_empty_and_multiple_lines_keep_full_fallback(self):
        for content in [b'', b'configured', b'configured\nforeign\n', b'configured\nforeign', b'configured\n\n', b'\n\n', b'\nforeign\n', b'  a\\b\t ']:
            for expected in [content.decode().rstrip('\n'), 'configured', '']:
                with self.subTest(content=content, expected=expected):
                    out = self.compare(content, expected)
                    for shell in SHELLS:
                        self.assertEqual(len(out[shell, 'after']['calls'].splitlines()), 1)

    def test_non_whitelist_always_uses_bounded_fallback(self):
        for content in [b'a\n', b'', b'a\nb\n', b'a', b'a\n\n']:
            out = self.compare(content, content.decode().rstrip('\n'), allowed=False)
            for shell in SHELLS:
                self.assertEqual(out[shell, 'after']['reads'], 0)
                self.assertEqual(len(out[shell, 'after']['calls'].splitlines()), 1)

    def test_missing_or_directory_never_become_empty_success(self):
        for kind in ['file', 'directory']:
            for expected in ['', 'sentinel']:
                out = self.compare(None, expected, kind=kind)
                for shell in SHELLS:
                    self.assertTrue(out[shell, 'after']['stdout'].startswith(b'RESULT=1\n'))

    def test_first_or_second_builtin_read_error_preserves_cat_fallback(self):
        for fail in [1, 2]:
            out = self.compare(b'a\n', 'a', fail_read=fail)
            for shell in SHELLS:
                self.assertTrue(out[shell, 'after']['stdout'].startswith(b'RESULT=0\n'))
                self.assertEqual(len(out[shell, 'after']['calls'].splitlines()), 1)

    def test_fallback_failure_and_timeout_map_to_one_without_matching_partial_output(self):
        for rc in [1, 2, 73, 124, 127, 137]:
            for allowed in [False, True]:
                out = self.compare(b'a', 'fallback-output', allowed=allowed, fallback_rc=rc)
                for shell in SHELLS:
                    self.assertEqual(out[shell, 'after']['stdout'], b'RESULT=1\nRI=<fallback-output>\n')

    def test_new_whitelist_is_byte_identical_to_readf(self):
        pattern = r'^        (/proc/sys/kernel/random/boot_id[^\n]+)\)$'
        self.assertEqual(re.search(pattern, function('readf'), re.M)[1], re.search(pattern, NEW, re.M)[1])
        entries = re.search(pattern, NEW, re.M)[1].split('|')
        self.assertEqual(len(entries), 11)
        self.assertEqual(len(set(entries)), 11)
        self.assertNotIn('*', '|'.join(entries))
        self.assertIn('ri=$(b cat "$1") || return 1', NEW)
        self.assertNotIn('$(readf', NEW)

    def test_every_allowlisted_scalar_compares_without_fallback(self):
        pattern = r'^        (/proc/sys/kernel/random/boot_id[^\n]+)\)$'
        for node in re.search(pattern, NEW, re.M)[1].split('|'):
            with self.subTest(node=node):
                out = self.compare(b'expected\n', 'expected', node=node)
                for shell in SHELLS:
                    self.assertEqual(out[shell, 'after']['reads'], 2)
                    self.assertEqual(out[shell, 'after']['calls'], b'')
                    self.assertTrue(out[shell, 'after']['stdout'].startswith(b'RESULT=0\n'))
        # Bounded tool routing is exercised by test_boot_native_file_tool_router.


if __name__ == '__main__':
    unittest.main(verbosity=2)
