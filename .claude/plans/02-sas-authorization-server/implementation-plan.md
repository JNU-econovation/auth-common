# sas-authorization-server - implementation

## 메타
- **작업명**: sas-authorization-server
- **문서 타입**: implementation
- **작성일**: 2026-05-24
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

Spring Authorization Server(SAS 1.x)를 auth-api에 통합하여 OIDC Authorization Server(IdP)로 재작업한다. member-auth에서 구현한 HMAC/쿠키 기반 JWT 계층(`TokenIssuer`, `JwtTokenIssuerAdapter`, `JwtCookieProperties`)을 전면 제거하고, 토큰 발급 권위를 SAS로 일원화한다. api-gateway는 HMAC 서명 검증에서 SAS JWKS(RSA) 기반 검증으로 교체한다. Java 21 / Spring Boot 3.2.2 / Gradle Kotlin DSL 멀티모듈 프로젝트 위에서 설계되며, 헥사고날 아키텍처 원칙에 따라 SAS 관련 타입은 auth-api/auth-infra(adapter/config) 계층에 격리하고 auth-core 도메인(Member)의 프레임워크 독립성을 유지한다.

---

## 모호함 결정 사항

구현 설계 전에 todo의 모호함 섹션 6개 항목에 대해 아래와 같이 결정한다. 이 결정은 이후 설계 전체에 적용된다.

| # | 모호함 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | RSA 키 외부화 형식 | `RSA_PRIVATE_KEY` / `RSA_PUBLIC_KEY` PEM 문자열 환경변수 방식 채택 | Docker/K8s Secret에서 환경변수 주입이 표준. keystore 파일 마운트는 파일 시스템 의존성 추가됨. |
| 2 | 세션 저장소 | `spring-session-jdbc`(JDBC 세션) 즉시 도입 | 단일 인스턴스라도 in-memory 세션은 재시작 시 세션 소멸. JDBC는 기존 DataSource 재사용이 가능해 인프라 추가 없음. |
| 3 | LoginService/LoginUseCase 존치 | `LoginUseCase` / `LoginService` 제거. `MemberUserDetailsService`로 자격증명 검증 로직 흡수 | `TokenIssuer` 제거 후 LoginService는 `findByLoginId` + `matches` 2줄만 남음. Spring Security 파이프라인과 중복. |
| 4 | SAS 엔드포인트 게이트웨이 라우팅 | SAS OAuth 엔드포인트(`/oauth2/**`, `/.well-known/**`, `/userinfo`)는 **Gateway를 통해 auth-api로 라우팅한다 (단일 진입점 유지).** Gateway는 해당 경로를 permitted(Bearer 토큰 검증 없이 통과)로 설정하고 auth-api에 프록시. `issuer URL = Gateway 공개 URL`. | 단일 진입점 유지 원칙. 직접 노출 시 auth-api 포트가 외부에 노출되어 Gateway의 보안/라우팅 통제가 무력화됨. JWKS URI(`/oauth2/jwks`)도 Gateway 도메인으로 일원화. |
| 5 | api-gateway JWT 검증 라이브러리 | `spring-boot-starter-oauth2-resource-server` (reactive) 교체 채택 | JWKS 자동 키 갱신(rotation) 내장, `jjwt` 완전 제거 가능, `ReactiveJwtDecoder` API가 WebFlux와 자연스럽게 통합. |
| 6 | consent 스킵 설정 | `RegisteredClient` 설정에서 `requireAuthorizationConsent(false)` 사용 | SAS 1.x 표준 설정 방식. 커스텀 `ConsentService` 구현 불필요. |

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 / 제거 | 사유 |
|---|---|---|
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthorizationServerConfig.java` | 신규 | SAS `@Order(1)` 필터체인, `AuthorizationServerSettings` 빈 (issuer = `AUTH_ISSUER_URI`, 값은 Gateway 공개 URL) |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` | 변경 | Stateless 단일 체인 → 세션 기반 `@Order(2)` 앱 체인으로 재작성 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java` | 신규 | `JdbcRegisteredClientRepository` 빈, 1st-party client seed |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/OAuth2AuthorizationServiceConfig.java` | 신규 | `JdbcOAuth2AuthorizationService`, `JdbcOAuth2AuthorizationConsentService` 빈 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RsaKeyConfig.java` | 신규 | PEM 환경변수 로드 → `JWKSource<SecurityContext>`, `JwtEncoder` 빈 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/PassportTokenCustomizer.java` | 신규 | `OAuth2TokenCustomizer<JwtEncodingContext>` — Passport 클레임 주입 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` | 변경 | `LoginService` 빈 등록 제거, `TokenIssuer` 파라미터 제거 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/JwtCookieProperties.java` | 제거 | HMAC/쿠키 방식 폐기 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetailsService.java` | 신규 | `UserDetailsService` 구현 — Member 로드, `MemberUserDetails` 반환 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetails.java` | 신규 | `UserDetails` 확장 — Member 도메인 래퍼, Passport 클레임 원본 보유 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` | 신규 | JSON body 자격증명 수신 → 세션 수립 커스텀 필터 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberController.java` | 변경 | `login()` 제거, `logout()` 세션 무효화로 재작성, `signup()` 유지 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/LoginRequest.java` | 제거 | `JsonLoginAuthenticationFilter`가 직접 JSON 파싱. DTO 불필요 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` | 변경 | `InvalidCredentialsException` 핸들러 제거(SAS 파이프라인 처리), 나머지 유지 |
| `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/port/out/TokenIssuer.java` | 제거 | SAS가 토큰 발급 전담 |
| `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/port/in/LoginUseCase.java` | 제거 | `MemberUserDetailsService`로 대체 |
| `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/usecase/LoginService.java` | 제거 | `MemberUserDetailsService`로 대체 |
| `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/token/JwtTokenIssuerAdapter.java` | 제거 | `TokenIssuer` 포트 제거에 연동 |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/JwtVerifier.java` | 변경 | HMAC → `ReactiveJwtDecoder` (JWKS URI 기반 RSA 검증)로 재작성 |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/JwtCookieToPassportFilter.java` | 변경 | 쿠키 파싱 → Bearer 헤더 파싱, 클래스명 `BearerToPassportFilter`로 변경 |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/PassportBuilder.java` | 변경 | `Claims`(jjwt) → `Jwt`(Spring Security) 타입으로 클레임 출처 변경 |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` | 변경 | `/oauth2/**`, `/.well-known/**`, `/userinfo` 경로를 permitted 라우팅(Bearer 토큰 검증 제외, auth-api 프록시)으로 추가 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/apis/auth-api`

```
com.econo.auth.api/
├── config/
│   ├── AuthorizationServerConfig       — SAS @Order(1) 필터체인, AuthorizationServerSettings (issuer = AUTH_ISSUER_URI, 값은 Gateway 공개 URL)
│   ├── SecurityConfig                  — 앱 @Order(2) 필터체인 (세션 기반, 재작성)
│   ├── RegisteredClientConfig          — JdbcRegisteredClientRepository + client seed
│   ├── OAuth2AuthorizationServiceConfig — JdbcOAuth2AuthorizationService/ConsentService 빈
│   ├── RsaKeyConfig                    — PEM → JWKSource, JwtEncoder 빈
│   ├── PassportTokenCustomizer         — OAuth2TokenCustomizer<JwtEncodingContext>
│   └── ApplicationServiceConfig        — SignupService 빈만 유지 (LoginService 제거)
├── security/
│   ├── MemberUserDetailsService        — UserDetailsService 구현
│   └── MemberUserDetails               — UserDetails 확장, Member 도메인 래퍼
├── filter/
│   └── JsonLoginAuthenticationFilter   — POST /api/v1/auth/login JSON 인증 필터
├── adapter/in/web/
│   ├── MemberController                — signup(유지), logout(재작성), login(제거)
│   └── SignupRequest                   — 변경 없음
└── exception/
    └── GlobalExceptionHandler          — InvalidCredentialsException 핸들러 제거
