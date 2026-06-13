# 11-swagger-dto-examples - implementation

## 메타
- **작업명**: 11-swagger-dto-examples
- **문서 타입**: implementation
- **작성일**: 2026-06-14
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

`auth-api` 컨트롤러의 inner record/class로 정의된 요청·응답 DTO를 `presentation/dto/` 패키지의 표준 record로 추출하고, 모든 DTO 필드에 `@Schema(description, example)`을 추가하여 Swagger UI에 의미 있는 예제를 노출한다. `ErrorResponse`(3곳 중복)와 `RoleUpdateRequest`(2곳 중복)를 공용 DTO 하나로 통합하며, `@Hidden`인 `InternalRouteController` inner record는 현 상태를 유지한다. Java 21 + Spring Boot 3.2.2 기반 `services/apis/auth-api` 단일 모듈 내 `presentation` 계층 한정 순수 리팩터 + 문서 메타데이터 추가이며, DB 변경 없음.

---

## 판단 포인트 해소

### ReissueController.ErrorResponse 처리 결정 (사용자 확정)

**전부 timestamp 포함으로 통일한다.** 공용 `ErrorResponse(String errorCode, String message, LocalDateTime timestamp)` 하나로 통합하고, `ReissueController`도 이 공용 DTO를 사용하여 항상 `timestamp`를 포함한다. `@JsonInclude(NON_NULL)`·nullable timestamp 트릭은 쓰지 않는다.

- 4개 컨트롤러(`AdminClientController`, `AdminMemberController`, `AdminRoleController`, `ReissueController`) 모두 `new ErrorResponse(code, message, LocalDateTime.now())`로 생성. 편의를 위해 `public ErrorResponse(String errorCode, String message) { this(errorCode, message, LocalDateTime.now()); }` 2인자 생성자를 제공할 수 있다(timestamp는 항상 채워짐).
- **소폭 동작 변경 수용**: 기존 `ReissueController` 에러 JSON에는 `timestamp`가 없었으나, 이제 `{errorCode, message, timestamp}` 3필드로 통일된다(사용자 승인). 일관된 에러 스키마 확보가 목적.

### AdminClientController 응답 전용 DTO 처리 결정

`RedirectUrisResponse(String clientId, Set<String> redirectUris)`와 `ClientDetailResponse(String clientId, String clientName, Set<String> redirectUris)`는 현재 package-private(컨트롤러 외부에서 참조 없음). 두 DTO는 Swagger 응답 스키마로 노출할 가치가 있으므로 **`presentation/dto/`로 추출하고 `@Schema` 적용**한다. `@WebMvcTest` 슬라이스 테스트에서 이 타입들을 직접 import하지 않으므로 추출해도 테스트 영향 없음.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/` | 신규 파일 13개 | 컨트롤러 inner DTO 추출 대상 패키지 (CONVENTION §1.1 `presentation/dto`) |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/` | 변경 파일 6개 | 기존 DTO에 `@Schema` 보강 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/` | 변경 7파일 | inner DTO 제거, `presentation/dto` import로 교체 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/` | 변경 6파일 | import 경로를 컨트롤러 inner class → `presentation/dto`로 갱신 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `presentation/dto/`

```
presentation/dto/
├── ErrorResponse.java              — 공용 에러 응답 (3컨트롤러 중복 통합 + ReissueController 통합)
├── RoleUpdateRequest.java          — 공용 역할 변경 요청 (2컨트롤러 중복 통합)
├── SelfRegisterClientRequest.java  — ClientController inner record 추출
├── SelfRegisterClientResponse.java — ClientController inner record 추출
├── RegisterClientRequest.java      — AdminClientController inner record 추출
├── RegisterClientResponse.java     — AdminClientController inner record 추출
├── RedirectUriRequest.java         — AdminClientController inner record 추출
├── RedirectUrisReplaceRequest.java — AdminClientController inner record 추출
├── RedirectUrisResponse.java       — AdminClientController package-private record 추출
├── ClientDetailResponse.java       — AdminClientController package-private record 추출
├── MemberSummary.java              — AdminMemberController inner record 추출
├── PagedMembersResponse.java       — AdminMemberController inner record 추출
├── ReissueRequest.java             — ReissueController inner record 추출
├── HealthResponse.java             — RootController inner record 추출
├── MemberQueryRequest.java         — MemberInfoController inner record 추출
├── MemberInfoResponse.java         — MemberInfoController inner record 추출
├── SignupRequest.java              — (기존) @Schema 보강
├── LoginResponse.java              — (기존) @Schema 보강
├── CreateRouteRequest.java         — (기존) @Schema 보강
├── UpdateRouteRequest.java         — (기존) @Schema 보강
├── RouteResponse.java              — (기존) @Schema 보강
└── RouteListResponse.java          — (기존) @Schema 보강
```

