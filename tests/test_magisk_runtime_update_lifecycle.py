"""Execute the production shell against mapped Android paths; no ADB or device I/O.

Only filesystem roots, command PATH and the synthetic observer pin are mapped.
All manager guards and state-machine branches run unmodified. Android privileged
commands are logged and simulated; ordinary copies/renames use real temp files.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
VERSION = 'boot-native-v1-20261014'
BOOT1 = '11111111-1111-4111-8111-111111111111'
BOOT2 = '22222222-2222-4222-8222-222222222222'
BOOT3 = '33333333-3333-4333-8333-333333333333'
OBSERVER = b'OFFLINE observer fixture\n'
SHA = lambda data: hashlib.sha256(data).hexdigest()

STUB = r'''#!/usr/bin/python3
import hashlib,json,os,pathlib,shutil,subprocess,sys
R=pathlib.Path(os.environ['FIXTURE_ROOT']); p=R/'state.json'; s=json.loads(p.read_text())
name=pathlib.Path(sys.argv[0]).name; a=sys.argv[1:]
with (R/'commands.jsonl').open('a') as f:f.write(json.dumps([name]+a)+'\n')
def save():p.write_text(json.dumps(s))
def stop_owner():
 s['alive']=False
 if not s.get('stubborn'):
  for d in (R/'proc').glob('[0-9]*'):shutil.rmtree(d)
  for d in (R/'sys/fs/cgroup').glob('carlife-boot-native-*'):shutil.rmtree(d)
  lock=R/'data/local/carlife-native/runtime/owner.lock'
  if lock.exists():lock.rmdir()
 save()
if name=='timeout':
 while a and (a[0].startswith('-') or a[0].replace('.','',1).isdigit()):
  if a[0]=='-k':a=a[2:]
  else:a=a[1:]
 os.execvp(a[0],a)
elif name=='id':print('0' if a[0] in ('-u','-g') else 'uid=0(root) gid=0(root)')
elif name=='getprop':
 vals={'ro.build.version.sdk':str(s.get('sdk',35)),'ro.board.platform':s.get('platform','lito'),'init.svc.carlife-native':s.get('init','running')}
 if a[0]=='init.svc.carlife-native':
  remaining=s.get('scan_fault_remaining',0)
  u=R/'proc/uptime';u.chmod(0o600);u.write_text('bad\n' if remaining else '90.00 800.00\n');u.chmod(0o444)
  if remaining:s['scan_fault_remaining']=remaining-1;save()
 print(vals.get(a[0],''))
elif name=='stat':
 f=pathlib.Path(a[-1]); st=f.stat(); fmt=a[1]
 if fmt=='%u:%g:%a':print('0:0:'+oct(st.st_mode&0o777)[2:])
 elif fmt=='%s':print(st.st_size)
 elif fmt=='%d':print(st.st_dev)
 else:raise Exception(fmt)
elif name=='sha256sum':
 if not a:data=sys.stdin.buffer.read();print(hashlib.sha256(data).hexdigest()+'  -')
 else:
  for f in a:print(hashlib.sha256(pathlib.Path(f).read_bytes()).hexdigest()+'  '+f)
elif name=='readlink':print(str(pathlib.Path(a[-1]).resolve()))
elif name=='chown' or name=='sync' or name=='sleep' or name=='chcon':pass
elif name=='ls':print('u:object_r:shell_data_file:s0 '+a[-1])
elif name=='start':
 if s.get('start_fail'):sys.exit(1)
 s['init']='running';s['starts']=s.get('starts',0)+1;save()
elif name=='stop':
 if s.get('stop_fail'):sys.exit(1)
 s['init']='running' if s.get('init_stuck') else 'stopped';save()
elif name=='sh':
 if a[-1]=='status':
  if s.get('status_fail'):sys.exit(70)
  print('phase=ACCESSORY_CONNECTED\nverified_alive='+str(s.get('alive',True)).lower())
 elif a[-1]=='stop':
  if s.get('owner_stop_fail'):sys.exit(71)
  stop_owner();print('phase=STOPPED')
 else:sys.exit(64)
elif name=='mv':
 dest=a[-1]
 if s.get('fail_mv') and s['fail_mv'] in dest:
  if not s.get('fail_once_done'):s['fail_once_done']=True;save();sys.exit(1)
 shutil.move(a[-2],dest)
 if dest.endswith('/md.jar') and s.get('fault_after_md'):
  s['scan_fault_remaining']=s.pop('fault_after_md');save()
 if dest.endswith('/md.jar') and s.get('live_after_md'):
  f=R/'proc/1717/cmdline';f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(b'app_process\0/system/bin\0org.carlife.hulab.MdMain\0');s.pop('live_after_md');save()
 if s.get('tamper_after_mv') and s['tamper_after_mv'] in dest:
  f=R/'data/local/carlife-native/versions/boot-native-v1-20261014/md.jar'
  f.chmod(0o600);f.write_bytes(b'external modification');f.chmod(0o400);s.pop('tamper_after_mv');save()
else:raise Exception((name,a))
'''

class RuntimeUpdateLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.r = Path(self.temp.name).resolve()
        self.p = self.r/'data/local/carlife-native'
        self.v = self.p/'versions'/VERSION
        self.d = self.r/'data/adb/carlife-runtime-update'
        self.s = self.r/'data/adb/carlife-boot'
        self.m = self.r/'data/adb/modules_update/carlife_runtime_update'
        self.bin = self.r/'bin'; self.bin.mkdir()
        for name in ('timeout','id','getprop','stat','sha256sum','readlink','chown','sync','sleep','start','stop','mv','chcon','ls'):
            self.put(self.bin/name, STUB.encode(), 0o755)
        self.put(self.bin/'timeout',b'#!/bin/sh\nshift 2\nshift\nexec "$@"\n',0o755)
        # These high-frequency Unix operations need no Python interpreter.
        # Keep the real shell control flow; only Android metadata is simulated.
        fast = """#!/bin/sh
