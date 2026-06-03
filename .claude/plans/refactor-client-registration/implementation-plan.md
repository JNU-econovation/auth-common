# refactor-client-registration - implementation

## 메타
- **작업명**: refactor-client-registration
- **문서 타입**: implementation
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

`POST /api/v1/admin/clients` 엔드포인트에서 `grantType`과 `redirectUris`를 사실상 필수로 강제하는 코드 경로를 제거하여 `{ "clientName": "app-b" }` 만으로도 201 응답이 가능하도록 전환한다. OAuth 필드(필드 자체, 예외 클래스, 핸들러)는 폐기하지 않고 보존한다. Java 21 / Spring Boot 3.2 / Hexagonal Architecture 위에서 설계되며, 변경 대상은 `services/apis/auth-api` 모듈 내부에 국한된다. Flyway 마이그레이션 파일 하나가 `services/libs/auth-infra` 모듈에 추가된다.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/apis/auth-api/src/main/java/com/econo/auth/api/domain/GrantType.java` | 변경 | `fromString(null)` null 허용 처리 — IMPL-1 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` | 변경 | `RegisterClientRequest` Swagger 문서 갱신 — API-1, API-2, IMPL-2 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/application/usecase/RegisterOAuthClientService.java` | 변경 | Command null 허용, validateCommand 완화, register 단일 흐름 통합 — IMPL-3, IMPL-4, IMPL-5, IMPL-8 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/application/port/out/SasClientRegistrar.java` | 변경 | 단일 `registerClient` 메서드 추가, 기존 두 메서드 `@Deprecated` 처리 — IMPL-6 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/sas/SasClientRegistrarAdapter.java` | 변경 | `registerClient` 단일 메서드 구현 — IMPL-7 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/domain/ServiceClient.java` | 변경 | `grantType`, `apiKeyHash` `@Nullable` 명시 — IMPL-9 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/persistence/ServiceClientJpaEntity.java` | 변경 | `grant_type` `nullable = true` — IMPL-10 |
| `services/libs/auth-infra/src/main/resources/db/migration/V5__make_grant_type_nullable.sql` | 신규 | `grant_type NOT NULL` 제약 제거 Flyway 마이그레이션 — DB-1 |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/application/usecase/RegisterOAuthClientServiceTest.java` | 변경 | null grantType 관련 테스트 교체/추가, SAS 포트 verify 갱신 — TEST-1, TEST-2, TEST-5 |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java` | 변경 | grantType 생략 등록 웹 레이어 테스트 추가 — TEST-3 |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` | 변경 | grantType 없는 등록 통합 테스트 추가 — TEST-4 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/apis/auth-api`

```
com.econo.auth.api/
├── domain/
│   ├── GrantType.java                  — fromString null 허용 변경
│   └── ServiceClient.java              — @Nullable 표기 추가
├── application/
│   ├── port/out/
│   │   └── SasClientRegistrar.java     — registerClient 단일 메서드 추가, 기존 @Deprecated
│   └── usecase/
│       └── RegisterOAuthClientService.java — Command nullable, validateCommand 완화, register 통합
├── adapter/
│   ├── in/web/
│   │   └── AdminClientController.java  — Swagger 문서 갱신
│   └── out/
│       ├── persistence/
│       │   └── ServiceClientJpaEntity.java — grant_type nullable = true
│       └── sas/
│           └── SasClientRegistrarAdapter.java — registerClient 구현
└── (exception/ 변경 없음)
```

---

##### GrantType (변경)
- **타입**: Domain Enum
- **책임**: `fromString(null)` 호출 시 `null` 반환. 비-null 알 수 없는 값 시 `UnsupportedGrantTypeException` 유지.
- **주요 변경**:
  - `fromString(String value)` 첫 줄의 `if (value == null) throw ...` 조건을 `if (value == null) return null;` 로 교체
  - 메서드 시그니처에 `@Nullable` 어노테이션 추가 (`org.springframework.lang.Nullable` 또는 Jakarta `@Nullable`, 프로젝트 내 기존 사용 어노테이션 확인 후 통일)
  - Javadoc `@return` 갱신: "null 입력 시 null 반환, 알 수 없는 비-null 값이면 UnsupportedGrantTypeException"
- **적용 컨벤션**:
  - 한국어 Javadoc, 영어 식별자 (CONVENTION.md §4.1)
  - `@param`, `@return`, `@throws` 태그 필수
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/domain/GrantType.java:17-20`
- **연관 todo**: `[IMPL-1] GrantType.fromString(null) null 허용으로 변경`

