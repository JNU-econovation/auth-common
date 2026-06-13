# auth-lib-extraction - report

## 메타
- **작업명**: auth-lib-extraction
- **작성일**: 2026-06-14
- **plan 문서**: todo.md, api-design-plan.md, implementation-plan.md, db-design-plan.md
- **방식**: 리팩토링 모드(동작 불변) — 기존 테스트(특히 AuthApiIntegrationTest)를 회귀 안전망으로 사용

## 진행 결과

### 1. test
- 신규 Red 테스트 없음(순수 리팩토링). green baseline 확보(merge 후 전체 테스트 green).

### 2. implementation
- 신규 모듈 `services/libs/login`(`com.econo.auth.login`): TokenEncoder/TokenDecoder 출력 포트, TokenModel/DecodedToken(도메인 record), InvalidTokenException/WrongTokenTypeException, LoginTokenUseCase(Jwt 제거)/LoginRedirectUseCase, LoginTokenService(@Service, TokenEncoder/TokenDecoder 사용)/LoginRedirectResolver(@Service), LoginAutoConfiguration. 테스트 17개.
- auth-api: NimbusTokenManager(config/security, TokenEncoder/TokenDecoder 구현), ReissueController(JwtDecoder/Jwt 제거 → verifyRefreshTokenAndGetMemberId + 도메인 예외 catch로 401 보존), ApplicationServiceConfig(loginRedirectResolver @Bean 제거), import 갱신, 구 application 4파일 삭제. 테스트 104개.
- 검증: 전체 clean build green. 가드 — login 코드의 spring-security/oauth2/jwt import 0(TokenEncoder/TokenDecoder 추상화 성공) / login 의존=member+service-client / 순환 0 / iCloud 중복 0 / ReissueController 프레임워크 탈피. AuthApiIntegrationTest(로그인·재발급·AT-as-RT→401) green.

### 3. code-review
- 반영 권장 3(critical 1/major 1/minor 1) / 참고 3.
- #1 iCloud 중복본 16개 제거(critical) / #2 DecodedToken·TokenModel null claims 방어 적용(major) / #3 roles 비이슈 격하 / 참고(@DisplayName 추가, lombok은 루트 컨벤션 유지로 skip, docs는 문서화 단계).

### 4. docs
- `services/libs/login/README.md` 신설. `docs/ARCHITECTURE.md`·`CLAUDE.md`에 login lib(구조·의존성·역할) 반영, "보류/향후 과제"였던 application 추출·TokenEncoder/TokenDecoder을 완료로 갱신.
- ADR 번호 충돌 정리: flyway ADR 0014 → **0015** 리넘버(파일 rename + INFRASTRUCTURE·member README·CLAUDE·ARCHITECTURE 참조 갱신), CLAUDE ADR 목록에 3계층 0014 추가. → 0014=3계층, 0015=flyway 단일 정합.

### 5. doc-review
- 반영 권장 5(major 3/minor 2) / 참고 3 — 4건 반영.
- #1 ADR-0014 보류→완료 / #5 login README issuer(AUTH_ISSUER_URI) 추가 / #2+#3 member README 3계층 경로·어휘 정정(기존 드리프트) / #4 ARCHITECTURE apps 표 보충.

## 변경 요약
- 신규: `services/libs/login` 모듈(소스 12 + 테스트 3), auth-api `NimbusTokenManager`(+테스트), `services/libs/login/README.md`.
- 수정: settings.gradle.kts, auth-api build.gradle.kts, ApplicationServiceConfig, SecurityConfig, JsonLoginAuthenticationFilter, ReissueController, docs(ARCHITECTURE·CLAUDE·INFRASTRUCTURE·ADR-0014), member README.
- 삭제: auth-api 구 application 4파일(+테스트 2). ADR 0014-flyway → 0015 rename.

## plan과의 차이
- 없음(구현은 plan 그대로). 모듈명은 plan 단계에서 `auth`→`login`으로 확정해 plan 문서·구현 모두 `login` 반영.
- 부수 처리: main(PR #12 마이그레이션 전역화) merge, ADR-0014 번호 충돌(3계층 vs flyway) 0015 리넘버, member README 기존 드리프트 정정.

## 다음 단계
- /commit 으로 커밋
- /git-pr 로 PR 생성
