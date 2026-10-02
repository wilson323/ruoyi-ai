import test from 'node:test';
import assert from 'node:assert/strict';
import { validateWhitelist, javaMethodAfterMapping, javaSourceWithoutComments } from './orphan-gate-lib.mjs';

const source = '@RequestMapping("/api/v1/jobs")\nclass Jobs {\n  @PostMapping("/{id}/scan")\n  public Reply scan(@PathVariable String id) { return null; }\n}';
const endpoint = { file: '/repo/controller/Jobs.java', line: 3, methodName: 'scan', method: 'POST', canonical: '/api/v1/jobs/{VAR}/scan' };
const entry = { path: endpoint.canonical, methods: ['POST'], reason: '定时扫描失败时由运维人员手动补跑', owner_card: 'OPS-06', review_date: '2026-10-02', expire: '2026-12-31', added_by: 'owner', evidence: 'Jobs.java#scan' };
function check(overrides = {}, endpoints = [endpoint], content = source) {
  return validateWhitelist({ $schema_version: 1, entries: [{ ...entry, ...overrides }] }, {
    beCanonicalSet: new Set(endpoints.map(e => e.canonical)), beEndpoints: endpoints,
    beFilesContent: new Map([['Jobs.java', content.split('\n')]]), mirrorText: '| OPS-06 |', today: '2026-10-02',
  });
}
function rejected(result) { assert.ok(result.errors.length > 0); assert.equal(result.activeMap.size, 0); }

test('stable anchor survives added lines and matches canonical path plus method', () => {
  const result = check({}, [{ ...endpoint, line: 5 }], '\n\n' + source);
  assert.deepEqual(result.errors, []); assert.equal(result.activeMap.size, 1);
});
test('wrong path fails even when that path exists on another endpoint', () => rejected(check(
  { path: '/api/v1/jobs/other' }, [endpoint, { ...endpoint, line: 8, methodName: 'other', canonical: '/api/v1/jobs/other' }],
)));
test('wrong HTTP method or duplicate methods fail', () => {
  rejected(check({ methods: ['GET'] })); rejected(check({ methods: ['POST', 'POST'] }));
});
test('missing and overloaded method anchors fail', () => {
  rejected(check({ evidence: 'Jobs.java#missing' }));
  rejected(check({}, [endpoint, { ...endpoint, line: 8 }]));
});
test('same basename in separate controller files is ambiguous', () => rejected(check({}, [endpoint, { ...endpoint, file: '/repo/other/Jobs.java' }])));
test('legacy mapping line is accepted but unrelated mapping line cannot exempt path', () => {
  assert.deepEqual(check({ evidence: 'Jobs.java:3' }).errors, []);
  rejected(check({ evidence: 'Jobs.java:1' })); rejected(check({ evidence: 'Jobs.java:99' }));
});
test('six safeguards still reject invalid card/reason/review and expired entries lose exemption', () => {
  rejected(check({ owner_card: 'invented' })); rejected(check({ reason: '内部接口' }));
  rejected(check({ review_date: '2025-01-01' }));
  const expired = check({ expire: '2026-10-01' }); assert.equal(expired.activeMap.size, 0); assert.equal(expired.expiredEntries.length, 1);
});
test('real mapping method extraction skips annotations, generics and comments', () => {
  const input = '@PostMapping("/x")\n/* public Fake wrong() {} */\n@Audit("x")\npublic Reply<List<String>> scan(@Body Req body) { return null; }';
  assert.equal(javaMethodAfterMapping(input, input.indexOf(')') + 1), 'scan');
  assert.equal(javaMethodAfterMapping('@GetMapping("/x")\n@GetMapping("/y")\npublic Reply other() {}', 17), null);
});
test('comment stripping preserves offsets, URLs and removes commented mappings', () => {
  const input = '// @GetMapping("/fake")\n@GetMapping("http://host/x") /* ignored */\npublic Reply real() {}';
  const stripped = javaSourceWithoutComments(input);
  assert.equal(stripped.length, input.length); assert.equal(stripped.split('\n').length, input.split('\n').length);
  assert.ok(stripped.includes('http://host/x')); assert.ok(!stripped.includes('/fake'));
});
