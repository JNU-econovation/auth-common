# backend-decided-login-redirect - implementation

## 메타
- **작업명**: backend-decided-login-redirect
- **문서 타입**: implementation
- **작성일**: 2026-06-07
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

`POST /api/v1/auth/login`(JsonLoginAuthenticationFilter)의 WEB 분기를 returnUrl 화이트리스트 방식에서 clientId 기반 302 리다이렉트 방식으로 전면 교체한다. 클라이언트가 전달한 clientId로 SAS에 등록된 redirect_uri를 백엔드가 직접 조회해 302 목적지를 결정하므로, user-supplied URL을 사용하지 않아 open redirect가 구조적으로 불가능하다. 구현 대상은 `services:apis:auth-api` 모듈 단독이며, `services:libs:service-client`의 `ClientRedirectUriService.findByClientId()`를 읽기 전용으로 재사용한다. 스택은 Java 17, Spring Boot 3.x, Spring Security 6 Servlet Filter 방식이다.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services:apis:auth-api` · `com.econo.auth.api.application.ReturnUrlValidator` | **삭제** | returnUrl 화이트리스트 방식 폐기. `LoginRedirectResolver`로 완전 대체. |
| `services:apis:auth-api` · `com.econo.auth.api.application.LoginRedirectResolver` | **신규** | clientId → redirect_uri 결정 로직. `LoginTokenService`와 같은 application 패키지. |
| `services:apis:auth-api` · `com.econo.auth.api.config.ApplicationServiceConfig` | **변경** | `returnUrlValidator()` 빈 제거 + `loginRedirectResolver()` 빈 추가. |
| `services:apis:auth-api` · `com.econo.auth.api.config.SecurityConfig` | **변경** | `ReturnUrlValidator` 파라미터 제거, `LoginRedirectResolver` 파라미터 추가. null-check 조건 교체. |
| `services:apis:auth-api` · `com.econo.auth.api.filter.JsonLoginAuthenticationFilter` | **변경** | 생성자 교체, `LoginRequest` record에 `clientId` 추가, `attemptAuthentication`에서 `setAttribute`, WEB 분기 sendRedirect 로직 교체. |
| `services:apis:auth-api` · `com.econo.auth.api.config.AuthRedirectProperties` | **변경** | 클래스 본문 유지, Javadoc 문구만 수정 (returnUrl 검증 → clientId 미전달·미등록·redirect_uri 없음). |
| `services:apis:auth-api` · `resources/application.yml` | **변경** | `auth.redirect.default-url` 주석 수정. |
| `services:apis:auth-api` (test) · `com.econo.auth.api.application.ReturnUrlValidatorTest` | **삭제** | 폐기되는 `ReturnUrlValidator`에 대한 테스트. |
| `services:apis:auth-api` (test) · `com.econo.auth.api.application.LoginRedirectResolverTest` | **신규** | `@ExtendWith(MockitoExtension.class)` 단위 테스트. |
| `services:apis:auth-api` (test) · `com.econo.auth.api.integration.AuthApiIntegrationTest` | **변경** | `WebLoginTest` 내 시나리오를 clientId 기반으로 교체 및 추가. |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services:apis:auth-api`

```
com.econo.auth.api/
├── application/
│   ├── LoginTokenService.java             — 기존 (변경 없음)
│   ├── ReturnUrlValidator.java            — 삭제 대상
│   └── LoginRedirectResolver.java         — 신규: clientId → redirect_uri 결정
├── config/
│   ├── AuthRedirectProperties.java        — Javadoc 문구 수정 (구현 변경 없음)
│   ├── ApplicationServiceConfig.java      — returnUrlValidator 빈 제거, loginRedirectResolver 빈 추가
│   └── SecurityConfig.java               — ReturnUrlValidator → LoginRedirectResolver 파라미터 교체
└── filter/
    └── JsonLoginAuthenticationFilter.java — LoginRequest에 clientId 추가, WEB 분기 교체

(test)
com.econo.auth.api/
├── application/
│   ├── ReturnUrlValidatorTest.java        — 삭제 대상
│   └── LoginRedirectResolverTest.java     — 신규 단위 테스트
└── integration/
    └── AuthApiIntegrationTest.java        — WebLoginTest 시나리오 교체·추가
```

