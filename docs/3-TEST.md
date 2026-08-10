# 3. TEST — 테스트하는 방법

> `docs/1-SPEC.md`의 각 기능(F1~F13)이 실제로 동작하는지 확인하는 절차.
> Security/JWT 관련 코드를 건드린 뒤에는 **§6 회귀 체크리스트를 전부** 수행하고 결과를 보고한다.

---

## 0-A. 최초 1회 준비 (MariaDB)

```sql
-- MariaDB에서 server/db/schema.sql 실행 (DB · 계정 생성)
--   mysql -u root -p < server/db/schema.sql
```

| 항목 | 값 |
|---|---|
| DB | `agenticauthdb` |
| 계정 | `aauthuser` / `aauthpw` |
| URL | `jdbc:mariadb://localhost:3306/agenticauthdb` |

테이블은 `spring.jpa.hibernate.ddl-auto=update` 가 첫 기동 때 자동 생성한다.

그다음 **테스트 계정을 만든다.**

```powershell
cd server
.\gradlew.bat test --tests "com.agenticauth.repository.MemberRepositoryTests"
```

| 계정 | 비밀번호 | 권한 |
|---|---|---|
| `user1@aaa.com` | `1111` | `ROLE_USER` |
| `admin@aaa.com` | `1111` | `ROLE_USER`, `ROLE_ADMIN` |

> 비밀번호는 반드시 `PasswordEncoder`로 인코딩해서 넣는다. SQL로 평문을 직접 INSERT하면 **로그인이 항상 `ERROR_LOGIN`** 이 된다.

---

## 0. 기본 명령 (Windows PowerShell 기준)

```powershell
cd server                                            # Gradle 프로젝트 루트는 server/ 다
.\gradlew.bat compileJava                            # 컴파일만 빠르게 확인
.\gradlew.bat test                                   # 전체 테스트
.\gradlew.bat test --tests "com.agenticauth.util.JWTUtilTests"   # 특정 클래스만
.\gradlew.bat bootRun                                # 서버 기동 (http://localhost:8080)
.\gradlew.bat clean build -x test                    # 산출물 재생성 (테스트 제외)
```

> 서버 포트는 **8080**이다 (`server.port=8080`).
> 테스트 리포트: `server/build/reports/tests/test/index.html`

---

## 1. 단위 테스트 — `JWTUtil` (DB·서버 불필요, **가장 먼저 작성**)

`JWTUtil`은 static 유틸이라 스프링 컨텍스트 없이 순수 JUnit으로 검증할 수 있다. 가장 빠르고 안정적인 안전망이다.

**`server/src/test/java/com/agenticauth/util/JWTUtilTests.java`**

```java
package com.agenticauth.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class JWTUtilTests {

    private Map<String, Object> sampleClaims() {
        return Map.of(
            "email", "user1@aaa.com",
            "nickname", "USER1",
            "social", false,
            "roleNames", List.of("USER"));
    }

    @Test
    void generateTokenThenValidateReturnsSameClaims() {
        String token = JWTUtil.generateToken(sampleClaims(), 10);

        Map<String, Object> result = JWTUtil.validateToken(token);

        assertEquals("user1@aaa.com", result.get("email"));
        assertEquals("USER1", result.get("nickname"));
        assertEquals(List.of("USER"), result.get("roleNames"));
    }

    @Test
    void expiredTokenThrowsExpiredException() {
        // Thread.sleep 대신 음수 min 으로 즉시 만료시킨다
        String token = JWTUtil.generateToken(sampleClaims(), -1);

        CustomJWTException ex = assertThrows(CustomJWTException.class,
                () -> JWTUtil.validateToken(token));

        assertEquals("Expired", ex.getMessage());
    }

    @Test
    void tamperedTokenThrowsException() {
        String token = JWTUtil.generateToken(sampleClaims(), 10) + "tampered";

        assertThrows(CustomJWTException.class, () -> JWTUtil.validateToken(token));
    }

    @Test
    void nonJwtStringThrowsMalFormed() {
        CustomJWTException ex = assertThrows(CustomJWTException.class,
                () -> JWTUtil.validateToken("this-is-not-a-jwt"));

        assertEquals("MalFormed", ex.getMessage());
    }
}
```

**포인트**

