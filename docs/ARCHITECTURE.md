# Architecture

auth-common의 아키텍처 문서.

## 개요

auth-common은 **모노레포 멀티모듈 프로젝트**로, Passport 기반 인증 서버와 API Gateway를 포함하는 통합 레포지토리이다.

- **API Gateway**가 클라이언트 요청을 수신하고, SAS 발급 JWT(Bearer 토큰)를 RS256 검증 후 Passport로 변환하여 다운스트림으로 전달한다.
- **Auth 서버(auth-api)**는 Spring Authorization Server(SAS 1.x) 기반 OIDC Authorization Server이다. 회원 가입·로그아웃 API와 함께 `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/userinfo`, `/.well-known/openid-configuration` 표준 엔드포인트를 제공한다.
- **auth-common-lib**는 다른 마이크로서비스가 Passport를 수신·검증하기 위해 사용하는 공유 라이브러리이다.

## 기술 스택

| 항목 | 내용 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.2.2 |
| Authorization Server | Spring Authorization Server 1.x (SAS) |
| Session | Spring Session JDBC |
| Build Tool | Gradle (Kotlin DSL), 멀티모듈 |
| 의존성 관리 | Spring Dependency Management 1.1.7 |
| JSON | Jackson (jackson-databind, jackson-datatype-jsr310) |
| Validation | Jakarta Bean Validation 3.0 |
| 코드 생성 | Lombok |
| 코드 포맷팅 | Spotless + Google Java Format 1.17.0 |
| 테스트 | JUnit 5, AssertJ, Mockito, Spring Boot Test (MockMvc) |
| 배포 | JitPack, GitHub Packages |

## 모듈 구조

```
auth-common/                          # 루트 프로젝트
├── services/
│   ├── apis/                         # 배포 단위 (Spring Boot Application)
│   │   ├── api-gateway/              # API Gateway 서버
│   │   └── auth-api/                 # OIDC Authorization Server (SAS 1.x)
│   └── libs/                         # 공유 라이브러리
│       ├── auth-core/                # 도메인 엔티티, 비즈니스 로직 (Spring 독립)
│       ├── auth-infra/               # JPA Repository, Flyway 마이그레이션
│       ├── service-client/           # ServiceClient·ServiceRoute 도메인, 헥사고날 구조
│       └── auth-common-lib/          # Passport, @PassportAuth (외부 서비스용)
├── docs/                             # 문서 (ARCHITECTURE.md, CONVENTION.md, DOC-GUIDE.md, README-GUIDE.md, INFRASTRUCTURE.md)
└── .claude/                          # Claude Code harness (agents, skills, commands)
```

### 모듈 의존성

```
api-gateway ──→ auth-common-lib

auth-api ──→ auth-core
         ──→ auth-infra
         ──→ service-client

service-client ──→ auth-infra   (JpaAuditingConfig 공유)

auth-infra ──→ auth-core

auth-core ──→ auth-common-lib

auth-common-lib (독립, 외부 서비스에 배포)
```

### 모듈별 역할

| 모듈 | 유형 | 역할 |
|------|------|------|
| **api-gateway** | App | 클라이언트 요청 수신, Bearer JWT RS256 검증 → Passport 생성 → 헤더 전달. SAS OAuth 엔드포인트를 auth-api로 프록시 |
| **auth-api** | App | OIDC Authorization Server (SAS 1.x). 회원 가입·로그아웃 API. JSON 로그인 → 세션 수립 → Authorization Code + PKCE → 토큰 발급 |
| **auth-core** | Lib | Member 엔티티, 회원가입 비즈니스 로직, 도메인 규칙. Spring 독립. |
| **auth-infra** | Lib | JPA Repository, Flyway 마이그레이션 (members, SAS 3종, Spring Session 2종, service_client·service_route, grant_type nullable) |
| **service-client** | Lib | ServiceClient·ServiceRoute 도메인, OAuth 클라이언트 등록·redirectUri 관리 유스케이스, JPA 어댑터, SAS 어댑터. Spring Boot AutoConfiguration으로 자기 스캔. |
| **auth-common-lib** | Lib | Passport 도메인, @PassportAuth 어노테이션, ArgumentResolver. 외부 마이크로서비스가 의존하는 공유 라이브러리 |

## 인증 흐름

### [흐름 A] Authorization Code + PKCE (로그인 → 세션 → code → token)

