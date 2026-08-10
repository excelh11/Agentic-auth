# agentic-auth — Spring Security + JWT 작업 프로젝트

**인증/인가(Spring Security + JWT)만 남긴 독립 실행 가능한 Spring Boot 프로젝트**와
그것을 검증하는 최소 프론트엔드, 그리고 4단계 에이전트 파이프라인이 한 저장소에 들어 있다.
쇼핑몰 기능(상품·장바구니·주문)은 들어 있지 않다.

**이 폴더가 저장소 루트다.** Claude Code는 여기서 실행해야 `.claude/` 의 에이전트와 `/agentic` 가 잡힌다.
**Gradle 프로젝트 루트는 `server/` 다.** `.\gradlew.bat` 은 `server` 로 들어가서 실행한다.

---

## 폴더 구조

```
agentic-auth/                    ← 저장소 루트 · Claude Code 실행 위치
├── CLAUDE.md                        # 이 파일 — 프로젝트 메모리
├── README.md · README.en.md · README.zh-CN.md
├── .claude/
│   ├── agents/                      # 서브 에이전트
│   │   ├── agentic-spec.md          #   1단계 SPEC      — 무엇을?
│   │   ├── agentic-plan.md          #   2단계 PLAN      — 어떻게?
│   │   ├── agentic-tasks.md         #   3단계 TASKS     — 어떤 순서로?
│   │   └── agentic-impl.md          #   4단계 IMPLEMENT — 코드로 구현
│   └── commands/agentic.md          # /agentic 슬래시 커맨드 (4단계 일괄 진행)
├── docs/
│   ├── 0-RULES.md                   # ⓪ 작업 규칙 — 모든 단계보다 앞선다
│   ├── 1-SPEC.md                    # ① 필수 기능에 대한 설명
│   ├── 2-PLAN.md                    # ② 기능 구현에 필요한 기술 목록
│   ├── 3-TEST.md                    # ③ 테스트하는 방법
│   └── diagrams/class-diagrams.md   # 클래스 다이어그램 (Mermaid — 코드가 바뀌면 같이 고친다)
├── server/                      ← Gradle 프로젝트 루트 (`.\gradlew.bat` 실행 위치)
│   ├── build.gradle · settings.gradle
│   ├── gradlew · gradlew.bat · gradle/
│   ├── db/schema.sql                # MariaDB DB·계정·테이블 (참고 DDL 포함)
│   ├── keys/                        # RS256 키쌍 (F12) — .gitignore 대상, 없으면 자동 생성
│   └── src/
│       ├── main/java/com/agenticauth/
│       │   ├── AgenticAuthApplication.java
│       │   ├── config/CustomSecurityConfig.java
│       │   ├── controller/          # APIRefreshController · SampleController · APIAgentController · advice
│       │   ├── domain/              # Member · MemberRole · Agent
│       │   ├── dto/MemberDTO.java
│       │   ├── repository/          # MemberRepository · AgentRepository
│       │   ├── security/            # CustomUserDetailsService · DelegationValidator
│       │   │                        #   · ScopeAuthorizer · ScopeCatalog · filter · handler
│       │   └── util/                # JWTUtil · CustomJWTException
│       ├── main/resources/          # application.properties · logback-spring.xml
│       └── test/java/com/agenticauth/   # 9개 테스트 클래스 (47개 테스트)
├── front/                       ← 검증용 프론트엔드 (Vite + React + TS)
│   └── src/                         # App.tsx · api.ts · types.ts
├── agent-example/               ← 위임 토큰을 쓰는 에이전트 스크립트 (Node, 의존성 없음)
│   └── agent.mjs                    # node agent.mjs — 위임 생애주기 전체를 돈다
└── mcp-server/                  ← MCP 서버 (Claude·Cursor 가 직접 API를 호출)
    ├── server.mjs                   # 도구 4개 노출. stdout 은 프로토콜 전용 — 로그는 stderr
    ├── mint-token.mjs               # 사용자가 위임 토큰을 발급하는 쪽
    └── test-client.mjs              # 실제 MCP 클라이언트로 붙어서 검증 (자립형)
```