---

##### RegisterOAuthClientCommand (변경 — RegisterOAuthClientService 내부 record)
- **타입**: Command Record (Application 계층)
- **책임**: 클라이언트 등록 요청 파라미터 운반. `grantType` null 허용.
- **주요 변경**:
  - `grantType` 컴포넌트 앞에 `@Nullable` 추가
  - Javadoc `@param grantType` 갱신: "그랜트 타입. null이면 서비스에서 CLIENT_CREDENTIALS 디폴트 적용"
  - `apiKeyHash` 이미 nullable이므로 표기 확인만 (추가 로직 불필요)
- **적용 컨벤션**:
  - record 사용 유지 (기존 패턴 그대로)
  - 한국어 Javadoc (CONVENTION.md §4.1)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/usecase/RegisterOAuthClientService.java:55-60`
- **연관 todo**: `[IMPL-3] RegisterOAuthClientCommand — grantType null 허용 선언`

---

##### RegisterOAuthClientService (변경)
- **타입**: Application Service (`@Service`)
- **책임**: OAuth 클라이언트 등록 유스케이스 구현. grantType null 시 `CLIENT_CREDENTIALS` 디폴트 적용 후 단일 흐름으로 처리.
- **주요 변경**:

  1. **`validateCommand` — grantType null 체크 제거**
     - `if (command.grantType() == null) throw new IllegalArgumentException(...)` 조건 전체 삭제
     - `clientName` null 검사는 그대로 유지
     ```
     // 변경 전
     if (command.grantType() == null) { throw new IllegalArgumentException("grantType은 필수입니다."); }
     if (command.clientName() == null) { throw new IllegalArgumentException("clientName은 필수입니다."); }

     // 변경 후
     if (command.clientName() == null) { throw new IllegalArgumentException("clientName은 필수입니다."); }
     ```

  2. **`register` — 단일 흐름 통합 + 새 포트 호출**
     - 기존 두 갈래 분기(`AUTHORIZATION_CODE` / else)를 유지하되, 시작 지점에서 `resolved` 로컬 변수 선언:
       ```
       GrantType resolved = (command.grantType() != null)
           ? command.grantType()
           : GrantType.CLIENT_CREDENTIALS;
       ```
     - `AUTHORIZATION_CODE` 분기: redirectUris 검증 유지, `bcryptSecret = null` 설정
     - `CLIENT_CREDENTIALS`(또는 null → 디폴트) 분기: rawSecret + bcryptSecret 생성. **apiKeyHash는 항상 null**
     - SAS 포트 호출을 두 개별 메서드 대신 `registerClient` 단일 메서드로 교체:
       ```
       sasClientRegistrar.registerClient(
           clientId,
           command.clientName(),
           bcryptSecret,         // AUTHORIZATION_CODE 분기에서는 null
           command.redirectUris()
       );
       ```
     - `serviceClientRepository.save(ServiceClient.create(...))` 호출 시 `resolved` 값 전달 (null 아닌 디폴트 값으로 저장)

  3. **`apiKeyHash` 생성 정책 결정**:
     - **항상 null 정책 채택**. 어느 분기에서도 `apiKeyHash`를 생성하지 않으며, `sha256Hex(rawSecret)` 호출과 `apiKeyHash` 로컬 변수 자체를 제거한다. `ServiceClient.create(...)` 호출 시 마지막 인자는 항상 `null`.
     - **`RegisterOAuthClientService.sha256Hex(String)` private 메서드 + `MessageDigest`/`StandardCharsets`/`HexFormat`/`NoSuchAlgorithmException` import 모두 제거** (사용처가 사라짐).
     - 근거: `api_key_hash` 컬럼은 검증 로직이 코드 전역에 0건이고, OAuth 2.0 표준 필드도 아니라 향후 OAuth 재도입 시점에도 자동으로 채워질 일이 없다. "안 쓰는 필드는 채우지 않고 nullable로 보존"하는 일관된 정책. 컬럼은 그대로 두되 (V4에서 이미 NULL 허용) 값은 항상 null로 저장한다. 향후 자체 API key 인증 채널이 필요해지면 그 시점에 생성 로직 부활.

- **의존성**: `SasClientRegistrar` (포트), `ServiceClientRepository` (포트), `ServiceRouteRepository` (포트), `PasswordEncoder`
- **적용 컨벤션**:
  - `@RequiredArgsConstructor` (CONVENTION.md §2.2)
  - `@Transactional` 유지
  - 한국어 Javadoc (CONVENTION.md §4.1)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/usecase/RegisterOAuthClientService.java:79-116`, `:127-134`