```
SPA → POST /api/v1/auth/login (JSON: loginId, password)
  → Gateway: permittedPath → Bearer 검증 SKIP → auth-api
  → JsonLoginAuthenticationFilter
    → DaoAuthenticationProvider → MemberUserDetailsService → MemberRepository
    → BCrypt 비밀번호 검증
  → 성공: Spring Session 수립 → 200 OK + Set-Cookie: SESSION=...

SPA → GET /oauth2/authorize?response_type=code&client_id=...&code_challenge=...
  → Gateway: permittedPath → auth-api
  → SAS AuthorizationEndpoint
  → 세션 인증 확인 → Authorization Code 생성 → 302 Redirect → {redirect_uri}?code=...

SPA → POST /oauth2/token (code, code_verifier, client_id)
  → Gateway: permittedPath → auth-api
  → SAS TokenEndpoint → PKCE 검증, Code 교환
  → JwtEncoder(RSA) 토큰 생성 → PassportTokenCustomizer
    (memberId, loginId, name, generation, status, roles 클레임 주입)
  → Access Token / ID Token / Refresh Token (JSON 응답)
```

### [흐름 B] Bearer 토큰 → Passport 변환 (API 요청)

```
Client → API Gateway (Authorization: Bearer <SAS JWT>)
  → BearerToPassportFilter
    → Authorization 헤더에서 Bearer 토큰 추출
    → JwtVerifier(JWKS URI 기반 ReactiveJwtDecoder)
      → auth-api 내부 /oauth2/jwks 직접 fetch (AUTH_JWKS_URI)
      → RS256 서명 검증, 만료 검증, iss = AUTH_ISSUER_URI(Gateway 공개 URL) 검증
    → PassportBuilder.buildAndSerialize(Jwt jwt)
      → 클레임(memberId, loginId, name, generation, status, roles) → Passport
      → JSON → Base64
    → X-User-Passport 헤더 주입
  → Downstream Microservices
    → PassportArgumentResolver가 헤더 수신
    → Base64 디코딩 → JSON 역직렬화
    → @PassportAuth 옵션에 따라 검증
      (validateExpiry, requiredRoles, includeHigherRoles, condition)
    → Passport 객체를 컨트롤러 파라미터에 주입
```

## auth-common-lib 패키지 구조

```
com.econo.common.auth
├── config/                          # Auto-Configuration
│   └── AuthAutoConfiguration       # WebMvcConfigurer 구현, ArgumentResolver 등록
├── core/passport/                   # 도메인 계층
│   ├── Passport                     # Aggregate Root - 회원 인증 정보
│   ├── PassportException            # 인증/인가 예외
│   └── Roles                        # 역할 상수 및 유틸리티
└── web/                             # 웹 계층
    ├── annotation/
    │   └── PassportAuth             # 파라미터 어노테이션
    └── resolver/
        └── PassportArgumentResolver # Spring MVC ArgumentResolver
```

## 계층 설계

### core (도메인 계층)

Spring Framework에 의존하지 않는 순수 도메인 로직.

- **Passport**: 회원 인증 정보를 담는 불변 객체 (Aggregate Root). `memberId`, `loginId`, `name`, `generation`(Integer), `status`(String), `roles`, `issuedAt`, `expiresAt` 필드를 가지며, 역할 확인(`hasRole`, `isAdmin`), 유효성 검증(`isValid`, `isExpired`, `isActive`), 접근 제어(`canAccessMember`) 메서드를 제공한다.
- **PassportException**: HTTP 상태 코드와 에러 코드를 포함하는 커스텀 예외. 정적 팩토리 메서드(`unauthorized`, `forbidden`, `badRequest`, `expired`, `invalid`)로 생성한다.
- **Roles**: 역할 상수(`USER`, `MANAGER`, `ADMIN`, `SUPER_ADMIN`)와 동적 역할 생성 헬퍼, 역할 계층 비교 유틸리티를 제공한다.

### web (웹 계층)

Spring MVC에 의존하는 웹 어댑터 계층.

- **@PassportAuth**: 컨트롤러 메서드 파라미터에 붙이는 어노테이션. `required`, `validateExpiry`, `requiredRoles`, `requireAllRoles`, `includeHigherRoles`, `condition` 옵션을 제공한다.
- **PassportArgumentResolver**: `HandlerMethodArgumentResolver` 구현체. `X-User-Passport` 헤더를 디코딩하고, 어노테이션 옵션에 따라 검증 후 Passport 객체를 주입한다.

