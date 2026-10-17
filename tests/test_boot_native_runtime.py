import importlib.util,json,pathlib,re,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
SCRIPT=ROOT/'tools/boot_native/runtime/runtime-controller.sh'
TEXT=SCRIPT.read_text()
def function(name,text=TEXT):
    m=re.search(r'^'+name+r'\(\) \{[^\n]*\}\n',text,re.M) or re.search(r'^'+name+r'\(\) \{.*?^\}',text,re.M|re.S)
    if not m:raise AssertionError(name)
    return m.group()
def shell(names,body,setup=''):
    return subprocess.run(['sh','-c','set -u\n'+'\n'.join(function(n)for n in names)+'\n'+setup+'\n'+body],capture_output=True,text=True,timeout=5)
class ScalarReadTests(unittest.TestCase):
    NODE='/sys/class/udc/a600000.dwc3/state'
    def run_read(self,content,allowed=True,as_directory=False,setup=''):
        with tempfile.TemporaryDirectory()as tmp:
            f=pathlib.Path(tmp)/'scalar'; calls=pathlib.Path(tmp)/'calls'
            if as_directory:f.mkdir()
            elif content is not None:f.write_bytes(content)
            # Replace only this exact whitelist path with a private regular fixture.
            # No tests open actual /proc, sysfs, configfs or device nodes.
            text=function('readf').replace(self.NODE,str(f)if allowed else str(f)+'-not-selected')
            program='set -u\n'+text+f'''\nb() {{ printf '%s\\n' "$*" >> '{calls}'; command "$@"; }}
'''+setup+f"\nreadf '{f}'\n"
            p=subprocess.run(['sh','-c',program],capture_output=True,timeout=5)
            return p,calls.read_text().splitlines()if calls.exists()else[]
    def test_exact_scalar_lf_is_byte_preserved_without_subprocess(self):
        for value in [b'configured\n',b'not attached\n',b'0x18d1\n',b'\n',b'  a\\b\t \n']:
            with self.subTest(value=value):
                p,calls=self.run_read(value)
                self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,value);self.assertEqual(calls,[])
    def test_empty_or_unterminated_scalar_keeps_bounded_cat_semantics(self):
        for value in [b'',b'configured',b'  a\\b\t ']:
            with self.subTest(value=value):
                p,calls=self.run_read(value)
                self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,value);self.assertEqual(len(calls),1)
    def test_multiline_or_unterminated_tail_is_never_silently_truncated(self):
        for value in [b'configured\nforeign\n',b'configured\nforeign',b'configured\n\n']:
            with self.subTest(value=value):
                p,calls=self.run_read(value)
                self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,value);self.assertEqual(len(calls),1)
    def test_missing_or_unreadable_scalar_does_not_become_empty_success(self):
        for directory in [False,True]:
            with self.subTest(directory=directory):
                p,calls=self.run_read(None,as_directory=directory)
                self.assertNotEqual(p.returncode,0);self.assertEqual(p.stdout,b'');self.assertEqual(len(calls),1)
    def test_first_read_error_uses_old_error_result(self):
        p,calls=self.run_read(b'configured\n',setup='read() { return 1; }; b() { return 73; }')
        self.assertEqual(p.returncode,73);self.assertEqual(p.stdout,b'')
    def test_generic_paths_always_use_original_bounded_reader(self):
        p,calls=self.run_read(b'configured\n',allowed=False)
        self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,b'configured\n');self.assertEqual(len(calls),1)
    def test_process_identity_and_runtime_markers_are_not_whitelisted(self):
        reader=function('readf')
        for path in ['/proc/$1/stat','/proc/$1/cgroup','/proc/$1/cmdline','$R/md.identity','$R/observer.nonce','$R/aoa-start','$R/md.boot-state']:
            self.assertNotIn(path,reader)
        self.assertNotIn('"$G/UDC"',reader)
        self.assertIn('b cat "$1"',reader)
