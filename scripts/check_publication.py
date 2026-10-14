#!/usr/bin/env python3
"""Check tracked source hygiene and local Markdown links; never inspect device state."""
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]

def main():
    raw = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT)
    paths = [Path(p) for p in raw.decode().split('\0') if p]
    if not paths:
        raise SystemExit('No tracked files; stage the public source tree before checking.')
    errors = []
    forbidden_suffixes = {'.apk','.jar','.dex','.img','.bin','.vup','.p12','.pfx','.pk8','.pem','.jks','.keystore','.zip'}
    private_patterns = [r'/Users/[A-Za-z0-9_.-]+/', r'192\.168\.[0-9]+\.[0-9]+',
                        r'-----BEGIN [A-Z ]*PRIVATE KEY-----', r'gh[pousr]_[A-Za-z0-9]{30,}',
                        r'github_pat_[A-Za-z0-9_]{40,}']
    for rel in paths:
        p = ROOT/rel
        if p.is_symlink(): errors.append(f'{rel}: symlink'); continue
        if p.suffix.lower() in forbidden_suffixes: errors.append(f'{rel}: binary/credential extension')
        if any(v in rel.parts for v in ('build','captures','runs','deps','__pycache__')):
            errors.append(f'{rel}: local output directory')
        if p.stat().st_size > 1_000_000: errors.append(f'{rel}: unexpected source file size')
        try: text = p.read_text('utf-8')
        except UnicodeDecodeError: errors.append(f'{rel}: non-text file'); continue
        for pattern in private_patterns:
            if re.search(pattern, text): errors.append(f'{rel}: possible private value ({pattern})')
        if p.suffix == '.md':
            for target in re.findall(r'\]\(([^)]+)\)', text):
                target = target.strip().split(' "',1)[0].strip('<>')
                if re.match(r'[A-Za-z][A-Za-z0-9+.-]*:', target) or target.startswith('#'): continue
                target = unquote(target.split('#',1)[0])
                if target and not (p.parent/target).exists(): errors.append(f'{rel}: broken link {target}')
    if errors:
        print('\n'.join(errors), file=sys.stderr); return 1
    print(f'PASS: {len(paths)} tracked text/source files; local Markdown links resolve; hygiene patterns clear.')
    return 0

if __name__ == '__main__': raise SystemExit(main())
