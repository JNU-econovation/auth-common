# 11-swagger-dto-examples - api-design

## 메타
- **작업명**: 11-swagger-dto-examples
- **문서 타입**: api-design
- **작성일**: 2026-06-14
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

이 문서는 기존 엔드포인트의 동작을 변경하지 않으면서, 컨트롤러 inner record로 흩어진 DTO를 `presentation/dto/` 표준 record로 추출하고 모든 필드에 `@Schema(description, example)`를 부여하는 순수 리팩터의 DTO 스키마를 명세한다. REST/JSON 프로토콜, springdoc-openapi + `cookieAuth`(apiKey in cookie `at`) 보안 스킴을 따른다. 신규 엔드포인트는 없으며, 이 문서의 핵심은 각 엔드포인트별로 추출할 DTO 이름, 필드 목록, 필드별 `description`·`example`·validation 제약을 확정하는 것이다.

---

## 본문

### 엔드포인트 목록

| 메서드 | 경로 | 태그 | 인증 / 권한 | 요청 DTO (추출 후) | 응답 DTO (추출 후) | 연관 todo |
|--------|------|------|-------------|--------------------|--------------------|-----------|
| POST | /api/v1/auth/signup | Auth | 없음 (public) | `SignupRequest` (기존, @Schema 보강) | 없음 (201 empty) | @Schema 보강 #1 |
| POST | /api/v1/auth/login | Auth | 없음 (필터 처리) | (OpenApiCustomizer 직접 정의) | `LoginResponse` (기존, @Schema 보강) | @Schema 보강 #2 |
| POST | /api/v1/auth/reissue | Auth | 없음 (RT 자체 검증) | `ReissueRequest` (추출) | `LoginResponse` (재사용) | 추출 #1 |
| POST | /api/v1/auth/logout | Auth | 없음 | 없음 | 없음 (200 empty) | — |
| POST | /api/v1/clients | Client | cookieAuth (인증된 회원 누구나) | `SelfRegisterClientRequest` (추출) | `SelfRegisterClientResponse` (추출) | 추출 #2 |
| POST | /api/v1/admin/clients | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | `RegisterClientRequest` (추출) | `RegisterClientResponse` (추출) | 추출 #3 |
| GET | /api/v1/admin/clients/{clientId} | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | 없음 | `ClientDetailResponse` (추출 결정) | 추출 #4 |
| POST | /api/v1/admin/clients/{clientId}/redirect-uris | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | `RedirectUriRequest` (추출) | `RedirectUrisResponse` (추출 결정) | 추출 #5 |
| DELETE | /api/v1/admin/clients/{clientId}/redirect-uris | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | `RedirectUriRequest` (재사용) | `RedirectUrisResponse` (재사용) | 추출 #5 |
| PUT | /api/v1/admin/clients/{clientId}/redirect-uris | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | `RedirectUrisReplaceRequest` (추출) | `RedirectUrisResponse` (재사용) | 추출 #6 |
| GET | /api/v1/admin/members | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | 없음 (쿼리 파라미터) | `PagedMembersResponse` (추출) | 추출 #7 |
| PATCH | /api/v1/admin/members/{memberId}/role | Admin | cookieAuth (SUPER_ADMIN) | `RoleUpdateRequest` (공용 추출) | `MemberRoleResponse` (현상 유지) | 공용 #1 |
| PUT | /api/v1/internal/members/{memberId}/role | (@Hidden) | X-Internal-Api-Key 헤더 | `RoleUpdateRequest` (공용 추출) | 없음 (200 empty) | 공용 #1 |
| POST | /api/v1/members/batch | Member | 없음 (Gateway AT 검증 후 도달) | `MemberQueryRequest` (추출) | `List<MemberInfoResponse>` (추출) | 추출 #8 |
| POST | /api/v1/admin/routes | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | `CreateRouteRequest` (기존, @Schema 보강) | `RouteResponse` (기존, @Schema 보강) | @Schema 보강 #3 |
| GET | /api/v1/admin/routes | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | 없음 | `RouteListResponse` (기존, @Schema 보강) | @Schema 보강 #4 |
| GET | /api/v1/admin/routes/{routeId} | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | 없음 | `RouteResponse` (재사용) | @Schema 보강 #3 |
| PUT | /api/v1/admin/routes/{routeId} | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | `UpdateRouteRequest` (기존, @Schema 보강) | `RouteResponse` (재사용) | @Schema 보강 #5 |
| DELETE | /api/v1/admin/routes/{routeId} | Admin | cookieAuth (ADMIN \| SUPER_ADMIN) | 없음 | 없음 (204 empty) | — |
| GET | / | Health | 없음 (public) | 없음 | `HealthResponse` (추출) | 추출 #9 |

---

### 공용 DTO 명세

#### `ErrorResponse` (신규 공용 — `presentation/dto/ErrorResponse.java`)

