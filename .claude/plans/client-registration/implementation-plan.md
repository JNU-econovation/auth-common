# client-registration - implementation

## 메타
- **작업명**: client-registration
- **문서 타입**: implementation
- **작성일**: 2026-05-25
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

Spring Authorization Server(SAS 1.x)의 OIDC Dynamic Client Registration(DCR) 기능을 활성화한다.
핵심 작업은 두 가지다: (1) `AuthorizationServerConfig`의 `OAuth2AuthorizationServerConfigurer`에 `.clientRegistrationEndpoint(...)` 커스터마이저를 추가하여 RFC 7591 `POST /connect/register` 엔드포인트를 켜고, (2) `RegisteredClientConfig`에 `client_credentials` + `client.create`/`client.read` 스코프를 가진 registrar client를 기존 멱등 seed 패턴으로 추가한다.
엔드포인트를 직접 구현하지 않고 SAS 내장 기능을 설정으로 활성화하는 것이 전부이며, auth-core 도메인은 변경하지 않는다.
Java 21 / Spring Boot 3.2.2 / Spring Authorization Server 1.x / Gradle Kotlin DSL 멀티모듈 프로젝트 위에서 설계된다.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthorizationServerConfig.java` | 변경 | `oidc(Customizer.withDefaults())` → DCR 엔드포인트 활성화 커스터마이저로 교체. CORS에 `/connect/register` 추가 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java` | 변경 | registrar client seed 추가. `REGISTRAR_CLIENT_ID` / `REGISTRAR_CLIENT_SECRET` 환경변수 바인딩 |
| `services/apis/auth-api/src/main/resources/application.yml` | 변경 | `REGISTRAR_CLIENT_ID`, `REGISTRAR_CLIENT_SECRET` 바인딩 추가 |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` | 변경 | `permittedPaths()`에 `/connect/register` 추가. `/connect/register` 라우트 추가 |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/DcrIntegrationTest.java` | 신규 | DCR E2E 흐름 통합 테스트 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/apis/auth-api`

```
com.econo.auth.api/
├── config/
│   ├── AuthorizationServerConfig   — 변경: DCR 활성화 + CORS /connect/register 추가
│   └── RegisteredClientConfig      — 변경: registrar client seed 추가
└── (test)
    └── integration/
        └── DcrIntegrationTest      — 신규: DCR E2E 통합 테스트
```

---

##### `AuthorizationServerConfig` (변경)

- **타입**: Config (`@Configuration`, `@Order(1)`)
- **책임**: 기존 SAS 필터체인 설정을 유지하면서 OIDC DCR 엔드포인트(`POST /connect/register`)를 추가 활성화한다. CORS 설정에 `/connect/register` 경로를 추가한다.
- **변경 메서드**:
  - `authorizationServerSecurityFilterChain(HttpSecurity)` — 현재의

    ```java
    http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
        .oidc(Customizer.withDefaults());
    ```

    를 다음으로 교체:

    ```java
    http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
        .oidc(oidc -> oidc
            .clientRegistrationEndpoint(clientRegistration ->
                clientRegistration.authorizationRuleCustomizer(
                    authorizationManagerRequestMatcherRegistry ->
                        authorizationManagerRequestMatcherRegistry
                            .anyRequest()
                            .hasAuthority("SCOPE_client.create")
                )
            )
        );
    ```

    **[플래그 A]** SAS 1.x `OidcConfigurer.clientRegistrationEndpoint()` 커스터마이저 메서드의 정확한 시그니처와 `authorizationRuleCustomizer` 파라미터 타입은 `spring-authorization-server` 소스 또는 공식 가이드(https://docs.spring.io/spring-authorization-server/reference/guides/how-to-dynamic-client-registration.html)에서 implement 직전 확인 필요. 핵심 의도: `client.create` 스코프가 없는 토큰으로 `POST /connect/register`를 호출하면 403이 반환되어야 한다.

    **[플래그 B]** SAS가 `client.create` 스코프를 자동으로 요구하는지(기본값) 아니면 위 `authorizationRuleCustomizer`를 통해 명시적으로 등록해야 하는지 공식 문서로 확인 필요. 만약 SAS 기본 동작이 이미 `client.create` 스코프를 요구한다면 `clientRegistrationEndpoint(Customizer.withDefaults())`만으로 충분하다. 보안상 critical — 스코프 인가 규칙이 없으면 토큰 없이 등록 가능한 취약점이 발생한다.

  - `corsConfigurationSource()` — 기존 6개 경로 설정에 다음을 추가:

    ```java
    // /connect/register — DCR 엔드포인트 (서버 간 호출 전용: 프런트 오리진만 허용)
    CorsConfiguration dcrConfig = new CorsConfiguration();
    dcrConfig.addAllowedOrigin(corsAllowedOrigins);
    dcrConfig.addAllowedMethod("POST");
    dcrConfig.addAllowedMethod("GET");
    dcrConfig.addAllowedMethod("OPTIONS");
    dcrConfig.addAllowedHeader("*");
    dcrConfig.setAllowCredentials(false);
    source.registerCorsConfiguration("/connect/register", dcrConfig);
    // 관리 엔드포인트 (RFC 7592 GET)
    source.registerCorsConfiguration("/connect/register/*", dcrConfig);
    ```

    **CORS 정책 결정**: DCR은 서버 간(M2M) 호출이 주 용도이므로 `allowCredentials=false`. 오리진은 일단 `corsAllowedOrigins`로 제한하되, 운영에서 별도 관리 오리진이 필요하다면 `REGISTRAR_ALLOWED_ORIGINS` 환경변수로 분리 가능.

- **연관 todo**:
  - `[ ] AuthorizationServerConfig — oidc.clientRegistrationEndpoint(...) 활성화`
  - `[ ] AuthorizationServerConfig.corsConfigurationSource() — /connect/register CORS 항목 추가`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthorizationServerConfig.java:57` (현재 `oidc(Customizer.withDefaults())` 위치)

