package com.agenticauth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * T17 — <b>회수 우회 통로가 실제로 막혔는지.</b>
 *
 * <p>{@code /api/member/**} 는 {@code JWTCheckFilter} 의 제외 경로다(F3). 그래서 F9-3(개별 회수)을
 * 필터에서만 검사하면, 비활성화된 에이전트가 {@code /api/member/refresh} 로 새 accessToken 을
 * 받아 부활한다. {@code APIRefreshController} 가 직접 검증해야만 막힌다.
 *
 * <p>이 테스트가 그 유일한 증거다. 실제 MariaDB 접속이 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class APIRefreshControllerDelegationTests {

  @Autowired
  private MockMvc mockMvc;

  @Value("${aauth.jwt.audience}")
  private String audience;

  private final Gson gson = new Gson();

  private String userToken() {
    return JWTUtil.generateToken(Map.of(
        "email", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of("USER")), 10);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> body(MvcResult result) throws Exception {
    return gson.fromJson(result.getResponse().getContentAsString(), Map.class);
  }

  @Test
  @DisplayName("F9-3 — 비활성화한 에이전트는 refresh 로 부활하지 못한다")
  public void deactivatedAgentCannotReviveViaRefresh() throws Exception {

    String token = userToken();

    // 1) 등록 — 이름은 필수다(F9-5)
    String agentId = body(mockMvc.perform(post("/api/agent/register")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("name", "일정 봇"))))
        .andExpect(status().isOk()).andReturn()).get("agentId").toString();

    // 2) 위임 토큰 발급
    Map<String, Object> issued = body(mockMvc.perform(post("/api/agent/delegate")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(gson.toJson(Map.of("agentId", agentId, "scope", List.of("sample:read")))))
        .andExpect(status().isOk()).andReturn());

    String delegatedAccess = issued.get("accessToken").toString();
    String delegatedRefresh = issued.get("refreshToken").toString();

    // 3) 아직은 통과한다
    mockMvc.perform(get("/api/sample/user")
            .header("Authorization", "Bearer " + delegatedAccess))
        .andExpect(status().isOk());

    // 4) 회수 — 사용자 본인 토큰으로 비활성화한다
    mockMvc.perform(post("/api/agent/" + agentId + "/deactivate")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    // 5) 보호 API 는 필터가 막는다 (F9-3)
    mockMvc.perform(get("/api/sample/user")
            .header("Authorization", "Bearer " + delegatedAccess))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_AGENT_INACTIVE")));

    // 6) ★ 핵심 — refresh 경로도 막혀야 한다.
    //    여기가 뚫려 있으면 에이전트가 새 accessToken 을 받아 계속 살아난다.
    mockMvc.perform(get("/api/member/refresh")
            .header("Authorization", "Bearer " + delegatedAccess)
            .param("refreshToken", delegatedRefresh))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ERROR_AGENT_INACTIVE")));
  }

  @Test
  @DisplayName("F5 회귀 — 일반 사용자 토큰의 refresh 동작은 그대로다")
  public void plainUserTokenRefreshIsUnaffected() throws Exception {

    String access = userToken();
    String refresh = JWTUtil.generateToken(Map.of(
        "email", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of("USER")), 60 * 24);

    // accessToken 이 아직 유효하므로 기존 토큰 쌍이 그대로 돌아온다 (F5 판정 3단계)
    mockMvc.perform(get("/api/member/refresh")
            .header("Authorization", "Bearer " + access)
            .param("refreshToken", refresh))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("accessToken")));
  }
}
