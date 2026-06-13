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
ECONO 자체 회원 도메인(Member)과 loginId/password 기반 가입·로그인 시스템을 구축한다.
이 작업은 향후 OIDC IdP(oidc-idp 작업)의 사용자 풀(end-user pool) 토대가 되므로,
엔티티 설계부터 JWT 발급·게이트웨이 Passport 변환까지 인증 흐름 전체를 완성해야 한다.
현재 `auth-api`, `auth-core`, `auth-infra` 모듈은 골격(빌드 파일 + 빈 패키지)만 존재하며, 이번이 첫 실질 구현이다.

---

## 본문

### API 작업

#### auth-api 모듈 (`services/apis/auth-api`)

- [ ] **POST /api/v1/auth/signup** 엔드포인트 추가
  - Request Body: `{ "name": string, "loginId": string, "password": string, "generation": int, "status": string }`
  - 5개 필드 모두 사용자 입력 (서버 기본값 없음)
  - 성공 시 HTTP 201 Created, Body 없음
  - 실패 시:
    - `VALIDATION_FAILED` (400) — name·loginId·generation·status 형식 위반, 필수 필드 누락
    - `MEMBER_ALREADY_EXISTS` (409) — loginId 중복
    - `INVALID_PASSWORD_POLICY` (400) — 비밀번호 정책 위반

- [ ] **POST /api/v1/auth/login** 엔드포인트 추가
  - Request Body: `{ "loginId": string, "password": string }` (email 필드 제거)
  - 성공 시 HTTP 200 OK + HttpOnly JWT 쿠키 설정 (`Set-Cookie: auth_token=...; HttpOnly; Secure; SameSite=Strict; Path=/`)
  - JWT 쿠키 만료 시간은 JWT 자체 만료(exp)와 동일하게 설정
  - 실패 시 HTTP 401, 메시지는 "아이디 또는 비밀번호가 올바르지 않습니다" (사용자 열거 방지 — loginId 미존재/비밀번호 불일치 동일 응답)
  - 응답 Body에 토큰 직접 노출 금지 (쿠키 전용)

- [ ] **POST /api/v1/auth/logout** 엔드포인트 추가
  - 성공 시 `auth_token` 쿠키를 Max-Age=0으로 만료 처리
  - HTTP 200 OK 반환

- [ ] **전역 예외 핸들러(`@RestControllerAdvice`)** 구성
  - Bean Validation 오류(`MethodArgumentNotValidException`) → 400 + `VALIDATION_FAILED` 에러 코드 + 필드별 메시지
  - `MemberAlreadyExistsException` → 409 + `MEMBER_ALREADY_EXISTS`
  - `InvalidPasswordPolicyException` → 400 + `INVALID_PASSWORD_POLICY`
  - `InvalidCredentialsException` → 401
  - 예상치 못한 예외 → 500, 내부 스택트레이스 외부 노출 금지

#### api-gateway 모듈 (`services/apis/api-gateway`)

- [ ] **JWT 쿠키 검증 → Passport 변환 GatewayFilter** 추가
  - 수신 요청의 `auth_token` 쿠키를 읽어 JWT 서명·만료 검증
  - 검증 성공 시 JWT 클레임에서 `memberId`, `loginId`, `name`, `generation`, `status`, `roles` 추출 → `Passport` 객체 생성
  - Passport를 Base64 직렬화하여 다운스트림 요청 헤더 `X-User-Passport`에 주입
  - 검증 실패(토큰 없음, 서명 오류, 만료) 시 → 인증 필요 경로는 401 반환; 인증 불필요 경로는 헤더 미설정 후 통과
  - 쿠키 없음과 서명 오류를 구분하여 로깅 (보안 감사 목적)

- [ ] **라우팅 설정** (`application.yml` 또는 Java Config)
  - `auth-api` 서비스로 `/api/v1/auth/**` 라우팅 등록
  - `/api/v1/auth/signup`, `/api/v1/auth/login`은 인증 게이트 제외(permit) 처리

