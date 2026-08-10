#!/usr/bin/env node
/**
 * agentic-auth MCP 서버 — 런타임 에이전트의 인증을 문자 그대로 성립시킨다.
 *
 * Claude·Cursor 같은 MCP 클라이언트가 이 서버에 붙으면,
 * 그 AI가 **사용자를 대신해** agentic-auth API를 호출하게 된다.
 * `agent-example/agent.mjs` 가 흐름을 스크립트로 증명했다면, 여기서는 **진짜 AI가 행위자**다.
 *
 * ── 인증 모델 ────────────────────────────────────────────────
 *
 * 이 서버는 **사용자 비밀번호도, 사용자 토큰도 모른다.**
 * 환경변수로 받은 **위임 토큰 하나**만 들고 있고, 그 안의 scope 밖은 서버가 거부한다.
 *
 *   AAUTH_DELEGATED_TOKEN   위임 토큰 (mint-token.mjs 로 발급)
 *   AAUTH_API               기본 http://localhost:8080
 *
 * 사용자가 에이전트를 회수하면 **이 서버는 즉시 아무것도 못 하게 된다.**
 * 프로세스를 재시작할 필요도 없다 — 매 요청마다 서버가 확인하기 때문이다.
 */

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

const API = process.env.AAUTH_API ?? 'http://localhost:8080';
const TOKEN = process.env.AAUTH_DELEGATED_TOKEN ?? '';

/* ── 위임 토큰 들여다보기 ──────────────────────────────────── */

/** payload 를 열어본다. 서명 검증은 서버만 한다 — 여기서 읽었다고 유효한 게 아니다. */
function decode(token) {
  try {
    return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
  } catch {
    return null;
  }
}

const claims = TOKEN ? decode(TOKEN) : null;

/* ── HTTP ──────────────────────────────────────────────────── */

async function callApi(path) {
  if (!TOKEN) {
    return {
      denied: true,
      reason:
        '위임 토큰이 없습니다. AAUTH_DELEGATED_TOKEN 환경변수를 설정하세요.\n' +
        '  cd mcp-server && node mint-token.mjs',
    };
  }

  let res;
  try {
    res = await fetch(API + path, { headers: { Authorization: `Bearer ${TOKEN}` } });
  } catch (e) {
    return { denied: true, reason: `백엔드(${API})에 연결할 수 없습니다: ${e.message}` };
  }

  const raw = await res.text();
  let data = raw;
  try {
    data = JSON.parse(raw);
  } catch {
    /* JSON이 아닐 수 있다 */
  }

  if (res.ok) return { denied: false, data };

  // 거부 사유를 그대로 올려보낸다 — AI가 "왜 못 했는지"를 사용자에게 설명할 수 있어야 한다.
  const code = data?.error ?? `HTTP ${res.status}`;
  return { denied: true, reason: explain(code, res.status), code };
}

/** 에러 코드를 AI가 사용자에게 그대로 옮길 수 있는 문장으로 바꾼다. */
function explain(code, status) {
  const map = {
    ERROR_SCOPE:
      `이 호출은 위임받은 범위(scope) 밖입니다. 지금 가진 scope: ${JSON.stringify(claims?.scope ?? [])}. ` +
      '사용자가 위임 범위를 넓혀야 가능합니다.',
    ERROR_AGENT_INACTIVE:
      '사용자가 이 에이전트를 회수했습니다. 토큰이 아직 만료되지 않았어도 더 이상 쓸 수 없습니다. ' +
      '다시 쓰려면 사용자가 새로 위임해야 합니다.',
    ERROR_AUDIENCE: '이 토큰은 다른 서버용입니다(audience 불일치).',
    ERROR_ACCESS_TOKEN: '토큰이 만료됐거나 유효하지 않습니다. 사용자에게 재위임을 요청하세요.',
    ERROR_ACCESSDENIED: '위임자의 역할 권한으로는 접근할 수 없는 API입니다.',
  };
  return map[code] ?? `호출이 거부됐습니다 (${code}, HTTP ${status}).`;
}