class ReadyTests(unittest.TestCase):
    def check_marker(self,change='',identity=True):
        with tempfile.TemporaryDirectory()as tmp:
            path=pathlib.Path(tmp)/'md.boot-state'
            data='contract=BOOT_NATIVE_MD_V1\nboot_id=boot\nnonce=nonce\npid=42\nstart=567\ncommand_ready=true\nmedia_ready=true\nerror_present=false\n'
            if change.startswith('+'):data+=change[1:]
            elif change:
                key,value=change.split('=',1);data=re.sub('^'+key+'=.*$',key+'='+value,data,flags=re.M)
            path.write_text(data)
            return shell(['read_md_ready'],'read_md_ready',f'R={tmp}; BOOT=boot; MD_NONCE=nonce\nidentity() {{ ID_PID=42; ID_START=567; return {0 if identity else 1}; }}\nowned_file() {{ return 0; }}')
    def test_only_actual_both_ready(self):self.assertEqual(self.check_marker().returncode,0)
    def test_stale_nonce_pid_boot_and_false_media_refused(self):
        for value in ['nonce=old','pid=41','start=566','boot_id=old','media_ready=false','command_ready=false','error_present=true']:
            with self.subTest(value=value):self.assertNotEqual(self.check_marker(value).returncode,0)
    def test_duplicate_key_and_dead_identity_refused(self):
        self.assertNotEqual(self.check_marker('+media_ready=true\n').returncode,0)
        self.assertNotEqual(self.check_marker(identity=False).returncode,0)
    def test_vendor_ack_requires_both_complete_events_and_same_controller(self):
        for generic,ack,sys,expect in [('1','a600000.dwc3','a600000.dwc3',0),('0','a600000.dwc3','a600000.dwc3',1),('1','','a600000.dwc3',1),('1','other','a600000.dwc3',1),('1','a600000.dwc3','other',1)]:
            p=shell(['vendor_usb_ready'],'vendor_usb_ready',f'''prop() {{ case "$1" in sys.carlife.generic_usb_ready) printf '%s' '{generic}';; vendor.usb.controller) printf a600000.dwc3;; sys.usb.controller) printf '%s' '{sys}';; sys.carlife.usb_controller_ready) printf '%s' '{ack}';; esac; }}''')
            self.assertEqual(p.returncode,expect,p.stderr)
    def test_next_boot_selection_is_not_runtime_stop(self):
        with tempfile.TemporaryDirectory()as tmp:
            pathlib.Path(tmp,'selected').write_text('android_auto\n')
            p=shell(['stop_requested'],'stop_requested',f'R={tmp}; P={tmp}; STOP=0; RUN_ID=a; BOOT=b')
            self.assertEqual(p.returncode,1)
    def test_detach_requires_full_second_and_suspended_not_detach(self):
        p=shell(['number','observe_udc'],'''observe_udc configured 90
observe_udc 'not attached' 99; [ "$DETACH_CONFIRMED" = 0 ] || exit 1
observe_udc 'not attached' 100; [ "$DETACH_CONFIRMED" = 0 ] || exit 2
observe_udc 'not attached' 199; [ "$DETACH_CONFIRMED" = 1 ] || exit 3
observe_udc suspended 200; [ "$DETACH_CONFIRMED" = 0 ]''','DETACH_ARMED=0; DETACH_SINCE=0')
        self.assertEqual(p.returncode,0,p.stderr)
class RecoveryTests(unittest.TestCase):
    def test_proven_exit_rearms_on_first_observation_without_detach(self):
        # No tick or elapsed interval is needed after irreversible child exit.
        p=shell(['recovery_reason'],'''recovery_reason || exit 81
[ "$RECOVERY_REASON" = MD_EXIT ]''',
            'PHASE=ACCESSORY_CONNECTED; DETACH_CONFIRMED=0; MD_EXIT_SINCE=0; identity() { return 2; }; child_exited() { return 0; }')
        self.assertEqual(p.returncode,0,p.stderr)
    def test_confirmed_detach_keeps_priority_without_md_exit(self):
        p=shell(['recovery_reason'],'''recovery_reason || exit 81
[ "$RECOVERY_REASON" = DETACH ]''',
            'PHASE=ACCESSORY_CONNECTED; DETACH_CONFIRMED=1; identity() { echo UNEXPECTED_IDENTITY; return 1; }; child_exited() { echo UNEXPECTED_EXIT; return 1; }')
        self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_live_md_does_not_rearm_or_check_exit(self):
        p=shell(['recovery_reason'],'''recovery_reason || exit 81
[ "$RECOVERY_REASON" = NONE ] && [ "$MD_EXIT_SINCE" = 0 ]''',
            'PHASE=ACCESSORY_CONNECTED; DETACH_CONFIRMED=0; MD_EXIT_SINCE=500; identity() { return 0; }; child_exited() { echo UNEXPECTED_EXIT; return 1; }')
        self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_identity_failure_without_proven_exit_never_rearms(self):
        for identity_code in [1,2,73]:
            with self.subTest(identity_code=identity_code):
                p=shell(['recovery_reason'],'''recovery_reason; rc=$?
[ "$rc" != 0 ] && [ "$RECOVERY_REASON" = NONE ]''',
                    f'PHASE=ACCESSORY_CONNECTED; DETACH_CONFIRMED=0; MD_EXIT_SINCE=0; identity() {{ return {identity_code}; }}; child_exited() {{ return 1; }}')
                self.assertEqual(p.returncode,0,p.stderr)
    def test_discovery_phase_does_not_use_md_exit_recovery(self):
        p=shell(['recovery_reason'],'''recovery_reason || exit 81
[ "$RECOVERY_REASON" = NONE ]''',
            'PHASE=USB_DISCOVERY; DETACH_CONFIRMED=0; identity() { echo UNEXPECTED_IDENTITY; return 1; }; child_exited() { echo UNEXPECTED_EXIT; return 0; }')
        self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_missing_process_proven_exit_is_also_immediate(self):
        p=shell(['recovery_reason'],'''recovery_reason || exit 81
[ "$RECOVERY_REASON" = MD_EXIT ]''',
            'PHASE=ACCESSORY_CONNECTED; DETACH_CONFIRMED=0; MD_EXIT_SINCE=0; identity() { return 1; }; child_exited() { return 0; }')
        self.assertEqual(p.returncode,0,p.stderr)