- **연관 todo**: `[IMPL-4]`, `[IMPL-5]`, `[IMPL-8]`

---

##### SasClientRegistrar (변경 — 포트 인터페이스)
- **타입**: Outbound Port (인터페이스)
- **책임**: SAS 클라이언트 등록 아웃바운드 계약. 단일 `registerClient` 메서드로 통합.
- **주요 변경**:
  - 신규 메서드 추가:
    ```java
    /**
     * OAuth 클라이언트를 SAS에 등록한다.
     *
     * <p>bcryptHashedSecret이 null이면 Authorization Code 공개 클라이언트(PKCE 필수)로 등록하고,
     * non-null이면 Client Credentials 시크릿 클라이언트로 등록한다.
     *
     * @param clientId      클라이언트 ID (UUID)
     * @param clientName    클라이언트 이름
     * @param bcryptHashedSecret BCrypt 해시된 시크릿 ({@code {bcrypt}...} 형식). null이면 공개 클라이언트.
     * @param redirectUris  허용 리다이렉트 URI 목록. 빈 Set이면 builder에 추가하지 않는다.
     */
    void registerClient(
        String clientId,
        String clientName,
        @Nullable String bcryptHashedSecret,
        Set<String> redirectUris
    );
    ```
  - 기존 두 메서드 `@Deprecated` 처리. `default` 메서드로 `registerClient` 위임 구현을 남겨 컴파일 호환성 유지:
    ```java
    /** @deprecated {@link #registerClient} 사용 */
    @Deprecated
    default void registerAuthorizationCodeClient(
            String clientId, String clientName, Set<String> redirectUris) {
        registerClient(clientId, clientName, null, redirectUris);
    }

    /** @deprecated {@link #registerClient} 사용 */
    @Deprecated
    default void registerClientCredentialsClient(
            String clientId, String clientName, String bcryptHashedSecret) {
        registerClient(clientId, clientName, bcryptHashedSecret, Set.of());
    }
    ```
  - 단, `RegisterOAuthClientService`는 즉시 `registerClient`만 사용하므로 기존 두 메서드는 테스트 코드 갱신 후 추후 삭제 가능. 이번 작업 범위에서는 `@Deprecated` 유지.
