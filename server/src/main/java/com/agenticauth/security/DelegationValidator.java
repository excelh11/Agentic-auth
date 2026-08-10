package com.agenticauth.security;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.agenticauth.domain.Agent;
import com.agenticauth.repository.AgentRepository;
import com.agenticauth.util.CustomJWTException;

import lombok.extern.log4j.Log4j2;

/**
 * 위임 토큰(act 클레임이 있는 토큰) 검증 — F9-3(개별 회수), F11(audience).
 *
 * 서명·만료 검증(JWTUtil.validateToken)만으로는 부족하다. act로 지목된 에이전트가
 * 지금 이 순간에도 유효한지(등록돼 있고 활성 상태고 소유자가 일치하는지)는 매 요청마다
 * DB를 조회해야만 알 수 있다 — 그게 F9-3이 "회수는 즉시 반영"이어야 하는 이유다.
 *
 * Filter(JWTCheckFilter)나 Bean이 아닌 곳(APIRefreshController)에서 재사용할 수 있도록
 * Filter와 분리된 평범한 @Component로 둔다. JWTCheckFilter 자체는 Bean으로 등록하지 않는다 —
 * Filter를 Bean으로 노출하면 서블릿 컨테이너에 이중 등록되기 때문이다.
 */
@Component
@Log4j2
public class DelegationValidator {

  private static final org.apache.logging.log4j.Logger auditLog =
      org.apache.logging.log4j.LogManager.getLogger("com.agenticauth.audit");

  private final AgentRepository agentRepository;
  private final String serverAudience;

  public DelegationValidator(AgentRepository agentRepository,
                              @Value("${aauth.jwt.audience}") String serverAudience) {
    this.agentRepository = agentRepository;
    this.serverAudience = serverAudience;
  }

  /**
   * @param claims  JWTUtil.validateToken()이 돌려준 claims. act가 있는 위임 토큰이어야 한다.
   * @param apiPath 감사 로그(F13)에 남길 호출 API 경로
   * @throws CustomJWTException aud 불일치("ERROR_AUDIENCE") 또는
   *         에이전트 미등록/비활성/소유자 불일치("ERROR_AGENT_INACTIVE")
   */
  public void validate(Map<String, Object> claims, String apiPath) {

    Object audClaim = claims.get("aud");

    if (audClaim == null || !serverAudience.equals(audClaim.toString())) {
      log.warn("위임 토큰 audience 불일치: " + audClaim);
      throw new CustomJWTException("ERROR_AUDIENCE");
    }

    Object actClaim = claims.get("act");

    if (actClaim == null) {
      log.warn("위임 토큰에 act 클레임이 없다");
      throw new CustomJWTException("ERROR_AGENT_INACTIVE");
    }

    String agentId = actClaim.toString();
    String delegator = subject(claims);

    Agent agent = agentRepository.findById(agentId).orElse(null);

    if (agent == null || !agent.isActive()) {
      log.warn("에이전트 미등록/비활성: " + agentId);
      throw new CustomJWTException("ERROR_AGENT_INACTIVE");
    }

    if (agent.getOwnerEmail() == null || !agent.getOwnerEmail().equals(delegator)) {
      log.warn("에이전트 소유자 불일치: agent=" + agentId + ", claim sub=" + delegator
          + ", owner=" + agent.getOwnerEmail());
      throw new CustomJWTException("ERROR_AGENT_INACTIVE");
    }

    // F13-1/F13-2 — 위임자와 행위자를 짝으로, 호출 API와 함께 감사 로그에 남긴다.
    // 별도 로거("com.agenticauth.audit")로 남겨 일반 로그와 구분한다 (logback-spring.xml).
    //
    // F13-3 — 행위자는 식별자와 **이름**을 함께 남긴다.
    // UUID만 남기면 로그를 나중에 봐도 어떤 에이전트였는지 알 수 없어 F13의 목적이 달성되지 않는다.
    auditLog.info("delegation-call | delegator={} | actor={} ({}) | api={}",
        delegator, agent.getName(), agentId, apiPath);
  }

  private String subject(Map<String, Object> claims) {
    Object sub = claims.get("sub");
    if (sub != null) {
      return sub.toString();
    }
    Object email = claims.get("email");
    return email != null ? email.toString() : null;
  }
}