---

##### `ReturnUrlValidator` (삭제)
- **타입**: 삭제 대상 파일
- **조치**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/ReturnUrlValidator.java` 파일 삭제.
- **연관 todo**: `[ ] ReturnUrlValidator 클래스 삭제`

---

##### `LoginRedirectResolver` (신규)
- **타입**: Service (Application Layer)
- **위치**: `com.econo.auth.api.application.LoginRedirectResolver`
- **책임**: `clientId`를 받아 `ClientRedirectUriService.findByClientId()`로 등록된 redirect_uri Set을 조회하고 결정적으로 첫 번째 URI를 반환한다. clientId가 null·blank이거나 `InvalidClientException`이 발생하거나 redirectUris가 비어 있으면 `defaultUrl`을 반환한다.
- **주요 메서드/함수**:
  - `resolve(String clientId, String defaultUrl): String`
    - `clientId`가 null이거나 `isBlank()`이면 즉시 `defaultUrl` 반환
    - `clientRedirectUriService.findByClientId(clientId)` 호출
    - `InvalidClientException` catch → `defaultUrl` 반환
    - `ClientInfo.redirectUris()`가 비어 있으면 `defaultUrl` 반환
    - `redirectUris`가 1개이면 그것을 반환
    - 복수이면 `redirectUris.stream().sorted().findFirst().orElse(defaultUrl)` — 알파벳 오름차순 정렬 후 첫 번째 (SAS `RegisteredClient` Set 순서 비보장 한계 반영)
- **의존성**: `ClientRedirectUriService` (service-client 모듈, 기존 `@Service` 빈)
- **적용 컨벤션**:
  - `@RequiredArgsConstructor`를 사용하지 않는다. `ApplicationServiceConfig`에서 `@Bean`으로 등록하므로 직접 생성자 선언이 필요하다. Lombok `@RequiredArgsConstructor`는 `@Component` 직접 선언 방식에 주로 사용되며, 이 클래스는 `ApplicationServiceConfig`에서 수동 등록된다.
  - 예외 처리: `InvalidClientException`은 `try-catch`로 흡수하여 `defaultUrl` 반환. `Exception`은 밖으로 전파하지 않는다(open redirect 방어 원칙과 동일).
  - 클래스 레벨 Javadoc 작성 (`@param`, `@return` 포함) — CONVENTION.md 4.1 준수.
  - 상태 없는 stateless 서비스. 필드는 `final` `ClientRedirectUriService` 하나.
- **참조할 기존 코드**:
  - `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ClientRedirectUriService.java:40-47` (`findByClientId` 시그니처, `InvalidClientException` 발생 조건)
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java:27-42` (기존 `@Bean` 등록 패턴)
- **연관 todo**:
  - `[ ] LoginRedirectResolver 클래스 신규 생성`
  - `[ ] ApplicationServiceConfig에 LoginRedirectResolver 빈 등록 추가`

---

##### `ApplicationServiceConfig` (변경)
- **타입**: Configuration
- **변경 내용**:
  1. `import com.econo.auth.api.application.ReturnUrlValidator` 제거.
  2. `returnUrlValidator(ClientRedirectUriService)` `@Bean` 메서드 삭제.
  3. `import com.econo.auth.api.application.LoginRedirectResolver` 추가.
  4. `loginRedirectResolver(ClientRedirectUriService)` `@Bean` 메서드 추가.
- **주요 메서드/함수**:
  ```
  @Bean
  LoginRedirectResolver loginRedirectResolver(ClientRedirectUriService clientRedirectUriService)
  ```
  반환: `new LoginRedirectResolver(clientRedirectUriService)`