- **적용 컨벤션**:
  - 헥사고날 원칙: 포트는 Application 계층에 위치, SAS 타입 미노출 (ARCHITECTURE.md §6)
  - 한국어 Javadoc
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/port/out/SasClientRegistrar.java:1-32`
- **연관 todo**: `[IMPL-6] SasClientRegistrar 포트 — 단일 메서드 registerClient로 통합`

---

##### SasClientRegistrarAdapter (변경)
- **타입**: Outbound Adapter (`@Component`)
- **책임**: `SasClientRegistrar` 포트 구현. `RegisteredClient` 빌드 로직을 인프라 어댑터 내부에 캡슐화.
- **주요 변경**:
  - `registerClient(clientId, clientName, bcryptHashedSecret, redirectUris)` 메서드 구현 추가:
    ```
    if (bcryptHashedSecret == null) {
        // Authorization Code 공개 클라이언트
        builder.clientAuthenticationMethod(NONE)
               .authorizationGrantType(AUTHORIZATION_CODE)
               .scope(OidcScopes.OPENID)
               .clientSettings(requireProofKey=true, requireAuthorizationConsent=false)
        redirectUris.forEach(builder::redirectUri);   // 빈 Set이면 루프 미실행
    } else {
        // Client Credentials 시크릿 클라이언트
        builder.clientSecret(bcryptHashedSecret)
               .clientAuthenticationMethod(CLIENT_SECRET_BASIC)
               .authorizationGrantType(CLIENT_CREDENTIALS)
               .scope("read")
    }
    registeredClientRepository.save(builder.build());
    ```
  - `authorizationGrantType()` SAS 빌더 검증: 두 분기 모두 최소 1개 부여 — AUTHORIZATION_CODE(null 분기) 또는 CLIENT_CREDENTIALS(non-null 분기). SAS 빌더 내부 검증 통과 확인.
  - **디폴트 AuthorizationGrantType 결정 근거**:
    - `grantType == null` 시 서비스에서 이미 `CLIENT_CREDENTIALS`로 resolved 되어 `bcryptHashedSecret`이 non-null로 전달됨. 따라서 어댑터는 null 분기에서 항상 AUTHORIZATION_CODE를 부여한다.
    - 어댑터는 `bcryptHashedSecret`의 null 여부만 보면 되며, grantType 값 자체를 알 필요 없음. 향후 OAuth 재도입 시 포트 시그니처에 `GrantType resolved` 파라미터 추가 후 분기 확장하면 된다. 코드 주석으로 명시.
  - 기존 `registerAuthorizationCodeClient`, `registerClientCredentialsClient`는 포트 인터페이스의 `default` 위임 메서드로 대체되므로 어댑터 구현 제거.
- **의존성**: `RegisteredClientRepository` (SAS 인프라)
- **적용 컨벤션**:
  - `@RequiredArgsConstructor`
  - SAS API(`RegisteredClient`, `AuthorizationGrantType` 등)는 이 어댑터에만 국한
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/sas/SasClientRegistrarAdapter.java:27-58`
- **연관 todo**: `[IMPL-7] SasClientRegistrarAdapter — 단일 registerClient 메서드 구현`

---

##### AdminClientController (변경 — Swagger/API 문서 갱신)
- **타입**: Inbound Adapter (`@RestController`)
- **책임**: HTTP 매핑, API 키 인증, GrantType 파싱 위임. 이번 작업에서 로직 변경 없음. Swagger 문서와 `parseGrantType` 경로만 수정.
- **주요 변경**:
  1. `registerClient` 메서드 내 `GrantType grantType = GrantType.fromString(request.grantType())` — `fromString`이 null을 반환하도록 변경되었으므로, 이 라인은 수정 없이 그대로 동작함. null grantType이 Command에 그대로 전달된다.
  2. `@Operation` description 갱신:
     - "grantType 생략 가능. 생략 시 `client_credentials`로 처리하여 clientSecret 발급." 문구 추가
  3. `@ApiResponse(responseCode = "400")` description 표 갱신:
     - `REDIRECT_URI_REQUIRED`: "authorization_code 타입이 **명시된** 경우에만 발생"으로 조건부 설명
     - `UNSUPPORTED_GRANT_TYPE`: "null이 아닌 알 수 없는 값일 때만 발생"으로 설명 보강
  4. `RegisterClientRequest` Javadoc `@param grantType` 갱신:
     - "그랜트 타입 (authorization_code, client_credentials). **생략 가능** — 생략 시 client_credentials 디폴트 적용"
