# backend-decided-login-redirect - todo

## 메타
- **작업명**: backend-decided-login-redirect
- **문서 타입**: todo
- **작성일**: 2026-06-07
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요
현재 `POST /api/v1/auth/login`(JsonLoginAuthenticationFilter)은 WEB 클라이언트에게 200 OK + Set-Cookie만 반환하고, 로그인 이후 이동할 목적지 결정은 프론트엔드가 담당한다. 이로 인해 프론트가 임의의 returnUrl을 따라갈 경우 open redirect 위험이 존재한다. 이전 시도(returnUrl 화이트리스트 방식)를 폐기하고, 이번 작업에서는 clientId만 받아 백엔드가 그 clientId에 등록된 redirect_uri를 직접 조회한 뒤 302로 보내는 방식으로 전면 재설계한다. user-supplied URL이 없으므로 open redirect가 구조적으로 불가능하다.

## 본문

### API 작업
- [ ] `POST /api/v1/auth/login` 요청 body 스펙을 `{loginId, password, clientId}`로 변경 — 기존 `{loginId, password}`에 `clientId` 필드 추가 (WEB/APP 공통 수신, APP은 무시)
- [ ] WEB 클라이언트 로그인 성공 응답을 `200 OK + body` → `302 Found + Location`으로 변경 — Set-Cookie(at, rt)는 기존과 동일하게 유지, body 없음
- [ ] `clientId` 미전달·미등록·등록 redirect_uri 없음 모두 `auth.redirect.default-url`로 302 fallback (4xx 거부 없음) — API 설계 문서에 명시
- [ ] APP 클라이언트(`Client-Type: APP`) 로그인 성공 응답은 기존 동작 유지 — `200 OK` + body(AT, RT, accessExpiredAt), 리다이렉트 없음, clientId 파싱하되 사용하지 않음
- [ ] 토큰을 절대 Location URL 쿼리/프래그먼트에 포함하지 않음을 API 설계 문서에 명시

### 구현 작업

#### 폐기·제거
- [ ] `ReturnUrlValidator` 클래스 삭제 (`services/apis/auth-api/src/main/java/com/econo/auth/api/application/ReturnUrlValidator.java`) — returnUrl 화이트리스트 방식 폐기
- [ ] `ReturnUrlValidatorTest` 클래스 삭제 (`services/apis/auth-api/src/test/java/com/econo/auth/api/application/ReturnUrlValidatorTest.java`)
- [ ] `ApplicationServiceConfig`의 `ReturnUrlValidator` 빈 등록 코드 제거 (`returnUrlValidator()` 메서드 삭제)
- [ ] `SecurityConfig`의 `ReturnUrlValidator` 의존성 주입 코드 제거 — `@Autowired(required = false) ReturnUrlValidator returnUrlValidator` 파라미터 및 null-check 분기 삭제

#### 신규 구현
- [ ] `LoginRequest` 내부 record에 `clientId` 필드 추가 (`JsonLoginAuthenticationFilter` 내부) — `record LoginRequest(String loginId, String password, String clientId) {}`
- [ ] `JsonLoginAuthenticationFilter.attemptAuthentication()`에서 `clientId`를 파싱 후 `request.setAttribute("clientId", loginRequest.clientId())`로 저장 — InputStream 단일 소비 이후에도 `successfulAuthentication`이 읽을 수 있도록
- [ ] `LoginRedirectResolver` 클래스 신규 생성 (`com.econo.auth.api.application` 패키지) — `ClientRedirectUriService.findByClientId(clientId)`를 읽기 전용으로 호출하여 등록된 redirect_uri를 반환하는 `resolve(String clientId, String defaultUrl): String` 메서드 구현
  - `clientId`가 null·blank이면 즉시 `defaultUrl` 반환
  - `InvalidClientException` 발생(미등록 clientId)이면 `defaultUrl` 반환
  - `ClientInfo.redirectUris()`가 비어 있으면 `defaultUrl` 반환
  - `redirectUris`가 1개이면 그대로 사용, 여러 개이면 정렬(알파벳 오름차순) 후 첫 번째 선택 — SAS `RegisteredClient`가 Set으로 저장해 순서를 보장하지 않으므로 결정적 선택 기준 적용
