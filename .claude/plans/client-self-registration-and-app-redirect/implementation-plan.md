# client-self-registration-and-app-redirect - implementation

## 메타
- **작업명**: client-self-registration-and-app-redirect
- **문서 타입**: implementation
- **작성일**: 2026-06-07
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

---

## 개요

이 문서는 두 기능의 구현 범위를 다룬다.

**기능 A (셀프 등록)**: `service-client` 라이브러리 모듈에 `ownerId`, `clientSecretHash` 필드를 도입하고, `auth-api` 모듈에 `POST /api/v1/clients` 신규 컨트롤러를 추가해 인증된 에코노 회원 누구나 SSO 클라이언트를 직접 등록할 수 있게 한다.

**기능 B (APP 리다이렉트)**: `auth-api`의 `JsonLoginAuthenticationFilter` APP 분기에서 이미 WEB 분기에서만 쓰이던 `LoginRedirectResolver`를 재사용해 `LoginResponse.app(...)` body에 `redirectUrl` 필드를 추가한다.

Java 21 + Spring Boot 3.2.2 + Gradle Kotlin DSL 멀티모듈, 헥사고날 아키텍처 위에서 설계된다.

---

## 핵심 설계 결정: clientSecret + PKCE 공존 방식

세 가지 옵션 중 **옵션 2(secret은 SAS 흐름 밖 용도에만 사용)** 를 선택한다.

### 선택 근거

현재 auth-api는 SAS 기반 `/oauth2/authorize` + `/oauth2/token` 흐름(경로 B)을 유지하고 있으나, ADR-0010 및 기존 코드(`SasClientRegistrarAdapter`가 `ClientAuthenticationMethod.NONE`만 등록)가 보여주듯 **자사 프런트엔드도 public PKCE 모델로 동작**한다. 셀프 등록 클라이언트도 동일한 PKCE 흐름을 사용한다고 가정하면:

- **옵션 1 (`CLIENT_SECRET_BASIC + NONE` 동시 등록)**: SAS가 `/oauth2/token`에서 secret을 실제로 검증하는 흐름이 생겨버린다. 현재 경로 A(JSON 로그인) 위주 운영 모델과 부정합을 만들고, 향후 경로 B 확장 시 혼선 가능성이 있다.
- **옵션 2 (NONE 유지, secret은 redirect-uri 관리용)**: SAS 등록 방식을 그대로 유지(`ClientAuthenticationMethod.NONE`)하고, 발급된 `clientSecret`은 redirect-uri CRUD(BasicAuth) 인증에만 사용한다. 기존 `AdminClientController`의 Basic Auth 패턴과 완전히 동일한 구조이며 변경 최소화 원칙에 부합한다.
- **옵션 3 (포트 신규 메서드)**: 단순한 분기를 포트 인터페이스 레벨까지 쪼개는 과설계. `SasClientRegistrar.registerAuthorizationCodeClient`가 이미 PKCE 등록을 처리하므로 불필요.

따라서 `SasClientRegistrarAdapter.registerAuthorizationCodeClient`는 변경 없이 기존 `ClientAuthenticationMethod.NONE` 방식 그대로 호출하고, 새로 생성된 `clientSecretHash`는 `service_client` 테이블에 저장하여 redirect-uri CRUD 인증에 활용한다.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/libs/service-client/.../domain/ServiceClient.java` | 변경 | `ownerId`, `clientSecretHash` 필드 추가 |
| `services/libs/service-client/.../adapter/out/persistence/ServiceClientJpaEntity.java` | 변경 | `owner_id`, `client_secret_hash` 컬럼 매핑 추가 |
| `services/libs/service-client/.../adapter/out/persistence/ServiceClientJpaRepository.java` | 변경 | `countByOwnerId` 추가 |
| `services/libs/service-client/.../adapter/out/persistence/ServiceClientRepositoryAdapter.java` | 변경 | `countByOwnerId` 위임 추가 |
| `services/libs/service-client/.../application/port/out/ServiceClientRepository.java` | 변경 | `countByOwnerId` 포트 메서드 추가 |
| `services/libs/service-client/.../application/usecase/RegisterOAuthClientService.java` | 변경 | 셀프 등록 커맨드/결과/로직 추가 |
| `services/libs/service-client/.../exception/ClientLimitExceededException.java` | 신규 | 1인 5개 초과 예외 |
| `services/apis/auth-api/.../adapter/in/web/ClientController.java` | 신규 | 셀프 등록 인바운드 컨트롤러 (`POST /api/v1/clients`) |
| `services/apis/auth-api/.../exception/GlobalExceptionHandler.java` | 변경 | `ClientLimitExceededException` 핸들러 등록 |
| `services/apis/auth-api/.../adapter/in/web/LoginResponse.java` | 변경 | `redirectUrl` 필드 + `app(...)` 팩토리 메서드 시그니처 확장 |
| `services/apis/auth-api/.../filter/JsonLoginAuthenticationFilter.java` | 변경 | APP 분기에 `LoginRedirectResolver` 호출 추가 |
| `services/libs/member/.../db/migration/V7__add_owner_id_to_service_client.sql` | 신규 | `owner_id`, `client_secret_hash` 컬럼 + 인덱스 추가 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/libs/service-client`

