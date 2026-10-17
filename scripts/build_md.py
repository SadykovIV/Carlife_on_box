#!/usr/bin/env python3
"""Build/test the common MD plus native boot overlay without installing anything."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / 'android-md'
NATIVE = ROOT / 'tools/boot_native/md'
TESTS = [
    'NativeVoiceFocusTest',
    'MdWireTest', 'MdWriterRaceTest', 'MdWatchdogRaceTest', 'DrainUntilStoppedTest',
    'PipelineProgressTest', 'AvcAccessUnitsTest', 'SourceLeaseTest', 'ProjectionTouchTest', 'LegacyMultitouchTest',
    'OemCsdGuardTest', 'AccessoryTransportTest', 'NativeSessionBoundaryTest', 'AsyncRingLogTest',
    'BootPendingInitTest', 'CarLifeFrameReaderTest', 'NativeCompatibilityTest', 'NativePhoneVerificationTest', 'NativeHuControlsTest',
    'AccessoryPacketWriteTest', 'NativePauseHeartbeatTest', 'AccessoryProgressDiagnosticsTest',
    'NativeOwnerRetirementTest', 'NativeProcessExitTest', 'NativeTouchRecoveryTest', 'NativeLegacyMultitouchTest', 'NativeFeatureResponseDiagnosticsTest',
    'NativeTouchWireDiagnosticsTest', 'NativeAsyncTouchInjectionTest', 'NativeEncoderPriorityTest',
    'VideoDiagnosticPolicyTest', 'NativeVideoDiagnosticsEntryTest',
    'CarLifeAudioTest', 'PcmAudioSessionTest', 'NativeAudioSessionTest', 'NativeApplicationMicStateTest',
    'SplitAudioSourceTest', 'VideoQueueTest', 'VideoRateControlTest', 'FrameRatePacingTest',
    'NativeVideoFlowTest', 'NativeVideoConfigTest', 'NativeVideoSettingsCommandTest', 'VideoModeTest', 'NativeSplitAudioSessionTest', 'NativeVrAudioSessionTest', 'NativeVrStopTest', 'NativeServicesTest', 'YandexRouteTest', 'NativeServicesStartupTest', 'NativeVoiceQueueTest', 'VoiceUiStateTest', 'NativeMicrophoneTest', 'NativeMicrophoneCallGuardTest', 'NativeCallAudioTest', 'NativeCallSessionTest', 'NativeServicesSessionTest',
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fixed_zip(path, entries):
    with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries):
            info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def required_file(value, label):
    if not value or not Path(value).is_file():
        raise ValueError(f'{label} not found; supply its path explicitly (see --help).')
    return Path(value).resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'), help='Android SDK root (or ANDROID_SDK_ROOT / ANDROID_HOME)')
    parser.add_argument('--android-jar', help='Explicit platform android.jar; overrides --sdk/platforms/android-35/android.jar')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'), help='JDK home (or JAVA_HOME); otherwise java/javac from PATH')
    d8 = parser.add_mutually_exclusive_group()
    d8.add_argument('--d8', default=os.environ.get('D8'), help='SDK d8 executable (or D8); otherwise find in --sdk/build-tools')
    d8.add_argument('--r8-jar', default=os.environ.get('R8_JAR'), help='R8 JAR with com.android.tools.r8.D8 (or R8_JAR)')
    parser.add_argument('--output', default=str(ROOT / 'build/md'), help='Output directory (default: build/md)')
    parser.add_argument('--test-only', action='store_true', help='Compile and run host JVM tests without D8 or a device artifact')
    args = parser.parse_args()
    api = required_file(args.android_jar or (str(Path(args.sdk) / 'platforms/android-35/android.jar') if args.sdk else None), 'Android API 35 android.jar')
    suffix = '.exe' if os.name == 'nt' else ''
    java = required_file(str(Path(args.java_home) / ('bin/java' + suffix)) if args.java_home else shutil.which('java'), 'JDK java')
    javac = required_file(str(Path(args.java_home) / ('bin/javac' + suffix)) if args.java_home else shutil.which('javac'), 'JDK javac')
    d8_command, compiler = None, None
    if not args.test_only:
        if args.r8_jar:
            compiler = required_file(args.r8_jar, 'R8 JAR')
            d8_command = [java, '-cp', compiler, 'com.android.tools.r8.D8']
        else:
            executable = args.d8
            if not executable and args.sdk:
                candidates = list((Path(args.sdk) / 'build-tools').glob('*/d8' + ('.bat' if os.name == 'nt' else '')))
                candidates.sort(key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)))
                executable = candidates[-1] if candidates else None
            compiler = required_file(executable, 'D8 executable or --r8-jar')
            d8_command = [compiler]
    out = Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    if args.java_home:
        env['JAVA_HOME'] = str(Path(args.java_home).resolve())
        env['PATH'] = str(java.parent) + os.pathsep + env.get('PATH', '')
    records = []

    def run(command, label):
        try:
            result = subprocess.run([str(x) for x in command], cwd=ROOT, env=env, capture_output=True, text=True,
                                    timeout=90 if label in TESTS else 180)
        except subprocess.TimeoutExpired as error:
            # A failed assertion may leave a worker alive. Keep its evidence and fail
            # the build instead of hanging indefinitely before writing the logs.
            decode = lambda value: value.decode('utf-8', errors='replace') if isinstance(value, bytes) else value or ''
            result = subprocess.CompletedProcess(command, 124, decode(error.stdout), decode(error.stderr)+'\nTimed out: '+label)
        # Logs stay in ignored build output. Public report omits absolute toolchain paths.
        (out / (label + '.stdout.txt')).write_text(result.stdout)
        (out / (label + '.stderr.txt')).write_text(result.stderr)
        records.append({'label': label, 'exit_code': result.returncode})
        (out / 'steps.json').write_text(json.dumps(records, indent=2) + '\n')
        if result.returncode:
            raise RuntimeError(f'{label} failed ({result.returncode}):\n{result.stdout}{result.stderr}')
        return result

    # Keep the historical source order: overlay replaces common MdMain by relative name.
    common = {str(p.relative_to(COMMON / 'src')): p for p in sorted((COMMON / 'src').rglob('*.java'))}
    overlay = {str(p.relative_to(NATIVE / 'src')): p for p in sorted((NATIVE / 'src').rglob('*.java'))}
    if not common or 'org/carlife/hulab/MdMain.java' not in overlay:
        raise ValueError('Missing common/native source layer')
    sources = dict(common)
    sources.update(overlay)
    compile_stubs = sorted((NATIVE / 'compile-stubs').rglob('*.java'))
    test_sources = sorted((COMMON / 'tests').rglob('*.java')) + sorted((NATIVE / 'tests').rglob('*.java'))
    fixture_provenance = json.loads((COMMON / 'tests/fixtures/provenance.json').read_text())
    for name, expected in fixture_provenance['files'].items():
        fixture = COMMON / 'tests/fixtures' / (name + '.hex')
        if hashlib.sha256(bytes.fromhex(fixture.read_text())).hexdigest() != expected:
            raise ValueError(f'Fixture hash mismatch: {name}')
    java_version = run([java, '-version'], 'java-version').stderr.strip()
    artifact = out / 'md-boot-native.jar'
    with tempfile.TemporaryDirectory(prefix='.md-build-', dir=out) as temporary:
        work = Path(temporary)
        classes = work / 'classes'
        classes.mkdir()
        stubs = work / 'compile-stubs'
        stubs.mkdir()
        if compile_stubs:
            run([javac, '--release', '8', '-cp', api, '-d', stubs, *compile_stubs], 'javac-compile-stubs')
        compile_cp = str(api) + os.pathsep + str(stubs)
        run([javac, '--release', '8', '-cp', compile_cp, '-d', classes, *sources.values()], 'javac')
        tests = work / 'test-classes'
        tests.mkdir()
        cp = str(classes) + os.pathsep + compile_cp
        run([javac, '--release', '8', '-cp', cp, '-d', tests, *test_sources], 'test-javac')
        test_cp = str(tests) + os.pathsep + cp
        for name in TESTS:
            result = run([java, '-cp', test_cp, 'org.carlife.hulab.' + name], name)
            summaries = [line for line in result.stdout.splitlines() if 'PASS' in line and not line.startswith('{')]
            print(' | '.join(summaries) if summaries else f'PASS {name}')
        if not args.test_only:
            classes_jar = work / 'classes.jar'
            fixed_zip(classes_jar, [(p.relative_to(classes).as_posix(), p.read_bytes()) for p in classes.rglob('*.class')])
            dex = work / 'dex'
            dex.mkdir()
            # Framework signature stubs are resolution-only, never program/Dex input.
            run([*d8_command, '--min-api', '21', '--lib', api, '--classpath', stubs, '--output', dex, classes_jar], 'd8')
            if sorted(p.name for p in dex.iterdir()) != ['classes.dex']:
                raise ValueError('Expected exactly one classes.dex; review multidex handling before use')
            fixed_zip(work / 'md-boot-native.jar', [('classes.dex', (dex / 'classes.dex').read_bytes())])
            os.replace(work / 'md-boot-native.jar', artifact)
    report = {
        'scope': 'OFFLINE_BUILD_AND_JVM_TESTS_NO_DEVICE_DEPLOYMENT',
        'tests_passed': len(TESTS), 'test_suites': TESTS,
        'artifact': None if args.test_only else artifact.name,
        'artifact_sha256': None if args.test_only else sha(artifact),
        'java_version': java_version, 'python_version': sys.version.split()[0],
        'zlib_version': zlib.ZLIB_RUNTIME_VERSION, 'android_jar_sha256': sha(api),
        'd8_input_sha256': sha(compiler) if compiler else None,
        'source_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in sources.values()},
        'compile_only_stub_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in compile_stubs},
        'common_source_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in common.values()},
        'overlay_keys': sorted(overlay),
        'test_source_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in test_sources},
    }
    (out / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'PASS: {len(TESTS)} JVM suites')
    if not args.test_only:
        print(f'BUILT {artifact}\nSHA256 {report["artifact_sha256"]}')
    print(f'Report: {out / "manifest.json"}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(1)
