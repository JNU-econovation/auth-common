# client-self-registration-and-app-redirect - todo

## 메타
- **작업명**: client-self-registration-and-app-redirect
- **문서 타입**: todo
- **작성일**: 2026-06-07
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

두 기능을 함께 구현한다.

**기능 A — SSO 클라이언트 셀프 등록**: 인증된 에코노 회원 누구나 자기 서비스 앱을 SSO 클라이언트로 직접 등록할 수 있게 한다. 1인 최대 5개 제한, clientId + clientSecret 1회 발급, 소유자(memberId) 개념 도입이 핵심이다. 현재 `AdminClientController`의 등록 엔드포인트는 ADMIN만 허용하고 secret 발급도 없으므로 신규 경로와 로직이 필요하다.

**기능 B — APP 로그인 리다이렉트 보강**: 현재 APP 로그인 응답(200 + body)에 `redirectUrl` 필드가 없어 앱이 로그인 후 이동 목적지를 알 수 없다. `LoginRedirectResolver`를 APP 분기에도 적용하여 body에 `redirectUrl`을 추가한다. WEB 동작(쿠키 + 302)은 기존 그대로 유지한다.

---

## 본문

### API 작업

#### [A] 셀프 등록 엔드포인트 신설
- [ ] **[A-API-1]** 셀프 등록 전용 엔드포인트 경로 및 인증 방식 확정
  - 현재 `POST /api/v1/admin/clients`는 ADMIN 전용. 셀프 등록은 별도 경로(`POST /api/v1/clients` 등)를 신설하거나 기존 경로의 권한을 완화하는 방향 중 선택이 필요함 (api-designer 결정 사항)
  - 인증: Gateway 주입 `X-User-Passport` 헤더의 `memberId` 추출. 인증된 회원이면 누구나 허용 (ADMIN 불필요)
  - 요청 바디: `{ clientName, redirectUris }`
  - 응답: `201 Created` + `{ clientId, clientSecret }` — clientSecret은 이 응답에서만 1회 노출

- [ ] **[A-API-2]** 셀프 등록 에러 응답 코드 정의
  - `CLIENT_LIMIT_EXCEEDED` (4xx): memberId당 클라이언트 5개 초과 시
  - `REDIRECT_URI_REQUIRED` (400): redirectUris 누락 시 (기존 재사용)
  - `DUPLICATE_CLIENT_NAME` (409): clientName 중복 시 (기존 재사용)
  - `VALIDATION_FAILED` (400): clientName 빈 문자열 (기존 재사용)
  - HTTP 상태 코드(422 vs 429 vs 400) 확정은 api-designer 결정 사항

- [ ] **[A-API-3]** 기존 `AdminClientController` 등록 엔드포인트(`POST /api/v1/admin/clients`) 처리 방향 결정
  - 셀프 등록 신설 후 기존 ADMIN 전용 엔드포인트를 유지(ADMIN도 셀프 등록 경유)할지, 폐기할지, 공존할지 결정

#### [B] APP 로그인 응답 필드 추가
- [ ] **[B-API-1]** `POST /api/v1/auth/login` — APP 분기 응답 스펙 변경
  - `Client-Type: APP` 요청에 대한 200 응답 body에 `redirectUrl` 필드 추가
  - 기존: `{ accessToken, accessExpiredTime, refreshToken }`
  - 변경: `{ accessToken, accessExpiredTime, refreshToken, redirectUrl }` (`@JsonInclude(NON_NULL)` 유지, WEB 분기에는 미포함)
  - `redirectUrl` 결정 로직: `LoginRedirectResolver.resolve(clientId, defaultUrl)` — clientId 누락·미등록·오류 시 default-url

---

### 구현 작업

#### [A] 셀프 등록 서비스/도메인

- [ ] **[A-IMPL-1]** `ServiceClient` 도메인에 소유자(`ownerId: Long`) 필드 추가
  - `ServiceClient.create(...)` 팩토리 메서드 시그니처 변경 — `ownerId` 파라미터 추가
  - 기존 `AdminClientController`·`RegisterOAuthClientService`·테스트가 `ownerId` 없이 호출하는 부분 일괄 수정

