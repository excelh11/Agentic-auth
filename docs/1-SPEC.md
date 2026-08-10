# 1. SPEC — 무엇을? (필수 기능에 대한 설명)

> 이 문서는 프로젝트의 **인증/인가 체계가 반드시 만족해야 하는 기능 명세**다.
> 모든 코드 변경은 이 명세를 깨뜨리지 않아야 한다. 명세를 바꿔야 한다면 **코드보다 이 문서를 먼저 고친다.**

**이 프로젝트의 목표는 F9~F13 — AI 에이전트가 사용자를 대신해 API를 호출할 때의 인증이다.**
F1~F8은 그 토대인 사용자 인증이며, 에이전트 토큰은 사용자 토큰에서 파생되므로 반드시 먼저 동작해야 한다.

| 구간 | 내용 | 상태 |
| ---- | ---- | ---- |
| **F1~F8** | 사용자 인증 — 로그인, JWT 검사, 인가, 갱신, CORS | 구현 완료 (토대) |
| **F9~F13** | **에이전트 위임 인증** — `act` 클레임, scope, audience, 비대칭 서명, 감사 로그 | **명세 확정 · 미구현** |

---

## 0. 대상 시스템

- 프론트엔드(React 등)와 분리된 **REST API 서버**. 화면 이동(redirect)이 없고 모든 응답은 JSON이다.
- 서버 포트: **8080** (`server.port=8080`)
- 인증 상태를 서버가 들고 있지 않는 **완전 무상태(stateless)** 구조.
- 검증용 프론트엔드는 **별도 프로젝트**다 → `../front` (Vite + React + TypeScript).

```
front (Vite dev server :5173)          server (Spring Boot :8080)
        │                                            │
        │  fetch + Authorization: Bearer …           │
        └────────── cross-origin ────────────────────┘
                    ↑ 여기서 F6(CORS)이 실제로 작동한다
```

> 프론트가 **다른 오리진**이므로 preflight(OPTIONS)와 CORS 헤더가 실제로 오간다.
> Vite proxy를 쓰면 same-origin이 되어 CORS를 우회하게 되므로, **F6를 검증하려면 proxy 없이 절대 URL로 호출**해야 한다.

### 관련 소스 지도

| 역할                     | 파일                                                                                                 |
| ------------------------ | ---------------------------------------------------------------------------------------------------- |
| 시큐리티 필터체인 / CORS | `src/main/java/com/agenticauth/config/CustomSecurityConfig.java`                                         |
| JWT 생성·검증            | `src/main/java/com/agenticauth/util/JWTUtil.java`                                                        |
| JWT 예외                 | `src/main/java/com/agenticauth/util/CustomJWTException.java`                                             |
| 토큰 검사 필터           | `src/main/java/com/agenticauth/security/filter/JWTCheckFilter.java`                                      |
| 로그인 성공/실패         | `src/main/java/com/agenticauth/security/handler/APILoginSuccessHandler.java`, `APILoginFailHandler.java` |
| 접근 거부                | `src/main/java/com/agenticauth/security/handler/CustomAccessDeniedHandler.java`                          |
| 사용자 조회              | `src/main/java/com/agenticauth/security/CustomUserDetailsService.java`                                   |
| 인증 주체(Principal)     | `src/main/java/com/agenticauth/dto/MemberDTO.java`                                                       |
| 토큰 갱신                | `src/main/java/com/agenticauth/controller/APIRefreshController.java`                                     |
| 예외 → HTTP 응답         | `src/main/java/com/agenticauth/controller/advice/CustomControllerAdvice.java`                            |
| MVC 포맷터 (CORS 아님)   | `src/main/java/com/agenticauth/config/CustomServletConfig.java`                                          |
| 검증용 프론트엔드        | `../front/` (별도 프로젝트 · Vite + React + TS)                                          |

---

## F1. 로그인 & 토큰 발급

| 항목       | 내용                                                                                                         |
| ---------- | ------------------------------------------------------------------------------------------------------------ |
| 엔드포인트 | `POST /api/member/login`                                                                                     |
| 요청       | `application/x-www-form-urlencoded`, 파라미터 `username`(이메일), `password`                                 |
| 처리       | Spring Security `formLogin` → `CustomUserDetailsService.loadUserByUsername()` → `BCryptPasswordEncoder` 비교 |
| 성공       | `APILoginSuccessHandler`가 **JSON 본문** 반환                                                                |
| 실패       | `APILoginFailHandler` → **401** + `{"error":"ERROR_LOGIN"}`                                                  |

**성공 응답 필드 (프론트와의 계약 — 임의 변경 금지)**

```json
{
  "email": "user1@aaa.com",
  "nickname": "USER1",
  "social": false,
  "roleNames": ["USER"],
  "accessToken": "eyJ0eXAiOiJKV1Qi...",
  "refreshToken": "eyJ0eXAiOiJKV1Qi..."
}
```

> **`pw`는 응답에도 claims에도 넣지 않는다.** JWT payload는 서명될 뿐 암호화되지 않아
> Base64 디코딩만으로 읽힌다. 비밀번호 해시를 실으면 토큰을 가진 누구나 오프라인 대입을 시도할 수 있다.
> claims에는 **인가에 필요한 최소한**(식별자 · 권한 · 유효시간)만 담는다.

**토큰 유효시간**

| 토큰         | 유효시간            | 발급 위치                              |
| ------------ | ------------------- | -------------------------------------- |
| accessToken  | **10분**            | `JWTUtil.generateToken(claims, 10)`    |
| refreshToken | **1440분 (24시간)** | `JWTUtil.generateToken(claims, 60*24)` |

