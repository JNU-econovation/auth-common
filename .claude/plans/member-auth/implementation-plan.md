# member-auth - implementation

## 메타
- **작업명**: member-auth
- **문서 타입**: implementation
- **작성일**: 2026-05-10
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

---

## 개요

ECONO 자체 Member 도메인과 loginId/비밀번호 기반 가입·로그인 시스템을 헥사고날 아키텍처로 구현한다.
Spring Boot 3.2.2 / Java 21 / Gradle Kotlin DSL 멀티모듈 위에서 `auth-core`(도메인·포트·유스케이스),
`auth-infra`(JPA·BCrypt·JWT 어댑터), `auth-api`(인바운드 웹 어댑터), `api-gateway`(JWT → Passport 변환 필터),
`auth-common-lib`(Passport 필드명 변경) 다섯 모듈에 걸쳐 설계된다.
도메인 객체는 프레임워크 의존성이 없으며, 의존성 방향은 항상 외부(어댑터) → 내부(도메인/유스케이스)로만 향한다.
이번 개정에서 `Email` VO가 제거되고 `loginId: String` 기반으로 전면 교체되며, `MemberStatus`가 `AM/RM/CM/OB`로 변경되고,
JWT 클레임 및 Passport 필드가 확장·갱신된다.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport` | 변경 | Passport 필드 `email` → `loginId`, 클레임 확장(generation, status 추가) |
| `services/libs/auth-common-lib/src/test/java/com/econo/common/auth/core/passport` | 변경 | PassportTest — `email` 파라미터·getter → `loginId` 전수 갱신 |
| `services/libs/auth-common-lib/src/test/java/com/econo/common/auth/web/resolver` | 변경 | PassportArgumentResolverTest — JSON 키 `"email"` → `"loginId"` 갱신 |
| `services/libs/auth-common-lib/src/test/java/com/econo/common/auth/integration` | 변경 | PassportAuthIntegrationTest — `email` 인자 → `loginId` 갱신 |
| `services/libs/auth-core/src/main/java/com/econo/auth/core/member/domain` | 신규 | Member 도메인 객체, MemberStatus enum — 프레임워크 무의존 순수 도메인 |
| `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/port/in` | 신규 | 인바운드 포트(SignupUseCase, LoginUseCase) |
| `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/port/out` | 신규 | 아웃바운드 포트(MemberRepository, PasswordHasher, TokenIssuer) |
| `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/usecase` | 신규 | 유스케이스 구현체(SignupService, LoginService) |
| `services/libs/auth-core/src/main/java/com/econo/auth/core/member/exception` | 신규 | 도메인 예외 3종 |
| `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/persistence` | 신규 | MemberJpaEntity, MemberJpaRepository, MemberRepositoryAdapter |
| `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/security` | 신규 | BCryptPasswordHasherAdapter |
| `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/token` | 신규 | JwtTokenIssuerAdapter |
| `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config` | 신규 | JpaAuditingConfig |
| `services/libs/auth-infra/src/main/resources/db/migration` | 신규 | Flyway SQL 스크립트(V1__create_members_table.sql) |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web` | 신규 | MemberController, SignupRequest, LoginRequest |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config` | 신규 | SecurityConfig, JwtCookieProperties |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/exception` | 신규 | GlobalExceptionHandler |
| `services/apis/auth-api/src/main/java/com/econo/auth/api` | 변경 | AuthApiApplication — scanBasePackages 명시 |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter` | 신규 | JwtCookieToPassportFilter |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security` | 신규 | JwtVerifier, PassportBuilder |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config` | 신규 | GatewayRoutingConfig |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/libs/auth-common-lib`

```
com.econo.common.auth.core.passport/
└── Passport                   — (변경) 필드 email → loginId, generation·status 필드 추가
```

##### Passport (변경)
- **타입**: Domain (Aggregate Root — auth-common-lib)
- **책임**: 마이크로서비스 간 통신용 회원 인증 정보를 불변으로 보유한다. `email` 필드를 `loginId`로 교체하고, 다운스트림이 매 요청마다 DB를 조회하지 않아도 되도록 `generation`, `status` 필드를 추가한다.
- **주요 메서드/함수**:
  - 생성자 파라미터 `@JsonProperty("email") String email` → `@JsonProperty("loginId") String loginId`
  - 필드 `private final String email` → `private final String loginId`
  - `private final Integer generation` 추가 (nullable — 클레임에 없을 경우 대비)
  - `private final String status` 추가 (nullable)
  - getter `getEmail()` → `getLoginId()`, `getGeneration()`, `getStatus()` 추가
  - `toString()` 내 `name` 포함 항목 유지; `email` 노출 제거 (loginId도 PII 미노출 정책에 따라 toString에서 생략)
  - `equals/hashCode` — `memberId` 기준 유지 (변경 없음)
- **의존성**: Jackson, Jakarta Validation, Lombok
- **적용 컨벤션**:
  - 필드 전체 `private final` (CONVENTION.md §2.3 불변성)
  - `@JsonCreator` + `@JsonProperty` 유지 (역직렬화 호환)
  - `roles` 필드 `List.copyOf()` 방어적 복사 유지 (ARCHITECTURE.md §2. 불변 객체)
  - `@Getter` 클래스 레벨 적용 (CONVENTION.md §2.2)
- **참조할 기존 코드**: `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java:17` — 전체 구조 유지, `email` 관련 라인만 교체
- **연관 todo**:
  - `[ ] Passport.java 필드명 변경`

---

#### 모듈 / 패키지: `services/libs/auth-common-lib` (테스트)

```
src/test/java/com/econo/common/auth/
├── core/passport/
│   ├── PassportTest               — (변경) email → loginId 전수 갱신
│   └── PassportExceptionTest      — (변경) 필요 시 email 참조 부분 갱신
├── web/resolver/
│   └── PassportArgumentResolverTest — (변경) JSON 키 "email" → "loginId"
└── integration/
    └── PassportAuthIntegrationTest  — (변경) createEncodedPassport() 인자 갱신
```

