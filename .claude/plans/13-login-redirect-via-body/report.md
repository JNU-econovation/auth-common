# login-redirect-via-body - report

## 메타
- **작업명**: login-redirect-via-body
- **작성일**: 2026-06-18
- **브랜치**: feat/login-redirect-via-body
- **plan 문서**:
  - todo.md
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 배경
WEB 로그인 성공 시 서버가 302로 Service Client(서드파티 도메인)로 리다이렉트하면, FE(SPA)가 `fetch`로 호출하는 구조에서 cross-origin 302를 fetch가 못 살리고 CORS(`net::ERR_FAILED`)가 발생한다. 서버 302를 제거하고 `redirectUrl`을 응답 body로 내려, FE가 `window.location`으로 직접 이동하도록 전환했다. SSO 토큰(AT/RT)은 기존처럼 HttpOnly 쿠키(.econovation.kr)로 유지.

## 진행 결과

### 1. test
- 작성/수정 테스트: 단위 4(`LoginResponseTest.WebFactoryMethodTest`) + 통합 7(`AuthApiIntegrationTest` WEB 로그인/재발급) + APP 비교 1.
- Red 확인: `web(String)` 미구현 컴파일 에러 → 테스트 sourceSet 전체 빌드 실패.

### 2. implementation
- 변경: 코드 4 / 테스트 2.
  - `LoginResponse`: `web(long, String)` → 최종 `web(String redirectUrl)` (accessExpiredTime WEB 제거), `@Deprecated` 해제.
  - `JsonLoginAuthenticationFilter`: WEB 분기 `sendRedirect` 제거 → 200 OK + body `{redirectUrl}` 직렬화. 클래스 Javadoc 갱신.
  - `ReissueController`: WEB 분기 `web(null)` → body `{}` (의도 주석 추가). APP 분기 불변.
  - `LoginOpenApiCustomizer`: Swagger 302 제거 → 200 WEB/APP 통합, 스키마 nullable + description 정정.
- Green: auth-api 전체 158 테스트 통과, spotless 통과.

### 3. code-review (2회)
- 1차: 반영 권장 5(major 3 문서 / minor 2 코드) → 코드(Swagger nullable, 테스트 given/메서드명) 반영, 문서 3건은 4단계로 이관.
- 작업 중 사용자 요청으로 **WEB 응답에서 accessExpiredTime 제거**(APP 유지) 반영 — plan 변경.
- 2차(문서 갱신 후 재리뷰): 반영 권장 3(major 1 Swagger description / minor 2 FEATURES·plan) → 전부 반영.
- 재검증: Green 유지, WEB body accessExpiredTime 잔존 0건, APP 회귀 없음.

### 4. docs
- 갱신 문서 8개:
  - `docs/ARCHITECTURE.md`([흐름 A]·모듈 표·설계결정 경로 A), `docs/SEQUENCE-DIAGRAMS.md`, `docs/FEATURES.md`, `docs/outcomes/03-frontend-integration.md`, `services/apis/auth-api/README.md`, `CLAUDE.md`(경로 A).
  - `docs/adr/0012-backend-decided-login-redirect.md`: amendment(결정 주체 유지 + 전달방식 302→body + WEB accessExpiredTime 제거 + Safari/Chrome 서드파티 쿠키 리스크 비고).
- WEB body를 전 문서에서 `{ redirectUrl }`로 통일(중간에 stale plan 참조로 `{accessExpiredTime, redirectUrl}` 오기재된 것 정정).

### 5. doc-review
- 문서(.md) 7종: 추가 반영 권장 없음(계약 정확·일관·302 잔재 없음).
- 소스 Javadoc 1건(major): `JsonLoginAuthenticationFilter` 클래스 Javadoc WEB body `{accessExpiredTime, redirectUrl}` → `{redirectUrl}` 정정. 반영 완료.
- 참고 2건(ARCHITECTURE 인터페이스명 표기, outcomes 예시 clientId)은 강제 아님 → 제외.

## 변경 요약
- 신규 파일: 없음
- 수정 파일(코드):
  - `services/apis/auth-api/.../presentation/dto/LoginResponse.java`
  - `services/apis/auth-api/.../config/security/JsonLoginAuthenticationFilter.java`
  - `services/apis/auth-api/.../presentation/controller/ReissueController.java`
  - `services/apis/auth-api/.../config/openapi/LoginOpenApiCustomizer.java`
- 수정 파일(테스트):
  - `services/apis/auth-api/.../presentation/dto/LoginResponseTest.java`
  - `services/apis/auth-api/.../integration/AuthApiIntegrationTest.java`
- 갱신 docs: ARCHITECTURE.md, SEQUENCE-DIAGRAMS.md, FEATURES.md, outcomes/03-frontend-integration.md, adr/0012-backend-decided-login-redirect.md, auth-api/README.md, CLAUDE.md

## 최종 API 계약
- WEB 로그인(비-APP): 200 OK + Set-Cookie(at/rt HttpOnly, SameSite=None, Secure) + body `{ "redirectUrl": "..." }`.
- APP 로그인(Client-Type: APP): 변경 없음 — body { accessToken, accessExpiredTime, refreshToken, redirectUrl }.
- WEB 재발급: body `{}`. APP 재발급: 변경 없음.
- redirectUrl은 백엔드가 clientId로 결정(ADR-0012 핵심 유지), 전달 방식만 302→body.

## plan과의 차이
1. **accessExpiredTime WEB 제거**: plan은 WEB body `{accessExpiredTime, redirectUrl}`였으나, 구현 중 사용자 결정으로 WEB에서 accessExpiredTime 제거 → body `{redirectUrl}`만. `web(long, String)` → `web(String)`. APP은 유지. plan 문서(api-design/implementation)도 사후 갱신.
2. **ReissueController 호출처**: plan은 "`web(long)` 호출처 없음"이라 했으나 실제 ReissueController가 호출 중 → 새 시그니처에 맞춰 WEB 재발급 body `{}`로 전환(accessExpiredTime 제거).

## 비고 (범위 밖)
- cross-site fetch로 `.econovation.kr` 쿠키 세팅 시 Safari ITP/Chrome 서드파티 쿠키 정책 리스크. 장기적으로 로그인 페이지를 `*.econovation.kr` 서브도메인으로 이전 권장. (ADR-0012 비고에 기록)
- FE 변경 필요: 로그인 fetch에 `credentials: 'include'`, 응답 body의 `redirectUrl`을 읽어 `window.location` 이동. CORS_ALLOWED_ORIGINS에 FE 오리진 추가 + 게이트웨이·auth-api 재배포.

## 다음 단계
- /commit 으로 커밋
- /git-pr 로 PR 생성
