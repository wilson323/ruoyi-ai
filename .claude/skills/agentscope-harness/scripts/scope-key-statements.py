#!/usr/bin/env python3
"""C2: inspect Java statements, never combine unrelated statements in one file."""
import re
import sys

TOKENS = re.compile(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[;,]', re.S)
PAIR = re.compile(r'\+\s*":"\s*\+')
IDENTITY = re.compile(r'\b(?:sessionId|slotId)\b', re.I)

def has_scope_pair(source):
    parts = []
    pos = 0
    for token in TOKENS.finditer(source):
        parts.append(source[pos:token.start()])
        text = token.group()
        if text in (';', ','):
            statement = ''.join(parts)
            # Ignore identity words in string literals; retain ':' literal for the pair check.
            identifiers = re.sub(r'"(?:\\.|[^"\\])*"', '""', statement)
            if PAIR.search(statement) and IDENTITY.search(identifiers):
                return True
            parts = []
        elif text.startswith(('//', '/*')):
            parts.append(' ')
        else:
            parts.append(text)
        pos = token.end()
    return False

def self_test():
    positive = 'String slotId = userId +\n ":" + sessionId;'
    negative = 'String auditId = runId + ":" + revision; String sessionId = request.sessionId();'
    assert has_scope_pair(positive), 'cross-line session key must remain red'
    assert not has_scope_pair(negative), 'unrelated audit ID must not become a session key'
    assert not has_scope_pair('String auditId = runId + ":" + "sessionId";')
    assert not has_scope_pair('// sessionId\nString auditId = runId + ":" + revision;')
    assert not has_scope_pair('event(runId + ":" + revision, source.sessionId());')
    print('C2 fixtures: true session key RED; unrelated audit key GREEN')

if __name__ == '__main__':
    if sys.argv[1:] == ['--self-test']:
        self_test()
    else:
        with open(sys.argv[1], encoding='utf-8') as source:
            sys.exit(0 if has_scope_pair(source.read()) else 1)