- **적용 컨벤션**:
  - 컨트롤러는 HTTP 매핑과 API 키 인증에만 집중 (기존 주석 유지)
  - `@JsonInclude(NON_NULL)` — `RegisterClientResponse`에 이미 적용, 유지
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java:108-162`
- **연관 todo**: `[API-1]`, `[API-2]`, `[IMPL-2]`

---

##### ServiceClient (변경 — 도메인)
- **타입**: Domain Object
- **책임**: 서비스 클라이언트 불변 도메인 객체. `grantType`, `apiKeyHash` nullable 명시.
- **주요 변경**:
  - `grantType` 필드와 `create` 팩토리 파라미터에 `@Nullable` 표기
  - `apiKeyHash` 필드와 파라미터에도 `@Nullable` 명시적으로 추가 (기존 로직은 이미 null 가능)
  - Javadoc `@param grantType`, `@param apiKeyHash` 갱신
- **적용 컨벤션**:
  - `private final` 필드 (CONVENTION.md §2.3 불변성)
  - 직접 생성자 유지 (생성자 로직 없으므로 `@RequiredArgsConstructor`로 전환 가능하나, 정적 팩토리 패턴 유지)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/domain/ServiceClient.java:1-35`
- **연관 todo**: `[IMPL-9] ServiceClient 도메인 — grantType nullable 명시`

---

##### ServiceClientJpaEntity (변경)
- **타입**: JPA Entity (Outbound Persistence Adapter)
- **책임**: `service_client` 테이블 매핑. `grant_type` 컬럼 nullable 허용.
- **주요 변경**:
  - `@Column(name = "grant_type", nullable = false, length = 30)` → `@Column(name = "grant_type", nullable = true, length = 30)` 또는 `nullable` 속성 제거 (JPA 기본값 `true`)
  - `from(ServiceClient)` 변환 메서드: `entity.grantType = serviceClient.getGrantType()` — null이 그대로 전달되며 추가 로직 불필요. 확인만.
  - `toDomain()`: 동일하게 null 전달, 변경 없음.
- **적용 컨벤션**:
  - `@NoArgsConstructor(access = PROTECTED)` 유지
  - `@Enumerated(EnumType.STRING)` 유지 — null 값은 JPA가 그대로 null로 저장
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/persistence/ServiceClientJpaEntity.java:39`
- **연관 todo**: `[IMPL-10] ServiceClientJpaEntity — grant_type nullable JPA 매핑 수정`

---

#### 모듈 / 패키지: `services/libs/auth-infra` (DB 마이그레이션)

```
services/libs/auth-infra/src/main/resources/db/migration/
└── V5__make_grant_type_nullable.sql    — grant_type NOT NULL 제약 제거
```

##### V5__make_grant_type_nullable.sql (신규)
- **타입**: Flyway Migration Script
- **책임**: `service_client.grant_type` 컬럼의 `NOT NULL` 제약 제거. 기존 데이터는 영향 없음.
- **내용**:
  ```sql
  -- service_client.grant_type NOT NULL 제약 제거
  -- grantType optional 전환 (OAuth 재도입 대비 컬럼 보존)
  ALTER TABLE service_client
      ALTER COLUMN grant_type DROP NOT NULL;

  COMMENT ON COLUMN service_client.grant_type
      IS '그랜트 타입 (AUTHORIZATION_CODE | CLIENT_CREDENTIALS | NULL=client_credentials 디폴트 처리)';
  ```
- **주의사항**:
  - `api_key_hash`는 V4에서 이미 `NULL` 허용 → 변경 없음
  - `oauth2_registered_client` 테이블(SAS 표준)은 수정하지 않음
  - iCloud Drive 환경: 파일 저장 후 `V5__make_grant_type_nullable 2.sql` 충돌본 생성 여부 즉시 확인 (BUILD-1)
- **연관 todo**: `[DB-1] Flyway 마이그레이션 V5__make_grant_type_nullable.sql 작성`

---

### 호출 흐름

#### 변경 전 (grantType 필수 강제)

```
Client
  → POST /api/v1/admin/clients { grantType: null, clientName: "app-b" }
  → AdminClientController.registerClient
      → GrantType.fromString(null)
          → UnsupportedGrantTypeException("null") 발생
          → GlobalExceptionHandler.handleUnsupportedGrantType
          → 400 UNSUPPORTED_GRANT_TYPE 반환