- **통합 대상**: `AdminClientController.ErrorResponse`, `AdminMemberController.ErrorResponse`, `AdminRoleController.ErrorResponse`, **`ReissueController.ErrorResponse`** (4곳 전부 공용으로 통합)
- **처리 결정 (사용자 확정)**: **전부 timestamp 포함으로 통일.** `ReissueController`도 공용 `ErrorResponse`(3필드)를 사용하여 항상 `timestamp`를 포함한다. 기존 Reissue 에러에는 timestamp가 없었으나 이제 `{errorCode, message, timestamp}`로 통일된다(승인된 소폭 동작 변경). `@JsonInclude(NON_NULL)`·nullable 트릭 미사용.
- **연관 todo**: `[ ] presentation/dto/ErrorResponse.java 신규 생성 — 필드: String errorCode, String message, LocalDateTime timestamp`

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `errorCode` | `String` | 에러 식별 코드 | `"NOT_FOUND"` | — |
| `message` | `String` | 에러 설명 메시지 | `"존재하지 않는 회원입니다."` | — |
| `timestamp` | `LocalDateTime` | 에러 발생 시각 (ISO-8601) | `"2026-06-14T10:30:00"` | — |

직렬화 예시:
```json
{
  "errorCode": "NOT_FOUND",
  "message": "존재하지 않는 회원입니다.",
  "timestamp": "2026-06-14T10:30:00"
}
```

#### `RoleUpdateRequest` (신규 공용 — `presentation/dto/RoleUpdateRequest.java`)

- **통합 대상**: `AdminMemberController.RoleUpdateRequest`, `AdminRoleController.RoleUpdateRequest` (2곳 동일)
- **연관 todo**: `[ ] presentation/dto/RoleUpdateRequest.java 신규 생성 — 필드: @NotBlank String role`

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `role` | `String` | 부여할 역할. 허용값: USER, ADMIN, SUPER_ADMIN | `"ADMIN"` | `@NotBlank` |

직렬화 예시:
```json
{
  "role": "ADMIN"
}
```

---

### 엔드포인트 상세

#### POST /api/v1/auth/signup

- **목적**: loginId/password 기반 회원 가입
- **연관 todo**: `[ ] presentation/dto/SignupRequest.java에 각 필드에 @Schema(description=..., example=...) 추가`
- **요청 헤더**: 없음 (인증 불필요)
- **요청 바디**:
  ```json
  {
    "name": "김에코",
    "loginId": "econo123",
    "password": "p@ssw0rd1",
    "generation": 8,
    "status": "AM"
  }
  ```
- **응답 (성공)**: `201 Created` — 바디 없음
- **응답 (에러)**:
  - `400` `VALIDATION_FAILED` — name/loginId/password 형식 위반 또는 generation 범위 초과
  - `400` `INVALID_PASSWORD_POLICY` — 비밀번호 정책 위반
  - `409` `MEMBER_ALREADY_EXISTS` — loginId 중복
- **인증 / 권한**:
  - 필요 여부: 불필요 (public)
  - 필요 역할/스코프: 없음
- **DTO 스키마 — `SignupRequest`** (기존 DTO, `@Schema` 보강 대상):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `name` | `String` | 회원 이름 (1~50자) | `"김에코"` | `@NotBlank @Size(min=1, max=50)` |
| `loginId` | `String` | 로그인 아이디 (영문·숫자·-_.  3~19자) | `"econo123"` | `@NotBlank @Pattern(^[a-zA-Z0-9\-_.]{3,19}$)` |
| `password` | `String` | 비밀번호 (8~19자) | `"p@ssw0rd1"` | `@NotBlank @Size(min=8, max=19)` |
| `generation` | `Integer` | 기수 (1~99) | `8` | `@NotNull @Min(1) @Max(99)` |
| `status` | `MemberStatus` | 활동 상태. 허용: AM(재학), RM(휴학), CM(수료), OB(졸업) | `"AM"` | `@NotNull` |

---

#### POST /api/v1/auth/reissue

- **목적**: Refresh Token으로 AT/RT 재발급. WEB은 쿠키, APP은 바디 사용
- **연관 todo**: `[ ] presentation/dto/ReissueRequest.java 신규 생성 — 필드: String refreshToken`
- **요청 헤더**:
  - `Client-Type: WEB` (기본값) 또는 `Client-Type: APP`
- **요청 바디** (APP 전용, WEB은 생략):
  ```json
  {
    "refreshToken": "eyJhbGciOiJSUzI1NiJ9..."
  }
  ```
  WEB 클라이언트는 body 없이 RT가 HttpOnly 쿠키 `rt`로 전달된다.
- **응답 (성공 — APP)**: `200 OK`
  ```json
  {
    "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
    "accessExpiredTime": 1718350800000,
    "refreshToken": "eyJhbGciOiJSUzI1NiJ9..."
  }
  ```
  WEB 응답은 쿠키(`at`, `rt`) 세팅 + body는 `{ "accessExpiredTime": 1718350800000 }` (accessToken·refreshToken null이므로 `@JsonInclude(NON_NULL)`로 제외됨)
