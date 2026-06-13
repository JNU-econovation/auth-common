# disable-internal-api-key - implementation

## 메타
- **작업명**: disable-internal-api-key
- **문서 타입**: implementation
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md

---

## 개요

`AdminClientController`의 `X-Internal-Api-Key` 인증 시스템을 완전히 제거하고, `POST /clients`·`GET /routes` 2개 endpoint를 public으로 전환하며, `GET /clients/{clientId}`·`POST|DELETE|PUT /clients/{clientId}/redirect-uris` 4개 endpoint를 `Authorization: Basic base64(clientId:clientSecret)` 검증으로 대체한다 (ADR-0010). 이 작업은 Java 21 / Spring Boot 3.2 / Gradle Kotlin DSL 멀티모듈 프로젝트의 `services/apis/auth-api` 모듈과 `services/apis/api-gateway` 모듈에 걸쳐 있으며, `services/libs` 모듈에는 변경이 없다. OAuth 필드(`grantType`, `apiKeyHash`)와 관련된 코드는 이 작업 범위에서 명시적으로 제외된다.

---

## 결정 사항

### 결정 1 — Basic Auth 검증 구현 위치: `AdminClientController` 내 private 메서드

**선택 근거:**

- 검증 대상이 `AdminClientController`의 4개 endpoint에 한정된다. Spring Security `BasicAuthenticationFilter` + `AuthenticationProvider` 경로를 택하면 `RequestMatcher`로 4개 endpoint를 개별 지정해야 하고, `SecurityConfig`에 새 `SecurityFilterChain` 빈(또는 `securityMatcher`)이 추가된다. 변경 파일 수와 복잡도가 필요 이상으로 늘어난다.
- 별도 `ClientBasicAuthValidator` 클래스는 로직이 단순(헤더 파싱 + repository 조회 + BCrypt 비교)해 추출 실익이 작다. 단일 책임 원칙 측면에서 "Admin endpoint 보호 로직이 Admin 컨트롤러에 있는 것"은 자연스럽다.
- 기존 `isValidApiKey` private 메서드와 동일한 계층(컨트롤러 private 헬퍼)에 위치하므로 패턴 일관성이 유지된다.
- 향후 재사용 요구가 생기면 별도 클래스로 추출하면 된다 (현재로서는 YAGNI).

따라서 **`AdminClientController` 내부에 `private Optional<String> resolveBasicAuthClientId(...)` 류의 private 메서드로 구현**한다. `@RequiredArgsConstructor`를 유지하면서 `RegisteredClientRepository`와 `PasswordEncoder` 빈을 `final` 필드로 추가한다.

### 결정 2 — `RegisteredClientRepository` 의존성 주입 경로: 직접 주입

`ClientRedirectUriService.findByClientId()`는 내부적으로 `registeredClientRepository.findByClientId()`를 호출하지만, 그 메서드는 클라이언트가 없을 때 `InvalidClientException`(→ 404)을 던진다. Basic Auth 실패는 **401**이어야 하므로, 서비스 레이어를 거치면 예외 처리 로직이 꼬인다. `AdminClientController`에 `RegisteredClientRepository`를 직접 주입해 `null` 반환을 직접 처리하는 것이 명확하다.

`RegisteredClientRepository` 빈은 `RegisteredClientConfig`에 `JdbcRegisteredClientRepository`로 이미 등록되어 있다 (`services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java:18`).

### 결정 3 — `PasswordEncoder` BCrypt 비교 방식

SAS가 `client_secret`을 `{bcrypt}<hash>` 형식으로 저장한다 (`SasClientRegistrarAdapter.registerClientCredentialsClient`에서 `bcryptHashedSecret`을 그대로 저장). `SecurityConfig`에 등록된 `BCryptPasswordEncoder(12)` 빈은 `DelegatingPasswordEncoder`가 아닌 순수 `BCryptPasswordEncoder`이므로, `{bcrypt}` 접두사가 포함된 문자열에 직접 `matches(raw, encoded)` 호출 시 접두사를 인식하지 못한다.

따라서 `registeredClient.getClientSecret()`에서 `{bcrypt}` 접두사를 제거한 후 `passwordEncoder.matches(rawSecret, strippedHash)` 를 호출한다. 제거 로직은 `strip("{bcrypt}")` 형태로 컨트롤러 private 헬퍼에 인라인한다.

