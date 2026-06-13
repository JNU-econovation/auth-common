# disable-internal-api-key - todo

## 메타
- **작업명**: disable-internal-api-key
- **문서 타입**: todo
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - implementation-plan.md (미작성)
  - api-design-plan.md (미작성)

## 개요

`AdminClientController`의 모든 엔드포인트를 보호하던 `X-Internal-Api-Key` 인증 시스템을 제거하고
인증 모델을 "등록 public + redirect-uris CRUD는 clientSecret Basic Auth"로 전환한다 (ADR-0010).
`PasswordEncoder` 빈과 `RegisteredClientRepository` 빈은 이미 `SecurityConfig` 및 `RegisteredClientConfig`에
등록되어 있으므로 신규 빈 추가 없이 Basic Auth 검증 로직에 바로 주입 가능하다.
`grantType`/`GrantType` enum/`UnsupportedGrantTypeException` 등 refactor-client-registration(2ab06d0)에서
nullable 보존으로 결정된 항목은 이번 작업 범위에 포함하지 않는다.

---

## 본문

### API 작업

- [ ] `POST /api/v1/admin/clients` — `@RequestHeader("X-Internal-Api-Key")` 파라미터 및 `isValidApiKey()` 검증 분기·401 블록 제거 (public 전환)
- [ ] `GET /api/v1/admin/routes` — `@RequestHeader("X-Internal-Api-Key")` 파라미터 및 `isValidApiKey()` 검증 분기·401 블록 제거 (public 전환)
- [ ] `GET /api/v1/admin/clients/{clientId}` — `@RequestHeader("X-Internal-Api-Key")` 파라미터 제거, Basic Auth(`Authorization: Basic base64(clientId:clientSecret)`) 검증으로 교체. 헤더 누락/디코딩 실패/BCrypt 불일치 시 401 `INVALID_CLIENT_CREDENTIALS` + `WWW-Authenticate: Basic realm="admin"`. path `{clientId}` ↔ Basic Auth clientId 불일치 시 403 `FORBIDDEN_CLIENT_MISMATCH`.
- [ ] `POST /api/v1/admin/clients/{clientId}/redirect-uris` — 위와 동일한 Basic Auth 검증으로 교체
- [ ] `DELETE /api/v1/admin/clients/{clientId}/redirect-uris` — 위와 동일한 Basic Auth 검증으로 교체
- [ ] `PUT /api/v1/admin/clients/{clientId}/redirect-uris` — 위와 동일한 Basic Auth 검증으로 교체
- [ ] Swagger `@Tag` description에서 "X-Internal-Api-Key 헤더 인증 필요" 문구 제거 및 새 인증 모델(public 2개 / Basic Auth 4개) 설명으로 갱신
- [ ] Swagger 각 `@Operation` description에서 "인증: X-Internal-Api-Key" 문구 제거 및 해당 endpoint 인증 방식에 맞게 갱신
- [ ] Swagger `@ApiResponse(responseCode = "401")` 설명에서 "X-Internal-Api-Key 없거나 틀림" 문구 제거; Basic Auth 4개 endpoint에 401 `INVALID_CLIENT_CREDENTIALS` / 403 `FORBIDDEN_CLIENT_MISMATCH` 응답 스펙 추가. public 2개 endpoint에서 401 응답 항목 삭제.

### 구현 작업