```

##### `AuthorizationServerConfig`
- **타입**: Config (`@Configuration`, `@Order(1)`)
- **책임**: SAS 인가 서버 전용 `SecurityFilterChain` 빈 등록. OIDC 활성화. `AuthorizationServerSettings`(issuer URL) 빈 등록.
- **주요 메서드/함수**:
  - `authorizationServerSecurityFilterChain(HttpSecurity)` — `OAuth2AuthorizationServerConfigurer`를 `HttpSecurity`에 적용. `oidc(Customizer.withDefaults())` 활성화. `exceptionHandling`의 `authenticationEntryPoint`를 외부 프런트 로그인 URL로 리다이렉트 설정. `@Order(1)`.
  - `authorizationServerSettings()` — `AuthorizationServerSettings.builder().issuer("${AUTH_ISSUER_URI}").build()` 반환. **`AUTH_ISSUER_URI`의 값은 auth-api 내부 URL이 아닌 Gateway 공개 URL이어야 한다.** JWKS URI(`/oauth2/jwks`), discovery document(`/.well-known/openid-configuration`) 등 모든 엔드포인트 URL이 Gateway 도메인으로 발행된다.
- **의존성**: `HttpSecurity`, `@Value("${AUTH_ISSUER_URI}")` 환경변수
- **적용 컨벤션**:
  - 클래스명 접미사 `Config` (CONVENTION.md 1.2)
  - 모든 `public` 메서드에 Javadoc (`@param`, `@return`)
  - `@Order(1)` — SAS 필터체인이 앱 필터체인보다 먼저 평가되어야 하므로 더 높은 우선순위(낮은 숫자)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java:23` (빈 등록 패턴 미러링)
- **연관 todo**: `[ ] AuthorizationServerConfig 신설`

##### `SecurityConfig` (재작성)
- **타입**: Config (`@Configuration`, `@EnableWebSecurity`, `@Order(2)`)
- **책임**: 앱용 `SecurityFilterChain` 재정의. 세션 기반, CSRF 조정, 커스텀 인증 필터 삽입, permit/authenticated 경로 설정.
- **주요 메서드/함수**:
  - `appSecurityFilterChain(HttpSecurity, JsonLoginAuthenticationFilter)` — 다음을 순서대로 구성:
    1. `sessionManagement(IF_REQUIRED)` — 세션 기반 전환
    2. `csrf` — 앱 체인(@Order(2))은 `CookieCsrfTokenRepository.withHttpOnlyFalse()` 사용. **/api/v1/auth/login 경로만 CSRF 제외**. SAS 체인(@Order(1))은 SAS가 자체 엔드포인트의 CSRF를 처리하므로 별도 설정 불필요.
    3. `cors` — SPA 도메인 허용 (`${CORS_ALLOWED_ORIGINS}` 환경변수로 주입)
    4. `authorizeHttpRequests` — `/api/v1/auth/signup`, `/api/v1/auth/logout` permitAll; 나머지 authenticated
    5. `addFilterBefore(JsonLoginAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`
  - `authenticationManager(AuthenticationConfiguration)` — `@Bean` 노출 (JsonLoginAuthenticationFilter 주입에 필요)
  - `passwordEncoder()` — `BCryptPasswordEncoder(12)` `@Bean` 등록 (`BCryptPasswordHasherAdapter`와 cost 값 일치 확인 필요)
