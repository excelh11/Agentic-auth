---
name: agentic-auth
description: Spring Security 6 + JWT 인증/인가 코드를 SPEC → PLAN → TASKS → IMPLEMENT 4단계로 수정한다. 로그인·토큰 검사 필터·인가·토큰 갱신·CORS(F1~F8)와 AI 에이전트 위임 인증(F9~F13, act 클레임·scope·audience·비대칭 서명·감사 로그)을 다룬다. 인증 관련 기능을 새로 추가하거나 바꿀 때, 토큰/claims/필터체인/에러 코드를 건드릴 때, 또는 "로그인이 안 된다"·"401이 나온다"·"에이전트에게 권한을 위임하고 싶다" 같은 요청에 사용한다.
---

# agentic-auth — Security/JWT 작업 스킬

이 프로젝트의 인증 코드는 **한 곳을 잘못 고치면 조용히 깨진다.**
컴파일러가 못 잡는 결합이 있고, 에러 문자열이 그대로 프론트와의 API 계약이기 때문이다.
그래서 코드를 바로 고치지 않고 네 단계를 거친다.

```
SPEC (무엇을?) ─→ PLAN (어떻게?) ─→ TASKS (어떤 순서로?) ─→ IMPLEMENT (코드로)
```

## 언제 이 스킬을 쓰나

- 인증·인가 기능을 새로 추가하거나 바꿀 때
- `JWTUtil` · `JWTCheckFilter` · `MemberDTO` · `CustomSecurityConfig` · `APIRefreshController` 를 건드릴 때
- claims 구조, 에러 코드, 필터 순서, 토큰 유효시간을 바꿀 때
- AI 에이전트 위임 인증(`act` · `scope` · `aud`)을 다룰 때
- 요청이 모호할 때 ("로그인 좀 고쳐줘") — SPEC 단계가 먼저 무엇을 만들지 확정한다

**단순 오타·상수값 하나·로그 문구**는 이 절차를 생략하고 바로 고쳐도 된다.

## 진행 방법

`/agentic <하고 싶은 작업>` 슬래시 커맨드가 네 단계를 순서대로 진행한다.
단계별 에이전트를 직접 부를 수도 있다.

| 단계 | 에이전트 | 도구 권한 | 기준 문서 |
|---|---|---|---|
| 0 · 규칙 | — (모든 단계) | — | **`docs/0-RULES.md`** |
| 1 · SPEC | `agentic-spec` | `Read` `Write` `Edit` `Glob` `Grep` — **`docs/1-SPEC.md` 만** | `docs/1-SPEC.md` |
| 2 · PLAN | `agentic-plan` | `Read` `Glob` `Grep` `Bash`(조회) — **쓰기 없음** | `docs/2-PLAN.md` |
| 3 · TASKS | `agentic-tasks` | `Read` `Write` `Edit` `Glob` `Grep` — **`docs/` 만** | `docs/1-SPEC.md` |
| 4 · IMPLEMENT | `agentic-impl` | + `Bash` — **전부** | `docs/3-TEST.md` |

**어디까지가 강제인가** — 하네스가 실제로 막는 것은 **`tools:` 에 없는 도구뿐**이다.
`agentic-plan` 은 `Write`·`Edit` 가 없어 **파일을 못 고친다(구조적 강제)**.
`agentic-spec`·`agentic-tasks` 는 자기 산출 문서를 쓰라고 `Write`·`Edit` 를 받았고,
**"코드는 건드리지 않는다"는 프롬프트 지시로만 강제된다** — 지켜야 하는 규율이지 막히는 벽이 아니다.

한 단계가 끝나면 결과를 보여주고 **승인을 받은 뒤** 다음으로 넘어간다. 몰아서 실행하지 않는다.

---

## 대상 시스템

| 항목 | 값 |
|---|---|
| 패키지 | `com.agenticauth` (**변경 금지**) |
| Java / Spring Boot | 21 / 3.5.15 (Spring Security **6.x**) |
| JWT 라이브러리 | `io.jsonwebtoken:jjwt` **0.11.5** |
| 서명 | **RS256 비대칭** — 키쌍은 `AAUTH_JWT_KEY_DIR`(기본 `server/keys`) |
| 필터 계층 직렬화 | Gson 2.10.1 (`@RestController` 밖이라 Jackson 자동 직렬화가 안 걸린다) |
| DB | MariaDB `localhost:3306/agenticauthdb` |
| Gradle 루트 | **`server/`** — `.\gradlew.bat` 은 여기서 실행 |
| 프론트 | `front/` (`:5173`) — 별도 npm 프로젝트 |

### 핵심 소스 위치 (저장소 루트 기준)