---

### 구현 작업

#### auth-common-lib — Passport 필드명 변경 (`services/libs/auth-common-lib`)

- [ ] **`Passport.java` 필드 변경** (`src/main/java/com/econo/common/auth/core/passport/Passport.java`)
  - 필드 `email` → `loginId` (이름 변경, `@JsonProperty("loginId")`, getter `getLoginId()`, 생성자 파라미터 동시 변경)
  - **신규 필드 추가**: `generation(Integer)`, `status(String)` — 다운스트림이 매 요청 DB 조회 안 해도 되게 JWT 클레임을 그대로 미러
  - 새 필드도 `@JsonProperty`, `@NotNull` 등 기존 패턴 따라 선언 + getter 추가
  - 생성자 시그니처: `Passport(memberId, loginId, name, generation, status, roles, issuedAt, expiresAt)`

#### auth-core 모듈 — 도메인/포트/유스케이스 (`services/libs/auth-core`)

헥사고날 아키텍처 패키지 구조:
`com.econo.auth.core.{member}.{domain|application.port.in|application.port.out|application.usecase}`

- [ ] **Member 도메인 객체** (`domain.Member`) 구현
  - 필드: `id(Long)`, `name(String)`, `loginId(String)`, `hashedPassword(String)`, `generation(Integer)`, `status(MemberStatus)`, `createdAt(LocalDateTime)`
  - 생성 팩토리 메서드 `Member.create(name, loginId, hashedPassword, generation, status)` — createdAt은 생성 시 주입
  - 불변 설계 (`private final` 필드, Lombok `@Getter`), 프레임워크 의존성 없음

- [ ] **MemberStatus enum** (`domain.MemberStatus`) 구현
  - 값: `AM`, `RM`, `CM`, `OB` (4개, 기본값 없음)
  - 코드/문서에는 약자만 사용 (의미 해설 주석 금지)

- [ ] **PasswordHasher 아웃바운드 포트** (`application.port.out.PasswordHasher`) 정의
  - `String hash(String rawPassword)`
  - `boolean matches(String rawPassword, String hashedPassword)`

- [ ] **MemberRepository 아웃바운드 포트** (`application.port.out.MemberRepository`) 정의
  - `void save(Member member)`
  - `Optional<Member> findByLoginId(String loginId)`
  - `boolean existsByLoginId(String loginId)`

- [ ] **TokenIssuer 아웃바운드 포트** (`application.port.out.TokenIssuer`) 정의
  - `String issue(Member member)` — 서명된 JWT 문자열 반환
  - JWT 클레임 계약: `sub(memberId)`, `loginId`, `name`, `generation`, `status`, `roles`, `iat`, `exp`
  - `roles`는 항상 `["USER"]` 고정 (status는 활동 상태이지 권한이 아님)

- [ ] **SignupUseCase 인바운드 포트** (`application.port.in.SignupUseCase`) + **구현** 작성
  - 커맨드: `SignupCommand(name: String, loginId: String, password: String, generation: Integer, status: MemberStatus)`
  - loginId 정규식 검증 (3~19자, 영숫자·`-_.`만 허용)
  - name 길이 검증 (1~50자)
  - generation 범위 검증 (1~99)
  - 비밀번호 정책 검증 (8~19자, **대소문자·숫자·특수기호 모두 포함**)
  - loginId 중복 체크 → `MemberAlreadyExistsException`
  - `PasswordHasher.hash()` 호출 → `Member.create()` → `MemberRepository.save()`

- [ ] **LoginUseCase 인바운드 포트** (`application.port.in.LoginUseCase`) + **구현** 작성
  - 커맨드: `LoginCommand(loginId: String, password: String)`
  - `MemberRepository.findByLoginId()` — 존재하지 않으면 `InvalidCredentialsException` (동일 메시지)
  - `PasswordHasher.matches()` — 불일치 시 `InvalidCredentialsException` (동일 메시지, 열거 방지)
  - 성공 시 `TokenIssuer.issue(member)` 호출 → JWT 반환
  - 결과: `LoginResult(jwtToken: String)`