`PasswordEncoder` 빈은 `SecurityConfig.passwordEncoder()` (`services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java:81`)에 이미 등록되어 있다.

### 결정 4 — `WWW-Authenticate` 헤더 추가

RFC 7235 권고에 따라 **401 응답 시 `WWW-Authenticate: Basic realm="admin"` 헤더를 추가한다.** 이 시스템은 내부망 전용이므로 브라우저 기본 인증 대화상자가 뜰 가능성은 없으나, 표준 준수로 클라이언트(curl, Postman)가 인증 방식을 자동 인식할 수 있다.

---

## 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` | 변경 | 인증 시스템 전환 핵심 대상 |
| `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` | 변경 | admin 라우트 추가 금지 주석 삽입 |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java` | 변경 | X-Internal-Api-Key 테스트 제거 + Basic Auth 테스트 신설 |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` | 변경 | X-Internal-Api-Key 참조 제거 + Basic Auth 통합 테스트 신설 |
| `services/apis/auth-api/src/test/resources/application-test.yml` | 변경 | `AUTH_INTERNAL_API_KEY` 행 제거 |
| `.claude/skills/register-service/SKILL.md` | 변경 | Internal API Key curl 참조 제거 |
| `docs/CLIENT_REGISTRATION.md` | 변경 | 인증 모델 문서 갱신 |
| `docs/FEATURES.md` | 변경 | Admin 섹션 인증 방식 갱신 |
| `docs/DYNAMIC_ROUTING.md` | 변경 | curl 예시 갱신 |

---

## 구성 요소 설계

### 모듈 / 패키지: `services/apis/auth-api` — `adapter.in.web`

```
adapter/in/web/
└── AdminClientController.java    — API 키 제거 + public/Basic Auth 이중 인증 모델 구현
```

#### `AdminClientController`

