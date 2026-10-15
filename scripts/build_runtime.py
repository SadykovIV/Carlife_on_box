#!/usr/bin/env python3
"""Build a source-only SM6350 runtime/selector seed. No device or flashing operations."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def runtime_version(value):
    """One ASCII path component, also safe in the controller's unquoted shell anchors."""
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', value):
        raise argparse.ArgumentTypeError('runtime version must be 1..64 ASCII letters, digits, dot, underscore or hyphen, starting with a letter or digit')
    return value

def replace_unique(text, old, new, label):
    if text.count(old) != 1:
        raise ValueError(label + ' must occur exactly once in staged source')
    return text.replace(old, new, 1)

def configure_controller_audio(text, audio_source):
    """Change only the staged MD launch line; the identity matcher is left untouched."""
    if audio_source not in ('off', 'remote-submix', 'split'):
        raise ValueError('unsupported audio source')
    if audio_source == 'off':
        return text
    launch = 'exec /system/bin/app_process /system/bin org.carlife.hulab.MdMain'
    lines = text.splitlines(keepends=True)
    candidates = [i for i, line in enumerate(lines) if launch in line]
    if len(candidates) != 1:
        raise ValueError('MD launch line must occur exactly once in staged controller')
    i = candidates[0]
    if lines[i].count(launch) != 1 or '--audio-source' in lines[i]:
        raise ValueError('MD launch line has duplicate command or an existing audio option')
    lines[i] = replace_unique(lines[i], '--transport native-usb',
                              '--transport native-usb --audio-source '+audio_source, 'native transport launch option')
    return ''.join(lines)

def video_mode(value):
    if value in ('720p', 'auto'):
        return value
    if re.fullmatch(r'[0-9]{3,4}x[0-9]{3,4}', value):
        w, h = map(int, value.split('x'))
        if 320 <= w <= 2560 and 180 <= h <= 1440 and not (w % 2 or h % 2) and ((w+15)//16)*((h+15)//16) <= 8704:
            return value
    raise argparse.ArgumentTypeError('video mode must be 720p, auto, or an even AVC level <=4.2 WIDTHxHEIGHT')

def configure_controller_services(text, services, mode):
    if services not in ('off', 'on'):
        raise ValueError('unsupported native services mode')
    mode = video_mode(mode)
    extra = (' --native-services on' if services == 'on' else '') + (' --video-mode '+mode if mode != '720p' else '')
    if not extra:
        return text
    launch = 'exec /system/bin/app_process /system/bin org.carlife.hulab.MdMain'
    lines = text.splitlines(keepends=True)
    candidates = [i for i, line in enumerate(lines) if launch in line]
    if len(candidates) != 1:
        raise ValueError('MD launch line must occur exactly once in staged controller')
    i = candidates[0]
    if '--native-services' in lines[i] or '--video-mode' in lines[i]:
        raise ValueError('MD launch line already specifies native services/video mode')
    lines[i] = replace_unique(lines[i], '--transport native-usb', '--transport native-usb'+extra, 'native transport launch option')
    return ''.join(lines)

def configure_runtime_inputs(control, controller, audio_source, version, source_version, native_services='off', mode='720p'):
    """Prepare version-consistent staging files without changing source/baseline files."""
    version = runtime_version(version)
    source_version = runtime_version(source_version)
    controller = configure_controller_audio(controller, audio_source)
    controller = configure_controller_services(controller, native_services, mode)
    if version != source_version:
        control = replace_unique(control, 'V=$P/versions/' + source_version + '\n',
                                 'V=$P/versions/' + version + '\n', 'resolver version path')
        control = replace_unique(control, 'exact_lf "$P/current" ' + source_version + ' || exit 70',
                                 'exact_lf "$P/current" ' + version + ' || exit 70', 'resolver current-version check')
        controller = replace_unique(controller, '[ "$V" = "$P/versions/' + source_version + '" ]',
                                    '[ "$V" = "$P/versions/' + version + '" ]', 'controller version check')
    return control, controller

def argument_parser(default_version):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--md-jar', type=Path, required=True)
    p.add_argument('--android-jar', type=Path, required=True)
    p.add_argument('--r8-jar', type=Path, required=True)
    p.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    p.add_argument('--clang', default='clang')
    p.add_argument('--ld-lld', default='ld.lld')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--audio-source', choices=('off', 'remote-submix', 'split'), default='off',
                   help='MD audio source for this staged runtime; off preserves the baseline launch line')
    p.add_argument('--native-services', choices=('off', 'on'), default='off', help='Media metadata, Yandex notification navigation, HU microphone/recognizer')
    p.add_argument('--video-mode', type=video_mode, default='720p', help='720p baseline, auto HU hint with capability fallback, or explicit WIDTHxHEIGHT')
    p.add_argument('--runtime-version', type=runtime_version, default=default_version,
                   help='Separate runtime version directory (default: %(default)s); never installs/selects it')
    return p

def main():
    spec = importlib.util.spec_from_file_location('package_runtime', ROOT/'tools/boot_native/build_package.py')
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    p = argument_parser(mod.VERSION)
    a = p.parse_args()
    if a.output.exists():
        p.error('output must be a new directory')
    for path in (a.md_jar, a.android_jar, a.r8_jar):
        if not path.is_file(): p.error('missing input: ' + str(path))
    try:
        control, controller = configure_runtime_inputs(
            (ROOT/'tools/boot_native/runtime/runtime-control.sh').read_bytes().decode('utf-8'),
            (ROOT/'tools/boot_native/runtime/runtime-controller.sh').read_bytes().decode('utf-8'),
            a.audio_source, a.runtime_version, mod.VERSION, a.native_services, a.video_mode)
    except ValueError as error:
        p.error(str(error))
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
    stage = out/'runtime-inputs'; (stage/'runtime').mkdir(parents=True)
    (stage/'observer/build').mkdir(parents=True); (stage/'md/build').mkdir(parents=True)
    (stage/'runtime/runtime-control.sh').write_bytes(control.encode('utf-8'))
    (stage/'runtime/runtime-controller.sh').write_bytes(controller.encode('utf-8'))
    shutil.copyfile(observer, stage/'observer/build/uevent-watch')
    shutil.copyfile(a.md_jar, stage/'md/build/md-boot-native.jar')
    mod.HERE = stage
    mod.VERSION = a.runtime_version
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
              'audio_source':a.audio_source,'runtime_version':a.runtime_version,
              'native_services':a.native_services,'video_mode':a.video_mode,
              'observer_sha256':sha(observer),'md_sha256':sha(a.md_jar),'selector':payload,
              'device_operations':0, 'requires_board_integration':True}
    (out/'BUILD.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))

if __name__ == '__main__': main()
