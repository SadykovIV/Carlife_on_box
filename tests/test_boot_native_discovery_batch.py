"""Exercise real discovery publication over private files; never access USB nodes."""
import pathlib
import shlex
import subprocess
import tempfile
import unittest

from tests.test_boot_native_runtime import function


ATTRIBUTES = [
    ('idVendor', '0x18d1'), ('idProduct', '0x4ee1'), ('bcdDevice', '0x1001'),
    ('bDeviceClass', '0x00'), ('bDeviceSubClass', '0x00'), ('bDeviceProtocol', '0x00'),
    ('strings/0x409/serialnumber', 'serial_1.A-2'),
    ('strings/0x409/manufacturer', 'CarLife'), ('strings/0x409/product', 'AndroidBox'),
    ('configs/b.1/bmAttributes', '0xc0'),
]


class DiscoveryBatchTests(unittest.TestCase):
    def run_case(self, *, failure='', failed_attribute=None, serial='serial_1.A-2'):
        with tempfile.TemporaryDirectory() as tmp:
            # Spaces and shell syntax are literal argv data, not interpolated script.
            gadget = pathlib.Path(tmp) / 'gadget with spaces $(not_a_command)'
            for name, _ in ATTRIBUTES:
                target = gadget / name
                target.parent.mkdir(parents=True, exist_ok=True)
                if name == failed_attribute:
                    target.mkdir()
                else:
                    target.write_text('original\n')
            (gadget / 'functions/mtp.gs0').mkdir(parents=True)
            (gadget / 'UDC').write_text('\n')
            other = pathlib.Path(tmp) / 'other-UDC'
            other.write_text('\n')
            setup = f'''
G={shlex.quote(str(gadget))}; OTHER={shlex.quote(str(other))}
R=/unused; RUN_ID=run; BOOT=boot; USB_OWNED=0; READY=1
FAIL={shlex.quote(failure)}; SERIAL={shlex.quote(serial)}
same_boot() {{ [ "$FAIL" != boot ]; }}
oem_absent() {{ [ "$FAIL" != oem ]; }}
accessory_unchanged() {{ [ "$FAIL" != layout ]; }}
read_md_ready() {{ [ "$FAIL" != media ] && [ "$READY" = 1 ]; }}
observer_ready() {{ [ "$FAIL" != observer ]; }}
read_is() {{
    path=$1; [ "$path" != /config/usb_gadget/g2/UDC ] || path=$OTHER
    value=$(cat "$path") || return 1
    [ "$value" = "$2" ]
}}
prop() {{
    case "$1" in
        sys.usb.mtp.device_type) printf '3\\n';;
        ro.serialno) printf '%s\\n' "$SERIAL";;
        *) return 90;;
    esac
}}
atomic() {{ printf 'ATOMIC %s\\n' "$1"; }}
clear_known_links() {{ printf 'CLEAR\\n'; }}
b() {{
    case "$1" in
        sha256sum) cat >/dev/null; printf 'hash  -\\n';;
        setprop)
            [ "$2:$3" = sys.usb.configfs:2 ] || return 91
            case "$FAIL" in
                rebound_g1) printf 'foreign\\n' > "$G/UDC";;
                rebound_g2) printf 'foreign\\n' > "$OTHER";;
            esac;;
        ln)
            printf 'LINK\\n'
            [ "$FAIL" != link ] || return 1
            command "$@";;
        *) return 92;;
    esac
}}
timeout() {{
    printf 'BATCH_DEADLINE %s %s %s\\n' "$1" "$2" "$3"
    [ "$1:$2:$3:$4:$5" = '-k:1:3:sh:-c' ] || return 93
    [ "$FAIL" != timeout ] || return 124
    shift 3
    command "$@"; batch_rc=$?
    [ "$batch_rc" = 0 ] || return "$batch_rc"
    [ "$FAIL" != media_lost ] || READY=0
}}
write_node() {{
    [ "$1" = "$G/UDC" ] && [ "$2" = a600000.dwc3 ] || return 94
    printf 'BIND\\n'
    printf '%s\\n' "$2" > "$1"
}}
physical_profile() {{
    printf 'PROFILE %s\\n' "$1"
    [ "$FAIL" != readback ] || return 1
    [ "$1" = mtp ] && read_is "$G/idVendor" 0x18d1 &&
        read_is "$G/idProduct" 0x4ee1 && read_is "$G/UDC" a600000.dwc3 &&
        [ "$(readlink "$G/configs/b.1/f1")" = "$G/functions/mtp.gs0" ]
}}
sample_now_ms() {{ SAMPLED_NOW_MS=10000; }}
log() {{ printf 'LOG %s\\n' "$1"; }}
phase() {{ printf 'PHASE %s\\n' "$1"; }}
'''
            body = '''
configure_discovery; rc=$?
printf 'RESULT rc=%s owned=%s\n' "$rc" "$USB_OWNED"
exit "$rc"
'''
            result = subprocess.run(
                ['sh', '-c', 'set -u\n' + function('configure_discovery') + '\n' + setup + body],
                capture_output=True, text=True, timeout=5,
            )
            values = {name: (gadget / name).read_text() for name, _ in ATTRIBUTES
                      if (gadget / name).is_file()}
            return result, values, (gadget / 'UDC').read_text()

    def test_exact_ten_values_and_single_batch_before_bind_readback(self):
        result, values, udc = self.run_case()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values, {name: value + '\n' for name, value in ATTRIBUTES})
        self.assertEqual(udc, 'a600000.dwc3\n')
        lines = result.stdout.splitlines()
        self.assertEqual(lines.count('BATCH_DEADLINE -k 1 3'), 1)
        for earlier, later in [('CLEAR', 'BATCH_DEADLINE -k 1 3'),
                               ('BATCH_DEADLINE -k 1 3', 'LINK'), ('LINK', 'BIND'),
                               ('BIND', 'PROFILE mtp'), ('PROFILE mtp', 'PHASE USB_DISCOVERY')]:
            self.assertLess(lines.index(earlier), lines.index(later))
        self.assertIn('RESULT rc=0 owned=1', lines)

    def test_each_failed_write_stops_at_exact_prefix_without_link_or_bind(self):
        # Failure at every position proves order and absence of writes after error.
        for failed_index, (failed, _) in enumerate(ATTRIBUTES):
            with self.subTest(failed=failed):
                result, values, udc = self.run_case(failed_attribute=failed)
                self.assertNotEqual(result.returncode, 0)
                for index, (name, expected) in enumerate(ATTRIBUTES):
                    if index != failed_index:
                        self.assertEqual(values[name], (expected if index < failed_index else 'original') + '\n')
                self.assertEqual(udc, '\n')
                self.assertNotIn('LINK\n', result.stdout)
                self.assertNotIn('BIND\n', result.stdout)
                self.assertIn('owned=0', result.stdout)

    def test_existing_identity_layout_and_readiness_fail_before_batch(self):
        for failure in ['boot', 'oem', 'layout', 'media', 'observer']:
            with self.subTest(failure=failure):
                result, values, udc = self.run_case(failure=failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('BATCH_DEADLINE', result.stdout)
                self.assertEqual(set(values.values()), {'original\n'})
                self.assertEqual(udc, '\n')

    def test_rebound_either_gadget_after_initial_guard_refuses_batch(self):
        for failure in ['rebound_g1', 'rebound_g2']:
            with self.subTest(failure=failure):
                result, values, _ = self.run_case(failure=failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('BATCH_DEADLINE', result.stdout)
                self.assertNotIn('BIND\n', result.stdout)
                self.assertEqual(set(values.values()), {'original\n'})

    def test_batch_has_three_second_bound_and_timeout_never_publishes(self):
        result, values, udc = self.run_case(failure='timeout')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('BATCH_DEADLINE -k 1 3', result.stdout)
        self.assertNotIn('LINK\n', result.stdout)
        self.assertNotIn('BIND\n', result.stdout)
        self.assertEqual(set(values.values()), {'original\n'})
        self.assertEqual(udc, '\n')

    def test_link_or_final_media_guard_failure_never_binds(self):
        for failure in ['link', 'media_lost']:
            with self.subTest(failure=failure):
                result, values, udc = self.run_case(failure=failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(values, {name: value + '\n' for name, value in ATTRIBUTES})
                self.assertNotIn('BIND\n', result.stdout)
                self.assertEqual(udc, '\n')

    def test_post_bind_readback_still_required_before_claiming_ownership(self):
        result, _, udc = self.run_case(failure='readback')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(udc, 'a600000.dwc3\n')
        self.assertIn('PROFILE mtp', result.stdout)
        self.assertIn('owned=0', result.stdout)
        self.assertNotIn('PHASE USB_DISCOVERY', result.stdout)

    def test_serial_validation_and_positional_data_are_preserved(self):
        for serial in ['', 'bad;exit 0', '$(touch unexpected)', 'two\nlines', 'a' * 127]:
            with self.subTest(serial=serial):
                result, values, _ = self.run_case(serial=serial)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('BATCH_DEADLINE', result.stdout)
                self.assertEqual(set(values.values()), {'original\n'})
        result, values, _ = self.run_case(serial='a' * 126)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values['strings/0x409/serialnumber'], 'a' * 126 + '\n')


if __name__ == '__main__':
    unittest.main()