---

##### `RegisteredClientConfig` (변경)

- **타입**: Config (`@Configuration`)
- **책임**: 기존 `firstPartyPublicClient` 멱등 seed에 더해, `client_credentials` + `client.create`/`client.read` 스코프를 가진 confidential registrar client를 멱등 seed한다.
- **변경 내용**:
  - `@Value` 필드 추가:

    ```java
    @Value("${REGISTRAR_CLIENT_ID:registrar}")
    private String registrarClientId;

    @Value("${REGISTRAR_CLIENT_SECRET}")
    private String registrarClientSecret;
    ```

  - `registeredClientRepository(JdbcOperations)` 빈 메서드에 registrar client 멱등 seed 블록 추가:

    ```java
    if (repository.findByClientId(registrarClientId) == null) {
        repository.save(registrarConfidentialClient());
    }
    ```

  - `registrarConfidentialClient()` private 헬퍼 추가:

    ```java
    private RegisteredClient registrarConfidentialClient() {
        return RegisteredClient.withId("00000000-0000-0000-0000-000000000002")
            .clientId(registrarClientId)
            .clientName("Econo Registrar")
            .clientSecret("{bcrypt}" + new BCryptPasswordEncoder().encode(registrarClientSecret))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .scope("client.create")
            .scope("client.read")
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(false)
                    .requireAuthorizationConsent(false)
                    .build())
            .build();
    }
    ```

  **client_secret 인코딩**: SAS는 `client_secret` 필드에 `{bcrypt}` 접두사가 붙은 인코딩 값을 저장하는 것을 지원한다(`PasswordEncoderFactories.createDelegatingPasswordEncoder()` 형식). `BCryptPasswordEncoder().encode(rawSecret)`으로 해시 후 `{bcrypt}` 접두사를 붙여 `clientSecret()`에 전달한다. 기동마다 BCrypt 해시값이 달라지지만, `findByClientId()` 로 존재 여부를 먼저 확인하므로 이미 seed된 경우에는 덮어쓰지 않는다 — 멱등성 유지.

  **[플래그 C]** `BCryptPasswordEncoder` 직접 인스턴스화 vs 빈 주입 여부: `SecurityConfig`의 `passwordEncoder()` 빈(`BCryptPasswordEncoder(12)`)을 재사용할 수 있다. 단, `RegisteredClientConfig`에서 `PasswordEncoder` 빈을 주입받으면 `SecurityConfig`와 순환의존 위험이 있으므로, 별도 `BCryptPasswordEncoder` 직접 인스턴스화가 더 안전하다. cost 값은 `12`로 맞추거나, `{bcrypt}` 접두사 방식이면 SAS가 검증 시 cost를 자동으로 처리한다. 구현 시 SAS 1.x 공식 가이드의 confidential client secret 예시 확인 권장.

  **DCR 등록 client의 `requireAuthorizationConsent` 기본값**: SAS DCR이 등록하는 클라이언트에 `requireAuthorizationConsent(true)`를 강제하려면, `clientRegistrationEndpoint` 커스터마이저를 통해 `OidcClientRegistrationAuthenticationProvider`에 주입되는 `RegisteredClientConverter`를 커스터마이즈해야 한다. **[플래그 D]** SAS 1.x에서 DCR 등록 시 `ClientSettings` 기본값(특히 `requireAuthorizationConsent`)을 강제 주입하는 표준 hook 확인 필요. 만약 SAS가 제공하는 `RegisteredClientConverter`를 오버라이드해야 한다면, 해당 로직은 `AuthorizationServerConfig`의 `clientRegistrationEndpoint` 커스터마이저 내에서 처리한다.

