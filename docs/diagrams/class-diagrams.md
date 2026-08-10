# 클래스 다이어그램

> **Mermaid 소스다.** GitHub·VS Code가 그대로 렌더링한다.
> 예전에는 PNG였는데, 클래스가 바뀔 때마다 다시 그려야 해서 **텍스트로 바꿨다.**
> 코드를 고쳤으면 여기도 같이 고친다 — `docs/1-SPEC.md` 와 같은 규칙이다.

전체 22개 클래스. `F9~F13`(에이전트 위임 인증)에서 새로 들어온 것은 **★ · 노란색**으로 표시했다.

---

## 1 · 패키지와 의존 방향

의존이 한 방향으로만 흐른다. `util` 은 아무것도 모르고, `domain`·`repository` 는 시큐리티를 모른다.

```mermaid
flowchart TD
    subgraph config
        CSC["CustomSecurityConfig<br/>필터체인 · CORS · URL 인가"]
    end
    subgraph controller
        SC["SampleController"]
        ARC["APIRefreshController"]
        AAC["APIAgentController ★"]
        CCA["advice/<br/>CustomControllerAdvice"]
    end
    subgraph security
        JCF["filter/JWTCheckFilter"]
        CUDS["CustomUserDetailsService"]
        DV["DelegationValidator ★"]
        SA["ScopeAuthorizer ★"]
        SCat["ScopeCatalog ★"]
        H["handler/<br/>APILoginSuccess · APILoginFail<br/>· CustomAccessDenied"]
    end
    subgraph dto
        MDTO["MemberDTO<br/>extends User"]
    end
    subgraph domain
        M["Member"]
        MR["MemberRole<br/>enum"]
        AG["Agent ★"]
    end
    subgraph repository
        MRepo["MemberRepository"]
        ARepo["AgentRepository ★"]
    end
    subgraph util
        JU["JWTUtil<br/>RS256"]
        CJE["CustomJWTException"]
    end

    CSC --> JCF
    CSC --> H
    CSC --> DV
    JCF --> JU
    JCF --> MDTO
    JCF --> DV
    JCF --> CJE
    DV --> ARepo
    DV --> CJE
    SA --> MDTO
    AAC --> SCat
    AAC --> ARepo
    AAC --> JU
    AAC --> MDTO
    ARC --> JU
    ARC --> DV
    SC --> MDTO
    SC -.->|PreAuthorize| SA
    H --> JU
    H --> MDTO
    CCA --> CJE
    CUDS --> MRepo
    CUDS --> MDTO
    MRepo --> M
    ARepo --> AG
    M --> MR

    classDef new fill:#fff3cd,stroke:#d39e00,stroke-width:2px
    class AAC,DV,SA,SCat,AG,ARepo new
```

---

## 2 · 인증 주체를 나르는 클래스

DB의 `Member` 와 Spring Security가 이해하는 `MemberDTO` 는 필드가 거의 같지만 **다른 타입**이다.
둘을 잇는 유일한 지점이 `CustomUserDetailsService.loadUserByUsername()` 이다.

`Agent` 는 이 계보 바깥에 있다 — 인증 주체가 아니라 **행위자의 신원**이기 때문이다.

```mermaid
classDiagram
    class Member {
        <<Entity>>
        +String email «Id»
        +String pw
        +String nickname
        +boolean social
        +List~MemberRole~ memberRoleList «Lazy»
    }
    class MemberRole {
        <<enum>>
        USER
        MANAGER
        ADMIN
    }
    class MemberDTO {
        +String email
        +String pw
        +String nickname
        +boolean social
        +List~String~ roleNames
        +String act «F9 · nullable»
        +List~String~ scope «F10 · nullable»
        +getClaims() Map
        -buildAuthorities() List
    }
    class User {
        <<Spring Security>>
        +getAuthorities() Collection
    }
    class CustomUserDetailsService {
        -MemberRepository memberRepository
        +loadUserByUsername(String) UserDetails
    }
    class Agent {
        <<Entity · F9>>
        +String agentId «Id»
        +String ownerEmail
        +boolean active «회수 근거»
        +LocalDateTime registeredAt
        +deactivate() void
    }

    User <|-- MemberDTO
    Member "1" --> "*" MemberRole
    CustomUserDetailsService ..> Member : 조회
    CustomUserDetailsService ..> MemberDTO : 변환
    Agent ..> Member : ownerEmail (FK)
```

> `MemberDTO` 는 생성자가 **둘**이다.
> 5-인자는 사용자 본인 토큰(F1)용, **7-인자는 위임 토큰(F9)용**으로 `act`·`scope` 를 더 받는다.
> 권한은 `ROLE_(roleNames)` **∪** `SCOPE_(scope)` 의 합집합이 된다.
> `Agent.agentId` 는 `@Id`, `Member.email` 도 `@Id` 이며 `memberRoleList` 는 지연 로딩이다.

---

## 3 · claims 를 쓰는 쪽과 읽는 쪽

**여기가 이 프로젝트에서 가장 위험한 지점이다.**
쓰는 쪽과 읽는 쪽이 **문자열 키로만** 이어져 있어서, 한쪽만 고치면 컴파일은 통과하고 런타임에 터진다.

F9~F13에서 **쓰는 쪽이 둘로 갈렸다.** 읽는 쪽은 하나이며 `act` 의 유무로 분기한다.

