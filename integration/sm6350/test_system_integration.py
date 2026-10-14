"""Run generated provision shell only inside temporary host fixtures."""
import hashlib,importlib.util,io,json,os,pathlib,re,shutil,subprocess,sys,tarfile,tempfile,unittest
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('integration',HERE/'build_integration.py');build=importlib.util.module_from_spec(spec);spec.loader.exec_module(build)
ROOT=build.ROOT

def synthetic_inputs(directory):
    """Create host-only package inputs; no OEM firmware or executable DEX required.

    The real resolver and controller are copied verbatim from the public source.
    Other payloads are inert bytes used only to exercise provision hashes/modes.
    """
    version='boot-native-v1-20261014';prefix='data/versions/'+version
    runtime_source=ROOT/'tools/boot_native/runtime'
    entries={name:(None,0o700) for name in
             ('data','data/logs','data/runtime','data/versions',prefix)}
    entries.update({
        'data/current':((version+'\n').encode(),0o600),
        'data/owner':(b'CARLIFE_NATIVE_HOST_FIXTURE\n',0o600),
        'data/runtime-control.sh':((runtime_source/'runtime-control.sh').read_bytes(),0o500),
        prefix+'/runtime-controller.sh':((runtime_source/'runtime-controller.sh').read_bytes(),0o500),
        prefix+'/md.jar':(b'INERT_HOST_FIXTURE_NOT_DEX\n',0o400),
        prefix+'/uevent-watch':(b'INERT_HOST_FIXTURE_NOT_EXECUTABLE\n',0o500),
    })
    checks=''.join(build.sha(entries[prefix+'/'+name][0])+'  '+name+'\n'
                   for name in ('md.jar','runtime-controller.sh','uevent-watch'))
    entries[prefix+'/payload.sha256']=(checks.encode(),0o400)
    archive=directory/'runtime.tar';members={}
    with tarfile.open(archive,'w',format=tarfile.USTAR_FORMAT) as tar:
        for name,(data,mode) in sorted(entries.items()):
            item=tarfile.TarInfo(name);item.uid=item.gid=0;item.mode=mode;item.mtime=0
            record={'type':'directory' if data is None else 'file','uid':0,'gid':0,'mode':mode}
            if data is None:
                item.type=tarfile.DIRTYPE;tar.addfile(item)
            else:
                item.size=len(data);tar.addfile(item,io.BytesIO(data))
                record.update(size=len(data),sha256=build.sha(data))
            members[name]=record
    manifest=directory/'runtime-manifest.json'
    manifest.write_text(json.dumps({'schema':1,'contract':'BOOT_NATIVE_RUNTIME_V1',
        'root':'/data/local/carlife-native','version':version,
        'archive_sha256':build.sha(archive.read_bytes()),'members':members}))
    selector=directory/'selector';selector.mkdir();payload={}
    for name in ('selector-daemon.sh','selector-daemon.jar','selector-control.sh'):
        data=('INERT_HOST_FIXTURE '+name+'\n').encode()
        (selector/name).write_bytes(data);payload[name]=build.sha(data)
    (selector/'manifest.json').write_text(json.dumps({'payload':payload}))
    init=directory/'init.rc'
    init.write_text('# Synthetic host fixture, not an OEM init file.\n'
        'on early-init\n    start sd-reverse\n\n'
        'service sd-reverse /system/bin/sd-reverse.sh\n    disabled\n\n'
        'service console /system/bin/sh\n    disabled\n\n'
        +build.CONSOLE_STARTS+'\n'
        'on boot\n    class_start main\n    class_start late_start\n')
    oem=directory/'sd-reverse.sh';oem.write_text('# INERT_HOST_FIXTURE_NEVER_EXECUTED\n')
    return archive,manifest,selector,init,oem