- **응답 (에러)** — 공용 `ErrorResponse`(`{errorCode, message, timestamp}`) 사용 (timestamp 포함으로 통일):
  - `401` `REFRESH_TOKEN_MISSING` — RT 없음
  - `401` `REFRESH_TOKEN_INVALID` — RT 만료·위조·AT로 재발급 시도
- **인증 / 권한**:
  - 필요 여부: RT 자체 검증 (쿠키 또는 바디). Passport/cookieAuth 불필요
  - 필요 역할/스코프: 없음
- **DTO 스키마 — `ReissueRequest`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `refreshToken` | `String` | APP 클라이언트의 Refresh Token. WEB은 쿠키에서 자동 추출하므로 이 필드를 보내지 않는다. | `"eyJhbGciOiJSUzI1NiJ9..."` | — (null 허용, APP일 때만 사용) |

---

#### POST /api/v1/auth/logout

- **목적**: WEB은 쿠키 만료, APP은 클라이언트가 RT 직접 삭제
- **연관 todo**: 해당 없음 (DTO 없음)
- **요청 헤더**: `Client-Type: WEB` (기본값) 또는 `Client-Type: APP`
- **요청 바디**: 없음
- **응답 (성공)**: `200 OK` — 바디 없음
- **인증 / 권한**: 없음 (멱등, RT 없어도 200)

---

#### POST /api/v1/clients

- **목적**: 인증된 회원이 자신의 앱을 SSO 클라이언트로 셀프 등록
- **연관 todo**: `[ ] presentation/dto/SelfRegisterClientRequest.java 신규 생성`, `[ ] presentation/dto/SelfRegisterClientResponse.java 신규 생성`
- **요청 헤더**: 쿠키 `at` (Gateway가 X-User-Passport로 변환하여 주입. Swagger 파라미터 노출 안 함)
- **요청 바디**:
  ```json
  {
    "clientName": "econo-board",
    "redirectUris": ["https://board.econovation.kr/callback"]
  }
  ```
- **응답 (성공)**: `201 Created`
  ```json
  {
    "clientId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "clientSecret": "s3cr3t-v4lue-here"
  }
  ```
- **응답 (에러)** — 공용 `ErrorResponse` 사용 가능하나 현재 컨트롤러는 직접 처리하지 않고 예외 전파; 에러 포맷은 전역 예외 핸들러 따름:
  - `400` `REDIRECT_URI_REQUIRED` — redirectUris 없음
  - `400` `VALIDATION_FAILED` — clientName 빈 문자열
  - `401` — X-User-Passport 누락
  - `409` `DUPLICATE_CLIENT_NAME` — 클라이언트 이름 중복
  - `422` `CLIENT_LIMIT_EXCEEDED` — 회원당 최대 5개 초과
- **인증 / 권한**:
  - 필요 여부: 필요
  - 필요 역할/스코프: 인증된 회원 누구나 (역할 제한 없음, `@PassportAuth` 기본)
  - `@SecurityRequirement(name = "cookieAuth")`
- **DTO 스키마 — `SelfRegisterClientRequest`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `clientName` | `String` | OAuth 클라이언트 이름 (서비스 앱 식별자) | `"econo-board"` | `@NotBlank` |
| `redirectUris` | `Set<String>` | 허용할 리다이렉트 URI 목록 (1개 이상 필수) | `["https://board.econovation.kr/callback"]` | `@NotNull` |

- **DTO 스키마 — `SelfRegisterClientResponse`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `clientId` | `String` | 발급된 OAuth 클라이언트 ID (UUID) | `"a1b2c3d4-e5f6-7890-abcd-ef1234567890"` | — |
| `clientSecret` | `String` | 발급된 클라이언트 시크릿 (1회만 반환) | `"s3cr3t-v4lue-here"` | — |

---

#### POST /api/v1/admin/clients

- **목적**: 관리자가 OAuth 클라이언트를 직접 등록하고 clientId를 발급
- **연관 todo**: `[ ] presentation/dto/RegisterClientRequest.java 신규 생성`, `[ ] presentation/dto/RegisterClientResponse.java 신규 생성`
- **요청 헤더**: 쿠키 `at` (ADMIN 또는 SUPER_ADMIN)
- **요청 바디**:
  ```json
  {
    "clientName": "econo-attendance",
    "redirectUris": ["https://attend.econovation.kr/callback", "http://localhost:3000/callback"]
  }
  ```
- **응답 (성공)**: `201 Created`
  ```json
  {
    "clientId": "b2c3d4e5-f6a7-8901-bcde-f12345678901"
  }
  ```
