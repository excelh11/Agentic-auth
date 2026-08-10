package com.agenticauth.controller.advice;

import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.agenticauth.util.CustomJWTException;

/**
 * CustomControllerAdvice
 */
@RestControllerAdvice
public class CustomControllerAdvice {


  @ExceptionHandler(NoSuchElementException.class)
  protected ResponseEntity<?> notExist(NoSuchElementException e) {

      String msg = e.getMessage();

      return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("msg", msg));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  protected ResponseEntity<?> handleIllegalArgumentException(MethodArgumentNotValidException e) {

      String msg = e.getMessage();

      return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(Map.of("msg", msg));
  }

      /**
       * 토큰 관련 예외는 인증 실패다. 200이 아니라 401로 나가야 한다.
       * (Expired · MalFormed · Invalid · JWTError · NULL_REFRASH · INVALID_STRING …)
       */
      @ExceptionHandler(CustomJWTException.class)
  protected ResponseEntity<?> handleJWTException(CustomJWTException e) {

      String msg = e.getMessage();

      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", msg));
  }
}