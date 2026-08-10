package com.agenticauth.repository;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.agenticauth.domain.Member;
import com.agenticauth.domain.MemberRole;

import lombok.extern.log4j.Log4j2;

/**
 * 로그인 테스트에 쓸 회원을 DB에 만든다.
 *
 * <pre>
 *   .\gradlew.bat test --tests "com.agenticauth.repository.MemberRepositoryTests"
 * </pre>
 *
 * ※ 실제 MariaDB 접속이 필요하다. db/schema.sql 을 먼저 실행할 것.
 * ※ 비밀번호는 반드시 PasswordEncoder(BCrypt)로 인코딩해서 넣는다.
 *   평문으로 넣으면 로그인 시 무조건 ERROR_LOGIN 이 난다.
 */
@SpringBootTest
@Log4j2
public class MemberRepositoryTests {

  @Autowired
  private MemberRepository memberRepository;

  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 로그인 테스트 계정 — front 화면의 기본값과 같다 */
  private static final String USER_EMAIL = "user1@aaa.com";
  private static final String USER_PW = "1111";

  private static final String ADMIN_EMAIL = "admin@aaa.com";
  private static final String ADMIN_PW = "1111";

  /**
   * user1@aaa.com (ROLE_USER) 과 admin@aaa.com (ROLE_USER + ROLE_ADMIN) 을 만든다.
   * 이미 있으면 덮어쓴다 — 여러 번 돌려도 안전하다.
   */
  @Test
  @DisplayName("테스트 계정 2개 생성 — user1(USER) · admin(USER+ADMIN)")
  public void createsTwoTestMembers() {

    // ── 일반 회원 ──
    Member user = Member.builder()
        .email(USER_EMAIL)
        .pw(passwordEncoder.encode(USER_PW))
        .nickname("USER1")
        .social(false)
        .build();
    user.addRole(MemberRole.USER);

    memberRepository.save(user);

    // ── 관리자 (F4 권한 테스트용) ──
    Member admin = Member.builder()
        .email(ADMIN_EMAIL)
        .pw(passwordEncoder.encode(ADMIN_PW))
        .nickname("ADMIN1")
        .social(false)
        .build();
    admin.addRole(MemberRole.USER);
    admin.addRole(MemberRole.ADMIN);

    memberRepository.save(admin);

    log.info("---------------- 생성 완료 ----------------");
    log.info(USER_EMAIL + " / " + USER_PW + "  → ROLE_USER");
    log.info(ADMIN_EMAIL + " / " + ADMIN_PW + "  → ROLE_USER, ROLE_ADMIN");
  }

  /**
   * 위 테스트가 만든 회원이 권한까지 함께 조회되는지 확인한다.
   * getWithRoles 는 @EntityGraph 로 memberRoleList 를 fetch join 한다 —
   * 일반 findById 로 바꾸면 LazyInitializationException 이 난다.
   */
  @Test
  @Transactional
  @DisplayName("권한까지 fetch join 으로 함께 조회된다")
  public void findsMemberWithRoles() {

    Member member = memberRepository.getWithRoles(USER_EMAIL);

    assertNotNull(member, USER_EMAIL + " 가 없다. createsTwoTestMembers 테스트를 먼저 실행할 것");
    assertEquals("USER1", member.getNickname());
    assertTrue(member.getMemberRoleList().contains(MemberRole.USER));

    log.info(member);
    log.info(member.getMemberRoleList());
  }

  /**
   * 비밀번호가 BCrypt로 저장됐는지 확인한다.
   * 이게 깨지면 로그인이 항상 ERROR_LOGIN 이 된다.
   */
  @Test
  @DisplayName("비밀번호가 BCrypt 해시로 저장된다 — 깨지면 로그인이 항상 ERROR_LOGIN")
  public void passwordIsStoredAsBCryptHash() {

    Member member = memberRepository.getWithRoles(USER_EMAIL);

    assertNotNull(member, USER_EMAIL + " 가 없다. createsTwoTestMembers 테스트를 먼저 실행할 것");
    assertNotEquals(USER_PW, member.getPw(), "평문으로 저장되어 있다");
    assertTrue(member.getPw().startsWith("$2a$") || member.getPw().startsWith("$2b$"),
        "BCrypt 해시 형식이 아니다: " + member.getPw());
    assertTrue(passwordEncoder.matches(USER_PW, member.getPw()),
        "인코딩된 비밀번호가 원문과 매칭되지 않는다");

    log.info("저장된 해시: " + member.getPw());
  }
}