- [ ] `AdminClientController` — `@Value("${AUTH_INTERNAL_API_KEY}") private String internalApiKey` 필드 제거
- [ ] `AdminClientController` — `private boolean isValidApiKey(String header)` 메서드 제거
- [ ] `AdminClientController` — `java.security.MessageDigest`, `java.nio.charset.StandardCharsets`, `org.springframework.beans.factory.annotation.Value` import 제거 (각각 유일 사용처 확인 후 제거)
- [ ] `AdminClientController` 클래스 Javadoc에서 "컨트롤러는 HTTP 매핑과 API 키 인증에만 집중한다" 문구를 "컨트롤러는 HTTP 매핑과 인증(public/Basic Auth)에만 집중한다"로 수정
- [ ] Basic Auth 검증 로직 신규 구현 — 구현 위치(private 메서드 vs 별도 `ClientBasicAuthValidator` 클래스)는 implementation-designer 결정. 처리 순서:
  1. `Authorization` 헤더 파싱 — `"Basic "` 접두사 제거 후 Base64 디코딩. 누락 또는 실패 시 401 `INVALID_CLIENT_CREDENTIALS`
  2. `clientId:clientSecret` 분리 — `:` 첫 번째 위치 기준 split. 파싱 실패 시 401
  3. `RegisteredClientRepository.findByClientId(clientId)` 조회 — 없으면 401
  4. `PasswordEncoder.matches(rawSecret, registeredClient.getClientSecret())` BCrypt 비교 — 불일치 시 401
  5. path `{clientId}` ↔ decoded clientId 일치 강제 — 불일치 시 403 `FORBIDDEN_CLIENT_MISMATCH`
  - 401 응답 시 `WWW-Authenticate: Basic realm="admin"` 헤더 추가
  - `PasswordEncoder` 빈 주입: `SecurityConfig`에 이미 `BCryptPasswordEncoder(12)`로 등록됨
  - `RegisteredClientRepository` 빈 주입: `RegisteredClientConfig`에 이미 `JdbcRegisteredClientRepository`로 등록됨
- [ ] `AdminClientController.ErrorResponse` — 기존 record 양식 유지. 신규 에러 코드 `INVALID_CLIENT_CREDENTIALS`, `FORBIDDEN_CLIENT_MISMATCH`는 기존 생성자(`errorCode`, `message`)로 생성 가능, 신규 record 불필요.
- [ ] `GatewayRoutingConfig.java` — `routes()` 빈 메서드 상단 또는 클래스 Javadoc에 주석 삽입: `/api/v1/admin/**` 라우트를 이 파일에 추가하지 말 것. admin endpoint는 내부망 전용이며, Gateway 외부 라우트에 노출 시 public 등록 endpoint 도배 공격 위험. ADR-0010 참조.

### DB 작업

- 해당 없음 (인증 채널 전환만 수행. `api_key_hash` 컬럼은 refactor-client-registration 결정에 따라 nullable 보존. 스키마 변경 없음.)

### 기타 작업

#### 설정 / 환경변수

- [ ] `services/apis/auth-api/src/test/resources/application-test.yml` — `AUTH_INTERNAL_API_KEY: valid-internal-key` 행 제거
- [ ] `services/apis/auth-api/src/main/resources/application.yml` — `AUTH_INTERNAL_API_KEY` 참조 없음(그레이더 확인 완료). 추가 변경 불필요.

#### 테스트 갱신 — `AdminClientControllerTest`

- [ ] 클래스 레벨 `@TestPropertySource(properties = "AUTH_INTERNAL_API_KEY=valid-internal-key")` 어노테이션 제거
- [ ] `@MockBean RegisteredClientRepository` 추가 — Basic Auth 검증 시 조회에 필요
- [ ] `RegisterClientTest` — 기존 모든 테스트에서 `.header("X-Internal-Api-Key", "valid-internal-key")` 제거 및 `@WithMockUser(roles = "ADMIN")` 제거 (public endpoint라 불필요)
- [ ] `RegisterClientTest.registerWithoutApiKey_returns401` 테스트 **삭제** (public 전환으로 의미 소멸)
- [ ] `RegisterClientTest.registerWithoutGrantType_returns201WithClientIdAndSecret` (`@Disabled`) — 헤더만 제거, `@Disabled` 유지
- [ ] `GetRoutesTest.getRoutes_withValidInternalApiKey_returns200` — 헤더 제거 후 테스트명을 `getRoutes_withoutAuth_returns200`으로 변경
- [ ] `GetRoutesTest.getRoutes_withoutApiKey_returns401` 테스트 **삭제** (public 전환)
- [ ] `GetRoutesTest.getRoutes_withInvalidApiKey_returns401` 테스트 **삭제** (public 전환)
- [ ] **신규 Nested class `GetClientBasicAuthTest` 추가** (4개 케이스):
  - 올바른 Basic Auth → 200
  - 잘못된 clientSecret → 401 `INVALID_CLIENT_CREDENTIALS`
  - path clientId ↔ Basic Auth clientId 불일치 → 403 `FORBIDDEN_CLIENT_MISMATCH`
  - `Authorization` 헤더 누락 → 401 `INVALID_CLIENT_CREDENTIALS`