##### PassportTest (변경)
- **타입**: Unit Test
- **책임**: Passport 생성·권한·유효성·접근 제어 시나리오 검증. `email` → `loginId` 필드명 교체에 따른 전수 갱신.
- **주요 메서드/함수**:
  - `new Passport(memberId, email, name, roles, issuedAt, expiresAt)` → `new Passport(memberId, loginId, name, generation, status, roles, issuedAt, expiresAt)` (생성자 시그니처 변경에 맞춰 갱신)
  - `passport.getEmail()` → `passport.getLoginId()` (모든 assert 라인)
  - `String email = "test@eeos.com"` → `String loginId = "econo_user01"` (테스트 픽스처)
- **참조할 기존 코드**: `services/libs/auth-common-lib/src/test/java/com/econo/common/auth/core/passport/PassportTest.java:29` — 기존 생성 패턴 확인
- **연관 todo**: `[ ] PassportTest.java 갱신`

##### PassportArgumentResolverTest (변경)
- **타입**: Unit Test
- **책임**: X-User-Passport 헤더 디코딩 → Passport 주입 흐름 검증. JSON 키 `"email"` → `"loginId"` 교체.
- **주요 메서드/함수**:
  - `createEncodedPassport()` 내부에서 Passport 생성 시 `loginId` 인자 전달
  - JSON 직렬화 결과의 `"email"` 키 참조가 있는 경우 `"loginId"`로 변경
- **참조할 기존 코드**: `services/libs/auth-common-lib/src/test/java/com/econo/common/auth/web/resolver/PassportArgumentResolverTest.java:150`
- **연관 todo**: `[ ] PassportArgumentResolverTest.java 갱신`

##### PassportAuthIntegrationTest (변경)
- **타입**: Integration Test (`@SpringBootTest` + MockMvc)
- **책임**: E2E 인증 흐름 검증. `createEncodedPassport(memberId, email, name, roles)` 시그니처를 `(memberId, loginId, name, roles)` 또는 확장 파라미터로 변경.
- **주요 메서드/함수**:
  - `createEncodedPassport(123L, "test@eeos.com", ...)` → `createEncodedPassport(123L, "econo_user01", ...)`
  - 응답 검증 assert에서 email 관련 검증 제거
- **참조할 기존 코드**: `services/libs/auth-common-lib/src/test/java/com/econo/common/auth/integration/PassportAuthIntegrationTest.java:117`
- **연관 todo**: `[ ] PassportAuthIntegrationTest.java 갱신`

---

#### 모듈 / 패키지: `services/libs/auth-core`

```
com.econo.auth.core.member/
├── domain/
│   ├── Member                   — 회원 Aggregate Root (불변 도메인 객체)
│   └── MemberStatus             — 회원 상태 enum (AM/RM/CM/OB)
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── SignupUseCase    — 가입 인바운드 포트 + SignupCommand record
│   │   │   └── LoginUseCase    — 로그인 인바운드 포트 + LoginCommand·LoginResult record
│   │   └── out/
│   │       ├── MemberRepository — 회원 저장/조회 아웃바운드 포트
│   │       ├── PasswordHasher   — 비밀번호 해싱 아웃바운드 포트
│   │       └── TokenIssuer      — JWT 발급 아웃바운드 포트
│   └── usecase/
│       ├── SignupService        — SignupUseCase 구현체
│       └── LoginService         — LoginUseCase 구현체
└── exception/
    ├── MemberAlreadyExistsException   — loginId 중복 (HTTP 409)
    ├── InvalidCredentialsException    — 인증 실패 (HTTP 401)
    └── InvalidPasswordPolicyException — 비밀번호 정책 위반 (HTTP 400)
```

##### Member
- **타입**: Domain (Aggregate Root)
- **책임**: 회원 상태와 식별 정보를 불변으로 보유하며, 가입 팩토리 메서드로만 생성된다. `Email` VO 없이 `loginId: String`을 그대로 보유한다.
- **주요 메서드/함수**:
  - `static Member create(String name, String loginId, String hashedPassword, Integer generation, MemberStatus status)` — 신규 회원 생성, `createdAt = LocalDateTime.now()`
  - `Long getId()`, `String getName()`, `String getLoginId()`, `String getHashedPassword()`, `Integer getGeneration()`, `MemberStatus getStatus()`, `LocalDateTime getCreatedAt()` — Lombok `@Getter`
- **의존성**: 없음 (순수 도메인)
- **적용 컨벤션**:
  - 필드 전체 `private final` (CONVENTION.md §2.3 불변성)
  - `@Getter` 클래스 레벨 적용, setter 없음 (CONVENTION.md §2.2)
  - 생성자는 `private`; 팩토리 메서드만 공개 (hexagonal-architecture SKILL §Core Concepts)
  - 프레임워크 import 없음
- **참조할 기존 코드**: `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java:17` — 불변 도메인 객체 패턴 미러링
- **연관 todo**: `[ ] Member 도메인 객체 구현`

##### MemberStatus
- **타입**: Domain (Enum)
- **책임**: 회원 상태 유효 값 집합을 열거한다. 가입 시 사용자 입력값으로 지정하며 기본값은 없다.
- **주요 메서드/함수**: 상수 `AM`, `RM`, `CM`, `OB` (4개)
- **의존성**: 없음
- **적용 컨벤션**: 상수 2자 약자 대문자 (CONVENTION.md §1.4); 의미 해설 주석 금지 (todo §MemberStatus)
- **연관 todo**: `[ ] MemberStatus enum 구현`

##### PasswordHasher (아웃바운드 포트)
- **타입**: Port (Outbound Interface)
- **책임**: 비밀번호 해싱 능력을 추상화하여 도메인이 구체 구현(BCrypt)에 의존하지 않도록 한다.
- **주요 메서드/함수**:
  - `String hash(String rawPassword)`
  - `boolean matches(String rawPassword, String hashedPassword)`
- **의존성**: 없음 (인터페이스)
- **적용 컨벤션**: 헥사고날 아웃바운드 포트 — `application.port.out` 패키지에 위치 (hexagonal-architecture SKILL §Java)
- **연관 todo**: `[ ] PasswordHasher 아웃바운드 포트 정의`

##### MemberRepository (아웃바운드 포트)
- **타입**: Port (Outbound Interface)
- **책임**: Member 영속성 조작을 추상화한다. `Email` VO 대신 `String loginId`를 파라미터로 사용한다.
- **주요 메서드/함수**:
  - `void save(Member member)`
  - `Optional<Member> findByLoginId(String loginId)`
  - `boolean existsByLoginId(String loginId)`