---

##### ErrorResponse (공용 — 신규)
- **타입**: 공용 응답 DTO (record)
- **책임**: 에러 코드·메시지·타임스탬프를 담는 공용 에러 응답. `timestamp`는 **항상 포함**(4개 컨트롤러 통일).
- **필드**:
  - `String errorCode` — `@Schema(description = "에러 코드", example = "INVALID_ROLE")`
  - `String message` — `@Schema(description = "에러 메시지", example = "유효하지 않은 역할입니다.")`
  - `LocalDateTime timestamp` — `@Schema(description = "에러 발생 시각", example = "2026-06-14T10:00:00")`
- **핵심 구현**:
  - `timestamp`는 nullable 아님 — `@JsonInclude(NON_NULL)` 미사용. 모든 에러 응답이 동일하게 `{errorCode, message, timestamp}`.
  - 2인자 편의 생성자: `public ErrorResponse(String errorCode, String message) { this(errorCode, message, LocalDateTime.now()); }` — timestamp 자동 충전(항상 값 있음).
  - `ReissueController`도 이 공용 DTO 사용 → 기존 timestamp 없던 응답이 timestamp 포함으로 통일(사용자 승인한 소폭 동작 변경).
- **의존성**: `java.time.LocalDateTime`
- **적용 컨벤션**:
  - CONVENTION §8.7 응답 타입: Map 대신 DTO record 사용
- **참조할 기존 코드**:
  - `AdminMemberController:71-75` (3필드 + 2인자 생성자 패턴)
- **연관 todo**:
  - `[ ] presentation/dto/ErrorResponse.java 신규 생성`
  - `[ ] AdminClientController inner record ErrorResponse 제거`
  - `[ ] AdminMemberController inner record ErrorResponse 제거`
  - `[ ] AdminRoleController inner record ErrorResponse 제거`
  - `[ ] ReissueController inner record ErrorResponse 제거`

---

##### RoleUpdateRequest (공용 — 신규)
- **타입**: 요청 DTO (record)
- **책임**: 역할 변경 엔드포인트 공용 요청 DTO.
- **필드**:
  - `@NotBlank String role` — `@Schema(description = "변경할 역할", example = "ADMIN")`
- **의존성**: `jakarta.validation.constraints.NotBlank`
- **적용 컨벤션**:
  - CONVENTION §2.5 Validation: `@NotBlank` 선언
  - CONVENTION §1.2 클래스 네이밍: 역할을 접미사로 표현
- **참조할 기존 코드**: `AdminMemberController:69` (`public record RoleUpdateRequest(@NotBlank String role) {}`)
- **연관 todo**:
  - `[ ] presentation/dto/RoleUpdateRequest.java 신규 생성`
  - `[ ] AdminMemberController inner record RoleUpdateRequest 제거`
  - `[ ] AdminRoleController inner record RoleUpdateRequest 제거`

---

##### SelfRegisterClientRequest (신규)
- **타입**: 요청 DTO (record)
- **책임**: 셀프 OAuth 클라이언트 등록 요청.
- **필드**:
  - `@NotBlank String clientName` — `@Schema(description = "OAuth 클라이언트 이름", example = "에코노 SPA")`
  - `@NotNull Set<String> redirectUris` — `@Schema(description = "허용할 리다이렉트 URI 목록", example = "[\"http://localhost:3000/callback\"]")`
- **의존성**: `jakarta.validation.constraints.NotBlank`, `jakarta.validation.constraints.NotNull`, `java.util.Set`
- **참조할 기존 코드**: `ClientController:37-38`
- **연관 todo**: `[ ] presentation/dto/SelfRegisterClientRequest.java 신규 생성`

---