- **연관 todo**:
  - `[ ] RegisteredClientConfig — registrar client 시드 추가`
  - `[ ] registrar client access token으로 /connect/register에 접근 가능한지 인가 검증`
  - `[ ] DCR로 등록된 client의 requireAuthorizationConsent 기본값 설정`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java:49` (`firstPartyPublicClient()` 헬퍼 패턴 미러링)

---

##### `application.yml` (변경)

- **변경 내용**: 환경변수 바인딩 2항목 추가

  ```yaml
  REGISTRAR_CLIENT_ID: ${REGISTRAR_CLIENT_ID:registrar}
  REGISTRAR_CLIENT_SECRET: ${REGISTRAR_CLIENT_SECRET}
  ```

  기본값 없는 `REGISTRAR_CLIENT_SECRET`는 운영 시 반드시 주입해야 하며, 누락 시 기동 실패가 의도된 동작이다.

- **연관 todo**: `[ ] 환경변수 추가 — REGISTRAR_CLIENT_ID, REGISTRAR_CLIENT_SECRET`

---

#### 모듈 / 패키지: `services/apis/api-gateway`

```
com.econo.auth.gateway/
└── config/
    └── GatewayRoutingConfig        — 변경: /connect/register 경로 permitted + 라우트 추가
```

##### `GatewayRoutingConfig` (변경)

- **타입**: Config (`@Configuration`, `@RequiredArgsConstructor`)
- **책임**: `POST /connect/register` 및 RFC 7592 관리 엔드포인트(`GET /connect/register/{clientId}`)를 permitted 경로로 추가하고 auth-api로 라우팅한다. DCR은 Bearer 토큰을 직접 포함하므로 Gateway에서 토큰 재검증을 skip해야 한다.
- **변경 내용**:
  - `routes(RouteLocatorBuilder)` 빈에 라우트 추가:

    ```java
    .route("dcr-register", r -> r.path("/connect/register", "/connect/register/**").uri(authApiUri))
    ```

    `StripPrefix` 없이 경로를 그대로 auth-api로 전달. SAS가 `/connect/register`를 그대로 처리한다.

  - `permittedPaths()` 반환 리스트에 `/connect/register` 추가:

    ```java
    return List.of(
        "/api/v1/auth/signup",
        "/api/v1/auth/login",
        "/api/v1/auth/logout",
        "/oauth2/",
        "/.well-known/",
        "/userinfo",
        "/connect/register"   // 추가
    );
    ```

    `BearerToPassportFilter.isProtectedPath()`가 `startsWith` 매칭을 사용하므로, `/connect/register`를 prefix로 등록하면 `/connect/register/{clientId}` 경로도 자동으로 skip된다.

  **이유**: DCR 엔드포인트의 Bearer 토큰은 SAS가 직접 검증한다. Gateway가 이 토큰을 Passport로 변환하려 하면 `registration_access_token` 구조가 다를 수 있어 실패한다. permitAll로 통과시키고 SAS가 자체 검증한다.

- **연관 todo**: `[ ] GatewayRoutingConfig 변경 — /connect/register 라우팅 추가`
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java:36` (기존 route + permittedPaths 패턴 미러링)

---

##### `DcrIntegrationTest` (신규)