- **타입**: Controller (인바운드 웹 어댑터, `adapter.in.web`)
- **책임**: Admin endpoint의 HTTP 매핑 및 인증 처리. public 2개 endpoint는 인증 없이 통과, Basic Auth 4개 endpoint는 `Authorization` 헤더를 파싱·검증 후 비즈니스 로직에 위임.
- **주요 변경 사항**:

  **제거:**
  - `@Value("${AUTH_INTERNAL_API_KEY}") private String internalApiKey` 필드
  - `private boolean isValidApiKey(String header)` 메서드
  - `java.security.MessageDigest`, `java.nio.charset.StandardCharsets`, `org.springframework.beans.factory.annotation.Value` import (각 유일 사용처 확인 후 제거)
  - 7개 endpoint에서 `@RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKeyHeader` 파라미터 및 `isValidApiKey()` 분기 블록 전부

  **추가 (final 필드):**
  ```java
  private final RegisteredClientRepository registeredClientRepository;
  private final PasswordEncoder passwordEncoder;
  ```
  `@RequiredArgsConstructor`가 생성자를 자동 생성하므로 별도 생성자 코드 불필요.

  **추가 (private 헬퍼 메서드):**

  ```
  /** Authorization 헤더에서 clientId + rawSecret 파싱. 실패 시 empty */
  private Optional<String[]> parseBasicAuth(String authorizationHeader)

  /** Basic Auth 자격증명 검증. 성공 시 authenticatedClientId 반환, 실패 시 401 응답 반환 */
  private ResponseEntity<?> verifyBasicAuth(String authorizationHeader)
  ```

  `verifyBasicAuth` 처리 순서 (todo `[ ] Basic Auth 검증 로직 신규 구현` 대응):
  1. `authorizationHeader`가 null이거나 `"Basic "` 접두사가 없으면 → `ResponseEntity.status(401).header("WWW-Authenticate", "Basic realm=\"admin\"").body(new ErrorResponse("INVALID_CLIENT_CREDENTIALS", "인증 정보가 없거나 형식이 올바르지 않습니다."))` 반환
  2. Base64 디코딩 실패 → 동일 401 반환
  3. `:` 첫 번째 위치 기준 split — `clientId`와 `rawSecret` 추출. `:` 없으면 → 동일 401 반환
  4. `registeredClientRepository.findByClientId(clientId)` — null이면 → 동일 401 반환 (클라이언트 존재 여부를 외부에 노출하지 않음)
  5. `registeredClient.getClientSecret()`에서 `{bcrypt}` 접두사 제거 후 `passwordEncoder.matches(rawSecret, stripped)` — 불일치 시 → 동일 401 반환
  6. 검증 성공 시 `null` 반환 (또는 별도 record로 `(clientId, registeredClient)` 반환)

  4개 endpoint에서의 호출 패턴:
  ```java
  ResponseEntity<?> authError = verifyBasicAuth(authorizationHeader);
  if (authError != null) return authError;
  // path clientId ↔ Basic Auth clientId 불일치 검사
  if (!pathClientId.equals(decodedClientId)) {
      return ResponseEntity.status(403)
          .body(new ErrorResponse("FORBIDDEN_CLIENT_MISMATCH", "경로의 clientId와 인증 clientId가 일치하지 않습니다."));
  }
  ```

  > path ↔ Basic Auth clientId 불일치 검사는 `verifyBasicAuth` 내부가 아닌 **각 endpoint에서 수행**한다. `verifyBasicAuth`는 자격증명 유효성만 담당하고, path 소유권 검사는 endpoint별 책임이다.

  **클래스 Javadoc 수정** (todo `AdminClientController` 클래스 Javadoc 수정 대응):
  - "컨트롤러는 HTTP 매핑과 API 키 인증에만 집중한다" → "컨트롤러는 HTTP 매핑과 인증(public/Basic Auth)에만 집중한다"

  **Swagger 어노테이션 수정**:
  - `@Tag` description: "모든 엔드포인트는 X-Internal-Api-Key 헤더 인증 필요" → "등록(`POST /clients`)·라우트 조회(`GET /routes`)는 인증 없음. 클라이언트 조회·redirectUri CRUD 4개 endpoint는 `Authorization: Basic base64(clientId:clientSecret)` 인증 필요."
  - 각 `@Operation` description에서 `"인증: X-Internal-Api-Key"` → public endpoint는 인증 없음 명시, Basic Auth endpoint는 `"인증: Basic Auth (clientId:clientSecret)"` 명시
  - `@ApiResponse(responseCode = "401")`: public 2개 endpoint에서 401 항목 삭제. Basic Auth 4개 endpoint에 `401 INVALID_CLIENT_CREDENTIALS` / `403 FORBIDDEN_CLIENT_MISMATCH` 응답 스펙 추가

- **의존성**:
  - `RegisterOAuthClientService` (기존)
  - `ClientRedirectUriService` (기존)
  - `RegisteredClientRepository` (신규 주입 — `RegisteredClientConfig` 빈)
  - `PasswordEncoder` (신규 주입 — `SecurityConfig` 빈)