##### SelfRegisterClientResponse (신규)
- **타입**: 응답 DTO (record)
- **책임**: 셀프 등록 결과 — clientId와 1회성 clientSecret 반환.
- **필드**:
  - `String clientId` — `@Schema(description = "발급된 OAuth 클라이언트 ID (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")`
  - `String clientSecret` — `@Schema(description = "1회 반환되는 클라이언트 시크릿 (재조회 불가)", example = "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")`
- **참조할 기존 코드**: `ClientController:41`
- **연관 todo**: `[ ] presentation/dto/SelfRegisterClientResponse.java 신규 생성`

---

##### RegisterClientRequest (신규)
- **타입**: 요청 DTO (record)
- **책임**: 어드민 OAuth 클라이언트 등록 요청.
- **필드**:
  - `@NotBlank String clientName` — `@Schema(description = "OAuth 클라이언트 이름", example = "에코노 SPA")`
  - `Set<String> redirectUris` — `@Schema(description = "허용할 리다이렉트 URI 목록", example = "[\"http://localhost:3000/callback\"]")`
- **참조할 기존 코드**: `AdminClientController:42`
- **연관 todo**: `[ ] presentation/dto/RegisterClientRequest.java 신규 생성`

---

##### RegisterClientResponse (신규)
- **타입**: 응답 DTO (record)
- **책임**: 어드민 등록 결과 — clientId만 반환 (시크릿 발급 없음).
- **필드**:
  - `String clientId` — `@Schema(description = "발급된 OAuth 클라이언트 ID (UUID)", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")`
- **참조할 기존 코드**: `AdminClientController:44`
- **연관 todo**: `[ ] presentation/dto/RegisterClientResponse.java 신규 생성`

---

##### RedirectUriRequest (신규)
- **타입**: 요청 DTO (record)
- **책임**: redirectUri 단건 추가/제거 요청.
- **필드**:
  - `String uri` — `@Schema(description = "추가 또는 제거할 리다이렉트 URI", example = "https://app.example.com/callback")`
- **참조할 기존 코드**: `AdminClientController:106`
- **연관 todo**: `[ ] presentation/dto/RedirectUriRequest.java 신규 생성`

---

##### RedirectUrisReplaceRequest (신규)
- **타입**: 요청 DTO (record)
- **책임**: redirectUri 전체 교체 요청.
- **필드**:
  - `Set<String> uris` — `@Schema(description = "새로 설정할 리다이렉트 URI 전체 목록", example = "[\"https://app.example.com/callback\", \"https://staging.example.com/callback\"]")`
- **참조할 기존 코드**: `AdminClientController:108`
- **연관 todo**: `[ ] presentation/dto/RedirectUrisReplaceRequest.java 신규 생성`

---

##### RedirectUrisResponse (신규)
- **타입**: 응답 DTO (record, package-private → public 승격)
- **책임**: redirectUri 추가/제거/교체 후 클라이언트 ID와 최신 URI 목록 반환.
- **필드**:
  - `String clientId` — `@Schema(description = "OAuth 클라이언트 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")`
  - `Set<String> redirectUris` — `@Schema(description = "변경 후 전체 리다이렉트 URI 목록", example = "[\"https://app.example.com/callback\"]")`
- **참조할 기존 코드**: `AdminClientController:110`
- **연관 todo**: `[ ] AdminClientController package-private RedirectUrisResponse 추출`

---

##### ClientDetailResponse (신규)
- **타입**: 응답 DTO (record, package-private → public 승격)
- **책임**: 클라이언트 상세 조회 응답.
- **필드**:
  - `String clientId` — `@Schema(description = "OAuth 클라이언트 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")`
  - `String clientName` — `@Schema(description = "OAuth 클라이언트 이름", example = "에코노 SPA")`
  - `Set<String> redirectUris` — `@Schema(description = "등록된 리다이렉트 URI 목록", example = "[\"https://app.example.com/callback\"]")`
- **참조할 기존 코드**: `AdminClientController:112`
- **연관 todo**: `[ ] AdminClientController package-private ClientDetailResponse 추출`

---

