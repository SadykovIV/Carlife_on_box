"""Diagnostic-clock parity and strict controller-diff boundary; no device owner execution."""
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/boot_native/runtime/runtime-controller.sh'
TEXT = SCRIPT.read_text()
SITES = ('log_ms', 'phase_ms', 'discovery_bind_start_ms', 'discovery_bind_return_ms',
         'accessory_bind_start_ms', 'accessory_bind_return_ms', 'RUN_STARTED_MS')
OLD_MS = '''now_ms() {
    nm_cs=$(now_cs) || return 1
    printf '%s\\n' "$((nm_cs * 10))"
}'''


def function(name, text=TEXT):
    match = re.search(r'^' + name + r'\(\) \{[^\n]*\}\n', text, re.M)
    if not match:
        match = re.search(r'^' + name + r'\(\) \{.*?^\}', text, re.M | re.S)
    if not match:
        raise AssertionError(name)
    return match.group().rstrip('\n')


def run_shell(code):
    return subprocess.run(['sh', '-c', 'set -u\n' + code], capture_output=True,
                          text=True, timeout=5)


class ClockParserParity(unittest.TestCase):
    def compare(self, raw, directory=False):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / 'uptime'
            if directory:
                fixture.mkdir()
            elif raw is not None:
                fixture.write_bytes(raw)
            quoted = shlex.quote(str(fixture))
            number = function('number')
            old = number + '\n' + function('now_cs') + '\n' + OLD_MS
            new = number + '\n' + function('sample_now_ms')
            old = old.replace('/proc/uptime', quoted)
            new = new.replace('/proc/uptime', quoted)
            before = run_shell(old + '\nv=$(now_ms); rc=$?; printf "%s|%s\\n" "$rc" "$v"')
            after = run_shell(new + '\nSAMPLED_NOW_MS=stale; sample_now_ms; rc=$?; printf "%s|%s\\n" "$rc" "$SAMPLED_NOW_MS"')
            return before, after

    def test_seconds_centiseconds_truncation_and_large_uptime_match(self):
        cases = [(b'0.00 0.00\n', 0), (b'0.01 3.02\n', 10),
                 (b'9.09 2.00\n', 9090), (b'010.01 0\n', 8010), (b'10.99 1.00\n', 10990),
                 (b'123.4 1.00\n', 123400), (b'123.456 1.00\n', 123450),
                 (b'4294967.89 1.00\n', 4294967890),
                 (b'123456789.01 1.00\n', 123456789010)]
        for raw, expected in cases:
            with self.subTest(raw=raw):
                before, after = self.compare(raw)
                self.assertEqual(before.returncode, 0, before.stderr)
                self.assertEqual(after.returncode, 0, after.stderr)
                self.assertEqual(before.stdout, f'0|{expected}\n')
                self.assertEqual(after.stdout, before.stdout)
                self.assertEqual(after.stderr, '')

    def test_parser_keeps_preexisting_noncanonical_input_semantics(self):
        for raw in (b'12\n', b'12.\n', b'  12.03 999\n', b'12.03\nextra\n'):
            with self.subTest(raw=raw):
                before, after = self.compare(raw)
                self.assertEqual(before.returncode, after.returncode)
                self.assertEqual(before.stdout, after.stdout)

    def test_read_failures_or_invalid_numbers_clear_stale_sample_and_fail(self):
        cases = [(None, False), (None, True), (b'', False), (b'12.34', False),
                 (b'bad.12 0\n', False), (b'12.xx 0\n', False), (b'-1.00 0\n', False),
                 (b'08.01 0\n', False), (b'0009.01 0\n', False)]
        for raw, directory in cases:
            with self.subTest(raw=raw, directory=directory):
                before, after = self.compare(raw, directory)
                self.assertEqual(before.stdout, '1|\n')
                self.assertEqual(after.stdout, '1|unknown\n')

    def test_new_helper_has_no_stdout_external_calls_or_substitution(self):
        helper = function('sample_now_ms')
        self.assertNotIn('$(', helper.replace('$((', 'ARITHMETIC('))
        for forbidden in ('timeout ', ' cat ', 'printf', 'eval', '`'):
            self.assertNotIn(forbidden, helper)
        self.assertNotIn('|', helper.replace('||', ''))
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'uptime'; p.write_text('12.34 0.00\n')
            code = function('number') + '\n' + helper.replace('/proc/uptime', shlex.quote(str(p)))
            result = run_shell(code + '\nPATH=/nonexistent; sample_now_ms; [ "$SAMPLED_NOW_MS" = 12340 ]')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, '')


