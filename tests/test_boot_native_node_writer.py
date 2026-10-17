"""Node-writer contract with local fixture paths; no USB or Android mutations.

The fake timeout proves routing/deadline arguments and failure propagation, not
Android timeout implementation timing. Shell redirection really writes fixtures.
"""
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
SCRIPT = 'printf "%s\\n" "$2" > "$1"'


class NodeWriterTests(unittest.TestCase):
    def invoke(self, value, *, busybox='executable', failure=0,
               destination='file', filename="node with ' space", initial=b'old bytes'):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            trace = root/'trace.jsonl'
            marker = root/'unexpected-evaluation'
            if callable(value):
                value = value(marker)
            target = root/filename
            if destination == 'file':
                target.write_bytes(initial)
            elif destination == 'directory':
                target.mkdir()
            elif destination == 'missing_parent':
                target = root/'missing'/filename
            else:
                raise ValueError(destination)
            stub = '#!' + sys.executable + '\n' + r'''
import json,os,pathlib,subprocess,sys
kind=pathlib.Path(sys.argv[0]).name
args=sys.argv[1:]
with open(os.environ['TRACE'],'a') as f:
    f.write(json.dumps({'kind':kind,'argv':args})+'\n')
if kind=='busybox' and args and args[0]=='sh':
    sys.exit(subprocess.run(['/bin/sh']+args[1:]).returncode)
if int(os.environ['FAILURE']):
    sys.exit(int(os.environ['FAILURE']))
if kind=='busybox':
    assert args[:5]==['timeout','-k','1','3',sys.argv[0]]
    command=args[4:]
else:
    assert args[:3]==['-k','1','3']
    command=args[3:]
sys.exit(subprocess.run(command).returncode)
'''
            for name in ['busybox', 'timeout']:
                if name == 'busybox' and busybox == 'missing':
                    continue
                p = root/name
                p.write_text(stub)
                p.chmod(0o600 if name == 'busybox' and busybox == 'not_executable' else 0o700)
            code = function('write_node').replace(BUSYBOX, shlex.quote(str(root/'busybox')))
            result = subprocess.run(
                ['/bin/sh', '-c', 'set -u\n' + code + '\nwrite_node "$1" "$2"',
                 'writer-test', str(target), value],
                capture_output=True, timeout=5,
                env={**os.environ, 'PATH':str(root)+':'+os.environ['PATH'],
                     'TRACE':str(trace), 'FAILURE':str(failure)})
            calls = [json.loads(s) for s in trace.read_text().splitlines()] if trace.exists() else []
            for call in calls:
                call['argv'] = ['<BUSYBOX>' if a == str(root/'busybox') else
                                '<TARGET>' if a == str(target) else a for a in call['argv']]
            data = target.read_bytes() if target.is_file() else None
            return result, calls, data, marker.exists()

    def test_busybox_child_keeps_timeout_argv_and_fixed_script(self):
        result, calls, data, _ = self.invoke('a600000.dwc3')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [
            {'kind':'busybox', 'argv':['timeout','-k','1','3','<BUSYBOX>','sh','-c',
                                     SCRIPT,'boot-usb-write','<TARGET>','a600000.dwc3']},
            {'kind':'busybox', 'argv':['sh','-c',SCRIPT,'boot-usb-write','<TARGET>','a600000.dwc3']}])
        self.assertEqual(data, b'a600000.dwc3\n')

    def test_value_bytes_and_final_newline_match_fallback(self):
        for value in ['', '0x2d00', 'value\n', 'a\nb', 'Привет', r'\n%\c', '-n']:
            with self.subTest(value=value):
                outputs = []
                for state in ['executable','missing']:
                    result, calls, data, _ = self.invoke(value, busybox=state)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(data, value.encode()+b'\n')
                    outputs.append(data)
                self.assertEqual(*outputs)

    def test_path_and_value_are_literal_arguments_without_eval(self):
        value = lambda marker: '$(touch '+shlex.quote(str(marker))+') `touch '+shlex.quote(str(marker))+'`; & | * ? $HOME'
        for state in ['executable','missing']:
            with self.subTest(state=state):
                result, calls, data, evaluated = self.invoke(
                    value, busybox=state, filename="node ' $() ;\nother")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertFalse(evaluated)
                self.assertEqual(data, calls[0]['argv'][-1].encode()+b'\n')
                self.assertEqual(calls[0]['argv'][-2], '<TARGET>')

    def test_missing_or_nonexecutable_busybox_uses_android_once(self):
        for state in ['missing','not_executable']:
            with self.subTest(state=state):
                result, calls, data, _ = self.invoke('', busybox=state)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(calls, [{'kind':'timeout','argv':[
                    '-k','1','3','sh','-c',SCRIPT,'boot-usb-write','<TARGET>','']}])
                self.assertEqual(data, b'\n')

    def test_failed_redirection_never_retries_through_other_provider(self):
        for state in ['executable','missing']:
            for destination in ['directory','missing_parent']:
                with self.subTest(state=state,destination=destination):
                    result, calls, data, _ = self.invoke('value', busybox=state,
                                                        destination=destination)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual([c['kind'] for c in calls],
                                     ['busybox','busybox'] if state=='executable' else ['timeout'])
                    self.assertIsNone(data)

    def test_bounded_child_failures_propagate_without_write_or_retry(self):
        for state in ['executable','missing']:
            for failure in [1,124,126,127,137,143]:
                with self.subTest(state=state,failure=failure):
                    result, calls, data, _ = self.invoke('new value', busybox=state,
                                                        failure=failure)
                    self.assertEqual(result.returncode, failure)
                    self.assertEqual(len(calls), 1)
                    self.assertEqual(data, b'old bytes')

    def test_success_truncates_existing_file_instead_of_appending(self):
        for state in ['executable','missing']:
            result, _, data, _ = self.invoke('x', busybox=state, initial=b'long previous value\n')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(data, b'x\n')


if __name__ == '__main__':
    unittest.main()
