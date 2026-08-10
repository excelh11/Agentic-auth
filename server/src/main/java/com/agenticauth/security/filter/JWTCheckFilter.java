package com.agenticauth.security.filter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.google.gson.Gson;
import com.agenticauth.dto.MemberDTO;
import com.agenticauth.security.DelegationValidator;
import com.agenticauth.util.CustomJWTException;
import com.agenticauth.util.JWTUtil;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class JWTCheckFilter extends OncePerRequestFilter{

    // JWTCheckFilter 자체는 Spring Bean으로 등록하지 않는다 — Filter 타입을 Bean으로 노출하면
    // 서블릿 컨테이너에 이중 등록돼 요청당 두 번 실행된다. CustomSecurityConfig가
    // DelegationValidator(Filter 아님)만 Bean으로 받아 new JWTCheckFilter(...)로 조립한다.
    private final DelegationValidator delegationValidator;

    public JWTCheckFilter(DelegationValidator delegationValidator) {
        this.delegationValidator = delegationValidator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException{
             // Preflight요청은 체크하지 않음
             // 어떠한 요청을 보낼껀데, 너희 서버 잘돌아가니?
        if(request.getMethod().equals("OPTIONS")){
            return true;
        }

        String path = request.getRequestURI();

        log.info("check uri......................."+path);

                //api/member/ 경로의 호출은 체크하지 않음
    if(path.startsWith("/api/member/")) {
        return true;
      }

        // 토큰 없이 열어두는 샘플 경로
        // (<img src>처럼 Authorization 헤더를 실을 수 없는 호출을 위한 자리다)
        if(path.startsWith("/api/sample/public")){
            return true;
        }

        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
    throws ServletException, IOException{

         log.info("------------------------JWTCheckFilter------------------");

    String authHeaderStr = request.getHeader("Authorization");

    // 헤더가 없거나 형식이 다르면 substring(7) 전에 걸러낸다.
    // 예전에는 여기서 NPE가 나고 아래 catch가 삼켜서, 원인이 "헤더 없음"인지
    // "토큰이 깨짐"인지 로그만 봐서는 구분할 수 없었다.
    if (authHeaderStr == null || !authHeaderStr.startsWith("Bearer ")) {
      log.warn("Authorization 헤더가 없거나 'Bearer ' 접두어가 빠졌다: " + authHeaderStr);
      sendUnauthorized(response);
      return;
    }

    try {
      //Bearer accestoken...
      String accessToken = authHeaderStr.substring(7);
      Map<String, Object> claims = JWTUtil.validateToken(accessToken);

      log.info("JWT claims: " + claims);

    //   filterChain.doFilter(request, response);

  String email = (String) claims.get("email");
      String nickname = (String) claims.get("nickname");
      Boolean social = (Boolean) claims.get("social");
      List<String> roleNames = stringList(claims.get("roleNames"));

      // F9~F13 — act 클레임이 있으면 위임 토큰이다. 사용자 본인 로그인 토큰(F1)에는 없다.
      String act = (String) claims.get("act");

      MemberDTO memberDTO;

      if (act != null) {

        // 서명·만료 확인만으로 끝나지 않는다 — audience(F11)와 에이전트의 현재 활성 상태(F9-3)를
        // 매 요청마다 다시 조회한다. 위반 시 CustomJWTException("ERROR_AUDIENCE" / "ERROR_AGENT_INACTIVE").
        delegationValidator.validate(claims, request.getRequestURI());

        List<String> scope = stringList(claims.get("scope"));

        Object subClaim = claims.get("sub");
        String subject = subClaim != null ? subClaim.toString() : email;

        memberDTO = new MemberDTO(subject, "", nickname, social.booleanValue(), roleNames, act, scope);

      } else {
        // 비밀번호는 claims에 없다. 상위 타입인 User 가 null 을 거부하므로 빈 문자열을 넣는다.
        memberDTO = new MemberDTO(email, "", nickname, social.booleanValue(), roleNames);
      }

      log.info("-----------------------------------");
      log.info(memberDTO);
      log.info(memberDTO.getAuthorities());

      // credentials 는 null 이다 — 이미 토큰으로 인증이 끝난 뒤라 자격증명을 들고 있을 이유가 없다.
      UsernamePasswordAuthenticationToken authenticationToken
      = new UsernamePasswordAuthenticationToken(memberDTO, null, memberDTO.getAuthorities());

      SecurityContextHolder.getContext().setAuthentication(authenticationToken);

    }catch(CustomJWTException e){

      log.error("JWT Check Error..............");
      log.error(e.getMessage());

      // F9~F13 전용 사유(audience 불일치·에이전트 비활성)는 프론트가 구분할 수 있도록
      // 구체적인 에러 코드를 그대로 실어 보낸다. 그 외 일반 토큰 오류(F3)는 기존대로
      // ERROR_ACCESS_TOKEN 하나로 뭉뚱그린다 — F3 스펙과 기존 테스트를 깨지 않기 위해서다.
      if ("ERROR_AUDIENCE".equals(e.getMessage()) || "ERROR_AGENT_INACTIVE".equals(e.getMessage())) {
        sendError(response, e.getMessage());
      } else {
        sendUnauthorized(response);
      }
      return;

    }catch(Exception e){

      log.error("JWT Check Error..............");
      log.error(e.getMessage());

      sendUnauthorized(response);
      return;
    }

    // K9 — 체인은 반드시 try 블록 **밖에서** 태운다.
    //
    // 예전에는 이 호출이 try 안에 있어서, 컨트롤러·서비스·DB에서 난 예외까지
    // 위 catch(Exception e) 에 걸려 ERROR_ACCESS_TOKEN 401 로 둔갑했다.
    // 실제로 agent 테이블 FK 제약 위반이 "토큰이 잘못됐다"로 보고돼 원인 추적이 한참 지연됐다.
    // 인증은 여기까지다 — 이 뒤에서 나는 예외는 인증 문제가 아니므로 손대지 않고 그대로 흘려보낸다.
    filterChain.doFilter(request, response);
  }

  /**
   * JSON claims 에서 꺼낸 값을 문자열 리스트로 본다.
   *
   * JWT payload 는 타입 정보가 없는 JSON이라 제네릭 캐스팅을 컴파일러가 검증할 수 없다.
   * 캐스팅을 한 곳에 모아 두고 여기서만 경고를 억제한다 — 호출부마다 흩뿌리지 않기 위해서다.
   */
  @SuppressWarnings("unchecked")
  private static List<String> stringList(Object value) {
    return (List<String>) value;
  }

  /**
   * 인증 실패 응답. 반드시 401을 실어 보낸다.
   *
   * 상태코드를 남기지 않으면 200으로 나가서, 클라이언트가 성공과 실패를
   * 본문 문자열로만 구분해야 한다. 표준 인터셉터가 동작하지 않게 된다.
   */
  private void sendUnauthorized(HttpServletResponse response) throws IOException {
    sendError(response, "ERROR_ACCESS_TOKEN");
  }

  /** F9~F13 신규 에러 코드 등, 사유가 명확할 때 그 코드를 그대로 401로 응답한다. */
  private void sendError(HttpServletResponse response, String errorCode) throws IOException {

    Gson gson = new Gson();
    String msg = gson.toJson(Map.of("error", errorCode));

    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json; charset=UTF-8");

    PrintWriter printWriter = response.getWriter();
    printWriter.println(msg);
    printWriter.close();
  }

}