- **의존성**: `Member` (도메인 타입만 사용)
- **적용 컨벤션**: 아웃바운드 포트; Spring Data 등 인프라 타입 import 금지
- **연관 todo**: `[ ] MemberRepository 아웃바운드 포트 정의`

##### TokenIssuer (아웃바운드 포트)
- **타입**: Port (Outbound Interface)
- **책임**: JWT 발급 능력을 추상화한다. 클레임 계약에 `loginId`, `name`, `generation`, `status`가 포함된다.
- **주요 메서드/함수**:
  - `String issue(Member member)` — 서명된 JWT 문자열 반환
  - **JWT 클레임 계약**: `sub(memberId)`, `loginId`, `name`, `generation`, `status`, `roles(["USER"])`, `iat`, `exp`
- **의존성**: `Member`
- **적용 컨벤션**: 아웃바운드 포트; JWT 라이브러리 타입 import 금지
- **연관 todo**: `[ ] TokenIssuer 아웃바운드 포트 정의`

##### SignupUseCase (인바운드 포트)
- **타입**: Port (Inbound Interface)
- **책임**: 회원 가입 커맨드를 정의하는 인바운드 포트.
- **주요 메서드/함수**:
  - `void signup(SignupCommand command)` — 성공 시 반환값 없음
  - (내부 record) `SignupCommand(String name, String loginId, String password, Integer generation, MemberStatus status)`
- **의존성**: `SignupCommand` (자체 record), `MemberStatus`
- **연관 todo**: `[ ] SignupUseCase 인바운드 포트 + 구현 작성`

##### SignupService
- **타입**: Use Case (Application Service)
- **책임**: 가입 유스케이스 오케스트레이션 — loginId 형식·중복 검증, name·generation 검증, 비밀번호 정책 검증, 해싱, 저장.
- **주요 메서드/함수**:
  - `void signup(SignupCommand command)` — 구현
  - `private void validateLoginId(String loginId)` — `^[a-zA-Z0-9\\-_.]{3,19}$` 정규식 검증
  - `private void validateName(String name)` — 1~50자 길이 검증
  - `private void validateGeneration(Integer generation)` — 1~99 범위 검증
  - `private void validatePasswordPolicy(String password)` — 8~19자, 대문자·소문자·숫자·특수기호(`!@#$%^&*()_+-=[]{};':"\\|,.<>/?` 등) 각 1자 이상 포함 검증
- **의존성**: `MemberRepository`, `PasswordHasher`
- **적용 컨벤션**:
  - `@RequiredArgsConstructor` + `@Service` (CONVENTION.md §2.2)
  - 아웃바운드 포트만 의존; JPA, BCrypt 등 구체 타입 import 금지
  - loginId 형식 오류 → `IllegalArgumentException` 또는 별도 `InvalidLoginIdFormatException` (구현자 판단; 단 GlobalExceptionHandler에서 400 매핑 필요)
  - 비밀번호 정책 위반 → `InvalidPasswordPolicyException`
  - loginId 중복 → `MemberAlreadyExistsException`
- **연관 todo**: `[ ] SignupUseCase 인바운드 포트 + 구현 작성`

##### LoginUseCase (인바운드 포트)
- **타입**: Port (Inbound Interface)
- **책임**: 로그인 커맨드를 정의하는 인바운드 포트.
- **주요 메서드/함수**:
  - `LoginResult login(LoginCommand command)`
  - (내부 record) `LoginCommand(String loginId, String password)`
  - (내부 record) `LoginResult(String jwtToken)`
- **연관 todo**: `[ ] LoginUseCase 인바운드 포트 + 구현 작성`

##### LoginService
- **타입**: Use Case (Application Service)
- **책임**: 로그인 유스케이스 오케스트레이션 — loginId 조회, 비밀번호 검증, JWT 발급.
- **주요 메서드/함수**:
  - `LoginResult login(LoginCommand command)` — 구현
- **의존성**: `MemberRepository`, `PasswordHasher`, `TokenIssuer`
- **적용 컨벤션**:
  - `@RequiredArgsConstructor`, `@Service`
  - `MemberRepository.findByLoginId()` 미존재/비밀번호 불일치 모두 동일한 `InvalidCredentialsException` throw (사용자 열거 방지)
  - `@Transactional(readOnly = true)` — 쓰기 작업 없음
- **연관 todo**: `[ ] LoginUseCase 인바운드 포트 + 구현 작성`

##### 도메인 예외 3종
- **타입**: Domain Exception
- **책임**: 각각 HTTP 상태와 매핑되는 의미론적 예외를 표현한다.
- **주요 메서드/함수**:
  - `MemberAlreadyExistsException` — HTTP 409, 정적 팩토리 `MemberAlreadyExistsException.of(String loginId)`
  - `InvalidCredentialsException` — HTTP 401, 정적 팩토리 `InvalidCredentialsException.of()` (고정 메시지: "아이디 또는 비밀번호가 올바르지 않습니다")
  - `InvalidPasswordPolicyException` — HTTP 400, 정적 팩토리 `InvalidPasswordPolicyException.of(String reason)`
- **의존성**: 없음 (`RuntimeException` 상속)
- **적용 컨벤션**:
  - 정적 팩토리 메서드 패턴 (CONVENTION.md §3.1 — `PassportException` 방식 미러링)
  - `@Getter` + `httpStatus` 필드 보유 → `GlobalExceptionHandler`에서 참조
- **참조할 기존 코드**: `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/PassportException.java:8` — 예외 설계 미러링
- **연관 todo**: `[ ] 커스텀 도메인 예외 정의`

---

#### 모듈 / 패키지: `services/libs/auth-infra`

```
com.econo.auth.infra/
├── member/
│   └── adapter/
│       └── out/
│           ├── persistence/
│           │   ├── MemberJpaEntity          — JPA 엔티티 (login_id, name, generation, status)
│           │   ├── MemberJpaRepository      — Spring Data JPA (findByLoginId, existsByLoginId)
│           │   └── MemberRepositoryAdapter  — MemberRepository 포트 구현체
│           ├── security/
│           │   └── BCryptPasswordHasherAdapter  — PasswordHasher 포트 구현체 (cost=12)
│           └── token/
│               └── JwtTokenIssuerAdapter    — TokenIssuer 포트 구현체 (확장 클레임)
└── config/
    └── JpaAuditingConfig                    — @EnableJpaAuditing 설정
```