STUB='''import hashlib,json,os,pathlib,subprocess,sys
name=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:]
if name=='timeout':
    assert a[:3]==['-k','1','4'];os.execvp(a[3],a[3:])
elif name=='stat':
    p=pathlib.Path(a[2]);st=p.stat()
    if a[1]=='%u:%g:%a':print('0:0:'+oct(st.st_mode&0o777)[2:])
    elif a[1]=='%s':print(st.st_size)
    else:raise ValueError(a)
elif name=='sha256sum':print(hashlib.sha256(pathlib.Path(a[0]).read_bytes()).hexdigest()+'  '+a[0])
elif name=='readlink':
    assert a[0]=='-f';print(pathlib.Path(a[1]).resolve())
elif name=='id':print(0)
elif name in ('chown','sync'):pass
elif name=='restorecon':
    if os.environ.get('FAIL_AFTER_RESOLVER_PUBLISH') and a[-1].endswith('/runtime-control.sh'):sys.exit(1)
elif name=='ps':print(os.environ.get('FAKE_NATIVE_PROCESS','PID ARGS'))
elif name=='getprop':
    assert a==['sys.carlife.data_ready'];print(os.environ.get('FAKE_DATA_READY',''))
elif name=='setprop':
    assert a==['sys.carlife.data_ready','1'],a
    with open(os.environ['FIXTURE_EVENTS'],'a')as f:f.write(' '.join(a)+'\\n')
elif name=='mv':
    if os.environ.get('FAIL_SELECTOR_PUBLISH') and a[-1].endswith('/data/adb/carlife-boot'):sys.exit(1)
    os.execv('/bin/mv',['/bin/mv']+a)
else:raise ValueError(name)
'''

class SystemIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base_tmp=tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.base_tmp.cleanup)
        cls.args=synthetic_inputs(pathlib.Path(cls.base_tmp.name))
        cls.generated=pathlib.Path(cls.base_tmp.name)/'candidate'
        # Scope the synthetic pin to this imported module in this test process.
        # The production builder file and b97/6add resolver pins remain intact.
        original_pin=build.INIT_SHA
        cls.addClassCleanup(setattr,build,'INIT_SHA',original_pin)
        build.INIT_SHA=build.sha(cls.args[3].read_bytes())
        cls.result=build.build(*cls.args,'carlife',cls.generated)
        cls.old_resolver=build.load_runtime(*cls.args[:2])[1]['data/runtime-control.sh']
    @classmethod
    def tearDownClass(cls):cls.base_tmp.cleanup()
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.r=pathlib.Path(self.tmp.name).resolve()
        shutil.copytree(self.generated/'files/system',self.r/'system')
        (self.r/'data').mkdir();(self.r/'proc/sys/kernel/random').mkdir(parents=True)
        (self.r/'proc/sys/kernel/random/boot_id').write_text('12345678-1234-1234-1234-123456789abc\n')
        (self.r/'proc/mounts').write_text('/dev/fake '+str(self.r/'data')+' ext4 rw 0 0\n')
        bin=self.r/'bin';bin.mkdir()
        for n in ['timeout','stat','sha256sum','readlink','id','chown','restorecon','sync','setprop','mv','ps','getprop']:
            f=bin/n;f.write_text('#!'+sys.executable+'\n'+STUB);f.chmod(0o700)
        source=(self.r/'system/etc/carlife/provision.sh').read_text()
        # All Android absolute paths are replaced before the shell is launched.
        source=re.sub(r'(?<![A-Za-z0-9_])/(system|vendor|data|proc|sys)(?![A-Za-z0-9_])',lambda m:str(self.r/m.group(1)),source)
        source=source.replace('PATH='+str(self.r/'system/bin')+':'+str(self.r/'system/xbin')+':'+str(self.r/'vendor/bin'),'PATH='+str(bin)+':/usr/bin:/bin')
        self.script=self.r/'fixture.sh';self.script.write_text(source)
        self.p=self.r/'data/local/carlife-native';self.s=self.r/'data/adb/carlife-boot';self.events=self.r/'events'
    def run_shell(self,**env):
        return subprocess.run(['/bin/sh',str(self.script)],env={**os.environ,'FIXTURE_EVENTS':str(self.events),**env},capture_output=True,text=True,timeout=20)
    def old_pilot(self,disabled=True):
        d=self.r/'data/adb/modules/carlife_native_pilot_v3';d.mkdir(parents=True);(self.r/'data/adb').chmod(0o700)
        if disabled:(d/'disable').write_bytes(b'');(d/'disable').chmod(0o644)
        return d
    def check_success(self,out):
        self.assertEqual(out.returncode,0,out.stderr)
        self.assertTrue(self.p.is_dir());self.assertEqual((self.s/'schema').read_bytes(),b'1\n')
        self.assertEqual(self.events.read_text().splitlines()[-1],'sys.carlife.data_ready 1')
    def test_fresh_stock_without_magisk_is_provisioned(self):
        out=self.run_shell();self.check_success(out)
        self.assertEqual((self.s/'selected').read_bytes(),b'carlife\n')
        self.assertFalse((self.r/'data/adb/modules').exists())
        self.assertEqual((self.p/'runtime-control.sh').stat().st_mode&0o777,0o500)
    def test_existing_owned_selection_and_evidence_preserved(self):
        self.check_success(self.run_shell());(self.s/'selected').write_bytes(b'android_auto\n')
        (self.s/'disabled').write_bytes(b'');(self.s/'disabled').chmod(0o600)
        (self.p/'runtime/evidence').write_bytes(b'original');(self.p/'logs/old').write_bytes(b'oldlog')
        self.check_success(self.run_shell())
        self.assertEqual((self.s/'selected').read_bytes(),b'android_auto\n')
        self.assertTrue((self.s/'disabled').exists());self.assertEqual((self.p/'runtime/evidence').read_bytes(),b'original')
        self.assertEqual((self.p/'logs/old').read_bytes(),b'oldlog')
    def test_factory_reset_seeds_carlife_again(self):
        self.check_success(self.run_shell());(self.s/'selected').write_bytes(b'carplay\n')
        # Only the host fixture simulates factory reset; provision never deletes data.
        shutil.rmtree(self.r/'data');(self.r/'data').mkdir();self.events.unlink()
        self.check_success(self.run_shell())
        self.assertEqual((self.s/'selected').read_bytes(),b'carlife\n')
        self.assertEqual((self.s/'selected').stat().st_mode&0o777,0o600)
    def test_existing_carplay_choice_survives_new_boot(self):
        self.check_success(self.run_shell());(self.s/'selected').write_bytes(b'carplay\n')
        (self.r/'proc/sys/kernel/random/boot_id').write_text('abcdefab-1234-1234-1234-123456789abc\n')
        self.check_success(self.run_shell())
        self.assertEqual((self.s/'selected').read_bytes(),b'carplay\n')
    def test_existing_active_legacy_refused_before_own_publication(self):
        self.old_pilot(False);out=self.run_shell()
        self.assertEqual(out.returncode,70);self.assertIn('existing_late_pilot_not_disabled',out.stderr)
        self.assertFalse(self.p.exists());self.assertFalse(self.events.exists())
    def test_existing_disabled_legacy_allowed(self):
        self.old_pilot();self.check_success(self.run_shell())
    def test_symlink_legacy_is_not_absence(self):
        (self.r/'data/adb/modules').mkdir(parents=True);(self.r/'data/adb').chmod(0o700)
        (self.r/'data/adb/modules/carlife_native_pilot_v3').symlink_to(self.r/'missing')
        out=self.run_shell();self.assertEqual(out.returncode,70);self.assertFalse(self.p.exists())
    def test_existing_mismatched_runtime_is_preserved(self):
        self.check_success(self.run_shell());self.events.unlink();f=self.p/'runtime-control.sh';f.chmod(0o700);f.write_bytes(b'prior-version');f.chmod(0o500)
        out=self.run_shell();self.assertEqual(out.returncode,70);self.assertEqual(f.read_bytes(),b'prior-version');self.assertFalse(self.events.exists())
    def test_selector_lock_and_nul_records_fail_closed(self):
        self.check_success(self.run_shell());self.events.unlink();lock=self.s/'.selector-lock';lock.mkdir()
        out=self.run_shell();self.assertEqual(out.returncode,70);self.assertTrue(lock.exists());self.assertFalse(self.events.exists())
        lock.rmdir();(self.s/'schema').write_bytes(b'1\0');out=self.run_shell()
        self.assertEqual(out.returncode,70);self.assertFalse(self.events.exists())
    def test_source_hash_failure_never_publishes_ready(self):
        (self.r/'system/etc/carlife/runtime-seed/current').write_bytes(b'other\n')
        out=self.run_shell();self.assertEqual(out.returncode,70);self.assertFalse(self.p.exists());self.assertFalse(self.events.exists())
    def test_unmounted_data_refused_without_creation(self):
        (self.r/'proc/mounts').write_text('')
        out=self.run_shell();self.assertEqual(out.returncode,70);self.assertIn('data_not_mounted',out.stderr);self.assertFalse((self.r/'data/adb').exists())
    def test_partial_firstboot_runtime_publish_resumes_without_overwrite(self):
        out=self.run_shell(FAIL_SELECTOR_PUBLISH='1');self.assertEqual(out.returncode,70)
        self.assertTrue(self.p.is_dir());self.assertFalse(self.s.exists());self.assertFalse(self.events.exists())
        original=(self.p/'runtime-control.sh').read_bytes();(self.p/'logs/partial-evidence').write_bytes(b'keep')
        self.check_success(self.run_shell());self.assertEqual((self.p/'runtime-control.sh').read_bytes(),original)
        self.assertEqual((self.p/'logs/partial-evidence').read_bytes(),b'keep')
    def test_init_actions_do_not_block_or_remove_radio_classes(self):
        init=(self.generated/'files/system/etc/init/hw/init.rc').read_text();original=self.args[3].read_text()
        self.assertEqual(init.count('    start sd-reverse\n'),1)
        self.assertEqual(init.count('class_start main'),original.count('class_start main'))
        self.assertIn('on post-fs-data\n    start carlife-provision',init)
        extra=init.split('# CARLIFE_SYSTEM_BOOT_V1:',1)[1]
        self.assertNotIn('exec_start',extra);self.assertNotIn('class_stop',extra)
        for n in ['early-dispatch','native-entry','selector-entry','provision']:
            p=self.generated/f'files/system/etc/carlife/{n}.sh';out=subprocess.run(['sh','-n',str(p)],capture_output=True)
            self.assertEqual(out.returncode,0,out.stderr)
    def test_exact_init_pin_and_determinism(self):
        out=self.r/'repeat';build.build(*self.args,'carlife',out)
        for f in (self.generated/'files').rglob('*'):
            if f.is_file():self.assertEqual(f.read_bytes(),(out/'files'/f.relative_to(self.generated/'files')).read_bytes())
        wrong=self.r/'wrong.rc';wrong.write_bytes(self.args[3].read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'exact E init pin'):build.build(*self.args[:3],wrong,self.args[4],'carlife',self.r/'rejected')
        self.assertFalse((self.r/'rejected').exists())
    def test_console_is_disabled_without_touching_other_init_services(self):
        text=(self.generated/'files/system/etc/init/hw/init.rc').read_text()
        self.assertNotIn('    start console\n',text)
        self.assertIn('service console /system/bin/sh',text)
        original=self.args[3].read_text()
        self.assertEqual(text.count('class_start main'),original.count('class_start main'))
        self.assertEqual(text.count('class_start late_start'),original.count('class_start late_start'))
        # Console boot command line lives in the separate exact boot candidate.
        self.assertNotIn('setprop vendor.qcom.main.start',text)
        controller=(self.generated/'files/system/etc/carlife/runtime-seed/versions/boot-native-v1-20261014/runtime-controller.sh').read_text()
        self.assertIn('bootstrap_qcom_main_once\n',controller)
    def install_old_resolver(self):
        self.check_success(self.run_shell());self.events.unlink()
        p=self.p/'runtime-control.sh';p.chmod(0o700);p.write_bytes(self.old_resolver);p.chmod(0o500)
    def test_exact_old_resolver_migrates_once_preserving_selection_and_evidence(self):
        self.install_old_resolver();(self.s/'selected').write_bytes(b'android_auto\n')
        (self.p/'logs/keep').write_bytes(b'original');self.check_success(self.run_shell())
        dirs=list(self.p.glob('.resolver-migration.*'));self.assertEqual(len(dirs),1);d=dirs[0]
        self.assertEqual((d/'old-runtime-control.sh').read_bytes(),self.old_resolver)
        self.assertEqual((d/'old-runtime-control.sh').stat().st_mode&0o777,0o500)
        self.assertEqual((d/'receipt').stat().st_mode&0o777,0o600)
        self.assertTrue((d/'COMMITTED').is_file());self.assertFalse((d/'new-runtime-control.sh').exists())
        self.assertEqual((self.s/'selected').read_bytes(),b'android_auto\n');self.assertEqual((self.p/'logs/keep').read_bytes(),b'original')
        self.assertEqual(hashlib.sha256((self.p/'runtime-control.sh').read_bytes()).hexdigest(),'6add4df4d3aeb321a813e9d2262d755c7d3615f5ff0883bca08c3cb70fe36481')
        self.check_success(self.run_shell());self.assertEqual(list(self.p.glob('.resolver-migration.*')),dirs)
    def test_migration_refuses_other_payload_or_selector_drift(self):
        self.install_old_resolver();f=self.p/'versions/boot-native-v1-20261014/md.jar';f.chmod(0o600);f.write_bytes(b'other');f.chmod(0o400)
        out=self.run_shell();self.assertEqual(out.returncode,70)
        self.assertEqual((self.p/'runtime-control.sh').read_bytes(),self.old_resolver)
        self.assertEqual(list(self.p.glob('.resolver-migration.*')),[]);self.assertFalse(self.events.exists())
    def test_migration_refuses_active_owner_and_published_readiness(self):
        self.install_old_resolver()
        for env in [{'FAKE_NATIVE_PROCESS':'123 app_process / org.carlife.hulab.MdMain --transport native-usb'},{'FAKE_DATA_READY':'1'}]:
            out=self.run_shell(**env);self.assertEqual(out.returncode,70)
            self.assertEqual((self.p/'runtime-control.sh').read_bytes(),self.old_resolver)
            self.assertEqual(list(self.p.glob('.resolver-migration.*')),[])
    def test_postrename_failure_keeps_backup_and_next_boot_accepts_known_new(self):
        self.install_old_resolver();out=self.run_shell(FAIL_AFTER_RESOLVER_PUBLISH='1')
        self.assertEqual(out.returncode,70);self.assertFalse(self.events.exists())
        dirs=list(self.p.glob('.resolver-migration.*'));self.assertEqual(len(dirs),1)
        self.assertEqual((dirs[0]/'old-runtime-control.sh').read_bytes(),self.old_resolver)
        self.assertFalse((dirs[0]/'COMMITTED').exists())
        self.check_success(self.run_shell());self.assertEqual(list(self.p.glob('.resolver-migration.*')),dirs)
    def owner_lock(self,boot):
        (self.p/'runtime/owner.lock').mkdir(mode=0o700)
        (self.p/'runtime/boot').write_text(boot+'\n');(self.p/'runtime/boot').chmod(0o600)
    def test_stale_prior_boot_lock_is_preserved_and_does_not_block_migration(self):
        self.install_old_resolver();prior='abcdefab-1234-1234-1234-123456789abc';self.owner_lock(prior)
        self.check_success(self.run_shell())
        self.assertTrue((self.p/'runtime/owner.lock').is_dir())
        self.assertEqual(list((self.p/'runtime/owner.lock').iterdir()),[])
        self.assertEqual((self.p/'runtime/boot').read_text(),prior+'\n')
    def test_current_boot_or_nonempty_owner_lock_is_not_removed(self):
        self.install_old_resolver();self.owner_lock('12345678-1234-1234-1234-123456789abc')
        out=self.run_shell();self.assertEqual(out.returncode,70)
        self.assertEqual(list(self.p.glob('.resolver-migration.*')),[])
        (self.p/'runtime/boot').write_text('abcdefab-1234-1234-1234-123456789abc\n')
        (self.p/'runtime/owner.lock/foreign').write_text('preserve')
        out=self.run_shell();self.assertEqual(out.returncode,70)
        self.assertEqual((self.p/'runtime/owner.lock/foreign').read_text(),'preserve')
        self.assertEqual((self.p/'runtime-control.sh').read_bytes(),self.old_resolver)

if __name__=='__main__':unittest.main()
