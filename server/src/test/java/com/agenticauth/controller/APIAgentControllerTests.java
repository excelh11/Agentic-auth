package com.agenticauth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.agenticauth.util.JWTUtil;
import com.google.gson.Gson;

/**
 * T15 — F9(등록·발급) · F10-2(scope 상한) · 재위임 차단.
 *
 * 실제 MariaDB 접속이 필요하다. 테스트 계정(user1@aaa.com)이 미리 만들어져 있어야 한다
 * ({@code MemberRepositoryTests} 를 먼저 돌린다).
 */
@SpringBootTest
@AutoConfigureMockMvc
public class APIAgentControllerTests {

  @Autowired
  private MockMvc mockMvc;

  @Value("${aauth.jwt.audience}")
  private String audience;

  private final Gson gson = new Gson();

  /** 사용자 본인 로그인 토큰(F1) — act 가 없다. */
  private String userToken(String... roles) {
    return JWTUtil.generateToken(Map.of(
        "email", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of(roles)), 10);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> body(MvcResult result) throws Exception {
    return gson.fromJson(result.getResponse().getContentAsString(), Map.class);
  }

  /** 에이전트를 등록하고 agentId 를 돌려준다. 이름은 필수다(F9-5). */
  private String registerAgent(String token) throws Exception {

    MvcResult result = mockMvc.perform(post("/api/agent/register")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("name", "일정 봇", "description", "회의를 잡는다"))))
        .andExpect(status().isOk())
        .andReturn();

    return body(result).get("agentId").toString();
  }

  @Test
  @DisplayName("F9 — 에이전트를 등록하면 호출자가 소유자가 되고 이름이 남는다")
  public void registerRecordsOwner() throws Exception {

    MvcResult result = mockMvc.perform(post("/api/agent/register")
            .header("Authorization", "Bearer " + userToken("USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("name", "일정 봇", "description", "회의를 잡는다"))))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> json = body(result);

    assertNotNull(json.get("agentId"));
    assertEquals("user1@aaa.com", json.get("ownerEmail"));
    assertEquals("일정 봇", json.get("name"), "F9-5 — 이름이 저장돼야 한다");
    assertEquals("회의를 잡는다", json.get("description"));
  }

  @Test
  @DisplayName("F9-5 — 이름 없이 등록하면 거부된다")
  public void registerWithoutNameIsRejected() throws Exception {

    // 이름 없는 에이전트는 감사 로그에 UUID로만 남아 F13을 만족시킬 수 없다
    mockMvc.perform(post("/api/agent/register")
            .header("Authorization", "Bearer " + userToken("USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("description", "이름이 없다"))))
        .andExpect(content().string(
            org.hamcrest.Matchers.containsString("ERROR_AGENT_NAME_REQUIRED")));

    // 공백만 있는 이름도 거부한다
    mockMvc.perform(post("/api/agent/register")
            .header("Authorization", "Bearer " + userToken("USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("name", "   "))))
        .andExpect(content().string(
            org.hamcrest.Matchers.containsString("ERROR_AGENT_NAME_REQUIRED")));
  }

  @Test
  @DisplayName("F13-3 — 위임 응답에 감사 로그용 에이전트 이름이 실린다")
  public void delegationResponseCarriesAgentName() throws Exception {

    String token = userToken("USER");
    String agentId = registerAgent(token);

    MvcResult result = mockMvc.perform(post("/api/agent/delegate")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("agentId", agentId, "scope", List.of("sample:read")))))
        .andExpect(status().isOk())
        .andReturn();

    assertEquals("일정 봇", body(result).get("agentName"));
  }

  @Test
  @DisplayName("F9-1 — 발급된 위임 토큰에 sub·act·scope·aud 가 실린다")
  public void delegatedTokenCarriesSubjectAndActor() throws Exception {

    String token = userToken("USER");
    String agentId = registerAgent(token);

    MvcResult result = mockMvc.perform(post("/api/agent/delegate")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("agentId", agentId, "scope", List.of("sample:read")))))
        .andExpect(status().isOk())
        .andReturn();

    String delegated = body(result).get("accessToken").toString();

    Map<String, Object> claims = JWTUtil.validateToken(delegated);

    assertEquals("user1@aaa.com", claims.get("sub"), "위임자 — F9-1");
    assertEquals(agentId, claims.get("act"), "행위자 — F9-1");
    assertEquals(List.of("sample:read"), claims.get("scope"), "위임 범위 — F10");
    assertEquals(audience, claims.get("aud"), "대상 서버 — F11");
    assertEquals("user1@aaa.com", claims.get("email"), "F1 계약인 email 은 그대로 남는다");
  }

  @Test
  @DisplayName("F9-2 — 사용자 본인 로그인 토큰에는 act 가 없다")
  public void plainUserTokenHasNoActor() {

    Map<String, Object> claims = JWTUtil.validateToken(userToken("USER"));

    assertNull(claims.get("act"));
    assertNull(claims.get("scope"));
    assertNull(claims.get("aud"));
  }

  @Test
  @DisplayName("F10-2 — 사용자가 갖지 않은 권한은 위임할 수 없다")
  public void scopeExceedingRoleIsRejected() throws Exception {

    String token = userToken("USER"); // USER 는 sample:admin 을 위임할 수 없다
    String agentId = registerAgent(token);

    mockMvc.perform(post("/api/agent/delegate")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("agentId", agentId, "scope", List.of("sample:admin")))))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_SCOPE_EXCEEDS_ROLE")));
  }

  @Test
  @DisplayName("F10-2 — ADMIN 은 sample:admin 을 위임할 수 있다")
  public void adminCanDelegateAdminScope() throws Exception {

    String token = userToken("USER", "ADMIN");
    String agentId = registerAgent(token);

    mockMvc.perform(post("/api/agent/delegate")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("agentId", agentId, "scope", List.of("sample:admin")))))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("빈 scope 는 거부된다 — '빈 값은 무제한'으로 오해될 여지를 없앤다")
  public void emptyScopeIsRejected() throws Exception {

    String token = userToken("USER");
    String agentId = registerAgent(token);

    mockMvc.perform(post("/api/agent/delegate")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("agentId", agentId, "scope", List.of()))))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_SCOPE_EXCEEDS_ROLE")));
  }

  @Test
  @DisplayName("재위임 차단 — 위임 토큰으로는 새 위임을 발급할 수 없다")
  public void delegatedTokenCannotRedelegate() throws Exception {

    String token = userToken("USER");
    String agentId = registerAgent(token);

    MvcResult issued = mockMvc.perform(post("/api/agent/delegate")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("agentId", agentId, "scope", List.of("sample:read")))))
        .andExpect(status().isOk())
        .andReturn();

    String delegated = body(issued).get("accessToken").toString();

    // 에이전트가 자기 자신에게 새 위임을 발급하려는 시도
    mockMvc.perform(post("/api/agent/delegate")
            .header("Authorization", "Bearer " + delegated)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("agentId", agentId, "scope", List.of("sample:list")))))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_SCOPE")));

    // 에이전트가 새 에이전트를 등록하려는 시도도 막힌다
    mockMvc.perform(post("/api/agent/register")
            .header("Authorization", "Bearer " + delegated))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_SCOPE")));
  }
}
