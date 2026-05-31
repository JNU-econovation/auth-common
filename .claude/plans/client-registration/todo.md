# client-registration - todo

## 메타
- **작업명**: client-registration
- **문서 타입**: todo
- **작성일**: 2026-05-25
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

Spring Authorization Server(SAS 1.x)의 OIDC Dynamic Client Registration(DCR) 기능을 활성화한다.
RFC 7591(`POST /connect/register`) 및 RFC 7592(GET/PUT/DELETE 관리 엔드포인트)를 SAS 내장 기능으로 켜며,
엔드포인트 보호에 사용할 **registrar client**(confidential, client_credentials, scope=`client.create`+`client.read`)를
기존 시드 패턴(`RegisteredClientConfig`)으로 추가 등록한다. 엔드포인트 자체를 직접 구현하지 않고
SAS 설정(`oidc.clientRegistrationEndpoint(...)`)을 통해 활성화하는 것이 핵심이다.

---

## 본문

### API 작업

- [ ] `POST /connect/register` 엔드포인트 활성화 확인
  - SAS가 자동 노출하는 경로. `clientRegistrationEndpoint(Customizer.withDefaults())` 활성화 후
    `/.well-known/openid-configuration`의 `registration_endpoint` 필드에 해당 URL이 나타나는지 검증.
  - 요청 헤더: `Authorization: Bearer <client.create 스코프 access_token>`
  - 요청 바디: RFC 7591 client metadata JSON (`redirect_uris`, `grant_types`, `response_types`,
    `token_endpoint_auth_method`, `scope`, `client_name` 등)
  - 응답: `201 Created` + client metadata + `client_id`, `client_secret`(confidential 시),
    `registration_access_token`, `registration_client_uri`

- [ ] RFC 7592 관리 엔드포인트(`GET /connect/register/{clientId}`, `PUT`, `DELETE`) 활성화 확인
  - SAS 1.x에서 RFC 7592가 실제로 지원되는지, 지원 범위(READ 전용인지 CRUD 전체인지) 공식 문서 및
    SAS 소스코드(`OidcClientRegistrationEndpointFilter`)로 확인 후 todo 갱신 필요. **(플래그: SAS 1.x RFC 7592 지원 범위 현행 문서 확인 필수)**
  - 요청 헤더: `Authorization: Bearer <registration_access_token>`

- [ ] `POST /connect/register` 및 관리 엔드포인트에 대한 CORS 정책 결정·적용
  - 현재 `AuthorizationServerConfig.corsConfigurationSource()`에 `/connect/register` 경로가 없음.
    운영자 백오피스(또는 서버 간 호출)만 허용할지, 모든 오리진에 열지 결정 후 추가.

### 구현 작업

- [ ] `AuthorizationServerConfig` — `oidc.clientRegistrationEndpoint(...)` 활성화
  - 현재 `oidc(Customizer.withDefaults())`를 `oidc(oidc -> oidc.clientRegistrationEndpoint(Customizer.withDefaults()))`
    형태로 교체(또는 체인 추가). 기존 `withDefaults()` 동작을 유지하면서 DCR만 추가로 켜는 방식 확인.
  - **(플래그: SAS 1.x `clientRegistrationEndpoint` API 정확한 시그니처 및 필요한 빈(TokenGenerator 등) 공식 문서 확인 필수)**

- [ ] `RegisteredClientConfig` — registrar client 시드 추가
  - `REGISTRAR_CLIENT_ID` / `REGISTRAR_CLIENT_SECRET` 환경변수를 `@Value`로 주입.
  - 기존 `firstPartyPublicClient()` 패턴 그대로 적용. 고정 UUID(예: `00000000-0000-0000-0000-000000000002`) 사용.
  - grant=`client_credentials`, auth_method=`client_secret_basic`,
    scope=`client.create` + `client.read`.
  - `requireAuthorizationConsent(false)` (운영자 client이므로 consent 불필요).
  - `requireProofKey(false)` (client_credentials는 PKCE 없음).
  - `registeredClientRepository()` 빈 내 멱등 seed 로직(`findByClientId` 후 없으면 `save`)에 추가.

- [ ] registrar client access token으로 `/connect/register`에 접근 가능한지 인가 검증
  - SAS가 `client.create` 스코프를 자동으로 요구하는지, 아니면 `clientRegistrationEndpoint`의
    커스텀 `authorizationRuleCustomizer`로 직접 스코프 보호 규칙을 등록해야 하는지 확인.
  - **(플래그: SAS 1.x clientRegistrationEndpoint 스코프 인가 규칙 기본값 공식 문서 확인 필수)**

- [ ] DCR로 등록된 client의 `requireAuthorizationConsent` 기본값 설정
  - SAS DCR이 등록하는 client의 `ClientSettings` 기본값에 `requireAuthorizationConsent(true)`가
    포함되도록 `clientRegistrationEndpoint` 커스터마이저 또는 SAS 제공 hook 확인.
  - **(플래그: SAS 1.x DCR 등록 시 ClientSettings 기본값 주입 방법 공식 문서 확인 필수)**

