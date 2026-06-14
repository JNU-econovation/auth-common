# Architecture

auth-common의 아키텍처 문서.

## 개요

auth-common은 **모노레포 멀티모듈 프로젝트**로, Passport 기반 인증 서버와 API Gateway를 포함하는 통합 레포지토리이다.

- **API Gateway**가 클라이언트 요청을 수신하고, SAS 발급 JWT(Bearer 토큰)를 RS256 검증 후 Passport로 변환하여 다운스트림으로 전달한다.
- **Auth 서버(auth-api)**는 Spring Authorization Server(SAS 1.x) 기반 OIDC Authorization Server이다. 회원 가입·로그아웃 API와 함께 `/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/userinfo`, `/.well-known/openid-configuration` 표준 엔드포인트를 제공한다.
- **econo-passport**는 다른 마이크로서비스가 Passport를 수신·검증하기 위해 사용하는 외부 공유 라이브러리이다(JitPack `com.github.JNU-econovation:econo-passport:1.0.3`, 이 레포 모듈 아님).

## 기술 스택

| 항목 | 내용 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.2.2 |
| Authorization Server | Spring Authorization Server 1.x (SAS) |
| Session | 표준 서블릿 HttpSession |
| Build Tool | Gradle (Kotlin DSL), 멀티모듈 |
| 의존성 관리 | Spring Dependency Management 1.1.7 |
| JSON | Jackson (jackson-databind, jackson-datatype-jsr310) |
| Validation | Jakarta Bean Validation 3.0 |
| 코드 생성 | Lombok |
| 코드 포맷팅 | Spotless + Google Java Format 1.17.0 |
| 테스트 | JUnit 5, AssertJ, Mockito, Spring Boot Test (MockMvc) |
| 배포 | Docker Hub (Jib 이미지) + SSH 배포 (CD). JitPack은 econo-passport 소비용 |

## 모듈 구조

```
auth-common/                          # 루트 프로젝트
├── services/
│   ├── apis/                         # 배포 단위 (Spring Boot Application)
│   │   ├── api-gateway/              # API Gateway 서버
│   │   └── auth-api/                 # OIDC Authorization Server (SAS 1.x)
│   └── libs/                         # 공유 라이브러리 (이 레포 로컬 모듈 4개)
│       ├── member/                   # Member 도메인·유스케이스·JPA 어댑터
│       ├── common-infra/             # JPA Auditing AutoConfiguration (공통 인프라 설정)
│       ├── service-client/           # ServiceClient 도메인, 3계층 구조
│       └── login/                    # 로그인 토큰 발급·재발급·리다이렉트 결정 (TokenEncoder/TokenDecoder 포트)
│   # Passport / @PassportAuth / PassportArgumentResolver 는
│   # 외부 의존성 econo-passport (JitPack) 에서 제공 — 이 레포 모듈 아님
├── db/                               # DB 마이그레이션 전역 관리 (어느 모듈도 소유 안 함)
│   ├── migration/                    # Flyway SQL 단일 소스 (V1__..., V2__..., ...)
│   └── Dockerfile                    # SQL 담은 flyway 이미지 빌드 (운영 배포용)
├── docs/                             # 문서 (ARCHITECTURE.md, CONVENTION.md, DOC-GUIDE.md, README-GUIDE.md, INFRASTRUCTURE.md)
└── .claude/                          # Claude Code harness (agents, skills, commands)
```

### 모듈 의존성

```
api-gateway ──→ econo-passport (외부, JitPack)

auth-api ──→ member
         ──→ service-client
         ──→ login
         ──→ econo-passport (JitPack, com.github.JNU-econovation:econo-passport:1.0.3)

login ──→ member
      ──→ service-client

member ──→ common-infra   (JpaAuditing AutoConfiguration 전이)

service-client ──→ common-infra   (JpaAuditing AutoConfiguration 공유)

econo-passport (외부 의존성 — JitPack 배포, 이 레포 모듈 아님)
```

### 모듈별 역할