```
com.econo.auth.client
├── domain/
│   └── ServiceClient                    — ownerId, clientSecretHash 필드 추가
├── application/
│   ├── port/out/
│   │   └── ServiceClientRepository      — countByOwnerId 포트 메서드 추가
│   └── usecase/
│       └── RegisterOAuthClientService   — selfRegister 메서드 + 관련 record 추가
├── adapter/out/persistence/
│   ├── ServiceClientJpaEntity           — owner_id, client_secret_hash 컬럼 매핑
│   ├── ServiceClientJpaRepository       — countByOwnerId Spring Data JPA 메서드
│   └── ServiceClientRepositoryAdapter  — countByOwnerId 위임 구현
└── exception/
    └── ClientLimitExceededException     — 신규 (422 CLIENT_LIMIT_EXCEEDED)
```

---

##### ServiceClient (변경)

- **타입**: Domain (Aggregate Root)
- **책임**: OAuth 클라이언트 도메인 상태를 불변 객체로 표현한다.
- **변경 내용**:
  - `@Nullable Long ownerId` 필드 추가 — 셀프 등록 시 회원 ID, ADMIN 등록 시 `null`
  - `@Nullable String clientSecretHash` 필드 추가 — BCrypt 해시된 secret, 셀프 등록 시에만 값이 있음
  - `create(String registeredClientId, String clientName, @Nullable GrantType grantType, @Nullable String apiKeyHash)` 기존 팩토리 메서드는 기존 호출부 호환을 위해 유지하되 `ownerId=null, clientSecretHash=null`로 위임
  - `create(String registeredClientId, String clientName, @Nullable GrantType grantType, @Nullable String apiKeyHash, @Nullable Long ownerId, @Nullable String clientSecretHash)` 오버로드 추가
- **적용 컨벤션**:
  - `private final` 필드, 생성자 내 직접 할당 (불변성 — CONVENTION.md 2.3)
  - 기존 4인자 `create`는 6인자 `create`에 위임하는 형태로 구현 (하위 호환)
  - Lombok `@Getter` 클래스 레벨 유지
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/domain/ServiceClient.java:15-41`
- **연관 todo**: `[A-IMPL-1]`

---

##### ServiceClientRepository (변경)

- **타입**: 아웃바운드 포트 (Port Out)
- **책임**: ServiceClient 영속성 포트 — 저장, 이름 중복 확인, 전체 ID 조회, 소유자별 카운트.
- **추가 메서드**:
  - `long countByOwnerId(Long ownerId)` — 소유자 회원 ID별 등록 클라이언트 수 반환
- **적용 컨벤션**:
  - 인터페이스 메서드에 Javadoc `@param`, `@return` 필수 (CONVENTION.md 4.1)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/port/out/ServiceClientRepository.java`
- **연관 todo**: `[A-IMPL-3]`

---

##### ServiceClientJpaEntity (변경)

- **타입**: JPA 어댑터 (Persistence Entity)
- **책임**: `service_client` 테이블 행을 JPA 엔티티로 표현하고 도메인 객체와 상호 변환한다.
- **변경 내용**:
  - `@Column(name = "owner_id", nullable = true) Long ownerId` 필드 추가
  - `@Column(name = "client_secret_hash", nullable = true, length = 72) String clientSecretHash` 필드 추가 (BCrypt 출력은 최대 60자이나 prefix `$2a$12$` 포함 넉넉하게 72)
  - `from(ServiceClient)`: `entity.ownerId = serviceClient.getOwnerId()`, `entity.clientSecretHash = serviceClient.getClientSecretHash()` 추가
  - `toDomain()`: `ServiceClient.create(registeredClientId, clientName, grantType, apiKeyHash, ownerId, clientSecretHash)` 6인자 팩토리 사용으로 변경
- **적용 컨벤션**:
  - `@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)` 유지 (JPA 프록시 요건)
  - `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate` 유지
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceClientJpaEntity.java:55-71`
- **연관 todo**: `[A-IMPL-2]`

---

##### ServiceClientJpaRepository (변경)

- **타입**: Spring Data JPA Repository
- **책임**: service_client 테이블 쿼리 — 기존 메서드 유지 + 소유자별 카운트 추가.
- **추가 메서드**:
  - `long countByOwnerId(Long ownerId)` — Spring Data JPA 파생 쿼리, 별도 `@Query` 불필요
- **적용 컨벤션**:
  - `JpaRepository<ServiceClientJpaEntity, Long>` 상속 구조 유지
  - 파생 쿼리 메서드명 컨벤션 (`countBy{Field}`)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceClientJpaRepository.java`
- **연관 todo**: `[A-IMPL-4]`

---

##### ServiceClientRepositoryAdapter (변경)

- **타입**: 아웃바운드 어댑터 (Persistence Adapter)
- **책임**: `ServiceClientRepository` 포트 구현 — `ServiceClientJpaRepository`에 위임.
- **추가 메서드**:
  ```java
  @Override
  @Transactional(readOnly = true)
  public long countByOwnerId(Long ownerId) {
      return serviceClientJpaRepository.countByOwnerId(ownerId);
  }
  ```