- 만료 테스트는 `Thread.sleep()`을 쓰지 않는다. `generateToken(claims, -1)`이면 `setExpiration`이 과거 시각이 되어 즉시 만료된다.
- 이 4개가 통과하면 F1의 토큰 발급과 F3/F5의 검증 로직 기반이 확보된다.

---

## 2. 단위 테스트 — `JWTCheckFilter` (스프링 컨텍스트 불필요)

DB가 없어도 필터 자체는 Mock 객체로 검증할 수 있다. **DB 접속이 불안정한 환경에서 이 방식을 우선한다.**

**`server/src/test/java/com/agenticauth/security/filter/JWTCheckFilterTests.java`**

```java
package com.agenticauth.security.filter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.Mockito.mock;

import com.agenticauth.dto.MemberDTO;
import com.agenticauth.security.DelegationValidator;
import com.agenticauth.util.JWTUtil;

public class JWTCheckFilterTests {

    // F9~F13 이후 JWTCheckFilter 는 DelegationValidator 를 받는다.
    // act 가 없는 일반 토큰 케이스에서는 호출되지 않으므로 stub 없이 mock 만 넘기면 된다.
    private final DelegationValidator delegationValidator = mock(DelegationValidator.class);
    private final JWTCheckFilter filter = new JWTCheckFilter(delegationValidator);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();   // 테스트 간 인증 상태 누수 방지
    }

    private String accessToken() {
        return JWTUtil.generateToken(Map.of(
            "email", "user1@aaa.com", "nickname", "USER1",
            "social", false, "roleNames", List.of("USER")), 10);
    }

    @Test
    void validTokenStoresAuthenticationInSecurityContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/admin");
        request.addHeader("Authorization", "Bearer " + accessToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user1@aaa.com", ((MemberDTO) auth.getPrincipal()).getEmail());
        assertNotNull(chain.getRequest());          // 체인이 진행됐다
    }

    @Test
    void missingTokenReturnsErrorAccessTokenAndStopsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertTrue(response.getContentAsString().contains("ERROR_ACCESS_TOKEN"));
        assertNull(chain.getRequest());             // 컨트롤러로 안 넘어갔다
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, response.getStatus());    // K2 수정 후 반드시 확인한다
    }

    @Test
    void excludedPathsPassWithoutToken() throws Exception {
        for (String uri : List.of("/api/member/login", "/api/sample/public")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            assertNotNull(chain.getRequest(), uri + " 는 통과해야 한다");
        }
    }

    @Test
    void optionsPreflightPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/sample/admin");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }
}
```

> `MockFilterChain`은 `doFilter`가 호출되면 request/response를 보관한다.
> `chain.getRequest()`가 `null`이면 **체인이 중단됐다**는 뜻 — F3의 "실패 시 체인 미진행"을 이걸로 검증한다.

---

## 3. 통합 테스트 — MockMvc (실 DB 필요)

**`server/src/test/java/com/agenticauth/security/SecurityIntegrationTests.java`**

```java
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTests {

    @Autowired MockMvc mockMvc;

    @Test
    void loginReturnsBothTokens() throws Exception {
        mockMvc.perform(post("/api/member/login")
                    .param("username", "user1@aaa.com")
                    .param("password", "1111"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.accessToken").exists())
               .andExpect(jsonPath("$.refreshToken").exists())
               .andExpect(jsonPath("$.roleNames").isArray())
               .andDo(print());
    }

    @Test
    void wrongPasswordReturnsErrorLogin() throws Exception {
        mockMvc.perform(post("/api/member/login")
                    .param("username", "user1@aaa.com")
                    .param("password", "wrong"))
               .andExpect(content().string(containsString("ERROR_LOGIN")));
    }

    @Test
    void validTokenCallsProtectedApi() throws Exception {
        String token = JWTUtil.generateToken(Map.of(
            "email", "user1@aaa.com", "nickname", "USER1",
            "social", false, "roleNames", List.of("USER")), 10);

        mockMvc.perform(get("/api/sample/admin")
                    .header("Authorization", "Bearer " + token))
               .andExpect(status().isOk());
    }
}
```

**주의**

