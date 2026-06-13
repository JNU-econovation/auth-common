# layered-architecture - report

## 메타
- **작업명**: layered-architecture
- **작성일**: 2026-06-13
- **plan 문서**: todo.md, api-design-plan.md, implementation-plan.md, db-design-plan.md
- **방식**: 리팩토링 모드(순수 패키지 이동, 동작 불변) — 표준 TDD(test-writer) 대신 기존 테스트를 회귀 안전망으로 사용

## 진행 결과

### 1. test
- 신규 테스트 작성 없음(순수 리팩토링). 기존 테스트 20개 파일을 회귀 기준으로 사용.
- baseline: 전체 `./gradlew test` green 확보(Testcontainers 통합 포함, Docker 필요).

### 2. implementation (Phase 1~5)
- Phase 1 `member` / Phase 2 `service-client` / Phase 3 `auth-api` / Phase 4 `api-gateway` / Phase 5 전체 검증 — 모두 green.
- 구조: `presentation`(controller/dto/util) · `application`(usecase/service/repository/domain/util) · `persistence`(entity/repository) · `config`(+`config/security`) · `exception`.
- 엄격 DIP seam 신설 6종: `MemberQueryUseCase`/`MemberQueryService`, `RegisterOAuthClientUseCase`, `ClientRedirectUriUseCase`, `LoginTokenUseCase`, `LoginRedirectUseCase`(전부 동작 중립).
- `config/security` 배치: auth-api(SecurityConfig·UserDetails(Service)·JsonLoginAuthenticationFilter), api-gateway(GatewaySecurityConfig·JwtVerifier·PassportBuilder·BearerToPassportFilter).
- 검증: 전체 clean build + 테스트 + spotless = SUCCESS. 가드 — repository 경계 누수 0 / 계층 순서 위반 0 / iCloud 중복본 0 / 구 패키지 잔존 0.
- 이슈: Phase 3에서 stale·iCloud 중복 `.class`로 빈 충돌 발생 → `clean`으로 해소(코드 정상). iCloud 환경은 대량 이동 후 clean 필수.

### 3. code-review
- 반영 권장 3(major 2/minor 1) / 참고 3.
- 결정: #1 `DynamicCorsConfigurationSource`(repository/service 직접 참조) — 일반 config/는 허용으로 판단, 현행 유지. #2 `MemberQueryService` 이중 `@Transactional` — 동작 동일, 현행 유지. #3 문서 미갱신 — 문서화 단계에서 처리.

### 4. docs
- `docs/ARCHITECTURE.md` 갱신(헥사고날 어휘 → 3계층, 의존성 규칙/다이어그램, 향후 과제 명시).
- `docs/CONVENTION.md` 갱신(패키지 컨벤션, config/security, config/ 예외 규칙).
- `docs/adr/0014-3-layer-dip-architecture.md` 신설(3계층+DIP 채택, 트레이드오프, 보류 사항).

### 5. doc-review
- 반영 권장 3(critical 1/major 1/minor 1) 전부 반영 / 참고 3 보류.
- #1 LoginTokenService 빈 등록 오기재 수정, #2 다이어그램에 일반 config/ 규칙 추가, #3 ADR 보류섹션 빈 등록 방식 구분.

## 변경 요약
- 커밋됨: `ed7afb2 refactor: 전체 모듈 패키지를 3계층(presentation/application/persistence) + 계층별 DIP 구조로 통일` (73 files, rename 63/신규 6/수정 4).
- 미커밋(docs): `docs/ARCHITECTURE.md`, `docs/CONVENTION.md`, `docs/adr/0014-3-layer-dip-architecture.md`.

## plan과의 차이
- `config/DynamicCorsConfigurationSource`가 libs 이동분을 import해 import 갱신 필요했음(plan 명시 목록 밖, 동일 규칙 적용).
- 계층 규칙 명확화: presentation·config/security는 application.usecase에만 의존, **일반 config/ 와이어링 클래스는 application(service/repository) 참조 허용**.

## 보류 사항 (향후 과제)
- auth-api의 앱 고유 application(LoginTokenService, LoginRedirectResolver + usecase)을 새 `auth` lib로 추출.
- 토큰 발급 프레임워크 의존(JwtEncoder/JwtDecoder) 제거를 위한 도메인 포트(`TokenCodec`) 추상화 — 어댑터는 auth-api가 제공.

## 다음 단계
- docs 변경 `/commit` 으로 커밋
- `/git-pr` 로 PR 생성
