package com.agenticauth.security.handler;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.google.gson.Gson;
import com.agenticauth.dto.MemberDTO;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class CustomAccessDeniedHandler implements AccessDeniedHandler{

  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response,
      AccessDeniedException accessDeniedException) throws IOException, ServletException {

    Gson gson = new Gson();

    // F10 — 위임 토큰(act 있음)이 부여받은 scope 밖의 API를 호출해 거부된 경우는
    // ERROR_SCOPE로 구분한다. act가 없는 본인 호출의 기존 거부 사유는 그대로 ERROR_ACCESSDENIED다.
    String errorCode = "ERROR_ACCESSDENIED";

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof MemberDTO) {
      MemberDTO memberDTO = (MemberDTO) authentication.getPrincipal();
      if (memberDTO.getAct() != null) {
        errorCode = "ERROR_SCOPE";
      }
    }

    String jsonStr = gson.toJson(Map.of("error", errorCode));

    response.setContentType("application/json");
    response.setStatus(HttpStatus.FORBIDDEN.value());
    PrintWriter printWriter = response.getWriter();
    printWriter.println(jsonStr);
    printWriter.close();

  }

}