```

grantType을 주더라도:
```
  → GrantType.fromString("client_credentials") → GrantType.CLIENT_CREDENTIALS
  → RegisterOAuthClientService.register(command)
      → validateCommand: grantType == null? No → 통과
                          clientName == null? No → 통과
      → else 분기: rawSecret 생성 → bcryptSecret 생성
          → sasClientRegistrar.registerClientCredentialsClient(clientId, name, bcrypt)
          → sasClientRegistrar.registerAuthorizationCodeClient (미호출)
      → serviceClientRepository.save(...)
      → 201 반환
```

#### 변경 후 (grantType optional)

**정상 경로 A: grantType 생략 (`{ "clientName": "app-b" }`)**

```
Client
  → POST /api/v1/admin/clients { clientName: "app-b" }
  → AdminClientController.registerClient
      → GrantType.fromString(null) → null 반환  ← 변경됨
      → RegisterOAuthClientCommand(grantType=null, clientName="app-b", ...)
      → RegisterOAuthClientService.register(command)
          → validateCommand: clientName != null → 통과
          → existsByClientName: false → 통과
          → resolved = CLIENT_CREDENTIALS  ← 디폴트 적용
          → [CLIENT_CREDENTIALS 분기] rawSecret 생성, bcryptSecret 생성 (apiKeyHash는 null)
          → sasClientRegistrar.registerClient(clientId, "app-b", bcryptSecret, emptySet)  ← 변경됨
              → SasClientRegistrarAdapter: bcryptHashedSecret != null
              → CLIENT_SECRET_BASIC + CLIENT_CREDENTIALS + scope("read") 빌드 후 SAS 저장
          → serviceClientRepository.save(ServiceClient.create(clientId, "app-b", CLIENT_CREDENTIALS, null))
      → 201 Created { clientId, clientSecret }
```

**정상 경로 B: grantType = "authorization_code"**

```
  → GrantType.fromString("authorization_code") → AUTHORIZATION_CODE
  → resolved = AUTHORIZATION_CODE
  → redirectUris 검증: not null and not empty → 통과
  → bcryptSecret = null
  → sasClientRegistrar.registerClient(clientId, name, null, redirectUris)
      → SasClientRegistrarAdapter: bcryptHashedSecret == null
      → NONE + AUTHORIZATION_CODE + PKCE + redirectUris 빌드 후 SAS 저장
  → serviceClientRepository.save(ServiceClient.create(clientId, name, AUTHORIZATION_CODE, null))
  → 201 Created { clientId } (clientSecret null → @JsonInclude NON_NULL으로 미포함)
```

**정상 경로 C: grantType = "client_credentials"**

```
  → GrantType.fromString("client_credentials") → CLIENT_CREDENTIALS
  → resolved = CLIENT_CREDENTIALS  (null 아님, 그대로 사용)
  → [CLIENT_CREDENTIALS 분기] 경로 A와 동일
  → 201 Created { clientId, clientSecret }