- [ ] `AuthorizationServerConfig.corsConfigurationSource()` — `/connect/register` CORS 항목 추가
  - API 작업에서 결정된 정책을 `UrlBasedCorsConfigurationSource`에 등록.

- [ ] `GlobalExceptionHandler` — DCR 관련 오류 응답 처리 확인
  - SAS가 반환하는 DCR 오류(RFC 7591 `invalid_redirect_uri` 등)가 기존 `GlobalExceptionHandler`와
    충돌 없이 RFC 형식으로 반환되는지 통합 테스트로 확인.

- [ ] 통합 테스트 추가 — DCR E2E 흐름 검증
  - 위치: `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/`
  - 클래스명: `DcrIntegrationTest`
  - 시나리오:
    1. registrar client로 `POST /oauth2/token` (client_credentials) → access_token 수신
    2. access_token Bearer로 `POST /connect/register` (confidential client 메타데이터) → 201 + client_id + registration_access_token
    3. registration_access_token으로 `GET /connect/register/{clientId}` → 200 (RFC 7592 지원 시)
    4. `client.create` 스코프 없는 토큰으로 `POST /connect/register` → 403 확인
  - `@SpringBootTest` + MockMvc + Testcontainers PostgreSQL 사용 (기존 `SasAuthorizationServerIntegrationTest` 패턴 참조)

### DB 작업

- [ ] SAS 1.x에서 `registration_access_token`의 저장 위치 확인
  - SAS DCR이 발급한 `registration_access_token`이 기존 `oauth2_authorization` 테이블에
    access_token_value로 저장되는지, 별도 컬럼/테이블이 필요한지 SAS 소스코드
    (`JdbcOAuth2AuthorizationService`, `OidcClientRegistrationEndpointFilter`) 기준 확인.
  - 신규 테이블이 필요 없다면 이 항목은 완료로 처리. 필요하다면 `V4__...sql` 마이그레이션 추가.
  - **(플래그: SAS 1.x registration_access_token 영속화 구조 소스코드·공식 문서 확인 필수)**

- [ ] (조건부) `V4__...sql` — DCR 관련 신규 테이블/컬럼 마이그레이션
  - 위 항목 확인 결과 스키마 변경이 필요한 경우에만 작성.
  - 파일 위치: `services/libs/auth-infra/src/main/resources/db/migration/V4__...sql`
  - 기존 V2 SAS DDL 컨벤션(PostgreSQL 방언, COMMENT ON 포함) 준수.

### 기타 작업

- [ ] 환경변수 추가 — `REGISTRAR_CLIENT_ID`, `REGISTRAR_CLIENT_SECRET`
  - `docs/INFRASTRUCTURE.md` 환경변수 표에 두 항목 추가 (사용 서버: `auth-api`, 필수: Y).
  - `application.yml`에 `${REGISTRAR_CLIENT_ID:registrar}`, `${REGISTRAR_CLIENT_SECRET}` 바인딩 추가.
  - `.env.example`(또는 `application-local.yml`) 로컬 개발용 기본값 추가.

- [ ] `docs/INFRASTRUCTURE.md` 갱신
  - 인프라 구성 요소 표에 `OIDC Dynamic Client Registration (DCR)` 항목 추가.
  - Flyway 버전 현황 표에 V4 행 추가 (DB 작업 결과에 따라 조건부).

- [ ] `docs/ARCHITECTURE.md` 갱신
  - auth-api 역할 설명에 `POST /connect/register` (OIDC DCR) 언급 추가.

- [ ] consent 화면 UI 의존성 명시 (이번 작업 범위 외)
  - DCR로 등록된 3rd-party client는 `requireAuthorizationConsent(true)`이므로,
    `/oauth2/authorize` 흐름에서 동의 화면이 필요하다.
  - 동의 화면 UI는 외부 프런트엔드 담당이며 이번 백엔드 작업 범위 밖임을
    `docs/ARCHITECTURE.md` 또는 별도 ADR에 명시하고 후속 작업으로 트래킹.

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthorizationServerConfig.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/OAuth2AuthorizationServiceConfig.java`
- `services/libs/auth-infra/src/main/resources/db/migration/V2__create_sas_tables.sql`
- `docs/ARCHITECTURE.md`
- `docs/CONVENTION.md`
- `docs/INFRASTRUCTURE.md`
- SAS 1.x 공식 문서: https://docs.spring.io/spring-authorization-server/reference/guides/how-to-dynamic-client-registration.html
- RFC 7591 (OAuth 2.0 Dynamic Client Registration): https://datatracker.ietf.org/doc/html/rfc7591
- RFC 7592 (OAuth 2.0 Dynamic Client Registration Management): https://datatracker.ietf.org/doc/html/rfc7592