- **응답 (에러)** — 공용 `ErrorResponse`:
  - `400` `REDIRECT_URI_REQUIRED` — redirectUris 없음
  - `400` `VALIDATION_FAILED` — clientName 빈 문자열
  - `403` — ADMIN 역할 없음
  - `409` `DUPLICATE_CLIENT_NAME` — 클라이언트 이름 중복
- **인증 / 권한**:
  - 필요 여부: 필요
  - 필요 역할/스코프: `ADMIN` 또는 `SUPER_ADMIN`
  - `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`
- **DTO 스키마 — `RegisterClientRequest`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `clientName` | `String` | 등록할 OAuth 클라이언트 이름 | `"econo-attendance"` | `@NotBlank` |
| `redirectUris` | `Set<String>` | 허용할 리다이렉트 URI 목록 (null 가능 — 추후 추가 허용) | `["https://attend.econovation.kr/callback"]` | — (null 허용) |

- **DTO 스키마 — `RegisterClientResponse`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `clientId` | `String` | 발급된 OAuth 클라이언트 ID (UUID) | `"b2c3d4e5-f6a7-8901-bcde-f12345678901"` | — |

---

#### GET /api/v1/admin/clients/{clientId}

- **목적**: 특정 클라이언트의 상세 정보 (clientId, clientName, redirectUris) 조회
- **연관 todo**: `[ ] AdminClientController inner record 제거 — RedirectUrisResponse, ClientDetailResponse는 추출 결정 필요`
- **결정**: `ClientDetailResponse`와 `RedirectUrisResponse`는 Swagger 스키마 노출이 필요하므로 **`presentation/dto/`로 추출 + public + `@Schema` 적용**한다.
- **요청 헤더**: 쿠키 `at` (ADMIN 또는 SUPER_ADMIN)
- **경로 파라미터**: `clientId` — OAuth 클라이언트 UUID
- **응답 (성공)**: `200 OK`
  ```json
  {
    "clientId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "clientName": "econo-attendance",
    "redirectUris": ["https://attend.econovation.kr/callback"]
  }
  ```
- **응답 (에러)** — 공용 `ErrorResponse`:
  - `403` — ADMIN 역할 없음
  - `404` — 존재하지 않는 clientId
- **인증 / 권한**:
  - 필요 여부: 필요
  - 필요 역할/스코프: `ADMIN` 또는 `SUPER_ADMIN`
- **DTO 스키마 — `ClientDetailResponse`** (추출 결정):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `clientId` | `String` | OAuth 클라이언트 ID (UUID) | `"b2c3d4e5-f6a7-8901-bcde-f12345678901"` | — |
| `clientName` | `String` | OAuth 클라이언트 이름 | `"econo-attendance"` | — |
| `redirectUris` | `Set<String>` | 등록된 리다이렉트 URI 목록 | `["https://attend.econovation.kr/callback"]` | — |

---

#### POST /api/v1/admin/clients/{clientId}/redirect-uris

- **목적**: 특정 클라이언트에 redirectUri 1개 추가
- **연관 todo**: `[ ] presentation/dto/RedirectUriRequest.java 신규 생성`
- **요청 헤더**: 쿠키 `at` (ADMIN 또는 SUPER_ADMIN)
- **요청 바디**:
  ```json
  {
    "uri": "https://new.econovation.kr/callback"
  }
  ```
- **응답 (성공)**: `200 OK`
  ```json
  {
    "clientId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "redirectUris": [
      "https://attend.econovation.kr/callback",
      "https://new.econovation.kr/callback"
    ]
  }
  ```
- **응답 (에러)** — 공용 `ErrorResponse`:
  - `403` — ADMIN 역할 없음
  - `404` — 존재하지 않는 clientId
- **인증 / 권한**:
  - 필요 여부: 필요
  - 필요 역할/스코프: `ADMIN` 또는 `SUPER_ADMIN`
- **DTO 스키마 — `RedirectUriRequest`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `uri` | `String` | 추가하거나 제거할 리다이렉트 URI | `"https://new.econovation.kr/callback"` | — |

- **DTO 스키마 — `RedirectUrisResponse`** (추출 결정):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `clientId` | `String` | OAuth 클라이언트 ID (UUID) | `"b2c3d4e5-f6a7-8901-bcde-f12345678901"` | — |
| `redirectUris` | `Set<String>` | 변경 후 전체 리다이렉트 URI 목록 | `["https://attend.econovation.kr/callback", "https://new.econovation.kr/callback"]` | — |

---

#### DELETE /api/v1/admin/clients/{clientId}/redirect-uris

- **목적**: 특정 클라이언트에서 redirectUri 1개 제거
- **요청 바디**: `RedirectUriRequest` 재사용 (위와 동일)
- **응답**: `200 OK` + `RedirectUrisResponse` (제거 후 전체 목록)
- **인증 / 권한**: ADMIN 또는 SUPER_ADMIN (동일)

---

#### PUT /api/v1/admin/clients/{clientId}/redirect-uris