##### MemberJpaEntity
- **타입**: JPA Entity (Outbound Adapter — Persistence)
- **책임**: `members` 테이블과 매핑되며, 도메인 `Member`와 상호 변환 메서드를 제공한다. `email` 컬럼이 `login_id`로 교체되고, `name`, `generation`, `status` 컬럼이 추가된다.
- **주요 메서드/함수**:
  - `static MemberJpaEntity from(Member member)` — 도메인 → JPA 엔티티 변환
  - `Member toDomain()` — JPA 엔티티 → 도메인 변환
- **의존성**: `Member`, `MemberStatus` (auth-core 도메인)
- **적용 컨벤션**:
  - `@Entity`, `@Table(name = "members", uniqueConstraints = @UniqueConstraint(name = "uq_members_login_id", columnNames = "login_id"))` (jpa-patterns SKILL §Entity Design)
  - `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate` (jpa-patterns SKILL §Auditing)
  - `@GeneratedValue(strategy = GenerationType.IDENTITY)` — PostgreSQL `BIGINT GENERATED ALWAYS AS IDENTITY` 대응
  - `@Enumerated(EnumType.STRING)` — MemberStatus (값: AM/RM/CM/OB)
  - `@Column(name = "login_id", nullable = false, unique = true, length = 30)`
  - `@Column(name = "name", nullable = false, length = 50)`
  - `@Column(name = "generation", nullable = false)`
  - Lombok `@Getter` 클래스 레벨; setter 없음 (불변 지향)
  - 도메인 변환 로직은 엔티티 내부에 위치
- **연관 todo**: `[ ] MemberJpaEntity 구현`

##### MemberJpaRepository
- **타입**: Spring Data JPA Repository (Outbound Adapter — Persistence)
- **책임**: `members` 테이블의 기본 CRUD 및 loginId 기반 조회를 Spring Data로 위임한다.
- **주요 메서드/함수**:
  - `Optional<MemberJpaEntity> findByLoginId(String loginId)` — 파생 쿼리
  - `boolean existsByLoginId(String loginId)` — 파생 쿼리
- **의존성**: `JpaRepository<MemberJpaEntity, Long>` (Spring Data)
- **적용 컨벤션**: Spring Data 파생 쿼리 사용 (SQL Injection 방지 — springboot-security SKILL §SQL Injection Prevention)
- **연관 todo**: `[ ] MemberJpaRepository 구현`

##### MemberRepositoryAdapter
- **타입**: Outbound Adapter (Port Implementation)
- **책임**: `MemberRepository` 포트를 구현하여 `MemberJpaRepository`에 위임하고, 도메인 ↔ JPA 변환을 처리한다.
- **주요 메서드/함수**:
  - `void save(Member member)` — `MemberJpaEntity.from(member)` 후 `jpaRepository.save()`
  - `Optional<Member> findByLoginId(String loginId)` — `jpaRepository.findByLoginId(loginId).map(MemberJpaEntity::toDomain)`
  - `boolean existsByLoginId(String loginId)` — `jpaRepository.existsByLoginId(loginId)`
- **의존성**: `MemberJpaRepository`, `MemberRepository` (포트)
- **적용 컨벤션**:
  - `@Component` + `@RequiredArgsConstructor`
  - `@Transactional` (쓰기), `@Transactional(readOnly = true)` (읽기)
- **연관 todo**: `[ ] MemberRepositoryAdapter 구현`

##### BCryptPasswordHasherAdapter
- **타입**: Outbound Adapter (Port Implementation)
- **책임**: `PasswordHasher` 포트를 BCrypt(cost=12)로 구현한다.
- **주요 메서드/함수**:
  - `String hash(String rawPassword)` — `BCryptPasswordEncoder.encode()`
  - `boolean matches(String rawPassword, String hashedPassword)` — `BCryptPasswordEncoder.matches()`
- **의존성**: `BCryptPasswordEncoder` (spring-security-crypto), `PasswordHasher` (포트)
- **적용 컨벤션**:
  - `@Component` + `@RequiredArgsConstructor`
  - `spring-security-crypto` 단독 의존 — Spring Security 풀 스택 미사용
  - `BCryptPasswordEncoder(12)` 인스턴스를 빈으로 등록하거나 생성자에서 직접 생성
- **연관 todo**: `[ ] BCryptPasswordHasherAdapter 구현`

##### JwtTokenIssuerAdapter
- **타입**: Outbound Adapter (Port Implementation)
- **책임**: `TokenIssuer` 포트를 jjwt(HMAC-SHA256)로 구현하며, 비밀키와 만료 시간을 외부 환경변수에서 주입받는다. 클레임이 확장되어 다운스트림이 DB 조회 없이 회원 정보를 활용할 수 있다.
- **주요 메서드/함수**:
  - `String issue(Member member)` — `Jwts.builder().claim("loginId", ...).claim("name", ...).claim("generation", ...).claim("status", ...).claim("roles", List.of("USER"))....signWith(key).compact()`
  - **JWT 클레임 전체**: `sub(memberId)`, `loginId`, `name`, `generation`, `status`, `roles(["USER"])`, `iat`, `exp`
- **의존성**: `jjwt-api/impl/jackson`, `TokenIssuer` (포트), `@Value("${JWT_SECRET}")`, `@Value("${auth.jwt.expiry-seconds}")`
- **적용 컨벤션**:
  - `@Component` + `@RequiredArgsConstructor`
  - 비밀키 하드코딩 금지 — `${JWT_SECRET}` 환경변수 (springboot-security SKILL §Secrets Management)
  - `@Slf4j` 로깅; 토큰 값 자체는 로그에 출력하지 않음
- **연관 todo**: `[ ] JwtTokenIssuerAdapter 구현`

##### JpaAuditingConfig
- **타입**: Config
- **책임**: `@EnableJpaAuditing`을 활성화하여 `@CreatedDate` 자동 설정을 가능하게 한다.
- **의존성**: Spring JPA
- **적용 컨벤션**: `@Configuration` + `@EnableJpaAuditing` (jpa-patterns SKILL §Entity Design)
- **연관 todo**: `[ ] JpaAuditingConfig 추가`

---

