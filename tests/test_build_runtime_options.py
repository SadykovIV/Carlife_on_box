"""Offline staging options: keep the baseline exact and build a distinct audio candidate."""
import argparse
import importlib.util
import io
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


build = load('runtime_options_under_test', ROOT / 'scripts/build_runtime.py')


def package_module():
    return load('runtime_package_options_fixture', ROOT / 'tools/boot_native/build_package.py')


class RuntimeBuildOptionsTests(unittest.TestCase):
    def setUp(self):
        self.version = package_module().VERSION
        self.control = (ROOT / 'tools/boot_native/runtime/runtime-control.sh').read_bytes().decode('utf-8')
        self.controller = (ROOT / 'tools/boot_native/runtime/runtime-controller.sh').read_bytes().decode('utf-8')

    def test_default_options_preserve_current_version_and_audio_off(self):
        args = build.argument_parser(self.version).parse_args([
            '--md-jar', 'md.jar', '--android-jar', 'android.jar', '--r8-jar', 'r8.jar', '--output', 'new-output'])
        self.assertEqual(args.audio_source, 'off')
        self.assertEqual(args.runtime_version, self.version)

    def test_explicit_options_and_invalid_audio_are_parsed(self):
        args = ['--md-jar', 'md.jar', '--android-jar', 'android.jar', '--r8-jar', 'r8.jar', '--output', 'new-output']
        parsed = build.argument_parser(self.version).parse_args(args + [
            '--audio-source', 'remote-submix', '--runtime-version', 'audio-candidate-1'])
        self.assertEqual((parsed.audio_source, parsed.runtime_version), ('remote-submix', 'audio-candidate-1'))
        with self.assertRaises(ValueError):
            build.configure_controller_audio(self.controller, 'microphone')

    def test_off_default_text_is_byte_exact(self):
        control, controller = build.configure_runtime_inputs(
            self.control, self.controller, 'off', self.version, self.version)
        self.assertEqual(control.encode(), self.control.encode())
        self.assertEqual(controller.encode(), self.controller.encode())
        # Off is a no-op even for a future controller layout; no unrelated build restriction.
        self.assertEqual(build.configure_controller_audio('future launch layout\r\n', 'off'), 'future launch layout\r\n')

    def test_split_services_auto_candidate_changes_launch_and_version_only(self):
        control, controller = build.configure_runtime_inputs(self.control, self.controller, 'split',
            'native-services-1', self.version, 'on', 'auto')
        self.assertIn('--native-services on --video-mode auto --audio-source split', controller)
        launch = next(line for line in controller.splitlines() if 'exec /system/bin/app_process' in line)
        self.assertEqual(launch.count('--native-services'), 1)
        self.assertEqual(launch.count('--audio-source'), 1)
        self.assertIn('exact_lf "$P/current" native-services-1 || exit 70', control)
        self.assertIn("case \"$1\" in *' org.carlife.hulab.MdMain '*'--transport native-usb'*)", controller)
        self.assertEqual(subprocess.run(['sh', '-n'], input=controller, text=True, capture_output=True).returncode, 0)

    def test_video_mode_is_validated_before_shell_staging(self):
        for value in ('720p', 'auto', '1280x480', '1920x1080', '2240x976'):
            self.assertEqual(build.video_mode(value), value)
        for value in (';id', 'auto$(id)', '1920x1080\n', '1281x720', '2560x1440', '0x0', '320x9999'):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                build.video_mode(value)

    def test_services_options_cannot_be_duplicated(self):
        once = build.configure_controller_services(self.controller, 'on', 'auto')
        with self.assertRaises(ValueError):
            build.configure_controller_services(once, 'on', 'auto')
        with self.assertRaises(ValueError):
            build.configure_controller_services(self.controller, 'maybe', 'auto')

    def test_audio_changes_only_the_actual_launch_not_identity_matcher(self):
        configured = build.configure_controller_audio(self.controller, 'remote-submix')
        before, after = self.controller.splitlines(), configured.splitlines()
        changed = [(a, b) for a, b in zip(before, after) if a != b]
        self.assertEqual(len(before), len(after))
        self.assertEqual(len(changed), 1)
        self.assertIn('exec /system/bin/app_process /system/bin org.carlife.hulab.MdMain', changed[0][0])
        self.assertEqual(changed[0][1], changed[0][0].replace(
            '--transport native-usb', '--transport native-usb --audio-source remote-submix'))
        self.assertEqual(configured.count('--audio-source remote-submix'), 1)
        self.assertIn("case \"$1\" in *' org.carlife.hulab.MdMain '*'--transport native-usb'*)", configured)

    def test_remote_submix_requires_unambiguous_launch_anchor(self):
        launch = next(line for line in self.controller.splitlines(keepends=True)
                      if 'exec /system/bin/app_process /system/bin org.carlife.hulab.MdMain' in line)
        for malformed in (
            self.controller.replace(launch, ''),
            self.controller + launch,
            self.controller.replace(launch, launch.replace('--transport native-usb', '--transport usb')),
            self.controller.replace(launch, launch.replace('--transport native-usb', '--transport native-usb --transport native-usb')),
            self.controller.replace(launch, launch.replace('--transport native-usb', '--transport native-usb --audio-source off')),
        ):
            with self.subTest(malformed=malformed[-120:]), self.assertRaises(ValueError):
                build.configure_controller_audio(malformed, 'remote-submix')

    def test_runtime_name_is_a_safe_single_shell_path_component(self):
        for version in ('audio-v1', 'V2_r1.20261015', 'a' * 64):
            self.assertEqual(build.runtime_version(version), version)
        for version in ('', '.', '..', '.hidden', '-option', '../next', 'a/b', 'a\\b',
                        'two words', 'a\nb', 'a;id', '$(id)', '`id`', 'a"', 'версия', 'a' * 65):
            with self.subTest(version=version), self.assertRaises(argparse.ArgumentTypeError):
                build.runtime_version(version)

    def test_candidate_updates_all_version_guards_only_in_staging(self):
        version = 'boot-native-audio-test-20261015'
        control, controller = build.configure_runtime_inputs(
            self.control, self.controller, 'remote-submix', version, self.version)
        self.assertIn('V=$P/versions/' + version + '\n', control)
        self.assertIn('exact_lf "$P/current" ' + version + ' || exit 70', control)
        self.assertIn('[ "$V" = "$P/versions/' + version + '" ]', controller)
        self.assertNotIn(self.version, control)
        self.assertNotIn(self.version, controller)
        self.assertEqual((ROOT / 'tools/boot_native/runtime/runtime-control.sh').read_bytes(), self.control.encode())
        self.assertEqual((ROOT / 'tools/boot_native/runtime/runtime-controller.sh').read_bytes(), self.controller.encode())
        for script in (control, controller):
            result = subprocess.run(['sh', '-n'], input=script, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_version_override_requires_each_exact_guard_once(self):
        path = 'V=$P/versions/' + self.version + '\n'
        current = 'exact_lf "$P/current" ' + self.version + ' || exit 70'
        guard = '[ "$V" = "$P/versions/' + self.version + '" ]'
        for old, where in ((path, 'control'), (current, 'control'), (guard, 'controller')):
            for duplicate in (False, True):
                control, controller = self.control, self.controller
                text = control if where == 'control' else controller
                changed = text + old if duplicate else text.replace(old, '')
                if where == 'control': control = changed
                else: controller = changed
                with self.subTest(anchor=old, duplicate=duplicate), self.assertRaises(ValueError):
                    build.configure_runtime_inputs(control, controller, 'off', 'audio-new', self.version)

    def test_default_package_is_identical_and_candidate_tar_is_consistent(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archives = []
            for name, audio, version in [('baseline', 'off', self.version),
                                         ('default-options', 'off', self.version),
                                         ('candidate', 'remote-submix', 'audio-candidate-1')]:
                stage = root / name
                for folder in ('runtime', 'observer/build', 'md/build'):
                    (stage / folder).mkdir(parents=True)
                control, controller = self.control, self.controller
                if name != 'baseline':
                    control, controller = build.configure_runtime_inputs(control, controller, audio, version, self.version)
                (stage / 'runtime/runtime-control.sh').write_bytes(control.encode())
                (stage / 'runtime/runtime-controller.sh').write_bytes(controller.encode())
                (stage / 'observer/build/uevent-watch').write_bytes(b'fixed offline observer fixture')
                (stage / 'md/build/md-boot-native.jar').write_bytes(b'fixed offline MD fixture')
                module = package_module()
                module.HERE, module.VERSION = stage, version
                manifest = module.build(stage / 'package')
                archives.append((stage / 'package/boot-native-runtime.tar').read_bytes())
                self.assertEqual(manifest['version'], version)
            self.assertEqual(archives[0], archives[1], 'off/default packaging is byte-identical to original contract')
            self.assertNotEqual(archives[0], archives[2])
            with tarfile.open(fileobj=io.BytesIO(archives[2])) as archive:
                self.assertEqual(archive.extractfile('data/current').read(), b'audio-candidate-1\n')
                control = archive.extractfile('data/runtime-control.sh').read().decode()
                controller = archive.extractfile('data/versions/audio-candidate-1/runtime-controller.sh').read().decode()
                self.assertIn('exact_lf "$P/current" audio-candidate-1 || exit 70', control)
                self.assertIn('[ "$V" = "$P/versions/audio-candidate-1" ]', controller)
                self.assertIn('--transport native-usb --audio-source remote-submix', controller)


if __name__ == '__main__':
    unittest.main()
