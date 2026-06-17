# login-redirect-via-body - todo

## 메타
- **작업명**: login-redirect-via-body
- **문서 타입**: todo
- **작성일**: 2026-06-18
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

WEB 로그인 성공 시 서버가 수행하던 `response.sendRedirect()` (302) 를 제거하고, 쿠키 SSO(AT/RT HttpOnly 쿠키)는 유지하면서 `redirectUrl` 을 응답 body로 내려주는 방식으로 전환한다. FE(SPA)가 `fetch`로 로그인을 호출할 때 서버 302가 cross-origin CORS 오류(`net::ERR_FAILED`)를 유발하는 문제를 해소하기 위함이다. 리다이렉트 목적지 결정 주체는 백엔드가 유지한다(ADR-0012 핵심 결정 유지). APP 분기, DB 스키마, 게이트웨이 코드는 변경하지 않는다.

## 본문

### API 작업
- [ ] `POST /api/v1/auth/login` WEB 분기 응답 변경: 302 Found → 200 OK + body `{ "accessExpiredTime": <long>, "redirectUrl": "<url>" }` (accessToken·refreshToken은 쿠키 전용이므로 body에서 null 유지, `@JsonInclude(NON_NULL)` 적용)
- [ ] Swagger 명세(`LoginOpenApiCustomizer`) WEB 분기 응답 수정: `302` 응답 항목을 `200` 응답(body: `{ accessExpiredTime, redirectUrl }`)으로 교체하고, 기존 `302` 항목 제거 (APP 200 항목 및 401 항목은 유지)

### 구현 작업
- [ ] `LoginResponse.web(long accessExpiredTime)` 팩토리 시그니처 변경: `web(long accessExpiredTime, String redirectUrl)` — `redirectUrl` 파라미터 추가, `@Deprecated` 어노테이션 제거
- [ ] `JsonLoginAuthenticationFilter.successfulAuthentication()` WEB 분기 수정: `response.sendRedirect(target)` 제거 → `response.setStatus(SC_OK)` + `objectMapper.writeValue(response.getWriter(), LoginResponse.web(accessExpiredTime, target))` 로 교체 (쿠키 세팅 코드는 변경 없음, 쿠키 세팅 후 body 직렬화 순서 유지)
- [ ] `JsonLoginAuthenticationFilter` 클래스 Javadoc WEB 분기 설명 갱신: "302 리다이렉트" → "200 OK + body(redirectUrl)"

### DB 작업
- 해당 없음

### 기타 작업
- [ ] `LoginResponseTest` WEB 분기 단위 테스트 추가: `web(long, String)` 팩토리 — `redirectUrl` 포함 직렬화 검증, `web(long, null)` 시 JSON에서 `redirectUrl` 필드 제외 검증 (`@JsonInclude(NON_NULL)`)
- [ ] `AuthApiIntegrationTest` WEB 분기 테스트 수정: `status().is3xxRedirection()` → `status().isOk()`, `Location` 헤더 검증 제거 → `jsonPath("$.redirectUrl")` 검증으로 교체, AT/RT 쿠키 세팅 검증은 유지 (영향받는 테스트: `web_login_issues_cookies_and_redirects`, `web_login_without_clientId_redirects_to_defaultUrl`, `web_login_unregistered_clientId_redirects_to_defaultUrl`, `web_login_with_registered_clientId_single_redirect_uri`, `web_login_with_registered_clientId_multiple_redirect_uris`, `web_login_location_does_not_contain_tokens`)
- [ ] `AuthApiIntegrationTest` 내 WEB 분기 검증 항목 추가: body에 `accessToken`·`refreshToken` 미포함, `redirectUrl` 필드 값이 올바른 URL인지 검증
- [ ] `AuthApiIntegrationTest` APP 분기 테스트 중 WEB 비교 케이스 수정: `web_login_response_does_not_contain_redirectUrl_in_body` 테스트를 삭제하거나 WEB이 200+body를 반환하도록 기대값 갱신 (기존 전제 "WEB은 302, body 없음"이 무효화됨)
- [ ] ADR-0012 amend: "전달 방식 302→body 전환" 보완 기재 — 결정 주체(백엔드 clientId 결정)는 유지, 전달 방식만 변경, 변경 동기(fetch cross-origin 302 문제), Safari ITP 비고 추가
- [ ] `docs/ARCHITECTURE.md` [흐름 A] WEB 분기 갱신: `response.sendRedirect(target)` → `200 OK + body { redirectUrl, accessExpiredTime }`, `HTTP 302 Found / Location` 항목 제거, `Set-Cookie` 기재 유지
- [ ] `services/apis/auth-api/README.md` `POST /api/v1/auth/login` WEB 응답 예시 갱신: 302 코드 블록 → 200 JSON 예시(`{ "accessExpiredTime": ..., "redirectUrl": "..." }`)로 교체, 쿠키 세팅 설명 유지

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

## 참고
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/JsonLoginAuthenticationFilter.java` — WEB 분기 103-112행
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/LoginResponse.java` — `web()` 팩토리 41-45행
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/openapi/LoginOpenApiCustomizer.java` — 302 응답 명세 59-71행
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` — `WebLoginTest` 중첩 클래스 및 `AppLoginTest.web_login_response_does_not_contain_redirectUrl_in_body`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/presentation/dto/LoginResponseTest.java` — `web()` 관련 케이스 없음(현재 `app()` 전용)
- `docs/adr/0012-backend-decided-login-redirect.md` — 현재 결정 내용 (amend 대상)
- `docs/ARCHITECTURE.md` — [흐름 A] 섹션 105-122행 (갱신 대상)
- `services/apis/auth-api/README.md` — WEB 응답 코드 블록 73-84행 (갱신 대상)