- **서명 방식**: F12(비대칭 서명, 전체 토큰 적용) 반영 이후 accessToken/refreshToken은 발급 키와 검증 키가 분리된 방식으로 서명한다. 응답 JSON 필드 구성은 바뀌지 않는다.
- 리다이렉트를 사용하지 않는다. `defaultSuccessUrl`, `failureUrl` 금지.
- `loginPage("/api/member/login")`은 로그인 **처리 URL** 역할을 겸한다.

---

## F2. 세션리스(Stateless) 인증

- `SessionCreationPolicy.STATELESS` — 서버는 `HttpSession`에 인증 정보를 저장하지 않는다.
- `csrf().disable()` — 세션/쿠키 기반이 아니므로 CSRF 토큰이 불필요하다.
- 매 요청의 인증 주체는 **오직 `Authorization: Bearer <accessToken>` 헤더**로만 결정된다.
- 서버 재시작·스케일아웃 후에도 기존 토큰이 그대로 동작해야 한다.

> **예외**: F12(비대칭 서명, 전체 토큰 적용) 도입 시점의 서명 키 교체는 이 원칙의 예외다.
> 그 순간 이전에 발급된 기존 토큰은 전량 무효화되며, 이는 사용자가 승인한 **1회성** 파괴적 변경이다.
> 그 이후에는 다시 "서버 재시작·스케일아웃에 기존 토큰이 그대로 동작한다"는 원칙이 적용된다.

---

## F3. 토큰 검사 필터 (`JWTCheckFilter`)

- `OncePerRequestFilter`를 상속하고, `UsernamePasswordAuthenticationFilter` **앞에** 등록된다.

### 검사 제외 대상 (`shouldNotFilter()` → `true`)

| 조건                                | 이유                                                          |
| ----------------------------------- | ------------------------------------------------------------- |
| HTTP 메서드가 `OPTIONS`             | CORS preflight는 인증 헤더 없이 온다                          |
| URI가 `/api/member/` 로 시작        | 로그인·회원가입·refresh는 토큰이 없거나 만료 상태로 호출된다  |
| URI가 `/api/sample/public` 로 시작 | 상품 이미지 조회는 `<img src>`로 호출되어 헤더를 실을 수 없다 |

### 검사 통과 시

1. `Authorization` 헤더에서 `Bearer ` 접두어를 떼고 accessToken 추출
2. `JWTUtil.validateToken()`으로 claims 획득
3. claims(`email`, `nickname`, `social`, `roleNames`)로 `MemberDTO` 복원
   ※ `social`은 `Boolean`으로 꺼내 `booleanValue()`를 호출하므로 **claims에서 빠지면 NPE**가 난다. 4개 모두 유지할 것.
   ※ 비밀번호는 claims에 없다. `MemberDTO`의 `pw` 자리에는 빈 문자열을 넣고,
   `UsernamePasswordAuthenticationToken`의 credentials에는 **`null`**을 넣는다 —
   인증이 이미 끝난 뒤이므로 자격증명을 들고 있을 이유가 없다.
4. `UsernamePasswordAuthenticationToken`을 만들어 `SecurityContextHolder`에 저장
5. `filterChain.doFilter()` 로 다음 필터 진행

### 검사 실패 시

- **HTTP 401** + `{"error":"ERROR_ACCESS_TOKEN"}` JSON 반환
- **체인을 진행시키지 않는다** (컨트롤러가 실행되면 안 된다)
- 헤더가 `null`이거나 `Bearer ` 접두어가 없으면 `substring(7)` **이전에** 걸러내고 `log.warn`으로 원인을 남긴다

---

## F4. 인가(권한 체크)

- `@EnableMethodSecurity` 기반의 **메서드 단위 인가**를 사용한다.
- 컨트롤러 메서드에 `@PreAuthorize("hasRole('ADMIN')")`, `@PreAuthorize("hasAnyRole('USER','ADMIN')")` 등을 붙인다.
- **`ROLE_` 접두어 규칙**
  - JWT claims의 `roleNames`에는 접두어를 **넣지 않는다** → `["USER"]`
  - `MemberDTO` 생성자가 `"ROLE_" + str`로 `SimpleGrantedAuthority`를 만든다 → `ROLE_USER`
  - `@PreAuthorize`의 SpEL `hasRole()`은 인자가 `ROLE_`로 **시작하지 않을 때만** 접두어를 붙인다.
    따라서 `hasRole('ADMIN')` 과 `hasRole('ROLE_ADMIN')` 은 **둘 다 `ROLE_ADMIN`으로 동작한다.**
    현재 코드는 두 형태가 섞여 있다 — `ProductController:75`는 `hasRole('ROLE_ADMIN')`, `CartController:37`은 `hasAnyRole('ROLE_USER')`.
  - ⚠️ 단, `authorizeHttpRequests` DSL의 `hasRole()`은 `ROLE_` 접두어를 넣으면 **예외를 던진다.** (K7로 도입된 URL 레벨 인가는 역할을 다루지 않고 인증 여부만 보므로 현재는 해당 없음)
- **URL 레벨 인가(K7)** — `authorizeHttpRequests`가 `/api/member/**`·`/api/sample/public`·`/error`를 `permitAll`, 나머지를 `authenticated`로 막는다.
  이건 `@PreAuthorize`를 **대체하지 않는다.** 애노테이션을 빠뜨린 엔드포인트가 무방비가 되지 않도록 하는 **바닥 방어선**이다.
  > `JWTCheckFilter.shouldNotFilter()`의 제외 경로와 **반드시 짝을 맞춘다.** 한쪽만 고치면 필터는 통과시키는데 인가가 막거나 그 반대가 된다.