class TerminalSessionRecoveryTests(unittest.TestCase):
    SETUP='''
G=/nonexistent/mock-gadget; R=/nonexistent/mock-runtime
PHASE=ACCESSORY_CONNECTED; GENERATION=4; ATTACH_FAILURES=0
DETACH_ARMED=1; DETACH_SINCE=8; MD_EXIT_SINCE=12; STOP=0
child_exited() { ce_pid=$MD; echo exited-$1; }
stop_requested() { [ "$STOP" = 1 ]; }
same_boot() { echo same-boot; }; oem_absent() { echo no-oem; }
physical_profile() { echo profile-$1; }; stop_child() { echo stop-$1; }
phase() { PHASE=$1; echo phase-$1; }
write_node() { echo write-${1##*/}-$2; }; read_is() { echo read-${1##*/}-$2; }
start_md() { echo start-md; }; start_observer() { echo start-observer; }
wait_md_ready() { echo ready-md; }
configure_discovery() { echo publish-mtp; PHASE=USB_DISCOVERY; }
'''
    def run_rearm(self,body='',code=0,setup=''):
        return shell(['rearm_discovery'],f"sh -c 'exit {code}' & MD=$!\n"+body,self.SETUP+setup)
    def test_successful_exact_child_retirement_cleans_before_unbind_and_mtp(self):
        p=self.run_rearm('''rearm_discovery MD_EXIT || exit 81
[ "$PHASE" = USB_DISCOVERY ] && [ "$GENERATION" = 5 ] && [ "$ATTACH_FAILURES" = 1 ] &&
[ "$DETACH_ARMED" = 0 ] && [ "$DETACH_SINCE" = 0 ] && [ "$MD_EXIT_SINCE" = 0 ]''')
        self.assertEqual(p.returncode,0,p.stderr)
        lines=p.stdout.splitlines()
        self.assertLess(lines.index('exited-md'),lines.index('stop-md'))
        self.assertLess(lines.index('stop-md'),lines.index('stop-observer'))
        self.assertLess(lines.index('stop-observer'),lines.index('write-UDC-'))
        self.assertEqual(lines.count('profile-accessory'),2)
        self.assertEqual(lines[-4:],['start-md','start-observer','ready-md','publish-mtp'])
        self.assertNotIn('write-UDC-a600000.dwc3',lines)
    def test_nonzero_child_exit_before_negotiation_never_reopens_or_rebinds(self):
        p=self.run_rearm('''rearm_discovery MD_EXIT; rc=$?
[ "$rc" != 0 ] && [ "$RECOVERY_FAILURE" = MD_EXIT_STATUS ] && [ "$GENERATION" = 4 ]''',code=71)
        self.assertEqual(p.returncode,0,p.stderr)
        self.assertEqual(p.stdout.splitlines(),['exited-md'])
    def test_immediate_reason_still_requires_successful_exact_child_exit(self):
        for code in [0,71]:
            with self.subTest(code=code):
                p=shell(['recovery_reason','rearm_discovery'],f'''sh -c 'exit {code}' & MD=$!
recovery_reason || exit 81
[ "$RECOVERY_REASON" = MD_EXIT ] || exit 82
rearm_discovery "$RECOVERY_REASON"
''',self.SETUP+'\nDETACH_CONFIRMED=0; identity() { return 2; }')
                if code==0:
                    self.assertEqual(p.returncode,0,p.stderr)
                    self.assertIn('publish-mtp',p.stdout)
                    self.assertLess(p.stdout.index('stop-observer'),p.stdout.index('write-UDC-'))
                else:
                    self.assertNotEqual(p.returncode,0)
                    self.assertNotIn('write-',p.stdout);self.assertNotIn('start-',p.stdout)
    def test_live_child_or_foreign_saved_pid_is_not_waited_or_rearmed(self):
        for stub in ['child_exited() { return 1; }',
                     'child_exited() { ce_pid=999999; return 0; }']:
            with self.subTest(stub=stub):
                p=self.run_rearm('''rearm_discovery MD_EXIT; rc=$?
wait "$MD"
[ "$rc" != 0 ] && [ "$RECOVERY_FAILURE" = MD_EXIT_STATUS ]''',setup=stub)
                self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_owner_drift_and_failed_cleanup_never_write_usb(self):
        for stub in ['same_boot() { return 1; }', 'oem_absent() { return 1; }',
                     'physical_profile() { return 1; }',
                     'stop_child() { echo stop-$1; [ "$1" != md ]; }',
                     'stop_child() { echo stop-$1; [ "$1" != observer ]; }']:
            with self.subTest(stub=stub):
                p=self.run_rearm('rearm_discovery MD_EXIT',setup=stub)
                self.assertNotEqual(p.returncode,0,p.stderr)
                self.assertNotIn('write-',p.stdout);self.assertNotIn('start-',p.stdout)
    def test_owner_is_rechecked_after_old_children_are_gone(self):
        p=self.run_rearm('rearm_discovery MD_EXIT',setup='''
owner_calls=0
physical_profile() { owner_calls=$((owner_calls+1)); [ "$owner_calls" = 1 ]; }
''')
        self.assertNotEqual(p.returncode,0,p.stderr)
        self.assertIn('stop-observer',p.stdout);self.assertNotIn('write-',p.stdout)
    def test_stop_arriving_during_cleanup_does_not_unbind_or_restart(self):
        p=self.run_rearm('rearm_discovery MD_EXIT',setup='''
stop_child() { echo stop-$1; [ "$1" != observer ] || STOP=1; }
''')
        self.assertNotEqual(p.returncode,0,p.stderr)
        self.assertNotIn('write-',p.stdout);self.assertNotIn('start-',p.stdout)
    def test_failed_unbind_or_new_readiness_never_publishes_mtp(self):
        for stub in ['write_node() { echo failed-unbind; return 1; }',
                     'read_is() { return 1; }',
                     'wait_md_ready() { echo failed-readiness; return 1; }']:
            with self.subTest(stub=stub):
                p=self.run_rearm('rearm_discovery MD_EXIT',setup=stub)
                self.assertNotEqual(p.returncode,0,p.stderr);self.assertNotIn('publish-mtp',p.stdout)
    def test_pause_and_reset_on_live_md_do_not_request_recovery(self):
        p=shell(['recovery_reason'],'''for protocol_event in PAUSE RESET VERSION; do
tick=1000; recovery_reason || exit 81
[ "$RECOVERY_REASON" = NONE ] && [ "$MD_EXIT_SINCE" = 0 ] || exit 82
done''','''PHASE=ACCESSORY_CONNECTED; DETACH_CONFIRMED=0; MD_EXIT_SINCE=500
identity() { return 0; }; child_exited() { echo UNEXPECTED_EXIT; return 1; }''')
        self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_discovery_phase_cannot_use_terminal_rearm(self):
        p=self.run_rearm('''PHASE=USB_DISCOVERY
rearm_discovery MD_EXIT; rc=$?; wait "$MD"
[ "$rc" != 0 ] && [ "$RECOVERY_FAILURE" = MD_EXIT_STATUS ]''')
        self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_physical_detach_does_not_require_a_successful_md_exit(self):
        p=self.run_rearm('''rearm_discovery DETACH; rc=$?; wait "$MD"
[ "$rc" = 0 ] && [ "$ATTACH_FAILURES" = 0 ] && [ "$PHASE" = USB_DISCOVERY ]''',code=71)
        self.assertEqual(p.returncode,0,p.stderr);self.assertNotIn('exited-md',p.stdout)
