package com.agenticauth.config;

import java.util.Arrays;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.agenticauth.security.DelegationValidator;
import com.agenticauth.security.filter.JWTCheckFilter;
import com.agenticauth.security.handler.APILoginFailHandler;
import com.agenticauth.security.handler.APILoginSuccessHandler;
import com.agenticauth.security.handler.CustomAccessDeniedHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Configuration
@Log4j2
@RequiredArgsConstructor
@EnableMethodSecurity
public class CustomSecurityConfig {

    @Bean
  public PasswordEncoder passwordEncoder(){
    return new BCryptPasswordEncoder();
  }


@Bean
  public SecurityFilterChain filterChain(HttpSecurity http, DelegationValidator delegationValidator) throws Exception {
    
    log.info("---------------------security config---------------------------");

    http.cors(httpSecurityCorsConfigurer -> {
      httpSecurityCorsConfigurer.configurationSource(corsConfigurationSource());
    });

    http.sessionManagement(sessionConfig ->  sessionConfig.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

    http.csrf(config -> config.disable());

        http.formLogin(config ->{
      config.loginPage("/api/member/login");
      config.successHandler(new APILoginSuccessHandler());
       config.failureHandler(new APILoginFailHandler());
    });

      http.addFilterBefore(new JWTCheckFilter(delegationValidator), UsernamePasswordAuthenticationFilter.class); //JWT 체크

    // K7 — URL 레벨 인가. 예전에는 이게 없어서 보호가 전적으로 @PreAuthorize 에만 의존했다.
    // 컨트롤러에 애노테이션을 빠뜨리면 그 엔드포인트는 조용히 무방비가 된다.
    //
    // ⚠️ 이 DSL 의 hasRole() 은 "ROLE_" 접두어를 붙이면 예외를 던진다 (F4 참고).
    //    여기서는 역할까지 다루지 않고 "인증 여부"만 본다 — 역할·scope 는 @PreAuthorize 가 계속 담당한다.
    http.authorizeHttpRequests(auth -> auth
        // JWTCheckFilter.shouldNotFilter() 의 제외 경로와 반드시 짝을 맞춘다.
        // 한쪽만 고치면 필터는 통과시키는데 인가가 막거나 그 반대가 된다.
        .requestMatchers("/api/member/**", "/api/sample/public").permitAll()
        // 예외 처리 forward(/error)까지 막으면 원인이 403 으로 덮인다 — K9 와 같은 계열의 함정이다.
        .requestMatchers("/error").permitAll()
        .anyRequest().authenticated());

        http.exceptionHandling(config -> {config.accessDeniedHandler(new CustomAccessDeniedHandler());
    });
    return http.build();
  }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

    CorsConfiguration configuration = new CorsConfiguration();

    configuration.setAllowedOriginPatterns(Arrays.asList("*"));
    configuration.setAllowedMethods(Arrays.asList("HEAD", "GET", "POST", "PUT", "DELETE"));
    configuration.setAllowedHeaders(Arrays.asList("Authorization", "Cache-Control", "Content-Type"));
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);

    return source;
  }
  
}