- **적용 컨벤션**:
  - `@RequiredArgsConstructor` — final 필드 기반 생성자 자동 생성 (신규 필드 2개 포함)
  - `@JsonInclude(NON_NULL)` — `RegisterClientResponse`의 `clientSecret`/`routeId` null 필터링 (기존 유지)
  - private 메서드 Javadoc: `/** 한 줄 설명 */` 형태 (docs/CONVENTION.md §4.2)
  - 에러 응답: `ErrorResponse(errorCode, message)` 2-arg 생성자 — 신규 record 불필요
  - `{bcrypt}` 접두사 처리: `startsWith("{bcrypt}") ? substring(8) : hash` 형태로 방어적으로 처리
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java:267-271` (기존 `isValidApiKey` 구조 참고)
- **연관 todo**:
  - `[ ] AdminClientController — @Value 필드 제거`
  - `[ ] AdminClientController — isValidApiKey() 메서드 제거`
  - `[ ] AdminClientController — import 제거`
  - `[ ] AdminClientController 클래스 Javadoc 수정`
  - `[ ] Basic Auth 검증 로직 신규 구현`
  - `[ ] AdminClientController.ErrorResponse — 기존 record 양식 유지`
  - `[ ] Swagger @Tag description 갱신`
  - `[ ] Swagger 각 @Operation description 갱신`
  - `[ ] Swagger @ApiResponse 갱신`

---

### 모듈 / 패키지: `services/apis/api-gateway` — `config`

```
config/
└── GatewayRoutingConfig.java    — admin 라우트 추가 금지 경고 주석 삽입
```

#### `GatewayRoutingConfig` (주석 삽입)

- **타입**: Config (`@Configuration`)
- **책임**: `routes()` 빈 메서드 상단에 admin endpoint 외부 노출 금지 경고 Javadoc 블록 삽입.
- **삽입 위치**: `routes()` 빈 메서드의 Javadoc 또는 클래스 Javadoc 내 `@apiNote` 형태.
- **삽입 내용** (todo `GatewayRoutingConfig.java — 주석 삽입` 대응):
  ```java
  /**
   * ...기존 Javadoc...
   *
   * <p><strong>주의:</strong> {@code /api/v1/admin/**} 라우트를 이 파일에 추가하지 말 것.
   * admin endpoint는 내부망 전용이며, Gateway 외부 라우트에 노출 시 public 등록 endpoint
   * ({@code POST /api/v1/admin/clients})에 대한 도배(flood) 공격 위험이 있다. ADR-0010 참조.
   */
  ```
- **적용 컨벤션**: 한국어 Javadoc (docs/CONVENTION.md §4)
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java:12-19` (기존 클래스 Javadoc 구조)
- **연관 todo**: `[ ] GatewayRoutingConfig.java — routes() 빈 메서드 상단 또는 클래스 Javadoc에 주석 삽입`

---

### 테스트 — `AdminClientControllerTest`

#### 변경 사항

| 항목 | 변경 내용 |
|---|---|
| 클래스 레벨 `@TestPropertySource` | 제거 — `AUTH_INTERNAL_API_KEY` 속성 불필요 |
| `@MockBean RegisteredClientRepository` | 추가 — Basic Auth 검증에서 조회 필요 |
| `@MockBean PasswordEncoder` | 추가 — BCrypt 비교 mock 필요 |
| `RegisterClientTest` 전체 테스트 | `.header("X-Internal-Api-Key", "valid-internal-key")` 제거, `@WithMockUser(roles = "ADMIN")` 제거 (public endpoint) |
| `RegisterClientTest.registerWithoutApiKey_returns401` | **삭제** — public 전환으로 의미 소멸 |
| `RegisterClientTest.registerWithoutGrantType_returns201WithClientIdAndSecret` (`@Disabled`) | 헤더만 제거, `@Disabled` 유지 |
| `GetRoutesTest.getRoutes_withValidInternalApiKey_returns200` | 헤더 제거, 테스트명 → `getRoutes_withoutAuth_returns200` |
| `GetRoutesTest.getRoutes_withoutApiKey_returns401` | **삭제** |
| `GetRoutesTest.getRoutes_withInvalidApiKey_returns401` | **삭제** |

#### 신규 Nested class 4개 — 각 4케이스

각 신규 class는 동일한 4개 케이스를 해당 endpoint URL에 대해 검증한다. 4케이스 공통 구조:

```
케이스 1: 올바른 Basic Auth → 200
  given: registeredClientRepository.findByClientId(...) → mock RegisteredClient
         passwordEncoder.matches(raw, stripped) → true
  when: Authorization: Basic base64(clientId:rawSecret)
  then: 200

케이스 2: 잘못된 clientSecret → 401 INVALID_CLIENT_CREDENTIALS
  given: passwordEncoder.matches(...) → false
  then: 401 + errorCode = "INVALID_CLIENT_CREDENTIALS"
        + WWW-Authenticate: Basic realm="admin" 헤더

케이스 3: path clientId ↔ Basic Auth clientId 불일치 → 403 FORBIDDEN_CLIENT_MISMATCH
  given: 올바른 자격증명이나 path의 {clientId}와 Basic Auth의 clientId가 다름
  then: 403 + errorCode = "FORBIDDEN_CLIENT_MISMATCH"

케이스 4: Authorization 헤더 누락 → 401 INVALID_CLIENT_CREDENTIALS
  given: Authorization 헤더 없음
  then: 401 + WWW-Authenticate: Basic realm="admin" 헤더
```

