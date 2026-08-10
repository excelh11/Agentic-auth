#!/usr/bin/env node
/**
 * MCP 서버 검증 — 실제 MCP 클라이언트로 붙어서 도구를 호출한다.
 *
 * MCP 서버는 **붙여봐야 아는 것**이다. 프로토콜이 조금만 어긋나도 클라이언트가
 * 조용히 연결에 실패하고, 그 사실을 서버 쪽에서는 알 수 없다.
 *
 * 자립형이다 — 토큰을 스스로 발급하므로 환경변수 준비가 필요 없다.
 * 백엔드만 떠 있으면 된다.
 *
 *   node test-client.mjs
 */

import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const API = process.env.AAUTH_API ?? 'http://localhost:8080';
const C = { reset: '\x1b[0m', dim: '\x1b[2m', bold: '\x1b[1m', green: '\x1b[32m', red: '\x1b[31m' };

let failures = 0;
function check(cond, what, detail) {
  if (cond) console.log(`  ${C.green}PASS${C.reset}  ${what}`);
  else {
    failures++;
    console.log(`  ${C.red}FAIL${C.reset}  ${what}`);
    if (detail) console.log(`        ${detail}`);
  }
}
const section = (t) => console.log(`\n${C.bold}${t}${C.reset}`);
const dim = (t) => console.log(t.split('\n').map((l) => `        ${C.dim}${l}${C.reset}`).join('\n'));

async function api(path, { method = 'GET', token, json, form } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  let body;
  if (json) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(json);
  } else if (form) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    body = new URLSearchParams(form);
  }
  const res = await fetch(API + path, { method, headers, body });
  const raw = await res.text();
  let data = raw;
  try {
    data = JSON.parse(raw);
  } catch {
    /* JSON이 아닐 수 있다 */
  }
  return { status: res.status, data };
}

console.log(`${C.bold}MCP 서버 검증${C.reset}  ${C.dim}${API}${C.reset}`);

/* ── 준비: 사용자가 위임한다 ────────────────────────────── */

const login = await api('/api/member/login', {
  method: 'POST',
  form: { username: 'user1@aaa.com', password: '1111' },
});
if (login.status !== 200) {
  console.error(`\n${C.red}로그인 실패 — 백엔드가 ${API} 에 떠 있는지 확인하세요.${C.reset}`);
  process.exit(1);
}
const userToken = login.data.accessToken;

const reg = await api('/api/agent/register', {
  method: 'POST',
  token: userToken,
  json: { name: 'MCP 테스트', description: 'MCP 서버 검증용' },
});
const agentId = reg.data.agentId;

// sample:read + sample:list 를 위임한다. sample:admin 은 일부러 뺀다.
const del = await api('/api/agent/delegate', {
  method: 'POST',
  token: userToken,
  json: { agentId, scope: ['sample:read', 'sample:list'] },
});

section('준비');
check(del.status === 200, `위임 완료 — scope: ${del.data.scope?.join(', ')}`);

/* ── 연결 ───────────────────────────────────────────────── */

const serverPath = new URL('./server.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');

const transport = new StdioClientTransport({
  command: 'node',
  args: [serverPath],
  env: { ...process.env, AAUTH_API: API, AAUTH_DELEGATED_TOKEN: del.data.accessToken },
  stderr: 'pipe',
});
const client = new Client({ name: 'agentic-auth-test-client', version: '1.0.0' });

section('연결');
await client.connect(transport);
check(true, '서버에 연결됨 (initialize 성공)');

const { tools } = await client.listTools();
const names = tools.map((t) => t.name).sort();
check(names.length === 4, `도구 4개가 노출된다 — ${names.join(', ')}`);
check(
  tools.every((t) => t.description && t.description.length > 20),
  '모든 도구에 설명이 있다 (AI가 언제 쓸지 판단할 근거)',
);

const text = (r) => r.content.map((c) => c.text).join('\n');
const callTool = (name) => client.callTool({ name, arguments: {} });

/* ── 자기 자격을 안다 ───────────────────────────────────── */

section('whoami — AI가 자기 한계를 안다');
const who = await callTool('whoami');
dim(text(who));
check(!who.isError, 'whoami 가 성공한다');
check(text(who).includes('행위자(act)'), '행위자를 보고한다');
check(text(who).includes('sample:read'), '위임받은 scope 를 보고한다');

/* ── scope 안 / 밖 ──────────────────────────────────────── */

section('scope 안 — 위임받은 범위');
check(!(await callTool('get_profile')).isError, 'get_profile (sample:read) 성공');
check(!(await callTool('list_items')).isError, 'list_items (sample:list) 성공');

section('scope 밖 — 위임받지 못한 범위');
const admin = await callTool('admin_report');
check(admin.isError === true, 'admin_report 는 거부된다 (sample:admin 미위임)');
check(text(admin).includes('범위'), '거부 사유가 사람이 읽는 문장으로 온다');
dim(text(admin));

/* ── ★ 회수: 재시작 없이 즉시 막히는가 ──────────────────── */

section('★ 사용자가 회수한다 — 연결은 그대로 둔 채');
const revoke = await api(`/api/agent/${agentId}/deactivate`, { method: 'POST', token: userToken });
check(revoke.status === 200, `회수 완료 — ${revoke.data.name}`);
console.log(`        ${C.dim}MCP 서버 프로세스도, 연결도, 토큰도 그대로다. 토큰은 아직 만료 전이다.${C.reset}`);

const afterProfile = await callTool('get_profile');
const afterList = await callTool('list_items');
const afterWho = await callTool('whoami');

check(afterProfile.isError === true, '회수 직후 get_profile 이 막힌다');
check(afterList.isError === true, '회수 직후 list_items 가 막힌다');
check(
  text(afterProfile).includes('회수'),
  '거부 사유가 "회수됨"으로 온다 (AI가 사용자에게 설명할 수 있어야 한다)',
);
dim(text(afterProfile));
check(afterWho.isError === true, 'whoami 도 사용 불가를 보고한다');

/* ── 사용자는 멀쩡한가 ──────────────────────────────────── */

section('사용자 본인은 영향 없다');
const userStill = await api('/api/sample/list', { token: userToken });
check(userStill.status === 200, `사용자 본인 토큰으로 같은 API 호출 → ${userStill.status}`);
console.log(`        ${C.dim}★ 에이전트만 끊었는데 사용자는 로그아웃되지 않았다 (F9-4)${C.reset}`);

await client.close();

console.log(`\n${'═'.repeat(60)}`);
if (failures === 0) {
  console.log(`${C.green}${C.bold} 전부 통과 — 실제 MCP 클라이언트가 붙을 수 있다.${C.reset}`);
} else {
  console.log(`${C.red}${C.bold} ${failures}건 실패${C.reset}`);
}
console.log('═'.repeat(60));

process.exit(failures === 0 ? 0 : 1);
