# refactor-client-registration - todo

## 메타
- **작업명**: refactor-client-registration
- **문서 타입**: todo
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요
OAuth 2.0 인가서버(SAS) 코드가 제거된 `feat/member-auth` 브랜치 HEAD 상태에서,
`POST /api/v1/admin/clients` 엔드포인트가 여전히 `grantType`과 `redirectUris`를 사실상 필수로 강제하는 부정합을 해소한다.
`{ "clientName": "app-b" }` 만으로도 201 응답이 가능하도록 OAuth 필드를 완전히 optional로 전환하되,
향후 OAuth 재도입 시 그대로 활용할 수 있도록 필드·클래스·핸들러는 폐기하지 않고 보존한다.

---

## 본문

### API 작업

- [ ] **[API-1] `POST /api/v1/admin/clients` 요청 스펙 변경 — `grantType` optional 명시**
  - `RegisterClientRequest` 레코드에서 `grantType` 필드의 필수 제약을 제거하고 null 허용으로 Swagger 문서(`@Operation` description) 갱신
  - grantType 생략 시 동작("디폴트 `client_credentials`로 처리") 을 API 문서에 명시
  - `redirectUris`는 이미 optional이므로 변경 없음 (기존 "null → 빈 Set 정규화" 로직 유지)

- [ ] **[API-2] `POST /api/v1/admin/clients` 400 오류 코드 표 갱신**
  - Swagger `@ApiResponse(responseCode = "400")` description 표에서 `REDIRECT_URI_REQUIRED` 항목을 "authorization_code 타입이 명시된 경우에만 발생"으로 조건부 설명으로 수정
  - `UNSUPPORTED_GRANT_TYPE`은 "null이 아닌 알 수 없는 값일 때만 발생"으로 설명 보강

---

### 구현 작업

- [ ] **[IMPL-1] `GrantType.fromString(null)` null 허용으로 변경**
  - `GrantType.fromString(String value)` — null 입력 시 `UnsupportedGrantTypeException` 대신 `null`을 반환하도록 수정
  - 알 수 없는 비-null 값 입력 시에는 기존대로 `UnsupportedGrantTypeException` 유지
  - 변경 후 `GrantType` 메서드 시그니처: `@Nullable public static GrantType fromString(@Nullable String value)`
  - Javadoc 갱신: "null 입력 시 null 반환, 알 수 없는 값이면 UnsupportedGrantTypeException"

- [ ] **[IMPL-2] `AdminClientController.registerClient` — grantType null 허용 흐름 통합**
  - `GrantType grantType = GrantType.fromString(request.grantType())` 호출 결과가 null이어도 이후 서비스 호출까지 그대로 전달되도록 유지 (컨트롤러에 추가 분기 없음)
  - 기존에 컨트롤러 레벨에 있던 `if (grantType == AUTHORIZATION_CODE && redirectUris == null) throw REDIRECT_URI_REQUIRED` 분기가 없는지 확인 — 코드 탐색 결과 이미 서비스로 위임되어 있으므로 변경 없음, 확인만

- [ ] **[IMPL-3] `RegisterOAuthClientCommand` — `grantType` null 허용 선언**
  - `RegisterOAuthClientCommand` 레코드의 `grantType` 파라미터에 `@Nullable` 표기 추가 (Jakarta 또는 Lombok `@Nullable`, 프로젝트 컨벤션에 맞게)
  - Javadoc 갱신: "null이면 서비스에서 CLIENT_CREDENTIALS 디폴트 적용"

- [ ] **[IMPL-4] `RegisterOAuthClientService.validateCommand` — grantType null 허용으로 수정**
  - `validateCommand` 메서드에서 `grantType == null`이면 `IllegalArgumentException`을 던지는 조건 제거
  - clientName null 검사는 유지

