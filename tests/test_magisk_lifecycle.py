"""Exercise the real installer shell in an isolated Android filesystem.

Only absolute Android paths and the block-device predicate are mapped to fixtures.
Production code has no TEST_ROOT, verification bypass, or test-only command mode.
No test calls adb, Magisk, an actual block node, or changes the host's ownership.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / 'tools/magisk'

# Commands unavailable on macOS or unsafe against host state. All other shell
# operations execute unchanged against the private, mapped directory tree.
COMMAND = r'''#!PYTHON
import hashlib,json,os,pathlib,shutil,signal,stat,subprocess,sys
name=pathlib.Path(sys.argv[0]).name; args=sys.argv[1:]
root=pathlib.Path(os.environ['MAGISK_TEST_ROOT'])
config=json.loads((root/'stub-config.json').read_text())
with (root/'commands.jsonl').open('a') as log:log.write(json.dumps([name,*args])+'\n')
if name=='busybox':
    os.execvp(args[0],args)
if name=='timeout':
    if args and args[0]=='-k':args=args[2:]
    os.execvp(args[1],args[1:])
match=config.get('fail_command')==name and all(x in ' '.join(args) for x in config.get('fail_contains',[]))
if match and not config.get('fail_after'):
    print('injected failure: '+name,file=sys.stderr);sys.exit(73)
rc=0
if name=='id':print('0' if args==['-u'] else 'uid=0(root) gid=0(root)')
elif name=='getprop':print(config.get('properties',{}).get(args[0],''))
elif name=='getenforce':print('Permissive')
elif name=='magisk':print('30700' if args==['-V'] else '30.7:MAGISK:R')
elif name=='stat':
    p=pathlib.Path(args[-1]);s=p.stat(follow_symlinks=False)
    fmt=args[args.index('-c')+1]
    values={'%u':'0','%g':'0','%a':format(stat.S_IMODE(s.st_mode),'o'),'%s':str(s.st_size),'%F':'regular file' if p.is_file() else 'directory'}
    for k,v in values.items():fmt=fmt.replace(k,v)
    print(fmt)
elif name=='sha256sum':
    if args and args[0]=='-c':
        manifest=pathlib.Path(args[-1]);rc=0
        for line in manifest.read_text().splitlines():
            h,p=line.split(None,1);p=p.lstrip('*');actual=hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
            if h!=actual:rc=1
    else:
        if not args:print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest()+'  -')
        for a in args:print(hashlib.sha256(pathlib.Path(a).read_bytes()).hexdigest()+'  '+a)
elif name=='readlink':print(str(pathlib.Path(args[-1]).resolve()))
elif name=='blockdev':print(pathlib.Path(args[-1]).stat().st_size)
elif name=='chown' or name=='sync':pass
elif name=='dd':
    kv=dict(x.split('=',1) for x in args if '=' in x);p=pathlib.Path(kv['if']);dest=pathlib.Path(kv['of'])
    data=p.read_bytes()
    if 'count' in kv:data=data[:int(kv['count'])*int(kv.get('bs','512'))]
    if config.get('partial_boot_write') and 'boot' in dest.name:data=data[:max(1,len(data)//2)];rc=74
    if ('notrunc' in kv.get('conv','') or '/dev/block/' in str(dest)) and dest.exists():
        old=dest.read_bytes();data=data+old[len(data):]
    dest.write_bytes(data)
elif name=='cp':
    destination=pathlib.Path(args[-1]);modes=[]
    for source in args[:-1]:
        if source.startswith('-'):continue
        target=destination/pathlib.Path(source).name if destination.is_dir() else destination
        if target.exists():
            modes.append((target,stat.S_IMODE(target.stat().st_mode)));target.chmod(0o600)
    rc=subprocess.run(['/bin/cp',*args]).returncode
    if '-p' not in args:
        for target,mode in modes:target.chmod(mode)
elif name in ('mv','rm'):rc=subprocess.run(['/bin/'+name,*args]).returncode
else:raise RuntimeError('unexpected stub '+name)
if match and config.get('fail_after'):
    if config.get('kill_parent'):os.kill(os.getppid(),signal.SIGKILL)
    sys.exit(73)
sys.exit(rc)
'''


class AndroidFixture:
    def __init__(self, root: Path):
        root = root.resolve()
        self.root = root
        self.sources = {p.name: p.read_text() for p in SCRIPTS.glob('*.sh')}
        self.bin = root / 'bin'
        self.bin.mkdir()
        self.state = root / 'data/adb/carlife-on-box'
        self.runtime = root / 'data/local/carlife-native'
        self.runtime.parent.mkdir(parents=True)
        self.selector = root / 'data/adb/carlife-boot'
        self.module = root / 'data/adb/modules/carlife_on_box'
        self.boot = root / 'dev/block/by-name/boot_a'
        self.boot.parent.mkdir(parents=True)
        self.module.mkdir(parents=True)
        self.config = {'properties': {
            'ro.boot.slot_suffix': '_a',
            'ro.product.board': 'chelianyi-led',
            'ro.board.reverse.custom': 'chelianyi-led',
            'ro.boot.vbmeta.device_state': 'unlocked',
            'ro.board.platform': 'sm6350',
            'ro.build.version.sdk': '35',
            'ro.product.device': 'sm6350',
            'ro.boot.flash.locked': '0',
            'ro.boot.verifiedbootstate': 'orange',
        }}
        self.write_config()
        bid = root / 'proc/sys/kernel/random/boot_id'
        bid.parent.mkdir(parents=True)
        bid.write_text('00000000-0000-0000-0000-000000000001\n')
        command = self.bin / 'command-stub'
        command.write_text(COMMAND.replace('PYTHON', sys.executable, 1))
        command.chmod(0o755)
        for name in ('id', 'getprop', 'getenforce', 'magisk', 'stat', 'sha256sum', 'readlink',
                     'blockdev', 'chown', 'sync', 'dd', 'cp', 'mv', 'rm', 'busybox', 'timeout'):
            (self.bin / name).symlink_to(command.name)
        magisk_dir = root / 'data/adb/magisk'
        magisk_dir.mkdir(parents=True)
        (magisk_dir / 'busybox').symlink_to(self.bin / 'busybox')
        self.env = dict(os.environ, MAGISK_TEST_ROOT=str(root),
                        PATH=str(self.bin) + os.pathsep + os.environ.get('PATH', '/usr/bin:/bin'))

    def write_config(self):
        (self.root / 'stub-config.json').write_text(json.dumps(self.config))

    def commands(self):
        log = self.root / 'commands.jsonl'
        return [json.loads(x) for x in log.read_text().splitlines()] if log.exists() else []

    def copy_script(self, name, destination=None):
        """Map the full script, retaining all lifecycle branches and checks."""
        text = self.sources[name]
        # Mapping is test-side only. Long prefixes first avoids remapping an
        # already substituted path whose fixture directory contains '/tmp'.
        text = re.sub(r'(?<![A-Za-z0-9_])(/(?:data|dev|proc|sys|system|vendor)(?:/[A-Za-z0-9_.:/-]+)?)',
                      lambda m: str(self.root) + m.group(1), text)
        text = re.sub(r'^PATH=.*$', 'PATH=' + shlex.quote(self.env['PATH']), text, flags=re.M)
        text = text.replace('[ -b ', '[ -f ')
        destination = destination or self.module / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text)
        destination.chmod(0o700)
        return destination

    def run(self, action, module=None):
        self.write_config()
        manager = self.copy_script('manager.sh')
        return subprocess.run(['/bin/sh', str(manager), action, str(module or self.module)],
                              env=self.env, capture_output=True, text=True, timeout=10)


def digest(data):
    return hashlib.sha256(data).hexdigest()

def package(fixture, *, kind='install', name='current', base_id='none', marker='v1'):
    module = fixture.module if name == 'current' else fixture.root / 'data/adb/modules_update/carlife_on_box'
    module.mkdir(parents=True, exist_ok=True)
    original = b'ANDROID!fixture-original-boot\x00' * 128
    candidate = b'ANDROID!fixture-carlife-boot\x00\x00' * 128
    assert len(original) == len(candidate)
    oem = b'#!/system/bin/sh\nCHANNEL=sd_msm6350\n'
    init = b'on early-init\n    start sd-reverse\nservice sd-reverse /system/bin/sd-reverse.sh\n'
    for path, value in [('system/bin/sd-reverse.sh', oem), ('system/etc/init/hw/init.rc', init)]:
        p = fixture.root / path
        p.parent.mkdir(parents=True, exist_ok=True)
        if p.exists():p.chmod(0o600)
        p.write_bytes(value)
        p.chmod(0o755 if path.endswith('.sh') else 0o644)
    fixture.config['properties']['ro.build.fingerprint'] = 'fixture/ultra1/android15'
    files = {
        'seed/runtime/current': (b'boot-native-v1-20261014\n', 0o600),
        'seed/runtime/owner': (b'carlife-native-init-owner-v1\n', 0o600),
        'seed/runtime/runtime-control.sh': (b'#!/system/bin/sh\nexit 91\n', 0o500),
        'seed/runtime/versions/boot-native-v1-20261014/md.jar': (marker.encode(), 0o400),
        'seed/runtime/versions/boot-native-v1-20261014/runtime-controller.sh': (b'#!/system/bin/sh\nexit 92\n', 0o500),
        'seed/runtime/versions/boot-native-v1-20261014/uevent-watch': (b'fixture-observer', 0o500),
        'seed/selector/schema': (b'1\n', 0o600),
        'seed/selector/selected': (b'carlife\n', 0o600),
        'seed/selector/selector-control.sh': (b'#!/system/bin/sh\nexit 93\n', 0o500),
        'seed/selector/selector-daemon.sh': (b'#!/system/bin/sh\nexit 94\n', 0o500),
        'seed/selector/selector-daemon.jar': (b'fixture-selector', 0o500),
    }
    version_prefix = 'seed/runtime/versions/boot-native-v1-20261014/'
    files[version_prefix + 'payload.sha256'] = (''.join(f'{digest(value)}  {name[len(version_prefix):]}\n' for name, (value, mode) in sorted(files.items()) if name.startswith(version_prefix)).encode(), 0o400)
    dirs = {p: 0o700 for p in ('seed/runtime', 'seed/runtime/logs', 'seed/runtime/runtime',
                             'seed/runtime/versions', 'seed/runtime/versions/boot-native-v1-20261014',
                             'seed/selector', 'seed/selector/logs')}
    for path, mode in dirs.items():
        p = module / path
        p.mkdir(parents=True, exist_ok=True)
        p.chmod(mode)
    for path, (value, mode) in files.items():
        p = module / path
        p.write_bytes(value)
        p.chmod(mode)
    seeds = list(files)
    for script in ('manager.sh', 'customize.sh', 'post-fs-data.sh', 'action.sh', 'uninstall.sh'):
        if (SCRIPTS / script).exists():
            p = fixture.copy_script(script, module / script)
            files[script] = p.read_bytes(), 0o755
    if kind == 'install':
        (module / 'boot').mkdir(exist_ok=True)
        for n, value in [('original', original), ('candidate', candidate)]:
            files['boot/' + n + '.img'] = value, 0o400
    files['payload-files.tsv'] = (''.join(f'{digest(files[n][0])}\t{files[n][1]:o}\t{n}\n' for n in sorted(seeds)).encode(), 0o600)
    files['payload-dirs.tsv'] = (''.join(f'{mode:o}\t{n}\n' for n, mode in sorted(dirs.items(), key=lambda x: (x[0].count('/'), x[0]))).encode(), 0o600)
    payload_id = digest(marker.encode())
    values = dict(FORMAT='1', MODULE_ID='carlife_on_box', PACKAGE_KIND=kind,
                  PROFILE='tbox-ultra-1-sm6350-v1', SLOT_SUFFIX='_a', BOARD='chelianyi-led',
                  CHANNEL='sd_msm6350', SDK='35', MAGISK_VERSION_CODE='30700',
                  EXPECTED_FINGERPRINT="'fixture/ultra1/android15'", RUNTIME_VERSION='boot-native-v1-20261014',
                  BOOT_ORIGINAL_SHA256=digest(original), BOOT_CANDIDATE_SHA256=digest(candidate),
                  BOOT_SIZE=str(len(original)), OEM_SHA256=digest(oem), INIT_SHA256=digest(init),
                  RUNTIME_ENTRY_SHA256=digest(files['seed/runtime/runtime-control.sh'][0]),
                  SELECTOR_ENTRY_SHA256=digest(files['seed/selector/selector-daemon.sh'][0]),
                  PAYLOAD_ID=payload_id, BASE_PAYLOAD_ID=base_id)
    files['manifest.env'] = (''.join(k + '=' + v + '\n' for k, v in values.items()).encode(), 0o600)
    files['module.prop'] = (b'id=carlife_on_box\n', 0o644)
    files['manifest.json'] = (b'{}\n', 0o600)
    files['skip_mount'] = (b'', 0o644)
    files['module.sha256'] = (''.join(f'{digest(value)}  {n}\n' for n, (value, _) in sorted(files.items()) if n != 'customize.sh').encode(), 0o600)
    files['payload.sha256'] = (''.join(f'{digest(value)}  {n}\n' for n, (value, _) in sorted(files.items())).encode(), 0o600)
    for n, (value, mode) in files.items():
        p = module / n
        p.parent.mkdir(parents=True, exist_ok=True)
        if p.exists():p.chmod(0o600)
        p.write_bytes(value)
        p.chmod(mode)
    return module, values, original, candidate


class MagiskLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.f = AndroidFixture(Path(self.temp.name))
        self.module, self.values, self.original, self.candidate = package(self.f)
        self.f.boot.write_bytes(self.original)

    def success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def install(self):
        return self.success(self.f.run('install'))

    def version(self):
        return self.f.runtime / 'versions/boot-native-v1-20261014'

    def stage(self, marker='v2'):
        module, values, _, _ = package(self.f, kind='update', name='staged',
                                      base_id=self.values['PAYLOAD_ID'], marker=marker)
        self.success(self.f.run('install', module))
        return module, values

    def promote(self, staged):
        shutil.rmtree(self.f.module)
        staged.rename(self.f.module)
        (self.f.module / 'customize.sh').unlink(missing_ok=True)
        boot_id = self.f.root / 'proc/sys/kernel/random/boot_id'
        boot_id.write_text('00000000-0000-0000-0000-000000000002\n')

    def boot_writes(self):
        return [row for row in self.f.commands() if row[0] == 'dd' and 'of=' + str(self.f.boot) in row]

    def test_fresh_install_seeds_before_one_boot_write_and_preserves_original(self):
        self.install()
        self.assertEqual(self.f.boot.read_bytes(), self.candidate)
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.assertEqual((self.f.state / 'phase').read_text(), 'installed\n')
        self.assertEqual((self.f.selector / 'selected').read_text(), 'carlife\n')
        self.assertEqual(len(self.boot_writes()), 1)
        self.assertEqual((self.f.state / 'original.img').stat().st_mode & 0o777, 0o400)
        self.assertEqual(self.f.runtime.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.f.runtime / 'runtime-control.sh').stat().st_mode & 0o777, 0o500)
        self.assertEqual((self.version() / 'md.jar').stat().st_mode & 0o777, 0o400)
        commands = self.f.commands()
        boot_write = commands.index(self.boot_writes()[0])
        seed_last = max(i for i, row in enumerate(commands) if row[0] == 'chown' and
                        (str(self.f.runtime) in row[-1] or str(self.f.selector) in row[-1]))
        self.assertLess(seed_last, boot_write)
        self.assertFalse((self.f.module / 'remove').exists())

    def test_changed_current_boot_is_refused_before_seed_or_flash(self):
        self.f.boot.write_bytes(b'X' * len(self.original))
        result = self.f.run('install')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('nothing flashed', result.stderr)
        self.assertEqual(self.boot_writes(), [])
        self.assertFalse(self.f.runtime.exists())
        self.assertFalse((self.f.state / 'original.img').exists())
        self.assertEqual(self.f.boot.read_bytes(), b'X' * len(self.original))

    def test_changed_device_is_rejected_before_state_created(self):
        for prop, value in [('ro.boot.slot_suffix', '_b'), ('ro.build.fingerprint', 'other'),
                            ('ro.board.reverse.custom', 'other')]:
            with self.subTest(property=prop):
                old = self.f.config['properties'][prop]
                self.f.config['properties'][prop] = value
                self.assertNotEqual(self.f.run('install').returncode, 0)
                self.assertFalse(self.f.state.exists())
                self.assertEqual(self.boot_writes(), [])
                self.f.config['properties'][prop] = old

    def test_corrupt_package_does_not_seed_or_flash(self):
        path = self.module / 'seed/runtime/versions/boot-native-v1-20261014/md.jar'
        path.chmod(0o600); path.write_bytes(b'corrupt')
        result = self.f.run('install')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('package checksum', result.stderr)
        self.assertFalse(self.f.state.exists())
        self.assertEqual(self.boot_writes(), [])

    def test_existing_unowned_runtime_is_preserved(self):
        self.f.runtime.mkdir(parents=True)
        foreign = self.f.runtime / 'owner-data'
        foreign.write_bytes(b'keep me')
        result = self.f.run('install')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(foreign.read_bytes(), b'keep me')
        self.assertFalse(self.f.selector.exists())
        self.assertEqual(self.boot_writes(), [])

    def test_failed_boot_write_retains_original_and_prepared_state(self):
        self.f.config.update(fail_command='dd', fail_contains=['of=' + str(self.f.boot)])
        result = self.f.run('install')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.f.boot.read_bytes(), self.original)
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.assertEqual((self.f.state / 'phase').read_text(), 'prepared\n')
        self.assertTrue((self.f.state / 'boot-write.intent').is_file())
        self.assertTrue((self.f.runtime / '.installer-owned').is_file())
        self.assertEqual(list(self.f.state.glob('lock.*')), [])

    def test_partial_boot_write_is_never_reported_installed_and_backup_survives(self):
        self.f.config['partial_boot_write'] = True
        result = self.f.run('install')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.f.boot.stat().st_size, len(self.original))
        self.assertNotEqual(self.f.boot.read_bytes(), self.original)
        self.assertNotEqual(self.f.boot.read_bytes(), self.candidate)
        self.assertEqual((self.f.state / 'phase').read_text(), 'prepared\n')
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.f.config = {'properties': self.f.config['properties']}
        self.assertNotEqual(self.f.run('activate').returncode, 0)
        self.assertNotEqual(self.f.run('rollback').returncode, 0)
        self.assertEqual(len(self.boot_writes()), 1)
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)

    def test_written_boot_interruption_commits_from_readback_on_next_boot(self):
        self.f.config.update(fail_command='dd', fail_contains=['of=' + str(self.f.boot)],
                             fail_after=True)
        result = self.f.run('install')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.f.boot.read_bytes(), self.candidate)
        self.assertEqual((self.f.state / 'phase').read_text(), 'prepared\n')
        self.f.config = {'properties': self.f.config['properties']}
        self.success(self.f.run('activate'))
        self.assertEqual((self.f.state / 'phase').read_text(), 'installed\n')
        self.assertEqual(len(self.boot_writes()), 1)

    def test_update_is_deferred_preserves_selection_logs_and_original_boot(self):
        self.install()
        original_stat = (self.f.state / 'original.img').stat()
        (self.f.selector / 'selected').write_text('carplay\n')
        (self.f.runtime / 'logs/owner.log').write_bytes(b'user log')
        staged, values = self.stage()
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v1')
        self.assertEqual((self.f.state / 'pending').read_text(), values['PAYLOAD_ID'] + '\n')
        self.assertEqual(self.f.boot.read_bytes(), self.candidate)
        self.promote(staged)
        self.success(self.f.run('activate'))
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v2')
        self.assertEqual((self.f.state / 'previous-version/md.jar').read_bytes(), b'v1')
        self.assertEqual((self.f.selector / 'selected').read_text(), 'carplay\n')
        self.assertEqual((self.f.runtime / 'logs/owner.log').read_bytes(), b'user log')
        self.assertEqual((self.f.state / 'original.img').stat().st_mtime_ns, original_stat.st_mtime_ns)
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.assertFalse((self.f.state / 'pending').exists())
        self.assertEqual(len(self.boot_writes()), 1)

    def test_two_successive_updates_retain_the_first_boot_backup(self):
        self.install()
        for marker in ('v2', 'v3'):
            staged, _ = self.stage(marker)
            self.promote(staged)
            self.success(self.f.run('activate'))
            self.assertEqual((self.version() / 'md.jar').read_bytes(), marker.encode())
            self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.assertEqual((self.f.state / 'previous-version/md.jar').read_bytes(), b'v2')
        self.assertEqual(len(self.boot_writes()), 1)

    def test_update_wrong_boot_preserves_running_payload_and_no_pending(self):
        self.install()
        staged, _, _, _ = package(self.f, kind='update', name='staged',
                                  base_id=self.values['PAYLOAD_ID'], marker='v2')
        self.f.boot.write_bytes(b'X' * len(self.candidate))
        result = self.f.run('install', staged)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v1')
        self.assertFalse((self.f.state / 'pending').exists())
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.assertEqual(len(self.boot_writes()), 1)

    def test_empty_lock_from_previous_boot_does_not_block_update_activation(self):
        self.install()
        staged, _ = self.stage()
        old_lock = self.f.state / 'lock.00000000-0000-0000-0000-000000000001'
        old_lock.mkdir(mode=0o700)
        self.promote(staged)
        self.success(self.f.run('activate'))
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v2')
        self.assertEqual((self.f.state / 'phase').read_text(), 'installed\n')
        self.assertTrue(old_lock.is_dir(), 'old boot evidence need not be deleted to recover')
        self.assertFalse((self.f.state / 'lock.00000000-0000-0000-0000-000000000002').exists())
        self.assertEqual(len(self.boot_writes()), 1)

    def test_current_boot_lock_refuses_a_competing_transaction_without_mutation(self):
        self.install()
        current_lock = self.f.state / 'lock.00000000-0000-0000-0000-000000000001'
        current_lock.mkdir(mode=0o700)
        before = (self.f.state / 'phase').read_bytes()
        result = self.f.run('rollback')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('another transaction in this boot', result.stderr)
        self.assertTrue(current_lock.is_dir())
        self.assertEqual((self.f.state / 'phase').read_bytes(), before)
        self.assertEqual(self.f.boot.read_bytes(), self.candidate)
        self.assertTrue(self.f.runtime.exists())
        self.assertEqual(len(self.boot_writes()), 1)

    def test_interrupted_update_after_old_rename_resumes_next_boot(self):
        self.install()
        staged, _ = self.stage()
        self.promote(staged)
        self.f.config.update(fail_command='mv', fail_contains=[str(self.version()), 'previous-version'],
                             fail_after=True, kill_parent=True)
        result = self.f.run('activate')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.version().exists())
        self.assertEqual((self.f.state / 'previous-version/md.jar').read_bytes(), b'v1')
        self.assertEqual((self.f.state / 'phase').read_text(), 'updating\n')
        self.f.config = {'properties': self.f.config['properties']}
        (self.f.root / 'proc/sys/kernel/random/boot_id').write_text('00000000-0000-0000-0000-000000000003\n')
        self.success(self.f.run('activate'))
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v2')
        self.assertFalse((self.f.state / 'pending').exists())
        self.assertEqual(len(self.boot_writes()), 1)

    def test_interrupted_update_after_new_rename_resumes_next_boot(self):
        self.install()
        staged, _ = self.stage()
        self.promote(staged)
        self.f.config.update(fail_command='mv', fail_contains=['.installer-next', str(self.version())],
                             fail_after=True, kill_parent=True)
        result = self.f.run('activate')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v2')
        self.assertEqual((self.f.state / 'phase').read_text(), 'updating\n')
        self.f.config = {'properties': self.f.config['properties']}
        (self.f.root / 'proc/sys/kernel/random/boot_id').write_text('00000000-0000-0000-0000-000000000003\n')
        self.success(self.f.run('activate'))
        self.assertFalse((self.f.state / 'pending').exists())
        self.assertEqual((self.f.state / 'previous-version/md.jar').read_bytes(), b'v1')
        self.assertEqual(len(self.boot_writes()), 1)

    def test_rollback_is_exact_deferred_cleanup_and_backup_survives_uninstall(self):
        self.install()
        outside = self.f.root / 'data/local/unrelated'
        outside.write_bytes(b'preserve')
        self.success(self.f.run('rollback'))
        self.assertEqual(self.f.boot.read_bytes(), self.original)
        self.assertEqual((self.f.state / 'phase').read_text(), 'rollback\n')
        self.assertTrue((self.f.module / 'remove').is_file())
        self.assertTrue(self.f.runtime.exists(), 'live owner payload must remain until boot')
        self.success(self.f.run('uninstall'))
        self.assertFalse(self.f.runtime.exists())
        self.assertFalse(self.f.selector.exists())
        self.assertEqual(outside.read_bytes(), b'preserve')
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.assertTrue((self.f.state / 'manager.sh').is_file())
        self.assertEqual(len(self.boot_writes()), 2)

    def test_rollback_refuses_foreign_boot_without_overwriting_it(self):
        self.install()
        self.f.boot.write_bytes(b'X' * len(self.candidate))
        result = self.f.run('rollback')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('will not overwrite', result.stderr)
        self.assertEqual(self.f.boot.read_bytes(), b'X' * len(self.candidate))
        self.assertTrue(self.f.runtime.exists())
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.assertEqual(len(self.boot_writes()), 1)

    def test_rollback_clears_staged_update_to_prevent_resurrection(self):
        self.install()
        staged, _ = self.stage()
        self.success(self.f.run('rollback'))
        self.assertFalse((self.f.state / 'pending').exists())
        self.assertFalse(staged.exists())
        self.assertEqual(self.f.boot.read_bytes(), self.original)
        self.success(self.f.run('activate'))
        self.assertFalse(self.f.runtime.exists())
        self.assertEqual(self.f.boot.read_bytes(), self.original)

    def test_reinstall_resets_old_rollback_boot_identity_before_live_rollback(self):
        self.install()
        self.success(self.f.run('uninstall'))
        self.assertFalse(self.f.runtime.exists())
        self.assertEqual(self.f.boot.read_bytes(), self.original)
        shutil.rmtree(self.f.module)
        package(self.f)
        (self.f.root / 'proc/sys/kernel/random/boot_id').write_text('00000000-0000-0000-0000-000000000002\n')
        self.install()
        self.assertFalse((self.f.state / 'rollback-boot-id').exists())
        self.assertFalse((self.f.state / 'cleanup').exists())
        self.success(self.f.run('rollback'))
        self.assertEqual(self.f.boot.read_bytes(), self.original)
        self.assertTrue(self.f.runtime.exists(), 'new live owner cannot inherit a previous boot cleanup authorization')
        self.assertTrue(self.f.selector.exists())
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)

    def test_uninstall_hook_recovers_without_module_payload_directory(self):
        self.install()
        hook = self.f.copy_script('uninstall.sh', self.f.root / 'uninstall-test.sh')
        shutil.rmtree(self.f.module)
        result = subprocess.run(['/bin/sh', str(hook)], env=self.f.env,
                                capture_output=True, text=True, timeout=10)
        self.success(result)
        self.assertEqual(self.f.boot.read_bytes(), self.original)
        self.assertFalse(self.f.runtime.exists())
        self.assertFalse(self.f.selector.exists())
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)
        self.assertIn('Original boot restored', (self.f.state / 'lifecycle.log').read_text())

    def test_incomplete_copy_staging_rebuilt_without_changing_running_generation(self):
        self.install()
        staged, _ = self.stage()
        next_version = self.f.runtime / 'versions/.installer-next'
        next_version.mkdir(mode=0o700)
        (next_version / 'md.jar').write_bytes(b'interrupted copy')
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v1')
        self.promote(staged)
        self.success(self.f.run('activate'))
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v2')
        self.assertEqual((self.f.state / 'previous-version/md.jar').read_bytes(), b'v1')
        self.assertFalse(next_version.exists())

    def test_power_loss_after_pending_removed_completes_updating_phase(self):
        self.install()
        staged, _ = self.stage()
        self.promote(staged)
        self.f.config.update(fail_command='rm', fail_contains=[str(self.f.state / 'pending')],
                             fail_after=True, kill_parent=True)
        result = self.f.run('activate')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.f.state / 'pending').exists())
        self.assertEqual((self.f.state / 'phase').read_text(), 'updating\n')
        self.assertEqual((self.version() / 'md.jar').read_bytes(), b'v2')
        self.f.config = {'properties': self.f.config['properties']}
        (self.f.root / 'proc/sys/kernel/random/boot_id').write_text('00000000-0000-0000-0000-000000000003\n')
        self.success(self.f.run('activate'))
        self.assertEqual((self.f.state / 'phase').read_text(), 'installed\n')
        self.assertFalse((self.f.state / 'pending').exists())
        self.assertEqual(len(self.boot_writes()), 1)

    def test_damaged_original_backup_refuses_rollback_without_boot_write(self):
        self.install()
        backup = self.f.state / 'original.img'
        backup.chmod(0o600); backup.write_bytes(b'corrupt'); backup.chmod(0o400)
        result = self.f.run('rollback')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('original backup damaged', result.stderr)
        self.assertEqual(self.f.boot.read_bytes(), self.candidate)
        self.assertTrue(self.f.runtime.exists())
        self.assertEqual(len(self.boot_writes()), 1)

    def test_uninstall_preserves_foreign_runtime_and_reports_it(self):
        self.install()
        (self.f.runtime / '.installer-owned').write_bytes(b'not this installer\n')
        result = self.f.run('uninstall')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('foreign data retained', result.stderr)
        self.assertTrue(self.f.runtime.exists())
        self.assertEqual(self.f.boot.read_bytes(), self.original)
        self.assertEqual((self.f.state / 'original.img').read_bytes(), self.original)


if __name__ == '__main__':
    unittest.main()