```

**예외 경로 1: grantType 비-null 알 수 없는 값 ("password")**

```
  → GrantType.fromString("password")
      → UnsupportedGrantTypeException("password") 발생
      → GlobalExceptionHandler.handleUnsupportedGrantType
      → 400 UNSUPPORTED_GRANT_TYPE  (기존 동작 유지)
```

**예외 경로 2: grantType = "authorization_code" + redirectUris 없음**

```
  → resolved = AUTHORIZATION_CODE
  → redirectUris == null || empty → RedirectUriRequiredException 발생
  → GlobalExceptionHandler.handleRedirectUriRequired
  → 400 REDIRECT_URI_REQUIRED  (기존 동작 유지)
```

**예외 경로 3: clientName null**

```
  → validateCommand: clientName == null → IllegalArgumentException 발생
  → GlobalExceptionHandler.handleIllegalArgument
  → 400 INVALID_ARGUMENT
  (단, @NotBlank 검증이 Controller 레이어에서 먼저 잡혀 400 VALIDATION_FAILED로 반환)
```

**예외 경로 4: clientName 중복**

```
  → existsByClientName: true → DuplicateClientNameException 발생
  → GlobalExceptionHandler.handleDuplicateClientName
  → 409 DUPLICATE_CLIENT_NAME  (기존 동작 유지)
