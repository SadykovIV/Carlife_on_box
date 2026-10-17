#!/usr/bin/env python3
"""Exercise owner cleanup with temporary files, never actual cgroups or USB."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'tools/boot_native/runtime/runtime-controller.sh'


def function(name):
    return re.search(r'^' + name + r'\(\) \{.*?^\}', SOURCE.read_text(), re.M | re.S).group()


class ControllerCgroupDrainTests(unittest.TestCase):
    def run_cleanup(self, settings='', parent_kind='directory'):
        with tempfile.TemporaryDirectory(prefix='carlife-cleanup-') as directory:
            root = Path(directory)
            parent = root / 'parent'
            if parent_kind == 'symlink':
                (root / 'real-parent').mkdir()
                parent.symlink_to(root / 'real-parent', target_is_directory=True)
            elif parent_kind != 'missing':
                parent.mkdir()
            if parent_kind == 'write_error':
                (parent / 'cgroup.procs').mkdir()
            elif parent.exists():
                (parent / 'cgroup.procs').write_text('')
            (root / 'run').mkdir()
            (root / 'runtime').mkdir()
            # Only fixed /sys/fs/cgroup paths in the extracted cleanup function
            # are redirected. All filesystem mutations below stay in this fixture.
            cleanup = function('cleanup_groups').replace('/sys/fs/cgroup', str(root))
            script = r'''
set -u
FIXTURE=$1; CG=$FIXTURE/run; CG_PARENT=/parent; R=$FIXTURE/runtime
TRACE=$FIXTURE/trace
ELAPSED=0; RELEASE_AT=0; BUSY_ROLE=; READ_ERROR_AT=-1
CLOCK_FAIL_AT=-1; CLOCK_REGRESS_AT=-1; SLEEP_FAIL=0; FAIL_RMDIR=
readf() {
    case "$1" in
        "$CG/md/cgroup.procs") role=md;;
        "$CG/observer/cgroup.procs") role=observer;;
        "$CG/controller/cgroup.procs") role=controller;;
        *) return 91;;
    esac
    printf 'read %s %s\n' "$role" "$ELAPSED" >> "$TRACE"
    if [ "$role" = controller ]; then
        read migrated < "$FIXTURE/parent/cgroup.procs" || return 92
        [ "$migrated" = "$$" ] || return 93
        [ "$READ_ERROR_AT" -lt 0 ] || [ "$ELAPSED" -lt "$READ_ERROR_AT" ] || return 74
        if [ "$ELAPSED" -lt "$RELEASE_AT" ]; then printf '4101\n4102\n'; fi
    elif [ "$BUSY_ROLE" = "$role" ]; then
        printf '4200\n'
    fi
    return 0
}
now_cs() {
    printf 'clock %s\n' "$ELAPSED" >> "$TRACE"
    [ "$CLOCK_FAIL_AT" -lt 0 ] || [ "$ELAPSED" -lt "$CLOCK_FAIL_AT" ] || return 75
    if [ "$CLOCK_REGRESS_AT" -ge 0 ] && [ "$ELAPSED" -ge "$CLOCK_REGRESS_AT" ]; then
        printf '%s\n' -1
    else printf '%s\n' "$ELAPSED"; fi
}
sleep() {
    [ "$1" = .1 ] || return 94
    printf 'sleep %s\n' "$ELAPSED" >> "$TRACE"
    [ "$SLEEP_FAIL" = 0 ] || return 76
    ELAPSED=$((ELAPSED+10))
}
b() {
    [ "$1" = rmdir ] && [ "$#" = 2 ] || return 95
    case "$2" in
        "$CG/md") role=md;; "$CG/observer") role=observer;;
        "$CG/controller") role=controller;; "$CG") role=root;;
        "$R/owner.lock") role=lock;; *) return 96;;
    esac
    printf 'rmdir %s %s\n' "$role" "$ELAPSED" >> "$TRACE"
    [ "$FAIL_RMDIR" != "$role" ] || return 77
}
''' + function('group_empty') + '\n' + cleanup + '\n' + settings + '\ncleanup_groups\n'
            result = subprocess.run(['sh', '-c', script, 'cleanup-fixture', str(root)],
                                    capture_output=True, text=True, timeout=5)
            trace = (root / 'trace').read_text().splitlines() if (root / 'trace').exists() else []
            migrated = (parent / 'cgroup.procs').read_text() if (parent / 'cgroup.procs').is_file() else ''
            return result, trace, migrated

    def test_empty_controller_has_no_sleep_and_preserves_removal_order(self):
        result, trace, migrated = self.run_cleanup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout + result.stderr, '')
        self.assertTrue(migrated.strip().isdigit())
        self.assertFalse(any(line.startswith('sleep ') for line in trace))
        self.assertEqual([line.split()[1] for line in trace if line.startswith('rmdir ')],
                         ['md', 'observer', 'controller', 'root', 'lock'])

    def test_inherited_watchdog_model_drains_after_migration(self):
        # Model the timeout grandchild surviving its applet for one second.
        # Every controller read also asserts that the owner already migrated.
        result, trace, _ = self.run_cleanup('RELEASE_AT=100')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sum(line.startswith('sleep ') for line in trace), 10)
        self.assertIn('read controller 100', trace)
        self.assertIn('rmdir controller 100', trace)
        self.assertEqual(result.stdout + result.stderr, '')

    def test_persistent_members_fail_at_four_seconds_and_keep_owner_lock(self):
        result, trace, _ = self.run_cleanup('RELEASE_AT=9999')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(sum(line.startswith('sleep ') for line in trace), 40)
        self.assertEqual(trace[-2:], ['read controller 400', 'clock 400'])
        self.assertEqual(result.stderr, 'CGROUP_DRAIN_TIMEOUT controller_pids=4101\n4102\n')
        self.assertFalse(any(line.startswith(('rmdir controller ', 'rmdir root ', 'rmdir lock ')) for line in trace))

    def test_controller_read_error_is_not_mistaken_for_empty_or_retried(self):
        result, trace, _ = self.run_cleanup('RELEASE_AT=100; READ_ERROR_AT=20')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(trace[-1], 'read controller 20')
        self.assertEqual(sum(line.startswith('sleep ') for line in trace), 2)
        self.assertFalse(any(line.startswith('rmdir controller ') for line in trace))
        self.assertEqual(result.stderr, '')

    def test_clock_failure_or_regression_stops_without_removing_controller(self):
        for setup in ['CLOCK_FAIL_AT=0', 'CLOCK_FAIL_AT=20', 'CLOCK_REGRESS_AT=20']:
            with self.subTest(setup=setup):
                result, trace, _ = self.run_cleanup('RELEASE_AT=100; ' + setup)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(line.startswith(('rmdir controller ', 'rmdir root ', 'rmdir lock ')) for line in trace))
                self.assertLessEqual(sum(line.startswith('sleep ') for line in trace), 2)

    def test_busy_md_or_observer_still_prevents_migration(self):
        for role in ['md', 'observer']:
            with self.subTest(role=role):
                result, trace, migrated = self.run_cleanup('BUSY_ROLE=' + role)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(migrated, '')
                self.assertFalse(any(line.startswith(('rmdir ', 'sleep ')) for line in trace))

    def test_parent_validation_and_migration_failure_stop_before_removal(self):
        for kind in ['missing', 'symlink', 'write_error']:
            with self.subTest(kind=kind):
                result, trace, _ = self.run_cleanup(parent_kind=kind)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(line.startswith(('rmdir ', 'sleep ', 'clock ')) for line in trace))

    def test_rmdir_error_is_never_retried_and_never_removes_following_paths(self):
        roles = ['md', 'observer', 'controller', 'root', 'lock']
        for i, role in enumerate(roles):
            with self.subTest(role=role):
                result, trace, _ = self.run_cleanup('FAIL_RMDIR=' + role)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual([line.split()[1] for line in trace if line.startswith('rmdir ')], roles[:i+1])

    def test_sleep_error_does_not_turn_into_unbounded_polling(self):
        result, trace, _ = self.run_cleanup('RELEASE_AT=100; SLEEP_FAIL=1')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(trace[-1], 'sleep 0')
        self.assertFalse(any(line.startswith('rmdir controller ') for line in trace))


if __name__ == '__main__':
    unittest.main(verbosity=2)
