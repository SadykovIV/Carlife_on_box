#!/usr/bin/env python3
"""Build a source-only SM6350 runtime/selector seed. No device or flashing operations."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--md-jar', type=Path, required=True)
    p.add_argument('--android-jar', type=Path, required=True)
    p.add_argument('--r8-jar', type=Path, required=True)
    p.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    p.add_argument('--clang', default='clang')
    p.add_argument('--ld-lld', default='ld.lld')
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.output.exists():
        p.error('output must be a new directory')
    for path in (a.md_jar, a.android_jar, a.r8_jar):
        if not path.is_file(): p.error('missing input: ' + str(path))
    java = str(a.java_home/'bin/java') if a.java_home else shutil.which('java')
    javac = str(a.java_home/'bin/javac') if a.java_home else shutil.which('javac')
    clang = shutil.which(a.clang)
    linker = shutil.which(a.ld_lld)
    if not all((java, javac, clang, linker)): p.error('Java, javac, Clang and LLD are required')
    out = a.output.resolve(); out.mkdir(parents=True)
    def run(args): subprocess.run(list(map(str, args)), check=True, cwd=ROOT)
    observer = out/'uevent-watch'
    run([clang, '--target=aarch64-linux-android21', '-Os', '-ffreestanding',
         '-fno-stack-protector', '-fno-builtin', '-fno-unwind-tables',
         '-fno-asynchronous-unwind-tables', '-nostdlib', '-fuse-ld='+linker,
         '-static', '-Wl,-e,_start', '-Wl,--build-id=none', '-Wl,-z,noexecstack',
         ROOT/'tools/boot_native/observer/uevent-watch.c', '-o', observer])
    # Reuse the original deterministic runtime packaging contract, with local build inputs.
    spec = importlib.util.spec_from_file_location('package_runtime', ROOT/'tools/boot_native/build_package.py')
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    stage = out/'runtime-inputs'; (stage/'runtime').mkdir(parents=True)
    (stage/'observer/build').mkdir(parents=True); (stage/'md/build').mkdir(parents=True)
    for n in ('runtime-control.sh', 'runtime-controller.sh'):
        shutil.copyfile(ROOT/'tools/boot_native/runtime'/n, stage/'runtime'/n)
    shutil.copyfile(observer, stage/'observer/build/uevent-watch')
    shutil.copyfile(a.md_jar, stage/'md/build/md-boot-native.jar')
    mod.HERE = stage
    runtime = mod.build(out/'runtime')
    selector = ROOT/'tools/boot_native/selector'
    release = out/'selector'; release.mkdir()
    classes = release/'classes'; classes.mkdir()
    sources = sorted((selector/'java').rglob('*.java'))
    run([javac, '--release', '8', '-cp', a.android_jar.resolve(), '-d', classes, *sources])
    jar = release/'selector-classes.jar'
    with zipfile.ZipFile(jar, 'w') as z:
        for f in sorted(classes.rglob('*.class')):
            z.writestr(zipfile.ZipInfo(f.relative_to(classes).as_posix(), (2026,1,1,0,0,0)), f.read_bytes())
    dex = release/'dex'; dex.mkdir()
    run([java, '-cp', a.r8_jar.resolve(), 'com.android.tools.r8.D8', '--min-api', '28',
         '--lib', a.android_jar.resolve(), '--output', dex, jar])
    with zipfile.ZipFile(release/'selector-daemon.jar', 'w') as z:
        z.writestr(zipfile.ZipInfo('classes.dex', (2026,1,1,0,0,0)), (dex/'classes.dex').read_bytes())
    shutil.copyfile(selector/'selector-control.sh', release/'selector-control.sh')
    helper_sha = sha(release/'selector-control.sh'); jar_sha = sha(release/'selector-daemon.jar')
    entry = '''#!/system/bin/sh
PATH=/system/bin:/system/xbin:/vendor/bin
export PATH
unset ENV BASH_ENV ASH_STANDALONE
umask 077
B=/data/adb/carlife-boot
[ "$(id -u)" = 0 ] || exit 71
[ ! -L "$B" ] && [ "$(readlink -f "$B")" = "$B" ] && [ "$(stat -c '%u:%g:%a' "$B")" = 0:0:700 ] || exit 71
for F in selector-daemon.jar selector-control.sh; do
 [ -f "$B/$F" ] && [ ! -L "$B/$F" ] && [ "$(stat -c '%u:%g:%a' "$B/$F")" = 0:0:500 ] || exit 71
done
[ "$(sha256sum "$B/selector-control.sh" | cut -d' ' -f1)" = "@HELPER@" ] || exit 71
[ "$(sha256sum "$B/selector-daemon.jar" | cut -d' ' -f1)" = "@JAR@" ] || exit 71
export CLASSPATH="$B/selector-daemon.jar"
exec /system/bin/app_process /system/bin org.carlife.selector.SelectorDaemon @HELPER@
'''.replace('@HELPER@', helper_sha).replace('@JAR@', jar_sha)
    (release/'selector-daemon.sh').write_text(entry)
    payload = {n:sha(release/n) for n in ('selector-daemon.sh','selector-daemon.jar','selector-control.sh')}
    (release/'manifest.json').write_text(json.dumps({'payload':payload, 'oem_apks_included':False},indent=2)+'\n')
    result = {'status':'SOURCE_BUILD_ONLY_NOT_DEVICE_TESTED','runtime_sha256':runtime['archive_sha256'],
              'observer_sha256':sha(observer),'md_sha256':sha(a.md_jar),'selector':payload,
              'device_operations':0, 'requires_board_integration':True}
    (out/'BUILD.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))

if __name__ == '__main__': main()