- [ ] **[IMPL-5] `RegisterOAuthClientService.register` — grantType null 시 단일 흐름 통합**
  - `command.grantType()`이 null인 경우 `GrantType.CLIENT_CREDENTIALS`를 로컬 변수로 디폴트 적용하여 이후 로직을 단일 흐름으로 처리
  - 로직 요약: `GrantType resolved = command.grantType() != null ? command.grantType() : GrantType.CLIENT_CREDENTIALS;`
  - `resolved == AUTHORIZATION_CODE && redirectUris 비어있음`이면 `RedirectUriRequiredException` 유지
  - `resolved != AUTHORIZATION_CODE` (CLIENT_CREDENTIALS 또는 null→디폴트) 이면 rawSecret 생성 + apiKeyHash 생성 + BCrypt 경로 진입
  - `serviceClientRepository.save(ServiceClient.create(...))` 호출 시 `resolved` 값을 전달 (null이 아닌 디폴트 값)

- [ ] **[IMPL-6] `SasClientRegistrar` 포트 — 단일 메서드 `registerClient`로 통합**
  - 기존 두 메서드(`registerAuthorizationCodeClient`, `registerClientCredentialsClient`)를 대체하는 단일 메서드 추가:
    ```
    void registerClient(
        String clientId,
        String clientName,
        @Nullable String bcryptHashedSecret,
        Set<String> redirectUris
    );
    ```
  - 기존 두 메서드는 `@Deprecated` 표기 후 default 메서드로 위임 구현을 남기거나, 구현체와 함께 전환 — implementation-designer가 판단
  - 포트 Javadoc: "bcryptHashedSecret이 null이면 Authorization Code 공개 클라이언트, non-null이면 Client Credentials 시크릿 클라이언트로 등록"

- [ ] **[IMPL-7] `SasClientRegistrarAdapter` — 단일 `registerClient` 메서드 구현**
  - `registerClient(clientId, clientName, bcryptHashedSecret, redirectUris)` 메서드 구현
  - `bcryptHashedSecret`이 null → `ClientAuthenticationMethod.NONE` + `AUTHORIZATION_CODE` + redirectUris 추가 (기존 `registerAuthorizationCodeClient` 로직)
  - `bcryptHashedSecret`이 non-null → `CLIENT_SECRET_BASIC` + `CLIENT_CREDENTIALS` + `clientSecret(bcryptHashedSecret)` (기존 `registerClientCredentialsClient` 로직)
  - `redirectUris`가 빈 Set이면 builder에 `redirectUri(...)` 호출 없음 (SAS builder 자체 검증 없으므로 통과)
  - `authorizationGrantType()`은 두 분기 모두 최소 1개 이상 부여되므로 SAS 빌더 검증 통과 확인

- [ ] **[IMPL-8] `RegisterOAuthClientService.register` — 새 포트 메서드 호출로 교체**
  - 기존 `sasClientRegistrar.registerAuthorizationCodeClient(...)` / `registerClientCredentialsClient(...)` 두 분기 호출을 `sasClientRegistrar.registerClient(clientId, clientName, bcryptHashedSecret, redirectUris)` 단일 호출로 교체
  - `bcryptHashedSecret` 은 CLIENT_CREDENTIALS 분기에서만 생성하여 전달, AUTHORIZATION_CODE 분기에선 null 전달

- [ ] **[IMPL-9] `ServiceClient` 도메인 — `grantType` nullable 명시**
  - `ServiceClient.grantType` 필드 및 `create(...)` 팩토리 파라미터에 `@Nullable` 표기 추가
  - `apiKeyHash`는 이미 nullable (코드 확인 완료), 표기만 명시적으로 추가

- [ ] **[IMPL-10] `ServiceClientJpaEntity` — `grantType` nullable JPA 매핑 수정**
  - `@Column(name = "grant_type", nullable = false, ...)` → `nullable = true` 로 변경
  - `from(ServiceClient)` 변환 메서드에서 null grantType이 전달될 경우 그대로 null로 저장하는지 확인 (추가 로직 불필요)

---

### DB 작업

- [ ] **[DB-1] Flyway 마이그레이션 `V5__make_grant_type_nullable.sql` 작성**
  - 위치: `services/libs/auth-infra/src/main/resources/db/migration/V5__make_grant_type_nullable.sql`
  - 내용:
    ```sql
    -- service_client.grant_type NOT NULL 제약 제거
    ALTER TABLE service_client
        ALTER COLUMN grant_type DROP NOT NULL;

    COMMENT ON COLUMN service_client.grant_type
        IS '그랜트 타입 (AUTHORIZATION_CODE | CLIENT_CREDENTIALS | NULL=디폴트 처리)';
    ```
  - `api_key_hash`는 V4에서 이미 `NULL`이므로 변경 없음 (확인 완료)
  - `oauth2_registered_client` 테이블(SAS 표준)은 건드리지 않음