- `@SpringBootTest`는 **실제 MariaDB 접속이 필요**하다. DB가 닫혀 있으면 컨텍스트 로딩부터 실패한다. 그 경우 §2의 필터 단위 테스트로 대체한다.
- 테스트 계정(`user1@aaa.com` / `1111`)이 DB에 있어야 한다. `MemberRepositoryTests`로 먼저 확인한다.
- 비밀번호는 **BCrypt로 저장**되어 있어야 한다. 평문으로 넣으면 로그인이 실패한다.

---

## 4. `front` 로 브라우저 검증 (F8)

서버와 프론트를 **각각 띄운다.**

```powershell
# 터미널 1 — 백엔드
.\gradlew.bat bootRun                 # http://localhost:8080

# 터미널 2 — 프론트
cd ..\..\front
npm install
npm run dev                           # http://localhost:5173
```

브라우저에서 **`http://localhost:5173`** 접속.

### 화면에서 순서대로 눌러 확인할 것

| 순서 | 동작 | 기대 결과 | SPEC |
|---|---|---|---|
| 1 | **로그인** (`user1@aaa.com` / `1111`) | accessToken·refreshToken 발급, payload와 남은 시간 표시 | F1 |
| 2 | **틀린 비밀번호로 로그인** | **401** `ERROR_LOGIN` | F1 |
| 3 | **보호 API 호출 (토큰 O)** — `/api/sample/user` | 200 + 목록 | F3 |
| 4 | **보호 API 호출 (토큰 X)** | **401** `ERROR_ACCESS_TOKEN` | F3 |
| 5 | **위조 토큰으로 호출** | **401** `ERROR_ACCESS_TOKEN` | F3 |
| 6 | **권한 부족 API** — `/api/sample/admin` (ADMIN 전용) | **403** `ERROR_ACCESSDENIED` | F4 |
| 7 | **refresh 호출** (accessToken 유효 상태) | 기존 토큰 쌍 그대로 반환 | F5 |
| 8 | **accessToken 강제 만료** → 보호 API 호출 | **401** `ERROR_ACCESS_TOKEN` | F3 |
| 9 | 이어서 **refresh 호출** | 새 accessToken 발급 | F5 |
| 10 | **refreshToken 없이 refresh** | **400** (파라미터 누락) — `NULL_REFRASH` 아님 | F5, F7 |

> **눈여겨볼 것 ①** — 2·4·5·8번은 이제 **`HTTP 401`** 로 찍힌다 (K1·K2·K3 수정 완료).
> 다만 프론트는 여전히 **본문의 에러 코드로 분기**한다 — 어느 관문에서 막혔는지는 상태코드만으로 알 수 없기 때문이다.
> 401 하나에 `ERROR_LOGIN` · `ERROR_ACCESS_TOKEN` · `Expired` · `INVALID_STRING` 이 모두 들어온다.
>
> **눈여겨볼 것 ②** — 이 화면은 `:5173`, API는 `:8080`이므로 **모든 요청이 cross-origin**이다.
> 브라우저 개발자도구 Network 탭에 **`OPTIONS` preflight가 먼저 찍히면 F6가 살아 있는 것**이다.

### 이어서 — 위임 인증 (F9~F13, F8-6)

**1~10번을 먼저 하고 로그인 상태에서** 아래를 순서대로 누른다. 1~3번은 순서를 지켜야 한다(등록 → 발급 → 확인).

| 순서 | 동작 | 기대 결과 | SPEC |
|---|---|---|---|
| 1 | **에이전트 등록** | `agentId` 발급 | F9 |
| 2 | **위임 토큰 발급** (`sample:read` 만) | `sub`·`act`·`scope`·`aud` 가 실린 토큰 | F9 |
| 3 | **위임 토큰 payload 보기** | 사용자 토큰에는 없던 네 클레임이 보인다 | F9-1 |
| 4 | **scope 안의 API** — `/api/sample/user` | 200 | F10-1 |
| 5 | **scope 밖의 API** — `/api/sample/list` | **403** `ERROR_SCOPE` | F10-1 |
| 6 | **audience 조작 호출** | **401** `ERROR_ACCESS_TOKEN` ※ | F11, F12 |
| 7 | **권한 초과 위임 시도** (`sample:admin`) | `ERROR_SCOPE_EXCEEDS_ROLE` | F10-2 |
| 8 | **위임 토큰으로 재위임 시도** | 거부 `ERROR_SCOPE` | F9 |
| 9 | **에이전트 회수(비활성화)** | 200 · **사용자 본인 토큰은 그대로 살아 있다** | F9-3, F9-4 |
| 10 | **회수 후 위임 토큰으로 호출** | **401** `ERROR_AGENT_INACTIVE` — 만료 전인데도 막힌다 | F9-3 |

