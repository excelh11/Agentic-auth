-- ================================================================
--  agentic-auth — MariaDB 초기 설정
--
--  실행:  mysql -u root -p < db/schema.sql
--         (또는 HeidiSQL / DBeaver에서 통째로 실행)
--
--  ※ 테이블은 spring.jpa.hibernate.ddl-auto=update 가 자동 생성한다.
--    아래 CREATE TABLE 은 "무엇이 만들어지는지" 확인용 참고 DDL이다.
--    직접 만들고 싶으면 §3을 실행하고 ddl-auto 를 validate 로 바꾸면 된다.
-- ================================================================


-- ── 1. 데이터베이스 ────────────────────────────────────────────
CREATE DATABASE IF NOT EXISTS agenticauthdb
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;


-- ── 2. 계정 ────────────────────────────────────────────────────
--   application.properties 의 username/password 와 일치해야 한다.
CREATE USER IF NOT EXISTS 'aauthuser'@'localhost' IDENTIFIED BY 'aauthpw';
CREATE USER IF NOT EXISTS 'aauthuser'@'%'         IDENTIFIED BY 'aauthpw';

GRANT ALL PRIVILEGES ON agenticauthdb.* TO 'aauthuser'@'localhost';
GRANT ALL PRIVILEGES ON agenticauthdb.* TO 'aauthuser'@'%';

FLUSH PRIVILEGES;


-- ── 3. 테이블 (참고용 — ddl-auto=update 가 자동 생성) ──────────
USE agenticauthdb;

-- com.agenticauth.domain.Member
CREATE TABLE IF NOT EXISTS member (
  email     VARCHAR(255) NOT NULL,
  pw        VARCHAR(255) NULL,          -- BCrypt 해시가 들어간다 (60자)
  nickname  VARCHAR(255) NULL,
  social    BIT(1)       NOT NULL,
  PRIMARY KEY (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Member.memberRoleList 는 @ElementCollection 이라 별도 테이블로 떨어진다.
-- 컬럼명 member_role_list 는 JPA 기본 네이밍 규칙(필드명 → snake_case) 결과다.
CREATE TABLE IF NOT EXISTS member_member_role_list (
  member_email      VARCHAR(255) NOT NULL,
  member_role_list  TINYINT      NULL,   -- enum ordinal: 0=USER, 1=MANAGER, 2=ADMIN
  CONSTRAINT fk_member_role_member
    FOREIGN KEY (member_email) REFERENCES member (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- com.agenticauth.domain.Agent (F9 — 위임 토큰의 행위자로 등록되는 에이전트)
CREATE TABLE IF NOT EXISTS agent (
  agent_id       VARCHAR(255) NOT NULL,
  name           VARCHAR(255) NULL,       -- F9-5 사람이 읽는 이름 (등록 시 필수)
  description    VARCHAR(255) NULL,       -- 무엇을 하는 에이전트인지
  owner_email    VARCHAR(255) NULL,       -- 이 에이전트를 등록한 사용자(위임자)
  active         BIT(1)       NOT NULL,   -- F9-3 개별 회수의 근거
  registered_at  DATETIME     NULL,
  PRIMARY KEY (agent_id),
  CONSTRAINT fk_agent_owner
    FOREIGN KEY (owner_email) REFERENCES member (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 이미 agent 테이블이 있다면 (ddl-auto=update 가 자동으로 해 주지만 참고용)
--   ALTER TABLE agent ADD COLUMN name VARCHAR(255) NULL, ADD COLUMN description VARCHAR(255) NULL;


-- ── 4. 확인용 조회 ─────────────────────────────────────────────
-- 테스트 코드(MemberRepositoryTests.회원_2명_생성)를 돌린 뒤 실행한다.
--
--   SELECT m.email, m.nickname, m.social, r.member_role_list AS role_ordinal
--     FROM member m
--     LEFT JOIN member_member_role_list r ON r.member_email = m.email
--    ORDER BY m.email;
--
-- 기대 결과
--   user1@aaa.com   USER1    0   → ROLE_USER
--   admin@aaa.com   ADMIN1   0   → ROLE_USER
--   admin@aaa.com   ADMIN1   2   → ROLE_ADMIN


-- ── 5. 초기화가 필요할 때 ──────────────────────────────────────
--   DELETE FROM member_member_role_list;
--   DELETE FROM member;
