/**
 * 백엔드(server)와의 계약.
 * 필드 이름은 docs/1-SPEC.md 의 F1 응답과 1:1로 맞춘다.
 * 여기를 바꾸려면 SPEC 부터 고쳐야 한다.
 */

/** F1. 로그인 성공 응답 */
export interface LoginResponse {
  email: string;
  nickname: string;
  social: boolean;
  roleNames: string[];
  accessToken: string;
  refreshToken: string;
}

/** F5. 토큰 갱신 응답 */
export interface RefreshResponse {
  accessToken: string;
  refreshToken: string;
}

/**
 * F7. 에러 응답.
 * 백엔드는 필터/핸들러에서는 `error`, ControllerAdvice에서는 `msg` 키를 쓴다.
 */
export interface ErrorResponse {
  error?: string;
  msg?: string;
}

/** F7. 에러 코드 — 백엔드가 내려주는 문자열 전체 목록 */
export const ERROR_CODES = {
  MALFORMED: 'MalFormed',
  EXPIRED: 'Expired',
  INVALID: 'Invalid',
  JWT_ERROR: 'JWTError',
  ERROR: 'Error',
  NULL_REFRESH: 'NULL_REFRASH', // 백엔드 오타지만 계약이므로 그대로 쓴다
  INVALID_STRING: 'INVALID_STRING',
  LOGIN_FAIL: 'ERROR_LOGIN',
  ACCESS_TOKEN: 'ERROR_ACCESS_TOKEN',
  ACCESS_DENIED: 'ERROR_ACCESSDENIED',

  // F9~F13 — 에이전트 위임 인증에서 추가된 코드. 위 기존 코드는 하나도 바뀌지 않았다.
  AUDIENCE: 'ERROR_AUDIENCE', // F11 · aud 가 이 서버가 아님
  AGENT_INACTIVE: 'ERROR_AGENT_INACTIVE', // F9-3 · 미등록·비활성·소유자 불일치
  SCOPE: 'ERROR_SCOPE', // F10-1 · scope 밖 호출 · 재위임 시도
  SCOPE_EXCEEDS_ROLE: 'ERROR_SCOPE_EXCEEDS_ROLE', // F10-2 · 역할을 초과하는 위임 요청
  AGENT_NAME_REQUIRED: 'ERROR_AGENT_NAME_REQUIRED', // F9-5 · 등록 시 이름 누락
} as const;

/** F9. 에이전트 등록 응답 */
export interface AgentRegisterResponse {
  agentId: string;
  /** F9-5 — 사람이 읽는 이름. 감사 로그와 승인 화면이 이걸 쓴다 */
  name: string;
  description: string;
  ownerEmail: string;
  active: boolean;
}

/** F9~F11. 위임 토큰 발급 응답 */
export interface DelegationResponse {
  accessToken: string;
  refreshToken: string;
  sub: string;
  act: string;
  /** F13-3 — 감사 로그에 UUID 대신 이 이름이 남는다 */
  agentName: string;
  scope: string[];
  aud: string;
}

/**
 * accessToken payload — JWTUtil이 심는 claims.
 * 비밀번호는 담기지 않는다. payload는 서명될 뿐 암호화되지 않기 때문이다.
 */
export interface JwtClaims {
  email: string;
  nickname: string;
  social: boolean;
  roleNames: string[];
  iat: number;
  exp: number;

  /**
   * F9~F11 — 위임 토큰에만 실린다. 사용자 본인 로그인 토큰(F1)에는 없다(F9-2).
   * 이 넷의 유무가 "본인 호출"과 "위임 호출"을 가른다.
   */
  sub?: string; // 위임자 (권한의 출처) — 인가 판정의 정본
  act?: string; // 행위자 (실제로 호출하는 에이전트)
  scope?: string[]; // 이 위임으로 할 수 있는 동작
  aud?: string; // 이 토큰이 통하는 서버
}

/** 화면 로그 한 줄 */
export interface LogEntry {
  id: number;
  time: string;
  title: string;
  status: number | null;
  body: string;
  ok: boolean;
}
