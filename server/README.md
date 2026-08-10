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

접속 정보를 바꾸려면 [`src/main/resources/application.properties`](src/main/resources/application.properties)도 같이 고친다.

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
| [../docs/1-SPEC.md](../docs/1-SPEC.md) | ① **필수 기능에 대한 설명** — `F1~F8`, 에러 코드 계약, 알려진 결함 `K1~K8` |
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

[`../docs/1-SPEC.md`](../docs/1-SPEC.md)에 K1~K8로 기록해 두었다.

**남아 있는 것**

| # | 내용 |
|---|---|
| K4 | JWT 시크릿 키가 `JWTUtil`에 하드코딩 — 공개된 예시 키로 의도한 것 |
| K6 | jjwt 0.11.5의 deprecated API 사용 |
| K7 | `authorizeHttpRequests` 미설정 — URL 레벨 인가 없음 |
| K8 | `checkExpiredToken()`이 `"Expired"`만 만료로 봐서, 형식이 깨진 토큰을 "아직 유효"로 판정 |

**해결된 것**

| # | 내용 | 조치 |
|---|---|---|
| K1 | `Authorization` 헤더가 null이면 NPE | `Bearer ` 접두어를 검사 전에 확인 · `log.warn`으로 원인 기록 |
| K2 | 인증 실패도 HTTP 200 | `JWTCheckFilter` · `APILoginFailHandler` 모두 **401** |
| K3 | JWT 예외에 `ResponseEntity.ok()` | `status(UNAUTHORIZED)` — **401** |
| K5 | `pw`(BCrypt 해시)가 JWT claims에 포함 | claims에서 제거 · credentials에 `null` (**F1 응답 계약 변경**) |

> 인증 실패는 이제 **401**로 나간다. 다만 프론트는 여전히 **본문의 에러 코드로 분기한다** —
> 401 하나에 `ERROR_LOGIN` · `ERROR_ACCESS_TOKEN` · `Expired` · `INVALID_STRING`이 모두 들어오기 때문이다.
