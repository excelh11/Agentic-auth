package com.agenticauth.security;

import java.util.Collection;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.agenticauth.dto.MemberDTO;

/**
 * F10 — 위임 토큰이 발급 시점에 지정된 범위 밖의 API를 호출하지 못하게 한다.
 *
 * {@code @PreAuthorize("@scopeAuth.has('sample:read')")} 형태로 쓴다.
 *
 * <b>fail-open이 의도된 설계다.</b> act가 없는 요청(사용자 본인 호출, F9-2)은
 * scope 제약을 아예 받지 않는다 — scope는 "위임"에만 적용되는 추가 제약이지,
 * 사용자 본인의 roleNames/@PreAuthorize(F4) 권한 체계를 대체하지 않는다.
 */
@Component("scopeAuth")
public class ScopeAuthorizer {

  public boolean has(String scopeName) {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null || !(authentication.getPrincipal() instanceof MemberDTO)) {
      return false;
    }

    MemberDTO memberDTO = (MemberDTO) authentication.getPrincipal();

    if (memberDTO.getAct() == null) {
      // 위임 토큰이 아니다 — scope 제약과 무관하게 항상 통과시킨다 (fail-open).
      return true;
    }

    String target = "SCOPE_" + scopeName;
    Collection<? extends GrantedAuthority> authorities = memberDTO.getAuthorities();

    return authorities.stream().anyMatch(a -> target.equals(a.getAuthority()));
  }
}
