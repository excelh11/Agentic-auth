# mcp-server — Claude·Cursor가 직접 이 API를 호출한다

> `agent-example/` 가 위임 흐름을 **스크립트로 증명**했다면, 여기서는 **진짜 AI가 행위자**다.
> MCP 클라이언트(Claude Code·Claude Desktop·Cursor)를 붙이면
> 그 AI가 **사용자를 대신해** agentic-auth API를 호출한다.
>
> "런타임 에이전트의 인증"이 문자 그대로 성립하는 지점이다.

---

## 인증 모델 — 여기가 요점이다

```
사용자 ──[비밀번호]──> mint-token.mjs ──[위임 토큰]──> MCP 서버 ──> API
                                                          ↑
                                                    AI는 여기부터만 안다
```

**MCP 서버는 사용자 비밀번호도, 사용자 토큰도 모른다.**
환경변수로 받은 **위임 토큰 하나**만 들고 있고, 그 안의 `scope` 밖은 백엔드가 거부한다.

**사용자가 회수하면 이 서버는 즉시 아무것도 못 하게 된다.**
프로세스를 재시작할 필요도, 설정을 바꿀 필요도 없다 — 백엔드가 **매 요청마다** 확인하기 때문이다.

---

## 설치

```bash
cd mcp-server
npm install          # @modelcontextprotocol/sdk
```

## 1. 사용자가 위임한다

```bash
node mint-token.mjs                                    # sample:read 만
node mint-token.mjs --scope sample:read,sample:list
node mint-token.mjs --user admin@aaa.com --scope sample:read,sample:admin
```

로그인 → 에이전트 등록 → 위임까지 하고, **붙여넣을 설정 JSON을 출력한다.**

## 2. MCP 클라이언트에 붙인다

**Claude Code**

```bash
claude mcp add-json agentic-auth '{
  "command": "node",
  "args": ["<절대경로>/mcp-server/server.mjs"],
  "env": {
    "AAUTH_API": "http://localhost:8080",
    "AAUTH_DELEGATED_TOKEN": "<mint-token.mjs 가 출력한 토큰>"
  }
}'
```

**Claude Desktop / Cursor** — 설정 파일의 `mcpServers` 에 출력된 JSON을 병합한다.

## 3. AI에게 시켜본다

> "내가 너에게 무슨 권한을 줬는지 확인해줘"  → `whoami`
> "내 프로필 보여줘"                          → `get_profile`
> "목록 가져와줘"                             → `list_items`

`sample:read` 만 위임했다면 세 번째에서 **AI가 거부당하고 그 사실을 설명한다.**

---

## 노출하는 도구

| 도구 | 필요한 scope | 하는 일 |
|---|---|---|
| `whoami` | — | **자기 한계를 안다.** 위임자·행위자·허용 범위·남은 시간을 돌려준다 |
| `get_profile` | `sample:read` | 위임자의 프로필 조회 |
| `list_items` | `sample:list` | 목록 조회 |
| `admin_report` | `sample:admin` + ADMIN 역할 | 관리자 리포트 |

**거부는 `isError` 로 돌려준다.** AI가 실패를 성공으로 오해하면 안 되기 때문이다.
사유도 사람이 읽는 문장으로 바꿔서 보낸다 — AI가 사용자에게 그대로 옮길 수 있어야 한다.

```
거부됨 — 이 호출은 위임받은 범위(scope) 밖입니다.
        지금 가진 scope: ["sample:read"]. 사용자가 위임 범위를 넓혀야 가능합니다.

거부됨 — 사용자가 이 에이전트를 회수했습니다.
        토큰이 아직 만료되지 않았어도 더 이상 쓸 수 없습니다.
```

---

## 검증

MCP 서버는 **붙여봐야 아는 것**이다. 프로토콜이 조금만 어긋나도 클라이언트가 조용히 연결에 실패하고,
서버 쪽에서는 그 사실을 알 수 없다. 그래서 공식 SDK의 `Client` 로 실제로 붙어서 확인한다.

```bash
node test-client.mjs      # 자립형 — 토큰을 스스로 발급한다. 백엔드만 떠 있으면 된다
```

확인하는 것:

1. `initialize` 성공 — **실제 MCP 클라이언트가 붙을 수 있다**
2. 도구 4개가 설명과 함께 노출된다
3. scope 안 호출은 통과, 밖은 `isError` 로 거부
4. **★ 연결을 유지한 채 사용자가 회수하면 다음 호출부터 즉시 막힌다**
5. 그런데 **사용자 본인 토큰은 그대로 살아 있다**

4번이 이 프로젝트의 핵심 주장이다. MCP 서버 프로세스도, 연결도, 토큰도 그대로이고
토큰은 아직 만료 전인데 **다음 요청부터 거부된다.**

기대와 다르면 종료 코드 1로 끝나므로 CI에 그대로 걸 수 있다.

---

## 알아둘 것

- **`accessToken` 은 10분**이다. 만료되면 `mint-token.mjs` 를 다시 실행하고 설정을 갱신한다.
  (자동 갱신을 넣지 않은 이유 — 갱신까지 서버가 하면 "짧은 수명"이라는 성질이 사라진다)
- **stdout 은 MCP 프로토콜 전용**이다. `server.mjs` 의 로그가 전부 `console.error` 인 이유다.
  stdout에 한 줄이라도 섞이면 클라이언트가 파싱에 실패한다.
- 이 폴더는 **npm 의존성이 있다**(`@modelcontextprotocol/sdk`).
  `agent-example/` 은 의존성이 0개이니, 가볍게 흐름만 보려면 그쪽을 먼저 본다.