- **의존성**: `ClientRedirectUriService`
- **적용 컨벤션**:
  - 기존 `signupService(...)` 메서드와 동일 형태의 `@Bean` 선언. 메서드명은 camelCase, 클래스명과 대응.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java:27-42`
- **연관 todo**:
  - `[ ] ApplicationServiceConfig의 ReturnUrlValidator 빈 등록 코드 제거`
  - `[ ] ApplicationServiceConfig에 LoginRedirectResolver 빈 등록 추가`

---

##### `SecurityConfig` (변경)
- **타입**: Configuration (`@EnableWebSecurity`)
- **변경 내용**:
  1. `import com.econo.auth.api.application.ReturnUrlValidator` 제거.
  2. `import com.econo.auth.api.application.LoginRedirectResolver` 추가.
  3. `appSecurityFilterChain(...)` 파라미터에서 `@Autowired(required=false) ReturnUrlValidator returnUrlValidator` 제거, `@Autowired(required=false) LoginRedirectResolver loginRedirectResolver` 추가.
  4. null-check 조건: `returnUrlValidator != null` → `loginRedirectResolver != null` 로 교체.
  5. `new JsonLoginAuthenticationFilter(...)` 인수에서 `returnUrlValidator` → `loginRedirectResolver` 로 교체.
- **주요 메서드/함수**: `appSecurityFilterChain(HttpSecurity, AuthenticationManager, LoginTokenService, TokenCookieManager, LoginRedirectResolver, AuthRedirectProperties)`
- **적용 컨벤션**:
  - `@Autowired(required = false)` null-check 패턴은 기존 방식 그대로 유지. `LoginRedirectResolver`는 `ApplicationServiceConfig`에 명시적으로 등록되므로 실제로 null이 되지 않지만, 테스트 슬라이스 컨텍스트 호환성을 위해 패턴을 보존한다.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java:43-92`
- **연관 todo**:
  - `[ ] SecurityConfig의 ReturnUrlValidator 의존성 주입 코드 제거`
  - `[ ] SecurityConfig에 LoginRedirectResolver 주입으로 교체`

---

##### `JsonLoginAuthenticationFilter` (변경)
- **타입**: Security Filter (`AbstractAuthenticationProcessingFilter` 확장)
- **변경 내용**:

  1. **import 교체**: `ReturnUrlValidator` import 제거, `LoginRedirectResolver` import 추가.

  2. **필드 교체**: `ReturnUrlValidator returnUrlValidator` → `LoginRedirectResolver loginRedirectResolver`.

  3. **생성자 시그니처 교체**:
     ```
     public JsonLoginAuthenticationFilter(
         AuthenticationManager authenticationManager,
         ObjectMapper objectMapper,
         LoginTokenService loginTokenService,
         TokenCookieManager cookieManager,
         LoginRedirectResolver loginRedirectResolver,
         String defaultRedirectUrl)
     ```

  4. **`LoginRequest` record 확장** (파일 말미의 private record):
     ```
     private record LoginRequest(String loginId, String password, String clientId) {}
     ```
     Jackson은 JSON에 `clientId` 필드 없으면 null 처리. InputStream 단일 소비 내에서 함께 파싱.

  5. **`attemptAuthentication()` 수정**:
     - `objectMapper.readValue(request.getInputStream(), LoginRequest.class)` — 기존과 동일, `LoginRequest`에 `clientId` 필드가 추가된 것으로 자동 파싱.
     - 파싱 후 `request.setAttribute("clientId", loginRequest.clientId())` 추가 — InputStream을 재소비하지 않고 `successfulAuthentication`에서 읽을 수 있도록.
     - `loginId`, `password` null 처리 코드는 기존 유지.

  6. **`successfulAuthentication()` WEB 분기 교체**:
     - 쿠키 세팅 순서 유지 (sendRedirect 전에 반드시 수행):
       ```
       cookieManager.setAtCookie(response, tokens.accessToken());
       cookieManager.setRtCookie(response, tokens.refreshToken());
       ```
     - returnUrl 쿼리 파라미터 읽기 코드(`request.getParameter("returnUrl")`) 제거.
     - 대신:
       ```
       String clientId = (String) request.getAttribute("clientId");
       String target = loginRedirectResolver.resolve(clientId, defaultRedirectUrl);
       response.sendRedirect(target);
       ```
     - `response.setStatus(SC_OK)`, `response.setContentType(...)`, `objectMapper.writeValue(...)` 등 WEB body 직렬화 코드 전부 제거.

  7. **Javadoc 수정**: 클래스 Javadoc의 `returnUrl 검증 후 302 리다이렉트` → `clientId로 redirect_uri 조회 후 302 리다이렉트`.

