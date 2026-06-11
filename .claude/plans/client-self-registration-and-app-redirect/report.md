# client-self-registration-and-app-redirect - report

## 메타
- **작업명**: client-self-registration-and-app-redirect
- **작성일**: 2026-06-12
- **plan 문서**: todo.md / api-design-plan.md / implementation-plan.md / db-design-plan.md
- **관련 ADR**: docs/adr/0013-passport-member-self-registration.md (신규), 0010 supersede 처리

## 개요
두 기능을 함께 구현.
- **A. SSO 클라이언트 셀프 등록**: 인증된 에코노 회원(X-User-Passport memberId)이 `POST /api/v1/clients`로 직접 등록. 1인 최대 5개. 응답 clientId + clientSecret(평문 1회, BCrypt 해시는 service_client.client_secret_hash). SAS는 NONE(public PKCE) 유지(B안). 기존 admin 등록(`/api/v1/admin/clients`, ADMIN)은 공존.
- **B. APP 로그인 리다이렉트**: APP 로그인 200 응답 body에 redirectUrl 추가(LoginRedirectResolver). WEB은 쿠키+302 유지.

## 진행 결과

### 1. test
- 신규: ClientControllerTest, LoginResponseTest, PassportHeaderParserTest. 수정: RegisterOAuthClientServiceTest, AuthApiIntegrationTest.
- Red 확인(신규 클래스 미존재 컴파일 에러) → 구현 후 Green.

### 2. implementation
- 신규: ClientController(POST /api/v1/clients), PassportHeaderParser(util), ClientLimitExceededException, V7 마이그레이션(owner_id BIGINT NULL + idx, client_secret_hash VARCHAR(72) NULL).
- 변경: ServiceClient(owner_id, client_secret_hash), ServiceClientJpaEntity/Repository/Adapter, ServiceClientRepository(countByOwnerId), RegisterOAuthClientService(selfRegister), LoginResponse(redirectUrl, app 4인자), JsonLoginAuthenticationFilter(APP 분기), GlobalExceptionHandler(422), SecurityConfig(/api/v1/clients 인증 경로).
- 빌드/테스트: `:services:libs:service-client:test :services:apis:auth-api:test` --rerun-tasks → BUILD SUCCESSFUL(Testcontainers 통합 포함, Docker 가동).
- 비고: implementer가 소켓 오류로 2회 중단 → 이어받아 완성. 통합테스트 격리 버그(공유 DB memberId 충돌) 1건 수정(전용 memberId=9001).

### 3. code-review
- 반영 권장 7(critical 1/major 4/minor 2), 참고 5. 사용자 선택으로 **7건 전부 반영**.
  - #1 검증 순서 교정(입력검증→DB카운트, NPE 방어), #2 401 AUTH_UNAUTHORIZED, #3 PassportClaims 공통화(AdminClientController/AdminMemberController), #4 redirectUris @NotNull, #5 동시성 한계 주석, #6 import 컨벤션, #7 트랜잭션 원자성 확인(동일 DataSource/@Transactional → 보장됨, 근거 주석).
- 재검증: --rerun-tasks Green.

### 4. docs
- 갱신: CLIENT_REGISTRATION.md(전체 재작성), auth-api/README.md, ARCHITECTURE.md, SEQUENCE-DIAGRAMS.md, FEATURES.md.
- 신규: ADR-0013(회원 셀프서비스 등록), ADR-0010 상태 → Superseded by ADR-0013.

### 5. doc-review
- 반영 권장 6(critical 1/major 3/minor 2), 참고 3. 사용자 선택으로 **6건 전부 반영**.
  - #1 README clientId가 APP에서도 쓰임으로 정정, #2 설계결정 번호 정렬, #3·#5 grantType/UnsupportedGrantType dead 서술 제거, #4 AdminClientController 에러표를 Passport ADMIN 403으로 교체, #6 INVALID_ARGUMENT 보강.
- ARCHITECTURE.md의 옛 ADR-0010 잔재 완전 정리됨.

