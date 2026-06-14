# gateway-tokenless-passthrough - report

## 메타
- **작업명**: gateway-tokenless-passthrough
- **작성일**: 2026-06-15
- **plan 문서**: todo.md, api-design-plan.md, implementation-plan.md, db-design-plan.md

## 진행 결과

### 1. test
- `BearerToPassportFilterTest` 갱신/추가: 새 동작 5케이스 Red 확인(미토큰 통과·동적 prefix 통과·Basic 헤더 통과·쿠키만 통과·위조헤더 strip), 기존 40 green 유지. 컴파일 OK.

### 2. implementation
- `BearerToPassportFilter` strip-then-branch로 변경: ①인바운드 X-User-Passport 항상 제거 ②미토큰 경로무관 passthrough ③유효토큰 JWKS 로컬검증+Passport 주입 ④무효토큰 보호경로 401/permitted 통과. `isProtectedPath`는 무효토큰 분기 전용.
- 빌드/테스트 green(5 Red→Green).

### 3. code-review
- 반영 권장 5(critical 1/major 2/minor 2) / 참고 4. 전부 반영: #1 strip 테스트 StepVerifier 보강, #2 GatewayRoutingConfig Javadoc(패키지 참조·permittedPaths 갱신), #3 application.yml 주석 + /oauth2/** 추가, #4 테스트 FQN→import, #5 DisplayName 명확화.

### 3.5. 로컬 e2e 검증 (사용자 요청)
- 게이트웨이 실기동 + stub 다운스트림으로 검증: ①미토큰+보호경로 200 passthrough ②위조 X-User-Passport strip(다운스트림 null) ④미토큰+permitted 200 — 통과.
- **버그 발견·수정**: 형식이 깨진(malformed) 토큰 → 500(should be 401). 원인: `JwtVerifier.verify()`가 동기 ParseException을 던져 onErrorResume 우회. 수정: `Mono.defer(() -> jwtVerifier.verify(token))`로 감싸 동기 예외도 리액티브 에러 경로로 일원화 → 보호경로 401. 테스트 케이스 추가(thenThrow BadJwtException). 재기동 후 ③ 401 확인.

### 4. docs
- `docs/adr/0017-gateway-tokenless-passthrough.md` 신설(ADR-0002 보완, supersede 아님). `docs/ARCHITECTURE.md` [흐름 B] + 컴포넌트 역할 정의(ApiGateway/AuthApi, JWKS 로컬검증=introspection 아님). `services/apis/api-gateway/README.md`, `CLAUDE.md`(Gateway 변환 + ADR 0017) 갱신.

### 5. doc-review
- 반영 권장 4(major 3/minor 1) / 참고 3. 전부 반영: #1 ARCHITECTURE [흐름 A] stale 정정, #2 무효토큰 레이블에 malformed 추가(ARCHITECTURE+README), #3 CLAUDE passport 링크 정정, #4 ADR 배경 보강.

## 변경 요약
- 코드: `BearerToPassportFilter.java`(strip-then-branch + Mono.defer), `GatewayRoutingConfig.java`(Javadoc), `application.yml`(주석 + /oauth2/**).
- 테스트: `BearerToPassportFilterTest.java`(새 케이스 + StepVerifier + malformed).
- 문서: ADR-0017 신설, `ARCHITECTURE.md`·`api-gateway/README.md`·`CLAUDE.md` 갱신.

## plan과의 차이
- 구현은 plan 그대로. 추가: 로컬 e2e에서 발견한 malformed 토큰 500→401 수정(`Mono.defer`) — plan에 없던 robustness 보강. /oauth2/**를 permitted-paths에 추가(테스트 mock·정적라우트와 일치).

## 다음 단계
- /commit 으로 커밋
- /git-pr 로 PR 생성
