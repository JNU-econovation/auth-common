# backend-decided-login-redirect - report

## 메타
- **작업명**: backend-decided-login-redirect
- **작성일**: 2026-06-07
- **plan 문서**:
  - todo.md
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md
- **ADR**: docs/adr/0012-backend-decided-login-redirect.md

## 개요

로그인 성공 후 리다이렉트를 프론트엔드가 아닌 백엔드가 결정하도록 변경. 단, 클라이언트가 임의 URL(returnUrl)을 보내는 게 아니라 **clientId만 보내고 백엔드가 그 clientId에 등록된 redirect_uri를 조회해 302** 한다. user-supplied URL이 없으므로 open redirect가 구조적으로 불가능.

> 설계 변경 이력: 최초 returnUrl + origin 화이트리스트 방식으로 plan/test/impl/ADR을 작성했으나, 커밋 전 clientId 기반 방식으로 전면 전환. plan 4종 + ADR-0012 + test + impl 모두 clientId 기준으로 재작성됨.

## 진행 결과

### 1. test
- 신규: `LoginRedirectResolverTest` (7개 — null/blank/공백 clientId→default, 미등록(InvalidClientException)→default, 단일 uri→그것, 복수→정렬 후 첫번째, 빈 Set→default)
- 수정: `AuthApiIntegrationTest` (returnUrl 쿼리 → clientId body 필드. 미등록 clientId→default 302, 등록 clientId 단일/복수→해당 uri 302, APP은 clientId 필드 있어도 200 유지, `login(id,pw,clientId)` 오버로드)
- 삭제: `ReturnUrlValidatorTest`
- Red 확인: `LoginRedirectResolver` 미존재로 컴파일 에러 → Green 전환 확인

### 2. implementation
- 신규: `LoginRedirectResolver`(`resolve(clientId, defaultUrl)`), `AuthRedirectProperties`
- 삭제: `ReturnUrlValidator`
- 변경: `JsonLoginAuthenticationFilter`(LoginRequest에 clientId 추가, attemptAuthentication 파싱→setAttribute→resolve→sendRedirect), `SecurityConfig`, `ApplicationServiceConfig`, `LoginResponse`, `application.yml`, `application-test.yml`(테스트용 AUTH_INTERNAL_API_KEY)
- 빌드/테스트: `./gradlew :services:apis:auth-api:test` → BUILD SUCCESSFUL (--rerun-tasks 전체 실행 확인)

### 3. code-review
- 반영 권장 5(major 3 / minor 2), 참고 5
- 반영: #1 기타 RuntimeException fail-safe catch, #2 `@Slf4j` fallback 로깅, #3 `@RequiredArgsConstructor`
- 미반영: #5 `LoginResponse.web()` 정리(사용자 미선택, 현행 유지), #4 ARCHITECTURE.md(문서 단계에서 처리)
- 재검증: Green 유지
- 비고: 리뷰 반영 implementer agent가 인프라(소켓) 오류로 중단 → 메인이 동일 명세로 직접 적용 후 빌드 검증

### 4. docs
- 갱신: `docs/ARCHITECTURE.md`(흐름 A/역할표/설계결정9/테스트구조), `services/apis/auth-api/README.md`(로그인 API·환경변수), `docs/SEQUENCE-DIAGRAMS.md`(WEB 로그인)

### 5. doc-review
- 반영 권장 5(major 3 / minor 2), 참고 3
- 반영: #1·#3·#5 fail-safe(RuntimeException) 표기, #2 LoginRedirectResolver 명시, #4 쿠키→sendRedirect 순서 단계 분리(⚠️)
- 미반영: P3(README curl 경로 `/api/v1/clients`→`/api/v1/admin/clients` — 기존 문서의 별개 오류, 범위 밖)

## 변경 요약

### 신규 파일
- services/apis/auth-api/src/main/java/com/econo/auth/api/application/LoginRedirectResolver.java
- services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthRedirectProperties.java
- services/apis/auth-api/src/test/java/com/econo/auth/api/application/LoginRedirectResolverTest.java
- docs/adr/0012-backend-decided-login-redirect.md

### 수정 파일 (코드)
- services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java
- services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java
- services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java
- services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/LoginResponse.java
- services/apis/auth-api/src/main/resources/application.yml
- services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java
- services/apis/auth-api/src/test/resources/application-test.yml

### 삭제 파일
- services/apis/auth-api/src/main/java/com/econo/auth/api/application/ReturnUrlValidator.java
- services/apis/auth-api/src/test/java/com/econo/auth/api/application/ReturnUrlValidatorTest.java

### 갱신 docs
- docs/ARCHITECTURE.md
- services/apis/auth-api/README.md
- docs/SEQUENCE-DIAGRAMS.md

## plan과의 차이
- DB 변경 없음(plan과 일치): clientId→redirect_uri 조회는 기존 oauth2_registered_client 읽기만.
- 복수 redirect_uri "첫 번째"는 SAS Set 순서 비보장이라 정렬 후 첫 번째로 결정(plan/ADR에 한계 명시). 등록 순서 "대표"가 필요하면 추후 primary redirect_uri 도입(별도 과제).
- 코드리뷰 #5(LoginResponse.web())는 미사용 deprecated 메서드로 남아 있음(사용자 선택).
- 문서리뷰 P3(README curl 경로 오류)은 기존 문서의 별개 이슈로 미해결.

## 잔여 과제 / 별도 이슈
- SameSite=None 인증 쿠키의 CSRF 대응 (이번 범위 외, 별도 이슈)
- LoginResponse.web() 미사용 메서드 정리 여부
- 복수 redirect_uri 대표 지정용 primary redirect_uri 개념 도입 검토
- **[별도 작업] 클라이언트 등록 문서+ADR 리싱크** — 이번 작업 중 발견된 누적 드리프트(backend-decided-login-redirect와 무관):
  - 실제 코드(`AdminClientController`): 6개 엔드포인트 모두 `/api/v1/admin/*` + ADMIN/SUPER_ADMIN 역할(X-User-Passport). 등록 body `{clientName, redirectUris}` → `{clientId}`. grantType/clientSecret/Basic Auth/public 등록 **없음**, 항상 authorization_code + PKCE public 클라이언트.
  - 문서/ADR은 옛 모델(ADR-0010: public 등록 + Basic Auth + grantType + clientSecret) 그대로 → 코드와 불일치.
  - 대상: `docs/CLIENT_REGISTRATION.md`(전체), `services/apis/auth-api/README.md`(등록 섹션·curl), `services/libs/service-client/README.md`, `AuthApiIntegrationTest.java:39`(주석).
  - **ADR-0010(client-secret self-service)은 SAS+PKCE 재도입으로 자신의 재검토 조건 #1이 충족되어 stale** → 현재 admin-역할 모델을 기록하는 신규 ADR로 supersede 필요.
  - 사용자 결정: 로그인-리다이렉트 커밋과 분리해 별도 작업으로 진행.

## 다음 단계
- /commit 으로 커밋
- /git-pr 로 PR 생성