- 권한 부족 시 `CustomAccessDeniedHandler` → HTTP **403** + `{"error":"ERROR_ACCESSDENIED"}`

### 현재 인가가 걸린 엔드포인트 (테스트 기준점)

| 엔드포인트 | 인가 | USER 계정으로 호출하면 |
|---|---|---|
| `GET /api/sample/public` | 없음 (필터 제외 경로) | 200 · 토큰 없이도 통과 |
| `GET /api/sample/user` | 없음 (JWT만) | 200 · 인증 주체 반환 |
| `GET /api/sample/list` | 없음 (JWT만) | 200 |
| `GET /api/sample/admin` | `hasRole('ADMIN')` | **403** `ERROR_ACCESSDENIED` |

> `SampleController`는 비즈니스 로직이 없는 **검증 전용** 컨트롤러다.
> 실제 비즈니스 API(`/api/todo/**`, `/api/products/**` 등) 자리를 대신한다.

---

## F5. 토큰 갱신 (`/api/member/refresh`)

| 항목          | 내용                                          |
| ------------- | --------------------------------------------- | ------------------------- |
| 엔드포인트    | `GET                                          | POST /api/member/refresh` |
| 요청 헤더     | `Authorization: Bearer <accessToken>`         |
| 요청 파라미터 | `refreshToken` (쿼리 스트링)                  |
| 응답          | `{"accessToken":"...", "refreshToken":"..."}` |

### 판정 로직

1. `refreshToken`이 없으면 → `CustomJWTException("NULL_REFRASH")`
2. `Authorization` 헤더가 없거나 길이 7 미만이면 → `CustomJWTException("INVALID_STRING")`
3. accessToken이 **아직 유효**하면 → 기존 토큰 쌍을 그대로 반환 (불필요한 재발급 방지)
4. accessToken을 **쓸 수 없으면**(만료·형식 오류·위조 — K8) → refreshToken을 검증하고 **새 accessToken(10분)** 발급
   > 예전에는 `"Expired"`만 4번으로 갔고, 형식이 깨진 토큰은 3번으로 빠져 **그 망가진 토큰이 그대로 되돌아왔다.**
   > 재발급은 refreshToken을 검증한 뒤에만 이뤄지므로, 조건을 넓혀도 검증이 느슨해지지 않는다.
5. refreshToken 잔여시간이 **60분 미만**이면 → refreshToken도 새로 발급 **(rotation)**, 아니면 기존 것 유지

> F12(비대칭 서명) 적용 이후에도 위 판정 로직(1~5단계)은 동일하게 유지된다.
> 바뀌는 것은 서명/검증에 쓰는 키가 발급용과 검증용으로 분리된다는 점뿐이다.

---

## F6. CORS

- CORS 설정은 **`CustomSecurityConfig.corsConfigurationSource()` 한 곳에서만** 한다.
- `CustomServletConfig`의 `addCorsMappings()`는 **의도적으로 주석 처리**되어 있다.
  시큐리티 필터체인이 MVC보다 앞서므로, MVC에 CORS를 걸면 preflight가 시큐리티 단계에서 먼저 차단된다.
  **되살리지 말 것.**

| 항목                    | 값                                           |
| ----------------------- | -------------------------------------------- |
| Allowed Origin Patterns | `*`                                          |
| Allowed Methods         | `HEAD, GET, POST, PUT, DELETE`               |
| Allowed Headers         | `Authorization, Cache-Control, Content-Type` |
| Allow Credentials       | `true`                                       |
| 적용 경로               | `/**`                                        |

- 프론트에서 새 헤더나 메서드(`PATCH` 등)가 필요해지면 **이 Bean만** 수정한다.

> **Vite proxy를 켜면 브라우저 입장에서 same-origin이 되어 CORS가 검증되지 않는다.**
> `front`는 **절대 URL(`http://localhost:8080/api/...`)로 직접 호출**하도록 만든다. 그래야 F6가 실제로 동작하는지 확인된다.

---

## F7. 예외 → 응답 규약

`CustomJWTException`의 메시지 문자열은 **프론트엔드가 분기에 사용하는 계약**이다.

| 코드                 | 발생 지점                   | 의미                       | HTTP  |
| -------------------- | --------------------------- | -------------------------- | ----- |
| `MalFormed`          | `JWTUtil.validateToken`     | 토큰 형식 오류             | 401   |
| `Expired`            | `JWTUtil.validateToken`     | 유효시간 만료              | 401   |
| `Invalid`            | `JWTUtil.validateToken`     | claim 검증 실패            | 401   |
| `JWTError`           | `JWTUtil.validateToken`     | 그 외 JWT 라이브러리 오류  | 401   |
| `Error`              | `JWTUtil.validateToken`     | 알 수 없는 오류            | 401   |
| `NULL_REFRASH`       | `APIRefreshController`      | refreshToken 파라미터 누락 | — ※   |
| `INVALID_STRING`     | `APIRefreshController`      | Authorization 헤더 이상    | 401   |
| `ERROR_LOGIN`        | `APILoginFailHandler`       | 로그인 실패                | 401   |
| `ERROR_ACCESS_TOKEN` | `JWTCheckFilter`            | accessToken 검증 실패      | 401   |
| `ERROR_ACCESSDENIED` | `CustomAccessDeniedHandler` | 권한 부족                  | 403   |
| `ERROR_AUDIENCE`     | `DelegationValidator` (필터 계층) | 위임 토큰의 `aud`가 이 서버가 아님 (F11) | 401 |
| `ERROR_AGENT_INACTIVE` | `DelegationValidator` (필터·refresh) | 에이전트 미등록·비활성·소유자 불일치 (F9-3) | 401 |
| `ERROR_SCOPE`        | `CustomAccessDeniedHandler` | 위임 토큰이 scope 밖 API 호출 (F10-1) · 재위임 시도 | 403 ※ |
| `ERROR_SCOPE_EXCEEDS_ROLE` | `APIAgentController`  | 위임자 권한을 초과하는 scope 요청 (F10-2) | 401 ※ |
| `ERROR_AGENT_NAME_REQUIRED` | `APIAgentController` | 에이전트 등록 시 이름 누락 (F9-5) | 401 ※ |