- **의존성**: `JsonLoginAuthenticationFilter`, `AuthenticationConfiguration`, `MemberUserDetailsService`
- **적용 컨벤션**:
  - `@EnableWebSecurity`는 `SecurityConfig`에만 선언 (중복 선언 금지)
  - `@Order(2)` — SAS 필터체인(@Order(1)) 이후 평가
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java:14` (재작성 대상)
- **연관 todo**: `[ ] SecurityConfig 재작성`

##### `RegisteredClientConfig`
- **타입**: Config (`@Configuration`)
- **책임**: `JdbcRegisteredClientRepository` 빈 등록. 1st-party public client seed 멱등 등록.
- **주요 메서드/함수**:
  - `registeredClientRepository(JdbcOperations)` — `JdbcRegisteredClientRepository(jdbcOperations)` 생성 후, `CLIENT_ID`에 해당하는 client가 없으면 `save()` 호출. `@Bean`.
  - `firstPartyPublicClient()` — private 헬퍼. `RegisteredClient.withId(UUID)` 빌더로 다음 설정:
    - `clientId("${FIRST_PARTY_CLIENT_ID}")` 환경변수 주입
    - `clientAuthenticationMethod(ClientAuthenticationMethod.NONE)` — public client
    - `authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)`, `REFRESH_TOKEN`
    - `redirectUri("${FIRST_PARTY_REDIRECT_URI}")` 환경변수 주입
    - `scope(OidcScopes.OPENID)`, `scope(OidcScopes.PROFILE)`
    - `clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())`
- **의존성**: `JdbcOperations`(`DataSource` 기반), `@Value` 환경변수
- **적용 컨벤션**:
  - `JdbcOperations`는 `JdbcTemplate`의 상위 인터페이스 — 기존 DataSource 재사용
  - `RegisteredClient.withId()`에 고정 UUID를 사용하면 멱등성 보장 (`clientId`로 존재 여부 확인 후 skip)
  - **develop에서 SAS 1.x `JdbcRegisteredClientRepository` API 현행 문서 확인 권장**
- **연관 todo**: `[ ] RegisteredClientConfig 신설`

##### `OAuth2AuthorizationServiceConfig`
- **타입**: Config (`@Configuration`)
- **책임**: `JdbcOAuth2AuthorizationService`, `JdbcOAuth2AuthorizationConsentService` 빈 등록.
- **주요 메서드/함수**:
  - `authorizationService(JdbcOperations, RegisteredClientRepository)` — `JdbcOAuth2AuthorizationService` 생성. `@Bean`.
  - `authorizationConsentService(JdbcOperations, RegisteredClientRepository)` — `JdbcOAuth2AuthorizationConsentService` 생성. `@Bean`.
- **의존성**: `JdbcOperations`, `RegisteredClientRepository`
- **적용 컨벤션**:
  - 클래스명 접미사 `Config` (CONVENTION.md 1.2)
  - `JdbcOperations`는 auto-configured `JdbcTemplate` 빈을 주입받아 사용
- **연관 todo**: `[ ] OAuth2AuthorizationServiceConfig 신설`

##### `RsaKeyConfig`
- **타입**: Config (`@Configuration`)
- **책임**: PEM 환경변수(`RSA_PRIVATE_KEY`, `RSA_PUBLIC_KEY`)에서 RSA 키페어 로드 → `JWKSource<SecurityContext>`, `JwtEncoder` 빈 등록.
- **주요 메서드/함수**:
  - `jwkSource()` — `@Bean`. PEM 문자열 파싱 → `RSAPrivateKey`, `RSAPublicKey` 변환 → `RSAKey.Builder(publicKey).privateKey(privateKey).keyID(UUID).build()` → `new ImmutableJWKSet<>(new JWKSet(rsaKey))` 반환.
    - PEM 파싱: `PemUtils` 정적 헬퍼 또는 `java.security.KeyFactory` + `PKCS8EncodedKeySpec` 직접 사용. **develop에서 Nimbus JOSE `PEMEncodedKeySpec` 또는 Spring Security `RsaKeyConverters` 현행 API 확인 권장**
  - `jwtEncoder(JWKSource<SecurityContext>)` — `new NimbusJwtEncoder(jwkSource)`. `@Bean`.
  - `jwtDecoder(JWKSource<SecurityContext>)` — `OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)`. `@Bean`. (SAS 내부 토큰 검증용)
- **의존성**: `@Value("${RSA_PRIVATE_KEY}")`, `@Value("${RSA_PUBLIC_KEY}")` 환경변수
- **적용 컨벤션**:
  - `private final` 필드, `@RequiredArgsConstructor` 불가 (생성자 내부 파싱 로직 필요) → 직접 생성자 또는 `@Value` 필드 주입
  - 예외 발생 시 `log.error()` + `RuntimeException` 래핑 (기동 불가 상태로 명시적 실패)
- **연관 todo**: `[ ] RsaKeyConfig 신설`

##### `PassportTokenCustomizer`
- **타입**: Config (`@Configuration` 내 `@Bean`, 또는 독립 `@Component`)
- **책임**: Access Token 및 ID Token에 Passport 커스텀 클레임(`memberId`, `loginId`, `name`, `generation`, `status`, `roles`) 주입.
- **주요 메서드/함수**:
  - `customize(JwtEncodingContext context)` — `OAuth2TokenCustomizer<JwtEncodingContext>` 구현.
    - `context.getTokenType()`이 `ACCESS_TOKEN` 또는 `ID_TOKEN`일 때만 클레임 주입
    - `context.getPrincipal()` → `Authentication` → `getPrincipal()` → `MemberUserDetails` 캐스팅
    - `context.getClaims().claim("memberId", ...)` 등으로 각 필드 설정
  - `@Bean` 등록: SAS는 `OAuth2TokenCustomizer<JwtEncodingContext>` 타입 빈을 자동 감지하여 토큰 생성 시 호출
- **의존성**: `MemberUserDetails` (SecurityContext에서 꺼냄)
- **적용 컨벤션**:
  - Javadoc에 클레임 목록 열거 (`memberId:Long`, `loginId:String`, `name:String`, `generation:Integer`, `status:String`, `roles:List<String>`)
  - `context.getTokenType().equals(OAuth2TokenType.ACCESS_TOKEN)` 분기 — ID Token에도 주입 여부 결정 (일반적으로 양쪽 모두 주입)
  - **develop에서 SAS 1.x `JwtEncodingContext` API 현행 문서 확인 권장**
- **연관 todo**: `[ ] PassportTokenCustomizer 신설`

##### `ApplicationServiceConfig` (재작성)
- **타입**: Config (`@Configuration`)
- **책임**: `SignupService` 빈만 등록. `LoginService` 빈 등록 제거.
- **주요 메서드/함수**:
  - `signupService(MemberRepository, PasswordHasher)` — 기존과 동일. `@Bean`.
  - ~~`loginService(...)`~~ — 삭제
- **의존성**: `MemberRepository`, `PasswordHasher`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java:22`
- **연관 todo**: `[ ] ApplicationServiceConfig 재작성`

