"""Execute the production helper with local commands; no Android/device I/O."""
import os
import pathlib
import shlex
import subprocess
import sys
import tempfile
import time
import unittest

from tests.test_boot_native_runtime import TEXT, function


class QcomBootstrapTests(unittest.TestCase):
    def run_case(self, before='', read_rc=0, write_rc=0, readback='1', readback_rc=0, log_rc=0):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp)
            script = '\n'.join([
                'set -u', function('bootstrap_qcom_main_once'),
                'QCOM_MAIN_BOOTSTRAP_ATTEMPTED=0',
                'calls=' + shlex.quote(str(p/'calls')),
                'logs=' + shlex.quote(str(p/'logs')),
                'written=' + shlex.quote(str(p/'written')),
                'log() { printf "%s\\n" "$*" >> "$logs"; return ' + str(log_rc) + '; }',
                'prop() { [ "$1" = vendor.qcom.main.start ] || exit 98; '
                'if [ -e "$written" ]; then printf "%s\\n" ' + shlex.quote(readback) + '; return ' + str(readback_rc) +
                '; else printf "%s\\n" ' + shlex.quote(before) + '; return ' + str(read_rc) + '; fi; }',
                'b() { [ "$QCOM_MAIN_BOOTSTRAP_ATTEMPTED" = 1 ] || return 99; '
                'printf "%s\\n" "$*" >> "$calls"; : > "$written"; return ' + str(write_rc) + '; }',
                'bootstrap_qcom_main_once || exit 80',
                'bootstrap_qcom_main_once || exit 81',
                'printf "continued flag=%s\\n" "$QCOM_MAIN_BOOTSTRAP_ATTEMPTED"',
            ])
            out = subprocess.run(['sh', '-c', script], capture_output=True, text=True, timeout=5)
            return out, (p/'logs').read_text(), (p/'calls').read_text() if (p/'calls').exists() else ''

    def test_missing_property_requests_exact_stock_trigger_once(self):
        out, logs, calls = self.run_case()
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(calls, 'setprop vendor.qcom.main.start 1\n')
        self.assertIn('QCOM_MAIN_BOOTSTRAP_REQUESTED\n', logs)
        self.assertEqual(out.stdout, 'continued flag=1\n')

    def test_already_requested_has_no_write(self):
        out, logs, calls = self.run_case(before='1')
        self.assertEqual(out.returncode, 0)
        self.assertEqual(calls, '')
        self.assertIn('ALREADY_REQUESTED', logs)

    def test_zero_property_is_inactive_and_requested_once(self):
        out, logs, calls = self.run_case(before='0')
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(calls, 'setprop vendor.qcom.main.start 1\n')
        self.assertIn('QCOM_MAIN_BOOTSTRAP_REQUESTED\n', logs)

    def test_unexpected_value_never_overwritten(self):
        for value in ['2', '1\n0', 'bad']:
            with self.subTest(value=value):
                out, logs, calls = self.run_case(before=value)
                self.assertEqual(out.returncode, 0)
                self.assertEqual(calls, '')
                self.assertIn('stage=unexpected_value', logs)

    def test_initial_read_error_or_timeout_never_writes(self):
        for rc in [1, 124, 127, 137]:
            with self.subTest(rc=rc):
                out, logs, calls = self.run_case(before='1', read_rc=rc)
                self.assertEqual(out.returncode, 0)
                self.assertEqual(calls, '')
                self.assertIn(f'stage=read rc={rc}', logs)

    def test_failed_write_never_retries_or_aborts_projection(self):
        for rc in [1, 70, 124, 127, 137]:
            with self.subTest(rc=rc):
                out, logs, calls = self.run_case(write_rc=rc)
                self.assertEqual(out.returncode, 0)
                self.assertEqual(calls, 'setprop vendor.qcom.main.start 1\n')
                self.assertIn(f'stage=write rc={rc}', logs)
                self.assertNotIn('BOOTSTRAP_REQUESTED\n', logs)

    def test_readback_is_required_for_requested_label(self):
        for value, rc in [('', 0), ('0', 0), ('1', 1), ('1', 124)]:
            with self.subTest(value=value, rc=rc):
                out, logs, calls = self.run_case(readback=value, readback_rc=rc)
                self.assertEqual(out.returncode, 0)
                self.assertEqual(len(calls.splitlines()), 1)
                self.assertIn('stage=readback', logs)
                self.assertNotIn('BOOTSTRAP_REQUESTED\n', logs)

    def test_failed_log_does_not_block_write_or_carlife(self):
        out, logs, calls = self.run_case(log_rc=1)
        self.assertEqual(out.returncode, 0)
        self.assertEqual(len(calls.splitlines()), 1)

    def test_real_bounded_write_timeout(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp)
            (p/'timeout').write_text('#!' + sys.executable + '\n' + '''import subprocess,sys
assert sys.argv[1:4] == ['-k','1','3']
try:
    r=subprocess.run(sys.argv[4:],timeout=3)
    sys.exit(r.returncode)
except subprocess.TimeoutExpired:
    sys.exit(124)
''')
            (p/'setprop').write_text('#!' + sys.executable + '\nimport time\ntime.sleep(10)\n')
            for n in ['timeout', 'setprop']: (p/n).chmod(0o700)
            script = '\n'.join(['set -u', function('b'), function('bootstrap_qcom_main_once'),
                'QCOM_MAIN_BOOTSTRAP_ATTEMPTED=0', 'prop() { printf "\\n"; }',
                'log() { printf "%s\\n" "$*"; }',
                'bootstrap_qcom_main_once; bootstrap_qcom_main_once; printf "CONTINUED\\n"'])
            started = time.monotonic()
            out = subprocess.run(['sh', '-c', script], env={**os.environ, 'PATH': tmp+':'+os.environ['PATH']}, capture_output=True, text=True, timeout=6)
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertGreaterEqual(time.monotonic()-started, 2.8)
            self.assertLess(time.monotonic()-started, 5)
            self.assertEqual(out.stdout.count('stage=write rc=124'), 1)
            self.assertTrue(out.stdout.endswith('CONTINUED\n'))

    def test_scheduling_is_only_after_owner_admission_and_not_a_usb_gate(self):
        claim = TEXT.index('claim_owner ||')
        fallback = TEXT.index('\nbootstrap_qcom_main_if_due\n', claim)
        self.assertLess(TEXT.index('phase BOOTSTRAP ||', claim), fallback)
        self.assertLess(fallback, TEXT.index('while ! stop_requested', claim))
        client_actions = TEXT[TEXT.index('if [ "$ACTION" = status ]; then'):claim]
        self.assertNotIn('bootstrap_qcom_main_', client_actions)
        helper = '\n'.join(line for line in function('bootstrap_qcom_main_once').splitlines() if not line.lstrip().startswith('#'))
        for forbidden in ['sys.usb', 'persist.', 'ctl.', 'sdAutoReverse ', 'sdCarplaySvc ', 'sys.boot_completed', 'start vendor.', 'stop vendor.']:
            self.assertNotIn(forbidden, helper)
        self.assertLess(helper.index('QCOM_MAIN_BOOTSTRAP_ATTEMPTED=1'), helper.index('qmb_before=$(prop'))


if __name__ == '__main__': unittest.main()
