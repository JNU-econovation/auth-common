# 11-swagger-dto-examples - todo

## 메타
- **작업명**: 11-swagger-dto-examples
- **문서 타입**: todo
- **작성일**: 2026-06-14
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요
`auth-api` 컨트롤러에 inner record/class로 정의된 요청·응답 DTO를 `presentation/dto/` 패키지의 표준 record로 추출하고, 모든 DTO 필드에 `@Schema(description=..., example=...)`를 추가하여 Swagger UI에 의미 있는 예제를 노출한다. `ErrorResponse`(3곳 중복)와 `RoleUpdateRequest`(2곳 중복)는 공용 DTO 하나로 통합한다. `@Hidden`인 `InternalRouteController`의 inner record는 현 상태를 유지한다. 동작 변경 없이 순수한 리팩터 + 문서 메타데이터 추가이며, 기존 테스트는 green을 유지한다.

## 본문

### API 작업
- 해당 없음 (엔드포인트 추가·변경·삭제 없음. 순수 presentation 계층 리팩터)

### 구현 작업

#### [공용 DTO 신규 생성]
- [ ] `presentation/dto/ErrorResponse.java` 신규 생성 — 필드: `String errorCode`, `String message`, `LocalDateTime timestamp`. `AdminClientController`, `AdminMemberController`, `AdminRoleController` 3곳에 동일하게 정의된 inner record를 이 하나로 대체한다. `@Schema(description, example)` 적용.
- [ ] `presentation/dto/RoleUpdateRequest.java` 신규 생성 — 필드: `@NotBlank String role`. `AdminMemberController`, `AdminRoleController` 2곳 inner record를 이 하나로 대체한다. `@Schema(description, example)` 적용.

#### [ClientController inner DTO 추출]
- [ ] `presentation/dto/SelfRegisterClientRequest.java` 신규 생성 — 필드: `@NotBlank String clientName`, `@NotNull Set<String> redirectUris`. `@Schema` 적용. 현재 `ClientController` inner record `SelfRegisterClientRequest` 대체.
- [ ] `presentation/dto/SelfRegisterClientResponse.java` 신규 생성 — 필드: `String clientId`, `String clientSecret`. `@Schema` 적용. 현재 `ClientController` inner record `SelfRegisterClientResponse` 대체.
- [ ] `ClientController` inner record `SelfRegisterClientRequest`, `SelfRegisterClientResponse` 제거하고 `presentation/dto` 패키지의 DTO로 교체. import 갱신.
- [ ] `ClientApiDocs`의 `SelfRegisterClientRequest` import를 컨트롤러 inner class에서 `presentation/dto`로 갱신.

#### [AdminClientController inner DTO 추출]
- [ ] `presentation/dto/RegisterClientRequest.java` 신규 생성 — 필드: `@NotBlank String clientName`, `Set<String> redirectUris`. `@Schema` 적용. 현재 `AdminClientController` inner record 대체.
- [ ] `presentation/dto/RegisterClientResponse.java` 신규 생성 — 필드: `String clientId`. `@Schema` 적용. 현재 `AdminClientController` inner record `RegisterClientResponse` 대체.
- [ ] `presentation/dto/RedirectUriRequest.java` 신규 생성 — 필드: `String uri`. `@Schema` 적용. 현재 `AdminClientController` inner record 대체.
- [ ] `presentation/dto/RedirectUrisReplaceRequest.java` 신규 생성 — 필드: `Set<String> uris`. `@Schema` 적용. 현재 `AdminClientController` inner record 대체.
- [ ] `AdminClientController` inner record `RegisterClientRequest`, `RegisterClientResponse`, `RedirectUriRequest`, `RedirectUrisReplaceRequest`, `ErrorResponse` 제거하고 `presentation/dto` 패키지의 DTO로 교체. `RedirectUrisResponse`·`ClientDetailResponse`는 컨트롤러 내부 응답 전용이므로 별도 판단 필요 (public으로 유지하거나 추출).
- [ ] `AdminClientApiDocs`의 import 경로를 컨트롤러 inner class에서 `presentation/dto`로 갱신.

