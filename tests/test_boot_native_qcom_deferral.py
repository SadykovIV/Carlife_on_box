"""Execute owner control flow with a fake monotonic clock and local-only stubs."""
import pathlib
import shlex
import subprocess
import tempfile
import unittest

from tests.test_boot_native_runtime import TEXT, function


class QcomDeferralTests(unittest.TestCase):
    def execute(self, body, extra='', *, functions=()):
        setup = r'''
QCOM_MAIN_BOOTSTRAP_ATTEMPTED=0; QCOM_WRITTEN=0
RUN_STARTED_MS=10000; CLOCK_MS=10000; PHASE=BOOTSTRAP
sample_now_ms() { SAMPLED_NOW_MS=$CLOCK_MS; }
log() { printf 'LOG %s t=%s\n' "$*" "$CLOCK_MS"; }
prop() { case "$1" in
 vendor.qcom.main.start) printf '%s\n' "$QCOM_WRITTEN";;
 sys.usb.configfs) printf '2\n';;
 *) return 94;; esac; }
b() { case "$*" in
 'setprop vendor.qcom.main.start 1')
  QCOM_WRITTEN=1; printf 'QCOM_WRITE phase=%s t=%s\n' "$PHASE" "$CLOCK_MS";;
 ln\ -s\ *) :;;
 *) printf 'UNEXPECTED_COMMAND %s\n' "$*" >&2; return 95;; esac; }
'''
        code = '\n'.join(['set -u', function('number'),
                          function('bootstrap_qcom_main_once'),
                          function('bootstrap_qcom_main_if_due'),
                          *(function(n) for n in functions), setup, extra, body])
        return subprocess.run(['sh', '-c', code], capture_output=True, text=True, timeout=5)

    def checked(self, result, rc=0):
        self.assertEqual(result.returncode, rc, result.stderr + result.stdout)
        self.assertEqual(result.stderr, '')
        return result.stdout.splitlines()

    def test_exact_deadline_is_from_controller_start_and_never_retries(self):
        r = self.execute('''
for CLOCK_MS in 10000 29990; do
 bootstrap_qcom_main_if_due
 [ "$QCOM_MAIN_BOOTSTRAP_ATTEMPTED" = 0 ] || exit 81
done
CLOCK_MS=30000; bootstrap_qcom_main_if_due
CLOCK_MS=90000; bootstrap_qcom_main_if_due; bootstrap_qcom_main_once
''')
        lines = self.checked(r)
        self.assertEqual([x for x in lines if x.startswith('QCOM_WRITE')],
                         ['QCOM_WRITE phase=BOOTSTRAP t=30000'])

    def test_clock_unavailable_invalid_or_regressed_bootstraps_immediately(self):
        for clock_setup in ['RUN_STARTED_MS=unknown', 'sample_now_ms() { return 1; }',
                            'CLOCK_MS=unknown', 'CLOCK_MS=9990']:
            with self.subTest(clock_setup=clock_setup):
                r = self.execute('bootstrap_qcom_main_if_due; bootstrap_qcom_main_if_due', clock_setup)
                self.checked(r)
                self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)

    def test_attempted_helper_does_not_even_resample_clock(self):
        r = self.execute('bootstrap_qcom_main_if_due', '''
QCOM_MAIN_BOOTSTRAP_ATTEMPTED=1
sample_now_ms() { echo UNEXPECTED_SAMPLE; return 1; }
''')
        self.assertEqual(self.checked(r), [])

    def run_owner(self, scenario, extra=''):
        # Execute the production admission/main tail. Only the device-presence
        # path is mapped to a fixture; every USB/property operation is a stub.
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp)
            g = p / 'gadget'
            if scenario != 'missing_gadget':
                for name in ['functions/mtp.gs0', 'functions/accessory.gs2',
                             'configs/b.1', 'strings/0x409']:
                    (g/name).mkdir(parents=True, exist_ok=True)
            (p/'usb_accessory').touch()
            body = TEXT[TEXT.index('phase BOOTSTRAP || fail STATUS\n'):]
            body = body.replace('[ -e /dev/usb_accessory ]',
                                '[ -e ' + shlex.quote(str(p/'usb_accessory')) + ' ]')
            setup = r'''
STOP=0; USB_OWNED=0; DETACH_ARMED=0; DETACH_SINCE=0
ATTACH_FAILURES=0; GENERATION=0; BOOT=boot; RUN_ID=run
ACC_FUNCTION=accessory.gs2; MODE=discovery
phase() { PHASE=$1; printf 'PHASE %s t=%s\n' "$1" "$CLOCK_MS"; }
now() { printf '%s\n' "$((CLOCK_MS/1000))"; }
now_cs() { printf '%s\n' "$((CLOCK_MS/10))"; }
sleep() { CLOCK_MS=$((CLOCK_MS+10000)); }
stop_requested() {
 [ "$SCENARIO" = early_stop ] || [ "$CLOCK_MS" -ge 40000 ] ||
 { [ "$PHASE" = ACCESSORY_CONNECTED ] && [ "$QCOM_WRITTEN" = 1 ]; }
}
same_boot() { :; }
oem_absent() { [ "$SCENARIO" != owner_failure ]; }
choose_accessory() { :; }
read_is() { :; }
links_snapshot() { :; }
physical_profile() {
 [ "$1" = mtp ] || [ "$SCENARIO" = adopt ] || [ "$MODE" = accessory ];
}
start_md() { printf 'MD_OWNER t=%s\n' "$CLOCK_MS"; [ "$SCENARIO" != md_failure ]; }
start_observer() { :; }
identity() { :; }
read_md_ready() { [ "$SCENARIO" != missing_media ]; }
vendor_usb_ready() { [ "$SCENARIO" != missing_vendor ]; }
initialize_usb_gpio() { printf 'GPIO_READY\n'; }
configure_discovery() { phase USB_DISCOVERY; }
accessory_unchanged() { :; }
readf() { printf 'not attached\n'; }
observe_udc() { :; }
advance_discovery() { [ "$SCENARIO" != success ] || enter_accessory; }
regular() { return 1; }
stop_child() { printf 'STOP_CHILD %s\n' "$1"; }
cleanup_groups() { printf 'CLEANUP\n'; }
observer_completed() { :; }
kernel_start_ok() { :; }
atomic() { :; }
clear_known_links() { :; }
write_node() {
 printf 'USB_WRITE %s %s\n' "${1##*/}" "$2"
 [ "${1##*/}" != idProduct ] || MODE=accessory
 return 0
}
'''
            setup += '\nG=' + shlex.quote(str(g)) + '\nR=' + shlex.quote(str(p/'runtime'))
            setup += '\nP=' + shlex.quote(str(p)) + '\nU=' + shlex.quote(str(p/'udc'))
            setup += '\nSCENARIO=' + shlex.quote(scenario) + '\n' + extra
            return self.execute(body, setup,
                                functions=('wait_md_ready', 'enter_accessory', 'fail', 'stop_own'))

    def test_successful_aoa_bootstraps_once_after_verified_bind_and_phase(self):
        r = self.run_owner('success')
        lines = self.checked(r)
        write = next(x for x in lines if x.startswith('QCOM_WRITE'))
        self.assertEqual(write, 'QCOM_WRITE phase=ACCESSORY_CONNECTED t=20000')
        self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)
        self.assertLess(lines.index('MD_OWNER t=10000'), lines.index('USB_WRITE UDC a600000.dwc3'))
        self.assertLess(lines.index('USB_WRITE UDC a600000.dwc3'), lines.index('PHASE ACCESSORY_CONNECTED t=20000'))
        self.assertLess(lines.index('PHASE ACCESSORY_CONNECTED t=20000'), lines.index(write))

    def test_adoption_also_bootstraps_after_connected_without_rebinding(self):
        r = self.run_owner('adopt')
        self.checked(r)
        self.assertIn('QCOM_WRITE phase=ACCESSORY_CONNECTED t=10000', r.stdout)
        self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)
        self.assertNotIn('USB_WRITE', r.stdout)

    def test_missing_hu_gadget_media_and_vendor_each_reach_fallback(self):
        phases = {'no_hu': 'USB_DISCOVERY', 'missing_gadget': 'WAIT_GADGET_DIRECTORIES',
                  'missing_media': 'MEDIA_WAIT', 'missing_vendor': 'WAIT_VENDOR_USB_ACTIONS'}
        for scenario, phase in phases.items():
            with self.subTest(scenario=scenario):
                r = self.run_owner(scenario)
                self.checked(r)
                self.assertIn(f'QCOM_WRITE phase={phase} t=30000', r.stdout)
                self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)
                self.assertNotIn('USB_WRITE', r.stdout)

    def test_early_failure_and_stop_restore_radio_before_cleanup(self):
        for scenario, rc in [('owner_failure', 71), ('md_failure', 71), ('early_stop', 0)]:
            with self.subTest(scenario=scenario):
                r = self.run_owner(scenario)
                self.checked(r, rc)
                self.assertIn('QCOM_WRITE phase=BOOTSTRAP t=10000', r.stdout)
                self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)
                self.assertLess(r.stdout.index('QCOM_WRITE'), r.stdout.index('STOP_CHILD'))

    def test_stop_status_failure_still_attempts_radio(self):
        r = self.execute('stop_own', 'phase() { return 1; }', functions=('stop_own',))
        self.checked(r, 1)
        self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)

    def test_failed_connected_status_does_not_mask_transition_failure(self):
        r = self.run_owner('success', '''
phase() { PHASE=$1; [ "$1" != ACCESSORY_CONNECTED ]; }
DISCOVERY_FAILURE=AOA_TRANSITION
''')
        self.checked(r, 71)
        self.assertIn('FAIL_AOA_TRANSITION', r.stdout)
        self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)

    def test_owner_unknown_clock_uses_previous_immediate_policy(self):
        r = self.run_owner('early_stop', 'sample_now_ms() { return 1; }')
        self.checked(r)
        self.assertIn('QCOM_WRITE phase=BOOTSTRAP t=10000', r.stdout)
        self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)

    def test_short_readiness_loops_check_due_without_changing_their_result(self):
        cases = [
            ('wait_observer_ready', 'identity() { return 1; }', 1),
            ('observer_completed', 'identity() { return 1; }; child_exited() { return 1; }', 1),
        ]
        for name, setup, rc in cases:
            with self.subTest(name=name):
                r = self.execute(name, 'CLOCK_MS=30000\n' + setup, functions=(name,))
                self.checked(r, rc)
                self.assertEqual(r.stdout.count('QCOM_WRITE'), 1)


if __name__ == '__main__':
    unittest.main()
