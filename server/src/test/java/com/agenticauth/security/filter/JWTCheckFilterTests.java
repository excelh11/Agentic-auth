package com.agenticauth.security.filter;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.agenticauth.dto.MemberDTO;
import com.agenticauth.security.DelegationValidator;
import com.agenticauth.util.CustomJWTException;
import com.agenticauth.util.JWTUtil;

import jakarta.servlet.FilterChain;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 필터는 Mock 객체로 검증한다 — 스프링 컨텍스트도 DB도 필요 없다.
 *
 * MockFilterChain 은 doFilter 가 호출되면 request/response 를 보관한다.
 * chain.getRequest() 가 null 이면 체인이 중단됐다는 뜻이다.
 *
 * DelegationValidator는 act가 있는 위임 토큰일 때만 호출되므로, 기존(act 없는) 케이스는
 * mock을 그냥 통과시켜도(기본적으로 아무 것도 하지 않음) 영향을 받지 않는다.
 */
public class JWTCheckFilterTests {

  private final DelegationValidator delegationValidator = mock(DelegationValidator.class);
  private final JWTCheckFilter filter = new JWTCheckFilter(delegationValidator);

  @AfterEach
  public void clear() {
    SecurityContextHolder.clearContext(); // 테스트 간 인증 상태 누수 방지
  }

  private String accessToken() {
    return JWTUtil.generateToken(Map.of(
        "email", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of("USER")), 10);
  }

  @Test
  @DisplayName("정상 토큰이면 SecurityContext 에 인증이 저장된다")
  public void validTokenStoresAuthenticationInSecurityContext() throws Exception {

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/user");
    request.addHeader("Authorization", "Bearer " + accessToken());
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    assertNotNull(auth);
    assertEquals("user1@aaa.com", ((MemberDTO) auth.getPrincipal()).getEmail());
    assertNotNull(chain.getRequest(), "체인이 진행됐어야 한다");
    assertNull(auth.getCredentials(), "인증이 끝난 뒤 자격증명을 들고 있으면 안 된다");
  }

  @Test
  @DisplayName("K5 — claims 에 비밀번호가 실리지 않는다")
  public void claimsDoNotCarryPassword() {

    MemberDTO memberDTO = new MemberDTO(
        "user1@aaa.com", "$2a$10$해시", "USER1", false, List.of("USER"));

    Map<String, Object> claims = memberDTO.getClaims();

    assertFalse(claims.containsKey("pw"), "pw 는 JWT payload 에서 그대로 읽힌다. 넣으면 안 된다");
    assertEquals(Set.of("email", "nickname", "social", "roleNames"), claims.keySet());
  }

  @Test
  @DisplayName("K5 — 발급된 토큰의 payload 를 디코딩해도 해시가 없다")
  public void decodedPayloadHasNoPasswordHash() {

    String token = JWTUtil.generateToken(
        new MemberDTO("user1@aaa.com", "$2a$10$해시", "USER1", false, List.of("USER")).getClaims(), 10);

    String payload = new String(
        Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

    assertFalse(payload.contains("$2a$10$"), "payload: " + payload);
  }

  @Test
  @DisplayName("F3 — 토큰이 없으면 ERROR_ACCESS_TOKEN 을 반환하고 체인을 중단한다")
  public void missingTokenReturnsErrorAccessTokenAndStopsChain() throws Exception {

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/user");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertTrue(response.getContentAsString().contains("ERROR_ACCESS_TOKEN"));
    assertNull(chain.getRequest(), "컨트롤러로 넘어가면 안 된다");
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(401, response.getStatus(), "인증 실패는 401로 나가야 한다");
  }

  @Test
  @DisplayName("K1 — Bearer 접두어가 없으면 401 로 거부한다")
  public void missingBearerPrefixRejectsWith401() throws Exception {

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/user");
    request.addHeader("Authorization", accessToken()); // "Bearer " 를 빼먹은 경우
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("ERROR_ACCESS_TOKEN"));
    assertNull(chain.getRequest());
  }