- [ ] **신규 Nested class `AddRedirectUriBasicAuthTest` 추가** (위와 동일한 4케이스, `POST /clients/{clientId}/redirect-uris`)
- [ ] **신규 Nested class `RemoveRedirectUriBasicAuthTest` 추가** (위와 동일한 4케이스, `DELETE /clients/{clientId}/redirect-uris`)
- [ ] **신규 Nested class `ReplaceRedirectUrisBasicAuthTest` 추가** (위와 동일한 4케이스, `PUT /clients/{clientId}/redirect-uris`)
- [ ] 신규 테스트 전체: `@DisplayName` 한글, Given-When-Then, Base64 헬퍼 메서드 추출 (`private String basicAuthHeader(String id, String secret)`)

#### 테스트 갱신 — `AuthApiIntegrationTest`

- [ ] `DynamicPropertySource`에서 `registry.add("AUTH_INTERNAL_API_KEY", ...)` 행 제거
- [ ] `AdminClientRegistrationTest` 전체 — `.header("X-Internal-Api-Key", "test-internal-key")` 제거
- [ ] `AdminClientRegistrationTest.register_without_api_key_returns_401` 테스트 **삭제**
- [ ] `AdminClientRegistrationTest.register_wrong_api_key_returns_401` 테스트 **삭제**
- [ ] `RedirectUriManagementTest.add_redirect_uri` — 헤더 제거. 등록 후 응답에서 `clientSecret` 추출 → Basic Auth 헬퍼로 redirect-uris 호출 및 조회 케이스 재작성
- [ ] `RoutesTest.get_routes_with_valid_key` — 헤더 제거, 테스트명 갱신 (`get_routes_without_auth_returns200`)
- [ ] `RoutesTest.get_routes_without_key` 테스트 **삭제** (public 전환)
- [ ] **헬퍼 메서드 추가**: `private String buildBasicAuthHeader(String clientId, String rawSecret)` — `"Basic " + Base64(clientId + ":" + rawSecret)`
- [ ] **신규 통합 테스트 추가 — Basic Auth 검증** (기존 `RedirectUriManagementTest` 내부 또는 별도 Nested class):
  - `client_credentials` 등록 후 반환된 `clientSecret`으로 `GET /api/v1/admin/clients/{clientId}` → 200
  - 잘못된 secret으로 동일 요청 → 401 `INVALID_CLIENT_CREDENTIALS`
  - 다른 클라이언트의 clientId로 Basic Auth 구성하여 요청 → 403 `FORBIDDEN_CLIENT_MISMATCH`
  - `Authorization` 헤더 없이 redirect-uris POST → 401

#### 스킬 갱신

- [ ] `.claude/skills/register-service/SKILL.md` — "전제 조건 파악" 섹션에서 항목 2 "auth-api Internal API Key" 전체 제거
- [ ] `.claude/skills/register-service/SKILL.md` — Step 1 curl 예시에서 `-H "X-Internal-Api-Key: ${AUTH_INTERNAL_API_KEY}"` 줄 제거
- [ ] `.claude/skills/register-service/SKILL.md` — 완료 체크리스트의 `clientId`, `clientSecret` 저장 항목에 "(clientSecret은 등록 응답에서 1회만 노출 — 반드시 즉시 저장)" 문구 추가

#### 문서 갱신