- **적용 컨벤션**:
  - `@Component` + `@RequiredArgsConstructor` 유지
  - `@Transactional(readOnly = true)` — 읽기 전용 쿼리
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceClientRepositoryAdapter.java`
- **연관 todo**: `[A-IMPL-5]`

---

##### RegisterOAuthClientService (변경)

- **타입**: Use Case Service
- **책임**: OAuth 클라이언트 등록 유스케이스 — 기존 ADMIN 경로 유지, 셀프 등록 경로 추가.
- **신규 record**:
  ```java
  public record SelfRegisterOAuthClientCommand(
      String clientName, Set<String> redirectUris, Long ownerId) {}

  public record SelfRegisterOAuthClientResult(String clientId, String clientSecret) {}
  ```
  - 기존 `RegisterOAuthClientCommand` / `RegisterOAuthClientResult`는 그대로 유지 (ADMIN 경로용)
  - 별도 커맨드를 쓰는 이유: `ownerId`를 기존 커맨드에 추가하면 기존 ADMIN 호출부 전체를 수정해야 하고, 두 등록 경로의 책임이 명확히 달라 커맨드 분리가 더 명확함.

- **신규 메서드 `selfRegister`**:
  ```java
  @Transactional
  public SelfRegisterOAuthClientResult selfRegister(SelfRegisterOAuthClientCommand command)
  ```
  구현 절차:
  1. `command.clientName()` null/blank 검증 → `IllegalArgumentException`
  2. `command.redirectUris()` 비어있으면 → `RedirectUriRequiredException`
  3. `serviceClientRepository.countByOwnerId(command.ownerId()) >= 5` → `ClientLimitExceededException`
  4. `serviceClientRepository.existsByClientName(command.clientName())` → `DuplicateClientNameException`
  5. `String clientId = UUID.randomUUID().toString()`
  6. `String rawSecret = UUID.randomUUID().toString()` (평문 secret — 1회 노출용)
  7. `String secretHash = passwordEncoder.encode(rawSecret)` (BCrypt cost 12 — `SecurityConfig`의 `PasswordEncoder` 빈 재사용)
  8. `sasClientRegistrar.registerAuthorizationCodeClient(clientId, clientName, redirectUris)` — `NONE` 방식 그대로
  9. `serviceClientRepository.save(ServiceClient.create(clientId, clientName, GrantType.AUTHORIZATION_CODE, null, command.ownerId(), secretHash))`
  10. `return new SelfRegisterOAuthClientResult(clientId, rawSecret)`

- **의존성 추가**: `PasswordEncoder` — `service-client` 모듈에서 Spring Security BCrypt를 사용하기 위해 생성자 주입. `auth-api`의 `ApplicationServiceConfig` 또는 `ServiceClientAutoConfiguration`에서 빈 주입 경로 확보 필요 (아래 `ApplicationServiceConfig` 설계 항목 참조).

- **적용 컨벤션**:
  - `@Service` + `@RequiredArgsConstructor` 유지
  - `@Transactional` 메서드 — 등록 전체가 단일 트랜잭션
  - Javadoc `@throws` 모든 예외 명시 (CONVENTION.md 4.1)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientService.java:48-68`
- **연관 todo**: `[A-IMPL-6]`

> **PasswordEncoder 의존성 위치 결정**: `RegisterOAuthClientService`가 `PasswordEncoder`를 직접 주입받는다. `service-client` 모듈 `build.gradle.kts`에 `compileOnly("org.springframework.security:spring-security-crypto")` (또는 이미 spring-security가 포함된 의존성으로 전이되는지 확인 필요). `auth-api`의 `SecurityConfig`가 이미 `BCryptPasswordEncoder(12)` 빈을 등록하므로 `auth-api` 컨텍스트에서는 자동 주입된다.

---

##### ClientLimitExceededException (신규)

- **타입**: 도메인 예외 (Domain Exception)
- **책임**: 회원 1인당 클라이언트 등록 5개 초과 시 발생.
- **설계**:
  ```java
  package com.econo.auth.client.exception;

  import org.springframework.http.HttpStatus;
  import org.springframework.web.bind.annotation.ResponseStatus;

  /** 클라이언트 등록 한도 초과 예외 (1인 최대 5개) */
  @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
  public class ClientLimitExceededException extends RuntimeException {

      public ClientLimitExceededException() {
          super("클라이언트 등록 한도를 초과했습니다. 회원당 최대 5개까지 등록할 수 있습니다.");
      }
  }
  ```
  - HTTP 422 (UNPROCESSABLE_ENTITY) 선택 이유: 요청 형식은 올바르나 비즈니스 규칙 위반 — "처리할 수 없는 엔티티"가 의미상 적합. 400(Bad Request), 429(Too Many Requests)보다 더 정확하게 "요청은 이해했으나 시스템이 수행할 수 없음"을 표현.
- **적용 컨벤션**:
  - 기존 예외 클래스 패턴 미러링: `DuplicateClientNameException`과 동일한 구조
  - `@ResponseStatus`로 HTTP 상태 선언
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/exception/DuplicateClientNameException.java`
- **연관 todo**: `[A-IMPL-7]`

---

#### 모듈 / 패키지: `services/apis/auth-api`

```
com.econo.auth.api
├── adapter/in/web/
│   ├── ClientController                — 신규: POST /api/v1/clients (셀프 등록)
│   └── LoginResponse                  — 변경: redirectUrl 필드 + app() 팩토리 시그니처 확장
├── config/
│   └── ApplicationServiceConfig       — 변경: RegisterOAuthClientService 빈 등록 (PasswordEncoder 주입)
├── exception/
│   └── GlobalExceptionHandler         — 변경: ClientLimitExceededException 핸들러 추가
└── filter/
    └── JsonLoginAuthenticationFilter  — 변경: APP 분기 redirectUrl 추가