- **타입**: 통합 테스트 (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@Testcontainers`)
- **위치**: `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/DcrIntegrationTest.java`
- **책임**: DCR E2E 흐름 검증. registrar client로 access token 발급 → `POST /connect/register` → `GET /connect/register/{clientId}` → 인가 검증.
- **테스트 시나리오**:
  1. `registrar client로 client_credentials 토큰 발급` — `POST /oauth2/token` (Basic Auth: registrar client id/secret, `grant_type=client_credentials`, `scope=client.create client.read`) → access_token 수신
  2. `DCR 등록 성공` — Bearer access_token으로 `POST /connect/register` (RFC 7591 메타데이터 JSON: `redirect_uris`, `grant_types`, `response_types`, `token_endpoint_auth_method`, `scope`, `client_name`) → `201 Created` + `client_id` + `client_secret` + `registration_access_token` + `registration_client_uri`
  3. `GET 관리 엔드포인트 (RFC 7592)` — registration_access_token으로 `GET /connect/register/{clientId}` → `200 OK` (SAS 1.x 지원 범위 확인 후 조건부 — [플래그 E])
  4. `인가 검증 — client.create 없는 토큰으로 403` — `client.read`만 가진 토큰(또는 scope 없는 토큰)으로 `POST /connect/register` → `400` 또는 `403` 확인
- **`@DynamicPropertySource` 추가 항목**:

  ```java
  registry.add("REGISTRAR_CLIENT_ID", () -> "registrar-test");
  registry.add("REGISTRAR_CLIENT_SECRET", () -> "registrar-secret-test-1!");
  // 기존 SAS 환경변수(RSA_PRIVATE_KEY, RSA_PUBLIC_KEY, AUTH_ISSUER_URI 등)는
  // SasAuthorizationServerIntegrationTest.TestRsaKeys 상수 재사용
  ```

- **적용 컨벤션**:
  - `@Nested` + `@DisplayName` 한글 테스트명 (CONVENTION.md 5.1)
  - Given-When-Then 주석 구분 (CONVENTION.md 5.2)
  - `SasAuthorizationServerIntegrationTest.TestRsaKeys` 상수를 import해서 PEM 키 재사용
  - `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` + `MockMvc` 유지
- **연관 todo**: `[ ] 통합 테스트 추가 — DCR E2E 흐름 검증`
- **참조할 기존 코드**: `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/SasAuthorizationServerIntegrationTest.java:47` (컨테이너/DynamicPropertySource/MockMvc 패턴 미러링)

---

### RFC 7592 지원 범위 — SAS 네이티브 한정

이번 작업에서 구현하는 RFC 7592 범위를 명확히 한정한다.

| 엔드포인트 | SAS 1.x 네이티브 지원 | 이번 작업 범위 |
|---|---|---|
| `POST /connect/register` (RFC 7591) | 지원 (DCR 활성화 시) | 포함 |
| `GET /connect/register/{clientId}` (RFC 7592 조회) | **[플래그 E]** SAS 1.x에서 RFC 7592 GET 지원 여부를 `OidcClientRegistrationEndpointFilter` 소스 및 공식 문서 확인 필요. SAS 1.2+에서는 GET 지원이 추가된 것으로 알려져 있으나 정확한 버전 경계 확인 필요 | SAS 지원 시 포함, 미지원 시 제외 |
| `PUT /connect/register/{clientId}` (RFC 7592 수정) | SAS 1.x 미지원 (공식 확인) | 제외 — 별도 작업으로 트래킹 |
| `DELETE /connect/register/{clientId}` (RFC 7592 삭제) | SAS 1.x 미지원 (공식 확인) | 제외 — 별도 작업으로 트래킹 |

커스텀 PUT/DELETE 구현은 `OidcClientRegistrationAuthenticationProvider` 확장 또는 별도 컨트롤러가 필요한 큰 작업이므로 이번 범위에서 제외한다.

---

### DB 작업 — 스키마 변경 없음 (조건부)

`registration_access_token`의 영속화 구조를 분석한 결론:

- SAS DCR이 발급하는 `registration_access_token`은 SAS 내부적으로 OAuth2 access token으로 처리된다. `JdbcOAuth2AuthorizationService`가 이를 기존 `oauth2_authorization.access_token_value` 컬럼에 저장한다.
- 현재 `oauth2_authorization` 테이블(V2)에 `access_token_value TEXT` 컬럼이 이미 존재하며, DCR용 별도 컬럼/테이블 추가가 불필요하다.
- **[플래그 F]** SAS 1.x `OidcClientRegistrationEndpointFilter` 소스코드를 통해 `registration_access_token` 처리 경로가 `JdbcOAuth2AuthorizationService`를 통하는지 최종 확인 필요. 신규 테이블이 필요하지 않다면 이 항목은 완료 처리. 필요하다면 `V4__dcr_registration_token.sql` 추가 (기존 V2 컨벤션 준수: PostgreSQL 방언, `COMMENT ON` 포함).

---

### 호출 흐름

#### [흐름 C] DCR — registrar client token 발급 → client 등록

정상 경로:
```
운영자 / M2M 서버
  → POST Gateway /oauth2/token
      Content-Type: application/x-www-form-urlencoded
      Authorization: Basic base64(registrarClientId:registrarClientSecret)
      grant_type=client_credentials&scope=client.create+client.read
  → GatewayRoutingConfig: permittedPath(/oauth2/) → BearerToPassportFilter SKIP
  → Gateway → auth-api POST /oauth2/token
  → SAS TokenEndpoint
      → ClientAuthenticationFilter: CLIENT_SECRET_BASIC 인증
      → JdbcRegisteredClientRepository.findByClientId(registrarClientId) → registrar client
      → client_credentials grant 처리
      → JwtEncoder(RSA) → access_token 생성 (scope: client.create client.read)
  → 200 OK + {"access_token": "...", "scope": "client.create client.read", ...}

운영자 / M2M 서버
  → POST Gateway /connect/register
      Authorization: Bearer <access_token>
      Content-Type: application/json
      { "redirect_uris": [...], "grant_types": [...], ... }
  → GatewayRoutingConfig: permittedPath(/connect/register) → BearerToPassportFilter SKIP
  → Gateway → auth-api POST /connect/register
  → SAS OidcClientRegistrationEndpointFilter
      → 스코프 인가 검증: SCOPE_client.create 필요
      → RegisteredClientConverter: RFC 7591 메타데이터 → RegisteredClient 변환
          (requireAuthorizationConsent 기본값 주입 — [플래그 D])
      → JdbcRegisteredClientRepository.save(newClient)
  → 201 Created + { "client_id": "...", "client_secret": "...",
                     "registration_access_token": "...",
                     "registration_client_uri": "{issuer}/connect/register/{clientId}" }
```

RFC 7592 조회 (SAS 지원 시):
```
운영자 / M2M 서버
  → GET Gateway /connect/register/{clientId}
      Authorization: Bearer <registration_access_token>
  → GatewayRoutingConfig: permittedPath(/connect/register) → BearerToPassportFilter SKIP
  → Gateway → auth-api GET /connect/register/{clientId}
  → SAS OidcClientRegistrationEndpointFilter (GET)
      → registration_access_token 검증
      → JdbcRegisteredClientRepository.findByClientId(clientId) → client 정보
  → 200 OK + { RFC 7591 client metadata }
```

예외 / 실패 경로:
```
[registrar client secret 오류]
  POST /oauth2/token → SAS TokenEndpoint → ClientAuthenticationException
  → SAS 표준 에러 응답: 401 + {"error": "invalid_client", "error_description": "..."}

[client.create 스코프 없는 토큰으로 POST /connect/register]
  SAS OidcClientRegistrationEndpointFilter → 인가 실패
  → 403 Forbidden (또는 SAS가 반환하는 정확한 에러 코드 — [플래그 B] 확인)

[잘못된 RFC 7591 메타데이터 (예: invalid_redirect_uri)]
  SAS OidcClientRegistrationEndpointFilter → OAuth2AuthenticationException
  → RFC 7591 형식 에러: 400 + {"error": "invalid_redirect_uri", "error_description": "..."}
  → 이 에러는 SAS가 직접 응답하므로 GlobalExceptionHandler를 거치지 않음.
    GlobalExceptionHandler와 충돌 여부를 DcrIntegrationTest에서 검증.

[REGISTRAR_CLIENT_SECRET 환경변수 미설정]
  애플리케이션 기동 실패 (BeanCreationException) — 의도된 동작
```

---

### 컨벤션 준수 항목

- **네이밍**:
  - 변경 대상 클래스명 유지: `AuthorizationServerConfig`, `RegisteredClientConfig`, `GatewayRoutingConfig` (CONVENTION.md 1.2 접미사 `Config`)
  - 신규 테스트 클래스: `DcrIntegrationTest` (CONVENTION.md 1.2 테스트 패턴 `{Domain}IntegrationTest`)
  - private 헬퍼 메서드: `registrarConfidentialClient()` — 기존 `firstPartyPublicClient()` 대칭 네이밍

- **의존성 주입**:
  - `@Value` 필드 주입 방식 유지 (기존 `RegisteredClientConfig` 패턴)
  - `BCryptPasswordEncoder` 직접 인스턴스화 — 빈 간 순환의존 방지

- **예외 처리**:
  - DCR 관련 SAS 에러는 SAS 파이프라인이 RFC 7591 형식으로 처리 → `GlobalExceptionHandler` 불관여
  - `REGISTRAR_CLIENT_SECRET` 미설정 시 기동 실패 — Spring `@Value` 기본 동작으로 처리 (별도 방어 코드 불필요)

- **불변성**:
  - `RegisteredClient`는 SAS의 빌더 패턴 불변 객체 — 별도 방어적 복사 불필요

- **테스트 패턴**:
  - `@Nested` + `@DisplayName` 한글 테스트명 (CONVENTION.md 5.1)
  - Given-When-Then 주석 구분 (CONVENTION.md 5.2)
  - `@SpringBootTest` + `MockMvc` + Testcontainers PostgreSQL (CONVENTION.md 5.3)
  - `SasAuthorizationServerIntegrationTest.TestRsaKeys` 재사용 — PEM 키 중복 선언 방지

- **Javadoc**:
  - 변경된 모든 `public` 메서드에 Javadoc 갱신 (`@param`, `@return` 필수) (CONVENTION.md 4.1)
  - private 헬퍼 `registrarConfidentialClient()` — 한 줄 주석 형태 (CONVENTION.md 4.2)

- **Spotless**:
  - 변경/신규 파일 전체 대상 `./gradlew format` + `spotlessCheck` 통과 필수 (CONVENTION.md 2.1)

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨 (SAS 타입은 auth-api/config, auth-core 도메인 불변)
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두 — 흐름 C)

---

## 플래그 목록 (구현 전 확인 필수)

| ID | 항목 | 관련 구성 요소 | 영향도 |
|---|---|---|---|
| A | `OidcConfigurer.clientRegistrationEndpoint()` 커스터마이저 메서드 정확한 시그니처 | `AuthorizationServerConfig` | 컴파일 오류 가능 |
| B | SAS가 `client.create` 스코프를 기본으로 요구하는지 vs `authorizationRuleCustomizer` 명시 필요 여부 | `AuthorizationServerConfig` | **보안 critical** — 잘못되면 토큰 없이 등록 가능하거나 정상 토큰도 403 |
| C | confidential client_secret 저장 방식: `{bcrypt}` 접두사 + `BCryptPasswordEncoder` vs `PasswordEncoderFactories` 위임 방식 | `RegisteredClientConfig` | 런타임 인증 실패 가능 |
| D | DCR 등록 시 `requireAuthorizationConsent(true)` 기본값 강제 주입 hook — `RegisteredClientConverter` 오버라이드 방법 | `AuthorizationServerConfig` | 3rd-party client 동의 정책 |
| E | SAS 1.x `GET /connect/register/{clientId}` (RFC 7592) 네이티브 지원 여부 | `DcrIntegrationTest` 시나리오 3 | 테스트 포함/제외 결정 |
| F | `registration_access_token` 영속화 경로 — `oauth2_authorization` 테이블 재사용 여부 | DB 마이그레이션 (조건부 V4) | 스키마 변경 발생 가능 |

---

## 참고
- `docs/CONVENTION.md` — 네이밍, Lombok, 예외 처리, 테스트 컨벤션
- `docs/INFRASTRUCTURE.md` — 환경변수 목록, Flyway 버전 현황
- `docs/ARCHITECTURE.md` — 헥사고날 아키텍처, auth-core 도메인 불변 원칙
- 기존 구현 참조 파일:
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthorizationServerConfig.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java`
  - `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java`
  - `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/SasAuthorizationServerIntegrationTest.java`
  - `services/libs/auth-infra/src/main/resources/db/migration/V2__create_sas_tables.sql`
- SAS 1.x 공식 가이드: https://docs.spring.io/spring-authorization-server/reference/guides/how-to-dynamic-client-registration.html
- RFC 7591 (Dynamic Client Registration): https://datatracker.ietf.org/doc/html/rfc7591
- RFC 7592 (Dynamic Client Registration Management): https://datatracker.ietf.org/doc/html/rfc7592
