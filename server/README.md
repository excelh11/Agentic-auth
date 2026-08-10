# server — agentic-auth 백엔드

Spring Boot 3.5 + Spring Security 6 + JWT **인증/인가만** 남긴 학습·검증용 백엔드.
원본 쇼핑몰 프로젝트에서 Security/JWT에 필요한 소스만 가져왔다. 상품·장바구니·주문 기능은 없다.

**이 폴더가 Gradle 프로젝트 루트다.** 아래 `.\gradlew.bat` 명령은 모두 여기서 실행한다.
검증용 프론트엔드는 형제 폴더 → [`../front`](../front) (Vite + React + TypeScript)

| 항목 | 값 |
|---|---|
| Java / Spring Boot | 21 / 3.5.15 |
| JWT | `io.jsonwebtoken:jjwt` **0.11.5** |
| DB | **MariaDB** `localhost:3306/agenticauthdb` |
| 서버 포트 | **8080** |
| accessToken / refreshToken | **10분 / 1440분(24h)** |

---

## 1. 실행 순서

### ① DB 준비 (최초 1회)

MariaDB에서 [`db/schema.sql`](db/schema.sql)을 실행한다.

```bash
mysql -u root -p < db/schema.sql
```

`agenticauthdb` 데이터베이스와 `aauthuser` / `aauthpw` 계정이 만들어진다.
**테이블은 만들 필요 없다** — `ddl-auto=update` 가 첫 기동 때 자동 생성한다.

접속 정보를 바꾸려면 `src/main/resources/application.properties` 도 같이 고친다.
이 파일은 `.gitignore` 대상이라 저장소에 없다 — [`application.properties.example`](src/main/resources/application.properties.example) 을 복사해서 값을 채운다.

### ② 테스트 계정 생성 (최초 1회)

```powershell
.\gradlew.bat test --tests "com.agenticauth.repository.MemberRepositoryTests"
```

| 계정 | 비밀번호 | 권한 | 용도 |
|---|---|---|---|
| `user1@aaa.com` | `1111` | `ROLE_USER` | 일반 로그인 · ADMIN API 호출 시 403 확인 |
| `admin@aaa.com` | `1111` | `ROLE_USER` + `ROLE_ADMIN` | 모든 API 통과 확인 |

> 비밀번호는 `BCryptPasswordEncoder`로 인코딩되어 저장된다.
> **SQL로 평문을 직접 INSERT하면 로그인이 항상 실패한다.**

### ③ 서버 기동

```powershell
.\gradlew.bat bootRun          # http://localhost:8080
```

### ④ 프론트 기동 (별도 터미널)

```powershell
cd ..\front
npm install
npm run dev                    # http://localhost:5173
```

브라우저에서 `http://localhost:5173` 접속 → 로그인 후 버튼을 눌러가며 확인한다.

---

## 2. API

| 엔드포인트 | 인증 | 인가 | 설명 |
|---|---|---|---|
| `POST /api/member/login` | — | — | 로그인. **form-urlencoded** (`username`, `password`) |
| `GET /api/member/refresh` | — | — | 토큰 갱신. 헤더 `Authorization` + 파라미터 `refreshToken` |
| `GET /api/sample/public` | 불필요 | — | 필터 제외 경로 |
| `GET /api/sample/user` | **JWT 필요** | — | 인증 주체(email·권한) 반환 |
| `GET /api/sample/list` | **JWT 필요** | — | 더미 목록 |
| `GET /api/sample/admin` | **JWT 필요** | `hasRole('ADMIN')` | USER 계정이면 403 |

### 에러 코드

프론트는 **HTTP 상태코드가 아니라 이 문자열로 분기한다.**

| 코드 | 발생 지점 |
|---|---|
| `ERROR_LOGIN` | 로그인 실패 |
| `ERROR_ACCESS_TOKEN` | accessToken 검증 실패 |
| `ERROR_ACCESSDENIED` | 권한 부족 (403) |
| `Expired` / `MalFormed` / `Invalid` / `JWTError` / `Error` | JWT 검증 실패 |
| `NULL_REFRASH` | refreshToken 파라미터 누락 (백엔드 오타지만 계약이라 유지) |

---

## 3. 테스트

```powershell
# DB 없이 도는 것 — Security/JWT를 건드리면 최소 이건 통과시킨다
.\gradlew.bat test --tests "com.agenticauth.util.JWTUtilTests" --tests "com.agenticauth.security.filter.JWTCheckFilterTests"

# DB 필요
.\gradlew.bat test --tests "com.agenticauth.repository.MemberRepositoryTests"

# 전체
.\gradlew.bat test
```

| 테스트 | DB | 내용 |
|---|---|---|
| `JWTUtilTests` | ❌ | 토큰 생성·검증, 만료(`Expired`), 위조, 형식 오류(`MalFormed`) |
| `JWTCheckFilterTests` | ❌ | 정상/무토큰/위조 토큰, 제외 경로, OPTIONS preflight |
| `MemberRepositoryTests` | ✅ | 계정 생성, 권한 fetch join, BCrypt 저장 확인 |

리포트: `build/reports/tests/test/index.html`

---

## 4. 문서 & 작업 방식

| 문서 | 내용 |
|---|---|
| [../docs/1-SPEC.md](../docs/1-SPEC.md) | ① **필수 기능에 대한 설명** — `F1~F13`, 에러 코드 계약, 알려진 결함 `K1~K9` |
| [../docs/2-PLAN.md](../docs/2-PLAN.md) | ② **기능 구현에 필요한 기술 목록** — 사용/금지 API, 필터 흐름, 설계 규칙 |
| [../docs/3-TEST.md](../docs/3-TEST.md) | ③ **테스트하는 방법** — 테스트 코드, curl, 회귀 체크리스트 20항목 |

Security/JWT를 수정할 때는 Claude Code에서 4단계 파이프라인을 쓴다.
(에이전트 정의는 저장소 루트에 있으므로 **Claude Code는 `../` 에서 실행한다.**)

```
SPEC ──→ PLAN ──→ TASKS ──→ IMPLEMENT
무엇을?   어떻게?   어떤 순서로?   코드로 구현
```

```
/agentic 토큰 만료 시 401 상태코드가 나가게 해줘
```

각 단계는 전용 서브 에이전트(`agentic-spec` / `agentic-plan` / `agentic-tasks` / `agentic-impl`)가 담당하며,
**앞 3단계는 코드를 수정하지 않는다** — `agentic-plan` 은 쓰기 도구가 없어서 구조적으로,
`agentic-spec`·`agentic-tasks` 는 `docs/` 만 쓰도록 지시받아서. 정의는 [`../.claude/agents/`](../.claude/agents) 참고.

---

## 5. 알려진 결함

`K1~K9` 전부 [`../docs/1-SPEC.md`](../docs/1-SPEC.md)에 기록해 두었다 —
**현재 미해결 항목은 없고**, `K6`은 결함이 아니었던 것으로 **정정**했다.
항목별 조치 내역은 [루트 README](../README.md)의 「백엔드 코드의 알려진 결함」에 있다.

> 표를 여기에 다시 두지 않는 이유 — 같은 표가 두 군데 있으면 한쪽만 갱신돼 어긋난다.
> 실제로 그렇게 어긋난 적이 있어서 이 섹션을 포인터로 바꿨다.
