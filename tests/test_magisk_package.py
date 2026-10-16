"""Exercise the one-ZIP dispatcher in a private Android filesystem.

The module lifecycle below models Magisk v30.7 util_functions.sh:609-708:
https://raw.githubusercontent.com/topjohnwu/Magisk/v30.7/scripts/util_functions.sh
Source SHA256: 79b0acacfc62e231496e5b88df9d720eecd4482e229d852a9d4fd0fb54d31822.
In particular, customize.sh is sourced, and finalization uses its resulting
MODID/MODPATH. Android commands and inner installers are fixture boundaries;
the production package dispatcher and its shell error paths run unchanged.
"""
from __future__ import annotations

import hashlib
import argparse
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'tools/magisk_package'
SHA = lambda value: hashlib.sha256(value).hexdigest()


def shell_map(source: str, root: Path) -> str:
    source = source.replace('"/$MAIN"', '"'+str(root)+'/$MAIN"')
    source = source.replace('"/$SETTINGS"', '"'+str(root)+'/$SETTINGS"')
    # The real ARM observer is a synthetic byte string in this offline suite.
    source = source.replace('a109a2a1270a09e25c87199cb9fa027c90db61dc511d846cb2fc6b94806c814e', SHA(b'observer'))
    source = re.sub(r'(?<![A-Za-z0-9_])(/(?:data|proc|sys|system|vendor|dev)(?:/[A-Za-z0-9_.:/-]+)?)',
                    lambda m: str(root) + m.group(1), source)
    return re.sub(r'^PATH=.*$', 'PATH='+str(root/'bin')+':/usr/bin:/bin:/usr/sbin:/sbin', source, flags=re.M)


def indexed(files: dict[str, bytes], legacy=False) -> dict[str, bytes]:
    files = dict(files)
    primary = 'files.sha256' if legacy else 'payload.sha256'
    files.pop(primary, None)
    files.pop('module.sha256', None)
    files['module.sha256'] = ''.join(f'{SHA(v)}  {n}\n' for n, v in sorted(files.items())
                                    if n != 'customize.sh').encode()
    files[primary] = ''.join(f'{SHA(v)}  {n}\n' for n, v in sorted(files.items())).encode()
    return files


def zip_bytes(files: dict[str, bytes]) -> bytes:
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, value in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o600) << 16
            archive.writestr(info, value)
    return stream.getvalue()


COMMAND = r'''#!PYTHON
import hashlib,json,os,pathlib,shutil,stat,subprocess,sys
r=pathlib.Path(os.environ['CARLIFE_PACKAGE_FIXTURE'])
name=pathlib.Path(sys.argv[0]).name; a=sys.argv[1:]
c=json.loads((r/'config.json').read_text())
with (r/'commands.jsonl').open('a') as f:f.write(json.dumps([name,*a])+'\n')
if c.get('fail_command')==name and all(t in ' '.join(a) for t in c.get('fail_contains',[])):
 print('injected failure: '+name,file=sys.stderr);sys.exit(73)
if name=='busybox':os.execvp(a[0],a)
elif name=='id':print('0' if a in (['-u'],['-g']) else 'uid=0(root) gid=0(root)')
elif name=='magisk':
 if a==['-V']:print('30700')
 else:raise RuntimeError('unexpected recursive Magisk invocation: '+repr(a))
elif name=='getprop':print(c.get('props',{}).get(a[0],''))
elif name=='getenforce':print('Permissive')
elif name in ('chown','chcon','sync'):pass
elif name=='stat':
 p=pathlib.Path(a[-1]);s=p.stat(follow_symlinks=False)
 fmt=a[a.index('-c')+1]
 values={'%u':'0','%g':'0','%a':format(stat.S_IMODE(s.st_mode),'o'),'%s':str(s.st_size),'%d':str(s.st_dev)}
 for k,v in values.items():fmt=fmt.replace(k,v)
 print(fmt)
elif name=='readlink':print(str(pathlib.Path(a[-1]).resolve()))
elif name=='sha256sum':
 if a and a[0]=='-c':
  rc=0
  content=sys.stdin.read() if a[-1]=='-' else pathlib.Path(a[-1]).read_text()
  for line in content.splitlines():
   h,p=line.split(None,1);p=p.lstrip('*')
   try:actual=hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
   except OSError:actual='unreadable'
   if h!=actual:rc=1
  sys.exit(rc)
 elif not a:print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest()+'  -')
 else:
  for p in a:print(hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()+'  '+p)
elif name in ('cp','mv','rm'):
 sys.exit(subprocess.run(['/bin/'+name,*a]).returncode)
else:raise RuntimeError('unexpected fixture command '+repr([name,*a]))
'''