| 모듈 | 유형 | 역할 |
|------|------|------|
| **api-gateway** | App | 클라이언트 요청 수신. 인바운드 `X-User-Passport` 항상 제거(위조 방지). 토큰 있으면 auth-api JWKS 공개키로 RS256/만료/issuer 로컬 검증 → `X-User-Passport` 주입. 토큰 없으면 경로 무관 passthrough(다운스트림 `@PassportAuth` 위임). 무효 토큰이면 보호 경로 401 거부. SAS OAuth 엔드포인트를 auth-api로 프록시. ※ auth-api 인트로스펙션 호출 아님 — JWKS 기반 로컬 검증 |
| **auth-api** | App | OIDC Authorization Server (SAS 1.x). 토큰 발급(SAS) + JWKS 제공(`/oauth2/jwks`) + 회원 가입·로그아웃 API. JSON 로그인(경로 A) → AT/RT 쿠키 발급 + clientId 기반 302(WEB) 또는 200+body+redirectUrl(APP). SSO 클라이언트 셀프 등록(`POST /api/v1/clients`, Passport 회원 인증). Authorization Code + PKCE(경로 B) → 토큰 발급. `/api/v1/clients`, `/api/v1/admin/**` 엔드포인트는 econo-passport 라이브러리의 `@PassportAuth` + `PassportArgumentResolver`로 `X-User-Passport` 헤더를 파싱·검증한다. 회원·권한·서비스클라이언트 관리. `NimbusTokenManager`(`TokenEncoder/TokenDecoder` 구현체)를 `config/security/`에 보유한다. |
| **member** | Lib | Member 도메인·유스케이스·출력 포트(repository)·예외·JPA 어댑터·BCrypt 어댑터. Spring Boot AutoConfiguration(`MemberAutoConfiguration`)으로 자기 스캔. (DB 마이그레이션은 모듈 밖 `db/migration`에서 전역 관리 — ADR-0015) |
| **common-infra** | Lib | `@EnableJpaAuditing` AutoConfiguration. `member`·`service-client` 모듈에 JPA Auditing을 일원화 제공. |
| **service-client** | Lib | ServiceClient 도메인, OAuth 클라이언트 등록(셀프·어드민)·redirectUri 관리 유스케이스, JPA 어댑터, SAS 어댑터. Spring Boot AutoConfiguration으로 자기 스캔. |
| **login** | Lib | AT/RT 발급·재발급·RT 검증(`LoginTokenUseCase`), clientId 기반 리다이렉트 결정(`LoginRedirectUseCase`). JWT 서명·검증은 `TokenEncoder/TokenDecoder` 출력 포트로 추상화(구현체는 소비자 앱이 제공). `spring-security-oauth2` 의존 없음. Spring Boot AutoConfiguration(`LoginAutoConfiguration`)으로 자기 스캔. |

> Passport 수신·검증 기능(Passport 도메인, @PassportAuth, PassportArgumentResolver)은 외부 의존성 econo-passport가 제공한다(이 레포 로컬 모듈 아님).

## 인증 흐름

### [흐름 A] JSON 로그인 → 쿠키 발급 → clientId 기반 302 리다이렉트

WEB(`Client-Type` 헤더 없거나 `APP`이 아닌 경우)과 APP(`Client-Type: APP`)의 응답 방식이 다르다.
상세 설계 근거: [ADR-0012](./adr/0012-backend-decided-login-redirect.md)

```
클라이언트 → POST /api/v1/auth/login
              Body: {loginId, password, clientId}   ← clientId는 선택 필드
              Header: Client-Type: WEB | APP (생략 시 WEB)
  → Gateway: 미토큰 → 경로 무관 passthrough → auth-api
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

상세 설계 근거: [ADR-0002](./adr/0002-gateway-as-auth-boundary.md), [ADR-0017](./adr/0017-gateway-tokenless-passthrough.md)

```
Client → API Gateway
  → BearerToPassportFilter (GlobalFilter, @Order(-1) — 라우팅 prefix 재작성 전 실행)
    ⓪ 인바운드 X-User-Passport 무조건 제거 (위조 방지 — 토큰 유무·경로 무관)
       → strippedExchange 생성
    ① 토큰 추출: Authorization: Bearer <token> 헤더 → Cookie: at=<token> 순

    [분기 A: 토큰 없음]
      → 경로 무관 passthrough (strippedExchange 그대로 체인)
      → Downstream: X-User-Passport 없음
        → @PassportAuth(required=true) → 401 거부
        → @PassportAuth(required=false) → 정상 처리

    [분기 B: 유효 토큰]
      → JwtVerifier(JWKS URI 기반 ReactiveJwtDecoder)
        → auth-api /oauth2/jwks에서 공개키 fetch (AUTH_JWKS_URI) ※ 인트로스펙션 아님
        → RS256 서명 검증, 만료 검증, iss = AUTH_ISSUER_URI 검증 (로컬 검증)
      → PassportBuilder.buildAndSerialize(Jwt jwt)
        → 클레임(memberId, loginId, name, generation, status, roles) → Passport
        → JSON → Base64
      → strippedExchange에 X-User-Passport 주입 (검증된 값만 설정)
      → Downstream: X-User-Passport = 검증된 Passport Base64
        → PassportArgumentResolver: Base64 디코딩 → JSON 역직렬화
        → @PassportAuth 옵션에 따라 검증
          (validateExpiry, requiredRoles, includeHigherRoles, condition)
        → Passport 객체를 컨트롤러 파라미터에 주입

    [분기 C: 무효 토큰 (형식 오류·만료·서명 오류·issuer 불일치)]
      → isProtectedPath(path) 판별 (permitted-paths 참조)
        → 보호 경로: HTTP 401
        → permitted 경로: passthrough (strippedExchange)