## 변경 요약
### 신규 파일
- services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/ClientController.java
- services/apis/auth-api/src/main/java/com/econo/auth/api/util/PassportHeaderParser.java
- services/libs/service-client/src/main/java/com/econo/auth/client/exception/ClientLimitExceededException.java
- services/libs/member/src/main/resources/db/migration/V7__add_owner_id_to_service_client.sql
- 테스트: ClientControllerTest, LoginResponseTest, PassportHeaderParserTest
- docs/adr/0013-passport-member-self-registration.md

### 수정 파일 (코드)
- service-client: RegisterOAuthClientService, ServiceClient, ServiceClientJpaEntity, ServiceClientJpaRepository, ServiceClientRepositoryAdapter, ServiceClientRepository(port)
- auth-api: LoginResponse, JsonLoginAuthenticationFilter, GlobalExceptionHandler, SecurityConfig, AdminClientController, AdminMemberController(PassportClaims 공통화)
- 테스트: RegisterOAuthClientServiceTest, ClientControllerTest, AuthApiIntegrationTest

### 갱신 docs
- docs/CLIENT_REGISTRATION.md, services/apis/auth-api/README.md, docs/ARCHITECTURE.md, docs/SEQUENCE-DIAGRAMS.md, docs/FEATURES.md, docs/adr/0010(상태), docs/adr/0013(신규)

## plan과의 차이
- 검증 순서: 테스트/리뷰 반영으로 입력검증을 DB 카운트 앞으로(plan 의도와 일치하게 교정).
- PassportClaims: plan은 공통화를 명시했고 1차 구현에선 미반영됐으나 코드리뷰 #3으로 공통화 완료.
- clientSecret: 발급·BCrypt 저장하나 **현재 소비 in-scope 엔드포인트 없음**(선발급/보관). 향후 redirect-uri 셀프 관리 API에서 사용 예정.

## 잔여 과제 / 별도 이슈
- redirect-uri 셀프 관리 API(셀프 등록 클라이언트가 자기 redirect_uri를 Basic Auth로 CRUD) — 미구현, clientSecret의 실제 소비처.
- clientSecret 회전/재발급 엔드포인트 — 미구현.
- 셀프 등록 rate limiting(도배 방지) — 별도 이슈.
- 1인 5개 제한 동시성 — 현재 count 검증(주석), 필요 시 DB 락 강화.
- redirect URI 형식 검증(피싱 방지) — 기존에도 없음, 별도 이슈.
- SameSite=None 쿠키 CSRF — 별도 이슈(이전 작업부터 이월).

## 추가 작업 (develop 이후, 사용자 지시)
- **econo-passport 라이브러리 도입**: auth-api가 직접 만든 PassportHeaderParser를 폐기하고 외부 라이브러리 `com.github.JNU-econovation:econo-passport:1.0.3`의 `@PassportAuth`/`Passport`/`Roles`로 X-User-Passport 파싱·인가를 통일.
  - ClientController: `@PassportAuth Passport`로 memberId(소유자) 식별. AdminClientController/AdminMemberController: `@PassportAuth(requiredRoles=ADMIN/SUPER_ADMIN)` (updateRole은 SUPER_ADMIN)로 수동 isAdmin 대체.
  - PassportHeaderParser(+테스트) 삭제. GlobalExceptionHandler에 PassportException 매핑(401→AUTH_UNAUTHORIZED, 403→FORBIDDEN, 그 외 status+errorCode).
  - 에러코드 변화: passport 헤더 누락 → 401 AUTH_UNAUTHORIZED(어드민도 기존 403→401), 잘못된 Base64 → 400 AUTH_BAD_REQUEST. 문서 반영함.
  - auth-api 전체 테스트 Green(통합 포함, --rerun-tasks).
- **[별도 과제] 게이트웨이 passport 라우팅 갭**: api-gateway가 /api/v1/clients·/admin/**·/members/**를 auth-api로 라우팅하지 않고 /api/**→EEOS로 보냄 → 이 경로에 X-User-Passport 주입 경로가 없음. @PassportAuth가 프로덕션에서 동작하려면 게이트웨이 라우트 추가 필요. (메모리 gateway-passport-routing-gap 기록, 사용자 결정으로 이번 커밋과 분리)

## 다음 단계
- /commit 으로 커밋
- /git-pr 로 PR 생성 (또는 기존 PR #4에 포함)