> **기존 문자열을 변경·삭제하지 않는다. 추가만 허용한다.**
> (`NULL_REFRASH`는 오타지만 프론트가 이미 이 값을 쓰고 있으므로 고치지 않는다.)
>
> ※ **`NULL_REFRASH`는 실제로 나오지 않는다.** `@RequestParam("refreshToken")`이 필수(기본값)라
> 파라미터가 빠지면 메서드 진입 전에 `MissingServletRequestParameterException` → **400**이 나간다.
> 컨트롤러 안의 `null` 검사는 도달하지 않는 코드다. `@RequestHeader("Authorization")`도 마찬가지로,
> 헤더가 아예 없으면 `INVALID_STRING`이 아니라 400이다. *(실제 서버 응답으로 확인)*

> ※ **F9~F13 신규 코드 4개는 "추가"다.** 위 표의 기존 문자열은 하나도 바뀌지 않았다.
> `ERROR_SCOPE`가 403인 것은 인증은 됐으나 인가가 부족한 상황이라 `ERROR_ACCESSDENIED`와 같은 계층이기 때문이고,
> `ERROR_SCOPE_EXCEEDS_ROLE`이 401인 것은 `CustomJWTException` → `CustomControllerAdvice` 경로를 그대로 타기 때문이다
> (`NULL_REFRASH`·`INVALID_STRING`과 같은 하우스 스타일).
>
> `ERROR_AUDIENCE`·`ERROR_AGENT_INACTIVE`는 **필터 계층**에서 나가므로 `@RestControllerAdvice`가 잡지 못한다.
> `JWTCheckFilter`가 직접 401로 쓴다. 이 둘만은 `ERROR_ACCESS_TOKEN`으로 뭉뚱그리지 않는다 — 프론트가 사유를 구분해야 하기 때문이다.

---

## F8. 검증용 프론트엔드 (`front`)

**로그인과 JWT 동작만** 확인하는 최소 프론트엔드. 쇼핑몰 기능(상품·장바구니)은 다루지 않는다.

| 항목 | 내용 |
|---|---|
| 위치 | 저장소 루트의 `front/` (`server/` 와 **별도 프로젝트**) |
| 스택 | Vite + React 18 + TypeScript |
| 개발 서버 | `http://localhost:5173` |
| API 서버 | `http://localhost:8080` — **절대 URL로 직접 호출** (proxy 미사용) |
| 토큰 보관 | `localStorage` (`aauth_access`, `aauth_refresh`) |

### 필수 동작

- **F8-1** 로그인 폼 → `POST /api/member/login` (form-urlencoded) 후 두 토큰을 저장한다.
- **F8-2** accessToken의 payload(claims)와 **만료까지 남은 시간**을 화면에 표시한다.
- **F8-3** 아래 호출을 버튼으로 제공하고, 각 버튼에 **기대 결과를 함께 표시**한다.
  | 버튼 | 검증 대상 |
  |---|---|
  | 토큰 O로 보호 API 호출 | F3 정상 통과 |
  | 토큰 X로 보호 API 호출 | F3 `ERROR_ACCESS_TOKEN` |
  | 위조 토큰으로 호출 | F3 서명 검증 |
  | ADMIN 전용 API 호출 | F4 `403 ERROR_ACCESSDENIED` |
  | refresh 호출 / 파라미터 없이 호출 | F5, F7 `NULL_REFRASH` |
- **F8-4** accessToken의 `exp`를 과거로 조작하는 **강제 만료** 버튼을 제공한다.
  서명이 깨지므로 서버는 이를 거부해야 하며, refresh 호출로 복구되어야 한다.
- **F8-5** 모든 요청의 **HTTP 상태코드와 응답 본문 원문**을 로그 영역에 누적 표시한다.
  (K2/K3 때문에 에러도 200으로 오는 현상을 눈으로 확인할 수 있어야 한다.)