### config (설정 계층)

- **AuthAutoConfiguration**: `WebMvcConfigurer`를 구현하여 `PassportArgumentResolver`를 자동 등록한다. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 선언되어 Spring Boot 3.x Auto-Configuration으로 동작한다.

## auth-core 패키지 구조 (헥사고날)

```
com.econo.auth.core
└── member/
    ├── domain/
    │   ├── Member                       # Aggregate Root - 회원 도메인 객체
    │   └── MemberStatus                 # 활동 상태 Enum (AM / RM / CM / OB)
    ├── application/
    │   ├── port/
    │   │   ├── in/
    │   │   │   └── SignupUseCase        # 인바운드 포트 (가입) + SignupCommand record
    │   │   └── out/
    │   │       ├── MemberRepository    # 아웃바운드 포트 (영속성)
    │   │       └── PasswordHasher      # 아웃바운드 포트 (해싱)
    │   └── usecase/
    │       └── SignupService           # SignupUseCase 구현체
    └── exception/
        ├── MemberAlreadyExistsException # 409 MEMBER_ALREADY_EXISTS
        ├── InvalidCredentialsException  # (클래스 보존, SAS 도입 후 핸들러 미등록)
        └── InvalidPasswordPolicyException # 400 INVALID_PASSWORD_POLICY
```

> `LoginUseCase`, `LoginService`, `TokenIssuer`는 SAS 도입으로 제거됨.

## service-client 패키지 구조 (헥사고날)

```
com.econo.auth.client
├── config/
│   └── ServiceClientAutoConfiguration   # @AutoConfiguration + @ComponentScan + @EnableJpaRepositories + @EntityScan
├── domain/
│   ├── ServiceClient                    # Aggregate Root — OAuth 클라이언트 도메인 객체
│   ├── ServiceRoute                     # Gateway 라우팅 정보 (record)
│   └── GrantType                        # OAuth 그랜트 타입 enum (AUTHORIZATION_CODE, CLIENT_CREDENTIALS)
├── application/
│   ├── port/out/
│   │   ├── ServiceClientRepository      # ServiceClient 아웃바운드 포트
│   │   ├── ServiceRouteRepository       # ServiceRoute 아웃바운드 포트
│   │   ├── SasClientRegistrar           # SAS 클라이언트 등록 아웃바운드 포트
│   │   └── SasRedirectUriManager        # SAS redirectUri 조회·갱신 아웃바운드 포트 (SAS 의존 격리)
│   └── usecase/
│       ├── RegisterOAuthClientService   # OAuth 클라이언트 등록 서비스
│       └── ClientRedirectUriService     # redirectUri 관리 서비스 (ClientInfo record 포함)
├── adapter/out/
│   ├── persistence/
│   │   ├── ServiceClientJpaEntity       # service_client 테이블 JPA 엔티티
│   │   ├── ServiceClientJpaRepository   # Spring Data JPA 인터페이스
│   │   ├── ServiceClientRepositoryAdapter  # ServiceClientRepository 포트 구현체
│   │   ├── ServiceRouteJpaEntity        # service_route 테이블 JPA 엔티티
│   │   ├── ServiceRouteJpaRepository    # Spring Data JPA 인터페이스
│   │   └── ServiceRouteRepositoryAdapter   # ServiceRouteRepository 포트 구현체
│   └── sas/
│       ├── SasClientRegistrarAdapter    # SasClientRegistrar 포트 구현체
│       └── SasRedirectUriManagerAdapter # SasRedirectUriManager 포트 구현체 (RegisteredClientRepository 직접 의존 격리)
└── exception/
    ├── InvalidClientException           # @ResponseStatus 없음 — 클라이언트 미존재 (GlobalExceptionHandler가 404 CLIENT_NOT_FOUND로 매핑)
    ├── RedirectUriRequiredException     # @ResponseStatus(400) — redirectUri 누락·한도 초과·유효하지 않은 URI
    ├── UnsupportedGrantTypeException    # @ResponseStatus(400) — 미지원 그랜트 타입
    └── DuplicateClientNameException     # @ResponseStatus(409) — 클라이언트 이름 중복
```

> `GrantType.fromString`은 알 수 없는 비-null 값에 대해 `IllegalArgumentException`을 throw한다. `AdminClientController`가 이를 catch하여 `UnsupportedGrantTypeException`으로 변환한다.