#### 모듈 / 패키지: `services/apis/auth-api`

```
com.econo.auth.api/
├── adapter/
│   └── in/
│       └── web/
│           ├── MemberController    — REST 컨트롤러 (가입/로그인/로그아웃)
│           ├── SignupRequest       — 가입 요청 DTO (record, 5개 필드)
│           └── LoginRequest        — 로그인 요청 DTO (record)
├── config/
│   ├── SecurityConfig              — Spring Security CSRF 비활성화 + permitAll
│   └── JwtCookieProperties         — JWT 쿠키 설정값 바인딩
├── exception/
│   └── GlobalExceptionHandler      — @RestControllerAdvice
└── AuthApiApplication              — (변경) scanBasePackages 명시
```

##### MemberController
- **타입**: Inbound Adapter (REST Controller)
- **책임**: HTTP 요청을 유스케이스 커맨드로 변환하고, 로그인 성공 시 HttpOnly 쿠키를 응답에 설정한다.
- **주요 메서드/함수**:
  - `ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request)` — `SignupUseCase.signup()` 위임, 201 반환
  - `ResponseEntity<Void> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response)` — `LoginUseCase.login()` 위임 후 `ResponseCookie` 설정, 200 반환
  - `ResponseEntity<Void> logout(HttpServletResponse response)` — `auth_token` 쿠키 Max-Age=0 만료, 200 반환
  - `private ResponseCookie buildAuthCookie(String jwt)` — `ResponseCookie.from("auth_token", jwt).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(expirySeconds).build()`
  - `private ResponseCookie expireAuthCookie()` — `maxAge(0)` 만료 쿠키
- **의존성**: `SignupUseCase`, `LoginUseCase`, `JwtCookieProperties`
- **적용 컨벤션**:
  - `@RestController`, `@RequestMapping("/api/v1/auth")`, `@RequiredArgsConstructor` (CONVENTION.md §2.2)
  - `@Valid` on request body (springboot-security SKILL §Input Validation)
  - 응답 Body에 JWT 직접 노출 금지 — 쿠키 전용
  - `@Slf4j` — 가입/로그인 이벤트 구조화 로깅 (loginId는 PII 수준 주의)
- **연관 todo**: `[ ] MemberController 구현`, `[ ] 요청 DTO 구현`

##### SignupRequest
- **타입**: Inbound Adapter DTO (Java record)
- **책임**: 가입 요청 페이로드 5개 필드를 Bean Validation 제약과 함께 보유한다.
- **주요 메서드/함수**:
  ```java
  record SignupRequest(
    @NotBlank @Size(min = 1, max = 50) String name,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9\\-_.]{3,19}$") String loginId,
    @NotBlank @Size(min = 8, max = 19) String password,
    @NotNull @Min(1) @Max(99) Integer generation,
    @NotNull MemberStatus status
  )
  ```
- **적용 컨벤션**: `record` 사용 (CONVENTION.md §8.4)
- **연관 todo**: `[ ] 요청 DTO 구현`

##### LoginRequest
- **타입**: Inbound Adapter DTO (Java record)
- **책임**: 로그인 요청 페이로드를 보유한다.
- **주요 메서드/함수**:
  ```java
  record LoginRequest(
    @NotBlank String loginId,
    @NotBlank String password
  )
  ```
- **적용 컨벤션**: `record` 사용
- **연관 todo**: `[ ] 요청 DTO 구현`

##### GlobalExceptionHandler
- **타입**: Exception Handler (`@RestControllerAdvice`)
- **책임**: 도메인 예외와 Spring 표준 예외를 HTTP 응답으로 변환한다.
- **주요 메서드/함수**:
  - `ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex)` → 400 + `VALIDATION_FAILED` + 필드별 메시지
  - `ResponseEntity<ApiError> handleMemberAlreadyExists(MemberAlreadyExistsException ex)` → 409 + `MEMBER_ALREADY_EXISTS`
  - `ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex)` → 401
  - `ResponseEntity<ApiError> handleInvalidPasswordPolicy(InvalidPasswordPolicyException ex)` → 400 + `INVALID_PASSWORD_POLICY`
  - `ResponseEntity<ApiError> handleGeneric(Exception ex)` → 500 (스택트레이스 외부 노출 금지)
  - (내부 record) `ApiError(String code, String message)`
- **의존성**: 도메인 예외 3종, Spring MVC 예외
- **적용 컨벤션**:
  - `@RestControllerAdvice` + `@Slf4j`
  - 500 핸들러에서 `log.error(..., ex)` 후 제네릭 메시지 반환
  - 사용자 열거 방지 — `InvalidCredentialsException` 메시지를 고정 문구로 래핑 ("아이디 또는 비밀번호가 올바르지 않습니다")
- **참조할 기존 코드**: `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/PassportException.java:8` — 예외 설계 참조
- **연관 todo**: `[ ] 전역 예외 핸들러 구성`

##### SecurityConfig
- **타입**: Config
- **책임**: Spring Security CSRF를 비활성화하고 stateless 정책을 설정한다.
- **주요 메서드/함수**:
  - `SecurityFilterChain filterChain(HttpSecurity http)` — `csrf.disable()`, `sessionManagement(STATELESS)`, 모든 경로 `permitAll()`
- **적용 컨벤션**: springboot-security SKILL §CSRF Protection
- **연관 todo**: `[ ] auth-api build.gradle.kts 의존성 추가`

##### AuthApiApplication (변경)
- **타입**: Application Entry Point
- **책임**: Spring Boot 진입점 — `auth-core`, `auth-infra` 모듈 빈이 스캔되도록 패키지 범위 명시.
- **주요 메서드/함수**: `@SpringBootApplication(scanBasePackages = {"com.econo.auth.api", "com.econo.auth.core", "com.econo.auth.infra"})`
- **연관 todo**: `[ ] AuthApiApplication Spring Boot 자동 설정 정비`

---

#### 모듈 / 패키지: `services/apis/api-gateway`

```
com.econo.auth.gateway/
├── filter/
│   └── JwtCookieToPassportFilter   — GlobalFilter (JWT 쿠키 → Passport 헤더)
├── security/
│   ├── JwtVerifier                 — JWT 서명·만료 검증 (게이트웨이 독립)
│   └── PassportBuilder             — JWT 클레임 → Passport 생성 + Base64 직렬화
└── config/
    └── GatewayRoutingConfig        — 라우트 및 인증 불필요 경로 설정
```