- **F8-6** 위임 인증(F9~F13) 시나리오도 F8-3과 같은 형태로 버튼과 기대 결과를 제공한다.
  | # | 버튼 | 기대 결과 | 검증 대상 |
  |---|---|---|---|
  | 1 | 에이전트 등록 | `agentId` 반환 | F9 |
  | 2 | 위임 토큰 발급 (`sample:read` 만) | `accessToken` + `sub`·`act`·`scope`·`aud` | F9 |
  | 3 | 위임 토큰 payload 보기 | `sub`/`act`/`scope`/`aud` 가 화면에 보임 | F9-1 (F8-2 확장) |
  | 4 | scope 안의 API 호출 (`/api/sample/user`) | **200** | F10-1 |
  | 5 | scope 밖의 API 호출 (`/api/sample/list`) | **403** `ERROR_SCOPE` | F10-1 |
  | 6 | audience 조작 호출 | **401** `ERROR_ACCESS_TOKEN` ※ | F11·F12 |
  | 7 | 권한을 넘어서는 위임 시도 | `ERROR_SCOPE_EXCEEDS_ROLE` | F10-2 |
  | 8 | 위임 토큰으로 재위임 시도 | 거부 `ERROR_SCOPE` | F9 |
  | 9 | 에이전트 회수(비활성화) | 200 · 사용자 본인 토큰은 그대로 살아 있음 | F9-3, F9-4 |
  | 10 | 회수 후 위임 토큰으로 호출 | **401** `ERROR_AGENT_INACTIVE` (만료 전이라도) | F9-3 |

  > 화면 섹션 순서는 **F1 → F3·F4 → F5 → F9~F13** 이다.
  > **F2(세션리스)는 버튼이 없다** — "세션을 만들지 않는다"는 성질이라 눌러 확인할 대상이 없다.
  > 대신 화면 전체가 F2의 증거다(쿠키·세션 없이 `localStorage` 토큰만 쓴다).

  > ※ **6번은 `ERROR_AUDIENCE` 가 나오지 않는다 — 브라우저에서는 낼 수 없다.**
  > 브라우저에 서명용 개인키가 없어 `aud` 를 바꾸면 서명이 깨지고, 서버는 서명 검증(`JWTUtil`)을
  > audience 검증(`DelegationValidator`)보다 **먼저** 하므로 `ERROR_ACCESS_TOKEN` 이 먼저 나간다.
  > 이건 결함이 아니라 **F12(비대칭 서명)가 동작한다는 증거**다 — 검증만 할 수 있는 쪽은 위조할 수 없다.
  > `ERROR_AUDIENCE` 자체는 `SecurityIntegrationTests`(서버가 서명하므로 임의 `aud` 를 실을 수 있다)에서 확인한다.

### 제약

- **토큰을 쿠키·세션으로 주고받지 않는다.** F2(무상태)를 깨면 안 된다.
- **Vite proxy를 쓰지 않는다.** proxy를 켜면 same-origin이 되어 F6(CORS)이 검증되지 않는다.
- 백엔드의 **에러 코드 문자열(F7)로 분기**한다. HTTP 상태코드로 분기하면 K2/K3 때문에 동작하지 않는다.
- 이 프로젝트는 **백엔드를 바꾸지 않는다.** 프론트에서 불편한 점이 나오면 SPEC 변경으로 올린다.

---

## F9. `act` 클레임 — 위임자/행위자 구분 및 개별 회수

| 항목 | 내용 |
|---|---|
| 목적 | 위임 호출의 토큰에 위임한 사용자와 실제 호출 주체(에이전트)를 함께 실어, 서버와 로그가 "누가 시켰고 누가 실행했는지"를 구분할 수 있게 한다 |
| 발급 경로 | 기존 `/api/member/login`과는 **별도의 엔드포인트**에서 발급한다 — F1 응답 계약을 건드리지 않기 위해서다. 정확한 경로명·요청 형태는 PLAN에서 정한다 |
| 위임 토큰 vs 사용자 토큰 | 사용자 본인 로그인 토큰(F1)에는 행위자 정보가 없다. 위임 토큰에만 존재하며, 서버는 이 유무로 두 종류를 구분한다 |
| 행위자 자격 | 사전에 등록된 에이전트만 위임 토큰의 행위자가 될 수 있다 (아래 "에이전트 등록" 참고) |

**요구사항**

- **F9-1** (필수) 위임 토큰에는 위임한 사용자와 실제 행위자(에이전트)를 구분하는 정보가 함께 담긴다.
- **F9-2** (필수) 사용자 본인 로그인 토큰(F1)에는 행위자 정보가 없다.
- **F9-3** (필수) **개별 회수**: 에이전트가 비활성화되면, 그 에이전트가 행위자로 찍힌 위임 토큰은 (만료 전이라도) 더 이상 요청을 통과시키지 않는다. 즉 위임 토큰 검증은 서명·만료 확인만으로 끝나지 않고, 행위자(에이전트)가 현재도 유효한 상태인지 조회하는 절차를 반드시 거친다.
- **F9-4** (필수) 사용자 본인을 로그아웃/토큰 폐기시키지 않고도, 에이전트만 개별적으로 차단할 수 있다.

**에이전트 등록 (신규 저장소 — 사용자 승인 완료, DB 스키마 변경 포함)**

위임 토큰의 행위자가 되려면 사전에 등록된 에이전트여야 한다. 아래는 "무엇을 저장해야 하는가" 수준이며, 컬럼명·타입 등 구체적 스키마는 PLAN에서 정한다.

| 저장해야 하는 것 | 이유 |
|---|---|
| 에이전트를 식별하는 고유값 | `act` 클레임에 실어 어떤 에이전트가 행위자인지 판별 |
| **사람이 읽는 이름** | **F13의 전제다.** 식별자만 남기면 감사 로그의 행위자가 `agent-a46e2155…` 라서 "무엇이 했는지"를 알 수 없다. 위임 승인 화면도 이름 없이는 만들 수 없다 |
| 설명 | 이 에이전트가 무엇을 하는지. 사용자가 위임을 승인·회수할 때 판단 근거가 된다 |
| 이 에이전트를 등록한 사용자(위임자)와의 연결 | "누가 이 에이전트에게 위임했는지" 추적 — F13 감사 로그의 위임자 정보와 반드시 일치해야 함 |
| 활성/비활성 상태 | F9-3 개별 회수의 근거 — 비활성이면 신규 토큰 발급도, 기존 위임 토큰 통과도 거부 |
| 등록 시각 | 감사 목적 |

