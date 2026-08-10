package com.agenticauth.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agenticauth.security.DelegationValidator;
import com.agenticauth.util.CustomJWTException;
import com.agenticauth.util.JWTUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@RestController
@RequiredArgsConstructor
@Log4j2
public class APIRefreshController {

  /**
   * F9-3 — 이 경로는 {@code JWTCheckFilter} 의 제외 경로({@code /api/member/})라 필터를 거치지 않는다.
   *
   * <p>그래서 여기서 직접 검증하지 않으면 <b>비활성화한 에이전트가 refresh 로 부활한다.</b>
   * 회수는 "매 요청 시점의 게이트"여야 하는데, 필터를 안 거치는 이 경로가 그 구멍이었다.
   */
  private final DelegationValidator delegationValidator;

@RequestMapping("/api/member/refresh")
public Map<String, Object> refresh(
    @RequestHeader("Authorization") String authHeader,
    @RequestParam("refreshToken") String refreshToken
){

    if(refreshToken == null) {
      throw new CustomJWTException("NULL_REFRASH");
    }

    if(authHeader == null || authHeader.length() < 7) {
      throw new CustomJWTException("INVALID_STRING");
    }

    String accessToken = authHeader.substring(7);

    //Access 토큰이 아직 쓸 수 있다면
    if(needsReissue(accessToken) == false ) {
      // 아직 유효한 토큰을 그대로 돌려주는 경로에서도 위임은 다시 확인한다.
      validateIfDelegated(JWTUtil.validateToken(accessToken));
      return Map.of("accessToken", accessToken, "refreshToken", refreshToken);
    }

    //Refresh토큰 검증
    Map<String, Object> claims = JWTUtil.validateToken(refreshToken);

    log.info("refresh ... claims: " + claims);

    // 재서명 전에 확인한다. claims 맵을 통째로 다시 서명하므로 act/scope/aud 가 그대로 승계되는데,
    // 그 승계가 안전하려면 "지금도 유효한 위임인가"를 여기서 반드시 물어야 한다.
    validateIfDelegated(claims);

    String newAccessToken = JWTUtil.generateToken(claims, 10);

    String newRefreshToken =  checkTime((Integer)claims.get("exp")) == true? JWTUtil.generateToken(claims, 60*24) : refreshToken;

    return Map.of("accessToken", newAccessToken, "refreshToken", newRefreshToken);

  }

  /**
   * 위임 토큰(act 있음)이면 audience(F11)와 에이전트 활성 상태(F9-3)를 다시 확인한다.
   * 일반 사용자 토큰(act 없음)은 아무 일도 하지 않는다 — F5 기존 동작 불변.
   */
  private void validateIfDelegated(Map<String, Object> claims) {

    if (claims != null && claims.get("act") != null) {
      delegationValidator.validate(claims, "/api/member/refresh");
    }
  }

  //시간이 1시간 미만으로 남았다면
  private boolean checkTime(Integer exp) {

    //JWT exp를 날짜로 변환
    java.util.Date expDate = new java.util.Date( (long)exp * (1000 ));

    //현재 시간과의 차이 계산 - 밀리세컨즈
    long gap   = expDate.getTime() - System.currentTimeMillis();

    //분단위 계산 
    long leftMin = gap / (1000 * 60);

    //1시간도 안남았는지.. 
    return leftMin < 60;
  }

  /**
   * 이 accessToken 을 더 이상 쓸 수 없어서 재발급이 필요한가 — K8.
   *
   * <p>예전 이름은 {@code checkExpiredToken} 이었고 <b>{@code "Expired"} 만</b> true 로 봤다.
   * 그래서 형식이 깨지거나 위조된 토큰은 "아직 유효"로 판정돼,
   * <b>서버가 그 망가진 토큰을 200 과 함께 그대로 되돌려줬다.</b>
   * 클라이언트는 "갱신됐다"고 믿고 같은 토큰으로 계속 실패하게 된다.
   *
   * <p>만료든 형식 오류든 위조든, 못 쓰는 건 마찬가지다. 전부 재발급 대상으로 본다.
   * 재발급 자체는 아래에서 <b>refreshToken 을 검증한 뒤에만</b> 이뤄지므로,
   * 이렇게 넓혀도 검증이 느슨해지지 않는다.
   */
  private boolean needsReissue(String token) {

    try{
      JWTUtil.validateToken(token);
      return false;   // 아직 유효하다 — 기존 토큰 쌍을 그대로 돌려준다 (F5 판정 3)
    }catch(CustomJWTException ex) {
      log.info("accessToken 을 쓸 수 없다 (" + ex.getMessage() + ") — refreshToken 으로 재발급을 시도한다");
      return true;
    }
  }
  
}