##### JwtCookieToPassportFilter
- **타입**: Inbound Adapter (Spring Cloud Gateway `GlobalFilter`)
- **책임**: 모든 인입 요청에서 `auth_token` 쿠키를 추출해 JWT 검증 후 `X-User-Passport` 헤더를 다운스트림에 주입한다.
- **주요 메서드/함수**:
  - `Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain)`
  - `private Optional<String> extractCookie(ServerWebExchange exchange)` — `auth_token` 쿠키 추출
  - `private boolean isProtectedPath(String path)` — 인증 필요 경로 판별
  - `private Mono<Void> rejectUnauthorized(ServerWebExchange exchange)` — 401 응답
- **의존성**: `JwtVerifier`, `PassportBuilder`, `GatewayRoutingConfig`
- **적용 컨벤션**:
  - `@Component` + `@RequiredArgsConstructor` + `@Slf4j`
  - WebFlux 비동기 — `Mono<Void>` 반환, blocking 호출 금지
  - 쿠키 없음(`log.warn`) vs 서명 오류(`log.error`) 구분 로깅 — 보안 감사 목적
- **연관 todo**: `[ ] JwtCookieToPassportFilter 구현`

##### JwtVerifier
- **타입**: Component (Security)
- **책임**: 게이트웨이에서 독립적으로 JWT 서명·만료를 검증하고 클레임을 추출한다. `auth-core`에 의존하지 않는 독립 구현.
- **주요 메서드/함수**:
  - `Claims verify(String jwt)` — 서명·만료 검증 후 Claims 반환; 실패 시 `JwtException` throw
  - `private SecretKey buildKey()` — `JWT_SECRET` 환경변수에서 HMAC-SHA256 키 생성
- **의존성**: `jjwt-api`, `@Value("${JWT_SECRET}")`
- **적용 컨벤션**:
  - `@Component` + `@RequiredArgsConstructor`
  - 비밀키 외부화 (springboot-security SKILL §Secrets Management)
- **연관 todo**: `[ ] JwtVerifier 구현`

##### PassportBuilder
- **타입**: Component (Security)
- **책임**: JWT 클레임에서 `Passport` 객체를 생성하고 Base64 인코딩된 JSON으로 직렬화한다. 클레임 필드명과 Passport 필드명이 동일(`loginId`)하므로 추가 매핑 로직이 불필요하다.
- **주요 메서드/함수**:
  - `String buildAndSerialize(Claims claims)` — `Passport` 생성 → `ObjectMapper` JSON 직렬화 → Base64 인코딩
  - `private Passport buildPassport(Claims claims)`:
    - `memberId` ← `Long.valueOf(claims.getSubject())`
    - `loginId` ← `claims.get("loginId", String.class)` (필드명 일치, 추가 매핑 불필요)
    - `name` ← `claims.get("name", String.class)`
    - `generation` ← `claims.get("generation", Integer.class)`
    - `status` ← `claims.get("status", String.class)`
    - `roles` ← `claims.get("roles", List.class)`
    - `issuedAt`, `expiresAt` ← JWT `iat`/`exp` 변환
- **의존성**: `Passport` (auth-common-lib), `ObjectMapper`, `Claims` (jjwt)
- **적용 컨벤션**:
  - `@Component` + `@RequiredArgsConstructor`
  - `Passport` 생성 시 `roles`를 그대로 전달 — `Passport` 생성자 내부에서 `List.copyOf()` 방어적 복사 수행 (ARCHITECTURE.md §2. 불변 객체)
- **참조할 기존 코드**: `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/web/resolver/PassportArgumentResolver.java:105` — `decodePassport()` 역방향 로직 참조
- **연관 todo**: `[ ] PassportBuilder 구현`

##### GatewayRoutingConfig
- **타입**: Config
- **책임**: Spring Cloud Gateway 라우트와 인증 불필요 경로 목록을 정의한다.
- **주요 메서드/함수**:
  - `RouteLocator routes(RouteLocatorBuilder builder)` — `/api/v1/auth/**` → `auth-api` URI 라우팅
  - `List<String> permittedPaths()` — `/api/v1/auth/signup`, `/api/v1/auth/login` 목록 (JwtCookieToPassportFilter에서 참조)
- **의존성**: Spring Cloud Gateway, `@Value("${AUTH_API_URI}")`
- **적용 컨벤션**:
  - `@Configuration` + `@RequiredArgsConstructor`
  - URI 환경변수화 `${AUTH_API_URI}`
- **연관 todo**: `[ ] 라우팅 설정`

---

### 호출 흐름

#### 1. 회원 가입 시퀀스

정상 경로:
```
Client
  --POST /api/v1/auth/signup {name, loginId, password, generation, status}-->
    JwtCookieToPassportFilter (api-gateway)
      -- 인증 불필요 경로(signup) → 헤더 미설정 후 통과 -->
    MemberController.signup() (auth-api)
      -- @Valid 검증 (name 길이, loginId 정규식, password 길이, generation @Min/@Max, status @NotNull) -->
    SignupService.signup(SignupCommand(name, loginId, password, generation, status))
      1. validateLoginId(loginId)            [정규식 ^[a-zA-Z0-9\-_.]{3,19}$ 검증]
      2. validateName(name)                  [1~50자 길이 검증]
      3. validateGeneration(generation)      [1~99 범위 검증]
      4. validatePasswordPolicy(password)    [8~19자, 대소문자·숫자·특수기호 각 1자 이상 포함]
      5. MemberRepository.existsByLoginId()  [중복 확인]
      6. PasswordHasher.hash(password)       [BCrypt cost=12]
      7. Member.create(name, loginId, hashed, generation, status)
      8. MemberRepository.save(member)       [DB 저장]
    MemberController → ResponseEntity 201 Created (빈 바디)
```

