package com.agenticauth.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * JWTUtil은 static 유틸이라 스프링 컨텍스트도 DB도 필요 없다.
 * 가장 빠르고 안정적인 안전망이므로 Security/JWT를 건드리면 이걸 먼저 돌린다.
 *
 * <pre>
 *   .\gradlew.bat test --tests "com.agenticauth.util.JWTUtilTests"
 * </pre>
 */
public class JWTUtilTests {

  private Map<String, Object> sampleClaims() {
    return Map.of(
        "email", "user1@aaa.com",
        "nickname", "USER1",
        "social", false,
        "roleNames", List.of("USER"));
  }

  @Test
  public void generateTokenThenValidateReturnsSameClaims() {

    String token = JWTUtil.generateToken(sampleClaims(), 10);

    Map<String, Object> result = JWTUtil.validateToken(token);

    assertEquals("user1@aaa.com", result.get("email"));
    assertEquals("USER1", result.get("nickname"));
    assertEquals(List.of("USER"), result.get("roleNames"));
  }

  @Test
  public void expiredTokenThrowsExpiredException() {

    // Thread.sleep 대신 음수 min 으로 즉시 만료시킨다
    String token = JWTUtil.generateToken(sampleClaims(), -1);

    CustomJWTException ex = assertThrows(CustomJWTException.class,
        () -> JWTUtil.validateToken(token));

    assertEquals("Expired", ex.getMessage());
  }

  @Test
  public void tamperedTokenThrowsException() {

    String token = JWTUtil.generateToken(sampleClaims(), 10) + "tampered";

    assertThrows(CustomJWTException.class, () -> JWTUtil.validateToken(token));
  }

  @Test
  public void nonJwtStringThrowsMalFormed() {

    CustomJWTException ex = assertThrows(CustomJWTException.class,
        () -> JWTUtil.validateToken("this-is-not-a-jwt"));

    assertEquals("MalFormed", ex.getMessage());
  }
}
