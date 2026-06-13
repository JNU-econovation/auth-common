# 11-swagger-dto-examples - report

## 메타
- **작업명**: 11-swagger-dto-examples
- **작성일**: 2026-06-14
- **브랜치 / 워크트리**: `refactor/swagger-dto-examples` (`~/worktrees/auth-common-dynamic-gateway-routing`, origin/main 902eedf 기준)
- **plan 문서**: todo.md / api-design-plan.md / implementation-plan.md / db-design-plan.md

## 개요

auth-api 컨트롤러에 inner record/class로 흩어져 있던 요청·응답 DTO를 `presentation/dto/` 표준 record로 전부 추출하고, 각 필드에 Swagger `@Schema(description, example)`를 작성해 Swagger UI에 의미 있는 예시가 표시되도록 했다. 중복 `ErrorResponse`(3곳)·`RoleUpdateRequest`(2곳)는 공용 DTO 하나로 통합했다. `@Hidden`인 InternalRouteController inner record는 추출하지 않았다. DB 변경 없음.

## 진행 결과

### 1. test
- 작성: `ReissueControllerTest` — Reissue 에러 응답에 `timestamp` 포함을 검증하는 5개 신규 테스트.
- Red 확인(5 fail, 기존 149 green) → 구현 후 Green.

### 2. implementation
- 신규 DTO 16개(`presentation/dto/`): 공용 `ErrorResponse`·`RoleUpdateRequest` + 컨트롤러별 추출 14개.
- 기존 DTO 6개 `@Schema` 보강(SignupRequest, LoginResponse, CreateRouteRequest, UpdateRouteRequest, RouteResponse, RouteListResponse).
- 컨트롤러 7개 inner record 제거 → dto import 교체. docs 인터페이스 6개 import 갱신.
- 3모듈 빌드/auth-api 테스트 Green.

### 3. code-review
- 반영 권장 6 / 참고 4. 반영: `RoleUpdateRequest` `@Valid` 실효화(#1), redirect 요청 DTO `@NotBlank`/`@NotNull` + `@Valid`(#6), 정적 팩토리 `from()` Javadoc + 파라미터명 통일(#3), `ErrorResponse` 편의 생성자 Javadoc(#4). plan 문서 체크리스트·문구의 폐기된 NON_NULL 잔존(#5, 참고4)은 메인이 직접 정리.
- 재검증 Green.

### 4. docs
- `services/apis/auth-api/README.md` — reissue 에러 응답 포맷(공용 ErrorResponse, timestamp 포함) 추가.
- `docs/CONVENTION.md` §8.7 — "presentation/dto 요청·응답 DTO 필드에 @Schema(description, example) 작성, nullable 필드는 nullable=true" 규칙 추가, 제목을 `DTO 스키마 문서화(@Schema)`로 정합.

### 5. doc-review
- 반영 권장 2 / 참고 2. 반영: `ReissueRequest.refreshToken`에 `@Schema(nullable=true)` 추가(#1, 코드), README 에러 응답을 reissue 전용으로 범위 한정 + GlobalExceptionHandler(ApiError) 차이 명시(#2), CONVENTION §8.7 제목/서술 보정(참고1).

### 5.5 추가 보강 (로컬 실행 검증 중 발견 → 사용자 승인)
- `ResponseEntity<?>`(와일드카드) 반환 핸들러는 springdoc이 응답 스키마를 추론하지 못해 응답 DTO 8개의 example이 Swagger에 노출되지 않음을 로컬 `/v3/api-docs` 검증으로 발견.
- docs 인터페이스 4개(`ClientApiDocs`·`AdminClientApiDocs`·`ReissueApiDocs`·`AdminMemberApiDocs`)의 `@ApiResponse`에 `content = @Content(schema = @Schema(implementation = XxxResponse.class))` 명시로 해결(컨트롤러·테스트 무변경).
- 결과 검증: OpenAPI 스키마 14 → 22, 누락 8개(LoginResponse, SelfRegisterClientResponse, RegisterClientResponse, RedirectUrisResponse, ClientDetailResponse, MemberSummary, PagedMembersResponse, ErrorResponse) 전부 example과 함께 노출. `internal-route` 숨김 유지.

## 변경 요약
- 신규: `presentation/dto/` DTO 16개, 테스트 `ReissueControllerTest`.
- 변경: 컨트롤러 7, docs 인터페이스 6(+응답스키마 4), 기존 DTO 6 `@Schema` 보강, `OpenApiConfig`(태그) 없음, `docs/CONVENTION.md`·`auth-api/README.md`.
- 동작 변경: `POST /api/v1/auth/reissue` 에러에 `timestamp` 추가(승인), redirect/role 요청 `@Valid` 실효화(빈/null 거부).

## plan과의 차이
1. **응답 스키마 노출 보강**(§5.5) — plan엔 "필드별 @Schema 작성"만 있었으나, 와일드카드 반환 엔드포인트의 응답 example이 안 보여 docs 인터페이스 `@ApiResponse`에 `@Schema(implementation=...)`를 추가(사용자 승인).
2. **@Valid validation 실효화** — 코드리뷰 반영으로 redirect/role 요청에 `@Valid`+제약을 추가(기존 미동작 validation 실효화). 빈/null 입력을 400으로 거부.
3. ErrorResponse timestamp 통일 결정은 plan 작성 후 사용자 확정(전부 포함) → plan 3문서에 반영.

## 다음 단계
- /commit 으로 커밋 (또는 직접 커밋)
- /git-pr 로 PR 생성 (base: main)
- 로컬 스택 정리: `docker compose down -v` + 워크트리 `.env` 삭제
