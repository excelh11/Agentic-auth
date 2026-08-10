import { useEffect, useState } from "react";
import {
  API_BASE,
  callApi,
  deactivateAgent,
  decodeJwt,
  delegate,
  extractError,
  forgeAudience,
  forgeExpiredToken,
  isAgentRegisterResponse,
  isDelegationResponse,
  isLoginResponse,
  isRefreshResponse,
  login,
  refresh,
  registerAgent,
  timeLeft,
  tokenStore,
  type CallResult,
} from "./api";
import type { LogEntry } from "./types";

/** F8-3. 각 버튼이 무엇을 검증하는지 화면에 같이 띄운다 */
interface TestCase {
  label: string;
  detail: string;
  expect: string;
  spec: string;
  run: () => Promise<CallResult>;
  needsLogin: boolean;
}

export default function App() {
  const [email, setEmail] = useState("user1@aaa.com");
  const [password, setPassword] = useState("1111");

  const [accessToken, setAccessToken] = useState(tokenStore.getAccess());
  const [refreshToken, setRefreshToken] = useState(tokenStore.getRefresh());

  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [tick, setTick] = useState(0); // 남은 시간 표시를 1초마다 다시 그리기 위한 값
  const [busy, setBusy] = useState(false);

  /* ── F9~F13. 위임 인증 상태 ──
     위임 토큰은 사용자 토큰과 별도로 들고 있는다. 섞으면 "누가 호출한 것인지"가
     화면에서부터 흐려져서, 이 화면이 보여주려는 것 자체가 사라진다. */
  const [agentId, setAgentId] = useState<string | null>(null);
  const [agentName, setAgentName] = useState("일정 봇");
  const [agentToken, setAgentToken] = useState<string | null>(null);
  const [agentScope, setAgentScope] = useState<string[]>([]);
  const [agentRevoked, setAgentRevoked] = useState(false);

  useEffect(() => {
    const id = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);

  const claims = accessToken ? decodeJwt(accessToken) : null;
  const expired = claims ? claims.exp * 1000 <= Date.now() : false;

  function addLog(title: string, result: CallResult | null, note?: string) {
    const code = result ? extractError(result.data) : null;
    setLogs((prev) => [
      {
        id: Date.now() + Math.random(),
        time: new Date().toLocaleTimeString("ko-KR"),
        title,
        status: result ? result.status : null,
        body: note ?? (result ? formatBody(result) : ""),
        ok: !code,
      },
      ...prev,
    ]);
  }

  function formatBody(result: CallResult): string {
    if (typeof result.data === "string") return result.data;
    return JSON.stringify(result.data, null, 2);
  }

  async function run(title: string, fn: () => Promise<CallResult>) {
    setBusy(true);
    try {
      const result = await fn();
      addLog(title, result);
      return result;
    } catch (e) {
      // fetch 자체가 실패 = 서버 미기동 또는 CORS 차단
      addLog(
        title,
        null,
        `요청 실패: ${String(e)}\n\n` +
          `· 백엔드가 ${API_BASE} 에 떠 있는지 확인하세요.\n` +
          `· 브라우저 콘솔에 "blocked by CORS policy"가 있으면 F6(CORS) 문제입니다.`,
      );
      return null;
    } finally {
      setBusy(false);
    }
  }

  /* ── F1. 로그인 ── */
  async function doLogin(pw: string, title: string) {
    const result = await run(title, () => login(email, pw));
    if (result && isLoginResponse(result.data)) {
      tokenStore.save(result.data.accessToken, result.data.refreshToken);
      setAccessToken(result.data.accessToken);
      setRefreshToken(result.data.refreshToken);
    }
  }

  function doLogout() {
    tokenStore.clear();
    setAccessToken(null);
    setRefreshToken(null);
    addLog("토큰 삭제", null, "localStorage를 비웠습니다.");
  }

  /* ── F5. 갱신 ── */
  async function doRefresh(withRefreshToken: boolean) {
    if (!accessToken) return;

    const before = accessToken;
    const result = await run(
      withRefreshToken
        ? "GET /api/member/refresh"
        : "GET /api/member/refresh (refreshToken 없이)",
      () => refresh(before, withRefreshToken ? refreshToken : null),
    );

    if (result && isRefreshResponse(result.data)) {
      const changed = result.data.accessToken !== before;
      tokenStore.save(result.data.accessToken, result.data.refreshToken);
      setAccessToken(result.data.accessToken);
      setRefreshToken(result.data.refreshToken);
      addLog(
        "갱신 판정",
        null,
        changed
          ? "accessToken이 새로 발급되었습니다. (기존 토큰이 만료 상태였음)"
          : "accessToken이 아직 유효하여 기존 토큰 쌍을 그대로 반환했습니다. (F5 판정 3)",
      );
    }
  }

  /* ── F8-4. 강제 만료 ── */
  function doExpire() {
    if (!accessToken) return;
    const forged = forgeExpiredToken(accessToken);
    if (!forged) return;

    tokenStore.setAccess(forged);
    setAccessToken(forged);
    addLog(
      "accessToken 강제 만료",
      null,
      "exp를 1분 전으로 조작했습니다. payload를 건드렸으므로 서명도 깨집니다.\n" +
        "→ 보호 API 호출 시 ERROR_ACCESS_TOKEN, refresh 호출 시 새 토큰 발급이 정상입니다.\n" +
        "(refreshToken은 그대로이므로 refresh로 복구됩니다.)",
    );
  }

  /* ── F9~F13. 위임 인증 ── */

  /** F9. 에이전트 등록 — 사용자 본인 토큰으로만 가능하다. 이름은 필수(F9-5). */
  async function doRegisterAgent() {
    if (!accessToken) return;

    const result = await run(
      `POST /api/agent/register (${agentName || "이름 없음"})`,
      () =>
        registerAgent(
          accessToken,
          agentName,
          "사용자를 대신해 일정을 확인하고 회의를 잡는다",
        ),
    );

    if (result && isAgentRegisterResponse(result.data)) {
      setAgentId(result.data.agentId);
      setAgentToken(null);
      setAgentScope([]);
      setAgentRevoked(false);
    }
  }

  /** F9~F11. 위임 토큰 발급. scope 를 좁게 준다 — 그게 이 기능의 요점이다. */
  async function doDelegate(scope: string[], title: string) {
    if (!accessToken || !agentId) return;

    const result = await run(title, () =>
      delegate(accessToken, agentId, scope),
    );

    if (result && isDelegationResponse(result.data)) {
      setAgentToken(result.data.accessToken);
      setAgentScope(result.data.scope);
      setAgentRevoked(false);
    }
  }

  /** F9-1. 위임 토큰 payload 를 열어 sub/act/scope/aud 를 보여준다. */
  function doShowDelegatedPayload() {
    if (!agentToken) return;

    const c = decodeJwt(agentToken);
    if (!c) return;

    addLog(
      "위임 토큰 payload",
      null,
      JSON.stringify(c, null, 2) +
        "\n\n" +
        `sub(위임자) = ${c.sub ?? "—"}\n` +
        `act(행위자) = ${c.act ?? "—"}\n` +
        `scope       = ${(c.scope ?? []).join(", ") || "—"}\n` +
        `aud(대상)   = ${c.aud ?? "—"}\n\n` +
        '사용자 본인 토큰에는 이 넷이 없습니다(F9-2). 있다는 것 자체가 "위임된 호출"이라는 뜻입니다.',
    );
  }

  /** F9-3/F9-4. 개별 회수 — 사용자 본인은 로그아웃되지 않는다. */
  async function doRevokeAgent() {
    if (!accessToken || !agentId) return;

    const result = await run(
      `POST /api/agent/${agentId}/deactivate (에이전트 회수)`,
      () => deactivateAgent(accessToken, agentId),
    );

    if (result && result.status === 200) {
      setAgentRevoked(true);
      addLog(
        "회수 완료",
        null,
        "이제 이 에이전트의 위임 토큰은 만료 전이라도 거부됩니다(F9-3).\n" +
          '사용자 본인 토큰은 그대로 살아 있습니다 — 위 "현재 토큰 상태"를 보세요(F9-4).',
      );
    }
  }

  /* ── F8-3. 테스트 케이스 ── */
  const cases: TestCase[] = [
    {
      label: "보호 API 호출 (토큰 O)",
      detail: "GET /api/sample/user",
      expect: "200 · 인증 주체(email·권한) 반환",
      spec: "F3",
      needsLogin: true,
      run: () => callApi("/api/sample/user", accessToken),
    },
    {
      label: "보호 API 호출 (토큰 X)",
      detail: "GET /api/sample/user · Authorization 헤더 없음",
      expect: "ERROR_ACCESS_TOKEN",
      spec: "F3",
      needsLogin: false,
      run: () => callApi("/api/sample/user", null),
    },
    {
      label: "위조 토큰으로 호출",
      detail: "토큰 뒤에 문자열을 덧붙여 서명을 깨뜨림",
      expect: "ERROR_ACCESS_TOKEN",
      spec: "F3",
      needsLogin: true,
      run: () => callApi("/api/sample/user", accessToken + "tampered"),
    },
    {
      label: "필터 제외 경로 호출",
      detail: "GET /api/sample/public · shouldNotFilter 대상",
      expect: "토큰 없이 200",
      spec: "F3",
      needsLogin: false,
      run: () => callApi("/api/sample/public", null),
    },
    {
      label: "권한 부족 API 호출",
      detail: "GET /api/sample/admin · @PreAuthorize hasRole('ADMIN')",
      expect: "user1이면 403 ERROR_ACCESSDENIED · admin이면 200",
      spec: "F4",
      needsLogin: true,
      run: () => callApi("/api/sample/admin", accessToken),
    },
  ];

  return (
    <div className="wrap">
      <header>
        <h1>Security + JWT + Agent</h1>
        <p className="sub">
          사용자가 쓰는 서비스에 AI가 들어가 있는 경우다. <br />
          챗봇, 사용자 대신 API를 호출하는 AI, MCP 서버에 붙는 에이전트 같은 것.{" "}
          <br />
          이건 배포물의 일부라서 자기 인증이 필요하다. 그래서 요즘 "AI
          에이전트에게 JWT/OAuth를 어떻게 발급할 것인가"가 실제로 뜨거운 주제다.
        </p>

        <section style={{ marginBottom: 20 }}>
          <h2>여기서 확인할 수 있는 것</h2>

          <table>
            <thead>
              <tr>
                <th>구간</th>
                <th>무엇을 보나</th>
                <th className="act">어디서</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>
                  <b>사용자 인증</b>
                  <div className="ep">F1 ~ F8 · 토대</div>
                </td>
                <td className="expect">
                  로그인 · 토큰 검사 · 역할 인가 · 갱신 · CORS가
                  <b> 어느 관문에서 어떻게 막히는지</b>
                </td>
                <td className="act">
                  <span className="spec">F1 F3 F4 F5</span>
                </td>
              </tr>
              <tr>
                <td>
                  <b>에이전트 위임 인증</b>
                  <div className="ep">F9 ~ F13 · 이 프로젝트의 목표</div>
                </td>
                <td className="expect">
                  <b>누군가 나를 대신해 API를 호출할 때</b>, 사용자 토큰을
                  통째로 넘기지 않고
                  <b> 좁은 범위만 위임</b>하는 방법. 그리고{" "}
                  <b>에이전트만 끊는</b> 방법
                  <div className="ep">
                    연동 서비스든 AI 에이전트든 같은 문제 — 다만 AI는 무엇을
                    호출할지 미리 알 수 없어 더 절박하다
                  </div>
                </td>
                <td className="act">
                  <span className="spec">F9 ~ F13</span>
                </td>
              </tr>
            </tbody>
          </table>

          <p className="note">
            <b>사용자 토큰을 에이전트에게 그대로 주면</b> ① 누가 한 건지 구분이
            안 되고 ② 권한이 통째로 넘어가고 ③ 에이전트만 끊을 수 없습니다. 아래{" "}
            <b>F9~F13</b> 섹션에서 그 셋이 각각 어떻게 막히는지 버튼으로
            확인합니다.
            <br />
            <span className="ep">
              이 화면은 <b>위임 메커니즘</b>을 확인하는 곳입니다. 실제 AI가 이
              제약 안에서 움직이는 것까지 보려면 <code>mcp-server/</code> 를
              Claude·Cursor에 연결하세요.
            </span>
          </p>

          <p className="note">
            <code>{API_BASE}</code> 로 <b>절대 URL 직접 호출</b> — proxy를 쓰지
            않으므로 모든 요청이 cross-origin이고 preflight(OPTIONS)가 실제로
            발생합니다. 개발자도구 <b>Network 탭</b>에 <code>OPTIONS</code>가
            먼저 찍히면 CORS(F6)가 살아 있는 것입니다.
          </p>
        </section>
      </header>

      {/* ── F1 ── */}
      <section>
        <h2>
          F1. 로그인 &amp; 토큰 발급{" "}
          <span className="ep">POST /api/member/login</span>
        </h2>
        <div className="form">
          <label>
            이메일
            <input value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <label>
            비밀번호
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
        </div>
        <div className="btns">
          <button
            className="primary"
            disabled={busy}
            onClick={() => doLogin(password, "POST /api/member/login (로그인)")}
          >
            로그인
          </button>
          <button
            disabled={busy}
            onClick={() =>
              doLogin(
                "wrong-password",
                "POST /api/member/login (틀린 비밀번호)",
              )
            }
          >
            틀린 비밀번호로 로그인
          </button>
          <button disabled={!accessToken} onClick={doLogout}>
            토큰 삭제
          </button>
        </div>
        <p className="note">
          테스트 계정 (<code>MemberRepositoryTests.createsTwoTestMembers</code>{" "}
          실행 시 만들어집니다)
          <br />·{" "}
          <button
            className="link"
            onClick={() => {
              setEmail("user1@aaa.com");
              setPassword("1111");
            }}
          >
            user1@aaa.com / 1111
          </button>{" "}
          — ROLE_USER · ADMIN 전용 API 호출 시 403이 나야 정상
          <br />·{" "}
          <button
            className="link"
            onClick={() => {
              setEmail("admin@aaa.com");
              setPassword("1111");
            }}
          >
            admin@aaa.com / 1111
          </button>{" "}
          — ROLE_USER + ROLE_ADMIN · 모든 API 통과
        </p>
      </section>

      {/* ── 토큰 상태 ── */}
      <section>
        <h2>현재 토큰 상태</h2>

        <div className="kv">
          <span className="k">accessToken</span>
          {!accessToken ? (
            <span className="badge warn">없음</span>
          ) : (
            <span className={`badge ${expired ? "fail" : "ok"}`}>
              {claims
                ? expired
                  ? "만료됨"
                  : timeLeft(claims.exp)
                : "해독 불가"}
            </span>
          )}
        </div>
        <div className="token">{accessToken ? accessToken : "—"}</div>

        <div className="kv" style={{ marginTop: 14 }}>
          <span className="k">refreshToken</span>
          <span className={`badge ${refreshToken ? "ok" : "warn"}`}>
            {refreshToken ? "보관 중" : "없음"}
          </span>
        </div>
        <div className="token">{refreshToken ? refreshToken : "—"}</div>

        <div className="kv" style={{ marginTop: 14 }}>
          <span className="k">payload (claims)</span>
        </div>
        <pre className="payload">
          {claims
            ? JSON.stringify(claims, null, 2)
            : "로그인하면 accessToken의 payload가 표시됩니다."}
        </pre>
        {claims && (
          <p className="note">
            JWT는 서명만 될 뿐 암호화되지 않아 누구나 Base64로 열어볼 수
            있습니다. 그래서 payload에는 인가에 필요한 것(<code>email</code> ·{" "}
            <code>nickname</code> · <code>social</code> · <code>roleNames</code>
            )만 담고 <b>비밀번호는 넣지 않습니다</b>.
          </p>
        )}
      </section>

      {/* ── F3 / F4 ── */}
      <section>
        <h2>F3. 토큰 검사 · F4. 인가</h2>
        <p className="note">
          <b>F2(세션리스)는 버튼이 없습니다.</b> "서버가 세션을 만들지{" "}
          <b>않는다</b>"는 성질이라 눌러서 확인할 대상이 없기 때문입니다. 대신
          이 화면 전체가 F2의 증거입니다 — 쿠키도 세션도 쓰지 않고{" "}
          <code>localStorage</code> 의 토큰만으로 모든 호출이 이뤄집니다.
          개발자도구 Application 탭에 세션 쿠키가 없는 것으로 확인할 수
          있습니다.
        </p>
        <table>
          <thead>
            <tr>
              <th>테스트</th>
              <th>기대 결과</th>
              <th className="act" />
            </tr>
          </thead>
          <tbody>
            {cases.map((c) => (
              <tr key={c.label}>
                <td>
                  <b>{c.label}</b>
                  <div className="ep">{c.detail}</div>
                </td>
                <td className="expect">
                  <span className="spec">{c.spec}</span> {c.expect}
                </td>
                <td className="act">
                  <button
                    disabled={busy || (c.needsLogin && !accessToken)}
                    onClick={() => run(c.label, c.run)}
                  >
                    호출
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {/* ── F5 ── */}
      <section>
        <h2>
          F5. 토큰 갱신 <span className="ep">/api/member/refresh</span>
        </h2>
        <div className="btns">
          <button
            className="primary"
            disabled={busy || !accessToken}
            onClick={() => doRefresh(true)}
          >
            refresh 호출
          </button>
          <button
            disabled={busy || !accessToken}
            onClick={() => doRefresh(false)}
          >
            refreshToken 없이 호출
          </button>
          <button disabled={!accessToken} onClick={doExpire}>
            accessToken 강제 만료
          </button>
        </div>
        <p className="note">
          · accessToken이 <b>유효하면</b> 기존 토큰 쌍을 그대로 돌려줍니다.
          <br />· <b>강제 만료</b> 후에 눌러야 새 accessToken이 발급됩니다.
          <br />· refreshToken 없이 호출하면 <code>NULL_REFRASH</code> 가 와야
          합니다.
        </p>
      </section>

      {/* ── F9~F13 ── */}
      <section>
        <h2>
          F9~F13. 에이전트 위임 인증 <span className="ep">/api/agent/**</span>
        </h2>

        <p className="note">
          AI 에이전트가 <b>사용자를 대신해</b> API를 호출할 때, 사용자 토큰을
          그대로 넘기면 ① 누가 한 건지 구분이 안 되고 ② 권한이 통째로 넘어가고 ③
          에이전트만 끊을 수 없습니다.
          <br />
          아래는 <b>발레 파킹 키</b>와 같습니다 — 위임자는 그대로 두고, 행위자(
          <code>act</code>)와 범위(<code>scope</code>)를 따로 실은 별도 토큰을
          발급합니다.
        </p>

        <div className="form">
          <label>
            에이전트 이름 <small>(필수 — 감사 로그에 이 이름이 남는다)</small>
            <input
              value={agentName}
              onChange={(e) => setAgentName(e.target.value)}
              placeholder="예: 일정 봇"
            />
          </label>
        </div>

        <div className="kv">
          <span className="k">진행 단계</span>
          <span className={`badge ${accessToken ? "ok" : "warn"}`}>
            {!accessToken
              ? "0 / 3 — 로그인이 필요합니다"
              : !agentId
                ? "1 / 3 — 에이전트를 등록하세요"
                : !agentToken
                  ? "2 / 3 — 위임 토큰을 발급하세요"
                  : "3 / 3 — 아래 4번부터 확인할 수 있습니다"}
          </span>
        </div>

        <div className="kv" style={{ marginTop: 14 }}>
          <span className="k">에이전트</span>
          {!agentId ? (
            <span className="badge warn">미등록</span>
          ) : (
            <span className={`badge ${agentRevoked ? "fail" : "ok"}`}>
              {agentRevoked ? "회수됨(비활성)" : "활성"}
            </span>
          )}
        </div>
        <div className="token">
          {agentId ? `${agentName} · ${agentId}` : "—"}
        </div>

        <div className="kv" style={{ marginTop: 14 }}>
          <span className="k">위임 토큰</span>
          <span className={`badge ${agentToken ? "ok" : "warn"}`}>
            {agentToken ? `scope: ${agentScope.join(", ") || "—"}` : "미발급"}
          </span>
        </div>
        <div className="token">{agentToken ?? "—"}</div>

        <div className="btns" style={{ marginTop: 14 }}>
          <button
            className="primary"
            disabled={busy || !accessToken}
            onClick={doRegisterAgent}
          >
            1. 에이전트 등록
          </button>
          <button
            className="primary"
            disabled={busy || !agentId}
            onClick={() =>
              doDelegate(
                ["sample:read"],
                "POST /api/agent/delegate (scope: sample:read 만 위임)",
              )
            }
          >
            2. 위임 토큰 발급 <small>(sample:read 만)</small>
          </button>
          <button disabled={!agentToken} onClick={doShowDelegatedPayload}>
            3. 위임 토큰 payload 보기
          </button>
        </div>

        <p className="note">
          {!accessToken ? (
            <>
              <b>⚠️ 먼저 위의 F1에서 로그인하세요.</b> 위임은{" "}
              <b>사용자가 시작하는 것</b>이라, 로그인하기 전에는 1번부터 잠겨
              있습니다.
            </>
          ) : !agentId ? (
            <>
              <b>1. 에이전트 등록</b>부터 누르세요. 등록해야 위임할 대상이
              생깁니다. (2·3번은 그전까지 잠겨 있습니다)
            </>
          ) : !agentToken ? (
            <>
              에이전트가 등록됐습니다. 이제 <b>2. 위임 토큰 발급</b>을 누르세요.
            </>
          ) : (
            <>
              준비 완료 — <b>3번</b>으로 토큰 안을 확인하고, 아래 <b>4번부터</b>{" "}
              차례로 눌러봅니다.
            </>
          )}
          <br />
          <span className="ep">
            버튼이 흐리게 보이면 앞 단계가 아직 안 끝난 것입니다. 1 → 2 → 3 → 4…
            순서대로 풀립니다.
          </span>
        </p>

        <table style={{ marginTop: 16 }}>
          <thead>
            <tr>
              <th>테스트</th>
              <th>기대 결과</th>
              <th className="act" />
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>
                <b>4. scope 안의 API 호출</b>
                <div className="ep">
                  GET /api/sample/user · 위임받은 sample:read
                </div>
              </td>
              <td className="expect">
                <span className="spec">F10-1</span> 200 · 통과
              </td>
              <td className="act">
                <button
                  disabled={busy || !agentToken}
                  onClick={() =>
                    run("위임 토큰으로 /api/sample/user (scope 안)", () =>
                      callApi("/api/sample/user", agentToken),
                    )
                  }
                >
                  호출
                </button>
              </td>
            </tr>
            <tr>
              <td>
                <b>5. scope 밖의 API 호출</b>
                <div className="ep">
                  GET /api/sample/list · sample:list 는 안 받았음
                </div>
              </td>
              <td className="expect">
                <span className="spec">F10-1</span> 403 ·{" "}
                <code>ERROR_SCOPE</code>
              </td>
              <td className="act">
                <button
                  disabled={busy || !agentToken}
                  onClick={() =>
                    run("위임 토큰으로 /api/sample/list (scope 밖)", () =>
                      callApi("/api/sample/list", agentToken),
                    )
                  }
                >
                  호출
                </button>
              </td>
            </tr>
            <tr>
              <td>
                <b>6. audience 조작 호출</b>
                <div className="ep">
                  aud 를 다른 서버로 바꿔치기 · 서명이 깨진다
                </div>
              </td>
              <td className="expect">
                <span className="spec">F11</span> 401 ·{" "}
                <code>ERROR_ACCESS_TOKEN</code>
                <div className="ep">
                  ⚠️ <code>ERROR_AUDIENCE</code> 가 아니다 — 아래 설명 참고
                </div>
              </td>
              <td className="act">
                <button
                  disabled={busy || !agentToken}
                  onClick={() =>
                    run("위임 토큰의 aud 를 조작해 호출", () =>
                      callApi(
                        "/api/sample/user",
                        forgeAudience(
                          agentToken!,
                          "https://someone-else.example.com",
                        ),
                      ),
                    )
                  }
                >
                  호출
                </button>
              </td>
            </tr>
            <tr>
              <td>
                <b>7. 권한을 넘어서는 위임 시도</b>
                <div className="ep">
                  user1(ROLE_USER)이 sample:admin 을 위임하려 함
                </div>
              </td>
              <td className="expect">
                <span className="spec">F10-2</span>{" "}
                <code>ERROR_SCOPE_EXCEEDS_ROLE</code>
                <div className="ep">위임은 권한을 넓힐 수 없다</div>
              </td>
              <td className="act">
                <button
                  disabled={busy || !agentId}
                  onClick={() =>
                    doDelegate(
                      ["sample:admin"],
                      "POST /api/agent/delegate (역할 초과 scope 요청)",
                    )
                  }
                >
                  호출
                </button>
              </td>
            </tr>
            <tr>
              <td>
                <b>8. 위임 토큰으로 재위임 시도</b>
                <div className="ep">
                  에이전트가 스스로에게 새 위임을 발급하려 함
                </div>
              </td>
              <td className="expect">
                <span className="spec">F9</span> 거부 · <code>ERROR_SCOPE</code>
                <div className="ep">위임은 사람이 시작해야 한다</div>
              </td>
              <td className="act">
                <button
                  disabled={busy || !agentToken || !agentId}
                  onClick={() =>
                    run("위임 토큰으로 재위임 시도", () =>
                      delegate(agentToken!, agentId!, ["sample:list"]),
                    )
                  }
                >
                  호출
                </button>
              </td>
            </tr>
            <tr>
              <td>
                <b>9. 에이전트 회수(비활성화)</b>
                <div className="ep">사용자가 위임을 거둬들인다</div>
              </td>
              <td className="expect">
                <span className="spec">F9-3</span> 200 · 회수됨
                <div className="ep">
                  ★ 위 <b>현재 토큰 상태</b>를 보라 — 사용자 토큰은 그대로다
                </div>
              </td>
              <td className="act">
                <button
                  disabled={busy || !agentId || agentRevoked}
                  onClick={doRevokeAgent}
                >
                  회수
                </button>
              </td>
            </tr>
            <tr>
              <td>
                <b>10. 회수 후 위임 토큰으로 호출</b>
                <div className="ep">
                  9번을 먼저 누른 뒤 · 토큰은 아직 만료 전이다
                </div>
              </td>
              <td className="expect">
                <span className="spec">F9-3</span> 401 ·{" "}
                <code>ERROR_AGENT_INACTIVE</code>
              </td>
              <td className="act">
                <button
                  disabled={busy || !agentToken}
                  onClick={() =>
                    run("회수 후 위임 토큰으로 /api/sample/user", () =>
                      callApi("/api/sample/user", agentToken),
                    )
                  }
                >
                  호출
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <p className="note">
          <b>
            6번이 <code>ERROR_AUDIENCE</code> 가 아닌 이유
          </b>{" "}
          — 브라우저에는 서명용 개인키가 없어서 payload를 바꾸면{" "}
          <b>서명이 깨집니다.</b> 서버는 서명 검증(<code>JWTUtil</code>)을
          audience 검증(<code>DelegationValidator</code>)보다 <b>먼저</b> 하므로{" "}
          <code>ERROR_ACCESS_TOKEN</code> 이 먼저 나옵니다. 이건 결함이 아니라
          F12(비대칭 서명)가 제대로 동작한다는 증거입니다 —{" "}
          <b>검증만 할 수 있는 쪽은 위조할 수 없습니다.</b>
          <br />
          <code>ERROR_AUDIENCE</code> 자체는 서버 테스트(
          <code>SecurityIntegrationTests</code>)에서 확인합니다.
        </p>
      </section>

      {/* ── 로그 ── */}
      <section>
        <h2>
          응답 로그
          <button className="right" onClick={() => setLogs([])}>
            지우기
          </button>
        </h2>

        <p className="note">
          에러인데 <b>HTTP 200</b>으로 찍히는 것은 정상이 아니라 기록된 결함{" "}
          <b>K2 / K3</b>입니다. 그래서 이 화면은{" "}
          <b>상태코드가 아니라 본문의 에러 코드로</b> 성공/실패를 판정합니다.
        </p>

        {logs.length === 0 ? (
          <pre className="log empty">여기에 요청과 응답이 기록됩니다.</pre>
        ) : (
          logs.map((l) => (
            <pre key={l.id} className={`log ${l.ok ? "" : "bad"}`}>
              <b>
                [{l.time}] {l.title}
                {l.status !== null && ` → HTTP ${l.status}`}
              </b>
              {"\n"}
              {l.body}
            </pre>
          ))
        )}
      </section>

      {/* tick을 참조해야 1초마다 남은 시간이 다시 그려진다 */}
      <span hidden>{tick}</span>
    </div>
  );
}