class PublicationTests(unittest.TestCase):
    def test_aoa_keeps_existing_md_owner_and_order(self):
        p=shell(['enter_accessory'],'enter_accessory',r'''
G=/tmp/test-only-gadget; R=/tmp/not-used; RUN_ID=run; BOOT=boot; ACC_FUNCTION=accessory.gs2
kernel_start_ok() { echo kernel; }; observer_completed() { echo drained; }
physical_profile() { echo profile-$1; }; oem_absent() { echo no-oem; }; identity() { echo identity-$1; }
stop_child() { echo stop-$1; [ "$1" = observer ]; }; atomic() { echo intent; }; log() { echo log; }
write_node() { echo write-${1##*/}-$2; }; read_is() { :; }; accessory_unchanged() { :; }; clear_known_links() { echo clear-links; }
b() { [ "$1" = ln ] || exit 91; echo link-$3; }; phase() { echo phase-$1; }
bootstrap_qcom_main_once() { :; }; sample_now_ms() { SAMPLED_NOW_MS=10000; }
''')
        self.assertEqual(p.returncode,0,p.stderr)
        lines=p.stdout.splitlines();self.assertNotIn('stop-md',lines)
        self.assertLess(lines.index('drained'),lines.index('kernel'))
        self.assertLess(lines.index('identity-md'),lines.index('write-UDC-'))
        self.assertLess(lines.index('write-UDC-'),lines.index('write-UDC-a600000.dwc3'))
        self.assertEqual(lines[-1],'phase-ACCESSORY_CONNECTED')
    def test_no_aoa_write_when_authenticated_kernel_marker_rejected(self):
        p=shell(['enter_accessory'],'enter_accessory','observer_completed() { return 0; }; kernel_start_ok() { return 1; }; write_node() { echo UNEXPECTED_WRITE; }')
        self.assertNotEqual(p.returncode,0);self.assertNotIn('UNEXPECTED_WRITE',p.stdout)
    def test_no_publication_without_media_readiness(self):
        p=shell(['configure_discovery'],'configure_discovery','G=/tmp/fake; same_boot() { :; }; oem_absent() { :; }; accessory_unchanged() { :; }; read_is() { :; }; read_md_ready() { return 1; }; write_node() { echo UNEXPECTED_WRITE; }')
        self.assertNotEqual(p.returncode,0);self.assertNotIn('UNEXPECTED_WRITE',p.stdout)
    def test_existing_function_only_and_never_delete_instances(self):
        for suffix,expected in [(['accessory.gs2'],'accessory.gs2'),(['accessory.gs2','accessory.usb0'],'accessory.usb0'),(['accessory.gs2','accessory.foreign'],None)]:
            with tempfile.TemporaryDirectory()as tmp:
                funcs=pathlib.Path(tmp,'functions');funcs.mkdir()
                for name in suffix:(funcs/name).mkdir()
                # macOS stat differs, only mock stat result; enumerate real temp tree.
                setup=f'''G={tmp}; b() {{ if [ "$1" = stat ]; then printf 0:0:755:123; else command "$@"; fi; }}; atomic() {{ printf '%s' "$2"; }}'''
                p=shell(['choose_accessory'],'choose_accessory',setup)
                if expected:self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,expected)
                else:self.assertNotEqual(p.returncode,0)
                self.assertEqual(sorted(p.name for p in funcs.iterdir()),sorted(suffix))
    def test_new_accessory_instance_after_md_selection_refused(self):
        with tempfile.TemporaryDirectory()as tmp:
            funcs=pathlib.Path(tmp,'functions');funcs.mkdir();(funcs/'accessory.gs2').mkdir()
            setup=f'''G={tmp}; b() {{ if [ "$1" = stat ]; then printf 0:0:755:123; else command "$@"; fi; }}; atomic() {{ :; }}'''
            p=shell(['choose_accessory','accessory_unchanged'],'choose_accessory && accessory_unchanged && mkdir "$G/functions/accessory.usb0" && accessory_unchanged',setup)
            self.assertNotEqual(p.returncode,0)
    def test_source_does_not_contain_oem_return_or_init_auto_mtp_trigger(self):
        self.assertNotRegex(TEXT,r'\b(start|stop) sd-reverse\b')
        self.assertNotIn('setprop sys.usb.config autoMtp',TEXT)
        self.assertNotIn('sys.boot_completed', '\n'.join(l for l in TEXT.splitlines()if not l.lstrip().startswith('#')))
