package com.agenticauth.security.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.agenticauth.dto.MemberDTO;

/**
 * T10 — act 없는 기존 케이스는 반드시 ERROR_ACCESSDENIED로 남아 있어야 한다 (회귀 방지).
 * act 있는 위임 토큰의 scope 부족은 신규 코드 ERROR_SCOPE로 구분된다.
 */
public class CustomAccessDeniedHandlerTests {

  private final CustomAccessDeniedHandler handler = new CustomAccessDeniedHandler();

  @AfterEach
  public void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void noActReturnsErrorAccessDenied403() throws Exception {

    MemberDTO memberDTO = new MemberDTO("user1@aaa.com", "", "USER1", false, List.of("USER"));
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(memberDTO, null, memberDTO.getAuthorities()));

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(request, response, new AccessDeniedException("no admin role"));

    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("ERROR_ACCESSDENIED"));
    assertFalse(response.getContentAsString().contains("ERROR_SCOPE"));
  }

  @Test
  public void actPresentReturnsErrorScope403() throws Exception {

    MemberDTO memberDTO = new MemberDTO(
        "user1@aaa.com", "", "USER1", false, List.of("USER"),
        "agent-1", List.of("sample:read"));
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(memberDTO, null, memberDTO.getAuthorities()));

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(request, response, new AccessDeniedException("scope 밖 호출"));

    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("ERROR_SCOPE"));
  }

  @Test
  public void noAuthenticationReturnsErrorAccessDenied() throws Exception {

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(request, response, new AccessDeniedException("no auth"));

    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("ERROR_ACCESSDENIED"));
  }
}
