"""C8 identity return/read-order equivalence; fixture I/O only, no Android nodes."""
import pathlib
import shlex
import subprocess
import tempfile
import unittest

from tests.test_boot_native_runtime import TEXT, function

# Pre-C8 oracle is intentionally small and independent of research snapshots.
OLD_FIELDS = r'''fields() {
    pf=$(readf "/proc/$1/stat") || return 1; pf=@@{pf##*) }; set -- $pf
    [ "$#" -ge 20 ] || return 1
    printf '%s %s %s %s\n' "@@{20}" "$4" "$3" "$1"
}'''.replace('@@', '$')
OLD_CMDHASH = r'''cmdhash() { ch=$(b sha256sum "/proc/$1/cmdline") || return 1; printf '%s\n' "@@{ch%% *}"; }'''.replace('@@', '$')
OLD_IDENTITY = r'''identity() {
    id_role=$1
    owned_file "$R/$id_role.identity" 600 || return 1
    read ID_PID ID_START ID_HASH id_extra < "$R/$id_role.identity" || return 1
    number "$ID_PID" && number "$ID_START" && [ "$ID_PID" -gt 1 ] && [ -z "$id_extra" ] || return 1
    idf=$(fields "$ID_PID") || return 1; set -- $idf
    [ "$1" = "$ID_START" ] && group_matches "$ID_PID" "$id_role" || return 1
    [ "$4" = Z ] && return 2
    idh=$(cmdhash "$ID_PID") || return 1
    [ "$idh" = "$ID_HASH" ]
}'''


def stat_row(start='567', state='S', comm='app_process'):
    fields = [state, '1', '2', '3'] + [str(n) for n in range(5, 20)] + [start]
    return '42 (' + comm + ') ' + ' '.join(fields) + '\n'


