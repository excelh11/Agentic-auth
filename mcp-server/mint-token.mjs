#!/usr/bin/env node
/**
 * MCP 서버에 줄 위임 토큰을 발급한다. **이 부분은 사용자가 하는 일이다.**
 *
 * 여기가 위임 경계다 — 이 스크립트는 사용자 비밀번호를 쓰지만,
 * MCP 서버에는 **위임 토큰만** 넘어간다. AI는 사용자 자격증명을 영원히 못 본다.
 *
 *   node mint-token.mjs                          # 기본: sample:read
 *   node mint-token.mjs --scope sample:read,sample:list
 *   node mint-token.mjs --user admin@aaa.com --scope sample:read,sample:admin
 *
 * 의존성 없음. Node 18+ 내장 fetch 를 쓴다.
 */

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith('--')) acc.push([cur.slice(2), arr[i + 1]]);
    return acc;
  }, []),
);

const API = args.api ?? 'http://localhost:8080';
const USER = args.user ?? 'user1@aaa.com';
const PW = args.pw ?? '1111';
const SCOPE = (args.scope ?? 'sample:read').split(',').map((s) => s.trim()).filter(Boolean);
const NAME = args.name ?? 'MCP 서버 (Claude)';

const C = { reset: '\x1b[0m', dim: '\x1b[2m', bold: '\x1b[1m', green: '\x1b[32m', red: '\x1b[31m', yellow: '\x1b[33m' };

async function call(path, { method = 'GET', token, json, form } = {}) {
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
  return { status: res.status, data, error: data?.error ?? null };
}

function die(msg, detail) {
  console.error(`\n${C.red}${msg}${C.reset}`);
  if (detail) console.error(detail);
  process.exit(1);
}

/* ── 1. 사용자 로그인 ─────────────────────────────────────── */

const login = await call('/api/member/login', { method: 'POST', form: { username: USER, password: PW } });

if (login.status !== 200) {
  die(
    `로그인 실패 (${login.status}) — ${login.error ?? ''}`,
    `서버가 ${API} 에 떠 있고 테스트 계정이 있는지 확인하세요.\n` +
      '  cd server\n' +
      '  .\\gradlew.bat test --tests "com.agenticauth.repository.MemberRepositoryTests"\n' +
      '  .\\gradlew.bat bootRun',
  );
}

const userToken = login.data.accessToken;
console.log(`${C.green}✓${C.reset} 로그인 — ${login.data.email} (${login.data.roleNames.join(', ')})`);

/* ── 2. 에이전트 등록 ─────────────────────────────────────── */

const reg = await call('/api/agent/register', {
  method: 'POST',
  token: userToken,
  json: { name: NAME, description: 'MCP 클라이언트(Claude·Cursor)가 사용자를 대신해 호출한다' },
});

if (reg.status !== 200) die(`에이전트 등록 실패 — ${reg.error ?? reg.status}`);

const agentId = reg.data.agentId;
console.log(`${C.green}✓${C.reset} 에이전트 등록 — ${C.bold}${reg.data.name}${C.reset} (${agentId})`);

/* ── 3. 위임 ──────────────────────────────────────────────── */

const del = await call('/api/agent/delegate', {
  method: 'POST',
  token: userToken,
  json: { agentId, scope: SCOPE },
});

if (del.status !== 200) {
  die(
    `위임 실패 — ${del.error ?? del.status}`,
    del.error === 'ERROR_SCOPE_EXCEEDS_ROLE'
      ? `${USER} 의 역할로는 ${JSON.stringify(SCOPE)} 를 위임할 수 없습니다.\n` +
        'ROLE_USER → sample:read, sample:list\nROLE_ADMIN → + sample:admin'
      : null,
  );
}

console.log(`${C.green}✓${C.reset} 위임 — scope: ${C.bold}${del.data.scope.join(', ')}${C.reset}`);

/* ── 4. 붙여넣을 설정 출력 ────────────────────────────────── */

const serverPath = new URL('./server.mjs', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');

const token = del.data.accessToken;

/* Claude Code — 한 줄 명령. JSON 따옴표 escape 를 피할 수 있어 Windows 에서 특히 편하다. */
console.log(`\n${C.bold}${'─'.repeat(64)}${C.reset}`);
console.log(`${C.bold} ① Claude Code — 이 한 줄을 그대로 실행하세요${C.reset}`);
console.log(`${C.bold}${'─'.repeat(64)}${C.reset}\n`);
console.log(
  `claude mcp add agentic-auth -e AAUTH_API=${API} -e AAUTH_DELEGATED_TOKEN=${token} -- node "${serverPath}"`,
);

/* Claude Desktop / Cursor — 설정 파일에 병합하는 형태 */
const config = {
  mcpServers: {
    'agentic-auth': {
      command: 'node',
      args: [serverPath],
      env: { AAUTH_API: API, AAUTH_DELEGATED_TOKEN: token },
    },
  },
};

console.log(`\n${C.bold}${'─'.repeat(64)}${C.reset}`);
console.log(`${C.bold} ② Claude Desktop / Cursor — 설정 파일의 mcpServers 에 병합${C.reset}`);
console.log(`${C.bold}${'─'.repeat(64)}${C.reset}\n`);
console.log(JSON.stringify(config, null, 2));

console.log(`\n${C.yellow}※ accessToken 은 10분이면 만료됩니다.${C.reset} 만료되면 이 스크립트를 다시 실행하세요.`);
console.log(`${C.yellow}※ 회수하려면:${C.reset}`);
console.log(
  `  curl -X POST ${API}/api/agent/${agentId}/deactivate -H "Authorization: Bearer <사용자토큰>"`,
);
console.log(`${C.dim}  회수하면 MCP 서버는 재시작 없이 즉시 아무것도 못 하게 됩니다.${C.reset}`);