```

> **permitted-paths 역할**: 무효 토큰 분기(분기 C)에서만 통과/거부를 판별한다. 미토큰 분기(분기 A)에서는 참조하지 않는다.

## econo-passport (외부 의존성) 패키지 구조

> 아래는 이 레포 소스가 아닌 **외부 라이브러리 econo-passport**(JitPack `com.github.JNU-econovation:econo-passport:1.0.3`)의 패키지 구조를 참조용으로 기재한 것이다. 로컬에 소스가 존재하지 않는다.

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

## member 패키지 구조

```
com.econo.auth.member
├── config/
│   └── MemberAutoConfiguration          # @AutoConfiguration + @ComponentScan + @EnableJpaRepositories + @EntityScan
├── application/
│   ├── domain/
│   │   ├── Member                       # Aggregate Root — 회원 도메인 객체
│   │   └── MemberStatus                 # 활동 상태 Enum (AM / RM / CM / OB)
│   ├── usecase/
│   │   ├── SignupUseCase                # 입력 포트 (가입) + SignupCommand record
│   │   └── MemberQueryUseCase           # 입력 포트 (조회) — presentation/config/security → repository 직접 참조 차단 seam
│   ├── service/
│   │   ├── SignupService                # SignupUseCase 구현체 (@Component 아님 — auth-api의 ApplicationServiceConfig가 @Bean 등록)
│   │   └── MemberQueryService           # MemberQueryUseCase 구현체 (@Service, @Transactional(readOnly=true))
│   └── repository/
│       ├── MemberRepository             # 출력 포트 (영속성)
│       └── PasswordHasher               # 출력 포트 (해싱)
├── persistence/
│   ├── entity/
│   │   └── MemberJpaEntity              # members 테이블 JPA 엔티티
│   └── repository/
│       ├── MemberJpaRepository          # Spring Data JPA 인터페이스
│       ├── MemberRepositoryAdapter      # MemberRepository 출력 포트 구현체 (entity↔domain 변환 담당)
│       └── BCryptPasswordHasherAdapter  # PasswordHasher 출력 포트 BCrypt 구현체 (cost=12)
└── exception/
    ├── MemberNotFoundException          # 404 MEMBER_NOT_FOUND
    ├── MemberAlreadyExistsException     # 409 MEMBER_ALREADY_EXISTS
    ├── InvalidCredentialsException      # (클래스 보존, SAS 도입 후 핸들러 미등록)
    └── InvalidPasswordPolicyException   # 400 INVALID_PASSWORD_POLICY