> `mcp-server/` 는 **npm 의존성이 있다**(`@modelcontextprotocol/sdk`).
> `front/` 와 마찬가지로 gradle과 무관한 별도 npm 프로젝트다.

---

## 작업 흐름

```
SPEC ──→ PLAN ──→ TASKS ──→ IMPLEMENT
무엇을?   어떻게?   어떤 순서로?   코드로 구현
(목적)    (기술)    (작업 순서)
```

| 단계 | 에이전트 | 코드 수정 | 쓸 수 있는 곳 | 기준 문서 |
|---|---|---|---|---|
| 1. SPEC | `agentic-spec` | ❌ | `docs/1-SPEC.md` (프롬프트 제한) | `docs/1-SPEC.md` |
| 2. PLAN | `agentic-plan` | ❌ | **없음** — 쓰기 도구가 아예 없다 | `docs/2-PLAN.md` |
| 3. TASKS | `agentic-tasks` | ❌ | `docs/` (프롬프트 제한) | `docs/1-SPEC.md` |
| 4. IMPLEMENT | `agentic-impl` | ✅ | 전부 | `docs/3-TEST.md` |

> **강제 수준이 다르다.** 하네스가 실제로 막는 것은 `tools:` 에 **없는** 도구뿐이다.
> `agentic-plan` 만 `Write`·`Edit` 가 없어 구조적으로 막힌다.
> `agentic-spec`·`agentic-tasks` 는 자기 산출 문서를 쓰라고 권한을 받았고,
> **"코드는 안 건드린다"는 프롬프트 지시로만 지켜진다.**

**사용법**

```
/agentic 로그인 응답에 회원 가입일을 추가하고 싶어
/agentic 토큰 만료 시 401 상태코드가 나가게 해줘
```

또는 단계별로 직접 호출한다 — 예: "agentic-spec 에이전트로 요구사항부터 정리해줘"

---

## 대상 시스템 요약

| 항목 | 값 |
|---|---|
| 패키지 | `com.agenticauth` (**변경 금지**) |
| Java / Spring Boot | 21 / 3.5.15 (Spring Security 6.x) |
| JWT 라이브러리 | `io.jsonwebtoken:jjwt` **0.11.5** |
| 필터 계층 직렬화 | Gson 2.10.1 |
| DB | **MariaDB** `localhost:3306/agenticauthdb` (계정 `aauthuser`/`aauthpw`) |
| 서버 포트 | **8080** |
| 검증용 프론트 | `front/` (Vite + React + TS, `:5173`) — **별도 npm 프로젝트** |
| accessToken / refreshToken | **10분 / 1440분** |
| 빌드 | `server/` 에서 `.\gradlew.bat` (Windows) |

### 핵심 소스 위치

| 역할 | 경로 (저장소 루트 기준) |
|---|---|
| 필터체인 / CORS | `server/src/main/java/com/agenticauth/config/CustomSecurityConfig.java` |
| JWT 생성·검증 | `server/src/main/java/com/agenticauth/util/JWTUtil.java` |
| 토큰 검사 필터 | `server/src/main/java/com/agenticauth/security/filter/JWTCheckFilter.java` |
| 로그인/접근거부 핸들러 | `server/src/main/java/com/agenticauth/security/handler/` |
| 인증 주체 | `server/src/main/java/com/agenticauth/dto/MemberDTO.java` |
| 토큰 갱신 | `server/src/main/java/com/agenticauth/controller/APIRefreshController.java` |
| 예외 응답 | `server/src/main/java/com/agenticauth/controller/advice/CustomControllerAdvice.java` |
| 인가 확인용 샘플 API | `server/src/main/java/com/agenticauth/controller/SampleController.java` |

**F9~F13 (에이전트 위임 인증)**