##### `MemberUserDetailsService`
- **타입**: Security (`@Service` 또는 `@Component`, `com.econo.auth.api.security` 패키지)
- **책임**: Spring Security `UserDetailsService` 구현. `loginId`로 Member 로드 → `MemberUserDetails` 반환.
- **주요 메서드/함수**:
  - `loadUserByUsername(String loginId)` — `MemberRepository.findByLoginId(loginId)` → 없으면 `UsernameNotFoundException` throw. 찾으면 `new MemberUserDetails(member)` 반환.
- **의존성**: `MemberRepository`
- **적용 컨벤션**:
  - `@RequiredArgsConstructor` (final 필드 기반 생성자)
  - `UsernameNotFoundException`은 Spring Security 표준 예외 — `InvalidCredentialsException`을 throw하지 않음. `AuthenticationManager`가 Spring Security 예외를 `AuthenticationException`으로 처리.
  - 클래스명 컨벤션: `{Domain}UserDetailsService` (프로젝트 내 유사 접미사 없음 — Spring Security 표준 명칭 채택)
- **참조할 기존 코드**: `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/usecase/LoginService.java:20` (findByLoginId + matches 로직 참조)
- **연관 todo**: `[ ] MemberUserDetailsService 신설`

##### `MemberUserDetails`
- **타입**: Domain/Security (`com.econo.auth.api.security` 패키지)
- **책임**: Spring Security `UserDetails` 확장. Member 도메인 객체 래핑. `PassportTokenCustomizer`가 클레임 추출에 사용.
- **주요 메서드/함수**:
  - `MemberUserDetails(Member member)` — 생성자. `member`를 `private final` 필드로 보유.
  - `getUsername()` — `member.getLoginId()` 반환
  - `getPassword()` — `member.getHashedPassword()` 반환
  - `getAuthorities()` — `List.of(new SimpleGrantedAuthority("ROLE_USER"))` 반환 (고정. 향후 Member에 roles 필드 추가 시 확장)
  - `getMember()` — `Member` 반환 (`PassportTokenCustomizer`가 호출)
  - `isAccountNonExpired()`, `isAccountNonLocked()`, `isCredentialsNonExpired()`, `isEnabled()` — 모두 `true` 반환
- **의존성**: `Member` (auth-core 도메인)
- **적용 컨벤션**:
  - `@Getter` 클래스 레벨 적용 (getMember() 포함)
  - 필드 `private final Member member`
  - `roles`는 현재 `List.of("USER")` 고정 (기존 `JwtTokenIssuerAdapter:42`와 동일 정책)
- **참조할 기존 코드**: `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/token/JwtTokenIssuerAdapter.java:42` (roles 고정값 참조)
- **연관 todo**: `[ ] MemberUserDetailsService 신설` (MemberUserDetails는 해당 todo의 일부)

##### `JsonLoginAuthenticationFilter`
- **타입**: Filter (`com.econo.auth.api.filter` 패키지, `AbstractAuthenticationProcessingFilter` 상속)
- **책임**: `POST /api/v1/auth/login` 요청에서 JSON body(`loginId`, `password`) 파싱 → `UsernamePasswordAuthenticationToken` 생성 → `AuthenticationManager` 위임 → 성공 시 세션 수립.
- **주요 메서드/함수**:
  - `JsonLoginAuthenticationFilter(AuthenticationManager)` — `super(new AntPathRequestMatcher("/api/v1/auth/login", "POST"))` 호출. `setAuthenticationManager(authenticationManager)`.
  - `attemptAuthentication(HttpServletRequest, HttpServletResponse)` — `ObjectMapper`로 요청 바디 파싱 → `LoginRequest(loginId, password)` record → `UsernamePasswordAuthenticationToken(loginId, password)` 생성 → `getAuthenticationManager().authenticate(token)` 반환.
  - `successfulAuthentication(request, response, chain, authResult)` — `super.successfulAuthentication(...)` 호출 (SecurityContextRepository에 저장, 세션 생성). 이후 `response.setStatus(200)` + empty body 응답.
  - `unsuccessfulAuthentication(request, response, failed)` — `response.setStatus(401)` + JSON body `{"errorCode":"INVALID_CREDENTIALS","message":"아이디 또는 비밀번호가 올바르지 않습니다."}`.
