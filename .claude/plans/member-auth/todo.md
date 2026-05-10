# member-auth - todo

## 메타
- **작업명**: member-auth
- **문서 타입**: todo
- **작성일**: 2026-05-10
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요
ECONO 자체 회원 도메인(Member)과 이메일/비밀번호 기반 가입·로그인 시스템을 구축한다.
이 작업은 향후 OIDC IdP(oidc-idp 작업)의 사용자 풀(end-user pool) 토대가 되므로,
엔티티 설계부터 JWT 발급·게이트웨이 Passport 변환까지 인증 흐름 전체를 완성해야 한다.
현재 `auth-api`, `auth-core`, `auth-infra` 모듈은 골격(빌드 파일 + 빈 패키지)만 존재하며, 이번이 첫 실질 구현이다.

---

## 본문

### API 작업

#### auth-api 모듈 (`services/apis/auth-api`)

- [ ] **POST /api/v1/auth/signup** 엔드포인트 추가
  - Request Body: `{ "email": string, "password": string }`
  - 성공 시 HTTP 201 Created, Body 없음 (또는 최소 응답)
  - 실패 시: 이메일 형식 오류 400, 이메일 중복 409, 비밀번호 정책 위반 400
  - 응답 Body에 이메일 존재 여부를 직접 노출하지 않는 에러 메시지 설계

- [ ] **POST /api/v1/auth/login** 엔드포인트 추가
  - Request Body: `{ "email": string, "password": string }`
  - 성공 시 HTTP 200 OK + HttpOnly JWT 쿠키 설정 (`Set-Cookie: auth_token=...; HttpOnly; Secure; SameSite=Strict; Path=/`)
  - JWT 쿠키 만료 시간은 JWT 자체 만료(exp)와 동일하게 설정
  - 실패 시 HTTP 401, 메시지는 "이메일 또는 비밀번호가 올바르지 않습니다" (사용자 열거 방지 — 이메일 없음/비번 틀림 구분 금지)
  - 응답 Body에 토큰 직접 노출 금지 (쿠키 전용)

- [ ] **전역 예외 핸들러(`@RestControllerAdvice`)** 구성
  - Bean Validation 오류(`MethodArgumentNotValidException`) → 400 + 필드별 에러 메시지
  - 커스텀 도메인 예외(`MemberAlreadyExistsException`, `InvalidCredentialsException` 등) → 적절한 HTTP 상태
  - 예상치 못한 예외 → 500, 내부 스택트레이스 외부 노출 금지

#### api-gateway 모듈 (`services/apis/api-gateway`)

- [ ] **JWT 쿠키 검증 → Passport 변환 GatewayFilter** 추가
  - 수신 요청의 `auth_token` 쿠키를 읽어 JWT 서명·만료 검증
  - 검증 성공 시 JWT 클레임에서 `memberId`, `email`, `roles` 추출 → `Passport` 객체 생성
  - Passport를 Base64 직렬화하여 다운스트림 요청 헤더 `X-User-Passport`에 주입
  - 검증 실패(토큰 없음, 서명 오류, 만료) 시 → 인증 필요 경로면 401 반환; 인증 불필요 경로면 헤더 미설정 후 통과
  - 쿠키 없음과 서명 오류를 구분하여 로깅 (보안 감사 목적)

- [ ] **라우팅 설정** (`application.yml` 또는 Java Config)
  - `auth-api` 서비스로 `/api/v1/auth/**` 라우팅 등록
  - `/api/v1/auth/signup`, `/api/v1/auth/login`은 인증 게이트 제외(permit) 처리

---

### 구현 작업

#### auth-core 모듈 — 도메인/포트/유스케이스 (`services/libs/auth-core`)

헥사고날 아키텍처 패키지 구조:
`com.econo.auth.core.{member}.{domain|application.port.in|application.port.out|application.usecase}`

- [ ] **Member 도메인 객체** (`domain.Member`) 구현
  - 필드: `id(Long)`, `email(Email VO)`, `hashedPassword(String)`, `status(MemberStatus enum: ACTIVE)`, `createdAt(LocalDateTime)`, `emailVerified(boolean, 기본값 false)`
  - 생성 팩토리 메서드 `Member.create(email, hashedPassword)` — 가입 즉시 ACTIVE
  - 불변 설계 (Lombok `@Value` 또는 수동 불변), 프레임워크 의존성 없음

- [ ] **Email 값 객체** (`domain.Email`) 구현
  - RFC 5322 기본 형식 검증 (정규식 또는 Jakarta `@Email` 기반 팩토리)
  - 동등성은 소문자 정규화 기준