- **의존성**: `AuthenticationManager`, `ObjectMapper`, `LoginTokenService`, `TokenCookieManager`, `LoginRedirectResolver`, `String defaultRedirectUrl`
- **적용 컨벤션**:
  - `NullSecurityContextRepository` 세팅, `CLIENT_TYPE_HEADER = "Client-Type"` 상수, APP 분기 직렬화 코드 — 변경 없이 유지.
  - `unsuccessfulAuthentication()` — 변경 없음.
  - `LoginRequest` private record는 파일 말미에 유지. 필드 순서: `loginId`, `password`, `clientId` (기존 필드 뒤에 추가).
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java:45-128`
- **연관 todo**:
  - `[ ] LoginRequest 내부 record에 clientId 필드 추가`
  - `[ ] attemptAuthentication에서 clientId를 request.setAttribute로 저장`
  - `[ ] successfulAuthentication() WEB 분기 수정`
  - `[ ] JsonLoginAuthenticationFilter 생성자에서 ReturnUrlValidator 파라미터 제거, LoginRedirectResolver 파라미터 추가`

---

##### `AuthRedirectProperties` (변경 — Javadoc만)
- **타입**: Configuration Properties (`@ConfigurationProperties(prefix = "auth.redirect")`)
- **변경 내용**: 클래스 및 필드 Javadoc에서 "returnUrl 검증 실패·미전달" 문구를 "clientId 미전달·미등록·redirect_uri 없음" 문구로 교체. 클래스 본문(`@Getter`, `@Setter`, `defaultUrl` 필드) 변경 없음.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthRedirectProperties.java:1-25`
- **연관 todo**: `[ ] AuthRedirectProperties Javadoc 문구 수정`

---