- [ ] **[A-IMPL-2]** `ServiceClientJpaEntity`에 `owner_id` 컬럼 매핑 추가
  - `@Column(name = "owner_id", nullable = true)` — ADMIN 경로로 등록된 기존 레코드는 null 허용
  - `from(ServiceClient)` 및 `toDomain()` 변환 메서드 수정

- [ ] **[A-IMPL-3]** `ServiceClientRepository` 포트에 소유자별 카운트 쿼리 추가
  - `countByOwnerId(Long ownerId): long` 메서드 추가

- [ ] **[A-IMPL-4]** `ServiceClientJpaRepository`에 소유자별 카운트 쿼리 구현
  - `long countByOwnerId(Long ownerId)` Spring Data JPA 메서드 추가

- [ ] **[A-IMPL-5]** `ServiceClientRepositoryAdapter`에 `countByOwnerId` 위임 구현

- [ ] **[A-IMPL-6]** `RegisterOAuthClientService` — 셀프 등록 커맨드/결과 및 로직 추가
  - 신규 `SelfRegisterOAuthClientCommand(String clientName, Set<String> redirectUris, Long ownerId)` record 추가 (또는 기존 커맨드에 ownerId 추가 — 방향은 implementation-designer 결정)
  - 신규 `SelfRegisterOAuthClientResult(String clientId, String clientSecret)` record 추가 (clientSecret 1회 노출)
  - 5개 초과 검증: `serviceClientRepository.countByOwnerId(ownerId) >= 5` 이면 신규 예외 throw
  - clientSecret 생성 로직: `UUID.randomUUID().toString()` + BCrypt 해시 저장. 평문 secret은 결과에만 포함
  - SAS 등록: `SasClientRegistrar` 포트 — 기존 `registerAuthorizationCodeClient`는 `ClientAuthenticationMethod.NONE`(public PKCE). clientSecret 공존 방식 결정 필요 (implementation-designer 정리)
    - 옵션 1: `CLIENT_SECRET_BASIC` + `NONE` 동시 등록
    - 옵션 2: 셀프 등록 클라이언트는 `NONE` 유지, secret은 redirect-uri 관리 인증에만 사용
    - 옵션 3: `SasClientRegistrar` 포트에 신규 메서드 `registerAuthorizationCodeClientWithSecret` 추가
  - `serviceClientRepository.save`에 ownerId 포함 `ServiceClient` 전달

- [ ] **[A-IMPL-7]** `ClientLimitExceededException` 신규 예외 클래스 추가 (`service-client` 모듈 `exception` 패키지)
  - `@ResponseStatus` 적용 또는 `GlobalExceptionHandler`에 매핑 — 에러 코드 `CLIENT_LIMIT_EXCEEDED`

- [ ] **[A-IMPL-8]** `GlobalExceptionHandler`에 `ClientLimitExceededException` 핸들러 등록 (`auth-api` 모듈)

- [ ] **[A-IMPL-9]** 셀프 등록 인바운드 웹 어댑터 신설 또는 `AdminClientController` 수정
  - `X-User-Passport` 헤더에서 `memberId` 파싱 — ADMIN 역할 검사 없이 인증 여부(memberId 비-null)만 확인
  - `RegisterClientResponse`에 `clientSecret` 필드 추가 (또는 신규 `SelfRegisterClientResponse(clientId, clientSecret)`)
  - api-designer가 결정한 경로/컨트롤러 분리 방향에 따라 신규 컨트롤러(`ClientController`) 또는 기존 확장

- [ ] **[A-IMPL-10]** `SasClientRegistrarAdapter`에 secret 공존 방식 구현
  - `SasClientRegistrar` 포트 구현체 변경 또는 신규 메서드 구현 (A-IMPL-6 설계 방향에 따라)

#### [B] APP 로그인 응답 redirectUrl 추가