```

---

##### ClientController (신규)

- **타입**: 인바운드 웹 어댑터 (Controller)
- **책임**: `POST /api/v1/clients` 엔드포인트 — 인증된 회원의 셀프 SSO 클라이언트 등록.
- **클래스 구조**:
  ```java
  @Slf4j
  @Tag(name = "OAuth Client — Self Registration", ...)
  @RestController
  @RequestMapping("/api/v1/clients")
  @RequiredArgsConstructor
  public class ClientController {

      private static final String PASSPORT_HEADER = "X-User-Passport";

      private final RegisterOAuthClientService registerOAuthClientService;
      private final ObjectMapper objectMapper;

      public record SelfRegisterClientRequest(
          @NotBlank String clientName, Set<String> redirectUris) {}

      public record SelfRegisterClientResponse(String clientId, String clientSecret) {}

      @PostMapping
      @ResponseStatus(HttpStatus.CREATED)
      public ResponseEntity<SelfRegisterClientResponse> registerClient(
          @RequestHeader(value = PASSPORT_HEADER, required = false) String passportHeader,
          @Valid @RequestBody SelfRegisterClientRequest request) { ... }
  }
  ```

- **인증 로직**:
  - `PassportClaims`는 `AdminClientController`에 이미 `public record`로 선언되어 있음. `ClientController`가 직접 복사하는 대신 **`AdminClientController.PassportClaims`를 참조**하거나, 공통 파싱 유틸 클래스 `PassportHeaderParser`를 `auth-api` 내 별도 유틸 클래스로 추출하는 방향 중 선택.
  - 추천: **`PassportHeaderParser` 유틸 클래스 추출** (`com.econo.auth.api.util` 패키지) — `AdminClientController`와 `ClientController` 양쪽에서 재사용. 로직: Base64 디코딩 → `PassportClaims` 역직렬화 → `memberId` 반환.
  - 인증 조건: ADMIN 역할 불필요, `memberId`가 non-null이면 허용. `passportHeader`가 null/blank이거나 파싱 실패 시 401 반환.

- **핸들러 구현**:
  ```java
  @PostMapping
  public ResponseEntity<?> registerClient(
      @RequestHeader(value = PASSPORT_HEADER, required = false) String passportHeader,
      @Valid @RequestBody SelfRegisterClientRequest request) {

      Long memberId = PassportHeaderParser.extractMemberId(passportHeader, objectMapper);
      if (memberId == null) {
          return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body(new ErrorResponse("UNAUTHORIZED", "인증이 필요합니다."));
      }

      var command = new RegisterOAuthClientService.SelfRegisterOAuthClientCommand(
          request.clientName(), request.redirectUris(), memberId);
      var result = registerOAuthClientService.selfRegister(command);
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(new SelfRegisterClientResponse(result.clientId(), result.clientSecret()));
  }
  ```

- **의존성**: `RegisterOAuthClientService`, `ObjectMapper`
- **적용 컨벤션**:
  - `@Slf4j` + `@RequiredArgsConstructor` (CONVENTION.md 2.2)
  - `@Tag`, `@Operation`, `@ApiResponses` OpenAPI 어노테이션 (기존 컨트롤러 패턴 미러링)
  - `@Valid @RequestBody` Bean Validation (CONVENTION.md 2.5)
  - 에러 응답 DTO로 `GlobalExceptionHandler.ApiError` 또는 기존 `AdminClientController.ErrorResponse` 참조 — 일관성을 위해 `GlobalExceptionHandler.ApiError`가 더 적합
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java:48-98` (구조 미러링)
- **연관 todo**: `[A-IMPL-9]`

---

##### PassportHeaderParser (신규 유틸)

- **타입**: 유틸리티 클래스 (Util)
- **책임**: `X-User-Passport` Base64 헤더를 파싱해 `PassportClaims`의 `memberId`를 추출하는 공통 로직.
- **패키지**: `com.econo.auth.api.util`
- **설계**:
  ```java
  package com.econo.auth.api.util;

  public final class PassportHeaderParser {

      private PassportHeaderParser() {}

      /**
       * X-User-Passport 헤더에서 memberId를 추출한다.
       * 헤더 누락·파싱 실패 시 null 반환.
       */
      @Nullable
      public static Long extractMemberId(String passportHeader, ObjectMapper objectMapper) { ... }

      /**
       * X-User-Passport 헤더에서 PassportClaims를 파싱한다.
       * 실패 시 null 반환.
       */
      @Nullable
      public static PassportClaims parseClaims(String passportHeader, ObjectMapper objectMapper) { ... }

      @JsonIgnoreProperties(ignoreUnknown = true)
      public record PassportClaims(Long memberId, List<String> roles) {
          public boolean isAdmin() {
              return roles != null && (roles.contains("ADMIN") || roles.contains("SUPER_ADMIN"));
          }
          public boolean isSuperAdmin() {
              return roles != null && roles.contains("SUPER_ADMIN");
          }
      }
  }
  ```
  - `AdminClientController`의 내부 `PassportClaims`와 `isAdmin()` 로직을 이곳으로 이전하고, `AdminClientController`는 `PassportHeaderParser.PassportClaims`를 사용하도록 변경.
- **연관 todo**: `[A-IMPL-9]` (PassportClaims 공통화 고려 항목)