##### `application.yml` (변경 — 주석만)
- **변경 내용**:
  - `auth.redirect.default-url` 위의 주석을 "clientId 미전달·미등록·redirect_uri 없음 시 302 fallback 목적지"로 교체.
  - `auth.frontend-login-url` 주석에 역할 차이 명시 추가: `frontend-login-url`은 SAS `/oauth2/authorize` 미인증 진입 시 SPA 로그인 페이지 리다이렉트 전용(경로 B), `redirect.default-url`은 `JsonLoginAuthenticationFilter` WEB 분기 302 fallback 전용(경로 A).
  - 설정 키 값 자체는 변경 없음.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/resources/application.yml:30-38`
- **연관 todo**:
  - `[ ] application.yml의 auth.redirect.default-url 주석 수정`
  - `[ ] auth.frontend-login-url과 auth.redirect.default-url의 역할 차이 주석 명시`

---

##### `ReturnUrlValidatorTest` (삭제)
- **타입**: 삭제 대상 파일
- **조치**: `services/apis/auth-api/src/test/java/com/econo/auth/api/application/ReturnUrlValidatorTest.java` 파일 삭제.
- **연관 todo**: `[ ] ReturnUrlValidatorTest 클래스 삭제`

---

##### `LoginRedirectResolverTest` (신규)
- **타입**: 단위 테스트 (`@ExtendWith(MockitoExtension.class)`)
- **위치**: `services/apis/auth-api/src/test/java/com/econo/auth/api/application/LoginRedirectResolverTest.java`
- **책임**: `LoginRedirectResolver.resolve()` 분기 전수 검증.
- **구조**:
  ```
  @ExtendWith(MockitoExtension.class)
  class LoginRedirectResolverTest {
      @Mock ClientRedirectUriService clientRedirectUriService;
      private LoginRedirectResolver resolver;

      @BeforeEach
      void setUp() {
          resolver = new LoginRedirectResolver(clientRedirectUriService);
      }

      @Nested @DisplayName("resolve — clientId 기반 redirect_uri 결정")
      class ResolveTest { ... }
  }
  ```
- **주요 테스트 케이스** (`@Test` + `@DisplayName` 한글):
  - `null clientId → defaultUrl 반환` — `given()` 없이 직접 호출, `assertThat(result).isEqualTo(defaultUrl)`
  - `빈 문자열 clientId → defaultUrl 반환`
  - `미등록 clientId(InvalidClientException) → defaultUrl 반환` — `given(clientRedirectUriService.findByClientId("unknown")).willThrow(new InvalidClientException())`, `assertThat(result).isEqualTo(defaultUrl)`
  - `redirect_uri 1개인 클라이언트 → 그 URI 반환` — `ClientInfo` stub 구성 후 `assertThat(result).isEqualTo("https://app.example.com/callback")`
  - `redirect_uri 여러 개인 클라이언트 → 알파벳 정렬 후 첫 번째 URI 반환` — `Set.of("https://z.example.com", "https://a.example.com")` stub, `assertThat(result).isEqualTo("https://a.example.com")`
  - `redirect_uri Set이 비어 있는 클라이언트 → defaultUrl 반환` — `new ClientInfo(clientId, "name", Set.of())` stub
- **적용 컨벤션**:
  - `@BeforeEach`에서 `new LoginRedirectResolver(clientRedirectUriService)` 직접 생성 (`@InjectMocks` 미사용 — `LoginTokenServiceTest` 패턴 동일).
  - BDDMockito `given(...).willReturn(...)` / `given(...).willThrow(...)`.
  - AssertJ `assertThat(result).isEqualTo(...)`.
  - Given-When-Then 주석 구분.
- **참조할 기존 코드**: `services/apis/auth-api/src/test/java/com/econo/auth/api/application/LoginTokenServiceTest.java:26-38` (setUp 패턴)
- **연관 todo**: `[ ] LoginRedirectResolverTest 단위 테스트 작성`

---

##### `AuthApiIntegrationTest` (변경)
- **변경 내용**:
  1. **`WebLoginTest` 내 기존 테스트 수정**:
     - `web_login_without_returnUrl_redirects_to_defaultUrl` → `web_login_without_clientId_redirects_to_defaultUrl`로 `@DisplayName` 변경. request body에 `clientId` 필드 없이 호출, `Location`이 `http://localhost:3000`임을 검증. 기존 body content는 `{"loginId":"webuser04","password":"Econo1234!"}` 그대로 유지 — clientId 미전달 시 defaultUrl 동작을 이미 커버.
     - `web_login_unregistered_origin_redirects_to_defaultUrl` 테스트: returnUrl 쿼리 파라미터를 사용하던 기존 코드에서 미등록 clientId body 필드 방식으로 교체. `?returnUrl=https://evil.com/steal` 쿼리 파라미터 제거, body에 `"clientId":"nonexistent-client-id"` 추가. `Location` 헤더가 `http://localhost:3000`임을 검증 (4xx 아님).
  2. **`WebLoginTest`에 신규 테스트 추가**:
     - `등록된 clientId + redirect_uri 1개 → 해당 URI로 302`:
       - 선행 조건: `AdminClientRegistrationTest`에서 클라이언트 등록 후 `clientId` 추출 (또는 `@BeforeEach` 내에서 클라이언트 등록 헬퍼 호출).
       - `"clientId":"<등록된clientId>"` body 전달 → `Location` 헤더가 등록한 `redirectUri` 값임을 검증.
     - `등록된 clientId + redirect_uri 여러 개 → 정렬 후 첫 번째로 302`:
       - 복수 redirectUri를 가진 클라이언트 등록 후 동일 검증. `Location`이 알파벳 정렬 기준 첫 번째 URI임을 검증.
  3. **`login()` 헬퍼 메서드 오버로드 추가**:
     ```java
     private MvcResult login(String loginId, String clientType, String clientId) throws Exception
     ```
     body에 `"clientId":"..."` 포함 버전. 기존 `login(String, String)` 유지.
  4. **`AppLoginTest`**: clientId body 필드가 추가돼도 APP 분기가 `200 OK + body`를 유지하는지 확인 테스트 추가. 기존 테스트 `app_login_returns_tokens_in_body`의 request body에 `"clientId":"some-id"` 추가 가능, 기대 결과 변경 없음.
  5. **`DynamicPropertySource`**: `auth.redirect.default-url` 키는 이미 `"http://localhost:3000"`으로 등록되어 있으므로 변경 불필요 (`AuthApiIntegrationTest.java:65` 참고).