> `ServiceClientAutoConfiguration`은 `@EnableJpaRepositories` / `@EntityScan`으로 `com.econo.auth.client.adapter.out.persistence` 패키지를 직접 스캔한다. `InfraConfig`(auth-infra)는 `com.econo.auth.infra` 패키지만 스캔하며, service-client 패키지는 포함하지 않는다.

## 핵심 설계 결정

### 1. Gateway 책임 분리

JWT 파싱과 검증은 API Gateway의 책임이다. 다른 마이크로서비스는 이미 검증된 Passport를 전달받아 사용하므로, JWT 관련 의존성이 없다.

### 2. 불변 객체

Passport의 `roles` 필드는 `List.copyOf()`로 방어적 복사를 수행한다. 생성 이후 상태 변경이 불가능하다.

### 3. String 기반 역할 체계

Enum 대신 String 기반 역할을 사용한다. 이유:
- 동적 역할 생성 지원 (`DEPARTMENT_CS_ADMIN`, `PROJECT_2024_MEMBER`)
- 외부 시스템과의 연동 용이
- Spring Security 패러다임과 일치

### 4. 역할 계층

`SUPER_ADMIN(4) > ADMIN(3) > MANAGER(2) > USER(1)` 순서로 계층이 정의되어 있다. `includeHigherRoles = true` 옵션으로 상위 역할이 하위 역할의 권한을 자동 포함할 수 있다.

### 5. SpEL 조건 표현식

`@PassportAuth(condition = "#{passport.memberId == #userId or passport.isAdmin()}")` 형태로 복잡한 권한 로직을 선언적으로 표현할 수 있다. PathVariable과 RequestParam이 SpEL 컨텍스트에 자동 바인딩된다.

### 6. 헥사고날 아키텍처 (auth-core)

`auth-core`는 포트·어댑터 패턴을 따른다. 도메인과 유스케이스는 `auth-core`가 정의하고, 어댑터(`auth-infra`)가 포트를 구현한다. `SignupService`는 `@Component`가 아닌 일반 클래스이며, `auth-api`의 `ApplicationServiceConfig`에서 `@Bean`으로 등록한다.

### 7. Passport에 generation/status 포함

Gateway가 JWT 클레임에서 `generation`과 `status`를 Passport에 미러링한다. 다운스트림 서비스가 회원 조회 없이 이 값을 사용할 수 있다. 단, 실시간 최신 상태가 중요한 경우 직접 DB 조회를 권장한다.

### 8. SAS Model A — 자사 앱도 OAuth public client

자사 프런트엔드도 외부 OAuth 클라이언트와 동일하게 Authorization Code + PKCE + Refresh Token 그랜트를 사용한다. 토큰 발급 권위는 SAS로 일원화한다. 자사 클라이언트(`FIRST_PARTY_CLIENT_ID`)는 `RegisteredClientConfig`에서 기동 시 멱등 seed 등록된다.

### 9. 헤드리스 OIDC Authorization Server

auth-api는 로그인 UI를 제공하지 않는다. `/api/v1/auth/login`은 JSON 자격증명을 수신하여 서버 세션을 수립하는 API 엔드포인트이며, SAS의 `/oauth2/authorize`가 그 세션을 소비한다. 브라우저 로그인 UI는 외부 SPA가 담당한다. 미인증 상태로 `/oauth2/authorize`에 진입하면 `auth.frontend-login-url`(SPA 로그인 URL)로 302 리다이렉트된다.

### 10. RSA 키 고정 kid

`jwkSource()` 빈은 `keyID("econo-auth-rsa-key-v1")` 고정 kid를 사용한다. 기동마다 kid가 바뀌면 기발급 토큰의 JWKS 키 매칭이 영구 실패한다.

## 에러 코드 체계

### auth-common-lib (Passport 검증)

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 401 UNAUTHORIZED | AUTH_UNAUTHORIZED | 헤더 누락, 인증 실패 |
| 401 UNAUTHORIZED | AUTH_TOKEN_EXPIRED | Passport 만료 |
| 401 UNAUTHORIZED | AUTH_PASSPORT_INVALID | Passport 구조 유효성 실패 |
| 403 FORBIDDEN | AUTH_FORBIDDEN | 권한 부족 |
| 400 BAD_REQUEST | AUTH_BAD_REQUEST | 디코딩/파싱 실패 |

> 정의: `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/PassportException.java`