| 신규 클래스명 | endpoint | 연관 todo |
|---|---|---|
| `GetClientBasicAuthTest` | `GET /api/v1/admin/clients/{clientId}` | `[ ] 신규 Nested class GetClientBasicAuthTest 추가` |
| `AddRedirectUriBasicAuthTest` | `POST /api/v1/admin/clients/{clientId}/redirect-uris` | `[ ] 신규 Nested class AddRedirectUriBasicAuthTest 추가` |
| `RemoveRedirectUriBasicAuthTest` | `DELETE /api/v1/admin/clients/{clientId}/redirect-uris` | `[ ] 신규 Nested class RemoveRedirectUriBasicAuthTest 추가` |
| `ReplaceRedirectUrisBasicAuthTest` | `PUT /api/v1/admin/clients/{clientId}/redirect-uris` | `[ ] 신규 Nested class ReplaceRedirectUrisBasicAuthTest 추가` |

**헬퍼 메서드** (모든 신규 테스트에서 공유):
```java
/** "Basic " + Base64(id + ":" + secret) 형태의 Authorization 헤더 값 생성 */
private String basicAuthHeader(String id, String secret) {
    return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
}
```

**적용 컨벤션**:
- `@Nested` + `@DisplayName` 한글 (docs/CONVENTION.md §5.1)
- Given-When-Then 패턴 주석 (docs/CONVENTION.md §5.2)
- `@WebMvcTest` 슬라이스 테스트 유지 (docs/CONVENTION.md §5.3)

---

### 테스트 — `AuthApiIntegrationTest`

#### 변경 사항

| 항목 | 변경 내용 |
|---|---|
| `DynamicPropertySource` 내 `AUTH_INTERNAL_API_KEY` 행 | 제거 (`services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java:62`) |
| `AdminClientRegistrationTest` 전체 — `X-Internal-Api-Key` 헤더 | 제거 (public endpoint) |
| `AdminClientRegistrationTest.register_without_api_key_returns_401` | **삭제** |
| `AdminClientRegistrationTest.register_wrong_api_key_returns_401` | **삭제** |
| `RedirectUriManagementTest.add_redirect_uri` | 헤더 제거. 등록 응답에서 `clientSecret` 추출 → `buildBasicAuthHeader` 헬퍼로 redirect-uris 호출 재작성 |
| `RoutesTest.get_routes_with_valid_key` | 헤더 제거, 테스트명 → `get_routes_without_auth_returns200` |
| `RoutesTest.get_routes_without_key` | **삭제** |

**신규 헬퍼 메서드**:
```java
/** "Basic " + Base64(clientId + ":" + rawSecret) 헤더 값 생성 */
private String buildBasicAuthHeader(String clientId, String rawSecret)
```

**신규 통합 테스트 — Basic Auth 검증** (`RedirectUriManagementTest` 내부 또는 별도 `BasicAuthVerificationTest` Nested class):

```
케이스 1: client_credentials 등록 → 반환된 clientSecret으로 GET /clients/{clientId} → 200
케이스 2: 잘못된 secret으로 GET /clients/{clientId} → 401 INVALID_CLIENT_CREDENTIALS
케이스 3: 다른 clientId로 Basic Auth 구성 후 요청 → 403 FORBIDDEN_CLIENT_MISMATCH
케이스 4: Authorization 헤더 없이 POST /redirect-uris → 401
```

**연관 todo**:
- `[ ] DynamicPropertySource에서 AUTH_INTERNAL_API_KEY 행 제거`
- `[ ] AdminClientRegistrationTest 전체 헤더 제거`
- `[ ] register_without_api_key_returns_401 / register_wrong_api_key_returns_401 삭제`
- `[ ] RedirectUriManagementTest.add_redirect_uri 재작성`
- `[ ] RoutesTest.get_routes_with_valid_key 헤더 제거 + 테스트명 갱신`
- `[ ] RoutesTest.get_routes_without_key 삭제`
- `[ ] 헬퍼 메서드 buildBasicAuthHeader 추가`
- `[ ] 신규 통합 테스트 Basic Auth 검증 추가`

---

### 설정 파일

#### `application-test.yml`