- **적용 컨벤션**:
  - 기존 `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` + `@ActiveProfiles("test")` 구조 유지.
  - `@Nested` + `@DisplayName` 한글.
  - 헬퍼 메서드는 `private` + camelCase.
- **참조할 기존 코드**: `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java:130-290` (`WebLoginTest` 블록)
- **연관 todo**:
  - `[ ] AuthApiIntegrationTest 기존 WEB 로그인 테스트 수정`
  - `[ ] AuthApiIntegrationTest에 WEB 로그인 clientId 기반 리다이렉트 시나리오 추가`
  - `[ ] AuthApiIntegrationTest APP 로그인 테스트는 변경 없음 확인`

---

### 호출 흐름

#### 정상 경로 — WEB 클라이언트 (clientId 있음, 등록된 클라이언트)

```
클라이언트
  POST /api/v1/auth/login
  Header: Client-Type: WEB
  Body: {"loginId":"user@example.com", "password":"...", "clientId":"eeos-web"}

→ JsonLoginAuthenticationFilter.attemptAuthentication()
    objectMapper.readValue(inputStream, LoginRequest.class)
    → LoginRequest(loginId="user@example.com", password="...", clientId="eeos-web")
    request.setAttribute("clientId", "eeos-web")        ← InputStream 단일 소비 이후에도 전달 가능
    authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(...))

→ DaoAuthenticationProvider → MemberUserDetailsService.loadUserByUsername()
    인증 성공 → MemberUserDetails 반환

→ JsonLoginAuthenticationFilter.successfulAuthentication()
    loginTokenService.issue(member) → TokenPair(at, rt, expiredAt)
    clientType = request.getHeader("Client-Type") → "WEB"
    isApp = false

    [WEB 분기]
    cookieManager.setAtCookie(response, at)     → Set-Cookie: at=...; HttpOnly; SameSite=None
    cookieManager.setRtCookie(response, rt)     → Set-Cookie: rt=...; HttpOnly; SameSite=None
    clientId = (String) request.getAttribute("clientId")   → "eeos-web"
    target = loginRedirectResolver.resolve("eeos-web", "http://localhost:3000")
      → clientRedirectUriService.findByClientId("eeos-web")
        → ClientInfo(clientId="eeos-web", clientName="EEOS", redirectUris={"https://app.econovation.kr/callback"})
      redirectUris.size() == 1 → target = "https://app.econovation.kr/callback"
    response.sendRedirect("https://app.econovation.kr/callback")

← HTTP 302 Found
   Location: https://app.econovation.kr/callback
   Set-Cookie: at=...; HttpOnly; SameSite=None; ...
   Set-Cookie: rt=...; HttpOnly; SameSite=None; ...
   (body 없음)
```

#### 정상 경로 — WEB 클라이언트 (clientId 없음 → defaultUrl)

```
Body: {"loginId":"...", "password":"..."}   ← clientId 필드 없음

→ attemptAuthentication()
    LoginRequest(loginId, password, clientId=null)
    request.setAttribute("clientId", null)

→ successfulAuthentication() WEB 분기
    clientId = null
    loginRedirectResolver.resolve(null, "http://localhost:3000")
      → clientId가 null → 즉시 "http://localhost:3000" 반환
    cookieManager 세팅 후 response.sendRedirect("http://localhost:3000")

← HTTP 302 Found
   Location: http://localhost:3000
```