```mermaid
flowchart LR
    subgraph W1["쓰는 쪽 ① · F1 사용자 토큰"]
        A["MemberDTO.getClaims()"]
        A1["email · nickname<br/>social · roleNames<br/><b>4-key</b>"]
        A --> A1
    end
    subgraph W2["쓰는 쪽 ② · F9 위임 토큰 ★"]
        B["APIAgentController<br/>.delegate()"]
        B1["email · nickname · social · roleNames<br/><b>+ sub · act · scope · aud</b><br/><b>8-key</b>"]
        B --> B1
    end

    A1 --> JU["JWTUtil.generateToken()<br/><i>RS256 개인키로 서명</i>"]
    B1 --> JU
    JU --> T(("JWT"))
    T --> JV["JWTUtil.validateToken()<br/><i>RS256 공개키로 검증</i>"]
    JV --> R{"claims 에<br/>act 가 있나?"}

    R -->|"없음<br/>본인 호출"| R1["new MemberDTO(5-인자)<br/>권한 = ROLE_"]
    R -->|"있음<br/>위임 호출 ★"| R2["DelegationValidator.validate()<br/><i>aud · 에이전트 활성 · 소유자</i>"]
    R2 --> R3["new MemberDTO(7-인자)<br/>권한 = ROLE_ ∪ SCOPE_"]

    R1 --> SCH["SecurityContextHolder"]
    R3 --> SCH

    classDef danger fill:#f8d7da,stroke:#c82333,stroke-width:2px
    classDef new fill:#fff3cd,stroke:#d39e00,stroke-width:2px
    class A1,B1 danger
    class B,B1,R2,R3 new
```

> **빨간 박스 두 개가 짝이다.** 여기에 키를 더하거나 이름을 바꾸면
> `JWTCheckFilter` 의 읽는 부분도 **반드시 같이** 고쳐야 한다. 컴파일러는 아무것도 말해주지 않는다.

---

## 4 · 요청 하나가 지나가는 길

`JWTCheckFilter` 는 `UsernamePasswordAuthenticationFilter` **앞**에 선다.
`ExceptionTranslationFilter` 는 그보다 **뒤**에 있어서, `@PreAuthorize` 거부는 필터까지 올라오지 않는다.

```mermaid
flowchart TD
    REQ(["요청"]) --> CORS["CorsFilter<br/><i>OPTIONS preflight 는 여기서 끝</i>"]
    CORS --> JCF{"JWTCheckFilter"}

    JCF -->|제외 경로| SKIP["통과 · 검사 안 함<br/>/api/member/** · /api/sample/public"]
    JCF -->|토큰 없음 · 형식 오류 · 위조| E1["401 ERROR_ACCESS_TOKEN"]
    JCF -->|act 있음 · 검증 실패 ★| E2["401 ERROR_AUDIENCE<br/>401 ERROR_AGENT_INACTIVE"]
    JCF -->|인증 성공| AUTHZ

    SKIP --> AUTHZ["AuthorizationFilter<br/><i>URL 레벨 인가 · K7</i>"]
    AUTHZ -->|permitAll 또는 authenticated| PRE{"@PreAuthorize"}
    PRE -->|hasRole 실패| E3["403 ERROR_ACCESSDENIED"]
    PRE -->|scopeAuth.has 실패 ★| E4["403 ERROR_SCOPE"]
    PRE -->|통과| CTRL["Controller"]
    CTRL --> RES(["응답"])

    CTRL -.->|"여기서 난 예외는<br/>인증 실패로 둔갑하지 않는다 (K9)"| ERR["500 또는 advice 처리"]

    classDef fail fill:#f8d7da,stroke:#c82333
    classDef new fill:#fff3cd,stroke:#d39e00,stroke-width:2px
    class E1,E2,E3,E4 fail
    class E2,E4 new
```

> **`/api/member/**` 가 제외 경로**라는 게 함정이었다. `APIRefreshController` 는 필터를 안 거치므로
> **위임 검증을 스스로 해야 한다.** 안 그러면 회수한 에이전트가 refresh로 부활한다.

---

## 5 · F9~F13 위임 인증의 협력 관계

```mermaid
sequenceDiagram
    actor U as 사용자 user1
    participant AC as APIAgentController
    participant SC as ScopeCatalog
    participant AR as AgentRepository
    participant AG as 에이전트 봇
    participant F as JWTCheckFilter
    participant DV as DelegationValidator

    U->>AC: POST /api/agent/register
    AC->>AR: save Agent active=true
    AC-->>U: agentId

    U->>AC: POST /api/agent/delegate<br/>agentId + scope sample:read
    AC->>SC: 이 역할이 이 scope 를 위임할 수 있나
    Note over SC: USER → sample:read, sample:list<br/>ADMIN → + sample:admin
    SC-->>AC: 부분집합이다<br/>아니면 ERROR_SCOPE_EXCEEDS_ROLE
    AC-->>AG: 위임 토큰 sub · act · scope · aud

    AG->>F: GET /api/sample/user<br/>Bearer 위임토큰
    F->>DV: validate claims + path
    DV->>AR: findById act
    AR-->>DV: active 인가
    Note over DV: 감사 로그<br/>delegator + actor + api
    DV-->>F: 통과
    F-->>AG: 200

    U->>AC: POST /api/agent/{id}/deactivate
    AC->>AR: active = false

    AG->>F: GET /api/sample/user<br/>같은 토큰 · 아직 만료 전
    F->>DV: validate
    DV->>AR: findById act
    AR-->>DV: active = false
    DV-->>F: CustomJWTException
    F-->>AG: 401 ERROR_AGENT_INACTIVE
    Note over U,AG: 사용자 본인 토큰은 그대로 살아 있다
```

> **회수가 "발급 시점"이 아니라 "매 요청 시점"의 게이트**라는 게 핵심이다.
> 그래서 `DelegationValidator` 가 요청마다 DB를 한 번 조회한다 — PK 단건이다.
> 일반 사용자 토큰에는 이 비용이 없다. `act` 가 없으면 아예 호출하지 않는다.
