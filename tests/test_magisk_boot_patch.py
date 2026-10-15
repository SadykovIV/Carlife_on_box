"""Run the Android shell patcher offline against an explicitly simulated magiskboot.

The double models the reviewed v30.7 CLI using real newc/Android boot fixtures;
this is NOT a claim that Android magiskboot ran on macOS. An independent host
parser checks final bytes, and injected corruption tests verify fail-closed gates.
"""
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shlex
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SHELL = ROOT / 'tools/magisk/patch-boot.sh'
spec = importlib.util.spec_from_file_location('fixture_builder', ROOT / 'tests/test_magisk_builder.py')
f = importlib.util.module_from_spec(spec); spec.loader.exec_module(f)
b = f.b

DOUBLE = r'''#!PYTHON
import gzip,hashlib,importlib.util,json,os,pathlib,stat,struct,sys
spec=importlib.util.spec_from_file_location('independent_boot',os.environ['BOOT_PARSER'])
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
a=sys.argv[1:];kind=a.pop(0);root=pathlib.Path(os.environ['PATCH_TEST_ROOT'])
with (root/'calls.jsonl').open('a') as out:out.write(json.dumps([kind,*a])+'\n')
fault=os.environ.get('PATCH_FAULT','')
if fault==kind:sys.exit(73)
def canonical(entries):
    out=b''
    for ino,e in enumerate(sorted(entries,key=lambda x:x['name']),300000):
        fields=list(e['fields']);fields[0]=ino;fields[4]=1;fields[5]=0;fields[7]=fields[8]=fields[12]=0
        name=e['name'].encode()+b'\0';fields[6]=len(e['data']);fields[11]=len(name)
        raw=b'070701'+b''.join(('%08x'%x).encode() for x in fields)+name
        raw+=b'\0'*(b.align(len(raw),4)-len(raw));raw+=e['data'];raw+=b'\0'*(b.align(len(raw),4)-len(raw));out+=raw
    out+=b.newc_entry('TRAILER!!!',b'',0o755,300000+len(entries));return out
if kind=='unpack':
    assert a[0]=='-n';data=pathlib.Path(a[1]).read_bytes();parsed=b.parse_boot(data)
    for name,e in parsed['components'].items():
        if e['size']:pathlib.Path('ramdisk.cpio' if name=='ramdisk' else name).write_bytes(e['data'])
elif kind=='decompress':pathlib.Path(a[1]).write_bytes(gzip.decompress(pathlib.Path(a[0]).read_bytes()))
elif kind=='cpio':
    path=pathlib.Path(a[0]);entries,_=b.parse_cpio(path.read_bytes());byname={e['name']:e for e in entries}
    for command in a[1:]:
        c=command.split(' ')
        if c[0]=='test':sys.exit(1 if '.backup/.magisk' in byname else 0)
        if c[0]=='exists':sys.exit(0 if c[1] in byname else 1)
        if c[0]=='ls':
            for name,e in sorted(byname.items()):
                print('%s\t%d\t%d\t%d B\t%d:%d\t%s'%(stat.filemode(e['mode']),e['uid'],e['gid'],len(e['data']),e['fields'][9],e['fields'][10],name))
        elif c[0] in ('add','mkdir'):
            mode=int(c[1],8)|(stat.S_IFDIR if c[0]=='mkdir' else stat.S_IFREG)
            payload=b'' if c[0]=='mkdir' else pathlib.Path(c[3]).read_bytes()
            e=b.parse_cpio(b.newc_entry(c[2],payload,mode,10)+b.newc_entry('TRAILER!!!',b'',0,20))[0][0];byname[c[2]]=e
        elif c[0]=='rm':byname.pop(c[1],None)
        elif c[0]=='extract':
            e=byname[c[1]];assert stat.S_ISREG(e['mode']);pathlib.Path(c[2]).write_bytes(e['data'])
        else:raise AssertionError(c)
    path.write_bytes(canonical(list(byname.values())))
elif kind=='repack':
    assert os.environ['PATCHVBMETAFLAG']=='false'
    data=pathlib.Path(a[0]).read_bytes();cpio=pathlib.Path('ramdisk.cpio').read_bytes()
    if fault in ('cpio_data','cpio_uid','cpio_mode','cpio_extra','hook_mode'):
        entries,_=b.parse_cpio(cpio)
        if fault=='cpio_extra':entries+=b.parse_cpio(b.newc_entry('unexpected',b'x',stat.S_IFREG|0o600,9)+b.newc_entry('TRAILER!!!',b'',0,10))[0]
        else:
            e=next(x for x in entries if x['name']==('overlay.d/carlife-owner.rc' if fault=='hook_mode' else 'init'))
            if fault=='cpio_data':e['data']+=b'bad'
            if fault=='cpio_uid':e['fields'][2]=e['uid']=123
            if fault in ('cpio_mode','hook_mode'):e['fields'][1]=e['mode']=stat.S_IFREG|0o777
        cpio=canonical(entries)
    ramdisk=cpio if b.parse_boot(data)['components']['ramdisk']['data'].startswith(b'070701') else gzip.compress(cpio,mtime=0)
    output=bytearray(b.repack(data,ramdisk)[0]);parsed=b.parse_boot(output)
    if fault in ('kernel','dtb'):output[parsed['components'][fault]['offset']+100]^=1
    if fault=='cmdline':output[70]^=1
    if fault=='vbmeta':output[parsed['avb_footer']['vbmeta_offset']+120]^=1
    if fault=='footer':output[-60]^=1
    if fault=='oversize':output+=b'\0'*4096
    pathlib.Path(a[1]).write_bytes(output)
else:raise AssertionError(kind)
'''

