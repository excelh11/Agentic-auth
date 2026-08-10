package com.agenticauth.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.agenticauth.domain.Agent;
import com.agenticauth.repository.AgentRepository;
import com.agenticauth.util.CustomJWTException;

/**
 * DelegationValidator는 AgentRepository만 있으면 되므로 Mockito로 모킹해 DB 없이 검증한다.
 *
 * <pre>
 *   .\gradlew.bat test --tests "com.agenticauth.security.DelegationValidatorTests"
 * </pre>
 */
public class DelegationValidatorTests {

  private static final String AUDIENCE = "agentic-auth-server";

  private Map<String, Object> claims(String aud, String act, String sub) {
    return Map.of(
        "sub", sub,
        "email", sub,
        "act", act,
        "aud", aud,
        "scope", java.util.List.of("sample:read"));
  }

  @Test
  public void audienceMismatchThrowsErrorAudience() {

    AgentRepository agentRepository = mock(AgentRepository.class);
    DelegationValidator validator = new DelegationValidator(agentRepository, AUDIENCE);

    Map<String, Object> claims = claims("other-server", "agent-1", "user1@aaa.com");

    CustomJWTException ex = assertThrows(CustomJWTException.class,
        () -> validator.validate(claims, "/api/sample/user"));

    assertEquals("ERROR_AUDIENCE", ex.getMessage());
    verifyNoInteractions(agentRepository);
  }

  @Test
  public void agentNotFoundThrowsErrorAgentInactive() {

    AgentRepository agentRepository = mock(AgentRepository.class);
    when(agentRepository.findById("agent-1")).thenReturn(Optional.empty());

    DelegationValidator validator = new DelegationValidator(agentRepository, AUDIENCE);

    Map<String, Object> claims = claims(AUDIENCE, "agent-1", "user1@aaa.com");

    CustomJWTException ex = assertThrows(CustomJWTException.class,
        () -> validator.validate(claims, "/api/sample/user"));

    assertEquals("ERROR_AGENT_INACTIVE", ex.getMessage());
  }

  @Test
  public void agentInactiveThrowsErrorAgentInactive() {

    AgentRepository agentRepository = mock(AgentRepository.class);
    Agent inactiveAgent = Agent.builder()
        .agentId("agent-1")
        .ownerEmail("user1@aaa.com")
        .active(false)
        .registeredAt(LocalDateTime.now())
        .build();
    when(agentRepository.findById("agent-1")).thenReturn(Optional.of(inactiveAgent));

    DelegationValidator validator = new DelegationValidator(agentRepository, AUDIENCE);

    Map<String, Object> claims = claims(AUDIENCE, "agent-1", "user1@aaa.com");

    CustomJWTException ex = assertThrows(CustomJWTException.class,
        () -> validator.validate(claims, "/api/sample/user"));

    assertEquals("ERROR_AGENT_INACTIVE", ex.getMessage());
  }

  @Test
  public void ownerMismatchThrowsErrorAgentInactive() {

    AgentRepository agentRepository = mock(AgentRepository.class);
    Agent agent = Agent.builder()
        .agentId("agent-1")
        .ownerEmail("owner-other@aaa.com")
        .active(true)
        .registeredAt(LocalDateTime.now())
        .build();
    when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agent));

    DelegationValidator validator = new DelegationValidator(agentRepository, AUDIENCE);

    Map<String, Object> claims = claims(AUDIENCE, "agent-1", "user1@aaa.com");

    CustomJWTException ex = assertThrows(CustomJWTException.class,
        () -> validator.validate(claims, "/api/sample/user"));

    assertEquals("ERROR_AGENT_INACTIVE", ex.getMessage());
  }

  @Test
  public void validDelegationPassesWithoutException() {

    AgentRepository agentRepository = mock(AgentRepository.class);
    Agent agent = Agent.builder()
        .agentId("agent-1")
        .ownerEmail("user1@aaa.com")
        .active(true)
        .registeredAt(LocalDateTime.now())
        .build();
    when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agent));

    DelegationValidator validator = new DelegationValidator(agentRepository, AUDIENCE);

    Map<String, Object> claims = claims(AUDIENCE, "agent-1", "user1@aaa.com");

    assertDoesNotThrow(() -> validator.validate(claims, "/api/sample/user"));
  }
}