class PackageFixture:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.modules = self.root / 'data/adb/modules'
        self.staged = self.root / 'data/adb/modules_update'
        self.state = self.root / 'data/adb/carlife-package'
        self.runtime = self.root / 'data/local/carlife-native'
        self.selector = self.root / 'data/adb/carlife-boot'
        for path in (self.modules, self.staged, self.root/'data/adb/magisk', self.root/'system/bin', self.root/'data/local'):
            path.mkdir(parents=True, exist_ok=True)
        self.config = {'props': {'ro.build.version.sdk': '35', 'ro.board.platform': 'lito'}}
        command = COMMAND.replace('#!PYTHON', '#!' + sys.executable).encode()
        for name in ('id', 'magisk', 'getprop', 'getenforce', 'chown', 'chcon', 'sync',
                     'stat', 'readlink', 'sha256sum', 'cp', 'mv', 'rm'):
            self.put(self.bin/name, command, 0o755)
        self.put(self.root/'data/adb/magisk/busybox', command, 0o755)
        (self.root/'system/bin/sh').symlink_to('/bin/sh')
        self.put(self.root/'proc/sys/kernel/random/boot_id', b'11111111-1111-4111-8111-111111111111\n', 0o444)

    def put(self, path: Path, value: bytes, mode=0o600):
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            path.chmod(0o600)
        path.write_bytes(value)
        path.chmod(mode)

    def run(self, script: str):
        (self.root/'config.json').write_text(json.dumps(self.config))
        env = dict(os.environ, CARLIFE_PACKAGE_FIXTURE=str(self.root),
                   PATH=str(self.bin)+':/usr/bin:/bin:/usr/sbin:/sbin')
        mapped = shell_map(script, self.root)
        return subprocess.run(['/bin/sh'], input=mapped, cwd=self.root, env=env,
                              text=True, capture_output=True, timeout=30)

    def commands(self):
        p = self.root/'commands.jsonl'
        return [json.loads(line) for line in p.read_text().splitlines()] if p.exists() else []

    def install(self, package: Path):
        # These ordering/variable-scope rules are the relevant Magisk contract;
        # all installed production scripts come from the supplied outer ZIP.
        script = r'''
BOOTMODE=true
ARCH=arm64
API=35
MAGISK_VER_CODE=30700
TMPDIR=/dev/tmp
ZIPFILE=PACKAGE
ui_print() { printf '%s\n' "$*"; }
set_perm() { chmod "$4" "$1"; }
set_perm_recursive() {
    find "$1" -type d -exec chmod "$4" '{}' \;
    find "$1" -type f -exec chmod "$5" '{}' \;
}
abort() {
    ui_print "$*"
    [ -z "$MODPATH" ] || rm -rf "$MODPATH"
    rm -rf "$TMPDIR"
    exit 1
}
rm -rf "$TMPDIR"
mkdir -p "$TMPDIR"
unzip -oq "$ZIPFILE" module.prop -d "$TMPDIR" || exit 91
MODID=$(sed -n 's/^id=//p' "$TMPDIR/module.prop")
MODPATH=/data/adb/modules_update/$MODID
rm -rf "$MODPATH"
mkdir -p "$MODPATH"
unzip -oq "$ZIPFILE" customize.sh -d "$MODPATH" || exit 92
if ! grep -q '^SKIPUNZIP=1$' "$MODPATH/customize.sh"; then
    unzip -oq "$ZIPFILE" -d "$MODPATH" || exit 93
fi
. "$MODPATH/customize.sh"
mkdir -p "/data/adb/modules/$MODID"
touch "/data/adb/modules/$MODID/update"
rm -rf "/data/adb/modules/$MODID/remove" "/data/adb/modules/$MODID/disable"
cp -af "$MODPATH/module.prop" "/data/adb/modules/$MODID/module.prop" || exit 94
rm -f "$MODPATH/customize.sh"
printf 'FINAL_MODID=%s\nFINAL_MODPATH=%s\n' "$MODID" "$MODPATH"
rm -rf "$TMPDIR"
'''.replace('ZIPFILE=PACKAGE', 'ZIPFILE='+shlex.quote(str(package)))
        return self.run(script)