- **변경**: 9번 줄 `AUTH_INTERNAL_API_KEY: valid-internal-key` 행 삭제
- **연관 todo**: `[ ] services/apis/auth-api/src/test/resources/application-test.yml — AUTH_INTERNAL_API_KEY 행 제거`

---

### 스킬 문서 — `.claude/skills/register-service/SKILL.md`

- **변경 1**: "전제 조건 파악" 섹션 항목 2 "auth-api Internal API Key" (`AUTH_INTERNAL_API_KEY`) 전체 제거 (현재 29번 줄)
- **변경 2**: Step 1 curl 예시에서 `-H "X-Internal-Api-Key: ${AUTH_INTERNAL_API_KEY}"` 줄 제거 (현재 41번 줄)
- **변경 3**: 완료 체크리스트의 `clientId`, `clientSecret` 저장 항목에 "(clientSecret은 등록 응답에서 1회만 노출 — 반드시 즉시 저장)" 문구 추가
- **연관 todo**: `[ ] .claude/skills/register-service/SKILL.md — 3개 항목 갱신`

---

### 문서 파일

#### `docs/CLIENT_REGISTRATION.md`
- 도입부 "X-Internal-Api-Key 헤더 인증 필수" 문구 제거
- 등록 curl 예시에서 `-H "X-Internal-Api-Key: <KEY>"` 제거
- redirectUri 관리 curl 예시 4개를 `Authorization: Basic base64(clientId:clientSecret)` 헤더로 갱신
- Basic Auth 흐름 설명 섹션 추가 (등록 응답 `clientSecret`으로 헤더 구성 방법)
- "clientSecret은 등록 응답에서 단 1회만 노출, 분실 시 재등록 필요" 경고 추가
- 에러 코드 표: `401 UNAUTHORIZED` 행 제거, `401 INVALID_CLIENT_CREDENTIALS` / `403 FORBIDDEN_CLIENT_MISMATCH` 행 추가

#### `docs/FEATURES.md`
- "OAuth 클라이언트 관리 (Admin)" 섹션 `> 모든 요청에 X-Internal-Api-Key: <KEY> 헤더 필수` 제거
- 클라이언트 등록 curl 예시에서 헤더 제거
- redirectUri 관리 API 표에서 인증 방식을 "Basic Auth (clientId:clientSecret)"로 표기
- 섹션 3 "새 서비스 연동하기 > Step 1" curl에서 `-H "X-Internal-Api-Key: <KEY>"` 제거

#### `docs/DYNAMIC_ROUTING.md`
- 동적 라우팅 등록/라우트 목록 조회 curl 예시에서 `-H "X-Internal-Api-Key: <KEY>"` 제거

---

## 호출 흐름

### public endpoint — 정상 경로

```
Client → POST /api/v1/admin/clients (인증 없음)
  → AdminClientController.registerClient(request)
    → RegisterOAuthClientService.register(command)
    → 201 RegisterClientResponse{clientId, clientSecret, routeId}

Client → GET /api/v1/admin/routes (인증 없음)
  → AdminClientController.getRoutes()
    → RegisterOAuthClientService.getRoutes()
    → 200 RoutesResponse{routes}
```

### Basic Auth endpoint — 정상 경로

```
Client → GET /api/v1/admin/clients/{clientId}
         Header: Authorization: Basic base64(clientId:rawSecret)
  → AdminClientController.getClient(authorizationHeader, clientId)
    → verifyBasicAuth(authorizationHeader)
        → parseBasicAuth(header)
            → Base64 디코딩 → "clientId:rawSecret" split
        → registeredClientRepository.findByClientId(clientId)
        → passwordEncoder.matches(rawSecret, stripped_hash)
        → 검증 성공 → null 반환
    → pathClientId.equals(decodedClientId) 확인 → 일치
    → redirectUriService.findByClientId(clientId) → RegisteredClient
    → 200 ClientDetailResponse{clientId, clientName, redirectUris}
```

(POST/DELETE/PUT redirect-uris도 동일한 verifyBasicAuth → pathClientId 일치 확인 → 서비스 위임 흐름)

### Basic Auth endpoint — 예외 / 실패 경로

