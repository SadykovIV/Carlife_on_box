"""Test only b() routing with fake tools; no file applets are executed."""
import json
import os
import pathlib
import shlex
import subprocess
import sys
import tempfile
import unittest

from tests.test_boot_native_runtime import function

BUSYBOX = '/data/adb/magisk/busybox'
FILE_APPLETS = ['cat', 'stat', 'sha256sum', 'find', 'sort', 'tr', 'chmod',
                'mv', 'rm', 'ln', 'grep', 'mkdir', 'rmdir']


class FileToolRouterTests(unittest.TestCase):
    def invoke(self, args, *, busybox='executable', bb_rc=0, android_rc=0,
               stdin='', stdout='output\n', stderr=''):
        with tempfile.TemporaryDirectory() as tmp:
            p = pathlib.Path(tmp)
            trace = p/'trace'
            stub = '#!' + sys.executable + '\n' + r'''
import json,os,pathlib,sys
kind=pathlib.Path(sys.argv[0]).name
data=sys.stdin.read()
with open(os.environ['TRACE'],'a') as f:
    f.write(json.dumps({'kind':kind,'argv':sys.argv[1:],'stdin':data})+'\n')
sys.stdout.write(os.environ['STUB_STDOUT'])
sys.stderr.write(os.environ['STUB_STDERR'])
sys.exit(int(os.environ['BB_RC' if kind=='busybox' else 'ANDROID_RC']))
'''
            for name in ['busybox', 'timeout']:
                if name == 'busybox' and busybox == 'missing':
                    continue
                (p/name).write_text(stub)
                (p/name).chmod(0o600 if name == 'busybox' and busybox == 'not_executable' else 0o700)
            code = function('b').replace(BUSYBOX, shlex.quote(str(p/'busybox')))
            code += '\nb ' + ' '.join(shlex.quote(x) for x in args)
            result = subprocess.run(
                ['sh', '-c', 'set -u\n' + code], input=stdin, capture_output=True, text=True,
                env={**os.environ, 'PATH': str(p)+':'+os.environ['PATH'], 'TRACE': str(trace),
                     'BB_RC': str(bb_rc), 'ANDROID_RC': str(android_rc),
                     'STUB_STDOUT': stdout, 'STUB_STDERR': stderr}, timeout=5)
            calls = [json.loads(x) for x in trace.read_text().splitlines()] if trace.exists() else []
            for call in calls:
                call['argv'] = ['<BUSYBOX>' if x == str(p/'busybox') else x for x in call['argv']]
            return result, calls

    def test_exact_file_whitelist_uses_explicit_busybox_timeout_and_applet(self):
        for applet in FILE_APPLETS:
            with self.subTest(applet=applet):
                result, calls = self.invoke([applet, 'fixture'])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, [{'kind': 'busybox',
                                         'argv': ['timeout', '-k', '1', '3', '<BUSYBOX>', applet, 'fixture'],
                                         'stdin': ''}])

    def test_android_and_unlisted_commands_keep_original_route(self):
        for command in ['id', 'kill', 'pidof', 'getprop', 'setprop', 'am', 'cmd',
                        'sh', 'setsid', 'wait', 'cp', 'dd', 'touch', 'statx', 'readlink',
                        '/system/bin/cat', 'busybox']:
            with self.subTest(command=command):
                result, calls = self.invoke([command, 'argument'])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, [{'kind': 'timeout',
                                         'argv': ['-k', '1', '3', command, 'argument'], 'stdin': ''}])

    def test_missing_or_nonexecutable_busybox_falls_back_once(self):
        for state in ['missing', 'not_executable']:
            for applet in ['cat', 'mv', 'rm']:
                with self.subTest(state=state, applet=applet):
                    result, calls = self.invoke([applet, 'source', 'dest'], busybox=state)
                    self.assertEqual(result.returncode, 0)
                    self.assertEqual(calls, [{'kind': 'timeout',
                                             'argv': ['-k', '1', '3', applet, 'source', 'dest'],
                                             'stdin': ''}])

    def test_argument_bytes_order_stdin_stdout_stderr_are_preserved(self):
        args = ['grep', '-F', '-x', '', ' space\tline\nnext ', r'a\b$' + chr(96) + '*?[]', '--', '-file']
        for state, route in [('executable', 'busybox'), ('missing', 'timeout')]:
            with self.subTest(state=state):
                result, calls = self.invoke(args, busybox=state, stdin='binary\0data\nsecond\n',
                                            stdout='result bytes\n', stderr='diagnostic\n')
                self.assertEqual(result.returncode, 0)
                self.assertEqual(result.stdout, 'result bytes\n')
                self.assertEqual(result.stderr, 'diagnostic\n')
                self.assertEqual(len(calls), 1)
                self.assertEqual(calls[0]['kind'], route)
                self.assertEqual(calls[0]['argv'][-len(args):], args)
                self.assertEqual(calls[0]['stdin'], 'binary\0data\nsecond\n')

    def test_busybox_failure_propagates_without_android_retry_even_for_mutation(self):
        for rc in [1, 2, 64, 124, 126, 127, 137, 143]:
            for applet in ['stat', 'mv', 'rm']:
                with self.subTest(rc=rc, applet=applet):
                    result, calls = self.invoke([applet, 'fixture'], bb_rc=rc)
                    self.assertEqual(result.returncode, rc, result.stderr)
                    self.assertEqual(len(calls), 1)
                    self.assertEqual(calls[0]['kind'], 'busybox')

    def test_android_failure_propagates_without_busybox_retry(self):
        for state, command in [('missing', 'cat'), ('executable', 'setprop')]:
            with self.subTest(state=state, command=command):
                result, calls = self.invoke([command, 'fixture'], busybox=state, android_rc=124)
                self.assertEqual(result.returncode, 124)
                self.assertEqual(len(calls), 1)
                self.assertEqual(calls[0]['kind'], 'timeout')

    def test_empty_argv_keeps_original_timeout_error_path_without_unbound_parameter(self):
        result, calls = self.invoke([], android_rc=64)
        self.assertEqual(result.returncode, 64)
        self.assertEqual(calls, [{'kind': 'timeout', 'argv': ['-k', '1', '3'], 'stdin': ''}])


if __name__ == '__main__':
    unittest.main()