class VerifiedStartDetachTests(unittest.TestCase):
    SETUP='''
G=/nonexistent/mock-gadget; R=/nonexistent/mock-runtime; RUN_ID=run; BOOT=boot; ACC_FUNCTION=accessory.gs2
DETACH_ARMED=0; DETACH_SINCE=0; MD_EXIT_SINCE=0; PHASE=USB_DISCOVERY
observer_completed() { return 0; }; kernel_start_ok() { return 0; }
physical_profile() { return 0; }; oem_absent() { return 0; }; identity() { return 0; }
stop_child() { [ "$1" = observer ]; }; atomic() { return 0; }; log() { return 0; }
write_node() { return 0; }; read_is() { return 0; }; accessory_unchanged() { return 0; }; clear_known_links() { return 0; }
b() { [ "$1" = ln ]; }; phase() { PHASE=$1; }
bootstrap_qcom_main_once() { :; }; sample_now_ms() { SAMPLED_NOW_MS=10000; }
'''
    def run_state(self,body):
        return shell(['number','observe_udc','recovery_reason','enter_accessory'],body,self.SETUP)
    def test_verified_start_then_disconnect_between_polls_rearms(self):
        p=self.run_state('''enter_accessory || exit 81
# No configured/powered poll occurred, but START was authenticated.
observe_udc 'not attached' 1000
observe_udc 'not attached' 1100
tick=1100; recovery_reason
[ "$DETACH_ARMED" = 1 ] && [ "$RECOVERY_REASON" = DETACH ]''')
        self.assertEqual(p.returncode,0,p.stderr)
    def test_verified_start_still_requires_full_second(self):
        p=self.run_state('''enter_accessory || exit 81
observe_udc 'not attached' 1000
observe_udc 'not attached' 1099
tick=1099; recovery_reason
[ "$RECOVERY_REASON" = NONE ]''')
        self.assertEqual(p.returncode,0,p.stderr)
    def test_suspended_or_reconnected_state_breaks_absence_interval(self):
        for state in ['suspended','configured']:
            with self.subTest(state=state):
                p=self.run_state(f'''enter_accessory || exit 81
observe_udc 'not attached' 1000
observe_udc {state} 1099
observe_udc 'not attached' 1100
tick=1100; recovery_reason
[ "$RECOVERY_REASON" = NONE ]''')
                self.assertEqual(p.returncode,0,p.stderr)
    def test_failed_start_or_failed_bind_does_not_create_connection_evidence(self):
        for failure in ['kernel_start_ok() { return 1; }',
                        'physical_profile() { [ "$1" != accessory ]; }']:
            with self.subTest(failure=failure):
                p=self.run_state(f'''{failure}
if enter_accessory; then exit 82; fi
[ "$DETACH_ARMED" = 0 ] && [ "$DETACH_SINCE" = 0 ]''')
                self.assertEqual(p.returncode,0,p.stderr)
    def test_new_genuine_start_resets_attempt_counter_even_after_many_sessions(self):
        p=self.run_state('''for previous in 1 2 3 20; do
ATTACH_FAILURES=$previous
enter_accessory || exit 81
[ "$ATTACH_FAILURES" = 0 ] || exit 82
done''')
        self.assertEqual(p.returncode,0,p.stderr)
    def test_rejected_start_does_not_reset_counter(self):
        p=self.run_state('''ATTACH_FAILURES=2
kernel_start_ok() { return 1; }
if enter_accessory; then exit 81; fi
[ "$ATTACH_FAILURES" = 2 ]''')
        self.assertEqual(p.returncode,0,p.stderr)