---

##### ApplicationServiceConfig (변경)

- **타입**: 설정 (`@Configuration`)
- **책임**: 유스케이스 빈 등록 — `RegisterOAuthClientService`가 `PasswordEncoder`를 필요로 하므로 빈 등록 방식 변경.
- **변경 내용**:
  - `RegisterOAuthClientService`는 현재 `@Service`로 자동 등록됨. `PasswordEncoder` 빈이 `SecurityConfig`에서 등록되어 있으므로 `@Service` + `@RequiredArgsConstructor` 방식으로 자동 주입이 가능함.
  - **추가 변경 없음** — `RegisterOAuthClientService`가 `PasswordEncoder`를 `@RequiredArgsConstructor`로 주입받으면 Spring이 `SecurityConfig.passwordEncoder()` 빈을 자동 연결한다. `ApplicationServiceConfig`에 별도 `@Bean` 등록 불필요.
  - `LoginRedirectResolver` 빈 등록은 기존 그대로 유지.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java`
- **연관 todo**: `[A-IMPL-6]` (PasswordEncoder 주입 경로)

---

##### GlobalExceptionHandler (변경)

- **타입**: `@RestControllerAdvice`
- **책임**: 전역 예외 처리 — `ClientLimitExceededException` 핸들러 추가.
- **추가 메서드**:
  ```java
  /**
   * 클라이언트 등록 한도 초과 예외 처리
   *
   * @param ex 예외
   * @return 422 CLIENT_LIMIT_EXCEEDED
   */
  @ExceptionHandler(ClientLimitExceededException.class)
  public ResponseEntity<ApiError> handleClientLimitExceeded(ClientLimitExceededException ex) {
      return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
          .body(new ApiError("CLIENT_LIMIT_EXCEEDED", ex.getMessage()));
  }
  ```
- **적용 컨벤션**:
  - 기존 핸들러 패턴 미러링 (`handleDuplicateClientName` 참조)
  - Javadoc `@param`, `@return` 필수
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java:157-161`
- **연관 todo**: `[A-IMPL-8]`

---

##### LoginResponse (변경)

- **타입**: DTO (Response Record)
- **책임**: 로그인/재발급 응답 — WEB(쿠키+302), APP(body) 분기 표현.
- **변경 내용**:
  - `redirectUrl` 필드 추가: `@JsonInclude(NON_NULL)`이 이미 클래스 레벨에 적용되어 null이면 직렬화 제외됨.
  - 기존 record 시그니처: `LoginResponse(String accessToken, Long accessExpiredTime, String refreshToken)`
  - 변경 후 record 시그니처: `LoginResponse(String accessToken, Long accessExpiredTime, String refreshToken, String redirectUrl)`
  - 기존 `app(String accessToken, long accessExpiredTime, String refreshToken)` 팩토리 메서드 유지 (redirectUrl=null로 위임) — 하위 호환
  - 신규 `app(String accessToken, long accessExpiredTime, String refreshToken, String redirectUrl)` 팩토리 메서드 추가
- **적용 컨벤션**:
  - `@JsonInclude(JsonInclude.Include.NON_NULL)` 클래스 레벨 유지 → `redirectUrl = null`이면 WEB/APP 모두 미직렬화
  - `record` 타입 유지
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/LoginResponse.java`
- **연관 todo**: `[B-IMPL-1]`

---

##### JsonLoginAuthenticationFilter (변경)

- **타입**: Spring Security Filter
- **책임**: JSON 로그인 처리 — APP 분기에 `LoginRedirectResolver` 호출 추가.
- **변경 내용**: `successfulAuthentication` 내 `isApp == true` 분기:
  ```java
  if (isApp) {
      // APP: AT + RT 모두 body (200 OK) + redirectUrl
      String clientId = (String) request.getAttribute("clientId");
      String redirectUrl = loginRedirectResolver.resolve(clientId, defaultRedirectUrl);
      var body = LoginResponse.app(
          tokens.accessToken(), tokens.accessExpiredAt(), tokens.refreshToken(), redirectUrl);
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      objectMapper.writeValue(response.getWriter(), body);
  } else {
      // WEB: 기존 그대로
      ...
  }
  ```
  - `loginRedirectResolver`는 이미 필드로 주입되어 있고 `defaultRedirectUrl`도 이미 필드로 있음 — 추가 생성자 변경 불필요.
  - `clientId`는 `attemptAuthentication`에서 `request.setAttribute("clientId", loginRequest.clientId())`로 이미 설정됨 — 재사용.
  - `loginRedirectResolver.resolve(clientId, defaultRedirectUrl)`은 clientId가 null이거나 미등록이면 `defaultRedirectUrl`을 반환하므로 fail-safe 보장됨.
- **WEB 분기**: 변경 없음.
- **적용 컨벤션**:
  - 기존 코드 최소 변경 원칙
  - APP 분기 코드 블록 구조 유지 (인라인 주석 포함)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java:92-100`
- **연관 todo**: `[B-IMPL-2]`

---

#### 모듈 / 패키지: `services/libs/member` (DB 마이그레이션)

```
src/main/resources/db/migration/
└── V7__add_owner_id_to_service_client.sql   — 신규: owner_id, client_secret_hash 컬럼 + 인덱스
```

##### V7__add_owner_id_to_service_client.sql (신규)

