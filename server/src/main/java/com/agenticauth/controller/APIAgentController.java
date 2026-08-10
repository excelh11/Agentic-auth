package com.agenticauth.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticauth.domain.Agent;
import com.agenticauth.dto.MemberDTO;
import com.agenticauth.repository.AgentRepository;
import com.agenticauth.security.ScopeCatalog;
import com.agenticauth.util.CustomJWTException;
import com.agenticauth.util.JWTUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * F9~F13 — 에이전트 등록과 위임 토큰 발급.
 *
 * <p><b>로그인({@code /api/member/login})과 별도 엔드포인트다.</b> F1 의 응답 계약을
 * 건드리지 않기 위해서다. 여기서 나오는 토큰만 {@code act}/{@code scope}/{@code sub}/{@code aud} 를 싣는다.
 *
 * <p>이 경로들은 {@code JWTCheckFilter} 를 거친다({@code /api/member/} 로 시작하지 않는다).
 * 따라서 호출자는 이미 인증된 사용자이며, {@code @AuthenticationPrincipal} 로 받는다.
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Log4j2
public class APIAgentController {

  private final AgentRepository agentRepository;

  @Value("${aauth.jwt.audience}")
  private String serverAudience;

  /**
   * 에이전트 등록. 호출한 사용자가 소유자가 된다.
   *
   * <p>요청 본문: {@code {"name": "일정 봇", "description": "회의를 잡는다"}}
   *
   * <p><b>이름은 필수다(F9-5).</b> 이름 없는 에이전트는 감사 로그(F13)에 UUID로만 남아
   * "무엇이 실행했는지"를 알 수 없게 만든다.
   *
   * @return {@code agentId} — 이후 위임 토큰의 {@code act} 에 실린다
   */
  @PostMapping("/register")
  public Map<String, Object> register(@AuthenticationPrincipal MemberDTO memberDTO,
                                       @RequestBody(required = false) Map<String, Object> body) {

    rejectIfDelegated(memberDTO);

    String name = body != null && body.get("name") != null ? body.get("name").toString().trim() : "";
    String description = body != null && body.get("description") != null
        ? body.get("description").toString().trim() : "";

    if (name.isEmpty()) {
      log.warn("에이전트 등록 거부 — 이름이 없다");
      throw new CustomJWTException("ERROR_AGENT_NAME_REQUIRED");
    }

    String agentId = "agent-" + UUID.randomUUID();

    agentRepository.save(Agent.builder()
        .agentId(agentId)
        .name(name)
        .description(description)
        .ownerEmail(memberDTO.getEmail())
        .active(true)
        .registeredAt(LocalDateTime.now())
        .build());

    log.info("agent registered: " + agentId + " (" + name + ") owner=" + memberDTO.getEmail());

    return Map.of("agentId", agentId,
                  "name", name,
                  "description", description,
                  "ownerEmail", memberDTO.getEmail(),
                  "active", true);
  }

  /**
   * 위임 토큰 발급 — F9·F10·F11.
   *
   * <p>요청 본문: {@code {"agentId": "...", "scope": ["sample:read", ...]}}
   *
   * <p>발급 전에 세 가지를 확인한다.
   * <ul>
   *   <li>호출자가 이미 위임 토큰이면 거부 — 재위임(체인)을 막는다</li>
   *   <li>에이전트가 등록돼 있고 활성이며 호출자 소유인지 — F9-3</li>
   *   <li>요청 scope 가 호출자 역할이 위임할 수 있는 범위의 부분집합인지 — F10-2</li>
   * </ul>
   */
  @PostMapping("/delegate")
  public Map<String, Object> delegate(@AuthenticationPrincipal MemberDTO memberDTO,
                                       @RequestBody Map<String, Object> body) {

    rejectIfDelegated(memberDTO);

    String agentId = body.get("agentId") != null ? body.get("agentId").toString() : null;

    @SuppressWarnings("unchecked")
    List<String> requestedScope = (List<String>) body.get("scope");

    if (agentId == null) {
      throw new CustomJWTException("ERROR_AGENT_INACTIVE");
    }

    Agent agent = agentRepository.findById(agentId).orElse(null);

    if (agent == null || !agent.isActive()
        || !memberDTO.getEmail().equals(agent.getOwnerEmail())) {
      log.warn("위임 거부 — 미등록/비활성/소유자 불일치: " + agentId);
      throw new CustomJWTException("ERROR_AGENT_INACTIVE");
    }

    // F10-2 — 위임은 권한을 넓힐 수 없다.
    if (!ScopeCatalog.isSubsetOfRoles(requestedScope, memberDTO.getRoleNames())) {
      log.warn("위임 거부 — scope 가 역할을 초과: requested=" + requestedScope
          + ", allowed=" + ScopeCatalog.allowedFor(memberDTO.getRoleNames()));
      throw new CustomJWTException("ERROR_SCOPE_EXCEEDS_ROLE");
    }

    // 위임 토큰의 claims. F1 의 4-key 구조와 달리 sub/scope/act/aud 가 더 실린다.
    // 읽는 쪽은 JWTCheckFilter 다 — 여기를 바꾸면 거기도 같이 고쳐야 한다.
    Map<String, Object> claims = Map.of(
        "email", memberDTO.getEmail(),
        "sub", memberDTO.getEmail(),
        "nickname", memberDTO.getNickname(),
        "social", memberDTO.isSocial(),
        "roleNames", memberDTO.getRoleNames(),
        "scope", requestedScope,
        "act", agentId,
        "aud", serverAudience);

    log.info("delegation issued: sub=" + memberDTO.getEmail() + " act=" + agentId
        + " scope=" + requestedScope);

    return Map.of("accessToken", JWTUtil.generateToken(claims, 10),
                  "refreshToken", JWTUtil.generateToken(claims, 60 * 24),
                  "sub", memberDTO.getEmail(),
                  "act", agentId,
                  "agentName", agent.getName(),
                  "scope", requestedScope,
                  "aud", serverAudience);
  }

  /**
   * 에이전트 비활성화 — F9-3/F9-4(개별 회수).
   *
   * <p>사용자 본인은 로그아웃되지 않는다. 이 에이전트가 행위자인 위임 토큰만,
   * 만료 전이라도 다음 요청부터 거부된다.
   */
  @PostMapping("/{agentId}/deactivate")
  public Map<String, Object> deactivate(@AuthenticationPrincipal MemberDTO memberDTO,
                                         @PathVariable("agentId") String agentId) {

    rejectIfDelegated(memberDTO);

    Agent agent = agentRepository.findById(agentId).orElse(null);

    if (agent == null || !memberDTO.getEmail().equals(agent.getOwnerEmail())) {
      throw new CustomJWTException("ERROR_AGENT_INACTIVE");
    }

    agent.deactivate();
    agentRepository.save(agent);

    log.info("agent deactivated: " + agentId + " (" + agent.getName() + ")");

    return Map.of("agentId", agentId,
                  "name", agent.getName() != null ? agent.getName() : "",
                  "active", false);
  }

  /**
   * 위임 토큰으로는 이 컨트롤러를 쓸 수 없다.
   *
   * <p>막지 않으면 에이전트가 스스로에게 새 위임을 발급하거나 다른 에이전트를 등록해
   * 자신의 권한을 넓힐 수 있다. 위임은 사람이 시작해야 한다.
   */
  private void rejectIfDelegated(MemberDTO memberDTO) {

    if (memberDTO != null && memberDTO.getAct() != null) {
      log.warn("재위임 시도 거부: act=" + memberDTO.getAct());
      throw new CustomJWTException("ERROR_SCOPE");
    }
  }
}
