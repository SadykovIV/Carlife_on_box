#!/usr/bin/env python3
"""Build an exact-input Magisk install or data-only update ZIP, entirely offline.

The install ZIP contains the owner's original Magisk boot image and a minimally
patched copy. Updates inherit that boot integration and cannot silently change
its pinned entry points. No firmware, APK, signing key or prebuilt boot is shipped
with this source. All inputs are supplied by the owner.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = 'tbox-ultra-1-sm6350-v1'
MODULE_ID = 'carlife_on_box'
VERSION = 'boot-native-v1-20261014'
OLD_RESOLVER = 'b97b882340ec5e8f16f9e0cbd19aa18eb1cccc5e75bca1a278609d76f02d791a'
NEW_RESOLVER = '6add4df4d3aeb321a813e9d2262d755c7d3615f5ff0883bca08c3cb70fe36481'
SCRIPTS = ('customize.sh', 'manager.sh', 'post-fs-data.sh', 'uninstall.sh', 'action.sh')
SELECTOR_FILES = ('selector-daemon.sh', 'selector-daemon.jar', 'selector-control.sh')
BOOT_FILES = ('carlife-owner.rc', 'carlife-early-dispatch.sh',
              'carlife-native-entry.sh', 'carlife-selector-entry.sh')
MAX_ZIP = 300 * 1024 * 1024


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


integration = load('magisk_integration', ROOT / 'integration/sm6350/build_integration.py')
boot = load('magisk_boot_image', ROOT / 'tools/magisk/boot_image.py')
sha = integration.sha
require = integration.require


def regular_bytes(path, limit=boot.LIMIT):
    require(path.is_file() and not path.is_symlink(), 'regular input required: ' + path.name)
    require(path.stat().st_size <= limit, 'input too large: ' + path.name)
    return path.read_bytes()


def single_line(value):
    require(isinstance(value, str) and 0 < len(value) <= 256 and
            re.fullmatch(r'[A-Za-z0-9._:/+@,= -]+', value), 'unsafe fingerprint')
    return value


def module_gate():
    # This runs in the early child service, never a blocking init action. Magisk
    # can finish its post-fs-data transaction while OEM projection stays stopped.
    return '''installer_gate() {
    mg=/data/adb/modules/carlife_on_box
    mu=/data/adb/modules_update/carlife_on_box
    st=/data/adb/carlife-on-box
    if [ -e "$mg/disable" ] || [ -e "$mg/remove" ] || [ -L "$mg/disable" ] || [ -L "$mg/remove" ]; then return 20; fi
    if [ ! -e "$mg" ] && [ ! -L "$mg" ] && [ ! -e "$mu" ] && [ ! -L "$mu" ]; then return 20; fi
    [ ! -L "$mg" ] && [ ! -L "$mu" ] && [ ! -L "$st" ] || return 21
    [ -d "$st" ] && [ "$(b stat -c '%u:%g:%a' "$st")" = 0:0:700 ] || return 21
    regular "$st/phase" 600 || return 21
    ip=$(b cat "$st/phase") || return 21
    case "$ip" in installed|prepared|updating|rollback|failed) ;; *) return 21;; esac
    exact_lf "$st/phase" "$ip" || return 21
    [ "$ip" != rollback ] || return 20
    [ ! -e "$mu" ] && [ ! -L "$mu" ] || return 10
    [ ! -e "$st/pending" ] && [ ! -L "$st/pending" ] || return 10
    [ "$ip" = installed ] || return 10
    return 0
}
'''


def render_hooks(oem_sha, runtime_sha, selector_sha):
    for value in (oem_sha, runtime_sha, selector_sha):
        require(re.fullmatch(r'[a-f0-9]{64}', value), 'expected SHA256')
    result = {}
    for name in BOOT_FILES:
        text = (ROOT / 'tools/boot_native/boot' / (name + '.in')).read_text()
        for old, value in {'@OEM_SHA@': oem_sha, '@RUNTIME_SHA@': runtime_sha,
                           '@SELECTOR_SHA@': selector_sha}.items():
            text = text.replace(old, value)
        require(not re.search(r'@[A-Z_]+@', text), 'unresolved boot template placeholder')
        # Stock devices have no previous experimental module. An existing one
        # must remain disabled; no dummy module is created to satisfy a guard.
        anchor = 'if [ -d /data/adb/modules/carlife_native_pilot_v3 ]; then'
        replacement = '''if [ -e /data/adb/modules/carlife_native_pilot_v3 ] || [ -L /data/adb/modules/carlife_native_pilot_v3 ]; then
                [ -d /data/adb/modules/carlife_native_pilot_v3 ] && [ ! -L /data/adb/modules/carlife_native_pilot_v3 ] && [ "$(b readlink -f /data/adb/modules/carlife_native_pilot_v3)" = /data/adb/modules/carlife_native_pilot_v3 ] || exit 70'''
        if name == 'carlife-early-dispatch.sh':
            replacement = replacement.replace('|| exit 70', '|| { event OLD_LATE_HOOK_REFUSED; park; }')
        text = text.replace(anchor, replacement)
        if name == 'carlife-early-dispatch.sh':
            anchor = 'BOOT=$(b cat /proc/sys/kernel/random/boot_id)'
            require(text.count(anchor) == 1, 'early guard function anchor')
            text = text.replace(anchor, module_gate() + anchor, 1)
            anchor = 'while :; do\n    # /data/adb'
            require(text.count(anchor) == 1, 'early guard loop anchor')
            gated = '''while :; do
    if [ ! -e /data/adb ]; then event INSTALLER_DATA_PENDING; sleep 1; continue; fi
    installer_gate; installer_rc=$?
    case "$installer_rc" in
        0) ;;
        10) event INSTALLER_TRANSACTION_PENDING; sleep 1; continue;;
        20)
            [ "$(b getprop ro.persistent_properties.ready)" = true ] || { event OEM_PROPERTIES_PENDING; sleep 1; continue; }
            oem_file_ok || { event OEM_PIN_REFUSED; park; }
            event INSTALLER_OEM_BYPASS
            exec "$OEM"
            ;;
        *) event INSTALLER_STORE_REFUSED; park;;
    esac
    # /data/adb'''
            text = text.replace(anchor, gated, 1)
        elif name == 'carlife-native-entry.sh':
            anchor = '[ -d "$S" ] &&'
            require(text.count(anchor) == 1, 'native guard anchor')
            text = text.replace(anchor, module_gate() + 'installer_gate || exit 70\n' + anchor, 1)
            text = text.replace('exec /system/bin/sh "$P/runtime-control.sh" run-boot',
                                'installer_gate || exit 70\nexec /system/bin/sh "$P/runtime-control.sh" run-boot')
        elif name == 'carlife-selector-entry.sh':
            anchor = '[ -d "$S" ] &&'
            require(text.count(anchor) == 1, 'selector guard anchor')
            helpers = '''regular() { [ -f "$1" ] && [ ! -L "$1" ] && [ "$(b stat -c '%u:%g:%a' "$1")" = "0:0:$2" ]; }
exact_lf() {
    xl=$(b cat "$1" && printf '.') || return 1
    [ "$xl" = "$(printf '%s\\n.' "$2")" ] && [ "$(b stat -c %s "$1")" = "$((${#2}+1))" ]
}
'''
            wait = '''while :; do
    installer_gate; installer_rc=$?
    case "$installer_rc" in
        0) break;;
        10) sleep 1;;
        *) exit 70;;
    esac
done
'''
            text = text.replace(anchor, helpers + module_gate() + wait + anchor, 1)
        result[name] = text.encode()
    return result


def seed_inputs(archive, manifest, selector):
    metadata, runtime = integration.load_runtime(archive, manifest)
    require(runtime['data/owner'] == b'org.carlife.boot-native.v1\n' and
            runtime['data/current'] == (VERSION + '\n').encode(), 'runtime owner/version bytes')
    for name, value in runtime.items():
        expected_mode = 0o700 if value is None else (
            0o600 if name in ('data/owner', 'data/current') else
            0o400 if name.endswith(('/md.jar', '/payload.sha256')) else 0o500)
        require(metadata['members'][name]['mode'] == expected_mode, 'runtime mode: ' + name)
    version_prefix = 'data/versions/' + VERSION + '/'
    expected_checks = ''.join(f'{sha(runtime[version_prefix + name])}  {name}\n'
                              for name in ('md.jar', 'runtime-controller.sh', 'uevent-watch')).encode()
    require(runtime[version_prefix + 'payload.sha256'] == expected_checks, 'runtime payload checksum contract')
    resolver = runtime['data/runtime-control.sh']
    if sha(resolver) == OLD_RESOLVER:
        resolver = integration.conditional_old_pilot(resolver.decode()).encode()
    require(sha(resolver) == NEW_RESOLVER, 'unsupported runtime resolver')
    runtime['data/runtime-control.sh'] = resolver
    files, modes, dirs = {}, {}, {}
    for name, value in runtime.items():
        path = 'seed/runtime' + name[4:]
        if value is None:
            dirs[path] = metadata['members'][name]['mode']
        else:
            files[path], modes[path] = value, metadata['members'][name]['mode']
    selector_manifest = json.loads(regular_bytes(selector / 'manifest.json', 1024 * 1024))
    for name in SELECTOR_FILES:
        value = regular_bytes(selector / name, 8 * 1024 * 1024)
        require(sha(value) == selector_manifest['payload'][name], 'selector pin: ' + name)
        files['seed/selector/' + name], modes['seed/selector/' + name] = value, 0o500
    files['seed/selector/schema'], modes['seed/selector/schema'] = b'1\n', 0o600
    files['seed/selector/selected'], modes['seed/selector/selected'] = b'carlife\n', 0o600
    dirs.update({'seed/selector': 0o700, 'seed/selector/logs': 0o700})
    return files, modes, dirs


def read_package(path):
    """Read a local predecessor without extraction or executing its scripts."""
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_ZIP,
            'regular bounded base package required')
    result, total = {}, 0
    with zipfile.ZipFile(path) as archive:
        require(len(archive.infolist()) < 200, 'base package member limit')
        for info in archive.infolist():
            name = info.filename
            require(name == str(PurePosixPath(name)) and not name.startswith('/') and
                    '..' not in PurePosixPath(name).parts and name not in result and
                    not info.is_dir() and not stat.S_ISLNK(info.external_attr >> 16), 'base package path')
            total += info.file_size
            require(info.file_size <= boot.LIMIT and total <= MAX_ZIP, 'base package expansion bound')
            result[name] = archive.read(info)
    checks = {}
    for line in result['payload.sha256'].decode('ascii').splitlines():
        digest, name = line.split('  ', 1)
        require(re.fullmatch(r'[a-f0-9]{64}', digest) and name not in checks, 'base checksum syntax')
        checks[name] = digest
    require(set(checks) == set(result) - {'payload.sha256'}, 'complete base checksums')
    require(all(sha(result[name]) == value for name, value in checks.items()), 'base checksum mismatch')
    manifest = json.loads(result['manifest.json'])
    require(manifest['format'] == 1 and manifest['profile'] == PROFILE and manifest['module_id'] == MODULE_ID,
            'base package profile')
    require(manifest['package_kind'] in ('install', 'update'), 'base package kind')
    return manifest, result


def shell_env(values):
    rows = []
    for key, value in values.items():
        require(re.fullmatch(r'[A-Z][A-Z0-9_]*', key), 'environment key')
        value = str(value)
        if key == 'EXPECTED_FINGERPRINT':
            single_line(value)
            rows.append(key + "='" + value + "'\n")
        else:
            require(re.fullmatch(r'[A-Za-z0-9_.-]+', value), 'environment value: ' + key)
            rows.append(key + '=' + value + '\n')
    return ''.join(rows).encode()


def build(args):
    require(not args.output.exists(), 'fresh output directory required')
    files, modes, dirs = seed_inputs(args.runtime, args.runtime_manifest, args.selector_release)
    runtime_sha = sha(files['seed/runtime/runtime-control.sh'])
    selector_sha = sha(files['seed/selector/selector-daemon.sh'])
    audit = None
    if args.command == 'install':
        original = regular_bytes(args.current_image)
        require(re.fullmatch(r'[a-f0-9]{64}', args.expect_current_sha256), 'current boot SHA256 syntax')
        require(sha(original) == args.expect_current_sha256, 'current boot SHA256 mismatch')
        init = regular_bytes(args.init, 1024 * 1024)
        require(sha(init) == integration.INIT_SHA, 'unsupported OEM init; use the reviewed firmware profile')
        require(init.count(b'service sd-reverse /system/bin/sd-reverse.sh\n') == 1 and
                init.count(b'    start sd-reverse\n') == 1, 'OEM service/start count')
        require(init.index(b'on early-init\n') < init.index(b'    start sd-reverse\n') <
                init.index(b'    exec_start apexd-bootstrap\n'), 'OEM early-init order')
        oem = regular_bytes(args.oem_script, 1024 * 1024)
        pins = dict(original_sha256=sha(original), oem_sha256=sha(oem), init_sha256=sha(init),
                    size=len(original), fingerprint=single_line(args.fingerprint), slot_suffix=args.slot_suffix)
        hooks = render_hooks(pins['oem_sha256'], runtime_sha, selector_sha)
        candidate, audit = boot.overlay_image(original, hooks)
        pins['candidate_sha256'] = sha(candidate)
        files.update({'boot/original.img': original, 'boot/candidate.img': candidate})
        modes.update({'boot/original.img': 0o400, 'boot/candidate.img': 0o400})
        base_id = 'none'
    else:
        previous, base_files = read_package(args.base_package)
        pins = previous['boot']
        require(previous['runtime_entry_sha256'] == runtime_sha and
                previous['selector_entry_sha256'] == selector_sha,
                'data-only update cannot change boot-pinned entry points')
        require(previous['runtime_version'] == VERSION, 'base runtime version')
        change_prefix = 'seed/runtime/versions/' + VERSION + '/'
        for name, value in files.items():
            if not name.startswith(change_prefix):
                require(base_files.get(name) == value, 'data-only update changes an immutable seed: ' + name)
        base_id = previous['payload_id'] if previous['package_kind'] == 'install' else previous['base_payload_id']
    for name in SCRIPTS:
        files[name], modes[name] = regular_bytes(ROOT / 'tools/magisk' / name, 1024 * 1024), 0o755
    files['module.prop'] = (f'id={MODULE_ID}\nname=Baidu CarLife on Android Box\n'
        f'version=1.0\nversionCode=1\nauthor=Carlife_on_box contributors\n'
        f'description=TBox Ultra 1 native CarLife; reversible exact-boot integration.\n').encode()
    modes['module.prop'] = 0o644
    # A normal Magisk module ZIP; deliberately no updater-binary/recovery flasher.
    files['skip_mount'], modes['skip_mount'] = b'', 0o644
    seed_names = sorted(name for name in files if name.startswith('seed/'))
    files['payload-files.tsv'] = ''.join(f'{sha(files[name])}\t{modes[name]:o}\t{name}\n'
                                         for name in seed_names).encode()
    files['payload-dirs.tsv'] = ''.join(f'{mode:o}\t{name}\n' for name, mode in
                                       sorted(dirs.items(), key=lambda row: (row[0].count('/'), row[0]))).encode()
    modes.update({'payload-files.tsv': 0o600, 'payload-dirs.tsv': 0o600})
    payload_id = sha(''.join(f'{name}\0{sha(data)}\n' for name, data in sorted(files.items())).encode())
    env = dict(FORMAT=1, MODULE_ID=MODULE_ID, PACKAGE_KIND=args.command, PROFILE=PROFILE,
        SLOT_SUFFIX=pins['slot_suffix'], BOARD='chelianyi-led', CHANNEL='sd_msm6350', SDK=35,
        # Minimum tested Magisk, not a cap on later compatible releases.
        MAGISK_VERSION_CODE=30700, EXPECTED_FINGERPRINT=pins['fingerprint'], RUNTIME_VERSION=VERSION,
        BOOT_ORIGINAL_SHA256=pins['original_sha256'], BOOT_CANDIDATE_SHA256=pins['candidate_sha256'],
        BOOT_SIZE=pins['size'], OEM_SHA256=pins['oem_sha256'], INIT_SHA256=pins['init_sha256'],
        RUNTIME_ENTRY_SHA256=runtime_sha, SELECTOR_ENTRY_SHA256=selector_sha,
        PAYLOAD_ID=payload_id, BASE_PAYLOAD_ID=base_id)
    files['manifest.env'], modes['manifest.env'] = shell_env(env), 0o600
    manifest = dict(format=1, module_id=MODULE_ID, profile=PROFILE, package_kind=args.command,
        payload_id=payload_id, base_payload_id=base_id, boot=pins, runtime_version=VERSION,
        runtime_entry_sha256=runtime_sha, selector_entry_sha256=selector_sha,
        default_selection='carlife', hardware_validation='NOT_TESTED', device_operations=0,
        boot_audit=audit, proprietary_apks_included=False,
        source_runtime_sha256=sha(regular_bytes(args.runtime)),
        source_selector_manifest_sha256=sha(regular_bytes(args.selector_release / 'manifest.json')),
        builder_sha256=sha(Path(__file__).read_bytes()), boot_parser_sha256=sha((ROOT / 'tools/magisk/boot_image.py').read_bytes()))
    files['manifest.json'], modes['manifest.json'] = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode(), 0o600
    # Magisk removes customize.sh after installation; the persistent checksum
    # set intentionally excludes that one transient install hook.
    files['module.sha256'] = ''.join(f'{sha(data)}  {name}\n' for name, data in sorted(files.items())
                                    if name != 'customize.sh').encode()
    modes['module.sha256'] = 0o600
    files['payload.sha256'] = ''.join(f'{sha(data)}  {name}\n' for name, data in sorted(files.items())).encode()
    modes['payload.sha256'] = 0o600
    args.output.mkdir(parents=True)
    destination = args.output / ('Carlife_on_box-TBox-Ultra-1-' + args.command + '.zip')
    with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, value in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | modes[name]) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, value, compresslevel=9)
    result = dict(status='OFFLINE_BUILT_NOT_DEVICE_TESTED', package=destination.name,
                  package_sha256=sha(destination.read_bytes()), payload_id=payload_id,
                  package_kind=args.command, members=len(files), device_operations=0)
    (args.output / 'BUILD.json').write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    (args.output / 'manifest.json').write_bytes(files['manifest.json'])
    read_package(destination)
    return result


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for kind in ('install', 'update'):
        sub = commands.add_parser(kind)
        sub.add_argument('--runtime', type=Path, required=True)
        sub.add_argument('--runtime-manifest', type=Path, required=True)
        sub.add_argument('--selector-release', type=Path, required=True)
        sub.add_argument('--output', type=Path, required=True)
        if kind == 'install':
            sub.add_argument('--current-image', type=Path, required=True)
            sub.add_argument('--expect-current-sha256', required=True)
            sub.add_argument('--init', type=Path, required=True)
            sub.add_argument('--oem-script', type=Path, required=True)
            sub.add_argument('--fingerprint', required=True)
            sub.add_argument('--slot-suffix', choices=('_a', '_b'), default='_a')
        else:
            sub.add_argument('--base-package', type=Path, required=True)
    return parser


def main():
    parser = argument_parser()
    args = parser.parse_args()
    try:
        result = build(args)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        parser.exit(2, 'build failed: ' + str(error) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