- **타입**: Flyway 마이그레이션
- **책임**: `service_client` 테이블에 `owner_id`, `client_secret_hash` 컬럼 추가.
- **내용**:
  ```sql
  ALTER TABLE service_client
      ADD COLUMN owner_id          BIGINT       NULL,
      ADD COLUMN client_secret_hash VARCHAR(72) NULL;

  COMMENT ON COLUMN service_client.owner_id
      IS '클라이언트 소유자 회원 ID (셀프 등록 시 설정, ADMIN 등록 시 NULL)';
  COMMENT ON COLUMN service_client.client_secret_hash
      IS 'BCrypt 해시된 클라이언트 시크릿 (셀프 등록 시 설정, ADMIN 등록 시 NULL)';

  CREATE INDEX idx_service_client_owner_id ON service_client(owner_id);
  ```
  - `owner_id BIGINT NULL` — 기존 레코드는 null (무중단 마이그레이션 조건 충족)
  - `client_secret_hash VARCHAR(72) NULL` — BCrypt `$2a$12$` 포함 최대 60자, 여유분 포함 72
  - `client_secret_hash`는 `service_client` 테이블에 저장 (**SAS `oauth2_registered_client.client_secret`과 이중 저장하지 않음**) — 이유: SAS 테이블의 `client_secret`은 SAS 자체 검증 흐름(`/oauth2/token`)용이고, 셀프 등록 secret은 redirect-uri 관리 Basic Auth 검증에만 쓰임. 두 용도가 다르며, SAS NONE 등록이라 SAS `client_secret`은 실제로 null로 저장됨.
  - `idx_service_client_owner_id` 인덱스 — `countByOwnerId` 쿼리 성능 보장
- **연관 todo**: `[A-DB-1]`, `[A-DB-2]`

---

### 호출 흐름

#### [기능 A] 셀프 등록 정상 경로

```
POST /api/v1/clients
  Header: X-User-Passport: <Base64({"memberId":123,"roles":["USER"]})>
  Body:   { "clientName": "my-app", "redirectUris": ["https://my-app.com/callback"] }

→ ClientController.registerClient(passportHeader, request)
  → PassportHeaderParser.extractMemberId(passportHeader, objectMapper)
      → Base64.decode → objectMapper.readValue → PassportClaims.memberId = 123
  → RegisterOAuthClientService.selfRegister(SelfRegisterOAuthClientCommand("my-app", {...}, 123L))
      → serviceClientRepository.countByOwnerId(123L) → 0 (< 5, 통과)
      → serviceClientRepository.existsByClientName("my-app") → false (통과)
      → clientId = UUID.randomUUID().toString()
      → rawSecret = UUID.randomUUID().toString()
      → secretHash = passwordEncoder.encode(rawSecret)
      → sasClientRegistrar.registerAuthorizationCodeClient(clientId, "my-app", {...})
          → SasClientRegistrarAdapter → registeredClientRepository.save(RegisteredClient[NONE])
      → serviceClientRepository.save(ServiceClient[clientId, "my-app", AUTHORIZATION_CODE, null, 123L, secretHash])
      → return SelfRegisterOAuthClientResult(clientId, rawSecret)
  → return 201 Created { "clientId": "...", "clientSecret": "..." }
```

#### [기능 A] 예외 / 실패 경로

```
[인증 실패]
  X-User-Passport 헤더 없음 또는 파싱 실패
  → PassportHeaderParser.extractMemberId → null
  → ClientController → 401 { "errorCode": "UNAUTHORIZED" }

[5개 초과]
  serviceClientRepository.countByOwnerId(123L) → 5 (>= 5)
  → throw ClientLimitExceededException
  → GlobalExceptionHandler.handleClientLimitExceeded
  → 422 { "errorCode": "CLIENT_LIMIT_EXCEEDED", "message": "..." }

[redirectUris 누락]
  command.redirectUris() == null || isEmpty
  → throw RedirectUriRequiredException
  → GlobalExceptionHandler.handleRedirectUriRequired
  → 400 { "errorCode": "REDIRECT_URI_REQUIRED" }

[clientName 중복]
  serviceClientRepository.existsByClientName("my-app") → true
  → throw DuplicateClientNameException
  → GlobalExceptionHandler.handleDuplicateClientName
  → 409 { "errorCode": "DUPLICATE_CLIENT_NAME" }

[Bean Validation 실패]
  clientName = "" (blank)
  → MethodArgumentNotValidException
  → GlobalExceptionHandler.handleValidation
  → 400 { "errorCode": "VALIDATION_FAILED", "fieldErrors": [...] }
```

#### [기능 B] APP 로그인 redirectUrl 포함 정상 경로

```
POST /api/v1/auth/login
  Header: Client-Type: APP
  Body:   { "loginId": "user1", "password": "...", "clientId": "my-app-client-id" }

→ JsonLoginAuthenticationFilter.attemptAuthentication()
  → LoginRequest(loginId, password, clientId="my-app-client-id")
  → request.setAttribute("clientId", "my-app-client-id")
  → DaoAuthenticationProvider → BCrypt 검증 → 인증 성공

→ JsonLoginAuthenticationFilter.successfulAuthentication()
  → loginTokenService.issue() → TokenPair(AT, expiredAt, RT)
  → clientType = "APP" → isApp = true
  → clientId = request.getAttribute("clientId") = "my-app-client-id"
  → loginRedirectResolver.resolve("my-app-client-id", defaultRedirectUrl)
      → ClientRedirectUriService.findByClientId("my-app-client-id")
      → redirectUris = {"https://my-app.com/callback"}
      → return "https://my-app.com/callback"
  → LoginResponse.app(AT, expiredAt, RT, "https://my-app.com/callback")
  → 200 OK { "accessToken": "...", "accessExpiredTime": ..., "refreshToken": "...", "redirectUrl": "https://my-app.com/callback" }
```

