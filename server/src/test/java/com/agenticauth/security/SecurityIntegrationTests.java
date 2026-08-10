package com.agenticauth.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import com.agenticauth.domain.Agent;
import com.agenticauth.repository.AgentRepository;
import com.agenticauth.util.JWTUtil;

/**
 * T12 — F10(scope) 도입이 <b>기존 사용자 경로를 깨뜨리지 않았는지</b> 확인한다.
 *
 * `/api/sample/user` 와 `/api/sample/list` 에는 이번에 <b>처음으로</b> @PreAuthorize 가 붙었다.
 * 그전에는 애노테이션이 없어 JWT만 있으면 무조건 통과했다. ScopeAuthorizer 의 fail-open
 * (act == null 이면 무조건 true)이 실제로 동작하지 않으면 이 두 경로가 조용히 403이 된다 —
 * F1~F8 회귀다. 그래서 이 테스트가 T11 의 관문이다.
 *
 * 실제 MariaDB 접속이 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SecurityIntegrationTests {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AgentRepository agentRepository;

  @Value("${aauth.jwt.audience}")
  private String audience;

  /** 사용자 본인 로그인 토큰(F1) — act·scope·sub·aud 가 없다. */
  private String userToken(String... roles) {
    return JWTUtil.generateToken(Map.of(
        "email", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of(roles)), 10);
  }

  private String delegatedToken(String agentId, List<String> roles, List<String> scope) {
    return JWTUtil.generateToken(Map.of(
        "email", "user1@aaa.com",
        "sub", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", roles,
        "scope", scope,
        "act", agentId,
        "aud", audience), 10);
  }

  private void registerAgent(String agentId, boolean active) {
    agentRepository.save(Agent.builder()
        .agentId(agentId)
        .name("테스트 봇 " + agentId)   // F9-5 — 감사 로그가 이 이름을 쓴다
        .description("통합 테스트용")
        .ownerEmail("user1@aaa.com")
        .active(active)
        .registeredAt(java.time.LocalDateTime.now())
        .build());
  }

  // ── 회귀: 일반 사용자 토큰은 scope 제약을 받지 않는다 ──────────

  @Test
  @DisplayName("T12 — 일반 USER 토큰으로 /api/sample/user 는 여전히 200")
  public void plainTokenStillPassesSampleUser() throws Exception {

    mockMvc.perform(get("/api/sample/user")
            .header("Authorization", "Bearer " + userToken("USER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("USER_OK")));
  }

  @Test
  @DisplayName("T12 — 일반 USER 토큰으로 /api/sample/list 는 여전히 200")
  public void plainTokenStillPassesSampleList() throws Exception {

    mockMvc.perform(get("/api/sample/list")
            .header("Authorization", "Bearer " + userToken("USER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("LIST_OK")));
  }

  @Test
  @DisplayName("F4 회귀 — USER 계정으로 /api/sample/admin 은 여전히 403 ERROR_ACCESSDENIED")
  public void plainUserTokenDeniedOnAdminWithLegacyErrorCode() throws Exception {

    mockMvc.perform(get("/api/sample/admin")
            .header("Authorization", "Bearer " + userToken("USER")))
        .andExpect(status().isForbidden())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_ACCESSDENIED")));
  }

  @Test
  @DisplayName("F4 회귀 — ADMIN 계정으로 /api/sample/admin 은 200")
  public void plainAdminTokenPassesAdmin() throws Exception {

    mockMvc.perform(get("/api/sample/admin")
            .header("Authorization", "Bearer " + userToken("USER", "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ADMIN_OK")));
  }

  // ── F10: 위임 토큰은 scope 안에서만 움직인다 ──────────────────

  @Test
  @DisplayName("F10-1 — scope 내 호출은 통과")
  public void delegatedTokenCanCallApiWithinScope() throws Exception {

    registerAgent("agent-scope-ok", true);

    mockMvc.perform(get("/api/sample/user")
            .header("Authorization", "Bearer "
                + delegatedToken("agent-scope-ok", List.of("USER"), List.of("sample:read"))))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("F10-1 — scope 밖 호출은 ERROR_SCOPE 403")
  public void delegatedTokenCannotCallApiOutsideScope() throws Exception {

    registerAgent("agent-scope-no", true);

    // sample:read 만 위임받았는데 /list(sample:list)를 호출한다
    mockMvc.perform(get("/api/sample/list")
            .header("Authorization", "Bearer "
                + delegatedToken("agent-scope-no", List.of("USER"), List.of("sample:read"))))
        .andExpect(status().isForbidden())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_SCOPE")));
  }

  @Test
  @DisplayName("F10 — 역할이 ADMIN이어도 scope에 admin이 없으면 거부된다")
  public void delegatedTokenDeniedWhenScopeMissingEvenIfRoleAllows() throws Exception {

    registerAgent("agent-admin-noscope", true);

    mockMvc.perform(get("/api/sample/admin")
            .header("Authorization", "Bearer " + delegatedToken(
                "agent-admin-noscope", List.of("USER", "ADMIN"), List.of("sample:read"))))
        .andExpect(status().isForbidden());
  }

  // ── F9-3 / F11 ────────────────────────────────────────────────

  @Test
  @DisplayName("F9-3 — 비활성화된 에이전트의 위임 토큰은 만료 전이라도 거부된다")
  public void inactiveAgentDelegatedTokenIsRejected() throws Exception {

    registerAgent("agent-inactive", false);

    mockMvc.perform(get("/api/sample/user")
            .header("Authorization", "Bearer "
                + delegatedToken("agent-inactive", List.of("USER"), List.of("sample:read"))))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_AGENT_INACTIVE")));
  }

  @Test
  @DisplayName("F11 — audience 가 다르면 ERROR_AUDIENCE 401")
  public void delegatedTokenWithWrongAudienceIsRejected() throws Exception {

    registerAgent("agent-aud", true);

    String token = JWTUtil.generateToken(Map.of(
        "email", "user1@aaa.com",
        "sub", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of("USER"),
        "scope", List.of("sample:read"),
        "act", "agent-aud",
        "aud", "https://someone-else.example.com"), 10);

    mockMvc.perform(get("/api/sample/user").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_AUDIENCE")));
  }
}
