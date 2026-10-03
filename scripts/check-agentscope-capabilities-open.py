#!/usr/bin/env python3
"""Static anti-disable contract. Passing is NOT runtime/full-capability acceptance.

Scans production Java only. --self-test proves disable regressions become red
without modifying the working tree. Business DENY/ASK policy is intentionally
not treated as a capability disable.
"""
import argparse
from pathlib import Path
import re
import sys


def code_only(source):
    # Preserve offsets/newlines; exclude Java comments and string literals.
    return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"',
                  lambda m: re.sub(r'[^\n]', ' ', m.group()), source)


def violations(source):
    code = code_only(source)
    if not re.search(r'\bHarnessAgent\b', code):
        return []
    patterns = (r'\.\s*disable\w*\s*\(',
                r'\.\s*(?:skillsEnabled|enable\w+)\s*\(\s*false\b')
    return [(code.count('\n', 0, m.start()) + 1, m.group().strip())
            for pattern in patterns for m in re.finditer(pattern, code)]


def self_test():
    prefix = 'HarnessAgent.builder()'
    cases = [(prefix + '.disableMemoryTools()', True),
             (prefix + '.disableDynamicSkills()', True),
             (prefix + '.skillsEnabled(false)', True),
             (prefix + '.enablePlanMode(false)', True),
             (prefix + '.enablePlanMode().skillsEnabled(true)', False),
             (prefix + ' /* .disableShellTool() */', False),
             (prefix + '.sysPrompt(".disableShellTool()")', False),
             ('Other.builder().disabled(false)', False),
             (prefix + '.toolsConfig(businessDeniedTools)', False)]
    cases.append(('HarnessAgent.Builder configure(HarnessAgent.Builder b) { return b.disableShellTool(); }', True))
    cases.append((prefix + '.enableAgentTracingLog(false)', True))
    for source, red in cases:
        assert bool(violations(source)) == red, source
    print(f'PASS: {len(cases)} positive/negative scanner controls; no production code modified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    consumers = 0
    found = []
    for path in sorted((args.root / 'ruoyi-modules').glob('*/src/main/java/**/*.java')):
        source = path.read_text(encoding='utf-8')
        if re.search(r'\bHarnessAgent\s*\.\s*builder\s*\(', code_only(source)):
            consumers += 1
            print('CONSUMER', path.relative_to(args.root))
        found.extend((path.relative_to(args.root), line, call)
                     for line, call in violations(source))
    for path, line, call in found:
        print(f'FAIL {path}:{line}: {call}')
    if not consumers:
        print('ERROR: no production HarnessAgent builder consumers; cannot validate')
        return 2
    print(f'consumers={consumers} violations={len(found)}; static anti-disable contract only')
    return 1 if found else 0


if __name__ == '__main__':
    sys.exit(main())
