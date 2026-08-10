package com.agenticauth.controller;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticauth.dto.MemberDTO;

import lombok.extern.log4j.Log4j2;

/**
 * 인증/인가가 실제로 동작하는지 확인하기 위한 샘플 API.
 * 비즈니스 로직은 없다 — JWT 필터와 @PreAuthorize를 통과했는지만 보여준다.
 *
 * <pre>
 *  GET /api/sample/public   토큰 없이 통과 (JWTCheckFilter 제외 경로)
 *  GET /api/sample/user     JWT 필요, 권한 제한 없음          → F3 검증
 *  GET /api/sample/admin    ADMIN 권한 필요                   → F4 검증
 * </pre>
 */
@RestController
@RequestMapping("/api/sample")
@Log4j2
public class SampleController {

  /** 토큰 없이 접근 가능. JWTCheckFilter의 shouldNotFilter 제외 경로다. */
  @GetMapping("/public")
  public Map<String, Object> publicApi() {

    log.info("sample public...");

    return Map.of("result", "PUBLIC_OK",
                  "note", "토큰 없이 통과하는 경로입니다.");
  }

  /**
   * JWT만 있으면 접근 가능. 인증 주체가 제대로 복원됐는지 확인한다.
   *
   * {@code @scopeAuth.has(...)} 는 <b>위임 토큰에만</b> 적용되는 추가 제약이다(F10).
   * act 가 없는 사용자 본인 토큰은 항상 통과하므로 이 애노테이션이 붙어도 기존 동작은 그대로다.
   */
  @PreAuthorize("@scopeAuth.has('sample:read')")
  @GetMapping("/user")
  public Map<String, Object> userApi(@AuthenticationPrincipal MemberDTO memberDTO) {

    log.info("sample user... " + memberDTO);

    return Map.of("result", "USER_OK",
                  "email", memberDTO.getEmail(),
                  "nickname", memberDTO.getNickname(),
                  "roleNames", memberDTO.getRoleNames(),
                  "authorities", memberDTO.getAuthorities().toString());
  }

  /**
   * ADMIN 권한 필요. USER 계정으로 호출하면 403 ERROR_ACCESSDENIED가 나와야 한다.
   *
   * 위임 토큰이라면 <b>두 조건을 모두</b> 만족해야 한다 —
   * 위임자가 실제로 ADMIN인가(hasRole), 그리고 이 위임에 admin scope가 부여됐는가(F10).
   * scope 는 역할을 넓힐 수 없고 좁히기만 한다.
   */
  @PreAuthorize("hasRole('ADMIN') and @scopeAuth.has('sample:admin')")
  @GetMapping("/admin")
  public Map<String, Object> adminApi(@AuthenticationPrincipal MemberDTO memberDTO) {

    log.info("sample admin... " + memberDTO);

    return Map.of("result", "ADMIN_OK",
                  "email", memberDTO.getEmail(),
                  "roleNames", memberDTO.getRoleNames());
  }

  /** 목록 형태 응답이 필요할 때를 위한 더미. scope 제약은 위임 토큰에만 걸린다(F10). */
  @PreAuthorize("@scopeAuth.has('sample:list')")
  @GetMapping("/list")
  public Map<String, Object> listApi() {

    return Map.of("result", "LIST_OK",
                  "items", List.of("item-1", "item-2", "item-3"));
  }
}