- [ ] `docs/CLIENT_REGISTRATION.md`:
  - 도입부 "모든 Admin API는 X-Internal-Api-Key 헤더 인증 필수" 문구 제거
  - 각 등록 curl 예시에서 `-H "X-Internal-Api-Key: <KEY>"` 제거 (public)
  - redirectUri 관리 섹션의 curl 예시를 `Authorization: Basic base64(clientId:clientSecret)` 헤더로 갱신 (4개 endpoint 모두)
  - Basic Auth 흐름 설명 섹션 추가: 등록 응답의 `clientSecret`으로 Basic Auth를 구성하는 방법
  - "clientSecret은 등록 응답에서 단 1회만 노출, 분실 시 재등록 필요" 경고 추가
  - 에러 코드 표: `401 UNAUTHORIZED` 행 제거, `401 INVALID_CLIENT_CREDENTIALS` / `403 FORBIDDEN_CLIENT_MISMATCH` 행 추가
- [ ] `docs/FEATURES.md`:
  - "OAuth 클라이언트 관리 (Admin)" 섹션 도입부 `> 모든 요청에 X-Internal-Api-Key: <KEY> 헤더 필수` 제거
  - 클라이언트 등록 curl 예시에서 헤더 제거 (public)
  - redirectUri 관리 API 표 또는 설명에서 인증 방식을 "Basic Auth (clientId:clientSecret)"로 표기
  - 섹션 3 "새 서비스 연동하기 > Step 1" curl에서 `-H "X-Internal-Api-Key: <KEY>"` 제거
- [ ] `docs/DYNAMIC_ROUTING.md`:
  - "동적 라우팅 등록" curl 예시에서 `-H "X-Internal-Api-Key: <KEY>"` 제거
  - "라우트 목록 조회" curl 예시에서 `-H "X-Internal-Api-Key: <KEY>"` 제거
- [ ] `docs/INFRASTRUCTURE.md`:
  - 환경변수 표에 `AUTH_INTERNAL_API_KEY` 행이 존재하면 제거 (현재 표에 없음 — 작업 완료 후 재확인)

#### 빌드 검증

- [ ] `./gradlew build` 통과
- [ ] `grep -r "AUTH_INTERNAL_API_KEY" services/` → 0건
- [ ] `grep -r "X-Internal-Api-Key" services/` → 0건
- [ ] `grep -r "isValidApiKey" services/` → 0건
- [ ] `./gradlew spotlessApply` (Spotless 포맷팅) 통과

---

## 체크리스트

- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고

- `docs/adr/0010-client-secret-self-service-auth.md` — 이 작업의 근거 ADR
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` — 주요 변경 대상. `isValidApiKey()` 17줄(L267–271), 7개 endpoint에서 `@RequestHeader` 파라미터 및 분기 블록 위치 확인 완료.
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — `PasswordEncoder` 빈(`BCryptPasswordEncoder(12)`) 등록 위치. Basic Auth 검증에 재사용 가능.
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java` — `RegisteredClientRepository` 빈(`JdbcRegisteredClientRepository`) 등록 위치. `findByClientId()`로 `RegisteredClient.getClientSecret()` BCrypt 해시 조회.
- `services/apis/auth-api/src/test/resources/application-test.yml` — `AUTH_INTERNAL_API_KEY: valid-internal-key` 행 제거 대상 (9번 줄)
- `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java` — 갱신 대상. `@TestPropertySource` 34번 줄, `.header("X-Internal-Api-Key", ...)` 호출 10여 곳, 삭제 대상 테스트 3개 식별 완료.
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` — 갱신 대상. `AUTH_INTERNAL_API_KEY` DynamicPropertySource 62번 줄, `X-Internal-Api-Key` 헤더 호출 다수, 삭제 대상 테스트 3개 식별 완료.
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — admin 라우트 추가 금지 주석 삽입 대상
- `.claude/skills/register-service/SKILL.md` — 스킬 갱신 대상. Step 1 curl 39–48번 줄, 전제 조건 29번 줄, 완료 체크리스트 167번 줄.
- `docs/CLIENT_REGISTRATION.md`, `docs/FEATURES.md`, `docs/DYNAMIC_ROUTING.md` — 문서 갱신 대상
- `docs/INFRASTRUCTURE.md` — `AUTH_INTERNAL_API_KEY` 환경변수 행이 이미 없음 (확인 완료). 변경 불필요.
- `docs/CONVENTION.md` — 한국어 Javadoc, 영어 식별자, Given-When-Then, `@DisplayName` 한글 규칙