```
[케이스 1] Authorization 헤더 누락 또는 "Basic " 접두사 없음
  → verifyBasicAuth() 내에서
  → ResponseEntity 401 + WWW-Authenticate: Basic realm="admin"
       + body ErrorResponse("INVALID_CLIENT_CREDENTIALS", "인증 정보가 없거나 형식이 올바르지 않습니다.")

[케이스 2] Base64 디코딩 실패 또는 ":" 구분자 없음
  → verifyBasicAuth() 내에서
  → 위와 동일한 401 응답

[케이스 3] findByClientId(clientId) == null
  → verifyBasicAuth() 내에서
  → 위와 동일한 401 응답 (클라이언트 존재 여부 노출 금지)

[케이스 4] passwordEncoder.matches(raw, hash) == false
  → verifyBasicAuth() 내에서
  → 위와 동일한 401 응답

[케이스 5] path {clientId} ↔ Basic Auth clientId 불일치
  → verifyBasicAuth() 성공 반환 (null)
  → endpoint 내 if (!pathClientId.equals(decodedClientId)) 분기
  → 403 + ErrorResponse("FORBIDDEN_CLIENT_MISMATCH", "경로의 clientId와 인증 clientId가 일치하지 않습니다.")

[케이스 6] 자격증명은 맞으나 redirectUriService에서 InvalidClientException
  → GlobalExceptionHandler.handleInvalidClient() → 404 CLIENT_NOT_FOUND
  (정상적으로 등록된 클라이언트라면 발생하지 않음 — SAS와 ServiceClient 사이 불일치 시 발생 가능)
```

---

## 컨벤션 준수 항목

- **네이밍**: private 헬퍼 메서드는 `verifyBasicAuth`, `parseBasicAuth` (camelCase, 의미 있는 접두사 `verify`/`parse` — docs/CONVENTION.md §1.3)
- **의존성 주입**: `@RequiredArgsConstructor` + `private final` 필드 — `RegisteredClientRepository`, `PasswordEncoder` 신규 추가 (docs/CONVENTION.md §2.2)
- **예외 처리**: 컨트롤러 레벨 인증 실패는 `GlobalExceptionHandler`를 거치지 않고 컨트롤러에서 직접 `ResponseEntity`로 반환 (현재 `isValidApiKey` 패턴과 동일). `verifyBasicAuth`는 `ResponseEntity<?>`를 반환하고 null이면 성공으로 처리.
- **불변성**: `AdminClientController`는 DTO record 사용 유지, `private final` 필드 유지 (docs/CONVENTION.md §2.3)
- **JSON 직렬화**: `@JsonInclude(NON_NULL)` — `RegisterClientResponse` 유지 (docs/CONVENTION.md §2.4)
- **Javadoc**: public 클래스·메서드에 한국어 Javadoc, private 메서드에 `/** 한 줄 설명 */` (docs/CONVENTION.md §4)
- **테스트 패턴**: `@Nested` + `@DisplayName` 한글 + Given-When-Then + `private String basicAuthHeader(...)` 헬퍼 추출 (docs/CONVENTION.md §5)
- **포맷팅**: 변경 파일 전체에 `./gradlew format` (Spotless + Google Java Format 1.17.0) 적용 (docs/CONVENTION.md §2.1)

---

## 체크리스트

- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨 (`adapter.in.web`, `config`, 테스트, 문서)
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상 6케이스 + 예외 6케이스 모두 커버)

---

## 참고

- `docs/CONVENTION.md` — 한국어 Javadoc, `@RequiredArgsConstructor`, Given-When-Then, `@DisplayName` 한글, Spotless
- `docs/adr/0010-client-secret-self-service-auth.md` — 이 작업의 근거 ADR
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` — 주요 변경 대상 (L267–271: 기존 `isValidApiKey`)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java:81` — `PasswordEncoder` 빈 등록 위치
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java:18` — `RegisteredClientRepository` 빈 등록 위치
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/sas/SasClientRegistrarAdapter.java:47` — `{bcrypt}<hash>` 저장 형식 확인 위치
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/ClientRedirectUriService.java:33` — `findByClientId` 내부에서 `InvalidClientException`(404) 던짐 — 직접 주입이 필요한 이유
- `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java:34` — 제거 대상 `@TestPropertySource`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java:62` — 제거 대상 `AUTH_INTERNAL_API_KEY` DynamicPropertySource