class ObserverPublicationRaceTests(unittest.TestCase):
    def test_marker_written_between_absence_and_exit_check_is_consumed_once(self):
        with tempfile.TemporaryDirectory()as tmp:
            setup=f'''R={tmp}; calls=0
identity() {{ [ "$1" = observer ] || exit 81; printf 'published\n' > "$R/aoa-start"; return 1; }}
child_exited() {{ [ "$1" = observer ] && [ -f "$R/aoa-start" ]; }}
enter_accessory() {{ calls=$((calls+1)); [ "$(cat "$R/aoa-start")" = published ]; }}'''
            p=shell(['advance_discovery'],'advance_discovery && [ "$calls" = 1 ]',setup)
            self.assertEqual(p.returncode,0,p.stderr)
            # Exact prior branch falsely fails with the same deterministic ordering.
            pathlib.Path(tmp,'aoa-start').unlink()
            old='''if [ -e "$R/aoa-start" ]; then enter_accessory; elif ! identity observer; then child_exited observer || exit 82; exit 83; fi'''
            p=shell([],old,setup)
            self.assertEqual(p.returncode,83,p.stderr)
    def test_live_observer_without_marker_keeps_waiting(self):
        with tempfile.TemporaryDirectory()as tmp:
            p=shell(['advance_discovery'],'advance_discovery',f'''R={tmp}
identity() {{ return 0; }}; child_exited() {{ echo UNEXPECTED_EXIT_CHECK; return 1; }}; enter_accessory() {{ echo UNEXPECTED_TRANSITION; }}''')
            self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_proven_exit_without_marker_still_fails(self):
        with tempfile.TemporaryDirectory()as tmp:
            p=shell(['advance_discovery'],'advance_discovery; rc=$?; [ "$rc" = 1 ] && [ "$DISCOVERY_FAILURE" = OBSERVER_EXIT ]',f'''R={tmp}
identity() {{ return 1; }}; child_exited() {{ return 0; }}; enter_accessory() {{ echo UNEXPECTED_TRANSITION; }}''')
            self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_read_error_with_new_marker_is_not_proven_exit(self):
        with tempfile.TemporaryDirectory()as tmp:
            p=shell(['advance_discovery'],'advance_discovery; rc=$?; [ "$rc" = 1 ] && [ "$DISCOVERY_FAILURE" = OBSERVER_IDENTITY ]',f'''R={tmp}
identity() {{ printf 'published\n' > "$R/aoa-start"; return 1; }}; child_exited() {{ return 1; }}; enter_accessory() {{ echo UNEXPECTED_TRANSITION; }}''')
            self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout,'')
    def test_visible_but_incomplete_marker_is_read_only_after_completion(self):
        with tempfile.TemporaryDirectory()as tmp:
            pathlib.Path(tmp,'aoa-start').write_text('')
            setup=f'''R={tmp}; G=/tmp/fake; RUN_ID=run; BOOT=boot; ACC_FUNCTION=accessory.gs2
observer_completed() {{ printf 'complete\n' > "$R/aoa-start"; }}
kernel_start_ok() {{ [ "$(cat "$R/aoa-start")" = complete ]; }}
physical_profile() {{ :; }}; oem_absent() {{ :; }}; identity() {{ :; }}; stop_child() {{ :; }}; atomic() {{ :; }}; log() {{ :; }}
write_node() {{ echo WRITE; }}; read_is() {{ :; }}; accessory_unchanged() {{ :; }}; clear_known_links() {{ :; }}; b() {{ :; }}; phase() {{ :; }}
bootstrap_qcom_main_once() {{ :; }}; sample_now_ms() {{ SAMPLED_NOW_MS=10000; }}'''
            p=shell(['advance_discovery','enter_accessory'],'advance_discovery',setup)
            self.assertEqual(p.returncode,0,p.stderr);self.assertEqual(p.stdout.splitlines(),['WRITE']*3)
    def test_complete_but_wrong_nonce_marker_cannot_publish_usb(self):
        with tempfile.TemporaryDirectory()as tmp:
            pathlib.Path(tmp,'aoa-start').write_text('foreign 123 5 0 0 /devices/virtual/misc/mtp_usb\n')
            pathlib.Path(tmp,'observer.nonce').write_text('owned\n')
            setup=f'''R={tmp}; OBS_NONCE=owned
observer_completed() {{ :; }}; owned_file() {{ :; }}; read_is() {{ [ "$(cat "$1")" = "$2" ]; }}; write_node() {{ echo UNEXPECTED_WRITE; }}'''
            p=shell(['number','kernel_start_ok','enter_accessory','advance_discovery'],'advance_discovery; rc=$?; [ "$rc" = 1 ] && [ "$DISCOVERY_FAILURE" = AOA_TRANSITION ]',setup)
            self.assertEqual(p.returncode,0,p.stderr);self.assertNotIn('UNEXPECTED_WRITE',p.stdout)
    def test_completion_handles_exit_during_identity_and_checks_actual_exit_code(self):
        for code,expected in [(0,0),(79,1)]:
            with self.subTest(code=code):
                p=shell(['observer_completed'],'observer_completed',f'''sh -c 'exit {code}' & OBS=$!
identity() {{ return 1; }}; child_exited() {{ return 0; }}
bootstrap_qcom_main_if_due() {{ :; }}''')
                self.assertEqual(p.returncode,expected,p.stderr)
    def test_completion_does_not_wait_on_unproven_live_or_foreign_identity(self):
        p=shell(['observer_completed'],'observer_completed','''OBS=123
bootstrap_qcom_main_if_due() { :; }
identity() { return 1; }; child_exited() { return 1; }; wait() { echo UNEXPECTED_WAIT; }; sleep() { echo UNEXPECTED_SLEEP; }''')
        self.assertNotEqual(p.returncode,0);self.assertEqual(p.stdout,'')
