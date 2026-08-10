package com.agenticauth.domain;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

/**
 * F9 — 위임 토큰의 행위자가 되려면 사전에 등록된 에이전트여야 한다.
 *
 * <pre>
 *   agentId       act 클레임에 실리는 고유 식별자
 *   name          사람이 읽는 이름 — F9-5. 필수다
 *   description   무엇을 하는 에이전트인지 — 위임 승인·회수 시 판단 근거
 *   ownerEmail    이 에이전트를 등록한 사용자(위임자) — Member.email 과 연결
 *   active        F9-3 개별 회수의 근거. false면 신규 토큰 발급도, 기존 위임 토큰 통과도 거부한다
 *   registeredAt  등록 시각 — 감사 목적
 * </pre>
 *
 * <b>name 이 왜 필수인가</b> — 초기 명세는 식별자만 요구했다. 그렇게 만들고 보니
 * 감사 로그(F13)의 행위자가 {@code agent-a46e2155…} 라서 "무엇이 실행했는지"를 알 수 없었다.
 * F13의 목적이 절반만 달성되어 명세를 넓혔다.
 */
@Entity
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
public class Agent {

  @Id
  private String agentId;

  /** 사람이 읽는 이름 — F9-5. 감사 로그와 위임 승인 화면이 이걸 쓴다. */
  private String name;

  /** 이 에이전트가 무엇을 하는지. 선택. */
  private String description;

  private String ownerEmail;

  private boolean active;

  private LocalDateTime registeredAt;

  public void deactivate() {
    this.active = false;
  }
}