#### [기능 B] 예외 / 실패 경로

```
[clientId 없는 APP 로그인]
  request.getAttribute("clientId") = null
  → loginRedirectResolver.resolve(null, defaultRedirectUrl) → defaultRedirectUrl
  → LoginResponse.app(AT, expiredAt, RT, defaultRedirectUrl)
  → 200 OK { ..., "redirectUrl": "<default-url>" }

[미등록 clientId]
  loginRedirectResolver.resolve("unknown-id", defaultRedirectUrl)
  → ClientRedirectUriService.findByClientId("unknown-id") → throw InvalidClientException
  → catch InvalidClientException → return defaultRedirectUrl
  → LoginResponse.app(AT, expiredAt, RT, defaultRedirectUrl)
  → 200 OK { ..., "redirectUrl": "<default-url>" }

[WEB 분기 — redirectUrl 미포함 확인]
  clientType != "APP"
  → isApp = false → WEB 분기 실행
  → LoginResponse.app(...) 호출 안 됨 → redirectUrl 필드 직렬화 안 됨
  → 302 Found (기존 동작 그대로)
```

---

### 구 코드 대비 신규/변경/삭제 표

| 항목 | 구 상태 | 신규 상태 | 분류 |
|---|---|---|---|
| `ServiceClient` 도메인 필드 | `registeredClientId`, `clientName`, `grantType`, `apiKeyHash` | + `ownerId`, `clientSecretHash` 추가 | 변경 |
| `ServiceClient.create(4인자)` | `(clientId, clientName, grantType, apiKeyHash)` | 유지 (6인자 팩토리에 위임) | 변경 |
| `ServiceClient.create(6인자)` | 없음 | `(clientId, clientName, grantType, apiKeyHash, ownerId, clientSecretHash)` | 신규 |
| `ServiceClientJpaEntity` 컬럼 | `registered_client_id`, `client_name`, `grant_type`, `api_key_hash`, `created_at` | + `owner_id`, `client_secret_hash` | 변경 |
| `ServiceClientRepository` 포트 | `save`, `existsByClientName`, `findAllRegisteredClientIds` | + `countByOwnerId` | 변경 |
| `ServiceClientJpaRepository` | `existsByClientName`, `findAllRegisteredClientIds` | + `countByOwnerId` | 변경 |
| `ServiceClientRepositoryAdapter` | 3메서드 위임 | + `countByOwnerId` 위임 | 변경 |
| `RegisterOAuthClientService` | `RegisterOAuthClientCommand`, `RegisterOAuthClientResult`, `register()` | + `SelfRegisterOAuthClientCommand`, `SelfRegisterOAuthClientResult`, `selfRegister()`, `PasswordEncoder` 의존성 | 변경 |
| `ClientLimitExceededException` | 없음 | 신규 (`422 CLIENT_LIMIT_EXCEEDED`) | 신규 |
| `ClientController` | 없음 | 신규 (`POST /api/v1/clients`) | 신규 |
| `PassportHeaderParser` 유틸 | 없음 (`AdminClientController.PassportClaims` 내부 record) | 신규 (`com.econo.auth.api.util`) | 신규 |
| `AdminClientController.PassportClaims` | 내부 `public record` | `PassportHeaderParser.PassportClaims` 참조로 교체 | 변경 |
| `GlobalExceptionHandler` | 10개 핸들러 | + `handleClientLimitExceeded` | 변경 |
| `LoginResponse` | `(accessToken, accessExpiredTime, refreshToken)` record | + `redirectUrl` 필드, `app(4인자)` 팩토리 추가 | 변경 |
| `JsonLoginAuthenticationFilter` APP 분기 | `LoginResponse.app(AT, expiredAt, RT)` | `LoginResponse.app(AT, expiredAt, RT, redirectUrl)` — `loginRedirectResolver.resolve` 호출 추가 | 변경 |
| `V7__add_owner_id_to_service_client.sql` | 없음 | 신규 (`owner_id`, `client_secret_hash`, `idx_service_client_owner_id`) | 신규 |

---

### 컨벤션 준수 항목

