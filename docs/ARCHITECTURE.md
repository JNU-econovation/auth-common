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
│       ├── member/                   # Member 도메인·유스케이스·JPA 어댑터·Flyway 마이그레이션
│       ├── common-infra/             # JPA Auditing AutoConfiguration (공통 인프라 설정)
│       ├── service-client/           # ServiceClient·ServiceRoute 도메인, 헥사고날 구조
│       └── auth-common-lib/          # Passport, @PassportAuth (외부 서비스용)
├── docs/                             # 문서 (ARCHITECTURE.md, CONVENTION.md, DOC-GUIDE.md, README-GUIDE.md, INFRASTRUCTURE.md)
└── .claude/                          # Claude Code harness (agents, skills, commands)
```

### 모듈 의존성

```
api-gateway ──→ auth-common-lib

auth-api ──→ member
         ──→ service-client
         ──→ econo-passport (JitPack, com.github.JNU-econovation:econo-passport:1.0.3)

member ──→ common-infra   (JpaAuditing AutoConfiguration 전이)

service-client ──→ common-infra   (JpaAuditing AutoConfiguration 공유)

auth-common-lib (독립, 외부 서비스에 배포)
```

### 모듈별 역할

| 모듈 | 유형 | 역할 |
|------|------|------|
| **api-gateway** | App | 클라이언트 요청 수신, Bearer JWT RS256 검증 → Passport 생성 → 헤더 전달. SAS OAuth 엔드포인트를 auth-api로 프록시 |
| **auth-api** | App | OIDC Authorization Server (SAS 1.x). 회원 가입·로그아웃 API. JSON 로그인(경로 A) → AT/RT 쿠키 발급 + clientId 기반 302(WEB) 또는 200+body+redirectUrl(APP). SSO 클라이언트 셀프 등록(`POST /api/v1/clients`, Passport 회원 인증). Authorization Code + PKCE(경로 B) → 토큰 발급. `/api/v1/clients`, `/api/v1/admin/**` 엔드포인트는 econo-passport 라이브러리의 `@PassportAuth` + `PassportArgumentResolver`로 `X-User-Passport` 헤더를 파싱·검증한다. |
| **member** | Lib | Member 도메인·유스케이스·포트·예외·JPA 어댑터·BCrypt 어댑터·Flyway 마이그레이션 5종. Spring Boot AutoConfiguration(`MemberAutoConfiguration`)으로 자기 스캔. |
| **common-infra** | Lib | `@EnableJpaAuditing` AutoConfiguration. `member`·`service-client` 모듈에 JPA Auditing을 일원화 제공. |
| **service-client** | Lib | ServiceClient·ServiceRoute 도메인, OAuth 클라이언트 등록(셀프·어드민)·redirectUri 관리 유스케이스, JPA 어댑터, SAS 어댑터. Spring Boot AutoConfiguration으로 자기 스캔. |
| **auth-common-lib** | Lib | Passport 도메인, @PassportAuth 어노테이션, ArgumentResolver. 외부 마이크로서비스가 의존하는 공유 라이브러리 |

## 인증 흐름

### [흐름 A] JSON 로그인 → 쿠키 발급 → clientId 기반 302 리다이렉트

WEB(`Client-Type` 헤더 없거나 `APP`이 아닌 경우)과 APP(`Client-Type: APP`)의 응답 방식이 다르다.
상세 설계 근거: [ADR-0012](./adr/0012-backend-decided-login-redirect.md)

```
클라이언트 → POST /api/v1/auth/login
              Body: {loginId, password, clientId}   ← clientId는 선택 필드
              Header: Client-Type: WEB | APP (생략 시 WEB)
  → Gateway: permittedPath → Bearer 검증 SKIP → auth-api
  → JsonLoginAuthenticationFilter.attemptAuthentication()
    → objectMapper.readValue(InputStream) → LoginRequest(loginId, password, clientId)
    → request.setAttribute("clientId", clientId)  ← InputStream 단일 소비 이후 전달
    → DaoAuthenticationProvider → MemberUserDetailsService → MemberRepository
    → BCrypt 비밀번호 검증
  → 성공: JsonLoginAuthenticationFilter.successfulAuthentication()
    → LoginTokenService.issue() → AT + RT JWT 발급

  [WEB 분기]
    → TokenCookieManager.setAtCookie() → Set-Cookie: at (HttpOnly, SameSite=None)
    → TokenCookieManager.setRtCookie() → Set-Cookie: rt (HttpOnly, SameSite=None)
    → LoginRedirectResolver.resolve(clientId, defaultUrl)
        clientId 있고 등록됨  → ClientRedirectUriService.findByClientId()
                                → redirect_uri 1개: 그 URI
                                → redirect_uri 복수: 알파벳 오름차순 정렬 후 첫 번째
        clientId 없거나 미등록(InvalidClientException) → auth.redirect.default-url (fail-safe fallback)
        redirect_uri 빈 Set   → auth.redirect.default-url
        기타 RuntimeException (인프라·DB 오류) → auth.redirect.default-url (fail-safe)
    → response.sendRedirect(target)  ← 쿠키 헤더 추가 후 반드시 수행
  → HTTP 302 Found
     Location: <등록된 redirect_uri 또는 default-url>
     Set-Cookie: at=...; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=3600
     Set-Cookie: rt=...; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=2592000
     Body: 없음
  ※ 토큰은 Location URL(query/fragment)에 절대 포함하지 않는다. 쿠키 전용.
  ※ user-supplied URL이 없으므로 open redirect가 구조적으로 불가능하다.

  [APP 분기]
    → LoginRedirectResolver.resolve(clientId, defaultUrl) → redirectUrl 결정
        (WEB과 동일한 로직 — clientId 미전달·미등록·redirect_uri 빈 Set·기타 오류 → default-url)
    → LoginResponse.app(AT, expiredAt, RT, redirectUrl) → body 직렬화
  → HTTP 200 OK
     Body: {"accessToken": "...", "accessExpiredTime": ..., "refreshToken": "...", "redirectUrl": "..."}
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

## member 패키지 구조 (헥사고날)

```
com.econo.auth.member
├── config/
│   └── MemberAutoConfiguration          # @AutoConfiguration + @ComponentScan + @EnableJpaRepositories + @EntityScan
├── domain/
│   ├── Member                           # Aggregate Root — 회원 도메인 객체
│   └── MemberStatus                     # 활동 상태 Enum (AM / RM / CM / OB)
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   └── SignupUseCase            # 인바운드 포트 (가입) + SignupCommand record
│   │   └── out/
│   │       ├── MemberRepository         # 아웃바운드 포트 (영속성)
│   │       └── PasswordHasher           # 아웃바운드 포트 (해싱)
│   └── usecase/
│       └── SignupService                # SignupUseCase 구현체 (@Component 아님 — auth-api의 ApplicationServiceConfig가 @Bean 등록)
├── adapter/out/
│   ├── persistence/
│   │   ├── MemberJpaEntity              # members 테이블 JPA 엔티티
│   │   ├── MemberJpaRepository          # Spring Data JPA 인터페이스
│   │   └── MemberRepositoryAdapter      # MemberRepository 포트 구현체
│   └── security/
│       └── BCryptPasswordHasherAdapter  # PasswordHasher 포트 BCrypt 구현체 (cost=12)
└── exception/
    ├── MemberNotFoundException          # 404 MEMBER_NOT_FOUND
    ├── MemberAlreadyExistsException     # 409 MEMBER_ALREADY_EXISTS
    ├── InvalidCredentialsException      # (클래스 보존, SAS 도입 후 핸들러 미등록)
    └── InvalidPasswordPolicyException   # 400 INVALID_PASSWORD_POLICY
```

> `LoginUseCase`, `LoginService`, `TokenIssuer`는 SAS 도입으로 제거됨.

> `MemberAutoConfiguration`은 `@ComponentScan("com.econo.auth.member")`와 `@EnableJpaRepositories` / `@EntityScan`으로 `com.econo.auth.member.adapter.out.persistence` 패키지를 직접 스캔한다. `common-infra` 의존을 `api`로 선언하여 소비자(`auth-api`)에 `CommonInfraAutoConfiguration`(`@EnableJpaAuditing`)이 전이 활성화된다.

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
    ├── DuplicateClientNameException     # @ResponseStatus(409) — 클라이언트 이름 중복
    └── ClientLimitExceededException     # @ResponseStatus(422) — 1인 5개 등록 한도 초과 (셀프 등록 전용)
```

> `GrantType`은 `RegisterOAuthClientService`에서 `GrantType.AUTHORIZATION_CODE` 고정으로 사용한다. 컨트롤러 계층에서 grantType을 입력 받지 않으므로 `GrantType.fromString`은 호출되지 않는다.

> `ServiceClientAutoConfiguration`은 `@EnableJpaRepositories` / `@EntityScan`으로 `com.econo.auth.client.adapter.out.persistence` 패키지를 직접 스캔한다. 다른 AutoConfiguration에서 이 패키지를 중복 선언하면 충돌이 발생한다.

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

### 6. 헥사고날 아키텍처 (member)

`member` 모듈은 포트·어댑터 패턴을 따른다. 도메인·유스케이스·포트와 어댑터(JPA·BCrypt)가 하나의 모듈에 공존한다. `SignupService`는 `@Component`가 아닌 일반 클래스이며, `auth-api`의 `ApplicationServiceConfig`에서 `@Bean`으로 등록한다. `MemberAutoConfiguration`이 `@ComponentScan`으로 어댑터 빈을 자동 등록하고, `common-infra`를 `api` 의존으로 선언하여 JPA Auditing을 소비자에 전이 활성화한다.

### 7. Passport에 generation/status 포함

Gateway가 JWT 클레임에서 `generation`과 `status`를 Passport에 미러링한다. 다운스트림 서비스가 회원 조회 없이 이 값을 사용할 수 있다. 단, 실시간 최신 상태가 중요한 경우 직접 DB 조회를 권장한다.

### 8. SAS Model A — 자사 앱도 OAuth public client

자사 프런트엔드도 외부 OAuth 클라이언트와 동일하게 Authorization Code + PKCE + Refresh Token 그랜트를 사용한다. 토큰 발급 권위는 SAS로 일원화한다. 자사 클라이언트(`FIRST_PARTY_CLIENT_ID`)는 `RegisteredClientConfig`에서 기동 시 멱등 seed 등록된다.

### 9. 헤드리스 OIDC Authorization Server

auth-api는 로그인 UI를 제공하지 않는다. 브라우저 로그인 UI는 외부 SPA가 담당한다.

- **경로 A** (`POST /api/v1/auth/login`): JSON 자격증명을 수신하여 AT/RT JWT를 직접 발급한다. WEB 클라이언트는 쿠키(at, rt) 세팅 후 `clientId`에 등록된 redirect_uri로 302하고, APP 클라이언트는 200 OK + body(accessToken, refreshToken, redirectUrl)로 응답한다. clientId가 없거나 미등록이면 `auth.redirect.default-url`로 fail-safe 302(WEB) 또는 default-url을 redirectUrl 필드로 반환(APP)한다 (ADR-0012 참조).
- **경로 B** (`GET /oauth2/authorize` → `POST /oauth2/token`): SAS 기반 Authorization Code + PKCE 흐름. 미인증 상태로 `/oauth2/authorize`에 진입하면 `auth.frontend-login-url`(SPA 로그인 URL)로 302 리다이렉트된다.
- `auth.frontend-login-url`(경로 B 전용 — SAS 미인증 진입 리다이렉트)과 `auth.redirect.default-url`(경로 A fallback 목적지)은 역할이 다르므로 별도로 관리한다.

### 10. RSA 키 고정 kid

`jwkSource()` 빈은 `keyID("econo-auth-rsa-key-v1")` 고정 kid를 사용한다. 기동마다 kid가 바뀌면 기발급 토큰의 JWKS 키 매칭이 영구 실패한다.

### 11. 클라이언트 등록 이중 경로 — 셀프서비스 + 어드민

클라이언트 등록은 두 경로가 공존한다. 자세한 설계 근거: [ADR-0013](./adr/0013-passport-member-self-registration.md)

- **셀프 등록** (`POST /api/v1/clients`): econo-passport `@PassportAuth`로 `X-User-Passport`에서 `memberId`를 추출하여 인증. ADMIN 역할 불필요. 헤더 누락 또는 invalid → 401. 1인 5개 제한. `owner_id`·`client_secret_hash` 저장.
- **어드민 등록** (`POST /api/v1/admin/clients`): econo-passport `@PassportAuth(requiredRoles = {ADMIN, SUPER_ADMIN})`로 인증·인가. 헤더 누락·invalid → 401, 역할 부족 → 403. `owner_id=NULL`, `client_secret_hash=NULL`.
- 두 경로 모두 SAS에 `authorization_code + PKCE`, `ClientAuthenticationMethod.NONE` 클라이언트로 등록한다.
- `clientSecret`은 셀프 등록 시 발급·보관(service_client.client_secret_hash BCrypt 해시)하지만, 현재 이를 소비하는 in-scope 엔드포인트가 없다. 향후 redirect-uri 셀프관리 도입 시 활성화 예정.

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

### member (회원 도메인)

> 정의: `services/libs/member/src/main/java/com/econo/auth/member/exception/`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 404 NOT_FOUND | MEMBER_NOT_FOUND | memberId로 회원을 찾을 수 없음 (`MemberNotFoundException`) |
| 409 CONFLICT | MEMBER_ALREADY_EXISTS | loginId 중복 가입 (`MemberAlreadyExistsException`) |
| 400 BAD_REQUEST | INVALID_PASSWORD_POLICY | 비밀번호 정책 위반 (`InvalidPasswordPolicyException`) |
| 400 BAD_REQUEST | INVALID_ARGUMENT | loginId 형식 위반 (`IllegalArgumentException` → `GlobalExceptionHandler`가 매핑) |

### service-client (ServiceClient 도메인)

> 정의: `services/libs/service-client/src/main/java/com/econo/auth/client/exception/`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 404 NOT_FOUND | CLIENT_NOT_FOUND | clientId로 클라이언트를 찾을 수 없음 (`InvalidClientException`) |
| 400 BAD_REQUEST | REDIRECT_URI_REQUIRED | redirectUri 누락·비어있음 (`RedirectUriRequiredException`) |
| 409 CONFLICT | DUPLICATE_CLIENT_NAME | clientName 중복 (`DuplicateClientNameException`) |
| 422 UNPROCESSABLE_ENTITY | CLIENT_LIMIT_EXCEEDED | 1인 5개 등록 한도 초과 (`ClientLimitExceededException` — 셀프 등록 전용) |

### auth-api — 셀프 등록 API (ClientController)

> 정의: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/ClientController.java`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 401 UNAUTHORIZED | AUTH_UNAUTHORIZED | `X-User-Passport` 헤더 누락 또는 `memberId` 파싱 실패 |

### auth-api — Admin API (AdminClientController)

> 정의: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 401 UNAUTHORIZED | AUTH_UNAUTHORIZED | `X-User-Passport` 헤더 누락 또는 invalid passport (econo-passport unauthorized) |
| 403 FORBIDDEN | FORBIDDEN | ADMIN/SUPER_ADMIN 역할 부족 (econo-passport forbidden) |

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

services/libs/member/src/test/java/com/econo/auth/member/
├── application/usecase/
│   └── SignupServiceTest.java                         # SignupService 단위 테스트 (Mockito)
├── domain/
│   └── MemberTest.java                               # Member 도메인 단위 테스트
└── adapter/out/
    ├── persistence/MemberRepositoryAdapterTest.java   # @DataJpaTest + Testcontainers
    └── security/BCryptPasswordHasherAdapterTest.java  # BCrypt 단위 테스트

services/libs/service-client/src/test/java/com/econo/auth/client/
└── application/usecase/
    └── RegisterOAuthClientServiceTest.java  # RegisterOAuthClientService 단위 테스트 (Mockito)

services/apis/auth-api/src/test/java/com/econo/auth/api/
├── adapter/in/web/
│   ├── MemberControllerTest.java          # @WebMvcTest 웹 레이어 테스트
│   ├── AdminClientControllerTest.java     # @WebMvcTest AdminClientController 테스트
│   ├── AdminMemberControllerTest.java     # @WebMvcTest AdminMemberController 테스트
│   ├── AdminRoleControllerTest.java       # @WebMvcTest AdminRoleController 테스트
│   ├── ClientControllerTest.java          # @WebMvcTest ClientController 셀프 등록 테스트
│   └── LoginResponseTest.java             # LoginResponse 직렬화 단위 테스트 (redirectUrl 포함)
├── application/
│   ├── LoginTokenServiceTest.java         # LoginTokenService 단위 테스트 (Mockito)
│   └── LoginRedirectResolverTest.java     # LoginRedirectResolver 단위 테스트 (Mockito) — clientId 기반 6개 시나리오
└── integration/
    ├── AuthApiIntegrationTest.java        # @SpringBootTest E2E (회원가입·로그아웃·WEB/APP 로그인·셀프 등록)
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