##### MemberSummary (신규)
- **타입**: 응답 DTO (record)
- **책임**: 어드민 회원 목록의 단건 요약 정보. `Member` 도메인 → DTO 변환 정적 팩토리 포함.
- **필드**:
  - `Long memberId` — `@Schema(description = "회원 PK", example = "42")`
  - `String name` — `@Schema(description = "이름", example = "홍길동")`
  - `String loginId` — `@Schema(description = "로그인 아이디", example = "hong42")`
  - `Integer generation` — `@Schema(description = "기수", example = "30")`
  - `String status` — `@Schema(description = "활동 상태 (AM/RM/CM/OB)", example = "AM")`
  - `String role` — `@Schema(description = "역할 (USER/ADMIN/SUPER_ADMIN)", example = "USER")`
- **주요 메서드**: `public static MemberSummary from(Member m)` — Member 도메인을 DTO로 변환 (presentation이 domain Member 참조하는 현 패턴 유지)
- **의존성**: `com.econo.auth.member.application.domain.Member`
- **적용 컨벤션**:
  - CONVENTION §1.1: `presentation/dto`에 위치, domain Member 참조는 presentation 계층이 application.domain을 참조하는 허용 패턴 (DIP 불변식 내)
  - CONVENTION §1.3: 정적 팩토리이므로 `from` 접두사
- **참조할 기존 코드**: `AdminMemberController:52-64`
- **연관 todo**: `[ ] presentation/dto/MemberSummary.java 신규 생성`

---

##### PagedMembersResponse (신규)
- **타입**: 응답 DTO (record)
- **책임**: 어드민 회원 목록 페이지 응답.
- **필드**:
  - `List<MemberSummary> content` — `@Schema(description = "현재 페이지 회원 목록")`
  - `long totalElements` — `@Schema(description = "전체 회원 수", example = "150")`
  - `int totalPages` — `@Schema(description = "전체 페이지 수", example = "8")`
  - `int page` — `@Schema(description = "현재 페이지 번호 (0-based)", example = "0")`
  - `int size` — `@Schema(description = "페이지 크기", example = "20")`
- **의존성**: `java.util.List`, `MemberSummary`(동일 패키지)
- **참조할 기존 코드**: `AdminMemberController:66-67`
- **연관 todo**: `[ ] presentation/dto/PagedMembersResponse.java 신규 생성`

---

##### ReissueRequest (신규)
- **타입**: 요청 DTO (record)
- **책임**: APP 클라이언트용 리프레시 토큰 재발급 요청.
- **필드**:
  - `String refreshToken` — `@Schema(description = "APP 클라이언트가 바디로 전달하는 Refresh Token. WEB은 쿠키 사용이므로 null 허용.", example = "eyJhbGciOiJSUzI1NiJ9...")`
- **참조할 기존 코드**: `ReissueController:94`
- **연관 todo**: `[ ] presentation/dto/ReissueRequest.java 신규 생성`

---

##### HealthResponse (신규)
- **타입**: 응답 DTO (record)
- **책임**: 루트 헬스체크 응답 — 애플리케이션 이름, 기동 시각, uptime.
- **필드**:
  - `String application` — `@Schema(description = "애플리케이션 이름", example = "auth-api")`
  - `String startedAt` — `@Schema(description = "기동 시각 (ISO-8601)", example = "2026-06-14T09:00:00.000Z")`
  - `String uptime` — `@Schema(description = "가동 시간", example = "0일 1시간 17분 47초")`
- **참조할 기존 코드**: `RootController:45`
- **연관 todo**: `[ ] presentation/dto/HealthResponse.java 신규 생성`

---

##### MemberQueryRequest (신규)
- **타입**: 요청 DTO (record)
- **책임**: 단건/다건 회원 조회 요청.
- **필드**:
  - `@NotEmpty @Size(max = 1000) List<Long> ids` — `@Schema(description = "조회할 회원 ID 목록 (1개 이상, 최대 1000개)", example = "[1, 2, 42]")`
- **의존성**: `jakarta.validation.constraints.NotEmpty`, `jakarta.validation.constraints.Size`, `java.util.List`
- **참조할 기존 코드**: `MemberInfoController:58-61`
- **연관 todo**: `[ ] presentation/dto/MemberQueryRequest.java 신규 생성`

---