---

### 기타 작업

- [ ] **[TEST-1] `RegisterOAuthClientServiceTest` — 기존 "grantType null이면 IllegalArgumentException" 테스트 제거**
  - `ValidationTest.registerWithNullGrantType_throwsException()` 테스트가 새 동작(null 허용)과 충돌 → 삭제 또는 동작 변경("grantType null → CLIENT_CREDENTIALS 디폴트 적용, 정상 등록")으로 교체
  - 대체 테스트 추가: `grantType이 null이면 CLIENT_CREDENTIALS로 처리되어 rawSecret이 반환된다`

- [ ] **[TEST-2] `RegisterOAuthClientServiceTest` — "grantType 없는 등록" 시나리오 신규 추가**
  - 케이스 1: `grantType=null, redirectUris=null` → rawSecret 반환, SAS `registerClient` 호출됨
  - 케이스 2: `grantType=null, redirectUris` 비어있음 → 동일 (AUTHORIZATION_CODE 분기 미진입)

- [ ] **[TEST-3] `AdminClientControllerTest` — "grantType 없는 등록" 웹 레이어 테스트 추가**
  - 요청: `{ "clientName": "app-b" }` (grantType 생략)
  - 기대: 201 Created + `clientId` 반환, `clientSecret` 포함 (`@JsonInclude(NON_NULL)` 조건)
  - 기존 `registerWithUnsupportedGrantType_returns400` 테스트는 grantType이 비-null 잘못된 값("password")이므로 그대로 유지

- [ ] **[TEST-4] `AuthApiIntegrationTest` — "grantType 없는 등록" 통합 테스트 추가**
  - 기존 `AdminClientRegistrationTest` 네스트 클래스 안에 케이스 추가:
    - `{ "clientName": "no-grant-app" }` → 201, `clientId` + `clientSecret` 반환
    - DB에서 `service_client.grant_type` 컬럼이 NULL로 저장되었는지 확인 (JDBC 직접 조회 또는 조회 API 활용)
  - 기존 `register_authorization_code_without_redirect_uris` 테스트 — grantType이 명시된 상태에서 redirectUris 누락 → 400 여전히 유효, 유지

- [ ] **[TEST-5] `RegisterOAuthClientServiceTest` — SAS 포트 통합 호출 검증 갱신**
  - 기존 테스트 중 `registerAuthorizationCodeClient`, `registerClientCredentialsClient` 개별 메서드를 verify하는 테스트들을 새 `registerClient` 단일 메서드 호출 verify로 교체
  - Mock 설정도 `sasClientRegistrar.registerClient(...)` 기준으로 변경

- [ ] **[BUILD-1] `./gradlew build` 전체 통과 확인**
  - spotless 포매팅(탭 들여쓰기) 적용: `./gradlew spotlessApply` 후 빌드
  - iCloud Drive 중복 파일 함정 주의 — 마이그레이션 SQL 파일 추가 후 `V5__make_grant_type_nullable 2.sql` 등 충돌본 미생성 여부 확인, 생겨났다면 즉시 `rm` 처리

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고
- `services/apis/auth-api/src/main/java/com/econo/auth/api/domain/GrantType.java` — fromString null 처리 현황 (현재 null → 예외)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/usecase/RegisterOAuthClientService.java` — validateCommand grantType null 검사 위치
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/sas/SasClientRegistrarAdapter.java` — 두 메서드 분리 현황
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/port/out/SasClientRegistrar.java` — 포트 인터페이스
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/persistence/ServiceClientJpaEntity.java` — `grant_type` NOT NULL 매핑 현황
- `services/libs/auth-infra/src/main/resources/db/migration/V4__create_service_client_and_route.sql` — `grant_type VARCHAR(30) NOT NULL` 원본
- `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/application/usecase/RegisterOAuthClientServiceTest.java`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java`