- **목적**: 특정 클라이언트의 redirectUri 목록 전체 교체
- **연관 todo**: `[ ] presentation/dto/RedirectUrisReplaceRequest.java 신규 생성`
- **요청 바디**:
  ```json
  {
    "uris": ["https://attend.econovation.kr/callback"]
  }
  ```
- **응답**: `200 OK` + `RedirectUrisResponse`
- **인증 / 권한**: ADMIN 또는 SUPER_ADMIN
- **DTO 스키마 — `RedirectUrisReplaceRequest`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `uris` | `Set<String>` | 교체할 리다이렉트 URI 전체 목록 (기존 목록 전부 덮어씀) | `["https://attend.econovation.kr/callback"]` | — |

---

#### GET /api/v1/admin/members

- **목적**: 회원 목록 페이징 조회. role 파라미터로 필터링 가능
- **연관 todo**: `[ ] presentation/dto/MemberSummary.java 신규 생성`, `[ ] presentation/dto/PagedMembersResponse.java 신규 생성`
- **요청 헤더**: 쿠키 `at` (ADMIN 또는 SUPER_ADMIN)
- **쿼리 파라미터**:
  - `page` (int, 기본값 0) — 페이지 번호 (0-based)
  - `size` (int, 기본값 20) — 페이지 크기
  - `role` (String, 선택) — 역할 필터 (USER / ADMIN / SUPER_ADMIN)
- **응답 (성공)**: `200 OK`
  ```json
  {
    "content": [
      {
        "memberId": 42,
        "name": "김에코",
        "loginId": "econo123",
        "generation": 8,
        "status": "AM",
        "role": "USER"
      }
    ],
    "totalElements": 150,
    "totalPages": 8,
    "page": 0,
    "size": 20
  }
  ```
- **응답 (에러)** — 공용 `ErrorResponse`:
  - `403` — ADMIN 역할 없음
- **인증 / 권한**:
  - 필요 여부: 필요
  - 필요 역할/스코프: `ADMIN` 또는 `SUPER_ADMIN`
- **DTO 스키마 — `MemberSummary`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `memberId` | `Long` | 회원 PK | `42` | — |
| `name` | `String` | 회원 이름 | `"김에코"` | — |
| `loginId` | `String` | 로그인 아이디 | `"econo123"` | — |
| `generation` | `Integer` | 기수 | `8` | — |
| `status` | `String` | 활동 상태 (AM/RM/CM/OB) | `"AM"` | — |
| `role` | `String` | 현재 역할 (USER/ADMIN/SUPER_ADMIN) | `"USER"` | — |

- **DTO 스키마 — `PagedMembersResponse`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `content` | `List<MemberSummary>` | 현재 페이지 회원 목록 | (위 MemberSummary 배열) | — |
| `totalElements` | `long` | 전체 회원 수 (필터 적용 후) | `150` | — |
| `totalPages` | `int` | 전체 페이지 수 | `8` | — |
| `page` | `int` | 현재 페이지 번호 (0-based) | `0` | — |
| `size` | `int` | 페이지 크기 | `20` | — |

---

#### PATCH /api/v1/admin/members/{memberId}/role

- **목적**: 특정 회원의 역할 변경 (SUPER_ADMIN 전용)
- **연관 todo**: `[ ] presentation/dto/RoleUpdateRequest.java 신규 생성 (공용)`, `AdminMemberController inner record 제거`
- **요청 헤더**: 쿠키 `at` (SUPER_ADMIN)
- **경로 파라미터**: `memberId` — 회원 PK
- **요청 바디**: `RoleUpdateRequest` (공용)
  ```json
  {
    "role": "ADMIN"
  }
  ```
- **응답 (성공)**: `200 OK`
  ```json
  {
    "memberId": 42,
    "role": "ADMIN"
  }
  ```
  (`MemberRoleResponse` — package-private, 현상 유지)
- **응답 (에러)** — 공용 `ErrorResponse`:
  - `400` `INVALID_ROLE` — 허용되지 않는 역할 값
  - `403` `FORBIDDEN_SELF_ROLE_CHANGE` — 본인 역할 변경 시도
  - `403` — SUPER_ADMIN 역할 없음
  - `404` `NOT_FOUND` — 존재하지 않는 회원
  - `409` `LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED` — 마지막 SUPER_ADMIN 해제 시도
- **인증 / 권한**:
  - 필요 여부: 필요
  - 필요 역할/스코프: `SUPER_ADMIN` 전용
  - 추가 조건: 본인(`passport.memberId == memberId`) 변경 불가

---

#### PUT /api/v1/internal/members/{memberId}/role  (`@Hidden`)

- **목적**: Bootstrap 최초 관리자 역할 부여 (CLI 전용, Swagger 비공개)
- **연관 todo**: `[ ] AdminRoleController inner record RoleUpdateRequest, ErrorResponse 제거하고 공용 DTO로 교체`
- **요청 헤더**: `X-Internal-Api-Key: <secret>`
- **경로 파라미터**: `memberId` — 회원 PK
- **요청 바디**: `RoleUpdateRequest` (공용)
  ```json
  {
    "role": "ADMIN"
  }
  ```