class ResolverRecordTests(unittest.TestCase):
    resolver=(ROOT/'tools/boot_native/runtime/runtime-control.sh').read_text()
    def run_guard(self,names,body,setup):
        code='set -u\n'+'\n'.join(function(n,self.resolver)for n in names)+'\n'+setup+'\n'+body
        return subprocess.run(['sh','-c',code],capture_output=True,text=True,timeout=5)
    def test_exact_lf_rejects_nul_missing_extra_lf_and_read_error(self):
        cases=[(b'carlife\n',True),(b'carlife\0',False),(b'carlife',False),(b'carlife\n\n',False),(b'car\0life\n',False),(b'carlife\r\n',False)]
        with tempfile.TemporaryDirectory()as tmp:
            path=pathlib.Path(tmp,'record')
            for data,expected in cases:
                with self.subTest(data=data):
                    path.write_bytes(data)
                    # Preserve actual bytes; only adapt stat syntax for the Mac host.
                    setup='''b() { if [ "$1" = stat ]; then shift 3; wc -c < "$1" | tr -d ' '; else command "$@"; fi; }'''
                    p=self.run_guard(['exact_lf'],f'exact_lf "{path}" carlife',setup)
                    self.assertEqual(p.returncode==0,expected,p.stderr)
            p=self.run_guard(['exact_lf'],f'exact_lf "{path}" carlife','b() { return 1; }')
            self.assertNotEqual(p.returncode,0)
    def test_selector_lock_and_invalid_schema_cannot_admit(self):
        with tempfile.TemporaryDirectory()as tmp:
            root=pathlib.Path(tmp);(root/'schema').write_bytes(b'1\n');(root/'selected').write_bytes(b'carlife\n')
            setup=f'S={tmp}; dir() {{ :; }}; file() {{ :; }}; '+'''b() { if [ "$1" = stat ]; then shift 3; wc -c < "$1" | tr -d ' '; else command "$@"; fi; }'''
            call=lambda:self.run_guard(['exact_lf','selection_ok'],'selection_ok',setup)
            self.assertEqual(call().returncode,0)
            (root/'.selector-lock').mkdir();self.assertNotEqual(call().returncode,0);self.assertTrue((root/'.selector-lock').is_dir());(root/'.selector-lock').rmdir()
            (root/'.selector-lock').symlink_to(root/'absent');self.assertNotEqual(call().returncode,0);(root/'.selector-lock').unlink()
            (root/'schema').write_bytes(b'1\0');self.assertNotEqual(call().returncode,0)
    def test_disable_requires_zero_bytes_not_nul_or_lf(self):
        with tempfile.TemporaryDirectory()as tmp:
            p=pathlib.Path(tmp,'disable')
            for raw,expected in [(b'',True),(b'\0',False),(b'\n',False)]:
                p.write_bytes(raw)
                result=self.run_guard(['empty_file'],f'empty_file "{p}"','''b() { shift 3; wc -c < "$1" | tr -d ' '; }''')
                self.assertEqual(result.returncode==0,expected,result.stderr)