| 역할 | 경로 |
|---|---|
| 위임 토큰 검증 (aud·에이전트 활성·감사 로그) | `server/src/main/java/com/agenticauth/security/DelegationValidator.java` |
| scope 강제 (`@PreAuthorize("@scopeAuth.has(...)")`) | `server/src/main/java/com/agenticauth/security/ScopeAuthorizer.java` |
| 역할→허용 scope 상한 | `server/src/main/java/com/agenticauth/security/ScopeCatalog.java` |
| 에이전트 등록·위임·회수 API | `server/src/main/java/com/agenticauth/controller/APIAgentController.java` |
| 에이전트 엔티티 | `server/src/main/java/com/agenticauth/domain/Agent.java` |
| 감사 로그 설정 | `server/src/main/resources/logback-spring.xml` |

---

## 절대 규칙 · A. 작업 규칙

**아래는 전부 이 저장소에서 실제로 사고가 난 것이다.** 근거와 전체 목록은 [`docs/0-RULES.md`](docs/0-RULES.md) 에 있다.
여기에는 **어기면 되돌릴 수 없거나, 틀린 결론을 내게 되는 것**만 옮겼다.

### 되돌릴 수 없다

- **덮어쓰거나 지우기 전에 대상을 먼저 읽는다.**
  ⚠️ **git 저장소인지 먼저 확인한다** — 아니라면 실수했을 때 복구 수단이 전혀 없다.
  (이 규칙이 쓰인 시점에는 git 저장소가 아니었다)
- **여러 줄에 걸친 복잡한 `sed -i` 치환을 하지 마라.** 특수문자(`|` `&` `(` `)` `<` `>` `/`)가 섞이면 **`Edit` 도구를 쓴다.**
  `sed -e` 를 여러 개 붙이지 않는다 — 하나가 틀리면 나머지도 같이 망가진다.
  > 실제로 `README.md` + `class-diagrams.md` **759줄이 통째로 오염됐고**, git이 없어 되돌릴 수 없었다.
- **`server/keys/`** 를 지우면 RS256 키가 새로 생성되고 **그 전에 발급된 모든 토큰이 무효화된다.**
- **`application.properties`** 는 `.gitignore` 대상이라 사본이 없다.

### 조용히 실패한다 — 검증 결과를 믿기 전에

- **한글이 든 파일을 스크립트로 읽을 때 UTF-8을 명시한다.** PowerShell 5.1의 `Get-Content` 는 **기본이 ANSI**라 한글이 깨진다.
  `[System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)` / `WriteAllText(..., (New-Object System.Text.UTF8Encoding $false))`
  > 이걸 몰라서 mermaid 검증이 **7개 중 6개 오탐**으로 실패했고, 멀쩡한 다이어그램을 "고쳤다".
- **검증이 대량으로 실패하면 대상이 아니라 도구를 먼저 의심한다.**
- **Mermaid 문법 오류는 에러를 내지 않는다.** GitHub은 그냥 안 그린다. 파서로 검증한다.
- **컴파일 경고는 `build.gradle` 의 `-Xlint` 로 켜 두었다. 끄지 마라.**
- **테스트 클래스 실행 순서는 보장되지 않는다.** `agent` 가 `member.email` 을 FK로 참조하므로,
  DB 테스트 전에 `MemberRepositoryTests` 로 계정을 만든다.

### 보고할 때

- **"통과했다"와 "안 바꿨다"는 다르다.** 바꾸지 말라고 한 파일을 바꿨으면 **결과와 무관하게 보고한다.**
- **안 돌린 것을 통과했다고 쓰지 않는다.** 실패는 **출력 원문 그대로** 붙인다.
- **파일이 존재하는 것과 동작하는 것은 다르다.** 진행 상황을 파일 목록으로 판단하지 않는다.
- **회귀가 새 기능보다 우선이다.** 최우선 관문은 `docs/3-TEST.md` §6 **21번** —
  일반 사용자 토큰(`act` 없음)으로 `/api/sample/user`·`/api/sample/list` 가 **200**인가.

### 판단할 때

- **문서와 코드가 어긋나면 코드와 도구가 사실이다.** 기록된 결함도 틀릴 수 있다(K6이 그랬다).
  틀린 기록은 지우지 말고 **"정정됨"으로 근거와 함께 남긴다.**
- **기존 코드의 관행으로 답이 나오는 선택(명명 규칙 등)으로 멈추지 않는다.**
  멈추는 건 **미리 명시된 조건**에서만이다 — 계약 변경, 승인 항목, 지정된 검증 관문 실패.