- **응답 (성공)**: `200 OK` — 바디 없음
- **응답 (에러)** — 공용 `ErrorResponse`:
  - `401` `UNAUTHORIZED` — API Key 불일치
  - `404` `NOT_FOUND` — 존재하지 않는 회원
- **인증 / 권한**:
  - 필요 여부: `X-Internal-Api-Key` 헤더 검증
  - 필요 역할/스코프: 없음 (Passport 비사용)
  - `@Hidden` — Swagger UI 노출 없음

---

#### POST /api/v1/members/batch

- **목적**: IDs 목록으로 회원 정보 조회 (단건/다건 통합, 내부 서비스 간 통신용)
- **연관 todo**: `[ ] presentation/dto/MemberQueryRequest.java 신규 생성`, `[ ] presentation/dto/MemberInfoResponse.java 신규 생성`
- **요청 헤더**: 없음 별도 (Gateway가 AT 검증 후 X-User-Passport 주입하나, 엔드포인트에서 Passport를 파라미터로 받지 않음)
- **요청 바디**:
  ```json
  {
    "ids": [1, 2, 42]
  }
  ```
  단건: `{ "ids": [42] }`
- **응답 (성공)**: `200 OK`
  ```json
  [
    {
      "memberId": 1,
      "name": "김에코",
      "loginId": "econo123",
      "generation": 8,
      "status": "AM"
    },
    {
      "memberId": 42,
      "name": "이노베",
      "loginId": "innove42",
      "generation": 5,
      "status": "OB"
    }
  ]
  ```
  존재하지 않는 ID는 결과에서 제외 (에러 아님).
- **응답 (에러)**: 전역 예외 핸들러 처리
  - `400` — ids 빈 배열 또는 1000개 초과
- **인증 / 권한**:
  - 필요 여부: Gateway AT 검증 필요 (엔드포인트 자체는 Passport 검사 없음)
  - 필요 역할/스코프: 없음 (인증된 회원이면 누구나)
- **DTO 스키마 — `MemberQueryRequest`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `ids` | `List<Long>` | 조회할 회원 ID 목록 (1개 이상, 최대 1000개) | `[1, 2, 42]` | `@NotEmpty @Size(max=1000)` |

- **DTO 스키마 — `MemberInfoResponse`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `memberId` | `Long` | 회원 PK | `42` | — |
| `name` | `String` | 회원 이름 | `"김에코"` | — |
| `loginId` | `String` | 로그인 아이디 | `"econo123"` | — |
| `generation` | `Integer` | 기수 | `8` | — |
| `status` | `String` | 활동 상태 (AM/RM/CM/OB) | `"AM"` | — |

---

#### POST /api/v1/admin/routes

- **목적**: 새 동적 라우트 등록 (게이트웨이 즉시 반영)
- **연관 todo**: `[ ] presentation/dto/CreateRouteRequest.java에 각 필드에 @Schema 추가`
- **요청 헤더**: 쿠키 `at` (ADMIN 또는 SUPER_ADMIN)
- **요청 바디**:
  ```json
  {
    "pathPrefix": "/api/v1/board",
    "upstreamUrl": "http://board-service:8080",
    "enabled": true
  }
  ```
- **응답 (성공)**: `201 Created` — `RouteResponse`
  ```json
  {
    "routeId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "pathPrefix": "/api/v1/board",
    "upstreamUrl": "http://board-service:8080",
    "enabled": true,
    "createdAt": "2026-06-14T10:00:00",
    "updatedAt": "2026-06-14T10:00:00"
  }
  ```
- **응답 (에러)**:
  - `400` `VALIDATION_FAILED` / `ROUTE_UPSTREAM_INVALID`
  - `401` `AUTH_UNAUTHORIZED`
  - `403` `FORBIDDEN` / `ROUTE_PROTECTED`
  - `409` `ROUTE_PATH_CONFLICT`
- **인증 / 권한**: ADMIN 또는 SUPER_ADMIN
- **DTO 스키마 — `CreateRouteRequest`** (기존 DTO, `@Schema` 보강):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `pathPrefix` | `String` | 라우팅 경로 접두사 (예: /api/v1/board). 중복 불가, 보호 경로 패턴 금지 | `"/api/v1/board"` | `@NotBlank` |
| `upstreamUrl` | `String` | 업스트림 서비스 URL (내부 도커 네트워크 호스트 권장). SSRF 검증 대상 | `"http://board-service:8080"` | `@NotBlank` |
| `enabled` | `Boolean` | 라우트 활성화 여부 (false이면 라우팅 비활성) | `true` | `@NotNull` |

---

#### GET /api/v1/admin/routes