- [ ] **커스텀 도메인 예외** 정의
  - `MemberAlreadyExistsException` (HTTP 409, `MEMBER_ALREADY_EXISTS` 에러 코드)
  - `InvalidCredentialsException` (HTTP 401)
  - `InvalidPasswordPolicyException` (HTTP 400, `INVALID_PASSWORD_POLICY` 에러 코드)

#### auth-infra 모듈 — 어댑터 구현 (`services/libs/auth-infra`)

- [ ] **MemberJpaEntity** (`adapter.out.persistence.MemberJpaEntity`) 구현
  - `@Entity`, `@Table(name = "members")`
  - `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate`로 `createdAt` 자동 주입
  - 컬럼 매핑: `login_id`, `hashed_password`, `generation`, `status`(VARCHAR, enum name 저장)
  - 도메인 `Member` ↔ JPA 엔티티 변환 메서드 (정적 팩토리 `from(Member)`, `toDomain()`)

- [ ] **MemberJpaRepository** (Spring Data, `adapter.out.persistence`) 구현
  - `Optional<MemberJpaEntity> findByLoginId(String loginId)`
  - `boolean existsByLoginId(String loginId)`

- [ ] **MemberRepositoryAdapter** (아웃바운드 포트 구현체) 구현
  - `MemberRepository` 포트 구현
  - `MemberJpaRepository` 위임

- [ ] **BCryptPasswordHasherAdapter** (아웃바운드 포트 구현체) 구현
  - `PasswordHasher` 포트 구현
  - `BCryptPasswordEncoder(cost=12)` 사용 (`spring-security-crypto` 의존성 단독 추가)
  - Spring Security 풀 스택 미사용 — Crypto 모듈만 의존

- [ ] **JwtTokenIssuerAdapter** (아웃바운드 포트 구현체) 구현
  - `TokenIssuer` 포트 구현
  - `jjwt` 라이브러리 사용하여 HMAC-SHA256 서명
  - JWT 클레임: `sub(memberId)`, `loginId`, `name`, `generation`, `status`, `roles(["USER"])`, `iat`, `exp`
  - JWT 만료 시간: 설정값(`auth.jwt.expiry-seconds`)에서 주입
  - 비밀키: 환경 변수 `JWT_SECRET`에서 주입 (하드코딩 금지)

- [ ] **JpaAuditingConfig** (`config.JpaAuditingConfig`) — `@EnableJpaAuditing` 설정 클래스 추가

#### auth-api 모듈 — 인바운드 어댑터 (`services/apis/auth-api`)

- [ ] **MemberController** (`adapter.in.web.MemberController`) 구현
  - `POST /api/v1/auth/signup` 핸들러 — `SignupUseCase` 위임, 201 반환
  - `POST /api/v1/auth/login` 핸들러 — `LoginUseCase` 위임 → JWT를 HttpOnly 쿠키로 응답에 설정
  - `POST /api/v1/auth/logout` 핸들러 — `auth_token` 쿠키 Max-Age=0 만료
  - 쿠키 설정: `ResponseCookie.from("auth_token", jwt).httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(expirySeconds).build()`

- [ ] **요청 DTO** 구현 (Java record)
  - `SignupRequest`: `@NotBlank String name`, `@NotBlank String loginId`, `@NotBlank String password`, `@NotNull Integer generation`, `@NotNull MemberStatus status`
  - `LoginRequest`: `@NotBlank String loginId`, `@NotBlank String password`
  - loginId 정규식 Bean Validation: `@Pattern(regexp = "^[a-zA-Z0-9\\-_.]{3,19}$")`
  - name 길이 검증: `@Size(min = 1, max = 50)`
  - password 길이 검증: `@Size(min = 8, max = 19)` (대소문자·숫자·특수기호 포함 정책은 UseCase 수준 `validatePasswordPolicy()`에서 검증)
  - generation 범위 검증: `@Min(1) @Max(99)`

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
  - `jjwt` 의존성 추가
  - `auth-core`와 동일한 `JWT_SECRET` 환경 변수 참조