- [ ] **[B-IMPL-1]** `LoginResponse`에 `redirectUrl` 필드 추가
  - `@JsonInclude(NON_NULL)` 이미 적용되어 있으므로 null이면 미직렬화
  - `app(String accessToken, long accessExpiredTime, String refreshToken, String redirectUrl)` 팩토리 메서드 추가 또는 기존 메서드 시그니처 변경

- [ ] **[B-IMPL-2]** `JsonLoginAuthenticationFilter` — APP 분기에 `LoginRedirectResolver` 호출 추가
  - `successfulAuthentication` 내 `isApp == true` 분기에서 `loginRedirectResolver.resolve(clientId, defaultRedirectUrl)` 호출
  - 결과를 `LoginResponse.app(...)` 생성 시 `redirectUrl` 인자로 전달
  - WEB 분기(쿠키 + 302) 변경 없음

---

### DB 작업

- [ ] **[A-DB-1]** `service_client` 테이블에 `owner_id` 컬럼 추가 — Flyway 마이그레이션 `V7__add_owner_id_to_service_client.sql`
  ```sql
  ALTER TABLE service_client
      ADD COLUMN owner_id BIGINT NULL;
  COMMENT ON COLUMN service_client.owner_id
      IS '클라이언트 소유자 회원 ID (셀프 등록 시 설정, ADMIN 등록 시 NULL)';
  ```
  - 기존 레코드는 NULL 허용 (nullable) — 마이그레이션 무중단 조건 충족
  - 인덱스 추가: `CREATE INDEX idx_service_client_owner_id ON service_client(owner_id);` — countByOwnerId 성능

- [ ] **[A-DB-2]** `service_client.client_secret_hash` 컬럼 추가 고려 여부 결정 (db-designer 검토)
  - clientSecret을 `service_client` 테이블에 직접 저장할지 vs SAS `oauth2_registered_client.client_secret`에만 저장할지
  - SAS `oauth2_registered_client` 테이블의 `client_secret` 컬럼이 이미 존재함 (V2 마이그레이션). 중복 저장 여부를 db-designer가 확정하고 필요 시 마이그레이션 추가

- [ ] **[B-DB-1]** 해당 없음 — 기능 B는 DB 변경 없음

---

### 기타 작업

#### 테스트

- [ ] **[A-TEST-1]** `RegisterOAuthClientServiceTest` — 셀프 등록 시나리오 단위 테스트 추가 (`Mockito`)
  - 정상 등록 (5개 미만, clientId + clientSecret 반환)
  - 5개 초과 시 `ClientLimitExceededException` throw
  - `ownerId`별 카운트 격리 확인 (다른 회원 카운트에 영향 없음)
  - clientSecret이 BCrypt 해시로 저장되고 평문이 결과에 포함되는지 확인

- [ ] **[A-TEST-2]** 셀프 등록 컨트롤러 `@WebMvcTest` 테스트 추가
  - 인증된 회원(임의 역할)으로 등록 성공 → 201 + `clientId` + `clientSecret`
  - `X-User-Passport` 헤더 없이 요청 시 401/403
  - `CLIENT_LIMIT_EXCEEDED` 에러 응답 검증
  - `REDIRECT_URI_REQUIRED`, `DUPLICATE_CLIENT_NAME` 에러 응답 검증

- [ ] **[A-TEST-3]** `AdminClientControllerTest` — 기존 ADMIN 전용 등록 테스트 수정
  - api-designer가 결정한 경로/권한 방향에 따라 기존 테스트 시나리오 조정 (403 시나리오 변경 가능)

- [ ] **[B-TEST-1]** `AuthApiIntegrationTest` — APP 로그인 응답에 `redirectUrl` 포함 검증 추가
  - clientId 있는 APP 로그인: body에 `redirectUrl` 포함
  - clientId 없는 APP 로그인: body에 `redirectUrl = default-url` 포함