class DiagnosticBehavior(unittest.TestCase):
    def test_all_seven_call_sites_copy_success_and_keep_unknown_on_failure(self):
        for name in SITES:
            statement = f'if sample_now_ms; then {name}=$SAMPLED_NOW_MS; else {name}=unknown; fi'
            self.assertEqual(TEXT.count(statement), 1)
            for rc, expected in ((0, '23450'), (1, 'unknown')):
                with self.subTest(name=name, rc=rc):
                    code = f'sample_now_ms() {{ SAMPLED_NOW_MS=23450; return {rc}; }}\n'
                    code += statement + f'\n[ "${name}" = {expected} ]'
                    result = run_shell(code)
                    self.assertEqual(result.returncode, 0, result.stderr)

    def test_phase_keeps_origin_on_repeat_and_unknown_clock_does_not_gate_status(self):
        code = '\n'.join(function(n) for n in ('number', 'phase')) + '''
PHASE=MEDIA_WAIT; PHASE_STARTED_MS=1000
sample_now_ms() { SAMPLED_NOW_MS=$next; return "$clock_rc"; }
atomic() { printf 'STATUS %s\n' "$1"; }
log() { printf '%s\n' "$*"; SAMPLED_NOW_MS=999999; }
next=1500; clock_rc=0
phase MEDIA_WAIT || exit 81
[ "$PHASE_STARTED_MS" = 1000 ] && [ "$phase_ms" = 1500 ] || exit 82
next=2000
phase USB_DISCOVERY || exit 83
[ "$PHASE_STARTED_MS" = 2000 ] && [ "$phase_ms" = 2000 ] || exit 84
clock_rc=1
phase ACCESSORY_CONNECTED || exit 85
[ "$PHASE_STARTED_MS" = unknown ] && [ "$phase_ms" = unknown ] || exit 86
'''
        result = run_shell(code)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.count('STATUS status'), 3)
        self.assertIn('previous_phase_elapsed_ms=500', result.stdout)
        self.assertIn('previous_phase_elapsed_ms=1000', result.stdout)
        self.assertIn('previous_phase_elapsed_ms=unknown', result.stdout)

    def test_log_preserves_fields_and_clock_failure_is_only_diagnostic(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / 'logs').mkdir()
            code = '\n'.join(function(n) for n in ('number', 'log')) + f'''
P={shlex.quote(tmp)}; BOOT=boot; RUN_ID=run; GENERATION=0; PHASE=MEDIA_WAIT
RUN_STARTED_MS=1000; PHASE_STARTED_MS=1500
regular() {{ return 1; }}; owned_file() {{ return 0; }}
now() {{ printf '42\\n'; }}
sample_now_ms() {{ SAMPLED_NOW_MS=$next; return "$clock_rc"; }}
next=2340; clock_rc=0; log FIRST || exit 81
clock_rc=1; log SECOND || exit 82
'''
            result = run_shell(code)
            self.assertEqual(result.returncode, 0, result.stderr)
            rows = (Path(tmp) / 'logs/controller.log').read_text().splitlines()
            self.assertIn('uptime=2 ', rows[0])
            self.assertIn('boot_elapsed_ms=2340 run_elapsed_ms=1340 phase_elapsed_ms=840', rows[0])
            self.assertIn('uptime=42 ', rows[1])
            self.assertIn('boot_elapsed_ms=unknown run_elapsed_ms=unknown phase_elapsed_ms=unknown', rows[1])


class ClockShellSyntax(unittest.TestCase):
    def test_shell_syntax(self):
        result = subprocess.run(['sh', '-n', str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
