import type {
  AgentRegisterResponse,
  DelegationResponse,
  JwtClaims,
  LoginResponse,
  RefreshResponse,
} from './types';

/**
 * API 서버 주소.
 *
 * ⚠️ Vite proxy를 일부러 쓰지 않는다.
 *    proxy를 켜면 브라우저가 same-origin으로 인식해서
 *    F6(CORS)가 실제로 동작하는지 확인할 수 없게 된다.
 *    여기서 절대 URL로 직접 호출해야 preflight(OPTIONS)가 발생한다.
 */
export const API_BASE = 'http://localhost:8080';

/** 토큰 보관 위치 — 쿠키/세션을 쓰지 않는다 (F2 무상태 유지) */
const AT_KEY = 'aauth_access';
const RT_KEY = 'aauth_refresh';

export const tokenStore = {
  getAccess: () => localStorage.getItem(AT_KEY),
  getRefresh: () => localStorage.getItem(RT_KEY),
  save(accessToken: string, refreshToken: string) {
    localStorage.setItem(AT_KEY, accessToken);
    localStorage.setItem(RT_KEY, refreshToken);
  },
  setAccess(accessToken: string) {
    localStorage.setItem(AT_KEY, accessToken);
  },
  clear() {
    localStorage.removeItem(AT_KEY);
    localStorage.removeItem(RT_KEY);
  },
};

/** 모든 호출의 결과 — 상태코드와 본문 원문을 그대로 보존한다 */
export interface CallResult {
  status: number;
  raw: string;
  data: unknown;
}

async function call(path: string, init: RequestInit = {}): Promise<CallResult> {
  const res = await fetch(API_BASE + path, init);
  const raw = await res.text();

  let data: unknown = raw;
  try {
    data = JSON.parse(raw);
  } catch {
    // JSON이 아닐 수 있다 (에러 HTML 등) — raw를 그대로 쓴다
  }

  return { status: res.status, raw, data };
}

/**
 * F1. 로그인
 * ⚠️ Spring Security formLogin은 form-urlencoded만 읽는다. JSON으로 보내면 실패한다.
 */
export function login(username: string, password: string) {
  return call('/api/member/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password }),
  });
}

/** F3. 보호된 API 호출. token이 null이면 헤더를 아예 붙이지 않는다. */
export function callApi(path: string, token: string | null) {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`; // 'Bearer ' 뒤 공백 필수
  return call(path, { headers });
}

/** F5. 토큰 갱신. withRefreshToken=false면 파라미터를 빼고 호출한다(NULL_REFRASH 확인용). */
export function refresh(accessToken: string, refreshToken: string | null) {
  const query = refreshToken
    ? `?refreshToken=${encodeURIComponent(refreshToken)}`
    : '';
  return call(`/api/member/refresh${query}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
}

/* ── F9~F13. 에이전트 위임 인증 ───────────────────────────────── */

/**
 * F9. 에이전트 등록. 호출한 사용자가 소유자가 된다.
 *
 * `name` 은 필수다(F9-5) — 이름이 없으면 감사 로그에 UUID만 남아
 * "무엇이 실행했는지"를 알 수 없다.
 *
 * ⚠️ 위임 토큰으로는 호출할 수 없다 — 재위임을 막기 위해서다.
 */
export function registerAgent(token: string, name: string, description = '') {
  return call('/api/agent/register', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ name, description }),
  });
}

/**
 * F9~F11. 위임 토큰 발급.
 * scope 는 위임자가 실제로 가진 권한의 부분집합이어야 한다(F10-2) —
 * 넘어서면 ERROR_SCOPE_EXCEEDS_ROLE 이 온다.
 */
export function delegate(token: string, agentId: string, scope: string[]) {
  return call('/api/agent/delegate', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ agentId, scope }),
  });
}

/** F9-3/F9-4. 개별 회수 — 사용자 본인은 로그아웃되지 않는다. */
export function deactivateAgent(token: string, agentId: string) {
  return call(`/api/agent/${encodeURIComponent(agentId)}/deactivate`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
}

/**
 * aud 를 다른 값으로 바꾼 토큰을 만든다 (F11 확인 시도용).
 *
 * ⚠️ forgeExpiredToken 과 같은 한계가 있다 — payload를 건드리면 **서명이 깨진다.**
 *    서버는 서명 검증(JWTUtil)을 audience 검증(DelegationValidator)보다 먼저 하므로,
 *    실제로 돌아오는 코드는 ERROR_AUDIENCE 가 아니라 ERROR_ACCESS_TOKEN 이다.
 *    브라우저는 개인키가 없어 서명을 다시 만들 수 없다 — 이게 F12(비대칭 서명)의 요점이기도 하다.
 */
export function forgeAudience(token: string, aud: string): string | null {
  const claims = decodeJwt(token);
  if (!claims) return null;

  const parts = token.split('.');
  claims.aud = aud;

  const payload = btoa(JSON.stringify(claims))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

  return `${parts[0]}.${payload}.${parts[2]}`;
}

/**
 * accessToken payload 디코딩 — 화면 표시용이다.
 * 서명 검증은 서버만 한다. 여기서 통과했다고 유효한 토큰이 아니다.
 */
export function decodeJwt(token: string): JwtClaims | null {
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join(''),
    );
    return JSON.parse(json) as JwtClaims;
  } catch {
    return null;
  }
}

/**
 * exp를 과거로 바꾼 토큰을 만든다 (F8-4).
 * payload만 조작하므로 서명이 깨진다 → 서버는 반드시 거부해야 한다.
 */
export function forgeExpiredToken(token: string): string | null {
  const claims = decodeJwt(token);
  if (!claims) return null;

  const parts = token.split('.');
  claims.exp = Math.floor(Date.now() / 1000) - 60;

  const payload = btoa(JSON.stringify(claims))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

  return `${parts[0]}.${payload}.${parts[2]}`;
}

/** 만료까지 남은 시간을 사람이 읽는 문자열로 */
export function timeLeft(exp: number): string {
  const ms = exp * 1000 - Date.now();
  if (ms <= 0) return '만료됨';
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return `${m}분 ${s}초 남음`;
}

/** 타입 가드 없이 응답에서 에러 코드만 뽑는다 (F7) */
export function extractError(data: unknown): string | null {
  if (typeof data !== 'object' || data === null) return null;
  const obj = data as Record<string, unknown>;
  if (typeof obj.error === 'string') return obj.error;
  if (typeof obj.msg === 'string') return obj.msg;
  return null;
}

/** 로그인 응답인지 판정 */
export function isLoginResponse(data: unknown): data is LoginResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as LoginResponse).accessToken === 'string'
  );
}

/** 갱신 응답인지 판정 */
export function isRefreshResponse(data: unknown): data is RefreshResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as RefreshResponse).accessToken === 'string'
  );
}

/** F9. 에이전트 등록 응답인지 판정 */
export function isAgentRegisterResponse(
  data: unknown,
): data is AgentRegisterResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as AgentRegisterResponse).agentId === 'string'
  );
}

/** F9~F11. 위임 토큰 발급 응답인지 판정 — act 가 있어야 위임 토큰이다 */
export function isDelegationResponse(data: unknown): data is DelegationResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as DelegationResponse).accessToken === 'string' &&
    typeof (data as DelegationResponse).act === 'string'
  );
}