- [ ] **PassportBuilder** (`security.PassportBuilder`) 구현 (게이트웨이용)
  - JWT 클레임 → `Passport` 객체 생성 (`auth-common-lib`의 `Passport` 클래스 활용)
  - JWT 클레임 `loginId` → `Passport.loginId` (필드명이 동일하므로 추가 매핑 로직 불필요)
  - `Passport.name` ← `name`
  - `Passport.roles` ← `["USER"]` 고정
  - Passport를 ObjectMapper로 직렬화 → Base64 인코딩

---

### DB 작업

- [ ] **`members` 테이블 마이그레이션 스크립트** 작성 (`V1__create_members_table.sql`)
  - 마이그레이션 툴: Flyway
  - 위치: `services/libs/auth-infra/src/main/resources/db/migration/`
  - 파일명 컨벤션 준수: `V{version}__{description}.sql` (대문자 V, 언더스코어 두 개)
  - 컬럼:
    - `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` (PostgreSQL 표준 SQL, BIGSERIAL 대신 사용)
    - `name VARCHAR(50) NOT NULL`
    - `login_id VARCHAR(20) NOT NULL`
    - `hashed_password VARCHAR(72) NOT NULL`
    - `generation INTEGER NOT NULL`
    - `status VARCHAR(2) NOT NULL`
    - `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  - 제약:
    - `UNIQUE (login_id)` — `uq_members_login_id`
    - `CHECK (generation BETWEEN 1 AND 99)` — `chk_members_generation`
    - `CHECK (status IN ('AM', 'RM', 'CM', 'OB'))` — `chk_members_status`

- [ ] **`members.login_id` 인덱스** 확인
  - UNIQUE 제약이 자동으로 인덱스를 생성하므로 별도 `CREATE INDEX` 불필요
  - 마이그레이션 스크립트에서 인덱스 이름 `uq_members_login_id`가 명시적으로 선언됐는지 확인

- [ ] **테스트용 DB 컨테이너 설정** (`TestContainersConfig.java`)
  - PostgreSQL Testcontainer 설정 (`@DynamicPropertySource`로 JDBC URL 주입)
  - Flyway 테스트에서도 자동 실행되어 스키마를 운영과 동일하게 유지
  - `auth-infra`, `auth-api` 통합 테스트에서 재사용 가능한 공통 config 클래스 (Gradle `testFixtures` 또는 모듈별 복사 방식 중 결정)

---

### 기타 작업

#### auth-common-lib 테스트 갱신 (`services/libs/auth-common-lib/src/test/...`)

- [ ] **`PassportTest.java` 갱신**
  - Passport 인스턴스 생성 코드의 `email` 파라미터 → `loginId`로 변경
  - `getEmail()` 호출 → `getLoginId()`로 변경
  - getter 반환값 검증 assert 문도 동일하게 갱신

- [ ] **`PassportExceptionTest.java` 갱신** (필요 시)
  - Passport 인스턴스를 직접 생성하거나 `email` 필드를 참조하는 부분이 있으면 `loginId`로 갱신

- [ ] **`web/resolver/PassportArgumentResolverTest.java` 갱신**
  - Passport 직렬화/역직렬화 테스트에서 JSON 키 `"email"` → `"loginId"`로 변경
  - `getEmail()` 호출 → `getLoginId()`로 변경

- [ ] **`integration/PassportAuthIntegrationTest.java` 갱신**
  - JSON 페이로드 및 검증 부분의 `"email"` → `"loginId"`로 변경

#### 문서 갱신

- [ ] **루트 `README.md` 갱신**
  - "기본 정보 접근" 섹션 등 사용 예시에서 `passport.getEmail()` → `passport.getLoginId()`, `passport.email` → `passport.loginId` 전수 변경
  - API 레퍼런스의 Passport 필드 목록에서 `email` → `loginId` 갱신

- [ ] **`docs/ARCHITECTURE.md` 갱신**
  - Passport 필드 나열 부분(`memberId`, `email`, `name`, `roles`, `issuedAt`, `expiresAt`)에서 `email` → `loginId` 변경

- [ ] **`services/libs/auth-common-lib/README.md` 갱신**
  - 비즈니스 규칙·코드 진입점 섹션에서 Passport 필드명을 직접 명시한 부분 확인 후 `email` → `loginId` 갱신

#### 의존성 추가

- [ ] **auth-core `build.gradle.kts`** 의존성 확인
  - `auth-common-lib` api 의존이 이미 있으므로 추가 의존성 최소화
  - Jakarta Bean Validation (`jakarta.validation:jakarta.validation-api`) — loginId·name·generation 도메인 검증에서 직접 사용 시만 추가

- [ ] **auth-infra `build.gradle.kts`** 의존성 추가
  - `implementation("org.springframework.security:spring-security-crypto")` (BCrypt용, 풀 스택 아님)
  - `implementation("io.jsonwebtoken:jjwt-api:0.12.x")` + `runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.x")` + `runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.x")`
  - `implementation("org.flywaydb:flyway-core")`
  - `runtimeOnly("org.postgresql:postgresql")`
  - `testImplementation("org.testcontainers:postgresql")`
  - `testImplementation("org.testcontainers:junit-jupiter")`

- [ ] **auth-api `build.gradle.kts`** 의존성 확인
  - Spring Security 풀 스택 미사용 시 CSRF 비활성화는 `WebMvcConfigurer` 또는 별도 설정으로 처리
  - 쿠키/CORS 설정만 필요한 경우 Security 의존성 없이 진행 가능 — designer 판단

- [ ] **api-gateway `build.gradle.kts`** 의존성 추가
  - `implementation("io.jsonwebtoken:jjwt-api:0.12.x")` + `runtimeOnly` 세트
  - `implementation(project(":services:libs:auth-common-lib"))` — 이미 존재 여부 확인

#### 설정 파일

- [ ] **`auth-api/src/main/resources/application.yml`** 작성
  - `spring.datasource.*` — PostgreSQL 연결 (`${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`)
  - `spring.jpa.hibernate.ddl-auto=validate`
  - `spring.flyway.enabled=true`
  - `auth.jwt.expiry-seconds: 3600` (기본값 1시간, 환경변수 오버라이드 가능)
  - `auth.jwt.cookie-name: auth_token`

- [ ] **`auth-api/src/main/resources/application-test.yml`** 작성
  - Testcontainer로 대체할 datasource (빈 값 또는 Testcontainer `@DynamicPropertySource` 자동 설정에 위임)
  - `spring.flyway.enabled=true`

- [ ] **`api-gateway/src/main/resources/application.yml`** 작성
  - Spring Cloud Gateway 라우트 설정 (auth-api 서비스 URI 환경변수화)
  - `JWT_SECRET` 환경변수 참조 설정
  - `/api/v1/auth/signup`, `/api/v1/auth/login` permit 경로 설정

#### 환경 변수 문서화

- [ ] 필요한 환경 변수 목록 정리 (`.env.example` 또는 `docs/INFRASTRUCTURE.md` 환경 변수 섹션 보완)
  - `JWT_SECRET` — JWT 서명 비밀키 (최소 256bit, 양 서버에서 동일 값 사용)
  - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` — auth-api DB 연결 (`docs/INFRASTRUCTURE.md` 기존 정의 확인)
  - `AUTH_API_URI` — 게이트웨이가 라우팅할 auth-api 주소

#### 테스트

- [ ] **SignupUseCase 단위 테스트** (`auth-core`)
  - Given-When-Then, `@Nested` + `@DisplayName` 한글
  - 정상 가입, loginId 중복, loginId 형식 오류(3자 미만/20자 이상/허용 외 문자), name 길이 위반, generation 범위 외(0·100 등), 비밀번호 정책 위반(짧음/대문자 누락/특수기호 누락 등) 케이스
  - `MemberRepository`, `PasswordHasher` fake(stub) 구현으로 격리

- [ ] **LoginUseCase 단위 테스트** (`auth-core`)
  - 정상 로그인, loginId 없음, 비밀번호 불일치 케이스
  - loginId 없음과 비밀번호 불일치가 동일 예외 타입·메시지를 반환하는지 검증 (사용자 열거 방지)

- [ ] **MemberRepositoryAdapter 통합 테스트** (`auth-infra`, `@DataJpaTest` + Testcontainer)
  - 저장 후 loginId 조회 정상 동작 확인
  - 중복 loginId 저장 시 DB UNIQUE 제약 위반 확인
  - CHECK 제약 위반(generation=0, 잘못된 status) 시 예외 확인

- [ ] **BCryptPasswordHasherAdapter 단위 테스트** (`auth-infra`)
  - `hash()` 결과가 원문과 다름, `matches()` 정상 동작, 틀린 비밀번호 불일치 확인

- [ ] **MemberController 웹 레이어 테스트** (`auth-api`, `@WebMvcTest`)
  - `POST /api/v1/auth/signup` 요청/응답 검증 (201, 400 VALIDATION_FAILED, 400 INVALID_PASSWORD_POLICY, 409 MEMBER_ALREADY_EXISTS)
  - `POST /api/v1/auth/login` 응답에 `Set-Cookie` 헤더 포함 여부 및 `HttpOnly` 속성 확인
  - `POST /api/v1/auth/logout` 응답에서 `auth_token` 쿠키 Max-Age=0 확인

- [ ] **회원 가입·로그인 통합 테스트** (`auth-api`, `@SpringBootTest` + Testcontainer)
  - 가입 → 로그인 → 쿠키 수신 전체 흐름 검증
  - loginId 중복 가입 시도 409 응답 검증

#### 코드 품질

- [ ] Spotless 포맷팅 준수 확인 (`./gradlew spotlessCheck`)
  - Google Java Format 1.17.0, 탭 들여쓰기, trailing whitespace 제거, unused import 제거

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고
- `docs/ARCHITECTURE.md` — 모듈 구조, 헥사고날 패키지 컨벤션, Passport 필드 정의 (이번 작업에서 `email` → `loginId` 갱신 대상)
- `docs/CONVENTION.md` — 코드 스타일, 테스트 컨벤션, Lombok·불변 설계 규칙
- `docs/INFRASTRUCTURE.md` — PostgreSQL/Flyway 스키마 컨벤션, 환경 변수 목록, Testcontainer 테스트 환경
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java` — `email` 필드를 `loginId`로 이름 변경 (이번 작업 범위에 포함)
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/web/resolver/PassportArgumentResolver.java` — `X-User-Passport` 헤더 소비 방식 (Base64 + JSON)
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/config/AuthAutoConfiguration.java` — PassportArgumentResolver 자동 등록 방식
- `.claude/skills/hexagonal-architecture/SKILL.md` — 헥사고날 패키지 구조 및 포트/어댑터 설계 원칙
- `.claude/skills/springboot-security/SKILL.md` — BCrypt, HttpOnly 쿠키, CSRF 비활성화, 비밀키 외부화 패턴
- `.claude/skills/springboot-tdd/SKILL.md` — JUnit 5, `@Nested`/`@DisplayName` 한글, Given-When-Then 패턴
- `.claude/skills/jpa-patterns/SKILL.md` — `@Entity` 설계, Flyway 마이그레이션, `@DataJpaTest` + Testcontainer
- `build.gradle.kts` — Spring Boot 3.2.2, Java 21, Spotless Google Java Format 1.17.0 확인
- **호환성 메모**: auth-common-lib는 외부 배포 라이브러리(JitPack/GitHub Packages)이나, v1.0.0 현 시점에서 외부 사용자 호환성 고려 없이 `loginId`로 단순 변경. 호환성 가이드·마이그레이션 문서는 별도 작성하지 않음.
