#!/usr/bin/env python3
"""Preserve original cgroup ownership checks while replacing sed with built-ins."""
from pathlib import Path
import json
import os
import random
import re
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
SCRIPT = ROOT / 'tools/boot_native/runtime/runtime-controller.sh'
OLD_FUNCTION = 'group_matches() {\n    gm=$(readf "/proc/$1/cgroup") || return 1\n    [ "$(printf \'%s\\n\' "$gm" | sed -n \'s/^0:://p\')" = "/carlife-boot-native-$RUN_ID/$2" ] || return 1\n    gv=$(readf "$R/cgroup_v1") || return 1\n    [ "$(printf \'%s\\n\' "$gm" | sed \'/^0::/d\')" = "$gv" ]\n}'
TARGET = '/carlife-boot-native-test_run/md'
SHELL = os.environ.get('TEST_SHELL', '/bin/sh')


def extract(name, text):
    return re.search(r'^' + name + r'\(\) \{.*?^\}', text, re.M | re.S).group()


def oracle(group, v1, group_rc=0, v1_rc=0):
    # $(readf) strips every trailing LF before either sed sees the value.
    reads = ['/proc/42/cgroup']
    if group_rc:
        return 1, reads
    rows = group.rstrip('\n').split('\n')
    v2 = ''.join(row[3:] + '\n' for row in rows if row.startswith('0::')).rstrip('\n')
    if v2 != TARGET:
        return 1, reads
    reads.append('/fixture/cgroup_v1')
    if v1_rc:
        return 1, reads
    other = ''.join(row + '\n' for row in rows if not row.startswith('0::')).rstrip('\n')
    return int(other != v1.rstrip('\n')), reads


class GroupMatchDifferentialTest(unittest.TestCase):
    def test_full_sed_contract(self):
        old = OLD_FUNCTION.replace('group_matches()', 'old_group_matches()', 1)
        new = extract('group_matches', SCRIPT.read_text()).replace('group_matches()', 'new_group_matches()', 1)
        self.assertNotIn('$(', new.split('gm_rest=$gm', 1)[1].split('gv=$(', 1)[0])
        self.assertNotRegex(new, r'\|\s*sed\b')
        cases = []

        def add(label, group, v1='', group_rc=0, v1_rc=0):
            cases.append((label, group, v1, group_rc, v1_rc))

        for suffix in ['', '\n', '\n\n', '\n\n\n']:
            add('valid_empty_v1' + repr(suffix), '0::' + TARGET + suffix)
            add('ordered_v1' + repr(suffix), '7:cpu:/a\n0::' + TARGET + '\n4:memory:/b' + suffix,
                '7:cpu:/a\n4:memory:/b' + suffix)
            add('wrong_v1_order' + repr(suffix), '7:cpu:/a\n0::' + TARGET + '\n4:memory:/b' + suffix,
                '4:memory:/b\n7:cpu:/a' + suffix)
        add('trailing_empty_v2_duplicate_is_accepted_by_old_sed', '0::' + TARGET + '\n0::')
        add('leading_empty_v2_duplicate_is_rejected', '0::\n0::' + TARGET)
        add('duplicate_real_v2_rejected', '0::' + TARGET + '\n0::' + TARGET)
        add('empty_v1_rows', '\n0::' + TARGET + '\n\n')
        add('embedded_v1_blank_preserved', 'cpu:x\n\n0::' + TARGET + '\nmem:y', 'cpu:x\n\nmem:y')
        add('trailing_v1_blank_stripped', 'cpu:x\n\n0::' + TARGET, 'cpu:x')
        add('cr_preserved', '0::' + TARGET + '\r\n')
        add('cr_v1_preserved', 'cpu:x\r\n0::' + TARGET, 'cpu:x\r\n')
        add('literal_shell_characters', '0::' + TARGET + '\n8:cpu:/a\\b*?$`"\t ', '8:cpu:/a\\b*?$`"\t ')
        add('not_prefix', 'x0::' + TARGET + '\n0::' + TARGET, 'x0::' + TARGET)
        add('empty_group', '')
        add('only_newlines', '\n\n\n')
        add('failed_group_read_even_with_valid_bytes', '0::' + TARGET, '', 7, 0)
        add('failed_v1_read_even_with_matching_bytes', '0::' + TARGET, '', 0, 9)
        add('group_failure_does_not_read_v1', '0::' + TARGET, '', 124, 8)
        # The exhaustive 945-case development comparison is narrowed here to
        # focused cases plus deterministic mixed rows, keeping normal CI fast.
        alphabet = ['', '0::' + TARGET, '0::', '0::/other', '7:cpu:/a', '4:memory:/b', ' 0::' + TARGET]
        rng = random.Random(20261017)
        for i in range(64):
            rows = [rng.choice(alphabet + ['\\', '\r', 'x:y:/a b', '\t']) for _ in range(rng.randrange(1, 10))]
            group = '\n'.join(rows) + rng.choice(['', '\n', '\n\n'])
            v1 = '\n'.join(x for x in rows if not x.startswith('0::')) + rng.choice(['', '\n', '\n\n'])
            add('seeded_' + str(i), group, v1)
        # Fixtures replace the *read function*, never write or read actual /proc.
        program = '''set -u
RUN_ID=test_run; R=/fixture
readf() {
    printf '%s\\n' "$1" >> "$TRACE"
    case "$1" in
        /proc/42/cgroup) cat "$CASE/group"; return "$GROUP_RC";;
        /fixture/cgroup_v1) cat "$CASE/v1"; return "$V1_RC";;
        *) return 77;;
    esac
}
''' + old + '\n' + new + '''
for CASE in "$@"; do
    read GROUP_RC V1_RC < "$CASE/rc"
    TRACE=$CASE/old.trace
    old_group_matches 42 md; OLD_RC=$?
    TRACE=$CASE/new.trace
    new_group_matches 42 md; NEW_RC=$?
    printf '%s %s\\n' "$OLD_RC" "$NEW_RC"
done
'''
        with tempfile.TemporaryDirectory(prefix='carlife-group-match-') as tmp:
            paths = []
            for i, (_, group, v1, group_rc, v1_rc) in enumerate(cases):
                case = Path(tmp) / str(i); case.mkdir(); paths.append(case)
                (case / 'group').write_bytes(group.encode())
                (case / 'v1').write_bytes(v1.encode())
                (case / 'rc').write_text(f'{group_rc} {v1_rc}\n')
            result = subprocess.run([SHELL, '-c', program, 'group-match-fixtures', *map(str, paths)],
                                    text=True, capture_output=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stderr, '')
            results = result.stdout.splitlines()
            self.assertEqual(len(results), len(cases))
            for case, path, pair in zip(cases, paths, results):
                label, group, v1, group_rc, v1_rc = case
                with self.subTest(label=label):
                    expected, expected_reads = oracle(group, v1, group_rc, v1_rc)
                    self.assertEqual(pair, f'{expected} {expected}')
                    self.assertEqual((path / 'old.trace').read_text().splitlines(), expected_reads)
                    self.assertEqual((path / 'new.trace').read_text().splitlines(), expected_reads)
        print(json.dumps({'cases': len(cases), 'shell': SHELL, 'device_operations': 0, 'old_new_rc_and_read_order_identical': True}))


if __name__ == '__main__':
    unittest.main(verbosity=2)
