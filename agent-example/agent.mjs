#!/usr/bin/env node
/**
 * agentic-auth — 실제로 위임 토큰을 들고 API를 호출하는 에이전트 예제.
 *
 * 이 파일이 있는 이유:
 *   저장소 이름이 agentic-auth 인데 정작 "AI 에이전트"가 하나도 없었다.
 *   프론트 화면은 사람이 버튼을 눌러 에이전트를 흉내낼 뿐이다.
 *   여기서는 역할을 둘로 나눠 — [사용자] 와 [에이전트] — 위임 경계를 눈에 보이게 한다.
 *
 * 핵심은 이것이다:
 *   에이전트는 사용자 비밀번호도, 사용자 토큰도 모른다.
 *   위임 토큰 하나만 받아서 그 안에 적힌 범위(scope) 안에서만 움직인다.
 *
 * 의존성 없음. Node 18+ 의 내장 fetch 를 쓴다.
 *
 *   node agent.mjs
 *   node agent.mjs --api http://localhost:8080 --user user1@aaa.com --pw 1111
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

/* ── 출력 ──────────────────────────────────────────────── */

const C = {
  reset: '\x1b[0m', dim: '\x1b[2m', bold: '\x1b[1m',
  green: '\x1b[32m', red: '\x1b[31m', yellow: '\x1b[33m',
  blue: '\x1b[34m', magenta: '\x1b[35m',
};

const who = {
  user: `${C.blue}[사용자]${C.reset}`,
  agent: `${C.magenta}[에이전트]${C.reset}`,
};

let step = 0;
function section(title) {
  console.log(`\n${C.bold}${'─'.repeat(62)}${C.reset}`);
  console.log(`${C.bold} ${++step}. ${title}${C.reset}`);
  console.log(`${C.bold}${'─'.repeat(62)}${C.reset}`);
}

function ok(actor, msg) {
  console.log(`  ${who[actor]} ${C.green}✓${C.reset} ${msg}`);
}
function blocked(actor, msg) {
  console.log(`  ${who[actor]} ${C.red}✗${C.reset} ${msg}`);
}
function note(msg) {
  console.log(`    ${C.dim}${msg}${C.reset}`);
}

/* ── HTTP ──────────────────────────────────────────────── */

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

/** JWT payload 를 열어본다. 서명 검증은 서버만 한다 — 여기서 통과했다고 유효한 게 아니다. */
function decode(token) {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
}

/* ── 시나리오 ──────────────────────────────────────────── */

let failures = 0;
function expect(cond, what) {
  if (!cond) {
    failures++;
    console.log(`  ${C.red}!! 기대와 다름:${C.reset} ${what}`);
  }
}