- **F9-5** (필수) 에이전트 등록 시 **이름은 필수**다. 없으면 거부한다.
  이름 없는 에이전트는 F13을 만족시킬 수 없기 때문이다.

> **왜 이름이 "최소 이력"에 포함되는가** — 초기 명세는 식별자·소유자·활성상태·등록시각만 요구했다.
> 그대로 구현하고 보니 감사 로그의 행위자가 UUID뿐이라 **F13의 목적("행위자 구분 불가"의 해소)이 절반만 달성**됐다.
> "누가 시켰는지"는 알 수 있는데 "무엇이 실행했는지"는 여전히 알 수 없었다. 그래서 명세를 넓혔다.

---

## F10. scope — 위임 동작 범위 제한

| 항목 | 내용 |
|---|---|
| 목적 | "일정만 봐줘" 같은 좁은 위임이 사용자의 전체 권한으로 확장되지 않도록, 위임 토큰이 수행할 수 있는 동작을 명시적으로 제한한다 |
| 상한 | 위임 가능한 범위는 위임한 사용자가 F4 기준으로 실제 보유한 권한(`roleNames`)을 넘어설 수 없다 |

**요구사항**

- **F10-1** (필수) 위임 토큰은 발급 시점에 지정된 범위 밖의 API를 호출할 수 없다.
- **F10-2** (필수) 위임 토큰에 부여 가능한 범위는 위임한 사용자가 가진 권한의 부분집합이어야 하며, 사용자가 갖지 않은 권한은 위임할 수 없다.
- **F10-3** (선택) 위임 시 사용자가 부여할 범위 후보를 확인/선택하는 절차.

> scope 이름 규칙, 그리고 scope가 기존 `roleNames`/`@PreAuthorize`(F4)와 어떻게 상호작용하는지(대체인지 추가 제약인지)는 **PLAN에서 정한다.**

---

## F11. audience(`aud`) — 대상 서버 한정

| 항목 | 내용 |
|---|---|
| 목적 | 위임 토큰이 발급 시 지정한 API 서버 이외의 곳에서는 아예 쓸 수 없도록 한정한다 |

**요구사항**

- **F11-1** (필수) 위임 토큰은 발급 시 지정한 audience 이외의 서버에서는 검증에 실패한다.
- **F11-2** (필수) 서버는 자신을 가리키지 않는 `aud`를 가진 토큰을 거부한다.

> audience 값을 무엇으로 정의할지(단일 서버뿐인 현재 구성에서의 식별자 설계 포함)는 **PLAN에서 정한다.**

---

## F12. 비대칭 서명 (전체 토큰 적용 — 승인된 파괴적 변경)

| 항목 | 내용 |
|---|---|
| 적용 범위 | **전체 토큰** — F1(로그인)·F5(갱신)의 기존 사용자 토큰과 F9(위임) 토큰 모두 |
| 결정 근거 | `JWTUtil.java:21`의 하드코딩 대칭키(HS256)는 발급자와 검증자가 같은 키를 공유해, 검증만 해야 할 쪽도 위조 서명을 만들 수 있다. 발급 키와 검증 키를 분리해야 이 문제가 없어진다 |
| **파괴적 변경 승인** | 이 적용 범위 결정으로 **기존에 발급된 모든 HS256 토큰이 전환 시점 이후 즉시 검증 실패한다.** 학습·검증용 프로젝트이며 실사용 피해가 없다는 것을 사용자가 인지하고 승인했다. 전환 시 "이전 토큰은 모두 재로그인 필요"를 알리는 것으로 충분하며, 하위호환 브리지(구 토큰 유예 검증 등)는 요구되지 않는다 |

**요구사항**

- **F12-1** (필수) accessToken/refreshToken은 발급 키와 검증 키가 분리된 서명 방식을 쓴다. F1·F5·F9 어떤 경로로 발급되든 동일하게 적용된다.
- **F12-2** (필수) 응답 JSON의 필드 구성(F1의 `email`/`nickname`/`social`/`roleNames`/`accessToken`/`refreshToken`)은 바뀌지 않는다. 바뀌는 것은 서명 방식뿐이다.
- **F12-3** (필수) K4(`JWTUtil:21` 하드코딩 대칭키)는 F12 적용으로 해소된다 — 별도 조치가 필요 없다.

---

## F13. 감사 로그 — 위임자/행위자 짝 기록

| 항목 | 내용 |
|---|---|
| 목적 | 위임 호출이 발생했을 때, 누가 위임했고 누가 실제로 호출했는지 기록에 남겨 "행위자 구분 불가" 문제를 해소한다 |

**요구사항**

- **F13-1** (필수) `act`가 있는 요청(위임 호출)은 위임자와 행위자를 짝으로, 최소한 어떤 API를 호출했는지와 함께 기록된다.
- **F13-2** (필수) 사용자 본인 호출(act 없음)과 위임 호출(act 있음)이 기록만 보고 구분 가능해야 한다.
- **F13-3** (필수) 행위자는 **식별자와 함께 사람이 읽는 이름**으로 기록된다(F9-5).
  UUID만 남기면 로그를 나중에 봐도 어떤 에이전트였는지 알 수 없어, F13의 목적이 달성되지 않는다.

> 저장 위치·형식·보존 기간은 **PLAN에서 정한다.**

---

### F9~F13 — 확정된 구현 결정