> **눈여겨볼 것 ③** — 9번 직후 화면 위쪽 "현재 토큰 상태"를 보라. **사용자 토큰은 멀쩡하다.**
> 에이전트만 끊었는데 사용자는 로그아웃되지 않는 것 — 이게 F9-4(개별 회수)가 해결한 문제다.
>
> **눈여겨볼 것 ④** — 4번과 5번의 차이. 같은 위임 토큰인데 하나는 통과하고 하나는 막힌다.
> 사용자 본인 토큰으로는 **둘 다 200**이다(회귀 체크리스트 21번). scope 는 위임에만 걸리는 추가 제약이다.
>
> ※ **6번은 `ERROR_AUDIENCE` 가 아니라 `ERROR_ACCESS_TOKEN` 이 정상이다.**
> 브라우저에는 서명용 개인키가 없어 `aud` 를 바꾸면 서명이 깨지고, 서버는 서명 검증을 audience 검증보다
> 먼저 한다. **F12(비대칭 서명)가 동작한다는 증거**이지 결함이 아니다.
> `ERROR_AUDIENCE` 는 `SecurityIntegrationTests` 에서 확인한다(서버가 서명하므로 임의 `aud` 를 실을 수 있다).

### 화면으로 확인할 수 없는 것

| 항목 | 이유 | 대체 방법 |
|---|---|---|
| refreshToken rotation (잔여 60분 미만) | 24시간을 기다려야 한다 | `APILoginSuccessHandler`의 refreshToken 분(`60*24`)을 임시로 `70`으로 낮춰 재로그인 후 확인. **확인 뒤 되돌린다.** |

### 화면이 동작하지 않을 때

| 증상 | 원인 |
|---|---|
| 콘솔에 `blocked by CORS policy` | 백엔드가 안 떠 있거나, `corsConfigurationSource`의 `allowedHeaders`에 사용한 헤더가 없다 |
| 로그인이 계속 `ERROR_LOGIN` | JSON으로 보내고 있다. **`application/x-www-form-urlencoded`** 여야 한다 |
| 로그인은 되는데 이후 전부 `ERROR_ACCESS_TOKEN` | `Authorization` 헤더에 `Bearer ` 접두어(뒤 공백 포함)가 빠졌다 |
| `Failed to fetch` | API 포트가 틀렸다. `application.properties`의 `server.port`와 프론트의 `API_BASE`를 대조한다 |

---

## 5. 수동 E2E 테스트 — curl (서버 기동 후)

```powershell
.\gradlew.bat bootRun    # 별도 터미널에서
```

```powershell
# ── F1. 로그인 → 토큰 획득
curl -X POST "http://localhost:8080/api/member/login" `
     -d "username=user1@aaa.com&password=1111"

# ── F3. 보호된 API 호출
curl "http://localhost:8080/api/sample/admin" `
     -H "Authorization: Bearer <accessToken>"

# ── F3. 토큰 없이 호출 → ERROR_ACCESS_TOKEN
curl "http://localhost:8080/api/sample/admin"

# ── F3. 제외 경로는 토큰 없이 통과
curl "http://localhost:8080/api/sample/public"

# ── F5. 토큰 갱신
curl "http://localhost:8080/api/member/refresh?refreshToken=<refreshToken>" `
     -H "Authorization: Bearer <accessToken>"

# ── F5. refreshToken 누락 → NULL_REFRASH
curl "http://localhost:8080/api/member/refresh" `
     -H "Authorization: Bearer <accessToken>"

# ── F6. CORS preflight (-i 로 응답 헤더 확인)
curl -i -X OPTIONS "http://localhost:8080/api/sample/admin" `
     -H "Origin: http://localhost:5173" `
     -H "Access-Control-Request-Method: GET" `
     -H "Access-Control-Request-Headers: Authorization"
