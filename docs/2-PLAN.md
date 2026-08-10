# 2. PLAN — 어떻게? (기능 구현에 필요한 기술 목록)

> `docs/1-SPEC.md`의 기능을 **어떤 기술로** 구현하는지 정의한다.
> 이 목록에 없는 라이브러리·프레임워크는 **사용자 승인 없이 도입하지 않는다.**

---

## 1. 런타임 / 빌드

| 항목 | 버전 | 확인 위치 |
|---|---|---|
| Java | **21** (toolchain) | `build.gradle` |
| Spring Boot | **3.5.15** | `build.gradle` plugins |
| Spring Dependency Management | 1.1.7 | `build.gradle` plugins |
| 빌드 도구 | Gradle Wrapper (`gradlew.bat`) | `server/` (Gradle 프로젝트 루트) |
| 서버 포트 | **8080** | `application.properties` |

> Spring Boot 3.x → **Spring Security 6.x**, **Jakarta EE 9+**
> `javax.servlet.*` 이 아니라 **`jakarta.servlet.*`** 를 import 한다.

---

## 2. 인증/인가 핵심 라이브러리

### 2-1. `spring-boot-starter-security` (Spring Security 6.x)

| 구성요소 | 사용처 |
|---|---|
| `SecurityFilterChain` (Bean) | `CustomSecurityConfig.filterChain()` — 필터체인 전체 조립 |
| `HttpSecurity` 람다 DSL | `http.csrf(config -> config.disable())` 형태 |
| `OncePerRequestFilter` | `JWTCheckFilter` — 요청당 1회 실행 보장 |
| `UsernamePasswordAuthenticationFilter` | JWT 필터 삽입 기준점 (`addFilterBefore`) |
| `UserDetailsService` | `CustomUserDetailsService` — 이메일로 회원 조회 |
| `UserDetails` 구현 | `MemberDTO extends User` — 인증 주체 겸 DTO |
| `SimpleGrantedAuthority` | `ROLE_` 접두어를 붙여 권한 표현 |
| `BCryptPasswordEncoder` | 비밀번호 단방향 해시 |
| `SecurityContextHolder` | 인증 결과를 스레드 로컬에 저장 |
| `@EnableMethodSecurity` + `@PreAuthorize` | 메서드 단위 인가 |
| `AuthenticationSuccessHandler` | `APILoginSuccessHandler` — 토큰 발급 |
| `AuthenticationFailureHandler` | `APILoginFailHandler` |
| `AccessDeniedHandler` | `CustomAccessDeniedHandler` — 403 |
| `SessionCreationPolicy.STATELESS` | 무상태 |
| `CorsConfigurationSource` | CORS 단일 진실 공급원 |

**🚫 사용 금지 (Spring Security 5 문법)**

| 금지 | 대체 |
|---|---|
| `WebSecurityConfigurerAdapter` 상속 | `SecurityFilterChain` **Bean** 반환 |
| `.and()` 체이닝 | 람다 DSL로 각각 호출 |
| `antMatchers()`, `mvcMatchers()` | `requestMatchers()` |
| `authorizeRequests()` | `authorizeHttpRequests()` |
| `@EnableGlobalMethodSecurity` | `@EnableMethodSecurity` |

---

### 2-2. `io.jsonwebtoken:jjwt` **0.11.5**

```gradle
implementation 'io.jsonwebtoken:jjwt-api:0.11.5'
runtimeOnly   'io.jsonwebtoken:jjwt-impl:0.11.5'
runtimeOnly   'io.jsonwebtoken:jjwt-jackson:0.11.5'
```

| API | 용도 |
|---|---|
| `Jwts.builder()` | 토큰 생성 |
| `.setHeader() / .setClaims() / .setIssuedAt() / .setExpiration() / .signWith() / .compact()` | 0.11.x 빌더 체인 |
| `Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody()` | 파싱 + 서명 검증 |
| `Keys.hmacShaKeyFor(byte[])` | `SecretKey` 생성 |
| `ExpiredJwtException` / `MalformedJwtException` / `InvalidClaimException` / `JwtException` | 예외 분기 → `CustomJWTException` 변환 |

**주의사항**

