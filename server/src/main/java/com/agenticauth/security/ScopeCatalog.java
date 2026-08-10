package com.agenticauth.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F10-2 — 역할이 위임할 수 있는 scope 의 상한.
 *
 * <p>위임은 <b>권한을 넓힐 수 없고 좁히기만 한다.</b> 사용자가 갖지 않은 권한은
 * 어떤 에이전트에게도 위임할 수 없다. 그 판정의 단일 출처가 이 표다.
 *
 * <p>scope 어휘는 {@code roleNames}(USER/MANAGER/ADMIN)와 <b>다른 어휘</b>다 —
 * 역할보다 좁은 위임("목록만 읽어줘")이 가능해야 F10 이 의미를 갖기 때문이다.
 *
 * <p>코드 상수로 두는 이유: scope 문자열이 검증 전용 컨트롤러({@code SampleController})의
 * 엔드포인트에 하드 바인딩돼 있어 설정으로 뺄 실익이 없다. scope 대상 컨트롤러가
 * 늘어나면 그때 외부화를 재검토한다.
 */
public final class ScopeCatalog {

  public static final String SAMPLE_READ = "sample:read";
  public static final String SAMPLE_LIST = "sample:list";
  public static final String SAMPLE_ADMIN = "sample:admin";

  private static final Map<String, Set<String>> SCOPE_BY_ROLE = Map.of(
      "USER", Set.of(SAMPLE_READ, SAMPLE_LIST),
      "MANAGER", Set.of(SAMPLE_READ, SAMPLE_LIST),
      "ADMIN", Set.of(SAMPLE_READ, SAMPLE_LIST, SAMPLE_ADMIN));

  private ScopeCatalog() {
  }

  /** 이 역할들을 가진 사용자가 위임할 수 있는 scope 전체. 모르는 역할은 아무것도 주지 않는다. */
  public static Set<String> allowedFor(List<String> roleNames) {

    Set<String> allowed = new LinkedHashSet<>();

    if (roleNames == null) {
      return allowed;
    }

    for (String role : roleNames) {
      allowed.addAll(SCOPE_BY_ROLE.getOrDefault(role, Set.of()));
    }

    return allowed;
  }

  /**
   * 요청한 scope 가 이 역할들이 위임할 수 있는 범위 안에 있는가 — F10-2.
   *
   * <p>빈 요청은 거부한다. scope 가 비면 아무것도 못 하는 토큰이라 발급할 이유가 없고,
   * "빈 scope 는 무제한" 으로 잘못 해석될 여지를 없애기 위해서다.
   */
  public static boolean isSubsetOfRoles(List<String> requestedScope, List<String> roleNames) {

    if (requestedScope == null || requestedScope.isEmpty()) {
      return false;
    }

    return allowedFor(roleNames).containsAll(requestedScope);
  }
}