- **목적**: 등록된 전체 라우트 목록 조회
- **연관 todo**: `[ ] presentation/dto/RouteListResponse.java에 routes 필드에 @Schema 추가`
- **요청 헤더**: 쿠키 `at`
- **응답 (성공)**: `200 OK` — `RouteListResponse`
  ```json
  {
    "routes": [
      {
        "routeId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
        "pathPrefix": "/api/v1/board",
        "upstreamUrl": "http://board-service:8080",
        "enabled": true,
        "createdAt": "2026-06-14T10:00:00",
        "updatedAt": "2026-06-14T10:00:00"
      }
    ]
  }
  ```
- **인증 / 권한**: ADMIN 또는 SUPER_ADMIN
- **DTO 스키마 — `RouteListResponse`** (기존 DTO, `@Schema` 보강):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `routes` | `List<RouteResponse>` | 등록된 라우트 목록 (빈 배열 가능) | `[...]` | — |

---

#### GET /api/v1/admin/routes/{routeId}

- **목적**: routeId로 단건 라우트 조회
- **요청 헤더**: 쿠키 `at`
- **응답 (성공)**: `200 OK` — `RouteResponse` (위와 동일 스키마)
- **응답 (에러)**: `404` `ROUTE_NOT_FOUND`
- **인증 / 권한**: ADMIN 또는 SUPER_ADMIN

---

#### PUT /api/v1/admin/routes/{routeId}

- **목적**: 기존 라우트 전체 수정 (partial update 없음)
- **연관 todo**: `[ ] presentation/dto/UpdateRouteRequest.java에 각 필드에 @Schema 추가`
- **요청 바디**:
  ```json
  {
    "pathPrefix": "/api/v1/board-v2",
    "upstreamUrl": "http://board-service:8080",
    "enabled": false
  }
  ```
- **응답 (성공)**: `200 OK` — `RouteResponse`
- **인증 / 권한**: ADMIN 또는 SUPER_ADMIN
- **DTO 스키마 — `UpdateRouteRequest`** (기존 DTO, `@Schema` 보강):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `pathPrefix` | `String` | 변경할 경로 접두사. 보호 경로 패턴 및 중복 검증 대상 | `"/api/v1/board-v2"` | `@NotBlank` |
| `upstreamUrl` | `String` | 변경할 업스트림 URL. SSRF 검증 대상 | `"http://board-service:8080"` | `@NotBlank` |
| `enabled` | `Boolean` | 변경할 활성화 여부 | `false` | `@NotNull` |

---

#### DELETE /api/v1/admin/routes/{routeId}

- **목적**: 라우트 삭제 (게이트웨이 즉시 반영)
- **요청 헤더**: 쿠키 `at`
- **응답 (성공)**: `204 No Content`
- **응답 (에러)**: `403` `ROUTE_PROTECTED`, `404` `ROUTE_NOT_FOUND`
- **인증 / 권한**: ADMIN 또는 SUPER_ADMIN
- **DTO**: 없음

---

#### GET /

- **목적**: 루트 헬스체크 — 애플리케이션 이름, 기동 시각, uptime 반환
- **연관 todo**: `[ ] presentation/dto/HealthResponse.java 신규 생성`
- **요청 헤더**: 없음 (public, permitAll)
- **응답 (성공)**: `200 OK`
  ```json
  {
    "application": "auth-api",
    "startedAt": "2026-06-14T09:00:00Z",
    "uptime": "1일 1시간 30분 15초"
  }
  ```
- **인증 / 권한**: 없음
- **DTO 스키마 — `HealthResponse`** (신규 추출):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `application` | `String` | 애플리케이션 이름 | `"auth-api"` | — |
| `startedAt` | `String` | 애플리케이션 기동 시각 (ISO-8601) | `"2026-06-14T09:00:00Z"` | — |
| `uptime` | `String` | 현재까지의 가동 시간 | `"1일 1시간 30분 15초"` | — |

---

#### POST /api/v1/auth/login  (Spring Security 필터 처리 — OpenApiCustomizer)

- **목적**: loginId/password 기반 JSON 로그인. WEB: 쿠키 세팅 + 302 redirect, APP: 200 + body
- **연관 todo**: `[ ] presentation/dto/LoginResponse.java에 각 필드에 @Schema 추가`
- **요청 헤더**: `Client-Type: WEB` (기본) 또는 `Client-Type: APP`
- **요청 바디**:
  ```json
  {
    "loginId": "econo123",
    "password": "p@ssw0rd1"
  }
  ```
- **응답 (성공 — APP)**: `200 OK`
  ```json
  {
    "accessToken": "eyJhbGciOiJSUzI1NiJ9...",
    "accessExpiredTime": 1718350800000,
    "refreshToken": "eyJhbGciOiJSUzI1NiJ9..."
  }
  ```
  WEB 응답: `302 Found` + `Location: {clientId 등록 redirect_uri}` + 쿠키 `at`, `rt` 세팅
