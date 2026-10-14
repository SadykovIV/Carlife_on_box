"""Execute the actual shell helper; never contact Android or USB."""
import json
import os
import pathlib
import shlex
import subprocess
import sys
import tempfile
import time
import unittest

from tests.test_boot_native_runtime import TEXT, function


class FloatingNavTests(unittest.TestCase):
    def run_case(self, body, command='printf "Starting service: Intent {}\\n"', log='printf "%s\\n" "$*" >> "$events"'):
        with tempfile.TemporaryDirectory() as tmp:
            events = pathlib.Path(tmp, 'events')
            calls = pathlib.Path(tmp, 'calls')
            script = '\n'.join([
                'set -u', function('start_floating_nav_once'),
                'PHASE=ACCESSORY_CONNECTED; RECOVERY_REASON=NONE; udc_state=configured; FLOATING_NAV_ATTEMPTED=0',
                f'events={shlex.quote(str(events))}; calls={shlex.quote(str(calls))}',
                'log() { ' + log + '; }',
                'b() { [ "$FLOATING_NAV_ATTEMPTED" = 1 ] || return 99; printf "%s\\n" "$*" >> "$calls"; ' + command + '; }',
                body,
                'printf "flag=%s\\n" "$FLOATING_NAV_ATTEMPTED"',
            ])
            p = subprocess.run(['sh', '-c', script], capture_output=True, text=True, timeout=5)
            return p, events.read_text() if events.exists() else '', calls.read_text() if calls.exists() else ''

    def test_success_exact_command_once_across_reconnected_phase(self):
        p, log, calls = self.run_case('start_floating_nav_once; PHASE=USB_DISCOVERY; start_floating_nav_once; PHASE=ACCESSORY_CONNECTED; start_floating_nav_once')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(calls.splitlines(), ['am startservice --user 0 -n com.sd.ball/.WindowService -a com.sd.intent.action.WindowService'])
        self.assertIn('rc=0 result=COMMAND_ACCEPTED', log)
        self.assertEqual(p.stdout, 'flag=1\n')

    def test_rc_zero_error_response_is_failure_and_not_retried(self):
        for response in ['Error: Not found; no service started.', 'java.lang.SecurityException: Permission Denial', 'Exception occurred while executing']:
            with self.subTest(response=response):
                p, log, calls = self.run_case('start_floating_nav_once; start_floating_nav_once', 'printf "%s\\n" ' + shlex.quote(response))
                self.assertEqual(p.returncode, 0, p.stderr)
                self.assertIn('rc=0 result=ERROR_RESPONSE', log)
                self.assertEqual(len(calls.splitlines()), 1)

    def test_nonzero_and_missing_am_never_fail_or_retry(self):
        for rc in [1, 70, 124, 127, 137]:
            with self.subTest(rc=rc):
                p, log, calls = self.run_case('start_floating_nav_once || exit 88; start_floating_nav_once', f'printf "am failed\\n" >&2; return {rc}')
                self.assertEqual(p.returncode, 0, p.stderr)
                self.assertIn(f'rc={rc} result=COMMAND_FAILED', log)
                self.assertEqual(len(calls.splitlines()), 1)

    def test_readiness_discovery_and_recovery_do_not_consume_attempt(self):
        for setting in ['PHASE=USB_DISCOVERY', 'PHASE=WAIT_VENDOR_USB_ACTIONS', 'RECOVERY_REASON=DETACH', 'RECOVERY_REASON=MD_EXIT', 'udc_state="not attached"', 'udc_state=addressed', 'udc_state=default', 'udc_state=suspended', 'udc_state=']:
            with self.subTest(setting=setting):
                p, log, calls = self.run_case(setting + '; start_floating_nav_once')
                self.assertEqual(p.returncode, 0, p.stderr)
                self.assertEqual((log, calls, p.stdout), ('', '', 'flag=0\n'))

    def test_configured_after_skipped_detach_can_attempt(self):
        p, log, calls = self.run_case('RECOVERY_REASON=DETACH; start_floating_nav_once; RECOVERY_REASON=NONE; udc_state=addressed; start_floating_nav_once; udc_state=configured; start_floating_nav_once')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(len(calls.splitlines()), 1)

    def test_log_failure_does_not_fail_usb_or_repeat(self):
        p, log, calls = self.run_case('start_floating_nav_once || exit 88; start_floating_nav_once', log='return 1')
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertEqual(len(calls.splitlines()), 1)

    def test_actual_bounded_command_timeout(self):
        # macOS lacks Android toybox timeout. This fixture executes the exact b()
        # argv through a local timeout-compatible subprocess, with a real sleeping
        # am process. It does not claim to test Android's timeout implementation.
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root/'timeout').write_text('#!' + sys.executable + '\n' + '''import json, pathlib, subprocess, sys
pathlib.Path(__file__).with_name('timeout-argv.json').write_text(json.dumps(sys.argv[1:]))
assert sys.argv[1:4] == ['-k', '1', '3']
try:
    p = subprocess.run(sys.argv[4:], timeout=float(sys.argv[3]))
    sys.exit(p.returncode)
except subprocess.TimeoutExpired:
    sys.exit(124)
''')
            (root/'am').write_text('#!' + sys.executable + '\nimport pathlib,time\npathlib.Path(__file__).with_name("am-called").write_text("yes")\ntime.sleep(10)\n')
            for name in ['timeout', 'am']:
                (root/name).chmod(0o700)
            script = '\n'.join(['set -u', function('b'), function('start_floating_nav_once'),
                'PHASE=ACCESSORY_CONNECTED; RECOVERY_REASON=NONE; udc_state=configured; FLOATING_NAV_ATTEMPTED=0',
                'log() { printf "%s\\n" "$*"; }',
                'start_floating_nav_once || exit 88; start_floating_nav_once'])
            began = time.monotonic()
            p = subprocess.run(['sh', '-c', script], env={**os.environ, 'PATH': tmp + ':' + os.environ['PATH']}, capture_output=True, text=True, timeout=6)
            elapsed = time.monotonic() - began
            self.assertEqual(p.returncode, 0, p.stderr)
            self.assertTrue((root/'am-called').exists())
            self.assertGreaterEqual(elapsed, 2.8)
            self.assertLess(elapsed, 5)
            self.assertEqual(p.stdout.count('START_ATTEMPT'), 1)
            self.assertIn('rc=124 result=COMMAND_FAILED', p.stdout)
            self.assertEqual(json.loads((root/'timeout-argv.json').read_text())[4:], ['startservice', '--user', '0', '-n', 'com.sd.ball/.WindowService', '-a', 'com.sd.intent.action.WindowService'])

    def test_only_main_loop_none_branch_calls_ui(self):
        marker = 'recovery_reason || fail MD_IDENTITY'
        self.assertEqual(TEXT.count('FLOATING_NAV_ATTEMPTED=0'), 1)
        self.assertEqual(TEXT.count('                start_floating_nav_once\n'), 1)
        branch = TEXT[TEXT.rfind(marker):]
        self.assertIn('else\n                start_floating_nav_once\n', branch)
        helper = function('start_floating_nav_once')
        self.assertNotIn('setprop', helper)
        self.assertNotIn('settings ', helper)
        self.assertNotRegex(helper, r'(?m)(?<!&)\s&\s*$')
        self.assertLess(helper.index('FLOATING_NAV_ATTEMPTED=1'), helper.index('fnav_output=$(b am'))


if __name__ == '__main__':
    unittest.main()