def load_builder():
    spec = importlib.util.spec_from_file_location('one_magisk_package_builder', ROOT/'scripts/build_magisk_package.py')
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def mapped_archive(data: bytes, root: Path) -> bytes:
    """Map platform paths only, recomputing transport checksums for fixtures."""
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        files = {n: z.read(n) for n in z.namelist()}
    for name, value in list(files.items()):
        if name.endswith('.zip'):
            files[name] = mapped_archive(value, root)
        elif name.endswith('.sh'):
            files[name] = shell_map(value.decode(), root).encode()
    if 'module.sha256' in files:
        files = indexed(files, legacy='files.sha256' in files)
    if 'package/package.sha256' in files:
        files.pop('package/package.sha256')
        files['package/package.sha256'] = ''.join(f'{SHA(v)}  {n[8:]}\n' for n, v in sorted(files.items())
                                                if n.startswith('package/')).encode()
    return zip_bytes(files)


class MagiskPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.fixture = PackageFixture(self.root/'android')
        self.builder = load_builder()
        self.inputs = self.root/'inputs'
        self.inputs.mkdir()
        self.init = b'on early-init\n    start sd-reverse\n'
        self.oem = b'#!/system/bin/sh\nCHANNEL=sd_msm6350\n'
        self.fixture.put(self.fixture.root/'system/etc/init/hw/init.rc', self.init)
        self.fixture.put(self.fixture.root/'system/bin/sd-reverse.sh', self.oem)
        self.fixture.config['props'].update({'ro.board.reverse.custom':'chelianyi-led',
                                             'ro.boot.slot_suffix':'_a',
                                             'ro.boot.vbmeta.device_state':'unlocked'})
        self.old_main = zip_bytes({'AndroidManifest.xml':b'old-main', 'classes.dex':b'old main code'})
        self.old_settings = zip_bytes({'AndroidManifest.xml':b'old-settings', 'classes.dex':b'old settings code'})
        self.new_main = zip_bytes({'AndroidManifest.xml':b'old-main', 'classes.dex':b'new main code'})
        self.new_settings = zip_bytes({'AndroidManifest.xml':b'old-settings', 'classes.dex':b'new settings code'})
        self.fixture.put(self.fixture.root/self.builder.MAIN, self.old_main)
        self.fixture.put(self.fixture.root/self.builder.SETTINGS, self.old_settings)
        md = b'new MD mic4 fixture'
        controller = b'new controller fixture'
        self.runtime_files = {'md.jar':md, 'runtime-controller.sh':controller, 'uevent-watch':b'observer'}
        self.runtime_files['payload.sha256'] = ''.join(f'{SHA(v)}  {n}\n' for n, v in self.runtime_files.items()).encode()
        # The tested boundary is the dispatcher. Existing dedicated suites run
        # the real transaction engines; this records their route/arguments.
        manager = b'''#!/system/bin/sh
printf '%s:%s\\n' "$(sed -n 's/^MODULE_ID=//p' "$2/manifest.env")" "$1" >> /data/adb/backend-calls
[ ! -f /data/adb/fail-backend ] || exit 72
exit 0
'''
        customize = b'''[ "$BOOTMODE" = true ] || abort 'fixture BOOTMODE'
set_perm_recursive "$MODPATH" 0 0 0700 0600
set_perm "$MODPATH/manager.sh" 0 0 0500
/data/adb/magisk/busybox sh "$MODPATH/manager.sh" install "$MODPATH" || abort 'inner backend failure'
'''
        uenv = f'FORMAT=1\nMODULE_ID=carlife_on_box\nPACKAGE_KIND=universal\nBOOT_STRATEGY=on-device\nRUNTIME_VERSION=boot-native-v1-20261014\nBOOT_PROFILE_ID={"a"*64}\nPAYLOAD_ID={"b"*64}\nINIT_SHA256={SHA(self.init)}\nOEM_SHA256={SHA(self.oem)}\n'.encode()
        universal = {'manifest.env':uenv, 'manifest.json':b'{"payload_id":"fixture"}\n', 'module.prop':b'id=carlife_on_box\nname=fixture universal\nversion=old\nversionCode=1\n',
                     'customize.sh':customize, 'manager.sh':manager, 'skip_mount':b''}
        universal.update({'seed/runtime/versions/boot-native-v1-20261014/'+n:v for n,v in self.runtime_files.items()})
        self.universal = self.write_zip('universal.zip', indexed(universal))
        for action in ('update', 'rollback'):
            legacy = {'manifest.env':f'FORMAT=1\nMODULE_ID=carlife_runtime_update\nPACKAGE_ACTION={action}\nRUNTIME_VERSION=boot-native-v1-20261014\nPAYLOAD_ID={SHA(self.runtime_files["payload.sha256"])}\n'.encode(),
                      'module.prop':b'id=carlife_runtime_update\nname=fixture legacy\nversion=old\nversionCode=1\n',
                      'customize.sh':customize.replace(b' install ', b' stage '), 'manager.sh':manager,
                      'service.sh':b'#!/system/bin/sh\nexit 0\n', 'action.sh':b'#!/system/bin/sh\nexit 0\n',
                      'observer.sha256':(SHA(b'observer')+'\n').encode(),
                      'allowed-resolvers.txt':b'resolver\n', 'INSTALL_EN.txt':b'English', 'INSTALL_RU.txt':b'Russian',
                      'LICENSE':b'fixture', 'skip_mount':b''}
            legacy.update({'payload/'+n:v for n,v in self.runtime_files.items() if n!='uevent-watch'})
            setattr(self, 'legacy_'+action, self.write_zip('legacy-'+action+'.zip', indexed(legacy, legacy=True)))
        def ui(main, settings):
            return {'module.prop':b'id=carlife_oem_selector_v1\nname=old UI\n',
                    'service.sh':b'#!/system/bin/sh\nold late hook\n', self.builder.MAIN:main, self.builder.SETTINGS:settings}
        self.oem_overlay = self.write_zip('ui.zip', ui(self.new_main,self.new_settings))
        self.compatible = self.write_zip('old-ui.zip', ui(self.old_main,self.old_settings))

    def write_zip(self, name, files):
        result = self.inputs/name
        result.write_bytes(zip_bytes(files))
        return result

    def args(self, name='output'):
        values = dict(release='test-r8', version_code=8, output=self.root/name,
                      compatible_oem_overlay=[self.compatible])
        for n in ('universal','legacy_update','legacy_rollback','oem_overlay'):
            values[n] = getattr(self,n)
            values['expect_'+n+'_sha256'] = SHA(values[n].read_bytes())
        return argparse.Namespace(**values)

    def package(self, name='output', mapped=True):
        args = self.args(name)
        result = self.builder.build(args)
        package = args.output/result['package']
        if mapped:
            package.write_bytes(mapped_archive(package.read_bytes(), self.fixture.root))
        return package

    def assertSuccess(self, result):
        self.assertEqual(result.returncode, 0, result.stdout+'\n'+result.stderr)

    def existing_runtime(self, managed=False, ui=True):
        f = self.fixture
        version = f.runtime/'versions/boot-native-v1-20261014'
        for p in (f.runtime, f.runtime/'versions', version, f.runtime/'logs', f.runtime/'runtime', f.selector):
            p.mkdir(parents=True, exist_ok=True)
            p.chmod(0o700)
        f.put(f.runtime/'owner',b'org.carlife.boot-native.v1\n')
        f.put(f.runtime/'current',b'boot-native-v1-20261014\n')
        f.put(f.runtime/'runtime-control.sh',(ROOT/'tools/boot_native/runtime/runtime-control.sh').read_bytes(),0o500)
        previous = {'md.jar':b'actual previous MD','runtime-controller.sh':b'actual previous controller','uevent-watch':b'observer'}
        previous['payload.sha256'] = ''.join(f'{SHA(v)}  {n}\n' for n,v in previous.items()).encode()
        for n,v in previous.items():
            f.put(version/n,v,0o500 if n in ('runtime-controller.sh','uevent-watch') else 0o400)
        f.put(f.selector/'schema',b'1\n')
        f.put(f.selector/'selected',b'carplay\n')
        if managed:
            state=f.root/'data/adb/carlife-on-box'
            f.put(state/'phase',b'installed\n')
            f.put(state/'original.env',f'MODULE_ID=carlife_on_box\nBOOT_PROFILE_ID={"a"*64}\n'.encode())
            for p in (f.runtime,f.selector):f.put(p/'.installer-owned',b'carlife_on_box\n')
            module='carlife_on_box'
        else:
            module='carlife_runtime_update'
        f.put(f.modules/module/'module.prop',f'id={module}\nname=previous runtime\n'.encode())
        if ui:
            d=f.modules/'carlife_oem_selector_v1'
            with zipfile.ZipFile(self.compatible) as z:
                for n in z.namelist():f.put(d/n,z.read(n),0o755 if n.endswith('.sh') else 0o644)
            f.put(d/'disable',b'')
        return previous

    def promote(self):
        for directory in list(self.fixture.staged.iterdir()):
            dest=self.fixture.modules/directory.name
            if dest.exists():shutil.rmtree(dest)
            directory.rename(dest)
            (dest/'customize.sh').unlink(missing_ok=True)
            (dest/'update').unlink(missing_ok=True)
        self.fixture.put(self.fixture.root/'proc/sys/kernel/random/boot_id',
                         b'22222222-2222-4222-8222-222222222222\n',0o444)

    def run_control(self, mode):
        return self.fixture.run('/data/adb/magisk/busybox sh /data/adb/carlife-package/control.sh '+mode+'\n')

    def test_one_deterministic_zip_contains_both_install_routes_and_ui(self):
        first = self.package('first', mapped=False)
        second = self.package('second', mapped=False)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        with zipfile.ZipFile(first) as archive:
            files = {n:archive.read(n) for n in archive.namelist()}
        self.assertEqual([n for n in files if n.endswith('.zip')],
                         ['package/payload/legacy-rollback.zip','package/payload/legacy-update.zip',
                          'package/payload/oem-overlay.zip','package/payload/universal.zip'])
        self.assertIn(b'id=carlife_package\n', files['module.prop'])
        self.assertIn(b'SKIPUNZIP=1\n', files['customize.sh'])
        self.assertFalse(any(n.endswith('.img') for n in files))
        with zipfile.ZipFile(io.BytesIO(files['package/payload/oem-overlay.zip'])) as ui:
            self.assertNotIn('service.sh', ui.namelist(), 'early selector must not compete with historical late loader')
            self.assertEqual(ui.read(self.builder.MAIN), self.new_main)
            self.assertEqual(ui.read(self.builder.SETTINGS), self.new_settings)
        expected = ''.join(f'{SHA(v)}  {n[8:]}\n' for n,v in sorted(files.items())
                           if n.startswith('package/') and n!='package/package.sha256').encode()
        self.assertEqual(files['package/package.sha256'], expected)

    def test_bad_input_hash_is_rejected_before_output(self):
        args = self.args()
        args.expect_legacy_update_sha256 = '0'*64
        with self.assertRaisesRegex(ValueError,'SHA256 mismatch'):
            self.builder.build(args)
        self.assertFalse(args.output.exists())

    def test_different_fresh_and_update_runtime_is_rejected(self):
        with zipfile.ZipFile(self.legacy_update) as z:
            files = {n:z.read(n) for n in z.namelist()}
        files['payload/md.jar'] = b'different runtime'
        self.legacy_update.write_bytes(zip_bytes(indexed(files,legacy=True)))
        with self.assertRaisesRegex(ValueError,'runtime payload mismatch'):
            self.builder.build(self.args())

    def test_inner_package_tampering_is_rejected(self):
        with zipfile.ZipFile(self.universal) as z:
            files = {n:z.read(n) for n in z.namelist()}
        files['manager.sh'] += b'\n# tampered\n'
        self.universal.write_bytes(zip_bytes(files))
        with self.assertRaisesRegex(ValueError,'checksums differ'):
            self.builder.build(self.args())

    def test_traversal_and_donor_boot_are_rejected(self):
        original = self.universal.read_bytes()
        for name in ('../escaped', 'boot/foreign.img'):
            with self.subTest(name=name):
                with zipfile.ZipFile(io.BytesIO(original)) as z:
                    files = {n:z.read(n) for n in z.namelist()}
                files[name] = b'do not install'
                self.universal.write_bytes(zip_bytes(indexed(files)))
                with self.assertRaises(ValueError):
                    self.builder.build(self.args())

    def test_incorrect_overlay_id_and_unknown_member_are_rejected(self):
        original = self.oem_overlay.read_bytes()
        for change in ('wrong-id','extra-member'):
            with self.subTest(change=change):
                with zipfile.ZipFile(io.BytesIO(original)) as z:
                    files = {n:z.read(n) for n in z.namelist()}
                if change=='wrong-id':files['module.prop']=b'id=foreign-module\n'
                else:files['uninstall.sh']=b'unknown code'
                self.oem_overlay.write_bytes(zip_bytes(files))
                with self.assertRaises(ValueError):
                    self.builder.build(self.args())

    def test_fresh_shell_route_finalizes_universal_and_ui_ids(self):
        result = self.fixture.install(self.package())
        self.assertSuccess(result)
        self.assertIn('PREFLIGHT=PASS ROUTE=fresh', result.stdout)
        self.assertIn('FINAL_MODID=carlife_on_box', result.stdout)
        self.assertFalse((self.fixture.staged/'carlife_package').exists())
        self.assertFalse((self.fixture.modules/'carlife_package').exists())
        self.assertTrue((self.fixture.staged/'carlife_on_box/module.prop').is_file())
        self.assertTrue((self.fixture.modules/'carlife_on_box/update').is_file())
        self.assertTrue((self.fixture.staged/'carlife_oem_selector_v1'/self.builder.SETTINGS).is_file())
        self.assertTrue((self.fixture.modules/'carlife_oem_selector_v1/update').is_file())
        self.assertEqual((self.fixture.state/'ui-before.state').read_text(),'ABSENT\n')
        self.assertFalse(any(row[0]=='magisk' and row[1:]!=['-V'] for row in self.fixture.commands()))

    def test_managed_shell_reuses_id_and_preserves_live_selection(self):
        before=self.existing_runtime(managed=True)
        result=self.fixture.install(self.package())
        self.assertSuccess(result)
        self.assertIn('ROUTE=managed',result.stdout)
        self.assertIn('FINAL_MODID=carlife_on_box',result.stdout)
        self.assertFalse((self.fixture.staged/'carlife_runtime_update').exists())
        self.assertEqual((self.fixture.selector/'selected').read_bytes(),b'carplay\n')
        for n,v in before.items():
            self.assertEqual((self.fixture.runtime/'versions/boot-native-v1-20261014'/n).read_bytes(),v)

    def test_legacy_shell_reuses_id_and_saves_exact_prepackage_baseline(self):
        before=self.existing_runtime()
        first=self.fixture.root/'data/adb/carlife-runtime-update/backup/version/md.jar'
        self.fixture.put(first,b'older first-ever updater baseline',0o400)
        result=self.fixture.install(self.package())
        self.assertSuccess(result)
        self.assertIn('ROUTE=legacy',result.stdout)
        self.assertIn('FINAL_MODID=carlife_runtime_update',result.stdout)
        self.assertFalse((self.fixture.staged/'carlife_on_box').exists())
        self.assertFalse((self.fixture.runtime/'.installer-owned').exists())
        self.assertFalse((self.fixture.root/'data/adb/carlife-on-box').exists())
        self.assertEqual(first.read_bytes(),b'older first-ever updater baseline')
        for n in ('md.jar','runtime-controller.sh','payload.sha256'):
            self.assertEqual((self.fixture.state/'legacy-before/payload'/n).read_bytes(),before[n])
        self.assertEqual((self.fixture.state/'ui-before'/self.builder.SETTINGS).read_bytes(),self.old_settings)
        self.assertTrue((self.fixture.state/'ui-before/disable').exists())

    def test_pending_backend_is_preserved_on_abort_before_prepare(self):
        self.existing_runtime()
        active=(self.fixture.modules/'carlife_runtime_update/module.prop').read_bytes()
        pending=self.fixture.staged/'carlife_runtime_update'
        self.fixture.put(pending/'sentinel',b'pending existing installation')
        result=self.fixture.install(self.package())
        self.assertNotEqual(result.returncode,0)
        self.assertIn('pending module preserved',result.stderr)
        self.assertEqual((pending/'sentinel').read_bytes(),b'pending existing installation')
        self.assertEqual((self.fixture.modules/'carlife_runtime_update/module.prop').read_bytes(),active)
        self.assertFalse(self.fixture.state.exists())
        self.assertFalse((self.fixture.staged/'carlife_package').exists())

    def test_unfinished_legacy_receipt_is_preserved_before_prepare(self):
        self.existing_runtime()
        pending=self.fixture.root/'data/adb/carlife-runtime-update/pending'
        self.fixture.put(pending,b'OP_ID=unfinished\n')
        result=self.fixture.install(self.package())
        self.assertNotEqual(result.returncode,0)
        self.assertIn('incomplete legacy runtime',result.stderr)
        self.assertEqual(pending.read_bytes(),b'OP_ID=unfinished\n')
        self.assertFalse(self.fixture.state.exists())

    def test_pending_ui_is_preserved_before_backend_or_backup(self):
        self.existing_runtime()
        pending=self.fixture.staged/'carlife_oem_selector_v1'
        self.fixture.put(pending/'sentinel',b'UI pending')
        result=self.fixture.install(self.package())
        self.assertNotEqual(result.returncode,0)
        self.assertEqual((pending/'sentinel').read_bytes(),b'UI pending')
        self.assertFalse(self.fixture.state.exists())
        self.assertFalse((self.fixture.staged/'carlife_runtime_update').exists())

    def test_legacy_rollback_restores_exact_baseline_and_prior_disabled_ui(self):
        before=self.existing_runtime()
        self.assertSuccess(self.fixture.install(self.package()))
        self.promote()
        result=self.run_control('rollback')
        self.assertSuccess(result)
        target=self.fixture.staged/'carlife_runtime_update'
        for n in ('md.jar','runtime-controller.sh','payload.sha256'):
            self.assertEqual((target/'payload'/n).read_bytes(),before[n])
        ui=self.fixture.staged/'carlife_oem_selector_v1'
        self.assertEqual((ui/self.builder.SETTINGS).read_bytes(),self.old_settings)
        self.assertTrue((ui/'disable').exists())
        self.assertEqual((self.fixture.state/'phase').read_text(),'rollback\n')
        self.assertFalse((self.fixture.runtime/'.installer-owned').exists())

    def test_backend_failure_retains_matching_package_and_recovery_snapshot(self):
        before=self.existing_runtime()
        self.fixture.put(self.fixture.root/'data/adb/fail-backend',b'fail once')
        result=self.fixture.install(self.package())
        self.assertNotEqual(result.returncode,0)
        target=self.fixture.staged/'carlife_runtime_update'
        self.assertTrue((target/'manager.sh').is_file(), 'Magisk abort must not delete the package backing a possible durable receipt')
        self.assertTrue((target/'disable').exists())
        self.assertFalse((self.fixture.staged/'carlife_package').exists())
        self.assertTrue((self.fixture.state/'control.sh').is_file())
        self.assertEqual((self.fixture.state/'legacy-before/payload/md.jar').read_bytes(),before['md.jar'])
        self.assertEqual((self.fixture.modules/'carlife_oem_selector_v1'/self.builder.SETTINGS).read_bytes(),self.old_settings)
        self.assertFalse((self.fixture.modules/'carlife_runtime_update/update').exists())

    def test_ui_commit_failure_preserves_backend_package_after_success(self):
        self.existing_runtime()
        self.fixture.config.update(fail_command='chcon',fail_contains=['ui-next'])
        result=self.fixture.install(self.package())
        self.assertNotEqual(result.returncode,0)
        self.assertTrue((self.fixture.staged/'carlife_runtime_update/manager.sh').is_file())
        self.assertTrue((self.fixture.staged/'carlife_runtime_update/disable').exists())
        self.assertTrue((self.fixture.state/'ui-next'/self.builder.SETTINGS).is_file())
        self.assertEqual((self.fixture.state/'phase').read_text(),'prepared\n')
        self.assertEqual((self.fixture.modules/'carlife_oem_selector_v1'/self.builder.SETTINGS).read_bytes(),self.old_settings)

    def test_rollback_pending_ui_rejected_before_runtime_staging(self):
        self.existing_runtime()
        self.assertSuccess(self.fixture.install(self.package()))
        self.promote()
        self.fixture.put(self.fixture.staged/'carlife_oem_selector_v1/sentinel',b'pending unrelated UI')
        calls=self.fixture.root/'data/adb/backend-calls'
        before=calls.read_bytes()
        result=self.run_control('rollback')
        self.assertNotEqual(result.returncode,0)
        self.assertEqual((self.fixture.staged/'carlife_oem_selector_v1/sentinel').read_bytes(),b'pending unrelated UI')
        self.assertFalse((self.fixture.staged/'carlife_runtime_update').exists())
        self.assertEqual(calls.read_bytes(),before)
        self.assertEqual((self.fixture.state/'phase').read_text(),'installed\n')

    def test_repeated_package_update_retains_original_runtime_and_ui_backup(self):
        self.existing_runtime()
        self.assertSuccess(self.fixture.install(self.package('first')))
        backup=(self.fixture.state/'backup.sha256').read_bytes()
        previous=(self.fixture.state/'legacy-before/payload/md.jar').read_bytes()
        self.promote()
        self.assertSuccess(self.fixture.install(self.package('second')))
        self.assertEqual((self.fixture.state/'backup.sha256').read_bytes(),backup)
        self.assertEqual((self.fixture.state/'legacy-before/payload/md.jar').read_bytes(),previous)
        self.assertEqual((self.fixture.state/'ui-before'/self.builder.SETTINGS).read_bytes(),self.old_settings)

    def test_unreviewed_oem_ui_fails_before_any_backend_staging(self):
        self.existing_runtime()
        self.fixture.put(self.fixture.root/self.builder.SETTINGS,b'unknown OEM APK')
        result=self.fixture.install(self.package())
        self.assertNotEqual(result.returncode,0)
        self.assertIn('unreviewed OEM APK pair',result.stderr)
        self.assertFalse(self.fixture.state.exists())
        self.assertFalse((self.fixture.staged/'carlife_runtime_update').exists())

    def test_damaged_rollback_snapshot_does_not_stage_any_module(self):
        self.existing_runtime()
        self.assertSuccess(self.fixture.install(self.package()))
        self.promote()
        self.fixture.put(self.fixture.state/'legacy-before/payload/md.jar',b'corrupted backup')
        result=self.run_control('rollback')
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(list(self.fixture.staged.iterdir()))

    def test_foreign_active_ui_blocks_rollback_before_backend_mutation(self):
        self.existing_runtime()
        self.assertSuccess(self.fixture.install(self.package()))
        self.promote()
        self.fixture.put(self.fixture.modules/'carlife_oem_selector_v1/module.prop',b'id=foreign-module\n')
        calls=self.fixture.root/'data/adb/backend-calls'
        before=calls.read_bytes()
        result=self.run_control('rollback')
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(list(self.fixture.staged.iterdir()))
        self.assertEqual(calls.read_bytes(),before)

    def test_reinstall_after_complete_legacy_rollback_needs_no_manual_state_deletion(self):
        self.existing_runtime()
        self.assertSuccess(self.fixture.install(self.package('first')))
        self.promote()
        original=(self.fixture.state/'legacy-before/payload/md.jar').read_bytes()
        self.assertSuccess(self.run_control('rollback'))
        self.promote()
        self.assertSuccess(self.fixture.install(self.package('reinstall')))
        self.assertEqual((self.fixture.state/'phase').read_text(),'installed\n')
        archived=list(self.fixture.state.parent.glob('carlife-package.rollback.*'))
        self.assertEqual(len(archived),1)
        self.assertEqual((archived[0]/'legacy-before/payload/md.jar').read_bytes(),original)

    def test_failed_backend_can_rollback_without_removing_receipts_by_hand(self):
        self.existing_runtime()
        fail=self.fixture.root/'data/adb/fail-backend'
        self.fixture.put(fail,b'failure')
        result=self.fixture.install(self.package())
        self.assertNotEqual(result.returncode,0)
        fail.unlink()
        self.assertSuccess(self.run_control('rollback'))
        self.assertEqual((self.fixture.staged/'carlife_runtime_update/payload/md.jar').read_bytes(),b'actual previous MD')
        self.assertEqual((self.fixture.state/'phase').read_text(),'rollback\n')


if __name__ == '__main__':
    unittest.main()