class IdentityResultTests(unittest.TestCase):
    def compare(self, *, marker='42 567 abc\n', stat=None, group=None, v1='7:cpu:/inherited\n',
                hash_output='abc  /proc/42/cmdline\n', owned_rc=0, stat_rc=0,
                group_rc=0, v1_rc=0, hash_rc=0, expected=0):
        if stat is None:
            stat = stat_row()
        if group is None:
            group = '7:cpu:/inherited\n0::/carlife-boot-native-testrun/md\n'
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp)
            runtime = p/'runtime'
            runtime.mkdir()
            if marker is not None:
                (runtime/'md.identity').write_text(marker)
            for name, value in [('stat', stat), ('group', group), ('hash', hash_output)]:
                (p/name).write_text(value)
            (runtime/'cgroup_v1').write_text(v1)
            setup = r'''
RUN_ID=testrun
owned_file() { printf 'OWN %s %s\n' "$1" "$2" >> "$TRACE"; return "$OWNED_RC"; }
readf() {
    printf 'READ %s\n' "$1" >> "$TRACE"
    case "$1" in
        /proc/42/stat) cat "$FIXTURE/stat"; return "$STAT_RC";;
        /proc/42/cgroup) cat "$FIXTURE/group"; return "$GROUP_RC";;
        "$R/cgroup_v1") cat "$R/cgroup_v1"; return "$V1_RC";;
        *) return 91;;
    esac
}
b() {
    printf 'HASH %s\n' "$*" >> "$TRACE"
    [ "$*" = 'sha256sum /proc/42/cmdline' ] || return 92
    cat "$FIXTURE/hash"; return "$HASH_RC"
}
'''
            setup += '\nFIXTURE=' + shlex.quote(str(p)) + '\nR=' + shlex.quote(str(runtime))
            setup += f'\nOWNED_RC={owned_rc}; STAT_RC={stat_rc}; GROUP_RC={group_rc}; V1_RC={v1_rc}; HASH_RC={hash_rc}\n'
            results = []
            traces = []
            for variant in ['old', 'new']:
                funcs = [function('number'), function('group_matches')]
                funcs += ([OLD_FIELDS, OLD_CMDHASH, OLD_IDENTITY] if variant == 'old' else
                          [function('sample_fields'), function('sample_cmdhash'), function('identity')])
                trace = p/(variant+'.trace')
                code = 'set -u\n' + '\n'.join(funcs) + setup + '\nTRACE=' + shlex.quote(str(trace))
                code += r'''
identity md; rc=$?
printf '%s|%s|%s|%s\n' "$rc" "@@{ID_PID-unset}" "@@{ID_START-unset}" "@@{ID_HASH-unset}"
exit "$rc"
'''.replace('@@', '$')
                result = subprocess.run(['sh', '-c', code], cwd=p, capture_output=True, text=True, timeout=5)
                results.append(result)
                traces.append(trace.read_text().splitlines())
            self.assertEqual(results[0].returncode, expected, results[0].stderr)
            self.assertEqual(results[1].returncode, results[0].returncode, results[1].stderr)
            self.assertEqual(results[1].stdout, results[0].stdout)
            self.assertEqual(results[1].stderr, results[0].stderr)
            self.assertEqual(traces[1], traces[0])
            return traces[1]

    def test_matching_identity_reads_same_evidence_in_same_order(self):
        trace = self.compare()
        self.assertEqual([line.split()[0] for line in trace], ['OWN', 'READ', 'READ', 'READ', 'HASH'])

    def test_owner_marker_validation_stops_before_proc_or_hash(self):
        cases = [('42 567 abc\n', 1), ('0 567 abc\n', 0), ('1 567 abc\n', 0),
                 ('x 567 abc\n', 0), ('42 x abc\n', 0), ('42 567 abc extra\n', 0),
                 ('42 567 abc', 0), ('', 0)]
        for marker, owned_rc in cases:
            with self.subTest(marker=marker, owned_rc=owned_rc):
                trace = self.compare(marker=marker, owned_rc=owned_rc, expected=1)
                self.assertEqual(len(trace), 1)

    def test_failed_stat_reads_and_short_or_empty_records_are_rejected(self):
        for rc in [1, 2, 124, 127, 137]:
            with self.subTest(rc=rc):
                trace = self.compare(stat_rc=rc, expected=1)
                self.assertEqual(len(trace), 2)
        for value in ['', '\n', '42 (app) S 1 2\n', '42 (app) ' + ' '.join(['1']*19) + '\n']:
            with self.subTest(stat=value):
                trace = self.compare(stat=value, expected=1)
                self.assertEqual(len(trace), 2)

    def test_pid_reuse_rejected_before_cgroup_or_cmdline(self):
        trace = self.compare(stat=stat_row(start='568'), expected=1)
        self.assertEqual(len(trace), 2)

    def test_zombie_is_two_only_after_valid_start_and_cgroup_and_never_hashes(self):
        trace = self.compare(stat=stat_row(state='Z'), hash_rc=124, expected=2)
        self.assertEqual([line.split()[0] for line in trace], ['OWN', 'READ', 'READ', 'READ'])
        for change in [dict(stat=stat_row(start='568', state='Z')),
                       dict(stat=stat_row(state='Z'), group='0::/foreign\n'),
                       dict(stat=stat_row(state='Z'), v1='foreign\n')]:
            with self.subTest(change=change):
                trace = self.compare(**change, expected=1)
                self.assertFalse(any(line.startswith('HASH') for line in trace))

    def test_cgroup_mismatch_or_read_error_never_reaches_hash(self):
        for change in [dict(group='0::/foreign\n'), dict(v1='foreign\n'),
                       dict(group_rc=124), dict(v1_rc=124),
                       dict(group='0::/carlife-boot-native-testrun/md\n0::/foreign\n')]:
            with self.subTest(change=change):
                trace = self.compare(**change, expected=1)
                self.assertFalse(any(line.startswith('HASH') for line in trace))

    def test_hash_failure_mismatch_and_malformed_output_preserve_result(self):
        for rc in [1, 2, 124, 127, 137]:
            with self.subTest(rc=rc):
                self.compare(hash_rc=rc, expected=1)
        for output, expected in [('def  /proc/42/cmdline\n', 1), ('', 1), ('abc\n', 0),
                                 ('abc\n\n', 0), ('abc\n /proc/42/cmdline\n', 0),
                                 ('abc\nbad  /proc/42/cmdline\n', 1),
                                 ('abc\t/proc/42/cmdline\n', 1)]:
            with self.subTest(output=output):
                self.compare(hash_output=output, expected=expected)

    def test_proc_comm_parentheses_and_whitespace_keep_original_parser(self):
        for comm in ['app process', 'app (worker)', 'app ) trailing', 'app\\name', 'app\nname']:
            with self.subTest(comm=comm):
                self.compare(stat=stat_row(comm=comm))
        for suffix in ['', '\n', '\n\n', '  extra trailing fields\n']:
            with self.subTest(suffix=suffix):
                self.compare(stat=stat_row().rstrip('\n')+suffix)

    def test_samples_clear_stale_results_on_failure(self):
        code = '\n'.join(['set -u', function('sample_fields'), function('sample_cmdhash'), r'''
readf() { return 124; }; b() { return 124; }
SAMPLED_FIELDS=stale; sample_fields 42; sf_rc=$?
SAMPLED_CMDHASH=stale; sample_cmdhash 42; sh_rc=$?
[ "$sf_rc:$sh_rc:$SAMPLED_FIELDS:$SAMPLED_CMDHASH" = '1:1::' ]
'''])
        r = subprocess.run(['sh', '-c', code], capture_output=True, text=True, timeout=5)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stderr, '')

    def test_inner_reader_exit_stays_isolated_from_identity_owner(self):
        for helper, stub in [('sample_fields', 'readf() { exit 77; }'),
                             ('sample_cmdhash', 'b() { exit 78; }')]:
            with self.subTest(helper=helper):
                code = '\n'.join(['set -u', function(helper), stub,
                                  helper + ' 42; rc=$?; printf "continued:%s\\n" "$rc"'])
                r = subprocess.run(['sh', '-c', code], capture_output=True, text=True, timeout=5)
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertEqual(r.stdout, 'continued:1\n')
                self.assertEqual(r.stderr, '')

    def test_sample_helpers_do_not_change_callers_positional_arguments(self):
        code = '\n'.join(['set -u', function('sample_fields'), function('sample_cmdhash'),
                         'readf() { printf "%s" ' + shlex.quote(stat_row()) + '; }',
                         "b() { printf 'abc  /proc/42/cmdline\\n'; }",
                         'set -- caller argument; sample_fields 42 && sample_cmdhash 42',
                         '[ "$#:$1:$2:$SAMPLED_FIELDS:$SAMPLED_CMDHASH" = "2:caller:argument:567 3 2 S:abc" ]'])
        r = subprocess.run(['sh', '-c', code], capture_output=True, text=True, timeout=5)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_legacy_wrappers_are_still_used_by_non_identity_callers(self):
        for name in ['record_pid', 'pending_pid', 'pending_matches', 'child_exited', 'stop_child']:
            self.assertNotIn('sample_fields', function(name))
            self.assertNotIn('sample_cmdhash', function(name))
        identity = function('identity')
        self.assertNotIn('$(fields ', identity)
        self.assertNotIn('$(cmdhash ', identity)
        self.assertIn('$(readf "/proc/$1/stat")', function('sample_fields'))
        self.assertIn('$(b sha256sum "/proc/$1/cmdline")', function('sample_cmdhash'))


if __name__ == '__main__':
    unittest.main()