- 서명 알고리즘 **HS256** → 시크릿은 **최소 32바이트(256bit)**. 현재 키는 40자라 충족한다. 더 짧게 바꾸면 `WeakKeyException` 발생.
- **0.12.x 로 올리면 API가 전부 바뀐다** (`claims()`, `parser()`, `expiration()`, `verifyWith()`). 업그레이드 시 `JWTUtil` 전면 수정 + 전 구간 재테스트 필요. 승인 없이 올리지 않는다.
- `jjwt-impl`, `jjwt-jackson`은 반드시 `runtimeOnly` — `implementation`으로 올리면 내부 API에 컴파일 의존이 생긴다.

---

### 2-3. `com.google.code.gson:gson` 2.10.1

| 사용처 | 이유 |
|---|---|
| `JWTCheckFilter`, `APILoginSuccessHandler`, `APILoginFailHandler`, `CustomAccessDeniedHandler` | **서블릿 필터/핸들러 계층은 `@RestController` 바깥**이라 Jackson 자동 직렬화가 걸리지 않는다. 직접 `response.getWriter()`에 써야 하므로 Gson으로 수동 직렬화한다. |

- 한글 응답이 필요하면 `response.setContentType("application/json; charset=UTF-8")` 를 반드시 지정한다.
  (`APILoginSuccessHandler`만 charset이 있고 나머지 3개에는 없다 — 한글 메시지 추가 시 깨진다.)
- **컨트롤러 계층에서는 Gson을 쓰지 않는다.** Spring MVC의 Jackson이 처리한다.

---

### 2-4. 검증용 프론트엔드 `front` (F8)

**백엔드 프로젝트가 아니다.** `../front/`에 따로 둔다. gradle 의존성은 늘지 않는다.

| 기술 | 버전 | 비고 |
|---|---|---|
| Vite | **8.2** | `npm create vite@latest front -- --template react-ts` 로 생성 |
| React | **19.2** | 상태관리 라이브러리 없이 `useState` / `useEffect` 만 |
| TypeScript | **6.0** | 응답 타입을 SPEC F1의 필드로 정의 (`src/types.ts`) |
| `fetch` | 내장 | axios 등 HTTP 라이브러리 추가 없음 |
| oxlint | 1.75 | 스캐폴딩 기본값. `npm run lint` |

**파일 구성**

| 파일 | 역할 |
|---|---|
| `src/types.ts` | 백엔드 계약 — 응답 타입 + 에러 코드 상수 (F1, F5, F7) |
| `src/api.ts` | `API_BASE`, 호출 함수, 토큰 보관, JWT 디코딩 |
| `src/App.tsx` | 화면 — 로그인 / 토큰 상태 / 테스트 버튼 / 로그 |
| `src/index.css` | 스타일 (라이트·다크 대응) |

**설계 원칙 — 프론트가 F2(무상태)를 깨지 않게 한다**

- 토큰은 `localStorage`에 둔다. **쿠키·세션을 쓰지 않는다.**
- `credentials: 'include'` 를 쓰지 않는다. 인증은 오직 `Authorization` 헤더다.
- **Vite proxy를 설정하지 않는다.** proxy를 켜면 브라우저가 same-origin으로 인식해 **F6(CORS)이 검증되지 않는다.**
  `http://localhost:8080/api/...` 절대 URL로 호출한다.
- 에러 판정은 **본문의 에러 코드 문자열(F7)** 로 한다. HTTP 상태코드로 분기하면 K2/K3(에러도 200) 때문에 동작하지 않는다.
- 로그인은 **`application/x-www-form-urlencoded`** 다. JSON으로 보내면 Spring Security formLogin이 파라미터를 읽지 못한다.

**타입 정의는 SPEC을 따른다** — 로그인 응답 필드는 `docs/1-SPEC.md` F1의 JSON과 1:1로 맞춘다.

**이 프로젝트는 백엔드를 수정하지 않는다.** 프론트에서 불편한 점(예: 401이 아니라 200이 온다)이 나오면 **K 항목으로 이미 기록돼 있으니**, 고칠지 여부는 SPEC 단계에서 별도로 결정한다.

---

### 2-5. 데이터 계층

