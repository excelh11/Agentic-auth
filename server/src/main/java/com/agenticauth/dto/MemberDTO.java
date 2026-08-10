package com.agenticauth.dto;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class MemberDTO extends User {

  private String email;

  private String pw;

  private String nickname;

  private boolean social;

  private List<String> roleNames = new ArrayList<>();

  /** 위임 토큰의 행위자(에이전트) 식별자 — F9-1. 사용자 본인 로그인 토큰(F1)에는 없다(null) — F9-2. */
  private String act;

  /** 위임 토큰이 수행할 수 있는 동작 범위 — F10. act가 없으면 의미 없는 빈 리스트다. */
  private List<String> scope = new ArrayList<>();

  public MemberDTO(String email, String pw, String nickname, boolean social, List<String> roleNames) {
    super(
      email,
      pw,
      roleNames.stream().map(str -> new SimpleGrantedAuthority("ROLE_"+str)).collect(Collectors.toList()));

    this.email = email;
    this.pw = pw;
    this.nickname = nickname;
    this.social = social;
    this.roleNames = roleNames;
  }

  /**
   * 위임 토큰(act 클레임이 있는 토큰)의 인증 주체를 복원할 때 쓰는 생성자 — F9~F13.
   * 권한은 ROLE_(roleNames)와 SCOPE_(scope)의 합집합이다.
   *
   * 기존 5-인자 생성자와 getClaims()는 이 추가로 인해 한 글자도 바뀌지 않는다 —
   * F1 응답 계약을 건드리지 않기 위해서다.
   */
  public MemberDTO(String email, String pw, String nickname, boolean social, List<String> roleNames,
                    String act, List<String> scope) {
    super(
      email,
      pw,
      buildAuthorities(roleNames, scope));

    this.email = email;
    this.pw = pw;
    this.nickname = nickname;
    this.social = social;
    this.roleNames = roleNames;
    this.act = act;
    this.scope = scope != null ? scope : new ArrayList<>();
  }

  private static List<SimpleGrantedAuthority> buildAuthorities(List<String> roleNames, List<String> scope) {

    List<SimpleGrantedAuthority> authorities = new ArrayList<>();

    if (roleNames != null) {
      roleNames.forEach(str -> authorities.add(new SimpleGrantedAuthority("ROLE_" + str)));
    }

    if (scope != null) {
      scope.forEach(str -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + str)));
    }

    return authorities;
  }

  /**
   * JWT에 실을 claims.
   *
   * pw(BCrypt 해시)는 넣지 않는다. JWT payload는 서명될 뿐 암호화되지 않아
   * Base64 디코딩만으로 읽히기 때문이다. 인가에 필요한 것만 담는다.
   *
   * 여기를 바꾸면 읽는 쪽인 JWTCheckFilter 도 반드시 같이 고쳐야 한다.
   * 컴파일러가 불일치를 잡아주지 못한다.
   */
  public Map<String, Object> getClaims() {

    Map<String, Object> dataMap = new HashMap<>();

    dataMap.put("email", email);
    dataMap.put("nickname", nickname);
    dataMap.put("social", social);
    dataMap.put("roleNames", roleNames);

    return dataMap;
  }

}
