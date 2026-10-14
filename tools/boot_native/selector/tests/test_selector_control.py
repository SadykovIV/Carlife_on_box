"""Real shell/file operations in an isolated host fixture; no Android or USB calls."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys

SOURCE=Path(__file__).resolve().parents[1]/'selector-control.sh'

class SelectorControlTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name).resolve()
        self.adb=self.root/'data-adb';self.adb.mkdir();self.base=self.adb/'carlife-boot'
        self.bin=self.root/'bin';self.bin.mkdir();self.cp=self.root/'cp_aa';self.cp.write_text('2000')
        py=sys.executable
        self.tool('id', '#!/bin/sh\necho 0\n')
        self.tool('stat', f'#!{py}\nimport os,sys\ns=os.stat(sys.argv[-1]);print("0:0:%o"%(s.st_mode&0o7777))\n')
        self.tool('readlink', f'#!{py}\nimport os,sys\nprint(os.path.realpath(sys.argv[-1]))\n')
        self.tool('sync', '#!/bin/sh\nexit 0\n')
        self.tool('sleep', '#!/bin/sh\nexit 0\n')
        text=SOURCE.read_text().replace('PATH=/system/bin:/system/xbin:/vendor/bin','PATH='+str(self.bin)+':/usr/bin:/bin')
        text=text.replace('/data/adb',str(self.adb)).replace('/sys/devices/platform/soc/soc:qpdata_driver/cp_aa',str(self.cp))
        self.script=self.root/'control.sh';self.script.write_text(text)
    def tearDown(self):self.tmp.cleanup()
    def tool(self,name,text):p=self.bin/name;p.write_text(text);p.chmod(0o755)
    def run_cli(self,*args):return subprocess.run(['/bin/sh',str(self.script),*args],text=True,capture_output=True)
    def bootstrap(self,mode='android_auto'):
        r=self.run_cli('bootstrap',mode);self.assertEqual(r.returncode,0,r.stderr)
    def test_bootstrap_atomic_schema_and_no_rebootstrap(self):
        self.bootstrap();self.assertEqual((self.base/'schema').read_bytes(),b'1\n');self.assertEqual((self.base/'selected').read_bytes(),b'android_auto\n')
        self.assertEqual((self.base/'selected').stat().st_mode&0o777,0o600)
        self.assertNotEqual(self.run_cli('bootstrap','carlife').returncode,0)
    def test_three_choices_and_guard(self):
        self.bootstrap();self.assertEqual(self.run_cli('select-next-boot','carlife').returncode,0)
        self.assertEqual(self.run_cli('guard').stdout,'carlife\n')
        self.cp.write_text('1000');self.assertEqual(self.run_cli('select-next-boot','carplay').returncode,0)
        self.assertEqual(self.run_cli('get').stdout,'carplay\n')
        self.cp.write_text('2000');self.assertNotEqual(self.run_cli('guard').returncode,0)
    def test_mismatch_retains_existing_selection_no_oem_mutation(self):
        self.bootstrap('carlife');old=self.cp.read_bytes()
        r=self.run_cli('select-next-boot','carplay');self.assertNotEqual(r.returncode,0)
        self.assertEqual((self.base/'selected').read_bytes(),b'carlife\n');self.assertEqual(self.cp.read_bytes(),old)
        self.assertFalse((self.base/'.selector-lock').exists())
    def test_observes_delayed_oem_ack_without_resending(self):
        self.bootstrap('carlife')
        self.tool('sleep', '#!/bin/sh\nprintf 1000 > '+str(self.cp)+'\n')
        self.assertEqual(self.run_cli('select-next-boot','carplay').returncode,0)
    def test_rejects_non_root_and_arbitrary_enum(self):
        self.bootstrap();self.assertNotEqual(self.run_cli('select-next-boot','carlife;reboot').returncode,0)
        self.tool('id','#!/bin/sh\necho 1000\n');self.assertNotEqual(self.run_cli('get').returncode,0)
    def test_exact_bytes_permissions_schema(self):
        self.bootstrap();selected=self.base/'selected'
        for bad in (b'carlife',b'carlife\n\n',b'carlife\r\n',b'3\n',b'android_auto\x00\n'):
            selected.write_bytes(bad);self.assertNotEqual(self.run_cli('get').returncode,0,bad)
        selected.write_bytes(b'carlife\n');selected.chmod(0o644);self.assertNotEqual(self.run_cli('get').returncode,0)
        selected.chmod(0o600);(self.base/'schema').write_bytes(b'2\n');self.assertNotEqual(self.run_cli('get').returncode,0)
    def test_symlink_and_abandoned_lock_fail_closed(self):
        self.bootstrap();foreign=self.root/'foreign';foreign.write_text('foreign')
        (self.base/'selected').unlink();(self.base/'selected').symlink_to(foreign)
        self.assertNotEqual(self.run_cli('select-next-boot','carlife').returncode,0);self.assertEqual(foreign.read_text(),'foreign')
        (self.base/'selected').unlink();(self.base/'selected').write_text('carlife\n');(self.base/'selected').chmod(0o600)
        (self.base/'.selector-lock').mkdir();self.assertNotEqual(self.run_cli('guard').returncode,0)
    def test_disabled_does_not_delete_selection(self):
        self.bootstrap('carlife');marker=self.base/'disabled';marker.touch(mode=0o600)
        self.assertNotEqual(self.run_cli('guard').returncode,0);self.assertEqual(self.run_cli('get').stdout,'carlife\n')
    def test_sync_failure_does_not_publish_new_selection(self):
        self.bootstrap();self.tool('sync','#!/bin/sh\nexit 1\n')
        self.assertNotEqual(self.run_cli('select-next-boot','carlife').returncode,0)
        self.assertEqual((self.base/'selected').read_bytes(),b'android_auto\n')
        self.assertFalse((self.base/'.selector-lock').exists())

if __name__=='__main__':unittest.main()