| 역할 | 경로 |
|---|---|
| 필터체인 · CORS · URL 인가 | `server/src/main/java/com/agenticauth/config/CustomSecurityConfig.java` |
| JWT 생성·검증 | `server/src/main/java/com/agenticauth/util/JWTUtil.java` |
| 토큰 검사 필터 | `server/src/main/java/com/agenticauth/security/filter/JWTCheckFilter.java` |
| 인증 주체 | `server/src/main/java/com/agenticauth/dto/MemberDTO.java` |
| 토큰 갱신 | `server/src/main/java/com/agenticauth/controller/APIRefreshController.java` |
| 로그인/접근거부 핸들러 | `server/src/main/java/com/agenticauth/security/handler/` |
| 예외 → 응답 | `server/src/main/java/com/agenticauth/controller/advice/CustomControllerAdvice.java` |
| **위임 토큰 검증** | `server/src/main/java/com/agenticauth/security/DelegationValidator.java` |
| **scope 강제** | `server/src/main/java/com/agenticauth/security/ScopeAuthorizer.java` |
| **역할→scope 상한** | `server/src/main/java/com/agenticauth/security/ScopeCatalog.java` |
| **에이전트 등록·위임·회수** | `server/src/main/java/com/agenticauth/controller/APIAgentController.java` |

---

## 절대 규칙

> **0. 먼저 [`docs/0-RULES.md`](docs/0-RULES.md) 를 읽는다.** 작업 규칙이 근거와 함께 정리돼 있다.
> 특히 **여러 줄 `sed` 치환 금지**(759줄이 오염된 적 있고 git이 없어 되돌릴 수 없었다)와
> **PowerShell `Get-Content` 의 ANSI 기본값**(한글이 깨져 검증이 대량 오탐), 그리고
> **"통과"와 "무변경"은 다르다**는 항목은 이 스킬을 쓰는 동안 계속 유효하다.

1. **패키지명(`com.agenticauth`)을 바꾸지 않는다.**

2. **에러 문자열과 응답 JSON 필드명은 프론트와의 계약이다.** 변경은 사용자 승인 후에만, **추가는 자유.**
   `ERROR_LOGIN` · `ERROR_ACCESS_TOKEN` · `ERROR_ACCESSDENIED` · `NULL_REFRASH` · `INVALID_STRING` ·
   `ERROR_AUDIENCE` · `ERROR_AGENT_INACTIVE` · `ERROR_SCOPE` · `ERROR_SCOPE_EXCEEDS_ROLE`
   (`NULL_REFRASH` 는 오타지만 프론트가 이미 쓰고 있어 고치지 않는다)

3. **claims를 쓰는 쪽과 읽는 쪽은 항상 짝으로 고친다.** 컴파일러가 불일치를 못 잡는다.
   - 쓰는 쪽 ① `MemberDTO.getClaims()` — F1 사용자 토큰, 4-key
   - 쓰는 쪽 ② `APIAgentController.delegate()` — F9 위임 토큰, `sub`·`act`·`scope`·`aud` 추가
   - 읽는 쪽 `JWTCheckFilter` — **`act` 유무로 분기**한다

4. **CORS는 `CustomSecurityConfig` 한 곳에서만.** MVC(`WebMvcConfigurer.addCorsMappings`)에 걸면
   시큐리티 필터체인이 앞서기 때문에 preflight가 먼저 차단된다. 그래서 `CustomServletConfig` 자체를 두지 않았다.

5. **Spring Security 5 문법 금지** — `WebSecurityConfigurerAdapter` · `.and()` · `antMatchers()` ·
   `authorizeRequests()` · `@EnableGlobalMethodSecurity`

6. **jjwt 0.12.x API 금지** — 현재 **0.11.5**다. `setClaims()` · `parserBuilder()` 를 쓴다.
   (0.11.5에서 이건 정식 API다. deprecated가 아니다)

7. **신규 gradle 의존성 / jjwt 버전 변경 / DB 스키마 변경은 사용자 승인 필수.**

8. **알려진 결함(`K1~K9`)은 제안만** 하고 승인 없이 고치지 않는다.
   (현재 미해결 없음 — 새로 발견하면 `K10`부터 기록한다)

---

## 밟기 쉬운 지뢰

이 프로젝트에서 실제로 사고가 났던 지점들이다. 같은 자리를 다시 밟지 않는다.

**`JWTCheckFilter` 를 Spring Bean으로 만들지 마라.**
`Filter` 타입을 Bean으로 노출하면 Spring Boot가 서블릿 컨테이너에 **이중 등록**해 요청당 두 번 실행된다.
감사 로그도 두 번 남는다. 의존성(`DelegationValidator`)만 Bean으로 받아 `new JWTCheckFilter(...)` 로 조립한다.

