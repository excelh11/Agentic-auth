package com.agenticauth.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.agenticauth.dto.MemberDTO;

/**
 * F10 fail-open 고정 테스트.
 *
 * <pre>
 *   .\gradlew.bat test --tests "com.agenticauth.security.ScopeAuthorizerTests"
 * </pre>
 */
public class ScopeAuthorizerTests {

  private final ScopeAuthorizer scopeAuthorizer = new ScopeAuthorizer();

  @AfterEach
  public void clear() {
    SecurityContextHolder.clearContext();
  }

  private void authenticateAs(MemberDTO memberDTO) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(memberDTO, null, memberDTO.getAuthorities()));
  }

  @Test
  public void noActAlwaysReturnsTrueEvenWithoutScopeAuthority() {

    MemberDTO memberDTO = new MemberDTO("user1@aaa.com", "", "USER1", false, List.of("USER"));
    authenticateAs(memberDTO);

    assertTrue(scopeAuthorizer.has("sample:admin"),
        "act가 없는 본인 호출은 scope 제약 없이 항상 통과해야 한다 (fail-open)");
  }

  @Test
  public void actWithoutMatchingScopeReturnsFalse() {

    MemberDTO memberDTO = new MemberDTO(
        "user1@aaa.com", "", "USER1", false, List.of("USER"),
        "agent-1", List.of("sample:read"));
    authenticateAs(memberDTO);

    assertTrue(scopeAuthorizer.has("sample:read"));
    assertFalse(scopeAuthorizer.has("sample:admin"), "부여받지 않은 scope는 거부해야 한다");
  }

  @Test
  public void noAuthenticationReturnsFalse() {

    assertFalse(scopeAuthorizer.has("sample:read"));
  }
}