- [ ] **MemberStatus enum** (`domain.MemberStatus`) 구현
  - `ACTIVE` 값 정의. 향후 `SUSPENDED`, `DELETED` 확장 가능하도록 설계

- [ ] **PasswordHasher 아웃바운드 포트** (`application.port.out.PasswordHasher`) 정의
  - `String hash(String rawPassword)`
  - `boolean matches(String rawPassword, String hashedPassword)`

- [ ] **MemberRepository 아웃바운드 포트** (`application.port.out.MemberRepository`) 정의
  - `void save(Member member)`
  - `Optional<Member> findByEmail(Email email)`
  - `boolean existsByEmail(Email email)`

- [ ] **TokenIssuer 아웃바운드 포트** (`application.port.out.TokenIssuer`) 정의
  - `String issue(Member member)` — 서명된 JWT 문자열 반환
  - JWT 클레임 계약: `sub(memberId)`, `email`, `roles`, `iat`, `exp`

- [ ] **SignupUseCase 인바운드 포트** (`application.port.in.SignupUseCase`) + **구현** 작성
  - 커맨드: `SignupCommand(email: String, password: String)`
  - 이메일 형식 검증 → `Email VO` 생성 실패 시 예외
  - 비밀번호 정책 검증 (최소 8자, 영문+숫자 포함 등 합리적 기본값)
  - 이메일 중복 체크 → `MemberAlreadyExistsException`
  - `PasswordHasher.hash()` 호출 → `Member.create()` → `MemberRepository.save()`

- [ ] **LoginUseCase 인바운드 포트** (`application.port.in.LoginUseCase`) + **구현** 작성
  - 커맨드: `LoginCommand(email: String, password: String)`
  - `MemberRepository.findByEmail()` — 존재하지 않으면 `InvalidCredentialsException` (동일 메시지)
  - `PasswordHasher.matches()` — 불일치 시 `InvalidCredentialsException` (동일 메시지, 열거 방지)
  - 성공 시 `TokenIssuer.issue(member)` 호출 → JWT 반환
  - 결과: `LoginResult(jwtToken: String)`

- [ ] **커스텀 도메인 예외** 정의
  - `MemberAlreadyExistsException` (HTTP 409 매핑용)
  - `InvalidCredentialsException` (HTTP 401 매핑용)
  - `InvalidPasswordPolicyException` (HTTP 400 매핑용)

#### auth-infra 모듈 — 어댑터 구현 (`services/libs/auth-infra`)

- [ ] **MemberJpaEntity** (`adapter.out.persistence.MemberJpaEntity`) 구현
  - `@Entity`, `@Table(name = "members")`
  - `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate`
  - 도메인 `Member` ↔ JPA 엔티티 변환 메서드 (정적 팩토리 `from(Member)`, `toDomain()`)

- [ ] **MemberJpaRepository** (Spring Data, `adapter.out.persistence`) 구현
  - `Optional<MemberJpaEntity> findByEmail(String email)`
  - `boolean existsByEmail(String email)`

- [ ] **MemberRepositoryAdapter** (아웃바운드 포트 구현체) 구현
  - `MemberRepository` 포트 구현
  - `MemberJpaRepository` 위임

- [ ] **BCryptPasswordHasherAdapter** (아웃바운드 포트 구현체) 구현
  - `PasswordHasher` 포트 구현
  - `BCryptPasswordEncoder(cost=12)` 사용 (Spring Security Crypto 의존성 추가)
  - Spring Security 풀 스택 미사용 — Crypto 모듈만 의존

- [ ] **JwtTokenIssuerAdapter** (아웃바운드 포트 구현체) 구현
  - `TokenIssuer` 포트 구현
  - `jjwt` 라이브러리(또는 `nimbus-jose-jwt`) 사용하여 HMAC-SHA256 서명
  - JWT 만료 시간: 설정값(`auth.jwt.expiry-seconds`)에서 주입
  - 비밀키: 환경 변수 `JWT_SECRET`에서 주입 (하드코딩 금지)

- [ ] **JpaAuditingConfig** (`config.JpaAuditingConfig`) — `@EnableJpaAuditing` 설정 클래스 추가

#### auth-api 모듈 — 인바운드 어댑터 (`services/apis/auth-api`)

- [ ] **MemberController** (`adapter.in.web.MemberController`) 구현
  - `POST /api/v1/auth/signup` 핸들러 — `SignupUseCase` 위임
  - `POST /api/v1/auth/login` 핸들러 — `LoginUseCase` 위임 → JWT를 HttpOnly 쿠키로 응답에 설정
  - 쿠키 설정: `ResponseCookie.from("auth_token", jwt).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(expirySeconds).build()`

