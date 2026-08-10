# front

백엔드의 **로그인과 JWT 동작만** 확인하는 최소 프론트엔드.
쇼핑몰 기능(상품·장바구니·주문)은 다루지 않는다.

요구사항 명세는 저장소 루트에 있다 → [`../docs/1-SPEC.md`](../docs/1-SPEC.md) 의 **F8**

---

## 실행

터미널 두 개가 필요하다.

```bash
# 터미널 1 — 백엔드 (http://localhost:8080)
cd server
./gradlew.bat bootRun

# 터미널 2 — 프론트 (http://localhost:5173)
cd front
npm install
npm run dev
```

브라우저에서 `http://localhost:5173` 접속.

테스트 계정: `user1@aaa.com` / `1111` (DB에 BCrypt로 저장돼 있어야 함)

---

## 화면에서 확인하는 것

| #   | 동작                             | 기대 결과                                               | SPEC |
| --- | -------------------------------- | ------------------------------------------------------- | ---- |
| 1   | 로그인                           | 200 · accessToken·refreshToken 발급, 남은 시간 표시     | F1   |
| 2   | 틀린 비밀번호로 로그인           | **401** `ERROR_LOGIN`                                   | F1   |
| 3   | 보호 API 호출 (토큰 O)           | 200 · 목록 반환                                         | F3   |
| 4   | 보호 API 호출 (토큰 X)           | **401** `ERROR_ACCESS_TOKEN`                            | F3   |
| 5   | 위조 토큰으로 호출               | **401** `ERROR_ACCESS_TOKEN`                            | F3   |
| 6   | ADMIN 전용 API 호출              | USER 계정이면 **403** `ERROR_ACCESSDENIED`              | F4   |
| 7   | refresh 호출 (토큰 유효)         | 200 · 기존 토큰 쌍 그대로 반환                          | F5   |
| 8   | accessToken 강제 만료 → API 호출 | **401** `ERROR_ACCESS_TOKEN`                            | F3   |
| 9   | 이어서 refresh 호출              | 200 · 새 accessToken 발급                               | F5   |
| 10  | refreshToken 없이 refresh        | **400** (파라미터 누락) — `NULL_REFRASH` 아님           | F5   |

---

## 설계상 일부러 이렇게 한 것

**1. Vite proxy를 쓰지 않는다**

`src/api.ts`의 `API_BASE`는 `http://localhost:8080` 절대 URL이다.
proxy를 켜면 브라우저가 same-origin으로 인식해서 **CORS(F6)가 실제로 동작하는지 확인할 수 없다.**
지금 구조에서는 개발자도구 Network 탭에 `OPTIONS` preflight가 먼저 찍힌다 — 그게 정상이다.

**2. 상태코드가 아니라 본문의 에러 코드로 판정한다**

백엔드는 인증 실패에 **401**을 내려준다. 하지만 `res.ok` 로 분기하면 **어느 관문에서 막혔는지 알 수 없다** —
401 하나에 `ERROR_LOGIN`(로그인 실패) · `ERROR_ACCESS_TOKEN`(토큰 검증 실패) ·
`Expired`(refresh 중 만료) · `INVALID_STRING`이 모두 들어오기 때문이다.
그래서 응답 본문의 `error` 문자열을 본다.

**3. 토큰은 `localStorage`에만 둔다**

쿠키·세션을 쓰지 않고 `credentials: 'include'` 도 쓰지 않는다.
백엔드가 `SessionCreationPolicy.STATELESS`(F2)이므로 인증은 오직 `Authorization` 헤더다.

**4. 로그인은 form-urlencoded 다**

Spring Security의 `formLogin`은 JSON 본문을 읽지 못한다.
`Content-Type: application/x-www-form-urlencoded` + `URLSearchParams` 여야 한다.

---

## 자주 겪는 문제

| 증상                                | 원인                                                                                                           |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `Failed to fetch`                   | 백엔드가 안 떠 있거나 포트가 다르다. `application.properties`의 `server.port`와 `src/api.ts`의 `API_BASE` 대조 |
| 콘솔에 `blocked by CORS policy`     | `CustomSecurityConfig.corsConfigurationSource()`의 `allowedHeaders`에 사용한 헤더가 없다                       |
| 로그인이 계속 `ERROR_LOGIN`         | JSON으로 보내고 있거나, DB의 비밀번호가 BCrypt가 아니다                                                        |
| 로그인 후 전부 `ERROR_ACCESS_TOKEN` | `Bearer ` 접두어(뒤 공백 포함)가 빠졌다                                                                        |

---

## 백엔드를 고쳐야 할 때

이 프로젝트는 **백엔드를 수정하지 않는다.**
백엔드 변경이 필요하면 **저장소 루트에서** Claude Code를 띄우고 4단계 파이프라인을 쓴다.

```
/agentic 토큰 만료 시 401 상태코드가 나가게 해줘
```

---

## 명령

```bash
npm run dev       # 개발 서버
npm run build     # 타입체크(tsc -b) + 프로덕션 빌드
npm run lint      # oxlint
npm run preview   # 빌드 결과 미리보기
```
