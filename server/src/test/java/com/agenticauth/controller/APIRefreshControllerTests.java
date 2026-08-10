package com.agenticauth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.agenticauth.util.JWTUtil;
import com.google.gson.Gson;

/**
 * K8 — 갱신이 <b>망가진 accessToken 을 그대로 되돌려주지 않는지</b> 확인한다.
 *
 * <p>예전 `checkExpiredToken()` 은 `"Expired"` 만 만료로 봤다. 그래서 형식이 깨지거나 위조된
 * 토큰은 "아직 유효"로 판정돼 <b>200 과 함께 그 망가진 토큰이 그대로 돌아왔다.</b>
 * 클라이언트는 "갱신됐다"고 믿고 같은 토큰으로 계속 실패한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class APIRefreshControllerTests {

  @Autowired
  private MockMvc mockMvc;

  private final Gson gson = new Gson();

  private Map<String, Object> claims() {
    return Map.of(
        "email", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of("USER"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> body(MvcResult result) throws Exception {
    return gson.fromJson(result.getResponse().getContentAsString(), Map.class);
  }

  @Test
  @DisplayName("F5 판정 3 — accessToken 이 유효하면 기존 토큰 쌍을 그대로 돌려준다")
  public void validAccessTokenReturnsSamePair() throws Exception {

    String access = JWTUtil.generateToken(claims(), 10);
    String refresh = JWTUtil.generateToken(claims(), 60 * 24);

    MvcResult result = mockMvc.perform(get("/api/member/refresh")
            .header("Authorization", "Bearer " + access)
            .param("refreshToken", refresh))
        .andExpect(status().isOk())
        .andReturn();

    org.junit.jupiter.api.Assertions.assertEquals(
        access, body(result).get("accessToken"), "유효한 토큰은 재발급하지 않는다");
  }

  @Test
  @DisplayName("F5 판정 4 — 만료된 accessToken 은 새로 발급된다")
  public void expiredAccessTokenIsReissued() throws Exception {

    String expired = JWTUtil.generateToken(claims(), -1); // 즉시 만료
    String refresh = JWTUtil.generateToken(claims(), 60 * 24);

    MvcResult result = mockMvc.perform(get("/api/member/refresh")
            .header("Authorization", "Bearer " + expired)
            .param("refreshToken", refresh))
        .andExpect(status().isOk())
        .andReturn();

    org.junit.jupiter.api.Assertions.assertNotEquals(
        expired, body(result).get("accessToken"), "만료된 토큰이 그대로 돌아오면 안 된다");
  }

  @Test
  @DisplayName("K8 — 위조된 accessToken 을 '아직 유효'로 보고 그대로 되돌려주지 않는다")
  public void tamperedAccessTokenIsNotEchoedBack() throws Exception {

    String tampered = JWTUtil.generateToken(claims(), 10) + "tampered";
    String refresh = JWTUtil.generateToken(claims(), 60 * 24);

    MvcResult result = mockMvc.perform(get("/api/member/refresh")
            .header("Authorization", "Bearer " + tampered)
            .param("refreshToken", refresh))
        .andExpect(status().isOk())
        .andReturn();

    Object reissued = body(result).get("accessToken");

    org.junit.jupiter.api.Assertions.assertNotEquals(
        tampered, reissued, "망가진 토큰을 그대로 돌려주면 클라이언트는 계속 실패한다 — K8");
    org.junit.jupiter.api.Assertions.assertNotNull(reissued);

    // 돌려받은 토큰이 실제로 쓸 수 있어야 한다
    mockMvc.perform(get("/api/sample/user")
            .header("Authorization", "Bearer " + reissued))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("K8 — refreshToken 까지 망가졌으면 재발급하지 않고 거부한다")
  public void brokenRefreshTokenIsRejected() throws Exception {

    String tampered = JWTUtil.generateToken(claims(), 10) + "tampered";

    mockMvc.perform(get("/api/member/refresh")
            .header("Authorization", "Bearer " + tampered)
            .param("refreshToken", "this-is-not-a-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("MalFormed")));
  }
}