---

## 절대 규칙 · B. 코드 규칙

1. **패키지명(`com.agenticauth`)을 바꾸지 않는다.**
2. **에러 문자열과 응답 JSON 필드명은 프론트엔드와의 계약이다.** (`ERROR_LOGIN`, `ERROR_ACCESS_TOKEN`, `ERROR_ACCESSDENIED`, `NULL_REFRASH` …) 변경은 사용자 승인 후에만. 추가는 자유.
3. **CORS는 `CustomSecurityConfig` 한 곳에서만.** MVC(`WebMvcConfigurer.addCorsMappings`)에 CORS를 걸면 시큐리티 필터체인이 앞서기 때문에 preflight가 먼저 차단된다. 그래서 이 프로젝트에는 `CustomServletConfig` 자체를 두지 않았다.
4. **claims를 쓰는 쪽(`MemberDTO.getClaims()`)과 읽는 쪽(`JWTCheckFilter`)은 항상 짝으로 수정한다.** 컴파일러가 불일치를 못 잡는다.
5. **Spring Security 5 문법 금지** — `WebSecurityConfigurerAdapter`, `.and()`, `antMatchers()`, `authorizeRequests()`, `@EnableGlobalMethodSecurity`.
6. **jjwt 0.12.x API 금지** — 현재 0.11.5다. `setClaims()`, `parserBuilder()` 를 쓴다.
7. **신규 gradle 의존성 / jjwt 버전 변경 / DB 스키마 변경은 사용자 승인 필수.**
8. `docs/1-SPEC.md`의 **알려진 결함(K1~K9)은 제안만** 하고, 승인 없이 고치지 않는다.
9. **`catch (Exception e)` 의 범위를 최소로 잡는다.** 특히 **필터에서 `filterChain.doFilter()` 는 `try` 밖에서** 호출한다.
   > 이 저장소는 같은 실수를 두 번 했다 — K1(NPE를 catch가 삼켜 원인 왜곡), K9(**DB FK 위반이 `ERROR_ACCESS_TOKEN` 401로 둔갑**).
   > 인증 코드가 인증과 무관한 오류까지 삼키면 디버깅이 몇 배로 길어진다.
10. **`JWTCheckFilter` 를 Spring Bean으로 만들지 않는다.** `Filter` 타입을 Bean으로 노출하면
    서블릿 컨테이너에 **이중 등록**돼 요청당 두 번 실행된다(감사 로그도 두 번 남는다).
    의존성만 Bean으로 받아 `new JWTCheckFilter(...)` 로 조립한다.
11. **`shouldNotFilter()` 의 제외 경로와 `authorizeHttpRequests` 의 `permitAll` 은 짝이다.**
    한쪽만 고치면 필터는 통과시키는데 인가가 막거나 그 반대가 된다.
    또한 **필터 제외 경로(`/api/member/**`)의 컨트롤러는 필요한 검증을 스스로 해야 한다** —
    `APIRefreshController` 가 `DelegationValidator` 를 직접 부르는 이유다.

---

## 실행

```powershell
# 0) DB 준비 (최초 1회) — MariaDB에서 server/db/schema.sql 실행
cd server
# 1) 테스트 계정 생성 (최초 1회)
.\gradlew.bat test --tests "com.agenticauth.repository.MemberRepositoryTests"
# 2) 서버 기동
.\gradlew.bat bootRun            # http://localhost:8080
# 3) 프론트 (별도 터미널 — 저장소 루트에서)
cd front ; npm install ; npm run dev    # http://localhost:5173
```

DB 없이 돌릴 수 있는 테스트 — Security/JWT를 건드리면 최소 이건 통과시킨다.

```powershell
cd server
.\gradlew.bat test --tests "com.agenticauth.util.JWTUtilTests" --tests "com.agenticauth.security.filter.JWTCheckFilterTests"
```

---

## 참고

- `docs/1-SPEC.md`의 명세가 바뀌면 **코드보다 문서를 먼저** 고친다.