- **인증 / 권한**: 없음 (자격증명 자체가 인증)
- **DTO 스키마 — `LoginResponse`** (기존 DTO, `@Schema` 보강):

| 필드 | 타입 | description | example | validation |
|------|------|-------------|---------|------------|
| `accessToken` | `String` | JWT Access Token (APP 전용. WEB은 쿠키로 발급되어 null) | `"eyJhbGciOiJSUzI1NiJ9..."` | — |
| `accessExpiredTime` | `Long` | Access Token 만료 시각 (epoch milliseconds) | `1718350800000` | — |
| `refreshToken` | `String` | JWT Refresh Token (APP 전용. WEB은 쿠키로 발급되어 null) | `"eyJhbGciOiJSUzI1NiJ9..."` | — |
| `redirectUrl` | `String` | 로그인 후 이동 목적지 URL (현재 APP에서도 null — 미사용) | `null` | — |

`@JsonInclude(NON_NULL)` 적용됨 — null 필드는 응답 JSON에서 제외.

---

### 공통 헤더 / 메타데이터

| 헤더 | 방향 | 설명 |
|------|------|------|
| `Cookie: at=<JWT>` | 요청 | WEB 인증. Gateway가 Bearer로 변환하여 검증 후 X-User-Passport 주입 |
| `Client-Type: WEB\|APP` | 요청 | 로그인·재발급·로그아웃에서 응답 형태 분기. 기본값 WEB |
| `X-Internal-Api-Key: <secret>` | 요청 | AdminRoleController 전용 내부 API Key 인증 |
| `X-User-Passport: <Base64>` | 서버 내부 (Gateway → auth-api) | Swagger 파라미터로 노출 안 함 (`SpringDocUtils.addRequestWrapperToIgnore`) |

보안 스킴: `cookieAuth` — `apiKey in cookie, name=at` (`OpenApiConfig`에서 전역 선언)

---

### ReissueController.ErrorResponse 처리 결정 (사용자 확정)

**전부 timestamp 포함으로 통일** 채택.

- `ReissueController` 내부 `record ErrorResponse(String errorCode, String message)`를 제거하고, 공용 `presentation/dto/ErrorResponse`(`{errorCode, message, timestamp}`)를 사용한다.
- 기존 Reissue 에러 응답에는 timestamp가 없었으나, 이제 4개 컨트롤러 모두 `{errorCode, message, timestamp}`로 통일된다(승인된 소폭 동작 변경).
- 공용 `ErrorResponse`는 `@JsonInclude(NON_NULL)` 없이 timestamp를 항상 포함하며 `@Schema` example을 적용한다.

---

### AdminClientController 응답 전용 DTO 추출 결정

- `ClientDetailResponse`와 `RedirectUrisResponse`: Swagger 스키마 노출을 위해 **`presentation/dto/`로 추출 + public + `@Schema` 적용** 결정.
- 현재 package-private이지만 `AdminClientApiDocs`의 메서드 시그니처가 `ResponseEntity<?>` 와일드카드여서 springdoc이 타입 추론 불가. 추출하지 않으면 Swagger UI에 스키마 노출 안 됨.

---

## 체크리스트
- [x] todo의 모든 API 작업이 엔드포인트로 명세됨 (신규 엔드포인트 없음 확인, 기존 전체 커버)
- [x] 각 엔드포인트의 인증/권한이 명시됨 (기본값 의존 X)
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨
- [x] 요청·응답 스키마가 실제 본문 예시로 작성됨
- [x] 프로젝트 표준 헤더/메타데이터가 누락 없이 명시됨
- [x] ReissueController.ErrorResponse 처리 방식 결정됨 (별도 유지)
- [x] AdminClientController 응답 전용 DTO 추출 여부 결정됨 (추출)

---

## 참고
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/CONVENTION.md` — §1.1(패키지), §2.4(JSON), §2.5(Validation), §8(Swagger)
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/services/apis/auth-api/src/main/java/com/econo/auth/api/config/openapi/OpenApiConfig.java` — cookieAuth 스킴, 태그 순서, Passport 숨김 설정
- 참조 컨트롤러: `ClientController`, `AdminClientController`, `AdminMemberController`, `AdminRoleController`, `ReissueController`, `RootController`, `MemberInfoController`, `SignUpController`, `AdminRouteController`
- 참조 docs 인터페이스: `ClientApiDocs`, `AdminClientApiDocs`, `AdminMemberApiDocs`, `ReissueApiDocs`, `RootApiDocs`, `MemberInfoApiDocs`, `SignUpApiDocs`, `AdminRouteApiDocs`
- 기존 추출 DTO: `SignupRequest`, `LoginResponse`, `CreateRouteRequest`, `UpdateRouteRequest`, `RouteResponse`, `RouteListResponse`
- 도메인: `Member`, `MemberStatus` (AM/RM/CM/OB), `Roles` (USER/ADMIN/SUPER_ADMIN)