- **의존성**: `AuthenticationManager`, `ObjectMapper`, `SecurityContextRepository`(세션 기반 자동 적용)
- **적용 컨벤션**:
  - `@Slf4j` 적용 (실패 시 `log.warn("Login failed for loginId: {}", ...)`
  - `LoginRequest`는 필터 내부 private record로 정의 (별도 파일 불필요 — `LoginRequest.java` 파일 제거)
  - `ObjectMapper`는 `@RequiredArgsConstructor`로 주입받거나 생성자 파라미터로 수신
  - `AbstractAuthenticationProcessingFilter` 상속 패턴: **develop에서 Spring Security 6.x 현행 API 확인 권장**
- **연관 todo**: `[ ] JsonLoginAuthenticationFilter 신설`

##### `MemberController` (재작성)
- **타입**: Controller (`@RestController`, `adapter.in.web` 패키지)
- **책임**: `signup()` 유지. `logout()` 세션 무효화로 재작성. `login()` 메서드 제거(필터가 처리).
- **주요 메서드/함수**:
  - `signup(@Valid @RequestBody SignupRequest)` — 기존과 동일. 변경 없음.
  - `logout(HttpSession session, HttpServletResponse response)` — `session.invalidate()` 호출. 세션 쿠키 만료 응답(Set-Cookie: SESSION=; Max-Age=0). `200 OK` 반환.
  - ~~`login()`~~ — 삭제 (JsonLoginAuthenticationFilter가 `/api/v1/auth/login` 경로를 필터 레벨에서 처리하므로 핸들러 불필요)
- **의존성**: `SignupUseCase` (LoginUseCase 의존 제거)
- **적용 컨벤션**:
  - `@RequiredArgsConstructor` — final 필드 기반 생성자 (기존 직접 생성자에서 변경)
  - `@Value` 환경변수 의존 제거 (`expirySeconds`, `cookieName` 삭제)
  - Javadoc 유지 (`signup`, `logout`)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberController.java:49` (signup 메서드 그대로 유지)
- **연관 todo**: `[ ] MemberController.login 재작성`, `[ ] MemberController.logout 재작성`

##### `GlobalExceptionHandler` (재작성)
- **타입**: Exception Handler (`@RestControllerAdvice`, `exception` 패키지)
- **책임**: `InvalidCredentialsException` 핸들러 제거(SAS/Security 파이프라인이 처리). 나머지 도메인 예외 핸들러 유지.
- **유지 항목**: `MethodArgumentNotValidException`, `MemberAlreadyExistsException`, `InvalidPasswordPolicyException`, `IllegalArgumentException`, `Exception` 핸들러
- **제거 항목**: `InvalidCredentialsException` `@ExceptionHandler` 메서드 (`JsonLoginAuthenticationFilter.unsuccessfulAuthentication`이 직접 응답)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java:71`
- **연관 todo**: `[ ] GlobalExceptionHandler 재작성`

---

#### 모듈 / 패키지: `services/libs/auth-core`

```
com.econo.auth.core.member/
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── SignupUseCase           — 유지
│   │   │   └── LoginUseCase            — 제거 (MemberUserDetailsService로 대체)
│   │   └── out/
│   │       ├── MemberRepository        — 유지
│   │       ├── PasswordHasher          — 유지
│   │       └── TokenIssuer             — 제거 (SAS가 토큰 발급 전담)
│   └── usecase/
│       ├── SignupService               — 유지
│       └── LoginService                — 제거
├── domain/
│   ├── Member                          — 유지 (프레임워크 독립 불변 객체)
│   └── MemberStatus                   — 유지
└── exception/
    ├── MemberAlreadyExistsException    — 유지
    ├── InvalidCredentialsException     — 유지 (auth-infra가 의존하므로 유지. 사용처가 없어지더라도 삭제는 별도 판단)
    └── InvalidPasswordPolicyException  — 유지
```

##### `TokenIssuer` 포트 제거
- **타입**: 제거 대상 인터페이스 (`application.port.out`)
- **제거 사유**: SAS가 JWT 발급 전담. 포트가 추상화하던 책임이 프레임워크 레벨로 이동.
- **제거 영향**: `LoginService` → `LoginService` 자체 제거. `ApplicationServiceConfig.loginService()` → 제거. `JwtTokenIssuerAdapter` → 제거.
- **연관 todo**: `[ ] auth-core — TokenIssuer 포트 제거`

##### `LoginUseCase` / `LoginService` 제거
- **제거 사유**: 모호함 #3 결정. `TokenIssuer` 제거 후 잔여 로직이 `MemberUserDetailsService`와 완전 중복.
- **테스트 연동**: `LoginServiceTest` 파일 삭제.
- **연관 todo**: `[ ] auth-core — TokenIssuer 포트 제거`

---

#### 모듈 / 패키지: `services/libs/auth-infra`

```
com.econo.auth.infra/
├── member/adapter/out/
│   ├── persistence/
│   │   ├── MemberJpaEntity             — 유지
│   │   ├── MemberJpaRepository         — 유지
│   │   └── MemberRepositoryAdapter     — 유지
│   ├── security/
│   │   └── BCryptPasswordHasherAdapter — 유지
│   └── token/
│       └── JwtTokenIssuerAdapter       — 제거
└── config/
    ├── InfraConfig                     — 유지
    └── JpaAuditingConfig               — 유지
```

##### `JwtTokenIssuerAdapter` 제거
- **타입**: 제거 대상 어댑터 (`adapter.out.token`)
- **제거 사유**: `TokenIssuer` 포트 제거에 연동. `jjwt` 3종 의존성도 `auth-infra/build.gradle.kts`에서 함께 제거.
- **테스트 연동**: `JwtTokenIssuerAdapterTest` 파일 삭제.
- **연관 todo**: `[ ] auth-infra — JwtTokenIssuerAdapter 제거`

---

#### 모듈 / 패키지: `services/apis/api-gateway`

```
com.econo.auth.gateway/
├── config/
│   └── GatewayRoutingConfig            — 변경 (/oauth2/**, /.well-known/**, /userinfo 경로 permitted 라우팅 추가)
├── filter/
│   └── BearerToPassportFilter          — 변경 (JwtCookieToPassportFilter 재작성, 클래스명 변경)
└── security/
    ├── JwtVerifier                     — 변경 (HMAC → ReactiveJwtDecoder JWKS 기반)
    └── PassportBuilder                 — 변경 (Claims → Jwt 타입 변경)
```

##### `JwtVerifier` (재작성)
- **타입**: Security Component (`@Component`, `security` 패키지)
- **책임**: `spring-boot-starter-oauth2-resource-server` 기반 `ReactiveJwtDecoder` 빈 구성. JWKS URI에서 RSA 공개키 fetch → RS256 서명 검증. 공개키 자동 갱신(rotation) 내장.
- **주요 메서드/함수**:
  - `jwtDecoder(@Value("${AUTH_JWKS_URI}") String jwksUri)` — `NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build()` 반환. `@Bean`. **`AUTH_JWKS_URI`는 auth-api 내부 주소를 직접 가리킨다** (예: `http://auth-api:8081/oauth2/jwks`). Gateway 공개 URL을 경유하지 않아 자기참조 루프를 방지한다. 토큰의 `iss` 클레임 검증은 `AUTH_ISSUER_URI`(= Gateway 공개 URL) 기준으로 수행하므로, JWKS fetch 호스트와 issuer 클레임 호스트가 달라도 무관하다.
  - `verify(String token)` — `jwtDecoder.decode(token)` 반환 (`Mono<Jwt>`). **또는 클래스를 Config 전용으로 변환하고 `ReactiveJwtDecoder` 빈만 노출한 후 `BearerToPassportFilter`가 직접 주입받는 방식도 가능** — 기존 `JwtVerifier` 인터페이스를 유지하되 내부 구현만 교체.
- **의존성**: `@Value("${AUTH_JWKS_URI}")`, Nimbus(spring-security-oauth2-resource-server 전이 의존)
- **적용 컨벤션**:
  - `JWT_SECRET` 환경변수 의존 완전 제거
  - `NimbusReactiveJwtDecoder`의 캐시 기본값(5분) 활용. 명시적 TTL 오버라이드는 선택사항.
  - **develop에서 `NimbusReactiveJwtDecoder.withJwkSetUri()` 현행 API 확인 권장**
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/JwtVerifier.java:17` (재작성 대상)
- **연관 todo**: `[ ] JwtVerifier 재작성`

##### `BearerToPassportFilter` (기존 `JwtCookieToPassportFilter` 재작성)
- **타입**: Filter (`@Component`, `@RequiredArgsConstructor`, `GlobalFilter` 구현)
- **책임**: `Authorization: Bearer <token>` 헤더에서 JWT 추출 → `JwtVerifier`(ReactiveJwtDecoder)로 검증 → `PassportBuilder`로 Passport 직렬화 → `X-User-Passport` 헤더 주입.
- **주요 메서드/함수**:
  - `filter(ServerWebExchange, GatewayFilterChain)` — 기존 `JwtCookieToPassportFilter.filter`와 동일 구조. 변경 부분:
    - `extractCookie()` → `extractBearerToken()`: `Authorization` 헤더에서 `Bearer ` prefix 제거 후 토큰 추출
    - `jwtVerifier.verify(token)` 반환 타입이 `Mono<Jwt>` → `flatMap` 체인으로 비동기 처리
    - 예외 타입: `JwtException`(jjwt) → `JwtValidationException` 또는 `BadJwtException`(Spring Security)
  - `isProtectedPath(String)` — `routingConfig.permittedPaths().stream().noneMatch(path::startsWith)` 유지
  - `rejectUnauthorized(ServerWebExchange)` — `HttpStatus.UNAUTHORIZED` 유지
- **의존성**: `JwtVerifier`, `PassportBuilder`, `GatewayRoutingConfig`
- **적용 컨벤션**:
  - `@Slf4j`, `@Component`, `@RequiredArgsConstructor`
  - 클래스명 `BearerToPassportFilter` (쿠키 → Bearer 변경 명시)
  - 기존 파일명(`JwtCookieToPassportFilter.java`) 삭제, 새 파일명(`BearerToPassportFilter.java`) 생성
  - Javadoc: "Bearer 토큰 → Passport 헤더 주입 GlobalFilter"
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/JwtCookieToPassportFilter.java:34` (filter 메서드 구조 미러링 후 수정)
- **연관 todo**: `[ ] JwtCookieToPassportFilter 재작성`

##### `PassportBuilder` (변경)
- **타입**: Security Component (`@Component`, `@RequiredArgsConstructor`)
- **책임**: SAS 발급 JWT 클레임(`org.springframework.security.oauth2.jwt.Jwt`)에서 Passport 생성 및 Base64 직렬화.
- **주요 메서드/함수**:
  - `buildAndSerialize(Jwt jwt)` — 기존 `buildAndSerialize(Claims claims)` 시그니처 변경. `Jwt` 타입으로 클레임 출처 변경.
  - `buildPassport(Jwt jwt)` — 기존 `buildPassport(Claims claims)` 변경:
    - `memberId`: `jwt.getClaimAsString("sub")` → `Long.valueOf(...)`
    - `loginId`: `jwt.getClaimAsString("loginId")`
    - `name`: `jwt.getClaimAsString("name")`
    - `generation`: `jwt.getClaim("generation")` → Integer 캐스팅
    - `status`: `jwt.getClaimAsString("status")`
    - `roles`: `jwt.getClaimAsStringList("roles")`
    - `issuedAt`: `jwt.getIssuedAt()` → `LocalDateTime.ofInstant(..., ZoneId.systemDefault())`
    - `expiresAt`: `jwt.getExpiresAt()` → 동일 변환
- **의존성**: `ObjectMapper`, `org.springframework.security.oauth2.jwt.Jwt`
- **적용 컨벤션**:
  - `jjwt` `Claims` 임포트 완전 제거
  - `Instant` → `LocalDateTime` 변환 헬퍼 `toLocalDateTime(Instant instant)` — 기존 `toLocalDateTime(Date date)` 대체
  - 에러 처리: `log.error()` + `RuntimeException` 래핑 유지
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/PassportBuilder.java:43` (buildPassport 메서드 구조 유지, 타입만 변경)
- **연관 todo**: `[ ] PassportBuilder 재확인`

##### `GatewayRoutingConfig` (변경)
- **타입**: Config (`@Configuration`, `@RequiredArgsConstructor`)
- **책임**: 라우팅 설정 유지. SAS OAuth 엔드포인트(`/oauth2/**`, `/.well-known/**`, `/userinfo`)를 **permitted 경로(Bearer 토큰 검증 제외)로 추가**하고 auth-api로 프록시 라우팅 구성.
- **변경 내용**:
  - `permittedPaths()` 반환값에 `/oauth2/`, `/.well-known/`, `/userinfo` 접두사 추가. 기존 목록(`/api/v1/auth/signup`, `/api/v1/auth/login`, `/api/v1/auth/logout`)은 유지.
  - `BearerToPassportFilter.isProtectedPath()`가 이 목록을 참조하므로, 위 경로는 Bearer 토큰 검증 없이 통과되어 auth-api로 그대로 프록시된다.
  - Spring Cloud Gateway `RouteLocator` 또는 `application.yml` 라우팅 설정에 다음 라우트를 추가:
    - `/oauth2/**` → auth-api (`lb://auth-api` 또는 직접 URL)
    - `/.well-known/**` → auth-api
    - `/userinfo` → auth-api
  - 라우트 필터에 `StripPrefix` 적용 여부 결정 — SAS 엔드포인트는 경로 prefix 제거 없이 그대로 전달해야 하므로 `StripPrefix=0` 또는 적용 안 함.
  - Javadoc 업데이트: "SAS OAuth 엔드포인트는 Gateway를 통해 auth-api로 라우팅됨. issuer는 `AUTH_ISSUER_URI`(Gateway 공개 URL) 기준이므로 토큰 내 엔드포인트 URL이 Gateway 도메인을 가리킴."
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java:39`
- **연관 todo**: `[ ] GatewayRoutingConfig 재확인`

---

### 빌드 파일 변경

#### `services/apis/auth-api/build.gradle.kts`
```kotlin
// 추가
implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
implementation("org.springframework.session:spring-session-jdbc")
// Nimbus JOSE+JWT는 spring-authorization-server 전이 의존으로 자동 포함 — 별도 추가 불필요

// 제거 대상 없음 (jjwt는 auth-infra에 있고 auth-api는 직접 의존하지 않음)
```

#### `services/libs/auth-infra/build.gradle.kts`
```kotlin
// 제거
// implementation("io.jsonwebtoken:jjwt-api:0.12.6")
// runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
// runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
```

#### `services/apis/api-gateway/build.gradle.kts`
```kotlin
// 추가
implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
// WebFlux + Spring Cloud Gateway 환경이므로 reactive 스타터 사용

// 제거
// implementation("io.jsonwebtoken:jjwt-api:0.12.6")
// runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
// runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
```

---

### 호출 흐름

#### [흐름 A] Authorization Code + PKCE (로그인 → 세션 → code → token)

정상 경로:
```
SPA → POST Gateway /api/v1/auth/login (JSON body: loginId, password)
  → GatewayRoutingConfig: permittedPath → BearerToPassportFilter 토큰 검증 SKIP
  → Gateway → auth-api POST /api/v1/auth/login
  → JsonLoginAuthenticationFilter.attemptAuthentication()
  → AuthenticationManager.authenticate(UsernamePasswordAuthenticationToken)
  → DaoAuthenticationProvider
    → MemberUserDetailsService.loadUserByUsername(loginId)
    → MemberRepository.findByLoginId(loginId) [auth-infra]
    → BCryptPasswordHasherAdapter.matches() [비교는 DaoAuthenticationProvider 내부]
  → 인증 성공 → SecurityContextRepository(세션) 저장
  → JsonLoginAuthenticationFilter.successfulAuthentication()
  → 200 OK + Set-Cookie: SESSION=...

SPA → GET Gateway /oauth2/authorize?response_type=code&client_id=...&code_challenge=...
  → GatewayRoutingConfig: permittedPath(/oauth2/**) → BearerToPassportFilter 토큰 검증 SKIP
  → Gateway → auth-api GET /oauth2/authorize
  → SAS AuthorizationEndpoint
  → SecurityContext에 세션 인증 확인 (인증됨)
  → Authorization Code 생성 → JdbcOAuth2AuthorizationService 저장
  → 302 Redirect → {redirect_uri}?code=...

SPA → POST Gateway /oauth2/token (code, code_verifier, client_id)
  → GatewayRoutingConfig: permittedPath(/oauth2/**) → BearerToPassportFilter 토큰 검증 SKIP
  → Gateway → auth-api POST /oauth2/token
  → SAS TokenEndpoint
  → PKCE 검증, Code 교환
  → JwtEncoder(NimbusJwtEncoder, RSA) → Access Token / ID Token / Refresh Token 생성
    → iss = Gateway 공개 URL (AuthorizationServerSettings.issuer = AUTH_ISSUER_URI)
  → PassportTokenCustomizer.customize() 호출
    → SecurityContext에서 MemberUserDetails 꺼냄
    → claims에 memberId, loginId, name, generation, status, roles 주입
  → 토큰 응답 (JSON)
```

예외 / 실패 경로:
```
[인증 실패]
  JsonLoginAuthenticationFilter.attemptAuthentication() → AuthenticationException
  → unsuccessfulAuthentication() → 401 + {"errorCode":"INVALID_CREDENTIALS",...}

[만료된 세션으로 /oauth2/authorize 접근]
  SAS AuthorizationEndpoint → 세션 없음 → authenticationEntryPoint 호출
  → 302 Redirect → {FRONTEND_LOGIN_URL}?redirect=...

[PKCE 검증 실패]
  SAS TokenEndpoint → OAuth2AuthorizationCodeRequestAuthenticationException
  → SAS 표준 에러 응답 (error: invalid_grant)

[잘못된 client_id]
  SAS TokenEndpoint → OAuth2AuthorizationCodeRequestAuthenticationException
  → SAS 표준 에러 응답 (error: invalid_client)
```

#### [흐름 B] api-gateway Bearer 토큰 → Passport 변환

정상 경로:
```
Client → API Gateway (Authorization: Bearer <SAS_JWT>)
  → BearerToPassportFilter.filter()
  → extractBearerToken() → JWT 문자열 추출
  → JwtVerifier(ReactiveJwtDecoder).decode(token) [Mono<Jwt>]
    → NimbusReactiveJwtDecoder → auth-api 내부 /oauth2/jwks 직접 fetch (캐시 TTL 내 재사용)
      (AUTH_JWKS_URI = http://auth-api:8081/oauth2/jwks — Gateway 공개 URL 경유 없이 직접 호출)
    → RSA 서명 검증, 만료 검증, iss 클레임 = AUTH_ISSUER_URI(Gateway 공개 URL) 검증
  → Jwt 반환
  → PassportBuilder.buildAndSerialize(jwt)
    → Jwt.getClaim("memberId"), "loginId", "name", "generation", "status", "roles"
    → Passport 생성 → JSON → Base64
  → ServerWebExchange.mutate().request(r -> r.header("X-User-Passport", encodedPassport))
  → chain.filter(mutatedExchange) → Downstream Service
```

예외 / 실패 경로:
```
[Bearer 헤더 없음]
  extractBearerToken() → Optional.empty()
  → isProtectedPath() == true → 401 Unauthorized
  → isProtectedPath() == false → chain.filter(exchange) 그대로 통과

[JWT 서명 오류 / 만료]
  NimbusReactiveJwtDecoder.decode() → BadJwtException / JwtValidationException
  → isProtectedPath() == true → 401 Unauthorized
  → isProtectedPath() == false → chain.filter(exchange) 그대로 통과

[JWKS 페치 실패 (auth-api 내부 직접 호출 장애)]
  NimbusReactiveJwtDecoder → WebClientResponseException 등
  → 503 또는 500 응답 (BearerToPassportFilter에서 catch 후 처리 — develop에서 에러 처리 전략 결정 권장)
```

---

### 컨벤션 준수 항목

- **네이밍**:
  - 신규 Config 클래스: `AuthorizationServerConfig`, `RegisteredClientConfig`, `OAuth2AuthorizationServiceConfig`, `RsaKeyConfig` — 접미사 `Config` 준수
  - 신규 Security 클래스: `MemberUserDetailsService`, `MemberUserDetails` — Spring Security 표준 명칭 채택
  - 신규 Filter 클래스: `JsonLoginAuthenticationFilter` — Spring Security 명칭 패턴 `{Adjective}AuthenticationFilter`
  - 재작성 Filter 클래스: `BearerToPassportFilter` — 책임 명시적 표현

- **의존성 주입**:
  - `@RequiredArgsConstructor` 우선 사용 (CONVENTION.md 2.2)
  - `RsaKeyConfig`, `JsonLoginAuthenticationFilter`처럼 생성자 내부 로직이 필요한 경우에만 직접 생성자 작성

- **예외 처리**:
  - 도메인 예외(`MemberAlreadyExistsException` 등)는 `GlobalExceptionHandler`에서 처리 유지
  - Spring Security 예외(`AuthenticationException`)는 SAS 파이프라인에 위임 — `GlobalExceptionHandler`에서 처리하지 않음
  - 정적 팩토리 메서드 패턴 유지 (CONVENTION.md 3.1)

- **불변성**:
  - `MemberUserDetails.getMember()` 반환 Member는 이미 불변 객체 (`private final` 필드)
  - `PassportBuilder.buildPassport()`의 roles 처리: `Passport` 생성자가 `List.copyOf()` 방어적 복사 내장 — 별도 처리 불필요

- **테스트 패턴**:
  - `@Nested` + `@DisplayName` 한글 테스트명 (CONVENTION.md 5.1)
  - Given-When-Then 주석 구분 (CONVENTION.md 5.2)
  - `JwtVerifierTest` 재작성: RSA 키페어 직접 생성 또는 `WireMock`으로 JWKS 엔드포인트 스텁
  - 통합 테스트: `@SpringBootTest` + `MockMvc` 유지

- **Javadoc**:
  - 모든 `public` 클래스와 메서드에 Javadoc 작성 (CONVENTION.md 4.1)
  - `PassportTokenCustomizer` Javadoc에 주입 클레임 목록 명시

- **Spotless**:
  - 신설/재작성 파일 전체 대상 `./gradlew format` 후 `spotlessCheck` 통과 필수

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨 (SAS 타입은 auth-api/config, auth-core 도메인 프레임워크 독립 유지)
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두 — 흐름 A, B)

---

## 미해결 / 개발 시 확인 필요 사항

다음 항목은 SAS 1.x / Spring Security 6.x 현행 API 정확성을 기동 직전 개발 문서에서 확인해야 한다:

1. `RsaKeyConfig`: PEM 문자열 파싱 API — `RsaKeyConverters`(Spring Security), Nimbus `PEMEncodedKeySpec`, 또는 `KeyFactory` + `PKCS8EncodedKeySpec` 중 SAS 1.x 권장 방식 확인
2. `AuthorizationServerConfig`: `authenticationEntryPoint` 외부 URL 리다이렉트 설정 — `LoginUrlAuthenticationEntryPoint` vs `HttpStatusEntryPoint` + 커스텀 처리
3. `RegisteredClientConfig`: `JdbcRegisteredClientRepository` 생성자 시그니처 — `JdbcOperations` 단독 vs `JdbcOperations + ObjectMapper` 오버로드
4. `PassportTokenCustomizer`: `JwtEncodingContext.getPrincipal()` 반환 타입과 `MemberUserDetails` 캐스팅 경로 확인
5. `JsonLoginAuthenticationFilter`: `AbstractAuthenticationProcessingFilter` Spring Security 6.x에서의 `SecurityContextRepository` 자동 세션 저장 여부 — 명시적 `setSecurityContextRepository()` 호출 필요 여부
6. `BearerToPassportFilter`: JWKS 페치 실패(auth-api 내부 직접 호출 장애) 시 503 vs 500 응답 처리 전략 결정
7. `spring-session-jdbc` 스키마 마이그레이션: `V3__create_spring_session_tables.sql` — Spring Session JDBC 공식 스크립트의 PostgreSQL 방언 호환성 확인
8. `GatewayRoutingConfig`: Spring Cloud Gateway `RouteLocator` Java DSL vs `application.yml` 방식 중 프로젝트 기존 설정 방식 확인. `/oauth2/**` 라우트에 `StripPrefix` 필터 적용 불필요(경로 그대로 전달) 확인.

---

## 참고
- `docs/ARCHITECTURE.md` — 헥사고날 아키텍처, 모듈 의존 관계
- `docs/CONVENTION.md` — 네이밍, Lombok, 예외 처리, 테스트 컨벤션
- `docs/INFRASTRUCTURE.md` — PostgreSQL/Flyway 스키마 컨벤션, 환경 변수 목록
- 기존 구현 참조 파일:
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberController.java`
  - `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/usecase/LoginService.java`
  - `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/token/JwtTokenIssuerAdapter.java`
  - `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/JwtCookieToPassportFilter.java`
  - `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/JwtVerifier.java`
  - `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/PassportBuilder.java`
