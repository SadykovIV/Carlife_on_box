#!/usr/bin/env python3
"""Patch the two exact supported OEM APKs using owner-supplied inputs and signing key.

Offline only. No APK, key, SDK or proprietary dependency is distributed/downloaded.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'tools/boot_native/selector'
APK_CERT = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'
INPUTS = [
    ('MainAiBox', 'main_apk', '09b2f141f63a7ae016a1a7a43dd52be853b044dfb985d058c348fdaca3714b85', 'classes.dex', 1),
    ('SudingSettings', 'settings_apk', '8df7ba4a1785c472fa365336597eadeefe0958ea205920359a1833d8f78230fb', 'classes2.dex', 3),
]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['main-apk', 'settings-apk', 'android-jar', 'r8-jar', 'dexlib-jar', 'guava-jar', 'apksig-jar', 'signing-reference', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'), help='JDK 17 home, or JAVA_HOME')
    args = parser.parse_args()
    if not args.java_home:
        parser.error('--java-home or JAVA_HOME is required')
    java = args.java_home.resolve() / 'bin'
    suffix = '.exe' if os.name == 'nt' else ''
    java_bin, javac_bin = java / ('java' + suffix), java / ('javac' + suffix)
    required = [java_bin, javac_bin, args.main_apk, args.settings_apk, args.android_jar, args.r8_jar,
                args.dexlib_jar, args.guava_jar, args.apksig_jar,
                args.signing_reference / 'platform.x509.pem', args.signing_reference / 'platform.pk8']
    for path in required:
        if not path.is_file():
            raise ValueError('Missing required input: ' + path.name)
    # Verify the OEM inputs before compiling or creating any patched output.
    for name, arg, expected, _target, _count in INPUTS:
        if sha(getattr(args, arg)) != expected:
            raise ValueError('Unexpected original APK hash: ' + name)
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()):
        raise ValueError('Use an empty output directory to avoid mixing different builds')
    out.mkdir(parents=True, exist_ok=True)
    records = []
    env = dict(os.environ)
    env['JAVA_HOME'] = str(args.java_home.resolve())
    env['PATH'] = str(java) + os.pathsep + env.get('PATH', '')

    def run(command, label):
        result = subprocess.run([str(x) for x in command], cwd=ROOT, env=env, text=True, capture_output=True)
        (out / (label + '.stdout.txt')).write_text(result.stdout)
        (out / (label + '.stderr.txt')).write_text(result.stderr)
        records.append({'label': label, 'exit_code': result.returncode})
        (out / 'build-log.json').write_text(json.dumps(records, indent=2) + '\n')
        if result.returncode:
            raise RuntimeError(label + '\n' + result.stdout + result.stderr)

    classes = out / 'classes'
    classes.mkdir()
    run([javac_bin, '--release', '8', '-cp', args.android_jar.resolve(), '-d', classes,
         *sorted((SRC / 'java').rglob('*.java'))], 'compile-selector')
    jar = out / 'selector-classes.jar'
    with zipfile.ZipFile(jar, 'w') as archive:
        for path in sorted(classes.rglob('*.class')):
            archive.write(path, path.relative_to(classes))
    dexout = out / 'dex'
    dexout.mkdir()
    run([java_bin, '-cp', args.r8_jar.resolve(), 'com.android.tools.r8.D8', '--min-api', '28',
         '--lib', args.android_jar.resolve(), '--output', dexout, jar], 'd8-selector')
    if sorted(path.name for path in dexout.iterdir()) != ['classes.dex']:
        raise ValueError('Expected exactly one selector DEX')
    cp = os.pathsep.join(str(p.resolve()) for p in [args.dexlib_jar, args.guava_jar, args.apksig_jar])
    buildclasses = out / 'build-classes'
    buildclasses.mkdir()
    run([javac_bin, '--release', '8', '-cp', cp, '-d', buildclasses,
         *sorted((SRC / 'build-tools').glob('*.java'))], 'compile-build-tools')
    build_cp = str(buildclasses) + os.pathsep + cp
    apk_outputs = []
    for name, arg, expected, target, count in INPUTS:
        original = getattr(args, arg).resolve()
        with zipfile.ZipFile(original) as archive:
            (out / (name + '-original.dex')).write_bytes(archive.read(target))
        modified = out / (name + '-modified.dex')
        run([java_bin, '-cp', build_cp, 'PatchDex', out / (name + '-original.dex'), modified, count], 'patch-' + name)
        unsigned = out / (name + '-selector-unsigned.apk')
        unchanged = []
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(unsigned, 'w') as destination:
            dexnames = [n for n in source.namelist() if re.fullmatch(r'classes(?:[2-9][0-9]*)?\.dex', n)]
            newname = 'classes' + str(len(dexnames) + 1) + '.dex'
            if newname in source.namelist():
                raise ValueError('Non-contiguous DEX names')

            def put(original_info, data):
                info = copy.copy(original_info)
                if info.compress_type == zipfile.ZIP_STORED:
                    alignment = 4096 if info.filename.endswith('.so') else 4
                    offset = destination.fp.tell() + 30 + len(info.filename.encode('utf-8')) + len(info.extra)
                    if offset % alignment:
                        padding = (-offset - 4) % alignment
                        info.extra += struct.pack('<HH', 0xd935, padding) + b'\0' * padding
                destination.writestr(info, data)

            for info in source.infolist():
                if re.fullmatch(r'META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))', info.filename.upper()):
                    continue
                data = modified.read_bytes() if info.filename == target else source.read(info.filename)
                if info.filename != target:
                    unchanged.append({'path': info.filename, 'sha256': hashlib.sha256(data).hexdigest()})
                put(info, data)
            info = zipfile.ZipInfo(newname, (2026, 10, 14, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            put(info, (dexout / 'classes.dex').read_bytes())
        signed = out / (name + '-selector.apk')
        run([java_bin, '-cp', build_cp, 'SignPlatform', args.signing_reference.resolve() / 'platform.x509.pem',
             args.signing_reference.resolve() / 'platform.pk8', unsigned, signed], 'sign-' + name)
        with zipfile.ZipFile(signed) as final:
            for item in unchanged:
                if hashlib.sha256(final.read(item['path'])).hexdigest() != item['sha256']:
                    raise ValueError('Changed unrelated APK entry: ' + item['path'])
        apk_outputs.append({'name': name, 'input_sha256': expected, 'output': signed.name,
                            'output_sha256': sha(signed), 'patched_dex': target, 'wrapped_methods': count,
                            'certificate_sha256': APK_CERT, 'signature_verified': True,
                            'manifest_resources_native_unchanged': True, 'unchanged_entries': len(unchanged)})
    source_files = sorted((SRC / 'java').rglob('*.java')) + sorted((SRC / 'build-tools').glob('*.java'))
    manifest = {'scope': 'EXACT_OEM_SELECTOR_OFFLINE_BUILD_NO_DEVICE_INSTALLATION',
                'apk_outputs': apk_outputs,
                'source_files': {p.relative_to(ROOT).as_posix(): sha(p) for p in source_files},
                'dependencies': {name: sha(getattr(args, name)) for name in ['android_jar', 'r8_jar', 'dexlib_jar', 'guava_jar', 'apksig_jar']},
                'note': 'Owner-supplied key and original OEM APKs remain local; use build_runtime.py for the daemon bundle.'}
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    for item in apk_outputs:
        print('VERIFIED ' + item['output'] + ' SHA256 ' + item['output_sha256'])


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, zipfile.BadZipFile) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        sys.exit(1)