##### MemberInfoResponse (신규)
- **타입**: 응답 DTO (record)
- **책임**: 회원 정보 응답 단건. `Member` → DTO 변환 정적 팩토리 포함.
- **필드**:
  - `Long memberId` — `@Schema(description = "회원 PK", example = "42")`
  - `String name` — `@Schema(description = "이름", example = "홍길동")`
  - `String loginId` — `@Schema(description = "로그인 아이디", example = "hong42")`
  - `Integer generation` — `@Schema(description = "기수", example = "30")`
  - `String status` — `@Schema(description = "활동 상태 (AM/RM/CM/OB)", example = "AM")`
- **주요 메서드**: `public static MemberInfoResponse from(Member member)` — Member 도메인 → DTO 변환
- **의존성**: `com.econo.auth.member.application.domain.Member`
- **참조할 기존 코드**: `MemberInfoController:72-83`
- **연관 todo**: `[ ] presentation/dto/MemberInfoResponse.java 신규 생성`

---

##### SignupRequest (기존 — @Schema 보강)
- **변경 내용**: 각 필드에 `@Schema(description, example)` 추가. 기존 validation 어노테이션 유지.
- **추가할 @Schema**:
  - `name` — `@Schema(description = "이름", example = "홍길동")`
  - `loginId` — `@Schema(description = "로그인 아이디 (영문·숫자·-_.만 허용, 3~19자)", example = "hong42")`
  - `password` — `@Schema(description = "비밀번호 (8~19자)", example = "P@ssword1")`
  - `generation` — `@Schema(description = "기수 (1~99)", example = "30")`
  - `status` — `@Schema(description = "활동 상태 (AM/RM/CM/OB)", example = "AM")`
- **참조할 기존 코드**: `SignupRequest.java:12-17`
- **연관 todo**: `[ ] presentation/dto/SignupRequest.java @Schema 보강`

---

##### LoginResponse (기존 — @Schema 보강)
- **변경 내용**: 각 필드에 `@Schema(description, example)` 추가. 기존 `@JsonInclude`, 정적 팩토리 유지.
- **추가할 @Schema**:
  - `accessToken` — `@Schema(description = "Access Token (JWT). WEB에서는 쿠키로 전달되므로 null.", nullable = true, example = "eyJhbGciOiJSUzI1NiJ9...")`
  - `accessExpiredTime` — `@Schema(description = "Access Token 만료 시각 (epoch millis)", example = "1749902400000")`
  - `refreshToken` — `@Schema(description = "Refresh Token. WEB에서는 쿠키로 전달되므로 null.", nullable = true, example = "eyJhbGciOiJSUzI1NiJ9...")`
  - `redirectUrl` — `@Schema(description = "로그인 후 이동 목적지 URL (nullable)", nullable = true, example = "https://app.example.com/dashboard")`
- **연관 todo**: `[ ] presentation/dto/LoginResponse.java @Schema 보강`

---

##### CreateRouteRequest (기존 — @Schema 보강)
- **추가할 @Schema**:
  - `pathPrefix` — `@Schema(description = "게이트웨이 라우팅 경로 접두사", example = "/api/v1/myservice")`
  - `upstreamUrl` — `@Schema(description = "업스트림 서비스 URL (SSRF 검증 대상)", example = "http://myservice:8080")`
  - `enabled` — `@Schema(description = "라우트 활성화 여부", example = "true")`
- **연관 todo**: `[ ] presentation/dto/CreateRouteRequest.java @Schema 보강`

---

##### UpdateRouteRequest (기존 — @Schema 보강)
- **추가할 @Schema**: CreateRouteRequest와 동일 패턴 적용.
- **연관 todo**: `[ ] presentation/dto/UpdateRouteRequest.java @Schema 보강`

---

##### RouteResponse (기존 — @Schema 보강)
- **추가할 @Schema**:
  - `routeId` — `@Schema(description = "라우트 UUID", example = "550e8400-e29b-41d4-a716-446655440000")`
  - `pathPrefix` — `@Schema(description = "경로 접두사", example = "/api/v1/myservice")`
  - `upstreamUrl` — `@Schema(description = "업스트림 서비스 URL", example = "http://myservice:8080")`
  - `enabled` — `@Schema(description = "활성화 여부", example = "true")`
  - `createdAt` — `@Schema(description = "생성 시각", example = "2026-06-14T10:00:00")`
  - `updatedAt` — `@Schema(description = "최종 수정 시각", example = "2026-06-14T11:30:00")`
- **연관 todo**: `[ ] presentation/dto/RouteResponse.java @Schema 보강`

---