- [ ] `JsonLoginAuthenticationFilter.successfulAuthentication()` WEB 분기 수정 — `request.getAttribute("clientId")`로 clientId 읽기 → `LoginRedirectResolver.resolve(clientId, defaultRedirectUrl)` 호출 → 쿠키 세팅 후 `response.sendRedirect(target)`
- [ ] `JsonLoginAuthenticationFilter` 생성자에서 `ReturnUrlValidator` 파라미터 제거, `LoginRedirectResolver` 파라미터 추가
- [ ] `SecurityConfig.appSecurityFilterChain()`에서 `ReturnUrlValidator` 파라미터 제거, `LoginRedirectResolver` 주입으로 교체 — null-check 조건에도 `ReturnUrlValidator` → `LoginRedirectResolver`로 교체
- [ ] `ApplicationServiceConfig`에 `LoginRedirectResolver` 빈 등록 추가 — `ClientRedirectUriService`를 생성자 주입
- [ ] `AuthRedirectProperties` 클래스 유지 — Javadoc에서 "returnUrl 검증 실패·미전달" 문구를 "clientId 미전달·미등록·redirect_uri 없음" 문구로 수정, 클래스 본문 구현은 변경 없음

### DB 작업
- 해당 없음

### 기타 작업

#### 설정
- [ ] `application.yml`의 `auth.redirect.default-url` 키 존치 — 기존 설정값 그대로 유지, 주석을 "clientId 미전달·미등록·redirect_uri 없음 시 302 fallback 목적지"로 수정
- [ ] `auth.frontend-login-url`과 `auth.redirect.default-url`의 역할 혼동 방지를 위해 `application.yml` 주석에 두 키의 역할 차이 명시

#### 문서
- [ ] `docs/adr/0012-backend-decided-login-redirect.md`를 clientId 기반 방식으로 재작성 — 기존 returnUrl 화이트리스트 방식 ADR을 폐기하고, 배경·결정·근거·결과 섹션을 새 스펙으로 업데이트, redirect_uri 다중 등록 시 정렬 선택 한계 명시
- [ ] `docs/ARCHITECTURE.md`의 인증 흐름 A 설명에서 `returnUrl` 관련 내용 제거, clientId 기반 302 redirect 흐름으로 업데이트

#### 테스트
- [ ] `LoginRedirectResolverTest` 단위 테스트 작성 (`@ExtendWith(MockitoExtension.class)`) — 다음 시나리오 전수 검증
  - null clientId → defaultUrl 반환
  - 빈 문자열 clientId → defaultUrl 반환
  - 미등록 clientId(`InvalidClientException`) → defaultUrl 반환
  - redirect_uri가 1개인 클라이언트 → 그 URI 반환
  - redirect_uri가 여러 개인 클라이언트 → 정렬 후 첫 번째 URI 반환
  - redirect_uri Set이 비어 있는 클라이언트 → defaultUrl 반환
- [ ] `AuthApiIntegrationTest` 기존 WEB 로그인 테스트 수정 — `status().isOk()` → `status().is3xxRedirection()`, Set-Cookie(at, rt) 검증은 유지
- [ ] `AuthApiIntegrationTest`에 WEB 로그인 clientId 기반 리다이렉트 시나리오 추가
  - `clientId` 미전달 → `auth.redirect.default-url`로 302
  - 등록된 clientId + redirect_uri 1개 → 해당 URI로 302
  - 등록된 clientId + redirect_uri 여러 개 → 정렬 후 첫 번째로 302
  - 미등록 clientId → `auth.redirect.default-url`로 302
- [ ] `AuthApiIntegrationTest` APP 로그인 테스트는 변경 없음 확인 (200 + body 유지, clientId 필드 추가돼도 기존 동작 불변)

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

## 참고
- `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` — InputStream 단일 소비 제약, WEB/APP 분기 위치, `request.setAttribute` 전달 패턴 적용 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/ReturnUrlValidator.java` — 제거 대상 (returnUrl 화이트리스트 방식)
- `services/apis/auth-api/src/test/java/com/econo/auth/api/application/ReturnUrlValidatorTest.java` — 제거 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — `ReturnUrlValidator` → `LoginRedirectResolver` 교체 지점
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — `ReturnUrlValidator` 빈 제거, `LoginRedirectResolver` 빈 추가 지점
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/AuthRedirectProperties.java` — `auth.redirect.default-url` 바인딩, Javadoc 수정 대상
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ClientRedirectUriService.java` — `findByClientId(clientId)` 재사용 대상, `InvalidClientException` 발생 시 fallback 처리
- `services/apis/auth-api/src/main/resources/application.yml` — `auth.redirect.default-url` 주석 수정 대상
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` — WEB 로그인 테스트 수정 대상
- `docs/adr/0012-backend-decided-login-redirect.md` — ADR 전면 재작성 대상
- `docs/ARCHITECTURE.md` — 인증 흐름 A 수정 대상
- `docs/CONVENTION.md` — 테스트 컨벤션(Given-When-Then, @Nested, @DisplayName 한글), 클래스 네이밍 규칙
