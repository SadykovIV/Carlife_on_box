"""Run real controller GPIO/bootstrap shell functions over an isolated fake sysfs."""
import json, pathlib, subprocess, tempfile, unittest
from tests.test_boot_native_runtime import function
ROOT=pathlib.Path(__file__).resolve().parents[1]
TEXT=(ROOT/'tools/boot_native/runtime/runtime-controller.sh').read_text()
class GpioInitTests(unittest.TestCase):
 def run_case(self,*,exported=False,direction='out',value='1',board='chelianyi-led',channel='sd_msm6350',base='1124',ngpio='156',label='f100000.pinctrl',failure='',adopt=None):
  with tempfile.TemporaryDirectory() as tmp:
   p=pathlib.Path(tmp);chip=p/'sys/class/gpio/gpiochip1124';chip.mkdir(parents=True)
   for n,v in [('base',base),('ngpio',ngpio),('label',label)]: (chip/n).write_text(v+'\n')
   (p/'system/bin').mkdir(parents=True);(p/'system/bin/sd-reverse.sh').write_text('CHANNEL='+channel+'\n')
   line=p/'sys/class/gpio/gpio1132'
   if exported:
    line.mkdir();(line/'direction').write_text(direction+'\n');(line/'value').write_text(value+'\n')
   fn=function('initialize_usb_gpio',TEXT).replace('/sys/class/gpio',str(p/'sys/class/gpio')).replace('/system/bin/sd-reverse.sh',str(p/'system/bin/sd-reverse.sh'))
   setup=f'''G=/mock/g1; STOP=0; GPIO_FAILURE=; TESTROOT='{p}'; FAIL='{failure}'; BOARD='{board}'; PRIOR_DIRECTION='{direction}'; PRIOR_VALUE='{value}'
prop() {{ printf '%s' "$BOARD"; }}
b() {{ command "$@"; }}
readf() {{ [ "$FAIL" != read ] || return 1; cat "$1"; }}
read_is() {{ case "$1" in */UDC) return 0;; esac; got=$(readf "$1") || return 1; [ "$got" = "$2" ]; }}
same_boot() {{ :; }}
oem_absent() {{ [ "$FAIL" != oem ]; }}
log() {{ printf 'LOG %s\\n' "$1"; }}
write_node() {{
 printf 'WRITE %s %s\\n' "$1" "$2"
 case "$1" in
  */export) [ "$FAIL" != export ] || return 1
   mkdir "$TESTROOT/sys/class/gpio/gpio1132" || return 1
   printf '%s\\n' "$PRIOR_DIRECTION" > "$TESTROOT/sys/class/gpio/gpio1132/direction"
   printf '%s\\n' "$PRIOR_VALUE" > "$TESTROOT/sys/class/gpio/gpio1132/value";;
  */direction) [ "$FAIL" != direction ] || return 1
   [ "$2" = low ] || return 92
   printf 'out\\n' > "$1"
   [ "$FAIL" != readback ] || return 0
   printf '0\\n' > "${{1%/*}}/value";;
  *) return 93;;
 esac
}}
'''
   if adopt is None:body='initialize_usb_gpio; rc=$?; printf "RESULT rc=%s failure=%s\\n" "$rc" "$GPIO_FAILURE"; exit "$rc"'
   else:
    # Execute unchanged real main branch, not a separately reproduced condition.
    body=TEXT[TEXT.index('ADOPT=0\n'):TEXT.index('\nwhile ! stop_requested; do',TEXT.index('ADOPT=0\n'))]
    setup+=f'''physical_profile() {{ return {0 if adopt else 1}; }}
phase() {{ printf 'PHASE %s\\n' "$1"; }}
bootstrap_qcom_main_once() {{ :; }}
bootstrap_qcom_main_if_due() {{ :; }}
start_md() {{ printf 'MD_START\\n'; }}
start_observer() {{ printf 'OBSERVER_START\\n'; }}
wait_md_ready() {{ printf 'MD_READY\\n'; }}
vendor_usb_ready() {{ return 0; }}
stop_requested() {{ return 1; }}
identity() {{ :; }}
configure_discovery() {{ printf 'UDC_PUBLICATION\\n'; }}
fail() {{ printf 'FAULT_%s\\n' "$1"; exit 71; }}
'''
   result=subprocess.run(['sh','-c','set -u\n'+fn+'\n'+setup+'\n'+body],capture_output=True,text=True,timeout=5)
   after={n:(line/n).read_text().strip() for n in ('direction','value') if (line/n).exists()}
   return result,after
 def test_fresh_export_prior_out1_becomes_atomic_low_before_publication(self):
  r,a=self.run_case(adopt=False);self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(a,{'direction':'out','value':'0'})
  self.assertIn('/export 1132',r.stdout);self.assertIn('/direction low',r.stdout)
  self.assertLess(r.stdout.index('GPIO1132_READY_OUT0'),r.stdout.index('UDC_PUBLICATION'))
  self.assertNotIn('value 1',r.stdout);self.assertNotIn('unexport',r.stdout)
 def test_existing_out0_is_idempotent_no_gpio_writes(self):
  r,a=self.run_case(exported=True,value='0',adopt=False);self.assertEqual(r.returncode,0,r.stderr);self.assertNotIn('WRITE',r.stdout);self.assertIn('UDC_PUBLICATION',r.stdout)
 def test_existing_out1_one_atomic_direction_write(self):
  r,a=self.run_case(exported=True);self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(r.stdout.count('WRITE'),1);self.assertIn('/direction low',r.stdout);self.assertEqual(a['value'],'0')
 def test_input_line_becomes_low_without_high_pulse(self):
  r,a=self.run_case(exported=True,direction='in',value='0');self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(a,{'direction':'out','value':'0'});self.assertIn('/direction low',r.stdout)
 def test_wrong_board_channel_or_chip_mapping_refuses_before_write_or_publication(self):
  for kw in [dict(board='other'),dict(channel='other'),dict(base='1123'),dict(ngpio='155'),dict(label='other')]:
   with self.subTest(kw=kw):
    r,a=self.run_case(adopt=False,**kw);self.assertEqual(r.returncode,71);self.assertIn('FAULT_GPIO_MAPPING',r.stdout);self.assertNotIn('WRITE',r.stdout);self.assertNotIn('UDC_PUBLICATION',r.stdout)
 def test_read_export_direction_and_readback_fail_specific_no_publication(self):
  for failure,phase in [('read','GPIO_MAPPING'),('export','GPIO_EXPORT'),('direction','GPIO_DIRECTION_LOW'),('readback','GPIO_READBACK'),('oem','GPIO_PREPUBLICATION_OWNER')]:
   with self.subTest(failure=failure):
    r,a=self.run_case(adopt=False,failure=failure);self.assertEqual(r.returncode,71);self.assertIn('FAULT_'+phase,r.stdout);self.assertNotIn('UDC_PUBLICATION',r.stdout)
 def test_already_accessory_adoption_does_not_touch_gpio_even_wrong_map(self):
  r,a=self.run_case(adopt=True,board='other');self.assertEqual(r.returncode,0,r.stderr);self.assertIn('ADOPT_EXISTING_ACCESSORY',r.stdout);self.assertIn('ACCESSORY_CONNECTED',r.stdout);self.assertNotIn('GPIO',r.stdout);self.assertNotIn('WRITE',r.stdout);self.assertNotIn('UDC_PUBLICATION',r.stdout);self.assertEqual(a,{})
 def test_unexpected_prior_readback_refuses(self):
  r,a=self.run_case(exported=True,direction='unknown',adopt=False);self.assertEqual(r.returncode,71);self.assertIn('FAULT_GPIO_READBACK',r.stdout);self.assertNotIn('WRITE',r.stdout)
if __name__=='__main__':unittest.main()
