# client-registration - report

## 메타
- **작업명**: client-registration
- **작성일**: 2026-05-25
- **브랜치**: feat/client-registration (feat/member-auth 위에 스택)
- **plan 문서**: todo.md / api-design-plan.md / implementation-plan.md / db-design-plan.md

## 개요
Spring Authorization Server(SAS **1.2.1**) 내장 **OIDC Dynamic Client Registration(DCR)** 을 활성화했다. 직접 RFC 7591/7592 엔드포인트를 구현하지 않고 SAS 기능을 켜고 설정한다. 외부 client가 런타임에 자가 등록할 수 있게 하되, **registrar 클라이언트(client_credentials)** 가 발급한 `client.create` 스코프 access token(Initial Access Token)으로 보호한다.

## 확정 범위
- ✅ **등록**: `POST /connect/register` (scope `client.create`)
- ✅ **조회**: `GET /connect/register?client_id=...` (registration_access_token, scope `client.read`)
- ❌ **수정(PUT)·삭제(DELETE)**: SAS 1.2.1 네이티브 미지원 → **범위 외**. (전체 RFC 7592 CRUD는 SAS 위 커스텀 구현이 필요한 별도 작업)

## 진행 결과

### 1. test
- 신규 `DcrIntegrationTest` (12 시나리오: registrar 토큰 발급, 등록(confidential/public), 인가 검증(토큰 없음/스코프 부족 403/무효), 조회, consent, discovery). PUT/DELETE 미작성.
- Red 확인: 컴파일 성공 + DCR 미활성화로 통합 테스트 실패(구조적). 기존 단위/@WebMvcTest Green 유지.

### 2. implementation
- `AuthorizationServerConfig`: `oidc().clientRegistrationEndpoint(Customizer.withDefaults())` 활성화. SAS 1.2.1이 `client.create`/`client.read` 스코프를 자체 강제(근거 Javadoc).
- `RegisteredClientConfig`: confidential **registrar client** 멱등 시드(고정 UUID, client_credentials, scope client.create+client.read, secret 환경변수, BCrypt cost 12, TTL 5분).
- `GatewayRoutingConfig`: `/connect/register` permit + 라우팅.
- `application.yml`: `REGISTRAR_CLIENT_ID`/`REGISTRAR_CLIENT_SECRET`.
- DB 변경 없음(client→oauth2_registered_client, registration_access_token→oauth2_authorization).
- 검증: 전체 클린 컴파일 + 기존 실행 가능 테스트 Green. DcrIntegrationTest는 Docker 부재로 컴파일까지(실행 CI 위임).

### 3. code-review
- 반영 권장 7개(치명 2·주요 3·경미 2) + 참고 3개 반영.
  - 치명: DCR 스코프 인가 근거 명시(#1), 기존 통합 테스트(SAS·AuthApi)에 registrar 환경변수 주입(#2 — 미주입 시 컨텍스트 로드 실패).
  - 주요: BCrypt cost 12 통일, SecurityConfig securityMatcher에 `/connect/` 제외, Gateway permit 근거 주석.
  - 경미: 인가 실패 테스트 403 단언 고정.
  - 참고: registrar TTL 5분, CORS 근거 주석, spotless 포맷.
- 재검증: 클린 컴파일 + 기존 테스트 + spotlessCheck Green.

### 4. docs
- 갱신 5개: `docs/ARCHITECTURE.md`(DCR 흐름·설계 결정·SAS 역할), `docs/INFRASTRUCTURE.md`(REGISTRAR 환경변수·DCR 구성요소), `README.md`, `services/apis/auth-api/README.md`, `services/apis/api-gateway/README.md`. CONVENTION.md 불변(신규 규칙 없음).

### 5. doc-review
- 반영 권장 4개(주요 2·경미 2) 반영: ARCHITECTURE 기술스택 SAS 버전 1.2.1 통일, 설계 결정 번호 순서(9→10→11) 정정, INFRASTRUCTURE 인프라 표 DCR 행 추가, auth-api README DCR 헤딩 범위(등록+조회) 명확화. 참고 3개 제외.

## 변경 요약
- **신규**: `DcrIntegrationTest`
- **수정(코드/설정)**: AuthorizationServerConfig, RegisteredClientConfig, SecurityConfig, application.yml(auth-api), GatewayRoutingConfig
- **수정(테스트)**: AuthApiIntegrationTest, SasAuthorizationServerIntegrationTest (registrar 환경변수 주입)
- **갱신 docs**: ARCHITECTURE, INFRASTRUCTURE, 루트 README, auth-api README, api-gateway README (5)
- **DB**: 변경 없음 (신규 마이그레이션 없음)

## plan과의 차이
- **RFC 7592 범위 축소**: plan 선택은 "등록+관리(RFC 7592)"였으나, develop 사전점검에서 SAS 1.2.1이 PUT/DELETE를 미지원함을 확인 → 사용자 승인하에 **등록+조회**로 확정.
- consent 강제 주입: SAS 1.2.1에 `RegisteredClientConverter` 교체 표준 hook이 없어 미구현(향후 과제). 동의 UI는 외부 프런트(범위 밖).

## 다음 단계
- `/commit` 으로 커밋
- `/git-pr` 로 PR (현재 PR #4가 feat/member-auth 기준이므로, 이 브랜치용 별도 PR 또는 #4에 후속)
- **운영 전 필수**: Docker/CI에서 `DcrIntegrationTest` 등 Testcontainers 통합 테스트 실행하여 Green 확인 (DCR 동작은 로컬 미검증).