#### [AdminMemberController inner DTO 추출]
- [ ] `presentation/dto/MemberSummary.java` 신규 생성 — 필드: `Long memberId`, `String name`, `String loginId`, `Integer generation`, `String status`, `String role`. `@Schema` 적용. `Member`에서 변환하는 정적 팩토리 `from(Member)` 포함.
- [ ] `presentation/dto/PagedMembersResponse.java` 신규 생성 — 필드: `List<MemberSummary> content`, `long totalElements`, `int totalPages`, `int page`, `int size`. `@Schema` 적용.
- [ ] `AdminMemberController` inner record `MemberSummary`, `PagedMembersResponse`, `RoleUpdateRequest`, `ErrorResponse` 제거하고 `presentation/dto` 패키지의 공용/신규 DTO로 교체. `MemberRoleResponse`(package-private)는 컨트롤러 내부 전용이므로 현상 유지 또는 추출 선택.
- [ ] `AdminMemberApiDocs`의 `RoleUpdateRequest` import를 컨트롤러 inner class에서 `presentation/dto`로 갱신.

#### [AdminRoleController inner DTO 제거]
- [ ] `AdminRoleController` inner record `RoleUpdateRequest`, `ErrorResponse` 제거하고 `presentation/dto`의 공용 DTO로 교체. (`AdminRoleController`는 `@Hidden`이므로 docs 인터페이스 없음 — 컨트롤러 참조만 수정)

#### [ReissueController inner DTO 추출]
- [ ] `presentation/dto/ReissueRequest.java` 신규 생성 — 필드: `String refreshToken`. `@Schema` 적용. 현재 `ReissueController` inner record `ReissueRequest` 대체.
- [ ] `ReissueController` inner record `ReissueRequest` 제거, `presentation/dto`의 DTO로 교체. inner `ErrorResponse`(2필드)도 제거하고 **공용 `ErrorResponse`(3필드, timestamp 포함)로 교체** — 사용자 확정: 전부 timestamp 포함 통일(Reissue 에러도 timestamp 포함, 승인된 소폭 동작 변경).
- [ ] `ReissueApiDocs`의 `ReissueRequest` import를 컨트롤러 inner class에서 `presentation/dto`로 갱신.

#### [RootController inner DTO 추출]
- [ ] `presentation/dto/HealthResponse.java` 신규 생성 — 필드: `String application`, `String startedAt`, `String uptime`. `@Schema` 적용. 현재 `RootController` inner record `HealthResponse` 대체.
- [ ] `RootController` inner record `HealthResponse` 제거, `presentation/dto`의 DTO로 교체.
- [ ] `RootApiDocs`의 `HealthResponse` import를 컨트롤러 inner class에서 `presentation/dto`로 갱신.

#### [MemberInfoController inner DTO 추출]
- [ ] `presentation/dto/MemberQueryRequest.java` 신규 생성 — 필드: `@NotEmpty @Size(max=1000) List<Long> ids`. `@Schema` 적용. 현재 `MemberInfoController` inner record `MemberQueryRequest` 대체.
- [ ] `presentation/dto/MemberInfoResponse.java` 신규 생성 — 필드: `Long memberId`, `String name`, `String loginId`, `Integer generation`, `String status`. `@Schema` 적용. `from(Member)` 정적 팩토리 포함.
- [ ] `MemberInfoController` inner record `MemberQueryRequest`, `MemberInfoResponse` 제거, `presentation/dto`의 DTO로 교체.
- [ ] `MemberInfoApiDocs`의 `MemberQueryRequest`, `MemberInfoResponse` import를 컨트롤러 inner class에서 `presentation/dto`로 갱신.