### auth-core (회원 도메인)

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 409 CONFLICT | MEMBER_ALREADY_EXISTS | loginId 중복 가입 |
| 400 BAD_REQUEST | INVALID_PASSWORD_POLICY | 비밀번호 정책 위반 |
| 400 BAD_REQUEST | INVALID_LOGIN_ID_FORMAT | loginId 형식 위반 (IllegalArgumentException) |

### service-client (ServiceClient 도메인)

> 정의: `services/libs/service-client/src/main/java/com/econo/auth/client/exception/`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 404 NOT_FOUND | CLIENT_NOT_FOUND | clientId로 클라이언트를 찾을 수 없음 (`InvalidClientException`) |
| 400 BAD_REQUEST | REDIRECT_URI_REQUIRED | `authorization_code` 클라이언트에 redirectUri 누락, redirectUri 한도 초과, 유효하지 않은 URI (`RedirectUriRequiredException`) |
| 400 BAD_REQUEST | UNSUPPORTED_GRANT_TYPE | 지원하지 않는 grantType 값 (`UnsupportedGrantTypeException`. `GrantType.fromString`이 `IllegalArgumentException` throw → `AdminClientController`가 변환) |
| 409 CONFLICT | DUPLICATE_CLIENT_NAME | clientName 중복 (`DuplicateClientNameException`) |

### auth-api — Admin API (AdminClientController)

> 정의: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 401 UNAUTHORIZED | INVALID_CLIENT_CREDENTIALS | Authorization 헤더 누락, Base64 디코딩 실패, clientId 미존재, BCrypt 불일치, `authorization_code` 클라이언트(clientSecret 없음). 401 응답 시 `WWW-Authenticate: Basic realm="admin"` 헤더 포함 |
| 403 FORBIDDEN | FORBIDDEN_CLIENT_MISMATCH | path `{clientId}` ≠ Basic Auth에서 추출한 clientId |

> Bean Validation 오류(VALIDATION_FAILED)는 auth-api 웹 레이어에서 처리.

> 로그인 실패(INVALID_CREDENTIALS)는 `JsonLoginAuthenticationFilter`가 직접 401 JSON 응답을 반환하며, `GlobalExceptionHandler`를 거치지 않는다.

## 테스트 구조

```
services/libs/auth-common-lib/src/test/java/com/econo/common/auth/
├── core/passport/
│   ├── PassportTest.java              # Passport 도메인 단위 테스트
│   ├── PassportExceptionTest.java     # 예외 단위 테스트
│   └── RolesTest.java                 # 역할 유틸리티 단위 테스트
├── integration/
│   └── PassportAuthIntegrationTest.java  # MockMvc 통합 테스트
└── web/resolver/
    └── PassportArgumentResolverTest.java # ArgumentResolver 단위 테스트

services/libs/auth-core/src/test/java/com/econo/auth/core/
└── member/
    ├── application/usecase/
    │   └── SignupServiceTest.java     # SignupService 단위 테스트
    └── domain/
        └── MemberTest.java           # Member 도메인 단위 테스트

services/libs/auth-infra/src/test/java/com/econo/auth/infra/
└── member/adapter/out/
    ├── persistence/MemberRepositoryAdapterTest.java  # @DataJpaTest + Testcontainer
    └── security/BCryptPasswordHasherAdapterTest.java

services/libs/service-client/src/test/java/com/econo/auth/client/
└── application/usecase/
    └── RegisterOAuthClientServiceTest.java  # RegisterOAuthClientService 단위 테스트 (Mockito)

services/apis/auth-api/src/test/java/com/econo/auth/api/
├── adapter/in/web/
│   └── MemberControllerTest.java          # @WebMvcTest 웹 레이어 테스트
└── integration/
    ├── AuthApiIntegrationTest.java        # @SpringBootTest E2E (회원가입·로그아웃)
    └── SasAuthorizationServerIntegrationTest.java  # @SpringBootTest SAS 흐름 E2E

services/apis/api-gateway/src/test/java/com/econo/auth/gateway/
├── filter/
│   └── BearerToPassportFilterTest.java
└── security/
    ├── JwtVerifierTest.java
    └── PassportBuilderTest.java
```

- 단위 테스트: 도메인 로직 검증 (Spring 컨텍스트 불필요)
- 통합 테스트: `@SpringBootTest` + `MockMvc`로 E2E 흐름 검증
- JPA 통합 테스트: `@DataJpaTest` + Testcontainers PostgreSQL 이미지