예외 / 실패 경로:
```
[loginId 형식 오류 — DTO 레벨]
  @Pattern 위반 → MethodArgumentNotValidException
  → GlobalExceptionHandler.handleValidation() → 400 + VALIDATION_FAILED

[loginId 형식 오류 — 도메인 레벨]
  SignupService.validateLoginId() → 검증 실패 예외 (구현자 판단: IllegalArgumentException 또는 별도 예외)
  → GlobalExceptionHandler 400 매핑 필요

[name / generation / status 형식 오류]
  @Size / @Min / @Max / @NotNull 위반 → MethodArgumentNotValidException
  → GlobalExceptionHandler.handleValidation() → 400 + VALIDATION_FAILED

[비밀번호 정책 위반]
  SignupService.validatePasswordPolicy() → InvalidPasswordPolicyException
  → GlobalExceptionHandler.handleInvalidPasswordPolicy() → 400 + INVALID_PASSWORD_POLICY

[loginId 중복]
  SignupService: MemberRepository.existsByLoginId() == true
  → MemberAlreadyExistsException
  → GlobalExceptionHandler.handleMemberAlreadyExists() → 409 Conflict

[예상치 못한 예외]
  → GlobalExceptionHandler.handleGeneric() → 500 (log.error + 제네릭 메시지)
```

---

#### 2. 로그인 시퀀스

정상 경로:
```
Client
  --POST /api/v1/auth/login {loginId, password}-->
    JwtCookieToPassportFilter (api-gateway)
      -- 인증 불필요 경로(login) → 통과 -->
    MemberController.login() (auth-api)
      -- @Valid 검증 -->
    LoginService.login(LoginCommand(loginId, password))
      1. MemberRepository.findByLoginId(loginId)      [회원 조회]
      2. PasswordHasher.matches(password, hashed)     [비밀번호 검증]
      3. TokenIssuer.issue(member)                    [JWT 발급]
         → 클레임: sub, loginId, name, generation, status, roles(["USER"]), iat, exp
      4. LoginResult(jwtToken) 반환
    MemberController.buildAuthCookie(jwt)
      ResponseCookie: auth_token, HttpOnly=true, Secure=true,
                      SameSite=Strict, Path=/, maxAge=expirySeconds
    → ResponseEntity 200 OK (Set-Cookie 헤더, 빈 바디)
```

예외 / 실패 경로:
```
[loginId 미존재]
  LoginService: MemberRepository.findByLoginId() → Optional.empty()
  → InvalidCredentialsException (고정 메시지: "아이디 또는 비밀번호가 올바르지 않습니다")
  → GlobalExceptionHandler.handleInvalidCredentials() → 401 Unauthorized

[비밀번호 불일치]
  LoginService: PasswordHasher.matches() == false
  → InvalidCredentialsException (동일 고정 메시지 — 사용자 열거 방지)
  → GlobalExceptionHandler.handleInvalidCredentials() → 401 Unauthorized

[@Valid 오류]
  → MethodArgumentNotValidException → 400 Bad Request
```

---

#### 3. 게이트웨이 JWT 검증 → Passport 변환 시퀀스

정상 경로 (인증이 필요한 다운스트림 서비스 요청):
```
Client
  --GET /api/v1/some-service/resource (Cookie: auth_token=<jwt>)-->
    JwtCookieToPassportFilter.filter()
      1. extractCookie(exchange) → jwt 문자열 추출
      2. isProtectedPath(path)   → true
      3. JwtVerifier.verify(jwt)
           Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(jwt)
           → Claims (서명·만료 검증 완료)
      4. PassportBuilder.buildAndSerialize(claims)
           buildPassport(claims):
             memberId  ← Long.valueOf(claims.getSubject())
             loginId   ← claims.get("loginId")    [필드명 동일 — 추가 매핑 없음]
             name      ← claims.get("name")
             generation ← claims.get("generation")
             status    ← claims.get("status")
             roles     ← claims.get("roles")
           → Passport 객체
           ObjectMapper.writeValueAsString(passport) → JSON
           Base64.getEncoder().encodeToString(json.getBytes(UTF-8)) → encoded
      5. exchange.getRequest().mutate()
           .header("X-User-Passport", encoded)
           → 다운스트림 헤더 주입
    GatewayFilterChain.filter(exchange) → 다운스트림 서비스 전달
    PassportArgumentResolver (다운스트림, auth-common-lib)
      X-User-Passport 헤더 디코딩 → Passport 주입 (loginId 필드로 역직렬화)
```

예외 / 실패 경로:
```
[쿠키 없음 + 인증 필요 경로]
  extractCookie() → Optional.empty()
  isProtectedPath() == true
  → log.warn("auth_token cookie missing, path={}")
  → rejectUnauthorized() → 401 Unauthorized

[JWT 서명 오류]
  JwtVerifier.verify() → SignatureException
  → log.error("JWT signature invalid, path={}")  [쿠키 없음과 구분 로깅]
  → rejectUnauthorized() → 401

[JWT 만료]
  JwtVerifier.verify() → ExpiredJwtException
  → log.warn("JWT expired, path={}")
  → rejectUnauthorized() → 401

[쿠키 없음 + 인증 불필요 경로 (signup, login)]
  extractCookie() → Optional.empty()
  isProtectedPath() == false
  → X-User-Passport 헤더 미설정 후 체인 통과 (익명 접근 허용)
```

---

### 의존성 방향

```
[auth-api] ──→ [auth-core] ──→ [auth-common-lib]
[auth-api] ──→ [auth-infra] ──→ [auth-core]

[api-gateway] ──→ [auth-common-lib]

의존성 역전 원칙:
  auth-core 내부: UseCase → Port Interface (← auth-infra가 구현)
  auth-core는 auth-infra를 모름; auth-infra가 auth-core 포트를 구현
```

---

### `build.gradle.kts` 변경 사항 요약

| 모듈 | 추가 의존성 |
|---|---|
| `auth-core` | (선택) `compileOnly("jakarta.validation:jakarta.validation-api")` — loginId·name·generation 도메인 검증에서 Jakarta `@NotNull` 등 직접 사용 시만 |
| `auth-infra` | `implementation("org.springframework.security:spring-security-crypto")`, `implementation("io.jsonwebtoken:jjwt-api:0.12.x")`, `runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.x")`, `runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.x")`, `implementation("org.flywaydb:flyway-core")`, `runtimeOnly("org.postgresql:postgresql")`, `testImplementation("org.testcontainers:postgresql")`, `testImplementation("org.testcontainers:junit-jupiter")` |
| `auth-api` | `implementation("org.springframework.boot:spring-boot-starter-security")` (CSRF 비활성화용) |
| `api-gateway` | `implementation("io.jsonwebtoken:jjwt-api:0.12.x")`, `runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.x")`, `runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.x")` |