- [ ] **요청 DTO** 구현
  - `SignupRequest(email: @NotBlank @Email String, password: @NotBlank @Size(min=8) String)` — Java record
  - `LoginRequest(email: @NotBlank String, password: @NotBlank String)` — Java record

- [ ] **AuthApiApplication Spring Boot 자동 설정 정비**
  - `@SpringBootApplication(scanBasePackages = ...)` 패키지 범위 명확화
  - `auth-core`, `auth-infra` 모듈 빈이 올바르게 스캔되도록 설정

#### api-gateway 모듈 — 필터 구현 (`services/apis/api-gateway`)

- [ ] **JwtCookieToPassportFilter** (`filter.JwtCookieToPassportFilter`) 구현
  - WebFlux `GlobalFilter` 또는 `GatewayFilter` 구현 (Spring Cloud Gateway)
  - `auth_token` 쿠키 파싱 → JWT 검증(서명, 만료) → Passport 생성 → Base64 직렬화 → `X-User-Passport` 헤더 주입
  - JWT 검증 실패 시 인증 필요 경로는 401, 비필수 경로는 통과

- [ ] **JwtVerifier** (`security.JwtVerifier`) 구현 (게이트웨이용)
  - 게이트웨이는 `auth-core` 모듈에 의존하지 않으므로 독립적으로 JWT 파싱 구현
  - `jjwt` 또는 `nimbus-jose-jwt` 의존성 추가
  - `auth-core`와 동일한 `JWT_SECRET` 환경 변수 참조

- [ ] **PassportBuilder** (`security.PassportBuilder`) 구현 (게이트웨이용)
  - JWT 클레임 → `Passport` 객체 생성 (이미 존재하는 `auth-common-lib`의 `Passport` 클래스 활용)
  - `Passport`를 ObjectMapper로 직렬화 → Base64 인코딩

---

### DB 작업

- [ ] **`members` 테이블 마이그레이션 스크립트** 작성 (`V1__create_members_table.sql`)
  - 마이그레이션 툴: Flyway (auth-infra 모듈에 `flyway-core` 의존성 추가)
  - 위치: `services/libs/auth-infra/src/main/resources/db/migration/`
  - 컬럼:
    - `id BIGSERIAL PRIMARY KEY`
    - `email VARCHAR(320) NOT NULL`
    - `hashed_password VARCHAR(72) NOT NULL` (BCrypt 출력 최대 60자이지만 여유 확보)
    - `status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'`
    - `email_verified BOOLEAN NOT NULL DEFAULT FALSE`
    - `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  - 제약:
    - `UNIQUE (email)` — `uq_members_email`
    - `CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED'))` — 향후 확장값 예약

- [ ] **`members.email` 인덱스** 추가
  - 로그인 시 이메일로 단건 조회가 빈번하므로 unique constraint가 곧 인덱스로 작동하나,
    명시적 인덱스 이름 `idx_members_email` 확인

- [ ] **테스트용 DB 컨테이너 설정** (`TestContainersConfig.java`)
  - PostgreSQL Testcontainer 설정 (`@DynamicPropertySource`로 JDBC URL 주입)
  - `auth-infra`, `auth-api` 통합 테스트에서 재사용 가능한 공통 config 클래스

---

### 기타 작업

#### 의존성 추가

- [ ] **auth-core `build.gradle.kts`** 의존성 추가
  - 없음 (순수 Java 도메인; 프레임워크 의존 최소화). `auth-common-lib` api 의존이 이미 있으므로 Jakarta Validation만 필요 시 추가

- [ ] **auth-infra `build.gradle.kts`** 의존성 추가
  - `implementation("org.springframework.boot:spring-boot-starter-security")` — Crypto 모듈용 (또는 `spring-security-crypto`만 단독 추가)
  - `implementation("io.jsonwebtoken:jjwt-api:0.12.x")` + `runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.x")` + `runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.x")`
  - `implementation("org.flywaydb:flyway-core")`
  - `runtimeOnly("org.postgresql:postgresql")`
  - `testImplementation("org.testcontainers:postgresql")`
  - `testImplementation("org.testcontainers:junit-jupiter")`

- [ ] **auth-api `build.gradle.kts`** 의존성 추가
  - `spring-boot-starter-security` (CSRF 비활성화 + SecurityFilterChain 설정 목적, 실제 Spring Security 인증 체인은 미사용)
  - 또는 Security 미사용 시 CORS/쿠키 설정 전용 `WebMvcConfigurer`로 대체 — designer 판단