```

> `LoginUseCase`, `LoginService`, `TokenIssuer`는 SAS 도입으로 제거됨.

> `MemberAutoConfiguration`은 `@ComponentScan("com.econo.auth.member")`와 `@EnableJpaRepositories("com.econo.auth.member.persistence.repository")` / `@EntityScan("com.econo.auth.member.persistence.entity")`로 자기 모듈을 스캔한다. `common-infra` 의존을 `api`로 선언하여 소비자(`auth-api`)에 `CommonInfraAutoConfiguration`(`@EnableJpaAuditing`)이 전이 활성화된다.

## service-client 패키지 구조

```
com.econo.auth.client
├── config/
│   └── ServiceClientAutoConfiguration   # @AutoConfiguration + @ComponentScan + @EnableJpaRepositories + @EntityScan
├── application/
│   ├── domain/
│   │   ├── ServiceClient                # Aggregate Root — OAuth 클라이언트 도메인 객체
│   │   ├── ServiceRoute                 # 라우팅 정보 불변 도메인 record (routeId, pathPrefix, upstreamUrl, enabled, ...)
│   │   └── GrantType                    # OAuth 그랜트 타입 enum (AUTHORIZATION_CODE, CLIENT_CREDENTIALS)
│   ├── usecase/
│   │   ├── RegisterOAuthClientUseCase   # 입력 포트 — 클라이언트 등록 (Command/Result record 포함)
│   │   ├── ClientRedirectUriUseCase     # 입력 포트 — redirectUri 관리 (ClientInfo record 포함)
│   │   └── ManageRouteUseCase           # 입력 포트 — 라우트 CRUD (CreateRouteCommand/UpdateRouteCommand/RouteResult record 포함)
│   ├── service/
│   │   ├── RegisterOAuthClientService   # RegisterOAuthClientUseCase 구현체
│   │   ├── ClientRedirectUriService     # ClientRedirectUriUseCase 구현체
│   │   ├── ManageRouteService           # ManageRouteUseCase 구현체 (SSRF·보호경로 검증, DB 저장, refresh 트리거)
│   │   ├── GatewayRefreshClient         # 출력 포트 — api-gateway POST /api/v1/internal/routes/refresh 호출 추상화 (구현체는 auth-api)
│   │   └── ProtectedPathPolicy          # 출력 포트 — 보호 경로 판정 추상화 (값·구현체는 소비자 앱 auth-api가 제공)
│   └── repository/
│       ├── ServiceClientRepository      # 출력 포트 (ServiceClient 영속성)
│       ├── ServiceRouteRepository       # 출력 포트 (ServiceRoute 영속성 — findAllEnabled 포함)
│       ├── SasClientRegistrar           # 출력 포트 (SAS 클라이언트 등록)
│       └── SasRedirectUriManager        # 출력 포트 (SAS redirectUri 조회·갱신, SAS 의존 격리)
├── persistence/
│   ├── entity/
│   │   ├── ServiceClientJpaEntity          # service_client 테이블 JPA 엔티티
│   │   └── ServiceRouteJpaEntity           # service_route 테이블 JPA 엔티티 (@LastModifiedDate 자동 갱신)
│   └── repository/
│       ├── ServiceClientJpaRepository      # Spring Data JPA 인터페이스
│       ├── ServiceClientRepositoryAdapter  # ServiceClientRepository 출력 포트 구현체 (entity↔domain 변환 담당)
│       ├── ServiceRouteJpaRepository       # Spring Data JPA 인터페이스 (findAllByEnabled, existsByPathPrefixAndRouteIdNot 등)
│       ├── ServiceRouteRepositoryAdapter   # ServiceRouteRepository 출력 포트 구현체
│       ├── SasClientRegistrarAdapter       # SasClientRegistrar 출력 포트 구현체
│       └── SasRedirectUriManagerAdapter    # SasRedirectUriManager 출력 포트 구현체 (RegisteredClientRepository 직접 의존 격리)
└── exception/
    ├── InvalidClientException           # @ResponseStatus 없음 — 클라이언트 미존재 (GlobalExceptionHandler가 404 CLIENT_NOT_FOUND로 매핑)
    ├── RedirectUriRequiredException     # @ResponseStatus(400) — redirectUri 누락·한도 초과·유효하지 않은 URI
    ├── UnsupportedGrantTypeException    # @ResponseStatus(400) — 미지원 그랜트 타입
    ├── DuplicateClientNameException     # @ResponseStatus(409) — 클라이언트 이름 중복
    ├── ClientLimitExceededException     # @ResponseStatus(422) — 1인 5개 등록 한도 초과 (셀프 등록 전용)
    ├── RouteNotFoundException           # 404 ROUTE_NOT_FOUND — routeId 미존재
    ├── RoutePathConflictException       # 409 ROUTE_PATH_CONFLICT — pathPrefix 중복
    ├── RouteUpstreamInvalidException    # 400 ROUTE_UPSTREAM_INVALID — SSRF 검증 실패
    └── RouteProtectedException          # 403 ROUTE_PROTECTED — 보호 경로 가로채기·삭제 시도