PLAN 단계에서 정하고 구현으로 확인한 값들이다.

| 항목 | 확정값 |
|---|---|
| 위임자 식별자 | **`sub` 신설**(RFC 8693 관례대로 `act`와 짝). `email`은 F1 계약이라 위임 토큰에도 같은 값으로 함께 싣되, 인가 판정의 정본은 `sub`다. `JWTCheckFilter`는 "`sub`가 있으면 `sub`, 없으면 `email`" 한 줄 규칙으로 두 토큰을 구분 없이 처리한다 |
| scope 어휘 | **역할과 별도의 동작 단위** — `sample:read` · `sample:list` · `sample:admin`. `roleNames`(USER/MANAGER/ADMIN) 재사용을 택하지 않은 이유는, 역할을 통째로 위임하면 "목록만 읽어줘" 같은 좁은 위임이 불가능해 F10의 의미가 사라지기 때문이다 |
| scope 강제 지점 | `@PreAuthorize` + `@scopeAuth.has('...')` SpEL. `ScopeAuthorizer`가 **`act == null`이면 무조건 통과**시키므로 사용자 본인 호출(F9-2)은 scope 제약을 받지 않는다 — 의도된 fail-open이다. `SampleController` `/admin`은 `hasRole('ADMIN') and @scopeAuth.has('sample:admin')`으로 **역할과 scope 양쪽**을 요구한다 |
| 역할→scope 상한 | `ScopeCatalog`(코드 상수). `USER`·`MANAGER` → `{sample:read, sample:list}`, `ADMIN` → `+{sample:admin}`. F10-2 판정의 단일 출처. **빈 scope 요청은 거부**한다("빈 값 = 무제한"으로 오해될 여지 제거) |
| audience 값 | `application.properties`의 `aauth.jwt.audience`(기본 `agentic-auth-server`). 위임 토큰에만 실린다. 검증은 `JWTUtil`이 아니라 `DelegationValidator`가 한다 — `JWTUtil`은 알고리즘 검증만 책임지는 범용 유틸로 남긴다 |
| F5 재발급 시 승계 | `APIRefreshController`가 claims 맵을 통째로 재서명하므로 `act`/`scope`/`aud`가 **자동 승계**된다. 그것이 안전한 이유는 매 요청마다 필터가 에이전트 활성 상태를 다시 조회하기 때문이다. 단 `/api/member/**`는 F3 제외 경로라 필터를 안 거치므로, **`APIRefreshController`가 직접 `DelegationValidator`를 호출**한다 — 이 검증이 없으면 비활성화된 에이전트가 refresh로 부활한다 |
| 감사 로그 | 별도 로거 `com.agenticauth.audit` + `logback-spring.xml`의 전용 파일 appender. **DB 테이블이 아니다** |
| 발급 엔드포인트 | `POST /api/agent/register` · `POST /api/agent/delegate` · `POST /api/agent/{agentId}/deactivate`. `/api/member/login`을 확장하지 않았다 — F1 응답 계약을 건드리지 않기 위해서다 |
| 에이전트 테이블 | `agent(agent_id PK, owner_email FK→member.email, active, registered_at)` |
| 재위임 차단 | 위임 토큰(`act` 있음)으로는 `/api/agent/**` 전체를 쓸 수 없다. 막지 않으면 에이전트가 스스로에게 새 위임을 발급하거나 다른 에이전트를 등록해 자기 권한을 넓힐 수 있다. **위임은 사람이 시작해야 한다** |
| F12 키 관리 | `AAUTH_JWT_KEY_DIR`(기본 `./keys`)의 `jwt-private.key`/`jwt-public.key`. 없으면 `Keys.keyPairFor(RS256)`로 생성 후 저장한다. 첫 기동 자동 생성(개발 편의)과 재시작 후 토큰 유효(F2)를 둘 다 만족한다. ⚠️ **`keys/` 디렉터리가 유실되면 예고 없이 전원 재로그인이 필요해진다** — 영속 스토리지에 보존할 것 |
| 신규 의존성 | **없음.** jjwt 0.11.5가 이미 RS256을 지원한다(`SignatureAlgorithm.RS256`, `Keys.keyPairFor`, `signWith(Key, SignatureAlgorithm)`, `parserBuilder().setSigningKey(PublicKey)`) |

---

## 🔴 현재 코드의 알려진 결함 (Known Issues)

아래는 **명세 위반이지만 현재 코드에 남아 있는 항목**이다.
해당 파일을 수정하게 되면 사용자에게 알리고 **승인을 받은 뒤** 함께 고친다. 임의로 대량 리팩터링하지 않는다.

| #   | 위치                        | 문제                                                                             | 영향                                                               |
| --- | --------------------------- | -------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
> **현재 미해결 항목은 없다.** K1~K9는 모두 해결되었거나(아래 표) 오분류로 정정되었다(K6).

> `application.properties`의 DB 비밀번호가 평문인 것은 로컬 개발용이며 **git에 올리지 않는다.**
> 에이전트는 이 항목을 반복해서 지적하지 않는다.

### ⚪ 정정된 항목

| # | 원래 기록 | 확인 결과 |
|---|---|---|
| K6 | "`JWTUtil` 전반 — jjwt 0.11.5의 deprecated API(`setClaims`, `parserBuilder`, `setExpiration`) 사용" | **결함이 아니다.** `build.gradle`에 `-Xlint:deprecation`을 켜고 컴파일한 결과 **deprecation 경고 0건**이다. 이 API들은 0.11.5에서 정식 API이며 **0.12.x에서 deprecated된다.** 즉 "현재 결함"이 아니라 **업그레이드 시 대응할 항목**을 결함으로 잘못 기록한 것이다. `jjwt-api-0.11.5.jar`에서 실제로 `@Deprecated`인 것은 `signWith(SignatureAlgorithm, byte[])` 계열이며 이 프로젝트는 쓰지 않는다 |