- [ ] **api-gateway `build.gradle.kts`** 의존성 추가
  - `implementation("io.jsonwebtoken:jjwt-api:0.12.x")` + `runtimeOnly` 세트
  - `implementation(project(":services:libs:auth-common-lib"))` — 이미 존재

#### 설정 파일

- [ ] **`auth-api/src/main/resources/application.yml`** 작성
  - `spring.datasource.*` — PostgreSQL 연결 (환경변수 `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`)
  - `spring.jpa.hibernate.ddl-auto=validate`
  - `spring.flyway.enabled=true`
  - `auth.jwt.expiry-seconds: 3600` (기본값 1시간, 환경변수 오버라이드 가능)
  - `auth.jwt.cookie-name: auth_token`

- [ ] **`auth-api/src/main/resources/application-test.yml`** 작성
  - Testcontainer로 대체할 datasource (비워 두거나 H2 대신 Testcontainer 자동 설정)
  - `spring.flyway.enabled=true`

- [ ] **`api-gateway/src/main/resources/application.yml`** 작성
  - Spring Cloud Gateway 라우트 설정 (`auth-api` 서비스 URI 환경변수화)
  - `AUTH_JWT_SECRET` 환경변수 참조 설정

#### 환경 변수 문서화

- [ ] 필요한 환경 변수 목록 정리 (`.env.example` 또는 README 섹션)
  - `JWT_SECRET` — JWT 서명 비밀키 (최소 256bit)
  - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — auth-api DB 연결
  - `AUTH_API_URI` — 게이트웨이가 라우팅할 auth-api 주소

#### 테스트

- [ ] **SignupUseCase 단위 테스트** (`auth-core`)
  - Given-When-Then, `@Nested` + `@DisplayName` 한글
  - 정상 가입, 이메일 중복, 이메일 형식 오류, 비밀번호 정책 위반 케이스
  - `MemberRepository`, `PasswordHasher` fake(stub) 구현으로 격리

- [ ] **LoginUseCase 단위 테스트** (`auth-core`)
  - 정상 로그인, 이메일 없음, 비밀번호 불일치 케이스
  - 이메일 없음과 비밀번호 불일치가 동일 예외 타입·메시지를 반환하는지 검증 (열거 방지)

- [ ] **MemberRepositoryAdapter 통합 테스트** (`auth-infra`, `@DataJpaTest` + Testcontainer)
  - 저장 후 이메일 조회, 중복 이메일 저장 시 DB 제약 위반 확인

- [ ] **BCryptPasswordHasherAdapter 단위 테스트** (`auth-infra`)
  - `hash()` 결과가 원문과 다름, `matches()` 정상 동작, 틀린 비밀번호 불일치 확인

- [ ] **MemberController 웹 레이어 테스트** (`auth-api`, `@WebMvcTest`)
  - `POST /api/v1/auth/signup` 요청/응답 검증 (201, 400, 409)
  - `POST /api/v1/auth/login` 응답에 `Set-Cookie` 헤더 포함 여부 및 `HttpOnly` 속성 확인

- [ ] **회원 가입·로그인 통합 테스트** (`auth-api`, `@SpringBootTest` + Testcontainer)
  - 가입 → 로그인 → 쿠키 수신 전체 흐름 검증

#### 코드 품질

- [ ] Spotless 포맷팅 준수 확인 (`./gradlew spotlessCheck`)
  - Google Java Format 1.17.0, Tab indent, trailing whitespace 제거, unused import 제거

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java` — 게이트웨이가 빌드할 Passport 객체 구조
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/web/resolver/PassportArgumentResolver.java` — `X-User-Passport` 헤더 소비 방식 (Base64 + JSON)
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/config/AuthAutoConfiguration.java` — auth-api가 자동으로 PassportArgumentResolver를 등록하는 방식
- `.claude/skills/hexagonal-architecture/SKILL.md` — 헥사고날 패키지 구조 및 포트/어댑터 설계 원칙
- `.claude/skills/springboot-security/SKILL.md` — BCrypt, HttpOnly 쿠키, CSRF 비활성화, 비밀키 외부화 패턴
- `.claude/skills/springboot-tdd/SKILL.md` — JUnit 5, `@Nested`/`@DisplayName` 한글, Given-When-Then 패턴
- `.claude/skills/jpa-patterns/SKILL.md` — `@Entity` 설계, Flyway 마이그레이션, `@DataJpaTest` + Testcontainer
- `build.gradle.kts` — Spring Boot 3.2.2, Java 21, Spotless Google Java Format 1.17.0 확인