**`filterChain.doFilter()` 를 `try` 안에 넣지 마라 (K9).**
컨트롤러·서비스·DB 예외까지 `catch(Exception e)` 에 걸려 `ERROR_ACCESS_TOKEN` 401로 둔갑한다.
실제로 FK 제약 위반이 "토큰이 잘못됐다"로 보고돼 원인 추적이 한참 지연됐다.
**인증은 `try` 안에서 끝내고, 체인은 밖에서 태운다.**

**`/api/member/**` 는 필터 제외 경로다.**
`JWTCheckFilter` 를 거치지 않으므로, 여기에 붙는 컨트롤러는 필요한 검증을 **스스로** 해야 한다.
`APIRefreshController` 가 `DelegationValidator` 를 직접 부르는 이유다 —
안 그러면 회수한 에이전트가 refresh로 부활한다.

**`shouldNotFilter()` 의 제외 경로와 `authorizeHttpRequests` 의 `permitAll` 은 짝이다.**
한쪽만 고치면 필터는 통과시키는데 인가가 막거나 그 반대가 된다.

**`authorizeHttpRequests` DSL의 `hasRole()` 에 `ROLE_` 접두어를 넣으면 예외를 던진다.**
`@PreAuthorize` 의 `hasRole()` 은 반대로 둘 다 허용한다. 같은 이름의 다른 규칙이다.

**scope는 `@PreAuthorize` 를 대체하지 않는다.**
`ScopeAuthorizer.has()` 는 `act == null`(사용자 본인 호출)이면 **무조건 통과**시킨다 — 의도된 fail-open이다.
그래서 `SampleController` 에 `@scopeAuth.has(...)` 를 붙여도 일반 사용자 동작이 바뀌지 않는다.
이 성질이 깨지면 기존 F1~F8이 전부 회귀한다.

**브라우저는 토큰을 다시 서명할 수 없다.**
프론트에서 payload를 조작하면(강제 만료·aud 변조) 서명이 깨져서 `ERROR_ACCESS_TOKEN` 이 먼저 나간다.
`ERROR_AUDIENCE` 같은 claim 단계 에러는 **서버 테스트에서만** 재현할 수 있다.

---

## 검증

Gradle 명령은 **`server/` 안에서** 실행한다.

```powershell
cd server
.\gradlew.bat compileJava    # 작업 단위마다
.\gradlew.bat test           # 전체 52개
```

**DB 없이 도는 것** — Security/JWT를 건드리면 최소 이건 통과시킨다.

```powershell
.\gradlew.bat test --tests "com.agenticauth.util.JWTUtilTests" `
                   --tests "com.agenticauth.security.filter.JWTCheckFilterTests" `
                   --tests "com.agenticauth.security.DelegationValidatorTests" `
                   --tests "com.agenticauth.security.ScopeAuthorizerTests"
```

**DB가 필요한 것** — `SecurityIntegrationTests` · `APIAgentControllerTests` ·
`APIRefreshControllerTests` · `APIRefreshControllerDelegationTests` · `MemberRepositoryTests`

> 테스트 클래스 실행 순서는 보장되지 않는다. `agent` 테이블이 `member.email` 을 FK로 참조하므로,
> **테스트 계정이 없으면 FK 위반**이 난다. `MemberRepositoryTests` 를 먼저 한 번 돌려 계정을 만든다.

`@SpringBootTest` 가 DB 때문에 실패하면 **그 사실을 그대로 보고한다.** "통과했다"고 말하지 않는다.

변경 후에는 `docs/3-TEST.md` §6 **회귀 체크리스트 37항목** 중 영향받는 것을 확인하고 번호로 보고한다.
특히 **21번**(일반 사용자 토큰으로 `/api/sample/user`·`/list` 가 여전히 200)이 최우선이다 —
scope 도입이 기존 사용자 경로를 깨뜨리지 않았는지를 보는 관문이다.

---

## 보고 형식

IMPLEMENT 완료 후 반드시 아래를 포함한다.

- 변경/신규 파일 목록과 각각의 변경 내용
- `gradlew compileJava` / `gradlew test` 실행 결과 — **실패는 출력 원문 그대로**
- 확인한 회귀 체크리스트 항목 번호
- 프론트(`front/`)에 영향 가는 계약 변경 — 없으면 "없음"
- 명세가 바뀌었다면 `docs/1-SPEC.md` 갱신 여부

`front/` 는 이 스킬의 담당이 아니다. 백엔드 계약이 바뀌면 **프론트도 따라 고쳐야 한다는 사실만 보고**하고,
프론트 코드는 사용자 지시가 있을 때만 건드린다.