  @Test
  @DisplayName("F3 — 위조된 토큰이면 거부한다")
  public void tamperedTokenIsRejected() throws Exception {

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/user");
    request.addHeader("Authorization", "Bearer " + accessToken() + "tampered");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("ERROR_ACCESS_TOKEN"));
    assertNull(chain.getRequest());
  }

  @Test
  @DisplayName("F3 — 제외 경로는 토큰 없이도 통과한다")
  public void excludedPathsPassWithoutToken() throws Exception {

    for (String uri : List.of("/api/member/login", "/api/member/refresh", "/api/sample/public")) {

      MockFilterChain chain = new MockFilterChain();

      filter.doFilter(new MockHttpServletRequest("GET", uri),
          new MockHttpServletResponse(), chain);

      assertNotNull(chain.getRequest(), uri + " 는 통과해야 한다");
    }
  }

  @Test
  @DisplayName("F6 — OPTIONS 프리플라이트는 통과한다")
  public void optionsPreflightPasses() throws Exception {

    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(new MockHttpServletRequest("OPTIONS", "/api/sample/user"),
        new MockHttpServletResponse(), chain);

    assertNotNull(chain.getRequest(), "CORS preflight는 검사 없이 통과해야 한다");
  }

  @Test
  @DisplayName("K9 — 체인 뒤에서 난 예외를 인증 실패로 둔갑시키지 않는다")
  public void downstreamExceptionIsNotMaskedAsAuthFailure() throws Exception {

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/user");
    request.addHeader("Authorization", "Bearer " + accessToken());
    MockHttpServletResponse response = new MockHttpServletResponse();

    // 컨트롤러/서비스/DB 에서 나는 예외를 흉내낸다 (실제 사례: agent 테이블 FK 제약 위반)
    FilterChain exploding = (req, res) -> {
      throw new IllegalStateException("DB 폭발");
    };

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> filter.doFilter(request, response, exploding));

    assertEquals("DB 폭발", thrown.getMessage(), "원래 예외가 그대로 올라와야 한다");
    assertNotEquals(401, response.getStatus(), "인증과 무관한 오류를 401로 둔갑시키면 안 된다");
    assertFalse(response.getContentAsString().contains("ERROR_ACCESS_TOKEN"),
        "토큰은 멀쩡했다. ERROR_ACCESS_TOKEN 은 원인을 왜곡한다");
  }

  // ── F9~F13 위임 토큰 (T7) ──────────────────────────────────────
  //
  // DelegationValidator 는 mock 이다. 여기서 검증하는 것은 "필터가 act 를 보고
  // validator 를 부르는가, 그 결과에 따라 체인을 진행/중단하는가" 이지
  // validator 내부 판정이 아니다 — 그건 DelegationValidatorTests 의 몫이다.

  /** act·scope·sub·aud 를 실은 위임 토큰. 실제 발급 경로는 APIAgentController 다. */
  private String delegatedToken() {
    return JWTUtil.generateToken(Map.of(
        "email", "user1@aaa.com",
        "sub", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of("USER"),
        "scope", List.of("sample:read"),
        "act", "agent-1",
        "aud", "agentic-auth-server"), 10);
  }

  @Test
  @DisplayName("F9-1 — act 가 있고 validator 가 통과시키면 체인이 진행된다")
  public void actWithPassingValidatorContinuesChain() throws Exception {

    doNothing().when(delegationValidator).validate(any(), anyString());

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/user");
    request.addHeader("Authorization", "Bearer " + delegatedToken());
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertNotNull(chain.getRequest(), "체인이 진행됐어야 한다");

    MemberDTO principal =
        (MemberDTO) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

    assertEquals("agent-1", principal.getAct(), "행위자(act)가 복원돼야 한다 — F9-1");
    assertEquals("user1@aaa.com", principal.getEmail(), "위임자는 sub 를 근거로 삼는다");

    Set<String> authorities = principal.getAuthorities().stream()
        .map(a -> a.getAuthority()).collect(java.util.stream.Collectors.toSet());

    assertTrue(authorities.contains("ROLE_USER"), "위임자의 역할 권한이 남아 있어야 한다");
    assertTrue(authorities.contains("SCOPE_sample:read"), "scope 가 권한으로 올라와야 한다 — F10");

    // 매 요청마다 실제로 조회했는지 — F9-3 은 "발급 시점"이 아니라 "요청 시점" 게이트다
    verify(delegationValidator).validate(any(), anyString());
  }

  @Test
  @DisplayName("F9-3 — act 가 있고 validator 가 거부하면 401 로 체인이 중단된다")
  public void actWithRejectingValidatorStopsChainWith401() throws Exception {

    doThrow(new CustomJWTException("ERROR_AGENT_INACTIVE"))
        .when(delegationValidator).validate(any(), anyString());

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/user");
    request.addHeader("Authorization", "Bearer " + delegatedToken());
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("ERROR_AGENT_INACTIVE"),
        "사유를 ERROR_ACCESS_TOKEN 으로 뭉뚱그리지 않는다 — 프론트가 구분해야 한다");
    assertNull(chain.getRequest(), "컨트롤러로 넘어가면 안 된다");
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  @DisplayName("F9-2 — act 가 없는 토큰은 validator 를 부르지 않는다")
  public void tokenWithoutActDoesNotCallValidator() throws Exception {

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/sample/user");
    request.addHeader("Authorization", "Bearer " + accessToken());
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertNotNull(chain.getRequest());

    MemberDTO principal =
        (MemberDTO) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

    assertNull(principal.getAct(), "사용자 본인 토큰에는 act 가 없다 — F9-2");
    verifyNoInteractions(delegationValidator);
  }
}