```

---

### 컨벤션 준수 항목

- **네이밍**: 메서드명 `registerClient` (camelCase, `register` 접두사) — CONVENTION.md §1.3
- **의존성 주입**: `@RequiredArgsConstructor` 사용 (`RegisterOAuthClientService`, `SasClientRegistrarAdapter`) — CONVENTION.md §2.2
- **예외 처리**: 기존 `UnsupportedGrantTypeException`, `RedirectUriRequiredException` 정적 팩토리 없이 직접 생성자 호출 방식 유지 (기존 패턴 동일). `GlobalExceptionHandler` 변경 없음 — CONVENTION.md §3.1
- **불변성**: `ServiceClient` 필드 `private final` 유지. 컬렉션은 기존대로 처리 — CONVENTION.md §2.3
- **JSON 직렬화**: `@JsonInclude(NON_NULL)` — `RegisterClientResponse`에 이미 적용. `clientSecret` null 시 응답 JSON에서 제외됨 — CONVENTION.md §2.4
- **Javadoc**: 모든 변경된 `public` 클래스/메서드에 한국어 Javadoc 갱신. `@param`, `@return`, `@throws` 필수 — CONVENTION.md §4.1
- **테스트**: `@Nested` + `@DisplayName(한글)` + Given-When-Then + AssertJ — CONVENTION.md §5.1, §5.2
- **포맷팅**: 변경 후 `./gradlew format` (Spotless + Google Java Format 1.17.0) 실행 후 `./gradlew check` — CONVENTION.md §2.1, §6

---

### 테스트 영향 상세

#### RegisterOAuthClientServiceTest (변경)

**제거**:
- `ValidationTest.registerWithNullGrantType_throwsException()` — 새 동작(null → 정상 처리)과 충돌. 삭제.

**교체/추가** (`@Nested @DisplayName("grantType null 시 디폴트 처리")` 클래스 신설 권장):

1. `grantType이 null이면 CLIENT_CREDENTIALS로 처리되어 rawSecret이 반환된다`
   - command: `grantType=null, clientName="앱이름", redirectUris=null`
   - 기대: `result.clientSecret()` non-blank, `result.clientId()` non-blank

2. `grantType null + redirectUris 빈 Set → rawSecret 반환, SAS registerClient 호출됨`
   - command: `grantType=null, clientName="앱이름2", redirectUris=Set.of()`
   - verify: `sasClientRegistrar.registerClient(anyString(), eq("앱이름2"), argThat(s -> s.startsWith("{bcrypt}")), any())`

**SAS 포트 verify 갱신** (TEST-5):
- 기존: `then(sasClientRegistrar).should().registerAuthorizationCodeClient(...)` 또는 `registerClientCredentialsClient(...)`
- 신규: `then(sasClientRegistrar).should().registerClient(anyString(), anyString(), ...)` 로 교체
- `DuplicateClientTest.registerWithDuplicateClientName_throwsException`:
  - 기존 두 줄의 `never()` verify를 `registerClient` 단일 메서드 verify로 교체:
    ```
    then(sasClientRegistrar).should(never()).registerClient(any(), any(), any(), any());
    ```

#### AdminClientControllerTest (변경)

**추가** (`RegisterClientTest` 내부):
- `grantType 생략 시 201과 clientId + clientSecret 반환`
  - 요청: `{ "clientName": "app-b" }` (grantType 키 자체 없음)
  - mock: `registerOAuthClientService.register(any())` → `RegisterOAuthClientResult("cid", "secret", null)`
  - 검증: `status().isCreated()`, `jsonPath("$.clientId").value("cid")`, `jsonPath("$.clientSecret").value("secret")`

**유지**: `registerWithUnsupportedGrantType_returns400` — `grantType: "password"` 비-null 잘못된 값 → 400 여전히 유효.

#### AuthApiIntegrationTest (변경)

**추가** (`AdminClientRegistrationTest` 내부):
- `grantType 생략 등록 → clientId + clientSecret 반환, DB grant_type NULL 저장`
  ```
  POST /api/v1/admin/clients
  { "clientName": "no-grant-app" }
  → 201, clientId non-empty, clientSecret non-empty
  ```
  - DB 직접 검증(JDBC) 방법: 통합 테스트에 `@Autowired DataSource` 주입 후 `JdbcTemplate.queryForObject("SELECT grant_type FROM service_client WHERE client_name = ?", String.class, "no-grant-app")` → null 확인. 또는 `jsonPath("$.clientId")`만 검증하고 DB 검증은 별도 슬라이스 테스트로 위임.

**유지**: `register_authorization_code_without_redirect_uris` — `grantType: "authorization_code"` 명시 + redirectUris 없음 → 400 여전히 유효.

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
  - IMPL-1~10: GrantType, AdminClientController, Command, Service validateCommand, Service register, SasClientRegistrar 포트, SasClientRegistrarAdapter, Service 포트 호출, ServiceClient, JpaEntity
  - API-1, API-2: AdminClientController Swagger 문서
  - DB-1: V5 마이그레이션
  - TEST-1~5: 서비스 테스트, 컨트롤러 테스트, 통합 테스트
  - BUILD-1: spotless + iCloud 주의사항
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨
  - 도메인: `domain/` (GrantType, ServiceClient)
  - 포트: `application/port/out/` (SasClientRegistrar)
  - 서비스: `application/usecase/` (RegisterOAuthClientService)
  - 어댑터 in: `adapter/in/web/` (AdminClientController)
  - 어댑터 out persistence: `adapter/out/persistence/` (ServiceClientJpaEntity)
  - 어댑터 out SAS: `adapter/out/sas/` (SasClientRegistrarAdapter)
  - 인프라 마이그레이션: `auth-infra/db/migration/`
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상 A/B/C + 예외 1/2/3/4 모두 커버)

---

## 참고
- `docs/CONVENTION.md` — 네이밍, Lombok, 불변성, JSON, 예외, Javadoc, 테스트, 빌드 컨벤션 전체
- `docs/ARCHITECTURE.md` — 헥사고날 아키텍처, 모듈 의존성, 계층 설계
- `services/apis/auth-api/src/main/java/com/econo/auth/api/domain/GrantType.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/usecase/RegisterOAuthClientService.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/port/out/SasClientRegistrar.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/sas/SasClientRegistrarAdapter.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/domain/ServiceClient.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/persistence/ServiceClientJpaEntity.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`
- `services/libs/auth-infra/src/main/resources/db/migration/V4__create_service_client_and_route.sql`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/application/usecase/RegisterOAuthClientServiceTest.java`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java`