STAT = r'''#!PYTHON
import os,sys
assert sys.argv[1:3]==['-c','%s'];print(os.stat(sys.argv[3]).st_size)
'''


class OnDeviceBootPatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve();self.d=self.root/'state';self.m=self.root/'module'
        self.d.mkdir();(self.m/'hooks').mkdir(parents=True);self.bin=self.root/'bin';self.bin.mkdir()
        self.mb=self.bin/'magiskboot';self.mb.write_text(DOUBLE.replace('PYTHON',sys.executable,1));self.mb.chmod(0o755)
        p=self.bin/'stat';p.write_text(STAT.replace('PYTHON',sys.executable,1));p.chmod(0o755)
        self.hooks={name:(name+'\n').encode() for name in ('carlife-owner.rc','carlife-early-dispatch.sh','carlife-native-entry.sh','carlife-selector-entry.sh')}
        for name,value in self.hooks.items():(self.m/'hooks'/name).write_bytes(value)
        self.script=self.root/'patch-boot.sh';text=SHELL.read_text()
        text=text.replace('MB=/data/adb/magisk/magiskboot','MB='+shlex.quote(str(self.mb)))
        text=text.replace('PATH=/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin','PATH='+shlex.quote(str(self.bin)+':'+os.environ['PATH']))
        self.script.write_text(text)
        self.env=dict(os.environ,BOOT_PARSER=str(ROOT/'tools/magisk/boot_image.py'),PATCH_TEST_ROOT=str(self.root),PATCHVBMETAFLAG='true')
        self.original=f.boot(gzip.compress(f.cpio(),mtime=0));(self.d/'original.img').write_bytes(self.original)

    def run_patch(self, fault='', size=None):
        env=dict(self.env,PATCH_FAULT=fault)
        return subprocess.run(['/bin/sh',str(self.script),str(self.d),str(self.m),str(size or len(self.original))],env=env,capture_output=True,text=True,timeout=30)

    def assert_rejected(self,result):
        self.assertNotEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertFalse((self.d/'candidate.img').exists())
        self.assertEqual((self.d/'original.img').read_bytes(),self.original)

    def test_complete_patch_preserves_boot_and_cpio_with_independent_parser(self):
        result=self.run_patch();self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        image=(self.d/'candidate.img').read_bytes();old=b.parse_boot(self.original);new=b.parse_boot(image)
        self.assertEqual(len(image),len(self.original));self.assertNotEqual(image,self.original)
        for name in ('kernel','dtb','second','recovery_dtbo'):self.assertEqual(old['components'][name]['data'],new['components'][name]['data'])
        self.assertEqual(old['avb_footer']['vbmeta_sha256'],new['avb_footer']['vbmeta_sha256'])
        before={e['name']:e for e in b.parse_cpio(gzip.decompress(old['components']['ramdisk']['data']))[0]}
        after={e['name']:e for e in b.parse_cpio(gzip.decompress(new['components']['ramdisk']['data']))[0]}
        for name,e in before.items():
            for field in ('data','mode','uid','gid'):self.assertEqual(e[field],after[name][field])
        self.assertEqual(set(after)-set(before),{'overlay.d','overlay.d/sbin','overlay.d/carlife-owner.rc',*[f'overlay.d/sbin/{x}' for x in self.hooks if x.endswith('.sh')]})
        for name,value in self.hooks.items():self.assertEqual(after['overlay.d/'+('sbin/' if name.endswith('.sh') else '')+name]['data'],value)
        self.assertIn('PRESERVATION=PASS',(self.d/'patch-audit.txt').read_text())
        self.assertEqual((self.d/'original.img').read_bytes(),self.original)
        calls=[json.loads(x) for x in (self.root/'calls.jsonl').read_text().splitlines()]
        self.assertFalse(any(c[0] in ('sign','hexpatch','dtb') for c in calls))
        self.assertFalse(any(any(x in ('patch','restore','backup') for x in c[2:]) for c in calls))

    def test_each_magiskboot_failure_is_fatal_before_candidate(self):
        for fault in ('unpack','decompress','cpio','repack'):
            with self.subTest(fault=fault):
                result=self.run_patch(fault);self.assert_rejected(result)


    def test_repack_corruption_is_detected(self):
        for fault in ('kernel','dtb','cmdline','vbmeta','footer','oversize','cpio_data','cpio_uid','cpio_mode','cpio_extra','hook_mode'):
            with self.subTest(fault=fault):
                self.assert_rejected(self.run_patch(fault))


    def test_uncompressed_ramdisk_and_no_avb(self):
        self.original=f.boot(f.cpio(),avb=False);(self.d/'original.img').write_bytes(self.original)
        r=self.run_patch();self.assertEqual(r.returncode,0,r.stdout+r.stderr)

    def test_conflicting_overlay_refused(self):
        self.original=f.boot(gzip.compress(f.cpio([('overlay.d',b'',stat.S_IFDIR|0o755),('overlay.d/other.rc',b'service sd-reverse /evil',stat.S_IFREG|0o644)]),mtime=0));(self.d/'original.img').write_bytes(self.original)
        r=self.run_patch();self.assert_rejected(r);self.assertIn('conflicting',r.stderr)

    def test_dot_entries_are_refused_before_normalization(self):
        for name in ('.','..','a/../b'):
            with self.subTest(name=name):
                self.original=f.boot(gzip.compress(f.cpio([(name,b'',stat.S_IFDIR|0o755)]),mtime=0));(self.d/'original.img').write_bytes(self.original)
                self.assert_rejected(self.run_patch())

    def test_raw_cpio_duplicates_and_hardlinks_refused(self):
        raw=f.cpio(); duplicate=f.cpio([('init',b'duplicate',stat.S_IFREG|0o750)])
        hardlink=raw[:38]+b'00000002'+raw[46:]
        for value in (duplicate,hardlink):
            with self.subTest(kind='duplicate' if value==duplicate else 'hardlink'):
                self.original=f.boot(gzip.compress(value,mtime=0));(self.d/'original.img').write_bytes(self.original)
                self.assert_rejected(self.run_patch())

    def test_existing_overlay_dirs_and_unrelated_module_preserved(self):
        self.original=f.boot(gzip.compress(f.cpio([('overlay.d',b'',stat.S_IFDIR|0o700),('overlay.d/sbin',b'',stat.S_IFDIR|0o700),('overlay.d/other.rc',b'service unrelated /system/bin/true',stat.S_IFREG|0o644)]),mtime=0));(self.d/'original.img').write_bytes(self.original)
        r=self.run_patch();self.assertEqual(r.returncode,0,r.stdout+r.stderr)

    def test_overlay_symlink_refused(self):
        self.original=f.boot(gzip.compress(f.cpio([('overlay.d',b'/data/local',stat.S_IFLNK|0o777)]),mtime=0));(self.d/'original.img').write_bytes(self.original)
        self.assert_rejected(self.run_patch())

    def test_nonmagisk_ramdisk_refused(self):
        cpio=b.newc_entry('init',b'OEM',stat.S_IFREG|0o750,1)+b.newc_entry('TRAILER!!!',b'',0,2)
        self.original=f.boot(gzip.compress(cpio,mtime=0));(self.d/'original.img').write_bytes(self.original)
        self.assert_rejected(self.run_patch())

    def test_unknown_trailer_refused_before_unpack(self):
        value=bytearray(f.boot(gzip.compress(f.cpio(),mtime=0),avb=False));p=b.parse_boot(value);value[p['component_end']+12]=9
        self.original=bytes(value);(self.d/'original.img').write_bytes(self.original)
        self.assert_rejected(self.run_patch());self.assertFalse((self.root/'calls.jsonl').exists())

    def test_wrong_size_missing_magiskboot_and_existing_candidate(self):
        self.assert_rejected(self.run_patch(size=12345))
        self.mb.unlink();self.assert_rejected(self.run_patch())
        (self.d/'candidate.img').write_bytes(b'preserve');r=self.run_patch();self.assertNotEqual(r.returncode,0)
        self.assertEqual((self.d/'candidate.img').read_bytes(),b'preserve')

    def test_failure_can_retry_without_deleting_evidence(self):
        self.assert_rejected(self.run_patch('repack'))
        previous=set(self.d.glob('patch-work.*'));self.assertEqual(len(previous),1)
        r=self.run_patch();self.assertEqual(r.returncode,0,r.stdout+r.stderr)
        self.assertEqual(len(set(self.d.glob('patch-work.*'))),2)
        self.assertTrue(all(p.exists() for p in previous))


if __name__=='__main__':unittest.main()