```

**F6 preflight 성공 판정** — 응답에 아래 헤더가 있어야 한다.

```
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Methods: HEAD,GET,POST,PUT,DELETE
Access-Control-Allow-Headers: Authorization, Cache-Control, Content-Type
Access-Control-Allow-Credentials: true
```

**만료 시나리오를 직접 보고 싶다면** — `APILoginSuccessHandler`의 accessToken 유효시간을 임시로 `1`분으로 낮추고 로그인 → 1분 대기 → 호출 → `ERROR_ACCESS_TOKEN` 확인 → refresh 호출로 복구. **확인 후 반드시 10분으로 되돌린다.**

---

## 6. 회귀 체크리스트 (Security/JWT 변경 시 필수)

| # | 시나리오 | 기대 결과 | SPEC |
|---|---|---|---|
| 1 | 올바른 계정으로 로그인 | 200 + accessToken/refreshToken 반환 | F1 |
| 2 | 틀린 비밀번호로 로그인 | `ERROR_LOGIN` | F1 |
| 3 | 없는 계정으로 로그인 | `ERROR_LOGIN` | F1 |
| 4 | 토큰 없이 보호 API 호출 | `ERROR_ACCESS_TOKEN`, 컨트롤러 미실행 | F3 |
| 5 | 정상 토큰으로 보호 API 호출 | 200 | F3 |
| 6 | 만료 토큰으로 호출 | `ERROR_ACCESS_TOKEN` | F3 |
| 7 | 위조 토큰으로 호출 | `ERROR_ACCESS_TOKEN` | F3 |
| 8 | `/api/member/**` 토큰 없이 호출 | 통과 | F3 |
| 9 | `/api/sample/public` 토큰 없이 호출 | 통과(이미지 반환) | F3 |
| 10 | `OPTIONS` preflight | 200 + `Access-Control-Allow-*` 헤더 | F3, F6 |
| 11 | 만료 access + 유효 refresh 로 갱신 | 새 accessToken 발급 | F5 |
| 12 | 유효 access 로 갱신 | 기존 토큰 그대로 반환 | F5 |
| 13 | refreshToken 누락 | `NULL_REFRASH` | F5 |
| 14 | refresh 잔여 60분 미만 | refreshToken도 재발급 | F5 |
| 15 | 권한 없는 롤로 `@PreAuthorize` 호출 | **403** + `ERROR_ACCESSDENIED` | F4 |
| 16 | 로그인 응답 필드명 | 프론트 계약과 동일 | F1 |
| 17 | 에러 문자열 | `docs/1-SPEC.md` F7 표와 동일 | F7 |
| 18 | `OPTIONS` preflight를 curl로 확인 | `Access-Control-Allow-*` 헤더 반환 | F6 |
| 19 | `front`에서 로그인 → 보호 API 호출 | CORS 에러 없이 성공 | F6, F8 |
| 20 | `front`의 버튼 10개(§4)를 전부 실행 | 기대 결과와 일치 | F8 |

### F9~F13 — 에이전트 위임 인증

| # | 시나리오 | 기대 결과 | SPEC |
|---|---|---|---|
| 21 | **일반 USER 토큰**으로 `/api/sample/user`·`/api/sample/list` 호출 | **200** — scope 제약을 받지 않는다 | F10, **회귀 최우선** |
| 22 | scope 내 위임 토큰으로 호출 | 200 | F10-1 |
| 23 | scope 밖 위임 토큰으로 호출 | **403** `ERROR_SCOPE` | F10-1 |
| 24 | 위임자가 갖지 않은 권한을 위임 시도 | `ERROR_SCOPE_EXCEEDS_ROLE` | F10-2 |
| 25 | audience가 다른 위임 토큰으로 호출 | **401** `ERROR_AUDIENCE` | F11 |
| 26 | 에이전트 비활성화 후 기존 위임 토큰(만료 전)으로 호출 | **401** `ERROR_AGENT_INACTIVE` | F9-3 |
| 27 | 에이전트 비활성화 후 그 위임 refreshToken으로 `/api/member/refresh` | 거부 — **회수 우회 통로 차단** | F9-3, F5 |
| 28 | 위임 토큰으로 `/api/agent/**` 호출(재위임·재등록) | 거부 | F9 |
| 29 | 위임 호출과 본인 호출이 감사 로그에서 구분됨 | `delegation-call \| delegator=… \| actor(agent)=…` | F13 |
| 30 | 역할은 ADMIN인데 scope에 `sample:admin`이 없는 위임 토큰 | **403** — 역할과 scope를 **둘 다** 만족해야 한다 | F10 |

### 해결된 결함의 회귀 방지

| # | 시나리오 | 기대 결과 | 결함 |
|---|---|---|---|
| 31 | 토큰 없이 `/api/sample/user`·`/api/agent/register` | **401** — URL 레벨 인가가 바닥을 막는다 | K7 |
| 32 | 토큰 없이 `/api/sample/public`·`/api/member/refresh` | 인가에 막히지 않는다 (`permitAll`) | K7 |
| 33 | `OPTIONS` preflight | **200** — 인가가 preflight를 막지 않는다 | K7, F6 |
| 34 | **위조된** accessToken + 유효 refreshToken 으로 갱신 | **새 accessToken 발급** — 받은 토큰을 그대로 되돌려주지 않는다 | K8 |
| 35 | 34에서 받은 토큰으로 보호 API 호출 | 200 — 실제로 쓸 수 있는 토큰이어야 한다 | K8 |
| 36 | accessToken·refreshToken 둘 다 망가진 채로 갱신 | **401** `MalFormed` — 재발급하지 않는다 | K8 |
| 37 | 필터 뒤(컨트롤러·DB)에서 예외 발생 | `ERROR_ACCESS_TOKEN` 401로 **둔갑하지 않는다** | K9 |
| 38 | 이름 없이 에이전트 등록 | `ERROR_AGENT_NAME_REQUIRED` | F9-5 |
| 39 | 감사 로그의 `actor` | UUID가 아니라 **이름이 함께** 남는다 | F13-3 |

### 에이전트 클라이언트로 한 번에 확인하기

위 21~39번의 대부분을 **실제 클라이언트 입장에서** 한 번에 돌릴 수 있다.

```bash
cd agent-example
node agent.mjs
```

`[사용자]` 와 `[에이전트]` 를 나눠 출력하고, 기대와 다르면 **종료 코드 1** 로 끝난다.
브라우저 없이 위임 생애주기(등록 → 발급 → scope 안/밖 → 권한 상승 시도 → 회수 → 차단)를 전부 확인한다.

> **21번이 가장 중요하다.** `/user`·`/list`에는 이번에 **처음으로** `@PreAuthorize`가 붙었다.
> `ScopeAuthorizer`의 fail-open(`act == null`이면 통과)이 동작하지 않으면 이 두 경로가 조용히 403이 된다 — F1~F8 회귀다.

---

## 7. 디버깅 가이드

| 증상 | 원인 후보 | 확인 방법 |
|---|---|---|
| `check uri.......` 로그가 안 찍힘 | 필터 미등록 또는 `shouldNotFilter`가 true | `CustomSecurityConfig`의 `addFilterBefore` 확인, URI 접두어 확인 |
| curl은 되는데 브라우저만 CORS 에러 | preflight(OPTIONS) 차단 | `shouldNotFilter`의 OPTIONS 분기, `corsConfigurationSource`의 allowedHeaders 확인 |
| 로그인은 되는데 다음 요청이 401 | 프론트가 `Bearer ` 접두어를 안 붙임 | 요청 헤더 원문 확인 |
| `ClassCastException` on `getPrincipal()` | claims 구조와 `MemberDTO` 생성자 불일치 | `MemberDTO.getClaims()` ↔ `JWTCheckFilter`의 claims 읽는 부분 대조 |
| `NullPointerException` in `JWTCheckFilter` | K1 — `Authorization` 헤더 null | 헤더 null/`Bearer ` 검사 추가 |
| `LazyInitializationException` | `getWithRoles` 대신 일반 조회 사용 | `CustomUserDetailsService` 확인 |
| `WeakKeyException` | 시크릿이 32바이트 미만 | `JWTUtil.key` 길이 확인 |
| 403인데 원인 불명 | `@PreAuthorize` 롤 이름에 `ROLE_` 중복 | `hasRole('USER')` (접두어 없이) 인지 확인 |

**필터체인 전체를 로그로 보고 싶을 때** — `application.properties`에 이미 아래가 켜져 있다.

```properties
logging.level.org.springframework.security.web=trace
```

더 넓게 보려면 `logging.level.org.springframework.security=DEBUG` 로 바꾼다. **확인 후 되돌린다.**