```

> `GrantType`은 `RegisterOAuthClientService`에서 `GrantType.AUTHORIZATION_CODE` 고정으로 사용한다. 컨트롤러 계층에서 grantType을 입력 받지 않으므로 `GrantType.fromString`은 호출되지 않는다.

> `ServiceClientAutoConfiguration`은 `@EnableJpaRepositories("com.econo.auth.client.persistence.repository")` / `@EntityScan("com.econo.auth.client.persistence.entity")`로 자기 모듈을 스캔한다. 다른 AutoConfiguration에서 이 패키지를 중복 선언하면 충돌이 발생한다.

> `GatewayRefreshClient`는 인터페이스로, `auth-api` 모듈의 `GatewayClientConfig`에서 `GatewayRefreshClientImpl`(`RestClient` 기반)을 빈으로 등록한다. `ManageRouteService`는 이 인터페이스에만 의존한다.

> `ProtectedPathPolicy`도 같은 패턴이다. 보호 경로 목록 값은 배포 환경(게이트웨이 정적 라우트)에 종속되므로 service-client는 판정 포트만 정의하고, `auth-api`의 `ProtectedPathPolicyImpl`(`config/`)이 실제 경로 집합과 매칭 로직을 소유해 `ApplicationServiceConfig`에서 빈으로 등록한다.

## 계층 모델 및 의존성 규칙

전체 모듈의 패키지 구조는 **presentation / application / persistence 3계층 + 계층별 DIP**로 통일한다. 상세 채택 근거: [ADR-0014](./adr/0014-3-layer-dip-architecture.md)

### 계층별 역할

| 계층 | 패키지 | 역할 |
|------|--------|------|
| **presentation** | `presentation/controller`, `presentation/dto`, `presentation/util` | HTTP 요청·응답 처리. application.usecase 인터페이스에만 의존 |
| **application** | `application/usecase` (입력 포트 IF), `application/service` (구현), `application/repository` (출력 포트 IF), `application/domain` | 비즈니스 로직. 도메인 객체와 포트 인터페이스만 다룬다 |
| **persistence** | `persistence/entity` (JPA `@Entity`), `persistence/repository` (Spring Data + 어댑터) | 영속성 구현. application.repository 출력 포트를 구현하고, entity↔domain 변환을 담당 |
| **config** | `config/` | 일반 설정 및 빈 와이어링 |
| **config/security** | `config/security/` | Spring Security에 결합된 클래스 전용 (SecurityConfig, UserDetailsService, AuthenticationFilter, GlobalFilter, JWT 처리) |
| **exception** | `exception/` | 도메인 예외 |

### 의존성 방향

```
presentation.controller
        │
        ▼
application.usecase (입력 포트 IF)
        ▲
        │
application.service ──→ application.repository (출력 포트 IF)
                                  ▲
                                  │
                        persistence.repository (어댑터)
                                  │
                        persistence.entity (JPA @Entity)