class PackageTests(unittest.TestCase):
    def test_deterministic_data_only_disabled_selection_not_in_bundle(self):
        spec=importlib.util.spec_from_file_location('bootpkg',ROOT/'tools/boot_native/build_package.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
        with tempfile.TemporaryDirectory()as tmp:
            # Package contract tests use inert local payloads, not prior device build output.
            fixture=pathlib.Path(tmp,'inputs')
            for name, data in {
                'runtime/runtime-controller.sh': SCRIPT.read_bytes(),
                'runtime/runtime-control.sh': (SCRIPT.parent/'runtime-control.sh').read_bytes(),
                'observer/build/uevent-watch': b'synthetic-observer-not-executable',
                'md/build/md-boot-native.jar': b'synthetic-md-not-executable',
            }.items():
                target=fixture/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
            m.HERE=fixture
            a=m.build(pathlib.Path(tmp,'a'));b=m.build(pathlib.Path(tmp,'b'))
            self.assertEqual(a['archive_sha256'],b['archive_sha256'])
            self.assertTrue(all(p=='data'or p.startswith('data/')for p in a['members']))
            self.assertFalse(any(p.endswith('/selected')for p in a['members']))
            self.assertEqual(a['members']['data/versions/'+m.VERSION+'/md.jar']['mode'],0o400)
if __name__=='__main__':unittest.main()