#### 정상 경로 — APP 클라이언트 (기존 유지)

```
Header: Client-Type: APP
Body: {"loginId":"...", "password":"...", "clientId":"some-id"}  ← 파싱하되 무시

→ successfulAuthentication() isApp = true

    [APP 분기] — 기존과 동일
    body = LoginResponse.app(at, expiredAt, rt)
    response.setStatus(200) + objectMapper.writeValue(response.getWriter(), body)

← HTTP 200 OK
   Body: {"accessToken":"...","accessExpiredTime":...,"refreshToken":"..."}
```

#### 예외 / 실패 경로

```
[미등록 clientId — InvalidClientException]
  clientId = "unknown-client"
  loginRedirectResolver.resolve("unknown-client", "http://localhost:3000")
    → clientRedirectUriService.findByClientId("unknown-client")
      → SAS에서 미등록 → InvalidClientException 발생
    → catch(InvalidClientException e) → "http://localhost:3000" 반환
  response.sendRedirect("http://localhost:3000")
← 302 Location: http://localhost:3000   (4xx 거부 없음)

[등록된 clientId이나 redirectUris 비어 있음]
  ClientInfo.redirectUris() == Set.of()
  → "http://localhost:3000" 반환
← 302 Location: http://localhost:3000

[clientId 빈 문자열]
  resolve("", defaultUrl) → "".isBlank() → 즉시 defaultUrl 반환
← 302 Location: http://localhost:3000

[redirect_uri 복수 — 정렬 후 첫 번째]
  redirectUris = {"https://z.example.com/cb", "https://a.example.com/cb"}
  → sorted() → "https://a.example.com/cb" 선택
← 302 Location: https://a.example.com/cb

[자격증명 실패 — 기존 유지]
  attemptAuthentication() → BadCredentialsException
  → unsuccessfulAuthentication() (변경 없음)
← 401 {"errorCode":"INVALID_CREDENTIALS",...}

[쿠키 세팅 후 sendRedirect 순서 위반]
  cookieManager.setAtCookie / setRtCookie → response.addHeader(SET_COOKIE, ...)
  반드시 response.sendRedirect() 전에 실행됨 — 코드 순서로 보장.
  응답이 이미 커밋된 경우(극히 드묾) → IllegalStateException → IOException으로 전파,
  서블릿 컨테이너의 에러 응답으로 처리.
```

---

### 컨벤션 준수 항목

- **네이밍**: `LoginRedirectResolver` — `{기능}Resolver` 패턴 (CONVENTION.md 1.2 Resolver 행 참조). 테스트: `LoginRedirectResolverTest`. 메서드명 `resolve(...)` — camelCase 동사.
- **의존성 주입**: `LoginRedirectResolver`는 `ApplicationServiceConfig`에서 `@Bean`으로 등록 (application 계층 서비스는 Config에서 수동 등록 패턴). `@Component` 직접 선언 미사용.
- **예외 처리**: `InvalidClientException` catch 후 defaultUrl 반환 — 예외를 밖으로 던지지 않는다. 보안 관련 fallback은 예외 없이 안전한 기본값으로 흡수 (CONVENTION.md 3.2 패턴 준용).
- **불변성**: `LoginRequest` record 확장 시 기존 record 불변 방식 유지. `LoginRedirectResolver` 필드는 `final`.
- **테스트 패턴**: `@ExtendWith(MockitoExtension.class)` + `@Nested` + `@DisplayName` 한글. `@BeforeEach`에서 직접 생성자 호출. BDDMockito `given/willReturn/willThrow`. AssertJ `assertThat`. Given-When-Then 주석 구분 (CONVENTION.md 5.1, 5.2).
- **Javadoc**: 모든 public 클래스·메서드에 Javadoc 작성. `@param`, `@return` 태그 포함 (CONVENTION.md 4.1).
- **포맷팅**: Spotless + Google Java Format. 변경 후 `./gradlew format` 실행.