---

### 테스트 설계

| 테스트 클래스 | 모듈 | 유형 | 핵심 검증 |
|---|---|---|---|
| `SignupServiceTest` | auth-core | `@ExtendWith(MockitoExtension.class)` 단위 | 정상 가입, loginId 중복, loginId 형식 오류(4자 미만·특수문자), name 길이 위반, generation 음수·0, 비밀번호 정책 위반 — Fake `MemberRepository`/`PasswordHasher` |
| `LoginServiceTest` | auth-core | 단위 | 정상 로그인, loginId 없음, 비밀번호 불일치 — loginId 없음·불일치가 동일 예외 타입·메시지 반환 검증 (사용자 열거 방지) |
| `MemberRepositoryAdapterTest` | auth-infra | `@DataJpaTest` + Testcontainer | 저장 후 loginId 조회, 중복 loginId DB UNIQUE 제약 위반, generation CHECK 제약 위반(≤0), status CHECK 제약 위반 |
| `BCryptPasswordHasherAdapterTest` | auth-infra | 단위 | hash() 결과 ≠ 원문, matches() 정상 동작, 틀린 비밀번호 불일치 |
| `MemberControllerTest` | auth-api | `@WebMvcTest` | POST /signup 201·400(VALIDATION_FAILED)·400(INVALID_PASSWORD_POLICY)·409, POST /login Set-Cookie HttpOnly 속성, POST /logout Max-Age=0 쿠키 확인 |
| `AuthApiIntegrationTest` | auth-api | `@SpringBootTest` + Testcontainer | 가입 → 로그인 → 쿠키 수신 전체 흐름, loginId 중복 409 |
| `TestContainersConfig` | auth-infra / auth-api | 공통 설정 클래스 | `@DynamicPropertySource` PostgreSQL JDBC URL 주입 |
| `PassportTest` | auth-common-lib | 단위 | (변경) email → loginId 필드 갱신, generation·status 필드 추가 검증 |
| `PassportArgumentResolverTest` | auth-common-lib | 단위 | (변경) JSON 키 "email" → "loginId" 갱신 |
| `PassportAuthIntegrationTest` | auth-common-lib | 통합 | (변경) createEncodedPassport() loginId 인자 갱신 |

테스트 컨벤션:
- `@Nested` + `@DisplayName` 한글 (CONVENTION.md §5.1)
- Given-When-Then 주석 (CONVENTION.md §5.2)
- AssertJ fluent assertion (CONVENTION.md §5.3)
- 메서드명 영문 camelCase, `@DisplayName` 한글

---

### 컨벤션 준수 항목

- **네이밍**: 도메인 객체 `Member`, `MemberStatus`; 예외 `{Domain}Exception`; 어댑터 `{Target}Adapter`; 포트 `PasswordHasher`, `TokenIssuer`, `MemberRepository` — CONVENTION.md §1.2
- **의존성 주입**: `@RequiredArgsConstructor` 우선 — CONVENTION.md §2.2
- **불변성**: 도메인 객체 필드 `private final`, 컬렉션 `List.copyOf()` — CONVENTION.md §2.3
- **예외 처리**: 정적 팩토리 메서드 패턴 (`MemberAlreadyExistsException.of()`) — CONVENTION.md §3.1, §3.2
- **Javadoc**: 모든 `public` 클래스·메서드, `@param`/`@return` 필수 — CONVENTION.md §4.1
- **테스트 패턴**: `@Nested`/`@DisplayName` 한글, Given-When-Then, AssertJ — CONVENTION.md §5.1, §5.2
- **포맷팅**: Spotless + Google Java Format 1.17.0, `./gradlew spotlessCheck` — CONVENTION.md §2.1
- **DTO**: 서비스 애플리케이션 계층 DTO는 Java `record` — CONVENTION.md §8.4
- **Secrets**: `JWT_SECRET`, DB 자격증명 환경변수 외부화 — springboot-security SKILL §Secrets Management
- **트랜잭션**: 쓰기 `@Transactional`, 읽기 `@Transactional(readOnly = true)` — springboot-patterns SKILL §Production Defaults
- **Email VO 제거**: loginId는 String 그대로 사용; 도메인 레벨 정규식 검증은 `SignupService.validateLoginId()`에서 처리

---

## 체크리스트

- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (가입/로그인/게이트웨이 각각 정상·예외 경로 포함)
- [x] auth-common-lib Passport 필드명 변경 섹션 포함
- [x] JWT 클레임 확장(loginId·name·generation·status) 반영
- [x] MemberStatus AM/RM/CM/OB 4종 반영
- [x] Email VO 제거 및 loginId String 교체 전면 반영

---

## 참고

- `docs/ARCHITECTURE.md` — 모노레포 멀티모듈 구조, 모듈 의존성, 인증 흐름, Passport 필드 정의 (이번 작업에서 `email` → `loginId` 갱신 대상)
- `docs/CONVENTION.md` — 네이밍, Lombok, 불변성, 예외 처리, 테스트, 빌드 전체 컨벤션
- `docs/INFRASTRUCTURE.md` — PostgreSQL/Flyway 스키마 컨벤션, 환경 변수 목록, Testcontainer 테스트 환경
- `.claude/skills/hexagonal-architecture/SKILL.md` — 포트/어댑터 패턴, Java 패키지 구조
- `.claude/skills/springboot-security/SKILL.md` — BCrypt, HttpOnly 쿠키, CSRF 비활성화, Secrets 외부화
- `.claude/skills/springboot-patterns/SKILL.md` — Controller/Service 패턴, DTO record, GlobalExceptionHandler
- `.claude/skills/jpa-patterns/SKILL.md` — Entity 설계, Flyway, @DataJpaTest + Testcontainer
- `.claude/skills/springboot-tdd/SKILL.md` — JUnit 5, @Nested, Given-When-Then
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java` — 변경 대상 원본 (email 필드 현황 확인)
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/PassportException.java` — 정적 팩토리 예외 패턴 참조
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/web/resolver/PassportArgumentResolver.java` — X-User-Passport 헤더 소비 방식 (PassportBuilder 역방향 설계 기준)
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/config/AuthAutoConfiguration.java` — 자동 설정 패턴 참조