config/security ──→ application.usecase (입력 포트 IF) 만 참조
config/ (일반)  ──→ application.service, application.repository 참조 허용
```

### 의존성 불변식

1. 참조는 presentation → application → persistence 단방향. presentation 및 config/security는 `application.usecase` 인터페이스에만 의존한다. `application.service` 구현체나 `application.repository`·`persistence`를 직접 참조하지 않는다.
2. 일반 `config/`(보안 아님) 와이어링 클래스는 `application.repository`와 `application.service`를 참조해도 된다. 빈 등록·CORS 설정 등 설정 책임이므로 허용된다(예: `ApplicationServiceConfig`, `DynamicCorsConfigurationSource`).
3. `application.repository` 출력 포트 시그니처는 도메인 객체만 사용한다. JPA 엔티티(`*JpaEntity`)는 `persistence` 계층 바깥으로 나가지 않으며, entity↔domain 변환은 `persistence.repository` 어댑터의 책임이다.

### 모듈별 계층 사용 범위

| 모듈 유형 | presentation | application | persistence | config/security |
|-----------|-------------|-------------|-------------|-----------------|
| **libs** (member, service-client, login) | 없음 | usecase + service + repository + domain | entity + repository 어댑터 (login은 JPA 없음) | 없음 |
| **apps** (auth-api, api-gateway) | controller + dto + util | application 계층 패키지 없음 — 단 `SignupService` 빈 등록은 `config/ApplicationServiceConfig` 담당 | 없음 | SecurityConfig + Filter + JWT 유틸 (`NimbusTokenManager` 포함) |

## 핵심 설계 결정

### 1. Gateway 책임 분리

JWT 파싱과 검증은 API Gateway의 책임이다. 다른 마이크로서비스는 이미 검증된 Passport를 전달받아 사용하므로, JWT 관련 의존성이 없다.

**tokenless passthrough (ADR-0017 보완)**: 미토큰 요청은 게이트웨이를 통과하며, 인증 강제(401 거부)는 다운스트림 `@PassportAuth`가 담당한다. 이는 동적 라우팅 환경에서 게이트웨이가 prefix 재작성 전 경로를 보기 때문에 `permitted-paths`와 실제 공개 경로가 불일치하는 구조적 문제를 해소한다. 무효 토큰은 보호 경로에서 게이트웨이가 여전히 401로 거부한다. 위조 방지를 위해 인바운드 `X-User-Passport` 헤더는 토큰 유무·경로에 관계없이 항상 제거된다.

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

### 6. 3계층 + 계층별 DIP 아키텍처

전체 모듈의 패키지 구조를 presentation / application / persistence 3계층으로 통일하고 계층 경계를 인터페이스(DIP)로 강제한다. 상세: [ADR-0014](./adr/0014-3-layer-dip-architecture.md)

`member`·`service-client` lib 모듈은 application + persistence 계층을 포함한다. `SignupService`는 `@Component`가 아닌 일반 클래스이며, `auth-api`의 `ApplicationServiceConfig`에서 `@Bean`으로 등록한다. `MemberAutoConfiguration`이 `@ComponentScan`으로 빈을 자동 등록하고, `common-infra`를 `api` 의존으로 선언하여 JPA Auditing을 소비자에 전이 활성화한다.

**auth-api application 계층**: auth-api의 로그인 관련 application 계층(`LoginTokenService`, `LoginRedirectResolver`)은 `login` lib으로 추출 완료되었다. `TokenEncoder/TokenDecoder` 출력 포트 도입으로 lib이 `spring-security-oauth2`에 직접 의존하지 않으며, RS256 서명·검증 구현체(`NimbusTokenManager`)는 auth-api의 `config/security/`에 위치한다. `LoginRedirectResolver`와 `LoginTokenService`는 `login` lib의 `@Service`로 `LoginAutoConfiguration` 컴포넌트 스캔으로 자동 등록된다. auth-api `ApplicationServiceConfig`의 `loginRedirectResolver` `@Bean` 수동 등록은 제거되었다. `SignupService`는 auth-api `ApplicationServiceConfig`에서 `@Bean`으로 계속 수동 등록한다.

### 7. Passport에 generation/status 포함

Gateway가 JWT 클레임에서 `generation`과 `status`를 Passport에 미러링한다. 다운스트림 서비스가 회원 조회 없이 이 값을 사용할 수 있다. 단, 실시간 최신 상태가 중요한 경우 직접 DB 조회를 권장한다.

### 8. SAS Model A — 자사 앱도 OAuth public client

자사 프런트엔드도 외부 OAuth 클라이언트와 동일하게 Authorization Code + PKCE + Refresh Token 그랜트를 사용한다. 토큰 발급 권위는 SAS로 일원화한다. 자사 클라이언트(`FIRST_PARTY_CLIENT_ID`)는 `RegisteredClientConfig`에서 기동 시 멱등 seed 등록된다.

### 9. 헤드리스 OIDC Authorization Server

auth-api는 로그인 UI를 제공하지 않는다. 브라우저 로그인 UI는 외부 SPA가 담당한다.

- **경로 A** (`POST /api/v1/auth/login`): JSON 자격증명을 수신하여 AT/RT JWT를 직접 발급한다. WEB 클라이언트는 쿠키(at, rt) 세팅 후 `clientId`에 등록된 redirect_uri로 302하고, APP 클라이언트는 200 OK + body(accessToken, refreshToken, redirectUrl)로 응답한다. clientId가 없거나 미등록이면 `auth.redirect.default-url`로 fail-safe 302(WEB) 또는 default-url을 redirectUrl 필드로 반환(APP)한다 (ADR-0012 참조).
- **경로 B** (`GET /oauth2/authorize` → `POST /oauth2/token`): SAS 기반 Authorization Code + PKCE 흐름. 미인증 상태로 `/oauth2/authorize`에 진입하면 `auth.frontend-login-url`(SPA 로그인 URL)로 302 리다이렉트된다.
- `auth.frontend-login-url`(경로 B 전용 — SAS 미인증 진입 리다이렉트)과 `auth.redirect.default-url`(경로 A fallback 목적지)은 역할이 다르므로 별도로 관리한다.

### 10. Gateway 라우팅 — 정적 보호 라우트 + 동적 서비스 라우트 공존

api-gateway 라우팅은 두 계층으로 구성된다.

- **정적 보호 라우트**: `GatewayRoutingConfig`의 `RouteLocator` 빈(`@Order(1)`)에 auth-api 핵심 경로(`/api/v1/auth/**`, `/oauth2/**` 등)를 고정. 재배포 없이는 변경 불가.
- **동적 서비스 라우트**: `DynamicRouteDefinitionRepository`(`Ordered.LOWEST_PRECEDENCE`)가 인메모리 `ConcurrentHashMap` 캐시를 관리. 기동 시 `AuthApiRouteClient`가 auth-api `GET /api/v1/internal/routes`를 호출하여 초기 로드. 라우트 CRUD 시 `GatewayRefreshClient`가 api-gateway `POST /api/v1/internal/routes/refresh`를 호출하여 즉시 갱신.

이 구조에서 라우팅의 진실은 두 소스다: 보호 경로는 `GatewayRoutingConfig.java`, 동적 경로는 `service_route` 테이블. 신규 서비스 연동은 Admin API(`POST /api/v1/admin/routes`)로 처리하며 재배포가 필요 없다. 상세: [ADR-0016](./adr/0016-dynamic-gateway-routing-reintroduction.md), [DYNAMIC_ROUTING.md](./DYNAMIC_ROUTING.md)

동적 라우트는 StripPrefix 필터를 적용하지 않는다. 클라이언트가 요청한 전체 경로가 업스트림에 그대로 전달된다.

<!-- 11번 결번 -->

### 12. RSA 키 고정 kid

`jwkSource()` 빈은 `keyID("econo-auth-rsa-key-v1")` 고정 kid를 사용한다. 기동마다 kid가 바뀌면 기발급 토큰의 JWKS 키 매칭이 영구 실패한다.

### 13. 클라이언트 등록 이중 경로 — 셀프서비스 + 어드민

클라이언트 등록은 두 경로가 공존한다. 자세한 설계 근거: [ADR-0013](./adr/0013-passport-member-self-registration.md)

- **셀프 등록** (`POST /api/v1/clients`): econo-passport `@PassportAuth`로 `X-User-Passport`에서 `memberId`를 추출하여 인증. ADMIN 역할 불필요. 헤더 누락 또는 invalid → 401. 1인 5개 제한. `owner_id`·`client_secret_hash` 저장.
- **어드민 등록** (`POST /api/v1/admin/clients`): econo-passport `@PassportAuth(requiredRoles = {ADMIN, SUPER_ADMIN})`로 인증·인가. 헤더 누락·invalid → 401, 역할 부족 → 403. `owner_id=NULL`, `client_secret_hash=NULL`.
- 두 경로 모두 SAS에 `authorization_code + PKCE`, `ClientAuthenticationMethod.NONE` 클라이언트로 등록한다.
- `clientSecret`은 셀프 등록 시 발급·보관(service_client.client_secret_hash BCrypt 해시)하지만, 현재 이를 소비하는 in-scope 엔드포인트가 없다. 향후 redirect-uri 셀프관리 도입 시 활성화 예정.

## 에러 코드 체계

### econo-passport (Passport 검증)

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 401 UNAUTHORIZED | AUTH_UNAUTHORIZED | 헤더 누락, 인증 실패 |
| 401 UNAUTHORIZED | AUTH_TOKEN_EXPIRED | Passport 만료 |
| 401 UNAUTHORIZED | AUTH_PASSPORT_INVALID | Passport 구조 유효성 실패 |
| 403 FORBIDDEN | AUTH_FORBIDDEN | 권한 부족 |
| 400 BAD_REQUEST | AUTH_BAD_REQUEST | 디코딩/파싱 실패 |

> 정의: econo-passport 라이브러리 내부(외부 jar)의 `com.econo.common.auth.core.passport.PassportException`

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

### service-client (ServiceRoute 도메인 — 동적 라우팅)

> 정의: `services/libs/service-client/src/main/java/com/econo/auth/client/exception/`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 404 NOT_FOUND | ROUTE_NOT_FOUND | routeId에 해당하는 라우트 없음 (`RouteNotFoundException`) |
| 409 CONFLICT | ROUTE_PATH_CONFLICT | pathPrefix 중복 등록 (`RoutePathConflictException`) — DB UNIQUE 제약 사전 검증 |
| 400 BAD_REQUEST | ROUTE_UPSTREAM_INVALID | upstreamUrl SSRF 검증 실패 — 비허용 스킴, private IP, 빈 호스트 (`RouteUpstreamInvalidException`) |
| 403 FORBIDDEN | ROUTE_PROTECTED | 보호 경로(`ProtectedPathPolicy`) 가로채기·삭제 시도 (`RouteProtectedException`) |

### auth-api — 셀프 등록 API (ClientController)

> 정의: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ClientController.java`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 401 UNAUTHORIZED | AUTH_UNAUTHORIZED | `X-User-Passport` 헤더 누락 또는 `memberId` 파싱 실패 |

### auth-api — Admin API (AdminClientController)

> 정의: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminClientController.java`

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 401 UNAUTHORIZED | AUTH_UNAUTHORIZED | `X-User-Passport` 헤더 누락 또는 invalid passport (econo-passport unauthorized) |
| 403 FORBIDDEN | FORBIDDEN | ADMIN/SUPER_ADMIN 역할 부족 (econo-passport forbidden) |

> Bean Validation 오류(VALIDATION_FAILED)는 auth-api 웹 레이어에서 처리.

> 로그인 실패(INVALID_CREDENTIALS)는 `JsonLoginAuthenticationFilter`가 직접 401 JSON 응답을 반환하며, `GlobalExceptionHandler`를 거치지 않는다.

## 테스트 구조

```
services/libs/member/src/test/java/com/econo/auth/member/
├── application/service/
│   └── SignupServiceTest.java                                    # SignupService 단위 테스트 (Mockito)
├── application/domain/
│   └── MemberTest.java                                           # Member 도메인 단위 테스트
└── persistence/repository/
    ├── MemberRepositoryAdapterTest.java                          # @DataJpaTest + Testcontainers
    └── BCryptPasswordHasherAdapterTest.java                      # BCrypt 단위 테스트

services/libs/service-client/src/test/java/com/econo/auth/client/
└── application/service/
    ├── RegisterOAuthClientServiceTest.java  # RegisterOAuthClientService 단위 테스트 (Mockito)
    └── ClientRedirectUriServiceTest.java    # ClientRedirectUriService 단위 테스트 (Mockito)

services/libs/login/src/test/java/com/econo/auth/login/
└── application/service/
    ├── LoginTokenServiceTest.java           # LoginTokenService 단위 테스트 (Mockito)
    └── LoginRedirectResolverTest.java       # LoginRedirectResolver 단위 테스트 (Mockito)

services/apis/auth-api/src/test/java/com/econo/auth/api/
├── presentation/controller/
│   ├── SignUpControllerTest.java          # @WebMvcTest 웹 레이어 테스트
│   ├── AdminClientControllerTest.java     # @WebMvcTest AdminClientController 테스트
│   ├── AdminMemberControllerTest.java     # @WebMvcTest AdminMemberController 테스트
│   ├── AdminRoleControllerTest.java       # @WebMvcTest AdminRoleController 테스트
│   ├── ClientControllerTest.java          # @WebMvcTest ClientController 셀프 등록 테스트
│   └── JwksControllerTest.java            # @WebMvcTest JwksController 테스트
├── presentation/dto/
│   └── LoginResponseTest.java             # LoginResponse 직렬화 단위 테스트 (redirectUrl 포함)
└── integration/
    └── AuthApiIntegrationTest.java        # @SpringBootTest E2E (회원가입·로그아웃·WEB/APP 로그인·셀프 등록)

services/apis/api-gateway/src/test/java/com/econo/auth/gateway/
└── config/security/
    ├── BearerToPassportFilterTest.java
    ├── JwtVerifierTest.java
    └── PassportBuilderTest.java
```

- 단위 테스트: 도메인 로직 검증 (Spring 컨텍스트 불필요)
- 통합 테스트: `@SpringBootTest` + `MockMvc`로 E2E 흐름 검증
- JPA 통합 테스트: `@DataJpaTest` + Testcontainers PostgreSQL 이미지