---

## 구 returnUrl 구현 대비 제거 / 변경 / 신규 목록

| 구분 | 파일 / 구성 요소 | 구 방식 (returnUrl) | 신 방식 (clientId) |
|---|---|---|---|
| **삭제** | `ReturnUrlValidator.java` | returnUrl origin 화이트리스트 검증 클래스 | 폐기 — `LoginRedirectResolver`로 대체 |
| **삭제** | `ReturnUrlValidatorTest.java` | `ReturnUrlValidator` 단위 테스트 | 폐기 |
| **신규** | `LoginRedirectResolver.java` | 없음 | clientId → redirect_uri 결정, `resolve(clientId, defaultUrl): String` |
| **신규** | `LoginRedirectResolverTest.java` | 없음 | `LoginRedirectResolver` 단위 테스트 6개 시나리오 |
| **변경** | `JsonLoginAuthenticationFilter.java` 생성자 | `ReturnUrlValidator returnUrlValidator` 파라미터 | `LoginRedirectResolver loginRedirectResolver` 파라미터 |
| **변경** | `JsonLoginAuthenticationFilter.LoginRequest` record | `record LoginRequest(String loginId, String password)` | `record LoginRequest(String loginId, String password, String clientId)` |
| **변경** | `JsonLoginAuthenticationFilter.attemptAuthentication()` | returnUrl 없음 (쿼리 파라미터로 전달) | `request.setAttribute("clientId", loginRequest.clientId())` 추가 |
| **변경** | `JsonLoginAuthenticationFilter.successfulAuthentication()` WEB 분기 | `request.getParameter("returnUrl")` + `returnUrlValidator.resolveRedirectUrl(...)` | `request.getAttribute("clientId")` + `loginRedirectResolver.resolve(...)` |
| **변경** | `ApplicationServiceConfig` | `returnUrlValidator(ClientRedirectUriService)` `@Bean` | `loginRedirectResolver(ClientRedirectUriService)` `@Bean` |
| **변경** | `SecurityConfig.appSecurityFilterChain()` | `ReturnUrlValidator returnUrlValidator` 파라미터 + null-check | `LoginRedirectResolver loginRedirectResolver` 파라미터 + null-check 교체 |
| **변경** | `AuthRedirectProperties` Javadoc | "returnUrl 검증 실패·미전달 시" | "clientId 미전달·미등록·redirect_uri 없음 시" |
| **변경** | `application.yml` 주석 | "returnUrl이 없거나 검증 실패할 때" | "clientId 미전달·미등록·redirect_uri 없음 시 302 fallback" |
| **변경** | `AuthApiIntegrationTest.WebLoginTest` | `returnUrl` 쿼리 파라미터 기반 시나리오 | `clientId` body 필드 기반 시나리오로 교체 및 추가 |

---

## 체크리스트
- [ ] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [ ] 모든 구성 요소가 적절한 모듈/계층에 배치됨
- [ ] 모든 구성 요소가 적용 컨벤션을 명시함
- [ ] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두)

---

## 참고
- `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` — 변경 핵심 대상 (생성자, LoginRequest record, attemptAuthentication, successfulAuthentication WEB 분기)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/ReturnUrlValidator.java` — 삭제 대상
- `services/apis/auth-api/src/test/java/com/econo/auth/api/application/ReturnUrlValidatorTest.java` — 삭제 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — ReturnUrlValidator → LoginRedirectResolver 교체
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — 빈 등록 교체
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthRedirectProperties.java` — Javadoc 수정
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ClientRedirectUriService.java` — findByClientId(), ClientInfo record, InvalidClientException 재사용
- `services/apis/auth-api/src/main/resources/application.yml` — 주석 수정
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` — WebLoginTest 수정 대상
- `services/apis/auth-api/src/test/java/com/econo/auth/api/application/LoginTokenServiceTest.java` — 테스트 컨벤션 참조 (setUp 패턴)
- `docs/CONVENTION.md` — 네이밍, 의존성 주입, 예외 처리, 테스트 패턴