##### RouteListResponse (기존 — @Schema 보강)
- **추가할 @Schema**:
  - 클래스 레벨: `@Schema(description = "전체 라우트 목록 응답")`
  - `routes` — `@Schema(description = "등록된 라우트 목록")`
- **연관 todo**: `[ ] presentation/dto/RouteListResponse.java @Schema 보강`

---

#### 모듈 / 패키지: `presentation/controller/` (변경)

각 컨트롤러에서 inner DTO를 제거하고 `presentation/dto` 패키지 import로 교체한다. 메서드 시그니처와 비즈니스 로직은 변경 없음.

| 컨트롤러 | 제거할 inner DTO | 대체 import |
|---|---|---|
| `ClientController` | `SelfRegisterClientRequest`, `SelfRegisterClientResponse` | `dto.SelfRegisterClientRequest`, `dto.SelfRegisterClientResponse` |
| `AdminClientController` | `RegisterClientRequest`, `RegisterClientResponse`, `RedirectUriRequest`, `RedirectUrisReplaceRequest`, `RedirectUrisResponse`, `ClientDetailResponse`, `ErrorResponse` | 동명 `dto.*` |
| `AdminMemberController` | `MemberSummary`, `PagedMembersResponse`, `RoleUpdateRequest`, `ErrorResponse` | 동명 `dto.*` (`MemberRoleResponse`는 컨트롤러 내부 전용이므로 유지) |
| `AdminRoleController` | `RoleUpdateRequest`, `ErrorResponse` | 동명 `dto.*` |
| `ReissueController` | `ReissueRequest`, `ErrorResponse` | 동명 `dto.*` |
| `RootController` | `HealthResponse` | `dto.HealthResponse` |
| `MemberInfoController` | `MemberQueryRequest`, `MemberInfoResponse` | 동명 `dto.*` |

**참고**: `AdminMemberController`의 `MemberRoleResponse(Long memberId, String role)`는 `updateRole` 메서드의 성공 응답 전용 DTO이며 docs 인터페이스에 노출되지 않아 package-private 유지.

---

#### 모듈 / 패키지: `presentation/docs/` (변경)

docs 인터페이스에서 컨트롤러 inner class 참조를 `presentation/dto` 경로로 갱신한다.

| 인터페이스 | 변경할 import |
|---|---|
| `ClientApiDocs` | `ClientController.SelfRegisterClientRequest` → `dto.SelfRegisterClientRequest` |
| `AdminClientApiDocs` | `AdminClientController.{RegisterClientRequest, RedirectUriRequest, RedirectUrisReplaceRequest}` → 동명 `dto.*` |
| `AdminMemberApiDocs` | `AdminMemberController.RoleUpdateRequest` → `dto.RoleUpdateRequest` |
| `ReissueApiDocs` | `ReissueController.ReissueRequest` → `dto.ReissueRequest` |
| `RootApiDocs` | `RootController.HealthResponse` → `dto.HealthResponse` |
| `MemberInfoApiDocs` | `MemberInfoController.{MemberQueryRequest, MemberInfoResponse}` → 동명 `dto.*` |

**참고**: `AdminRoleController`는 `@Hidden`이므로 docs 인터페이스가 없다. 컨트롤러 참조만 교체.

---

### 호출 흐름

#### 정상 경로 (DTO 추출 후 — 동작 불변)

```
클라이언트 HTTP 요청
  → Spring MVC 역직렬화 (presentation/dto/XxxRequest record)
  → @Valid 검증
  → 컨트롤러 메서드 (@Override from ApiDocs)
  → UseCase 호출 (application/usecase)
  → 응답 생성 (presentation/dto/XxxResponse record)
  → JSON 직렬화 (Jackson, @JsonInclude 적용)
  → HTTP 응답
```

#### 예외 / 실패 경로