- **네이밍**: `ClientController`, `PassportHeaderParser`, `ClientLimitExceededException` — PascalCase, 접미사로 역할 표현 (CONVENTION.md 1.2). `countByOwnerId`, `selfRegister` — camelCase (1.3).
- **의존성 주입**: `@RequiredArgsConstructor` + `private final` 필드 — 직접 생성자를 쓰는 경우(`JsonLoginAuthenticationFilter`)만 예외 (CONVENTION.md 2.2).
- **예외 처리**: 정적 팩토리 메서드 패턴은 `PassportException` 전용 패턴이고, 도메인 예외(`ClientLimitExceededException`)는 생성자 직접 호출. 기존 예외 클래스 패턴(`DuplicateClientNameException`) 미러링 (CONVENTION.md 3.1).
- **불변성**: `ServiceClient` 필드 `private final` 유지, 컬렉션 방어적 복사는 해당 없음 (CONVENTION.md 2.3).
- **JSON 직렬화**: `LoginResponse` — `@JsonInclude(NON_NULL)` 기존 어노테이션 활용으로 `redirectUrl` null이면 직렬화 제외 (CONVENTION.md 2.4).
- **Validation**: `SelfRegisterClientRequest.clientName`에 `@NotBlank` (CONVENTION.md 2.5).
- **테스트 패턴**: `@Nested` + `@DisplayName` 한글, Given-When-Then, `@ExtendWith(MockitoExtension.class)` 단위 테스트, `@WebMvcTest` 웹 레이어 테스트 (CONVENTION.md 5.1~5.3).
- **Javadoc**: 모든 `public` 클래스/메서드에 Javadoc, `@param` / `@return` / `@throws` 필수 (CONVENTION.md 4.1).
- **포맷팅**: Spotless + Google Java Format 1.17.0 — `./gradlew format` 후 커밋 (CONVENTION.md 2.1).

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨 (A-IMPL-1~10, B-IMPL-1~2, A-DB-1~2 모두 포함)
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨 (도메인 → 포트 → 어댑터 헥사고날 준수)
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두 — 인증 실패, 5개 초과, redirectUris 누락, clientName 중복, Bean Validation 실패, APP clientId 없음/미등록, WEB 분기 미영향)

---

## 미해결 / 리스크

1. **`PasswordEncoder` 의존성 전이**: `RegisterOAuthClientService`가 `PasswordEncoder`를 주입받으려면 `service-client` 모듈 `build.gradle.kts`에 `compileOnly` 또는 `implementation` 형태로 `spring-security-crypto`가 필요하다. 현재 `build.gradle.kts`를 읽지 않았으므로 이미 포함된 의존성인지 확인 필요. `auth-api`가 `service-client`를 의존하고 `spring-security`가 `auth-api`에 있으므로 런타임에는 문제없으나, `service-client` 단독 컴파일/테스트 시 `compileOnly`로 선언해야 할 수 있다.

2. **`RegisterOAuthClientService` `@Service` 자동 등록 vs `ApplicationServiceConfig`**: 현재 `RegisterOAuthClientService`는 `@Service`로 자동 등록된다. `PasswordEncoder`를 주입받으면 `service-client` 모듈의 `ServiceClientAutoConfiguration` 스캔 시점에 `PasswordEncoder` 빈이 없으면 실패한다. `ServiceClientAutoConfiguration`이 `@AutoConfiguration`으로 늦게 로드되므로 일반적으로 문제없으나, 테스트 슬라이스(`@DataJpaTest` 등)에서 `PasswordEncoder` 빈 미존재 시 컨텍스트 로드 실패 가능. 테스트 설정에서 `PasswordEncoder` mock 또는 `@TestConfiguration` 제공 필요.

3. **`AdminClientController`의 `clientSecret` 검증 흐름 미구현**: 기존 `AdminClientController`의 `GET /clients/{clientId}`, `POST/DELETE/PUT /clients/{clientId}/redirect-uris`는 현재 `isAdmin()` (Passport ADMIN 역할 확인)으로 보호되어 있다. ADR-0010에서는 이를 Basic Auth(`clientId:clientSecret`)로 전환하기로 결정했으나 아직 미구현 상태이다. 이번 작업 범위에 포함할지 결정 필요 — todo에 명시되지 않았으므로 현재 범위에서 제외하고 별도 이슈로 추적 권장.

4. **`POST /api/v1/clients` 도배 공격**: 셀프 등록 엔드포인트가 인증된 회원이라면 누구나 접근 가능하다. 1인 5개 제한은 있으나 대량 계정 생성 후 클라이언트 등록 공격에 대한 방어가 없다. ADR-0010에서도 언급된 rate limiting은 이번 범위 밖으로 별도 이슈 추적 필요.

5. **`SecurityConfig`의 `/api/v1/clients` 경로 permitAll 추가**: 현재 `SecurityConfig`는 `/api/v1/admin/**`와 `/api/v1/members/**`를 `permitAll`로 등록하고 있다. 새 `/api/v1/clients`도 `permitAll` 목록에 추가해야 한다 (인증 자체는 컨트롤러가 `X-User-Passport`로 수행). `SecurityConfig.appSecurityFilterChain` 변경 필요 — 현재 설계 표에 누락되어 있으므로 구현 시 포함.

---

## 참고
- `docs/ARCHITECTURE.md` — 헥사고날 구조, service-client 패키지 구조, 인증 흐름 A, 에러 코드 체계
- `docs/CONVENTION.md` — 네이밍 규칙, Lombok 사용, 불변성, 테스트 컨벤션
- `docs/adr/0010-client-secret-self-service-auth.md` — clientSecret + PKCE 공존 결정 배경
- `docs/adr/0012-backend-decided-login-redirect.md` — APP 분기 redirectUrl 추가 배경
- `services/libs/service-client/src/main/java/com/econo/auth/client/domain/ServiceClient.java` — 도메인 객체 참조
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientService.java` — 기존 등록 서비스 참조
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/sas/SasClientRegistrarAdapter.java` — NONE 방식 SAS 등록 확인
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` — PassportClaims 파싱 패턴 참조
- `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` — APP 분기 수정 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 핸들러 추가 참조