/** 도구 결과를 MCP 형식으로. 거부는 isError 로 올려 AI가 성공으로 오해하지 않게 한다. */
function result(r, okLabel) {
  if (r.denied) {
    return { content: [{ type: 'text', text: `거부됨 — ${r.reason}` }], isError: true };
  }
  return {
    content: [
      { type: 'text', text: `${okLabel}\n\n${JSON.stringify(r.data, null, 2)}` },
    ],
  };
}

/* ── 서버 ──────────────────────────────────────────────────── */

const server = new McpServer({
  name: 'agentic-auth',
  version: '1.0.0',
});

server.registerTool(
  'whoami',
  {
    title: '위임 상태 확인',
    description:
      '내가 누구를 대신해서, 무엇을 할 수 있는 자격으로 움직이고 있는지 확인한다. ' +
      '위임자(sub)·행위자(act)·허용 범위(scope)·대상 서버(aud)를 돌려준다. ' +
      '무엇을 할 수 있는지 모를 때 가장 먼저 호출한다.',
  },
  async () => {
    if (!claims) {
      return {
        content: [
          {
            type: 'text',
            text:
              '위임 토큰이 없습니다. 사용자가 아직 권한을 위임하지 않았습니다.\n' +
              'mcp-server 폴더에서 `node mint-token.mjs` 를 실행해 토큰을 발급받아야 합니다.',
          },
        ],
        isError: true,
      };
    }

    const r = await callApi('/api/sample/user');
    const left = Math.max(0, Math.round(claims.exp - Date.now() / 1000));

    const summary = [
      `위임자(sub)  : ${claims.sub ?? claims.email}`,
      `행위자(act)  : ${claims.act}   ← 나`,
      `허용 범위    : ${JSON.stringify(claims.scope ?? [])}`,
      `대상 서버    : ${claims.aud}`,
      `남은 시간    : ${Math.floor(left / 60)}분 ${left % 60}초`,
      '',
      r.denied ? `현재 상태    : 사용 불가 — ${r.reason}` : '현재 상태    : 정상 (호출 가능)',
    ].join('\n');

    return { content: [{ type: 'text', text: summary }], isError: r.denied };
  },
);

server.registerTool(
  'get_profile',
  {
    title: '사용자 프로필 조회',
    description:
      '위임자의 프로필(이메일·닉네임·권한)을 조회한다. sample:read 범위가 필요하다.',
  },
  async () => result(await callApi('/api/sample/user'), '프로필을 가져왔습니다.'),
);

server.registerTool(
  'list_items',
  {
    title: '목록 조회',
    description:
      '목록을 조회한다. sample:list 범위가 필요하다. ' +
      '범위를 위임받지 못했다면 거부되며, 그 사실을 사용자에게 알려야 한다.',
  },
  async () => result(await callApi('/api/sample/list'), '목록을 가져왔습니다.'),
);

server.registerTool(
  'admin_report',
  {
    title: '관리자 리포트 조회',
    description:
      '관리자 전용 리포트를 조회한다. sample:admin 범위와 ADMIN 역할이 **둘 다** 필요하다. ' +
      '위임자가 ADMIN이 아니면 애초에 이 범위를 위임받을 수 없다.',
  },
  async () => result(await callApi('/api/sample/admin'), '리포트를 가져왔습니다.'),
);

const transport = new StdioServerTransport();
await server.connect(transport);

// stdout 은 MCP 프로토콜 전용이다. 로그는 반드시 stderr 로 — 섞이면 클라이언트가 파싱에 실패한다.
console.error(
  `[agentic-auth mcp] API=${API} ` +
    (claims
      ? `act=${claims.act} scope=${JSON.stringify(claims.scope)}`
      : 'AAUTH_DELEGATED_TOKEN 없음 — mint-token.mjs 로 발급하세요'),
);