> K6 확인 과정에서 **실제 경고 1건**(`JWTCheckFilter`의 unchecked cast)이 드러나 함께 정리했다.
> claims 캐스팅을 `stringList()` 한 곳에 모으고 거기서만 경고를 억제한다. 현재 컴파일 경고는 **0건**이다.

### ✅ 해결된 항목

| #   | 위치                        | 내용                                                              | 해결 |
| --- | --------------------------- | ----------------------------------------------------------------- | ---- |
| K1  | `JWTCheckFilter`            | 헤더가 `null`이면 `substring(7)`에서 NPE → catch가 삼켜 원인 왜곡 | `Bearer ` 접두어를 **검사 전에** 확인하고 `log.warn`으로 원인을 남긴다 |
| K2  | `JWTCheckFilter`            | 에러 응답에 `setStatus()`가 없어 HTTP **200**                     | `sendUnauthorized()`로 일원화 · **401** |
| K2′ | `APILoginFailHandler`       | 로그인 실패도 같은 이유로 HTTP **200**                            | **401** |
| K3  | `CustomControllerAdvice:42` | `ResponseEntity.ok()` — JWT 예외도 **200**                        | `ResponseEntity.status(UNAUTHORIZED)` · **401** |
| K5  | `MemberDTO.getClaims()`     | `pw`(BCrypt 해시)를 JWT claims에 포함 → payload에서 그대로 읽힘   | claims에서 제거 · 필터는 credentials에 `null`을 넣는다 **(F1 응답 계약 변경)** |
| K4  | `JWTUtil:21`                | 시크릿 키가 **소스에 하드코딩**된 대칭키(HS256) — 검증할 수 있는 쪽이 위조도 할 수 있었다 | **F12로 해소.** RS256 키쌍으로 전환하고 `AAUTH_JWT_KEY_DIR`(기본 `./keys`)에서 로드·없으면 생성한다. 발급은 개인키, 검증은 공개키 — 하드코딩 대칭키 자체가 사라졌다 |
| K9  | `JWTCheckFilter`            | `filterChain.doFilter()`가 `try` 블록 **안에** 있어 컨트롤러·서비스·DB의 **모든 예외**가 `catch(Exception e)`에 걸렸다 → 인증과 무관한 오류가 `ERROR_ACCESS_TOKEN` 401로 둔갑 (실제로 `agent` FK 제약 위반이 "토큰이 잘못됐다"로 보고됨) | 체인 호출을 **`try` 밖으로** 옮기고 각 `catch`에 `return`을 넣었다. 인증은 try 안에서 끝내고, 그 뒤 예외는 손대지 않고 흘려보낸다. 회귀 방지 테스트 `downstreamExceptionIsNotMaskedAsAuthFailure` 로 고정 |
| K7  | `CustomSecurityConfig`      | `authorizeHttpRequests` 미설정 — URL 레벨 인가가 없어 보호가 전적으로 `@PreAuthorize`에 의존했다. 컨트롤러에 애노테이션을 빠뜨리면 그 엔드포인트는 조용히 무방비가 된다 | `authorizeHttpRequests` 추가. `/api/member/**`·`/api/sample/public`·`/error`는 `permitAll`, 나머지는 `authenticated`. 역할·scope 판정은 그대로 `@PreAuthorize`가 담당한다 — 여기서는 **인증 여부만** 본다 |
| K8  | `APIRefreshController`      | `checkExpiredToken()`이 `"Expired"`만 만료로 봐서, 형식이 깨지거나 위조된 토큰을 "아직 유효"로 판정 → **그 망가진 토큰을 200과 함께 그대로 되돌려줬다.** 클라이언트는 "갱신됐다"고 믿고 같은 토큰으로 계속 실패한다 | `needsReissue()`로 이름을 바꾸고 **모든 검증 실패를 재발급 대상**으로 본다. 재발급은 `refreshToken`을 검증한 뒤에만 이뤄지므로 검증이 느슨해지지 않는다. `APIRefreshControllerTests` 4개로 고정 |

---

## 완료 판정 기준 (Definition of Done)

기능 변경 후 아래가 **전부** 참이어야 한다.

- [ ] F1~F8 중 어느 것도 깨지지 않았다
- [ ] `front`에서 F8-3의 버튼을 전부 눌러 기대 결과와 일치함을 확인했다
- [ ] 응답 JSON 필드명과 에러 문자열이 변경되지 않았다 (변경 시 사용자 승인 완료)
- [ ] `docs/3-TEST.md`의 회귀 체크리스트를 전부 통과했다
- [ ] 프론트엔드에 영향 가는 계약 변경을 명시적으로 보고했다

**F9~F13 (에이전트 위임 인증) 추가분**

- [ ] F9~F13이 각 섹션의 요구사항을 만족한다
- [ ] F12 적용으로 기존 HS256 토큰이 무효화된다는 점이 사용자 승인 사항으로 문서에 남아 있다
- [ ] 에이전트를 비활성화하면 해당 에이전트의 위임 토큰이 즉시(만료 전이라도) 거부됨을 확인했다 (F9-3)
- [ ] `front`에서 F8-6의 버튼을 전부 눌러 기대 결과와 일치함을 확인했다