```
[Bean Validation 실패]
  → MethodArgumentNotValidException
  → GlobalExceptionHandler (@ControllerAdvice)
  → 400 VALIDATION_FAILED

[컨트롤러 인라인 validation 실패 (역할 검증 등)]
  → 컨트롤러 내 if 분기
  → new ErrorResponse("CODE", "message")  ← 공용 dto.ErrorResponse 2인자 편의 생성자
  → timestamp 포함 직렬화 ({errorCode, message, timestamp})
  → 4xx HTTP 응답

[ReissueController 에러]
  → new ErrorResponse("CODE", "message")  ← 공용 dto.ErrorResponse 2인자 편의 생성자
  → 편의 생성자가 this(code, message, LocalDateTime.now())로 timestamp 자동 충전
  → {errorCode, message, timestamp} 3필드로 직렬화
  ⚠️ 동작 변화(사용자 승인): 기존 ReissueController 에러엔 timestamp가 없었으나,
     공용 통합 후 timestamp가 포함된다. 4개 컨트롤러 에러 스키마를 일관되게 통일하는 것이 목적.
     (필드 추가는 하위호환 breaking 아님)
```

#### 공용 ErrorResponse 생성자 설계

```
공용 ErrorResponse 생성자:
  - new ErrorResponse(code, message, LocalDateTime.now())  — 명시적 3인자
  - new ErrorResponse(code, message)                        — this(code, message, LocalDateTime.now()) 위임(편의)
  ※ timestamp는 항상 채워짐. @JsonInclude(NON_NULL) 미사용 — 모든 에러 응답이 동일하게 3필드.
```

**결론**: 4개 컨트롤러(AdminClient/Member/Role/Reissue) 모두 공용 `ErrorResponse`(3필드, timestamp 항상 포함)를 사용한다. 생성자 분리·null 위임 없음.

---

### 컨벤션 준수 항목

- **네이밍**: record 이름 PascalCase + 역할 접미사 (Request/Response). 패키지 `presentation/dto` 소문자 연결. (CONVENTION §1.1, §1.2)
- **JSON 직렬화**: `ErrorResponse`는 timestamp를 항상 포함(`@JsonInclude(NON_NULL)` 미사용). `LocalDateTime`은 기존 `jackson-datatype-jsr310` 설정으로 ISO-8601 직렬화. (CONVENTION §2.4)
- **Validation**: `@NotBlank`, `@NotNull`, `@NotEmpty`, `@Size` 등 Jakarta Bean Validation 선언. (CONVENTION §2.5)
- **Swagger 분리**: 컨트롤러는 `@Override` + Spring 웹 어노테이션만, Swagger 어노테이션은 `presentation/docs/` 인터페이스에만. (CONVENTION §8.1)
- **`@Schema` 적용**: 클래스 레벨은 선택, 필드 레벨 `@Schema(description, example)` 필수. nullable 필드는 `nullable = true` 추가. (CONVENTION §8.7)
- **의존성 방향**: presentation/dto가 application.domain(Member)를 참조하는 것은 `from(Member)` 정적 팩토리 패턴으로 허용 (기존 MemberInfoController, AdminMemberController 패턴 동일). (CONVENTION §1.1 의존성 방향 규칙)
- **포맷**: `./gradlew format` 실행 필수. (CONVENTION §2.1)
- **테스트**: 기존 `@WebMvcTest` 슬라이스 테스트는 JSON 문자열로 요청하고 jsonPath로 검증하므로 inner DTO를 직접 import하지 않아 **테스트 코드 변경 불필요**.

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨 (`presentation/dto`)
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두)
- [x] ReissueController.ErrorResponse 통합 방법 결정 및 명시 (공용 ErrorResponse 사용, timestamp 항상 포함, @JsonInclude 미사용 — 사용자 확정)
- [x] AdminClientController 응답 전용 DTO 처리 결정 (추출 + public + `@Schema`)
- [x] 테스트 코드 변경 불필요 확인 (JSON 문자열 기반 테스트)

---

## 참고
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/CONVENTION.md` — §1.1, §1.2, §2.1, §2.4, §2.5, §8
- 컨트롤러 inner DTO 소스:
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ClientController.java:37-41`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminClientController.java:42-112`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminMemberController.java:52-75`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminRoleController.java:35-41`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ReissueController.java:94-96`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/RootController.java:45`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/MemberInfoController.java:58-83`
- docs 인터페이스:
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ClientApiDocs.java:3`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/AdminClientApiDocs.java:3-5`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/AdminMemberApiDocs.java:3`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ReissueApiDocs.java:3`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/RootApiDocs.java:3`
  - `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/MemberInfoApiDocs.java:3-4`
- 기존 `@JsonInclude(NON_NULL)` 활용 패턴: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/LoginResponse.java:17`