name=${0##*/}
record='["'"$name"'"'
for a in "$@"; do record=$record',"'$a'"'; done
printf '%s]\\n' "$record" >> "$FIXTURE_ROOT/commands.jsonl"
case "$name" in
 id) printf '0\\n';;
 chown|sync|sleep|chcon) exit 0;;
 readlink) /bin/realpath "$2";;
 ls) printf 'u:object_r:shell_data_file:s0 %s\\n' "$2";;
 sha256sum) exec /sbin/sha256sum "$@";;
 stat) case "$2" in
   %u:%g:%a) printf '0:0:%s\\n' "$(/usr/bin/stat -f %Lp "$3")";;
   %s) /usr/bin/stat -f %z "$3";;
   %d) /usr/bin/stat -f %d "$3";;
   *) exit 1;; esac;;
 *) exit 1;; esac
"""
        if sys.platform == 'darwin':
            for name in ('id','chown','sync','sleep','chcon','readlink','ls','sha256sum','stat'):
                self.put(self.bin/name,fast.encode(),0o755)
        self.put(self.r/'data/adb/magisk/busybox',b'#!/bin/sh\n[ "$1" = timeout ] || exit 64\nshift\nexec "$FIXTURE_ROOT/bin/timeout" "$@"\n',0o755)
        self.put(self.r/'system/bin/sh', STUB.encode(), 0o755)
        for d in (self.p,self.p/'versions',self.v,self.p/'logs',self.p/'runtime',self.s,self.m,self.m/'payload',self.r/'sys/fs/cgroup'):
            d.mkdir(parents=True,exist_ok=True);d.chmod(0o700)
        self.put(self.p/'owner', b'org.carlife.boot-native.v1\n',0o600)
        self.put(self.p/'current',(VERSION+'\n').encode(),0o600)
        self.put(self.p/'runtime-control.sh',(ROOT/'tools/boot_native/runtime/runtime-control.sh').read_bytes(),0o500)
        self.put(self.v/'md.jar',b'baseline MD',0o400)
        self.put(self.v/'runtime-controller.sh',b'baseline controller',0o500)
        self.put(self.v/'uevent-watch',OBSERVER,0o500)
        self.payload_index(self.v)
        self.baseline={n:(self.v/n).read_bytes() for n in ('md.jar','runtime-controller.sh','payload.sha256','uevent-watch')}
        self.put(self.s/'schema',b'1\n',0o600);self.put(self.s/'selected',b'carlife\n',0o600)
        self.put(self.r/'proc/sys/kernel/random/boot_id',(BOOT1+'\n').encode(),0o444)
        self.put(self.r/'proc/uptime',b'90.00 800.00\n',0o444)
        self.state(alive=True,init='running')
        self.package()

    def put(self,p,data,mode):
        p.parent.mkdir(parents=True,exist_ok=True)
        if p.exists():p.chmod(0o600)
        p.write_bytes(data);p.chmod(mode)

    def state(self, **updates):
        p=self.r/'state.json';s=json.loads(p.read_text()) if p.exists() else {}
        s.update(updates);p.write_text(json.dumps(s));return s

    def payload_index(self,d):
        data=''.join(SHA((d/n).read_bytes())+'  '+n+'\n' for n in ('md.jar','runtime-controller.sh','uevent-watch')).encode()
        self.put(d/'payload.sha256',data,0o400)

    def package(self,action='update',md=b'candidate MD',controller=b'candidate controller'):
        raw=(ROOT/'tools/magisk_runtime_update/manager.sh').read_text()
        raw=raw.replace('a109a2a1270a09e25c87199cb9fa027c90db61dc511d846cb2fc6b94806c814e',SHA(OBSERVER))
        raw=re.sub(r'(?<![A-Za-z0-9_])(/(?:data|proc|sys|system|vendor)(?:/[A-Za-z0-9_.:/-]+)?)',lambda m:str(self.r)+m.group(1),raw)
        raw=re.sub(r'^PATH=.*$',f'PATH={self.bin}:/usr/bin:/bin',raw,flags=re.M)
        self.put(self.m/'manager.sh',raw.encode(),0o500)
        for n in ('action.sh','service.sh'):
            self.put(self.m/n,b'fixture retained script\n',0o500)
        for n in ('INSTALL_EN.txt','INSTALL_RU.txt','LICENSE','customize.sh','module.prop','skip_mount'):
            self.put(self.m/n,b'fixture '+n.encode()+b'\n',0o600)
        self.put(self.m/'manifest.env',f'FORMAT=1\nMODULE_ID=carlife_runtime_update\nPACKAGE_ACTION={action}\nRUNTIME_VERSION={VERSION}\nPAYLOAD_ID=PLACEHOLDER\n'.encode(),0o600)
        self.put(self.m/'observer.sha256',(SHA(OBSERVER)+'\n').encode(),0o600)
        self.put(self.m/'allowed-resolvers.txt',b'b97b882340ec5e8f16f9e0cbd19aa18eb1cccc5e75bca1a278609d76f02d791a\n6add4df4d3aeb321a813e9d2262d755c7d3615f5ff0883bca08c3cb70fe36481\n',0o600)
        self.put(self.m/'payload/md.jar',md,0o600);self.put(self.m/'payload/runtime-controller.sh',controller,0o600)
        data=f'{SHA(md)}  md.jar\n{SHA(controller)}  runtime-controller.sh\n{SHA(OBSERVER)}  uevent-watch\n'.encode()
        self.put(self.m/'payload/payload.sha256',data,0o600)
        f=self.m/'manifest.env';f.write_text(f.read_text().replace('PLACEHOLDER',SHA(data)))
        self.reindex()

    def reindex(self):
        files={p.relative_to(self.m).as_posix():p.read_bytes() for p in self.m.rglob('*') if p.is_file() and p.name not in ('files.sha256','module.sha256')}
        def checks(values):return ''.join(SHA(data)+'  '+n+'\n' for n,data in sorted(values.items())).encode()
        retained={n:b for n,b in files.items() if n!='customize.sh'}
        self.put(self.m/'module.sha256',checks(retained),0o600)
        files['module.sha256']=(self.m/'module.sha256').read_bytes()
        self.put(self.m/'files.sha256',checks(files),0o600)

    def run_manager(self,action,ok=True):
        env=dict(os.environ,FIXTURE_ROOT=str(self.r))
        result=subprocess.run(['/bin/sh',str(self.m/'manager.sh'),action,str(self.m)],env=env,text=True,capture_output=True,timeout=120)
        if ok:self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        else:self.assertNotEqual(result.returncode,0,result.stdout+result.stderr)
        return result.stdout+result.stderr

    def commands(self):
        p=self.r/'commands.jsonl';return [json.loads(x) for x in p.read_text().splitlines()] if p.exists() else []

    def boot(self,value=BOOT2):
        self.put(self.r/'proc/sys/kernel/random/boot_id',(value+'\n').encode(),0o444)

    def stage(self):return self.run_manager('stage')

    def installed(self,which):
        expected=self.baseline if which=='baseline' else {n:(self.m/'payload'/n).read_bytes() for n in ('md.jar','runtime-controller.sh','payload.sha256')}
        for n,data in expected.items():self.assertEqual((self.v/n).read_bytes(),data,n)
        self.assertEqual((self.v/'uevent-watch').read_bytes(),OBSERVER)

    def test_stage_keeps_live_owner_then_next_boot_applies_and_noop(self):
        self.assertIn('RESULT=STAGED',self.stage());self.installed('baseline')
        self.assertFalse(any(c[0] in ('stop','start') or c[-1:] == ['stop'] for c in self.commands()))
        self.assertIn('WAITING_FOR_REBOOT',self.run_manager('apply'));self.installed('baseline')
        self.boot();self.assertIn('RESULT=APPLIED',self.run_manager('apply'));self.installed('candidate')
        self.assertEqual(self.state()['starts'],1)
        self.assertIn('ALREADY_APPLIED',self.run_manager('apply'));self.assertEqual(self.state()['starts'],1)

    def test_first_backup_preserved_through_update_update_rollback(self):
        self.stage();self.boot();self.run_manager('apply')
        first=(self.d/'backup/version/md.jar').read_bytes()
        self.package(md=b'candidate two');self.stage();self.boot(BOOT3);self.run_manager('apply')
        self.assertEqual((self.d/'backup/version/md.jar').read_bytes(),first)
        self.package(action='rollback');self.stage();self.boot(BOOT1);self.run_manager('apply');self.installed('baseline')
        self.assertEqual(len(list((self.d/'operations').iterdir())),3)

    def test_same_candidate_stage_noops_and_backup_not_overwritten(self):
        self.stage();self.boot();self.run_manager('apply')
        opcount=len(list((self.d/'operations').iterdir()))
        self.assertIn('ALREADY_INSTALLED',self.stage())
        self.assertEqual(len(list((self.d/'operations').iterdir())),opcount)
        self.assertEqual((self.d/'backup/version/md.jar').read_bytes(),b'baseline MD')

    def test_stage_duplicate_before_reboot_preserves_boot_token(self):
        self.stage();pending=(self.d/'pending').read_bytes();self.stage()
        self.assertEqual((self.d/'pending').read_bytes(),pending)

    def test_exact_real_timeout_sha_watchdog_is_not_a_controller(self):
        self.stage();self.boot();self.state(alive=False,init='stopped')
        args=['/data/adb/magisk/busybox','timeout','-k','1','5','/system/bin/sha256sum',str(self.v/'runtime-controller.sh')]
        self.put(self.r/'proc/10716/cmdline',b'\0'.join(x.encode() for x in args)+b'\0',0o444)
        self.run_manager('apply');self.installed('candidate')

    def test_file_readers_do_not_own_runtime(self):
        self.stage();self.boot();self.state(alive=False,init='stopped')
        commands=[['sha256sum',str(self.v/'md.jar')],['cp',str(self.v/'runtime-controller.sh'),'/some/destination'],
                  ['chcon','u:object_r:shell_data_file:s0',str(self.v/'uevent-watch')],
                  ['stat',str(self.p/'runtime-control.sh'),'run-boot'],
                  ['app_process','/system/bin','org.example.OtherMain','org.carlife.hulab.MdMain'],
                  ['sh','-c','echo org.carlife.hulab.MdMain']]
        for pid,args in enumerate(commands,1800):
            self.put(self.r/'proc'/str(pid)/'cmdline',b'\0'.join(x.encode() for x in args)+b'\0',0o444)
        self.run_manager('apply');self.installed('candidate')

    def test_actual_controller_observer_and_resolver_argv_are_refused(self):
        self.stage();self.boot();self.state(alive=False)
        commands=[(['sh',str(self.v/'runtime-controller.sh'),'run',str(self.p),str(self.v)],'LIVE_CONTROLLER'),
                  ([str(self.v/'uevent-watch'),'arguments'],'LIVE_OBSERVER'),
                  (['sh',str(self.p/'runtime-control.sh'),'run-boot'],'LIVE_RESOLVER')]
        for args,reason in commands:
            self.put(self.r/'proc/1717/cmdline',b'\0'.join(x.encode() for x in args)+b'\0',0o444)
            self.run_manager('apply',ok=False);self.installed('baseline')
            self.assertFalse(any(c[0]=='stop' for c in self.commands()))

    def test_timeout_wrapped_real_controller_is_still_an_owner(self):
        self.stage();self.boot();self.state(alive=False)
        args=['/data/adb/magisk/busybox','timeout','-k','1','5','/data/adb/magisk/busybox','sh',str(self.v/'runtime-controller.sh'),'run',str(self.p),str(self.v)]
        self.put(self.r/'proc/1717/cmdline',b'\0'.join(x.encode() for x in args)+b'\0',0o444)
        self.run_manager('apply',ok=False);self.installed('baseline')
        self.assertFalse(any(c[0]=='stop' for c in self.commands()))

    def test_transient_scan_error_after_first_rename_retries_without_bypass(self):
        self.stage();self.boot();self.state(fault_after_md=1)
        out=self.run_manager('apply');self.installed('candidate')
        self.assertIn('REASON=UPTIME_FORMAT',out)
        self.assertIn('RESULT=APPLIED',out)

    def test_repeated_scan_error_stops_after_bounded_retries(self):
        self.stage();self.boot();self.state(fault_after_md=20)
        out=self.run_manager('apply',ok=False)
        self.assertEqual(out.count('QUIESCENCE_FAILED'),3)
        self.assertIn('QUIET_REASON=UPTIME_FORMAT',out)
        self.assertEqual((self.v/'runtime-controller.sh').read_bytes(),b'baseline controller')
        self.assertEqual((self.v/'payload.sha256').read_bytes(),self.baseline['payload.sha256'])

    def test_live_md_after_first_rename_is_terminal_not_transient(self):
        self.stage();self.boot();self.state(live_after_md=True)
        out=self.run_manager('apply',ok=False)
        self.assertEqual(out.count('QUIESCENCE_FAILED'),1)
        self.assertIn('QUIET_REASON=LIVE_MD PID=1717',out)
        self.assertNotIn('org.carlife.hulab.MdMain',out)
        self.assertEqual((self.v/'runtime-controller.sh').read_bytes(),b'baseline controller')

    def test_external_payload_change_has_distinct_reason_and_no_scan_retry(self):
        self.stage();self.boot();self.state(tamper_after_mv=str(self.v/'md.jar'))
        out=self.run_manager('apply',ok=False)
        self.assertIn('PAYLOAD_REASON=UNRECOGNIZED_LIVE_md.jar',out)
        self.assertNotIn('QUIESCENCE_FAILED',out)

    def test_large_proc_inventory_has_no_per_pid_external_command(self):
        self.stage();self.boot();self.state(alive=False,init='stopped')
        for pid in range(10000,10900):
            data=b'' if pid % 2 else b'/system/bin/unrelated\0--argument with space\0'
            self.put(self.r/'proc'/str(pid)/'cmdline',data,0o444)
        self.run_manager('apply');self.installed('candidate')
        for command in self.commands():
            self.assertNotEqual(command[0],'tr')
            self.assertFalse(any(re.search(r'/proc/[0-9]+/cmdline',arg) for arg in command))

    def test_nul_argv_full_length_native_owner_is_detected(self):
        self.stage();self.boot();self.state(alive=False)
        self.put(self.r/'proc/1717/cmdline',b'app_process\0'+b'x'*10000+b'\0org.carlife.hulab.MdMain\0--transport\0native-usb\0',0o444)
        self.assertIn('unverified_live_owner',self.run_manager('apply',ok=False));self.installed('baseline')

    def test_nonterminated_last_argv_native_owner_is_detected(self):
        self.stage();self.boot();self.state(alive=False)
        self.put(self.r/'proc/1717/cmdline',b'app_process\0/system/bin\0org.carlife.hulab.MdMain',0o444)
        self.run_manager('apply',ok=False);self.installed('baseline')

    def test_existing_pid_unreadable_or_nonregular_cmdline_fails_closed(self):
        self.stage();self.boot();self.state(alive=False)
        self.put(self.r/'proc/1717/cmdline',b'unknown',0o000)
        self.run_manager('apply',ok=False);self.installed('baseline')
        (self.r/'proc/1717/cmdline').unlink();(self.r/'proc/1717/cmdline').mkdir()
        self.run_manager('apply',ok=False);self.installed('baseline')

    def test_live_foreign_owner_no_write(self):
        self.stage();self.boot();self.state(alive=False)
        self.put(self.r/'proc/1717/cmdline',b'app_process\0/system/bin\0org.carlife.hulab.MdMain\0',0o444)
        self.assertIn('unverified_live_owner',self.run_manager('apply',ok=False));self.installed('baseline')
        self.assertFalse(any(c[0]=='stop' for c in self.commands()))

    def test_owner_stop_failure_does_not_init_kill_or_write(self):
        self.stage();self.boot();self.state(owner_stop_fail=True)
        self.assertIn('owner_stop_failed',self.run_manager('apply',ok=False));self.installed('baseline')
        self.assertFalse(any(c[0]=='stop' for c in self.commands()))

    def test_stale_stopped_status_does_not_prove_quiescence(self):
        self.stage();self.boot();self.state(stubborn=True)
        self.put(self.r/'proc/1717/cmdline',b'app_process\0/system/bin\0org.carlife.hulab.MdMain\0',0o444)
        self.assertIn('live_process_or_init_not_stopped',self.run_manager('apply',ok=False));self.installed('baseline')

    def test_nonempty_owned_cgroup_blocks_publication(self):
        self.stage();self.boot();self.state(stubborn=True)
        self.put(self.r/'sys/fs/cgroup/carlife-boot-native-a/md/cgroup.procs',b'1717\n',0o644)
        self.run_manager('apply',ok=False);self.installed('baseline')

    def test_partial_publish_retry_completes_exact_known_combinations(self):
        self.stage();self.boot();self.state(fail_mv=str(self.v/'runtime-controller.sh'))
        self.run_manager('apply',ok=False)
        self.assertEqual((self.v/'md.jar').read_bytes(),b'candidate MD')
        self.assertEqual((self.v/'payload.sha256').read_bytes(),self.baseline['payload.sha256'])
        self.run_manager('apply');self.installed('candidate')
        op=next((self.d/'operations').iterdir());self.assertTrue((op/'receipt.intent').exists())

    def test_rollback_from_partial_restores_original(self):
        self.stage();self.boot();self.state(fail_mv=str(self.v/'runtime-controller.sh'))
        self.run_manager('apply',ok=False)
        self.package(action='rollback');self.stage();self.boot(BOOT3);self.run_manager('apply');self.installed('baseline')

    def test_partial_external_modification_refused(self):
        self.stage();self.boot();self.state(fail_mv=str(self.v/'runtime-controller.sh'))
        self.run_manager('apply',ok=False)
        self.put(self.v/'md.jar',b'foreign MD',0o400)
        self.assertIn('live_payload_changed_outside_transaction',self.run_manager('apply',ok=False))
        self.package(action='rollback');self.run_manager('stage',ok=False)
        self.assertEqual((self.v/'md.jar').read_bytes(),b'foreign MD')

    def test_external_change_between_renames_refused(self):
        self.stage();self.boot();self.state(tamper_after_mv=str(self.v/'md.jar'))
        self.run_manager('apply',ok=False)
        self.assertEqual((self.v/'runtime-controller.sh').read_bytes(),b'baseline controller')

    def test_changed_module_cannot_apply_staged_operation(self):
        self.stage();self.boot();self.package(md=b'other candidate')
        self.assertIn('installed_module_does_not_match_pending',self.run_manager('apply',ok=False));self.installed('baseline')

    def test_corrupt_package_refused_before_state_created(self):
        (self.m/'payload/md.jar').write_bytes(b'corrupt')
        self.run_manager('stage',ok=False);self.assertFalse(self.d.exists());self.installed('baseline')

    def test_payload_path_injection_refused_even_reindexed(self):
        f=self.m/'payload/payload.sha256';f.write_bytes(f.read_bytes()+b'0'*64+b'  /data/other\n')
        m=self.m/'manifest.env';m.write_text(re.sub(r'PAYLOAD_ID=.*',f'PAYLOAD_ID={SHA(f.read_bytes())}',m.read_text()))
        self.reindex();self.run_manager('stage',ok=False);self.assertFalse(self.d.exists())

    def test_live_mode_or_symlink_mismatch_refused(self):
        (self.v/'md.jar').chmod(0o666);self.run_manager('stage',ok=False);self.installed('baseline')
        (self.v/'md.jar').chmod(0o400);saved=self.v/'saved';(self.v/'md.jar').rename(saved);(self.v/'md.jar').symlink_to(saved)
        self.run_manager('stage',ok=False)

    def test_changed_resolver_or_observer_refused(self):
        self.put(self.p/'runtime-control.sh',b'foreign resolver',0o500);self.run_manager('stage',ok=False)
        self.put(self.p/'runtime-control.sh',(ROOT/'tools/boot_native/runtime/runtime-control.sh').read_bytes(),0o500)
        self.put(self.v/'uevent-watch',b'foreign observer',0o500);self.run_manager('stage',ok=False)

    def test_module_finalization_removes_customize_apply_still_valid(self):
        self.stage();(self.m/'customize.sh').unlink();self.boot();self.run_manager('apply');self.installed('candidate')

    def test_saved_carplay_selection_and_disabled_do_not_start(self):
        (self.s/'selected').write_text('carplay\n');before=(self.s/'selected').read_bytes()
        self.stage();self.boot();self.run_manager('apply');self.installed('candidate')
        self.assertEqual((self.s/'selected').read_bytes(),before);self.assertEqual(self.state().get('starts',0),0)

    def test_disabled_carlife_does_not_start(self):
        self.put(self.s/'disabled',b'',0o600);self.stage();self.boot();self.run_manager('apply')
        self.assertEqual(self.state().get('starts',0),0)

    def test_status_is_read_only(self):
        self.assertIn('NOT_STAGED',self.run_manager('status'));self.assertFalse(self.d.exists())
        self.stage();before={str(p):p.read_bytes() for p in self.d.rglob('*') if p.is_file()}
        self.run_manager('status');after={str(p):p.read_bytes() for p in self.d.rglob('*') if p.is_file()}
        self.assertEqual(before,after)

    def test_wrong_platform_sdk_and_owner_rejected(self):
        self.state(platform='unknown');self.run_manager('stage',ok=False)
        self.state(platform='sm6350',sdk=34);self.run_manager('stage',ok=False)
        self.state(sdk=35);(self.p/'owner').write_text('foreign\n');self.run_manager('stage',ok=False)

    def test_interrupted_copy_temp_is_recreated(self):
        self.stage();op=next((self.d/'operations').iterdir())
        self.put(op/'publish.md.jar',b'partial copy',0o600)
        self.boot();self.run_manager('apply');self.installed('candidate')

    def test_status_detects_live_changes_after_completed_receipt(self):
        self.stage();self.boot();self.run_manager('apply')
        self.put(self.v/'md.jar',b'external edit',0o400)
        self.assertIn('RECEIPT_PRESENT_LIVE_CHANGED',self.run_manager('status'))

    def test_staged_update_then_rollback_cancels_pending(self):
        self.stage();self.package(action='rollback')
        self.assertIn('ALREADY_INSTALLED',self.stage());self.assertFalse((self.d/'pending').exists())
        self.boot();self.assertIn('NO_PENDING_OPERATION',self.run_manager('apply'));self.installed('baseline')
        self.assertEqual(self.state().get('starts',0),0)

    def test_repackaged_identical_payload_cancels_old_pending(self):
        self.stage();self.boot();self.run_manager('apply')
        (self.m/'module.prop').write_bytes(b'new release same payload');self.reindex()
        self.assertIn('ALREADY_INSTALLED',self.stage());self.assertFalse((self.d/'pending').exists())
        self.boot(BOOT3);self.assertIn('NO_PENDING_OPERATION',self.run_manager('apply'));self.installed('candidate')

    def test_later_boot_does_not_become_late_loader(self):
        self.stage();self.boot();self.run_manager('apply')
        self.assertEqual(self.state()['starts'],1)
        self.boot(BOOT3);self.state(init='stopped',alive=False)
        self.assertIn('ALREADY_APPLIED',self.run_manager('apply'))
        self.assertEqual(self.state()['starts'],1)
        self.assertEqual(self.state()['init'],'stopped')

    def test_failed_start_can_retry_without_republication(self):
        self.stage();self.boot();self.state(start_fail=True)
        self.assertIn('INIT_START_REQUEST_FAILED',self.run_manager('apply',ok=False));self.installed('candidate')
        before=len([c for c in self.commands() if c[0]=='mv' and c[-1].startswith(str(self.v)+'/')])
        self.state(start_fail=False);self.run_manager('apply')
        after=len([c for c in self.commands() if c[0]=='mv' and c[-1].startswith(str(self.v)+'/')])
        self.assertEqual(before,after);self.assertEqual(self.state()['starts'],1)

    def test_publication_checksum_is_last_and_no_forbidden_commands(self):
        self.stage();self.boot();self.run_manager('apply')
        publishes=[c[-1] for c in self.commands() if c[0]=='mv' and c[-1].startswith(str(self.v)+'/')]
        self.assertEqual(publishes,[str(self.v/n) for n in ('md.jar','runtime-controller.sh','payload.sha256')])
        for c in self.commands():
            self.assertNotIn(c[0],('setprop','reboot','dd','mount','su','magisk','settings'))
            self.assertFalse(any('/dev/block' in a for a in c))
        self.assertTrue((self.d/'last-applied').is_file())

if __name__=='__main__':unittest.main()