- [ ] **[B-TEST-2]** `JsonLoginAuthenticationFilter` APP 분기 단위/통합 테스트 보완
  - `redirectUrl`이 `LoginRedirectResolver.resolve` 결과와 일치하는지 확인
  - WEB 분기에 `redirectUrl`이 미포함됨을 확인

#### 문서 / ADR

- [ ] **[A-DOC-1]** ADR-0013 신규 작성 — 셀프 등록 모델 채택 기록
  - ADR-0010("등록 public + Basic Auth")을 supersede하는 내용 포함
  - 소유자 개념 도입, 5개 제한 정책, clientSecret + PKCE 공존 방식 근거 기록

- [ ] **[A-DOC-2]** `docs/CLIENT_REGISTRATION.md` 재작성
  - 현재 문서는 `/api/v1/clients` public + Basic Auth 모델로 드리프트되어 실제 코드(`/api/v1/admin/clients` ADMIN 전용)와 불일치
  - 셀프 등록 신규 엔드포인트, 5개 제한, clientSecret 1회 발급 흐름 반영
  - 에러 코드 표 업데이트 (`CLIENT_LIMIT_EXCEEDED` 추가)

- [ ] **[A-DOC-3]** `docs/ARCHITECTURE.md` 에러 코드 체계 표 업데이트
  - `service-client` 도메인 에러 코드 표에 `CLIENT_LIMIT_EXCEEDED` 행 추가

- [ ] **[B-DOC-1]** `docs/ARCHITECTURE.md` 인증 흐름 A(APP 분기) 설명 업데이트
  - APP 응답 body 예시에 `redirectUrl` 필드 추가 반영

#### 설정 / 의존성

- [ ] **[A-CONFIG-1]** 해당 없음 — 셀프 등록에 새 환경변수 불필요 (BCrypt cost 기존 12 재사용, 5개 제한은 하드코딩 또는 설정 프로퍼티화 여부는 implementation-designer 결정)

- [ ] **[B-CONFIG-1]** 해당 없음 — 기능 B는 설정 변경 없음 (기존 `auth.redirect.default-url` 재사용)

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고

### 탐지한 프로젝트 문서
- `docs/ARCHITECTURE.md` — 헥사고날 구조, 에러 코드 체계, 인증 흐름 A/B 상세
- `docs/CONVENTION.md` — 네이밍 규칙, 테스트 컨벤션, 빌드 방법
- `docs/CLIENT_REGISTRATION.md` — 현재 문서 (실제 코드와 드리프트 있음, 재작성 대상)
- `docs/adr/0010-client-secret-self-service-auth.md` — 이번 셀프 등록 모델이 supersede 대상
- `docs/adr/0012-backend-decided-login-redirect.md` — 기능 B의 ADR (경로 A WEB/APP 분기 근거)

### 관련 소스 파일
- `services/apis/auth-api/.../adapter/in/web/AdminClientController.java` — 현재 ADMIN 전용 등록 엔드포인트
- `services/apis/auth-api/.../filter/JsonLoginAuthenticationFilter.java` — APP 분기 (B-IMPL-2 수정 대상)
- `services/apis/auth-api/.../adapter/in/web/LoginResponse.java` — redirectUrl 추가 대상 (B-IMPL-1)
- `services/apis/auth-api/.../application/LoginRedirectResolver.java` — APP 분기에서 재사용
- `services/libs/service-client/.../domain/ServiceClient.java` — ownerId 추가 대상 (A-IMPL-1)
- `services/libs/service-client/.../adapter/out/persistence/ServiceClientJpaEntity.java` — owner_id 컬럼 매핑 (A-IMPL-2)
- `services/libs/service-client/.../adapter/out/sas/SasClientRegistrarAdapter.java` — secret 공존 방식 구현 대상 (A-IMPL-10)
- `services/libs/service-client/.../application/usecase/RegisterOAuthClientService.java` — 셀프 등록 로직 추가 (A-IMPL-6)
- `services/libs/member/src/main/resources/db/migration/` — Flyway 마이그레이션 위치 (현재 V6까지 존재, 다음은 V7)