#### [기존 추출 DTO에 @Schema 보강]
- [ ] `presentation/dto/SignupRequest.java`에 각 필드(`name`, `loginId`, `password`, `generation`, `status`)에 `@Schema(description=..., example=...)` 추가.
- [ ] `presentation/dto/CreateRouteRequest.java`에 각 필드(`pathPrefix`, `upstreamUrl`, `enabled`)에 `@Schema` 추가.
- [ ] `presentation/dto/UpdateRouteRequest.java`에 각 필드(`pathPrefix`, `upstreamUrl`, `enabled`)에 `@Schema` 추가.
- [ ] `presentation/dto/RouteResponse.java`에 각 필드(`routeId`, `pathPrefix`, `upstreamUrl`, `enabled`, `createdAt`, `updatedAt`)에 `@Schema` 추가.
- [ ] `presentation/dto/RouteListResponse.java`에 `routes` 필드에 `@Schema` 추가 (클래스 레벨 description 포함).
- [ ] `presentation/dto/LoginResponse.java`에 각 필드(`accessToken`, `accessExpiredTime`, `refreshToken`, `redirectUrl`)에 `@Schema` 추가.

#### [테스트 import 갱신]
- [ ] `AdminClientControllerTest`, `AdminMemberControllerTest`, `AdminRoleControllerTest`, `ClientControllerTest`, `AdminRouteControllerTest` 등 테스트 파일에서 컨트롤러 inner class를 참조하는 import를 `presentation/dto` 경로로 갱신한다. (테스트 로직 자체는 변경 없음)

### DB 작업
- 해당 없음 (presentation 계층 한정 변경. DB 스키마·마이그레이션·엔티티 변경 없음)

### 기타 작업
- [ ] `./gradlew format` 실행하여 Spotless 포맷 통과 확인 — 새로 생성한 모든 DTO record에 Google Java Format 적용.
- [ ] `./gradlew :services:apis:auth-api:test` 실행하여 기존 테스트 전체 green 확인 — DTO 추출로 인한 import 변경이 컴파일·런타임 오류를 유발하지 않음을 검증.
- [ ] `./gradlew spotlessCheck` 통과 확인 (pre-commit 훅 통과 기준).

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

## 참고
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/CONVENTION.md` — §1.1(패키지), §1.2(클래스), §2.4(JSON), §2.5(Validation), §8(Swagger)
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/ARCHITECTURE.md` — 계층 설계, 의존성 불변식
- 컨트롤러 파일들 (inner DTO 소스):
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ClientController.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminClientController.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminMemberController.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminRoleController.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ReissueController.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/RootController.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/MemberInfoController.java`
- docs 인터페이스 (import 갱신 대상):
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ClientApiDocs.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/AdminClientApiDocs.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/AdminMemberApiDocs.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ReissueApiDocs.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/RootApiDocs.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/MemberInfoApiDocs.java`
- 기존 추출 DTO (`@Schema` 보강 대상):
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/SignupRequest.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/CreateRouteRequest.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/UpdateRouteRequest.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/RouteResponse.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/RouteListResponse.java`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/LoginResponse.java`

### 주의사항 (후속 작업자 필독)

**ReissueController.ErrorResponse 처리 (사용자 확정)**: **전부 timestamp 포함으로 통일.** `ReissueController` 내부 2필드 `ErrorResponse`를 제거하고 공용 `ErrorResponse`(`{errorCode, message, timestamp}`)로 교체한다. 기존 Reissue 에러에 없던 timestamp가 포함되는 소폭 동작 변경을 승인했다. 공용 `ErrorResponse`는 `@JsonInclude(NON_NULL)` 없이 timestamp를 항상 포함하며, `new ErrorResponse(code, message)` 2인자 편의 생성자가 `timestamp = LocalDateTime.now()`를 채운다.

**`AdminClientController` 응답 전용 DTO**: `RedirectUrisResponse`와 `ClientDetailResponse`는 현재 package-private(컨트롤러 외부에서 참조 없음). Swagger schema 노출이 필요하다면 추출하고 public + `@Schema` 적용, 필요 없다면 package-private 유지.