async function main() {
  console.log(`${C.bold}agentic-auth — 위임 인증 데모${C.reset}`);
  console.log(`${C.dim}API: ${API}${C.reset}`);

  /* 1 ─────────────────────────────────────────────────── */
  section('사용자가 로그인한다');

  const login = await call('/api/member/login', {
    method: 'POST',
    form: { username: USER, password: PW },
  });

  if (login.status !== 200) {
    console.error(`\n${C.red}로그인 실패 (${login.status})${C.reset}`, login.data);
    console.error(`\n서버가 ${API} 에 떠 있는지, 테스트 계정이 있는지 확인하세요.`);
    console.error('  cd server');
    console.error('  .\\gradlew.bat test --tests "com.agenticauth.repository.MemberRepositoryTests"');
    console.error('  .\\gradlew.bat bootRun');
    process.exit(1);
  }

  const userToken = login.data.accessToken;
  ok('user', `로그인 성공 — ${login.data.email} (${login.data.roleNames.join(', ')})`);
  note('이 토큰은 사용자 본인 것이다. 에이전트에게 절대 넘기지 않는다.');

  /* 2 ─────────────────────────────────────────────────── */
  section('사용자가 에이전트를 등록한다');

  const reg = await call('/api/agent/register', {
    method: 'POST',
    token: userToken,
    json: { name: '일정 봇', description: '일정을 확인하고 회의를 잡는다' },
  });
  expect(reg.status === 200, '등록이 성공해야 한다');

  const agentId = reg.data.agentId;
  ok('user', `등록 완료 — ${C.bold}${reg.data.name}${C.reset} (${agentId})`);
  note('이름이 필수인 이유: 감사 로그에 UUID만 남으면 무엇이 했는지 알 수 없다 (F9-5)');

  /* 3 ─────────────────────────────────────────────────── */
  section('사용자가 좁은 범위만 위임한다');

  const del = await call('/api/agent/delegate', {
    method: 'POST',
    token: userToken,
    json: { agentId, scope: ['sample:read'] },
  });
  expect(del.status === 200, '위임 발급이 성공해야 한다');

  const agentToken = del.data.accessToken;
  const agentRefresh = del.data.refreshToken;

  ok('user', `위임 완료 — scope: ${C.bold}${del.data.scope.join(', ')}${C.reset}`);
  note('사용자는 sample:list 도 가질 수 있지만 read 만 줬다.');

  console.log();
  const claims = decode(agentToken);
  console.log(`  ${who.agent} 토큰을 받았다. 안을 열어보면:`);
  console.log(`    ${C.dim}sub  (위임자)  ${C.reset}${claims.sub}`);
  console.log(`    ${C.dim}act  (나)      ${C.reset}${claims.act}`);
  console.log(`    ${C.dim}scope(할 수 있는 것) ${C.reset}${JSON.stringify(claims.scope)}`);
  console.log(`    ${C.dim}aud  (쓸 수 있는 곳) ${C.reset}${claims.aud}`);
  note('사용자 본인 토큰에는 이 넷이 없다. 있다는 것 자체가 "위임된 호출"이라는 뜻이다.');

  /* 4 ─────────────────────────────────────────────────── */
  section('에이전트가 자기 일을 한다');

  const inScope = await call('/api/sample/user', { token: agentToken });
  expect(inScope.status === 200, 'scope 안 호출은 통과해야 한다');
  ok('agent', `GET /api/sample/user → ${inScope.status} — 위임받은 범위 안이다`);

  const outScope = await call('/api/sample/list', { token: agentToken });
  expect(outScope.status === 403 && outScope.error === 'ERROR_SCOPE',
    'scope 밖 호출은 403 ERROR_SCOPE 여야 한다');
  blocked('agent', `GET /api/sample/list → ${outScope.status} ${outScope.error}`);
  note('sample:list 를 안 받았다. 사용자는 할 수 있지만 에이전트는 못 한다 (F10-1)');

  console.log();
  const userSame = await call('/api/sample/list', { token: userToken });
  expect(userSame.status === 200, '사용자 본인은 같은 API를 호출할 수 있어야 한다');
  ok('user', `같은 API를 사용자가 호출하면 → ${userSame.status}`);
  note('scope 는 위임에만 걸리는 추가 제약이지, 사용자 권한 체계를 대체하지 않는다.');

  /* 5 ─────────────────────────────────────────────────── */
  section('에이전트가 권한을 넓히려 시도한다');

  const escalate = await call('/api/agent/delegate', {
    method: 'POST',
    token: agentToken,
    json: { agentId, scope: ['sample:list'] },
  });
  expect(escalate.error === 'ERROR_SCOPE', '재위임은 거부돼야 한다');
  blocked('agent', `스스로에게 재위임 시도 → ${escalate.error}`);
  note('위임은 사람이 시작해야 한다. 에이전트는 위임 API 자체를 쓸 수 없다.');

  const tooWide = await call('/api/agent/delegate', {
    method: 'POST',
    token: userToken,
    json: { agentId, scope: ['sample:admin'] },
  });
  expect(tooWide.error === 'ERROR_SCOPE_EXCEEDS_ROLE',
    '역할을 초과하는 위임은 거부돼야 한다');
  blocked('user', `사용자가 sample:admin 을 위임 시도 → ${tooWide.error}`);
  note('user1 은 ROLE_USER 다. 자기가 없는 권한은 위임할 수 없다 (F10-2)');

  /* 6 ─────────────────────────────────────────────────── */
  section('사용자가 에이전트를 회수한다');

  const revoke = await call(`/api/agent/${agentId}/deactivate`, {
    method: 'POST',
    token: userToken,
  });
  expect(revoke.status === 200, '회수가 성공해야 한다');
  ok('user', `회수 완료 — ${revoke.data.name}`);
  note('토큰은 아직 만료 전이다. 그래도 다음 요청부터 막힌다.');

  console.log();
  const afterRevoke = await call('/api/sample/user', { token: agentToken });
  expect(afterRevoke.status === 401 && afterRevoke.error === 'ERROR_AGENT_INACTIVE',
    '회수 후에는 401 ERROR_AGENT_INACTIVE 여야 한다');
  blocked('agent', `아까 되던 호출 → ${afterRevoke.status} ${afterRevoke.error}`);

  const viaRefresh = await call(
    `/api/member/refresh?refreshToken=${encodeURIComponent(agentRefresh)}`,
    { token: agentToken },
  );
  expect(viaRefresh.error === 'ERROR_AGENT_INACTIVE',
    'refresh 로도 부활하면 안 된다');
  blocked('agent', `refresh 로 새 토큰을 받으려는 시도 → ${viaRefresh.error}`);
  note('갱신 경로는 토큰 검사 필터를 안 거친다. 컨트롤러가 직접 막고 있다.');

  console.log();
  const userAlive = await call('/api/sample/user', { token: userToken });
  expect(userAlive.status === 200, '사용자 본인 토큰은 살아 있어야 한다');
  ok('user', `본인 토큰은 그대로 → ${userAlive.status}`);
  note('★ 에이전트만 끊었는데 사용자는 로그아웃되지 않았다. 이게 F9-4 다.');

  /* ─────────────────────────────────────────────────── */
  console.log(`\n${C.bold}${'═'.repeat(62)}${C.reset}`);
  if (failures === 0) {
    console.log(`${C.green}${C.bold} 전부 기대대로 동작했다.${C.reset}`);
    console.log(`${C.dim} 감사 로그를 보려면: server/logs/audit.*.log${C.reset}`);
  } else {
    console.log(`${C.red}${C.bold} ${failures}건이 기대와 달랐다.${C.reset}`);
  }
  console.log(`${C.bold}${'═'.repeat(62)}${C.reset}\n`);

  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(`\n${C.red}실행 실패:${C.reset}`, e.message);
  console.error(`서버가 ${API} 에 떠 있는지 확인하세요.`);
  process.exit(1);
});