| 기술 | 용도 | 주의점 |
|---|---|---|
| `spring-boot-starter-data-jpa` | `MemberRepository` | |
| `MemberRepository.getWithRoles(email)` | 회원 + 권한 조회 | `memberRoleList`가 지연 로딩이므로 **fetch join 필수**. 일반 `findById`로 바꾸면 `LazyInitializationException` |
| MariaDB (`org.mariadb.jdbc:mariadb-java-client`) | `jdbc:mariadb://localhost:3306/agenticauthdb` | `runtimeOnly`. DB·계정 생성은 `server/db/schema.sql` |
| `spring.jpa.hibernate.ddl-auto=update` | 스키마 자동 반영 | |

---

### 2-6. 보조 기술

| 기술 | 용도 |
|---|---|
| Lombok (`@Log4j2`, `@RequiredArgsConstructor`, `@Getter/@Setter`) | 보일러플레이트 제거 |
| Log4j2 | `logging.level.org.springframework.security.web=trace` 로 필터체인 추적 중 |
| `spring-boot-starter-test` (JUnit 5, MockMvc, AssertJ) | 테스트 |

> **`spring-security-test`는 현재 미포함**이다.
> `@WithMockUser`, `SecurityMockMvcRequestPostProcessors.jwt()` 등을 쓰려면 아래를 추가해야 하며, **사용자 승인이 필요**하다.
> ```gradle
> testImplementation 'org.springframework.security:spring-security-test'
> ```
> 승인 전에는 **실제 토큰을 `JWTUtil.generateToken()`으로 만들어 헤더에 실는 방식**으로 테스트한다.

---

## 3. 요청 처리 흐름 (필터 순서)

```
HTTP 요청
   │
   ├─ CorsFilter                     ← corsConfigurationSource() 적용
   │
   ├─ JWTCheckFilter                 ← addFilterBefore(..., UsernamePasswordAuthenticationFilter)
   │     ├ shouldNotFilter() true  → 검사 없이 통과 (OPTIONS, /api/member/**, /api/sample/public)
   │     ├ 토큰 정상 → SecurityContextHolder 에 인증 저장 → 계속
   │     └ 토큰 오류 → ERROR_ACCESS_TOKEN JSON 반환, 체인 중단
   │
   ├─ UsernamePasswordAuthenticationFilter   ← /api/member/login 처리
   │     ├ 성공 → APILoginSuccessHandler  (accessToken/refreshToken 발급)
   │     └ 실패 → APILoginFailHandler     (ERROR_LOGIN)
   │
   ├─ ExceptionTranslationFilter
   │     └ 권한 부족 → CustomAccessDeniedHandler (403, ERROR_ACCESSDENIED)
   │
   ├─ AuthorizationFilter
   │
   └─ DispatcherServlet → @PreAuthorize 검사 → Controller
         └ 예외 → CustomControllerAdvice
```

---

## 4. 설계 규칙

1. **claims 구조는 단일 정의처를 갖는다** — `MemberDTO.getClaims()`가 쓰고, `JWTCheckFilter`가 읽는다. **한쪽만 바꾸면 즉시 깨진다. 항상 짝으로 수정한다.**
2. **핸들러는 현재 Bean이 아니다** — `new APILoginSuccessHandler()` 형태로 등록되어 있다. 의존성 주입(예: 시크릿 키, Repository)이 필요해지면 `@Bean`으로 승격하고 `filterChain(HttpSecurity http, APILoginSuccessHandler h)` 로 주입받는다.
3. **`JWTUtil`은 static 유틸**이다. 필드 주입이 불가하므로 시크릿 외부화 시 `@Component` 전환 또는 `@Value` + `static` 세터 패턴 중 하나를 선택해야 하며, 호출부 전체(`APILoginSuccessHandler`, `JWTCheckFilter`, `APIRefreshController`)가 영향받는다.
4. **필터 계층에서는 `throw` 하지 않는다** — `@RestControllerAdvice`가 필터 예외를 잡지 못한다. 필터 안에서 직접 응답을 써야 한다.
5. **인증 주체는 `MemberDTO`** — 컨트롤러에서 `@AuthenticationPrincipal MemberDTO memberDTO` 로 받는다. `Principal`이나 `String`으로 받으면 `ClassCastException`.

---

## 5. 신규 의존성 도입 기준

아래는 **사용자 승인 필수**다.

- 새 gradle 의존성 추가 (`spring-security-test` 포함)
- jjwt 버전 변경
- DB 스키마 변경 (`Member`, `MemberRole` 등)
- 시크릿/자격증명의 외부화 방식 결정 (properties / 환경변수 / AWS Secrets Manager)
