"""Exercise controller timing without opening device nodes or changing gates."""
import pathlib
import shlex
import tempfile
import unittest

from tests.test_boot_native_runtime import TEXT, function, shell


class ControllerTimingTests(unittest.TestCase):
    def run_timing(self, body, extra=''):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / 'logs').mkdir()
            setup = f'''
P={shlex.quote(tmp)}; BOOT=testboot; RUN_ID=testrun; GENERATION=0
PHASE=INITIALIZING; RUN_STARTED_MS=6000; PHASE_STARTED_MS=6000
CLOCK_MS=10000
sample_now_ms() {{ SAMPLED_NOW_MS=$CLOCK_MS; }}
now() {{ printf '10\\n'; }}
regular() {{ return 1; }}
owned_file() {{ return 0; }}
atomic() {{ printf '%s\\n' "$2" > "$P/status"; }}
'''
            out = shell(['number', 'log', 'phase'], body, setup + extra)
            lines = (root / 'logs/controller.log').read_text().splitlines()
            status = (root / 'status').read_text() if (root / 'status').exists() else ''
            return out, [dict(token.split('=', 1) for token in line.split()) for line in lines], status

    def test_phase_transition_reports_actual_elapsed_and_existing_contract(self):
        out, rows, status = self.run_timing('''
phase BOOTSTRAP || exit 81
CLOCK_MS=14050; phase MEDIA_WAIT || exit 82
CLOCK_MS=14520; log WAIT_MD_COMMAND_AND_MEDIA || exit 83
''')
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(out.stderr, '')
        self.assertEqual(len(rows), 3)
        self.assertEqual(rows[0]['previous_phase_elapsed_ms'], '4000')
        self.assertEqual(rows[1]['previous_phase'], 'BOOTSTRAP')
        self.assertEqual(rows[1]['previous_phase_elapsed_ms'], '4050')
        self.assertEqual(rows[1]['boot_elapsed_ms'], '14050')
        self.assertEqual(rows[1]['uptime'], '14')
        self.assertEqual(rows[1]['run_elapsed_ms'], '8050')
        self.assertEqual(rows[2]['phase_elapsed_ms'], '470')
        self.assertEqual(rows[2]['clock'], 'proc_uptime')
        self.assertIn('contract=BOOT_NATIVE_RUNTIME_V1\nphase=MEDIA_WAIT\n', status)
        self.assertIn('media_validation=DISPLAY_PLUS_CODEC_CAPABILITIES_NOT_ENCODER_START', status)

    def test_repeated_wait_preserves_elapsed_and_rearm_starts_new_phase(self):
        out, rows, _ = self.run_timing('''
phase WAIT_GADGET_DIRECTORIES
CLOCK_MS=11250; phase WAIT_GADGET_DIRECTORIES
CLOCK_MS=14000; phase MEDIA_WAIT
CLOCK_MS=50000; GENERATION=1; phase DETACH_REARM
''')
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(rows[1]['phase_elapsed_ms'], '1250')
        self.assertEqual(rows[2]['previous_phase_elapsed_ms'], '4000')
        self.assertEqual(rows[3]['phase_elapsed_ms'], '0')
        self.assertEqual(rows[3]['generation'], '1')
        self.assertEqual(rows[3]['run_elapsed_ms'], '44000')

    def test_clock_read_error_is_unknown_not_a_new_startup_failure(self):
        out, rows, _ = self.run_timing('phase MEDIA_WAIT || exit 81', 'sample_now_ms() { return 1; }')
        self.assertEqual(out.returncode, 0, out.stderr)
        for field in ['boot_elapsed_ms', 'run_elapsed_ms', 'phase_elapsed_ms', 'previous_phase_elapsed_ms']:
            self.assertEqual(rows[0][field], 'unknown')
        self.assertEqual(rows[0]['uptime'], '10')

    def test_clock_going_back_does_not_publish_negative_duration(self):
        out, rows, _ = self.run_timing('CLOCK_MS=5000; phase MEDIA_WAIT')
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(rows[0]['run_elapsed_ms'], 'unknown')
        self.assertEqual(rows[0]['previous_phase_elapsed_ms'], 'unknown')

    def test_centiseconds_conversion_handles_leading_zeroes_in_fraction(self):
        # Feed /proc's fixed two-decimal format through the production parser.
        parser = function('sample_now_ms').replace('/proc/uptime', '"$clock_file"')
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'uptime'
            for clock, expected in [('9.99 0\n', '9990'), ('10.01 0\n', '10010')]:
                path.write_text(clock)
                out = subprocess.run(['sh', '-c', '\n'.join([
                    'set -u', function('number'), parser,
                    'clock_file=' + shlex.quote(str(path)),
                    'sample_now_ms || exit 81', 'printf \'%s\\n\' "$SAMPLED_NOW_MS"',
                ])], capture_output=True, text=True, timeout=5)
                self.assertEqual(out.returncode, 0, out.stderr)
                self.assertEqual(out.stdout.strip(), expected)

    def test_usb_bind_diagnostics_do_not_remove_post_bind_verification(self):
        for name, profile in [('configure_discovery', 'mtp'), ('enter_accessory', 'accessory')]:
            code = function(name)
            bind = code.index('write_node "$G/UDC" a600000.dwc3 || return 1')
            returned = code.index('bind_return_ms=$SAMPLED_NOW_MS', bind)
            verify = code.index('physical_profile ' + profile + ' || return 1', returned)
            self.assertLess(bind, returned)
            self.assertLess(returned, verify)
            self.assertLess(verify, code.index('USB_OWNED=1', verify))
        # No periodic timing task, additional sleep, or readiness condition.
        for name in ['sample_now_ms', 'log', 'phase']:
            self.assertNotIn('sleep ', function(name))
        self.assertIn('if sample_now_ms; then RUN_STARTED_MS=$SAMPLED_NOW_MS; else RUN_STARTED_MS=unknown; fi\nPHASE_STARTED_MS=$RUN_STARTED_MS\nclaim_owner', TEXT)


if __name__ == '__main__':
    unittest.main()
