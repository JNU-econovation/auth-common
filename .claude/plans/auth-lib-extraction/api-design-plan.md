# auth-lib-extraction - api-design

## 메타
- **작업명**: auth-lib-extraction
- **문서 타입**: api-design
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

---

## 개요

이 작업은 **외부 API 계약을 일절 변경하지 않는 순수 리팩토링**이다. 신규 엔드포인트는 0개이며, 기존 엔드포인트의 URL·HTTP 메서드·요청/응답 스키마·HTTP 상태 코드·인증/권한 모두 변경되지 않는다.

이 문서의 목적은 두 가지다.
1. 리팩토링 이전 시점의 모든 엔드포인트를 **계약 기준선(contract baseline)**으로 고정하여, 이후 구현이 이 기준선을 위반하지 않음을 체크하는 근거로 삼는다.
2. 특히 `ReissueController`의 에러 분기 — `REFRESH_TOKEN_MISSING`, `REFRESH_TOKEN_INVALID` — 가 `JwtDecoder`/`Jwt` 제거 후에도 **동일한 401 응답**으로 보존되는지 케이스별로 명시한다.

프로토콜: REST / JSON. 인증 방식: 엔드포인트마다 다름(아래 상세 참조). 공통 에러 응답 형식: `GlobalExceptionHandler.ApiError` (`errorCode`, `message`, `timestamp`, `fieldErrors`).

---

## 본문

### 엔드포인트 목록 (계약 기준선)

| 메서드 | 경로 | 설명 | 인증/권한 | 리팩토링 영향 여부 |
|--------|------|------|-----------|-------------------|
| POST | `/api/v1/auth/login` | JSON 로그인, AT/RT 발급 | 불필요 (public) | **직접 영향** — `LoginTokenUseCase.issue()` 호출 경로 변경 |
| POST | `/api/v1/auth/reissue` | AT/RT 재발급 | 불필요 (RT 자체 검증) | **직접 영향** — `JwtDecoder` 제거, 도메인 예외 catch 추가 |
| POST | `/api/v1/auth/logout` | 쿠키 만료 (WEB) | 불필요 (멱등) | 영향 없음 |
| POST | `/api/v1/auth/signup` | 회원 가입 | 불필요 (public) | 영향 없음 |
| POST | `/api/v1/clients` | OAuth 클라이언트 셀프 등록 | X-User-Passport (any 인증 회원) | 영향 없음 |
| POST | `/api/v1/admin/clients` | OAuth 클라이언트 어드민 등록 | X-User-Passport (ADMIN/SUPER_ADMIN) | 영향 없음 |
| GET | `/api/v1/admin/clients/{clientId}` | 클라이언트 조회 | X-User-Passport (ADMIN/SUPER_ADMIN) | 영향 없음 |
| POST | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 추가 | X-User-Passport (ADMIN/SUPER_ADMIN) | 영향 없음 |
| DELETE | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 제거 | X-User-Passport (ADMIN/SUPER_ADMIN) | 영향 없음 |
| PUT | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 전체 교체 | X-User-Passport (ADMIN/SUPER_ADMIN) | 영향 없음 |
| GET | `/api/v1/admin/members` | 회원 목록 조회 | X-User-Passport (ADMIN/SUPER_ADMIN) | 영향 없음 |
| PATCH | `/api/v1/admin/members/{memberId}/role` | 회원 역할 변경 | X-User-Passport (SUPER_ADMIN) | 영향 없음 |
| PUT | `/api/v1/internal/members/{memberId}/role` | 역할 변경 (내부 CLI 전용) | X-Internal-Api-Key 헤더 | 영향 없음 |
| POST | `/api/v1/members/batch` | 회원 정보 다건 조회 | 불필요 (Gateway 인증 후 내부 경로) | 영향 없음 |
| GET | `/api/v1/admin/jwks` | JWKS 공개키 | 불필요 (public) | 영향 없음 |
| GET | `/` | 헬스체크 | 불필요 (public) | 영향 없음 |

> SAS 표준 엔드포인트(`/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/userinfo`, `/.well-known/openid-configuration`)는 Spring Authorization Server가 직접 제공하며, 이번 리팩토링과 무관하다.

---

### 엔드포인트 상세

#### POST `/api/v1/auth/login`

- **목적**: JSON 자격증명(loginId/password/clientId) 수신 → BCrypt 인증 → AT/RT 발급 → WEB(쿠키+302) 또는 APP(200+body) 분기
- **연관 todo**: `[ ] JsonLoginAuthenticationFilter import 갱신 — LoginTokenUseCase, LoginRedirectUseCase, TokenPair를 com.econo.auth.login.application.usecase.*로 변경`
- **처리 주체**: `JsonLoginAuthenticationFilter` (Spring Security Filter — `@RestController` 아님)
- **요청 헤더**:
  ```
  Content-Type: application/json
  Client-Type: WEB | APP   (생략 시 WEB)
  ```
- **요청 바디**:
  ```json
  {
    "loginId": "honggildong",
    "password": "Econo1234!",
    "clientId": "optional-client-uuid"
  }
  ```
  (`clientId`는 선택 필드)

- **응답 (성공 — WEB)**:
  - 상태: `302 Found`
  - 헤더:
    ```
    Location: <등록된 redirect_uri 또는 auth.redirect.default-url>
    Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=3600
    Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=2592000
    ```
  - 바디: 없음 (토큰은 Location URL의 쿼리/프래그먼트에 절대 포함하지 않음)

- **응답 (성공 — APP)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "accessToken": "<JWT>",
      "accessExpiredTime": 1718270400000,
      "refreshToken": "<JWT>",
      "redirectUrl": "https://app.example.com/callback"
    }
    ```
  - 쿠키 없음

- **응답 (에러)**:
  - `401 UNAUTHORIZED` `INVALID_CREDENTIALS` — loginId 미존재 또는 BCrypt 불일치. `JsonLoginAuthenticationFilter.unsuccessfulAuthentication()`이 직접 반환. `GlobalExceptionHandler`를 거치지 않음.
    ```json
    {
      "errorCode": "INVALID_CREDENTIALS",
      "message": "아이디 또는 비밀번호가 올바르지 않습니다.",
      "timestamp": "2026-06-13T10:00:00",
      "fieldErrors": null
    }
    ```

- **인증/권한**:
  - 필요 여부: 불필요 (public endpoint, SecurityConfig `permitAll`)
  - 역할/스코프: 없음

- **리팩토링 후 동작 보존 기준**:
  - `loginTokenUseCase.issue(member)` 호출 주체는 `JsonLoginAuthenticationFilter`로 동일
  - `LoginTokenUseCase` 인터페이스 패키지가 `com.econo.auth.api.application.usecase` → `com.econo.auth.login.application.usecase`로 이동. 컴파일 import만 변경, 런타임 동작 불변
  - `TokenPair` record 구조(`accessToken`, `accessExpiredAt`, `refreshToken`) 불변
  - `LoginRedirectUseCase.resolve(clientId, defaultUrl)` 시그니처 불변

---

#### POST `/api/v1/auth/reissue`

- **목적**: RT 수신 → 서명/만료/token_type 검증 → 새 AT/RT 발급 → WEB(쿠키 교체) 또는 APP(body 반환)
- **연관 todo**: `[ ] ReissueController 수정 — JwtDecoder·Jwt 필드 및 관련 import 제거. rawRt null/blank 체크는 컨트롤러에 유지. loginTokenUseCase.verifyRefreshTokenAndGetMemberId(rawRt) 호출로 변경. InvalidTokenException과 WrongTokenTypeException catch → 기존 401 REFRESH_TOKEN_INVALID 응답 반환`
- **처리 주체**: `ReissueController`
- **요청 헤더**:
  ```
  Client-Type: WEB | APP   (생략 시 WEB)
  Content-Type: application/json   (APP 시에만 body 있음)
  ```
  WEB: `Cookie: rt=<JWT>` (HttpOnly 쿠키)
- **요청 바디** (APP 전용, WEB은 body 불필요):
  ```json
  { "refreshToken": "<JWT>" }
  ```

- **응답 (성공 — WEB)**:
  - 상태: `200 OK`
  - 헤더:
    ```
    Set-Cookie: at=<new JWT>; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=3600
    Set-Cookie: rt=<new JWT>; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=2592000
    ```
  - 바디: `LoginResponse.web(accessExpiredAt)` — `@JsonInclude(NON_NULL)`이므로 null 필드 제외
    ```json
    { "accessExpiredTime": 1718270400000 }
    ```

- **응답 (성공 — APP)**:
  - 상태: `200 OK`
  - 바디: `LoginResponse.app(accessToken, accessExpiredAt, refreshToken)` — redirectUrl 없음
    ```json
    {
      "accessToken": "<new JWT>",
      "accessExpiredTime": 1718270400000,
      "refreshToken": "<new JWT>"
    }
    ```

- **응답 (에러)**:
  - `401` `REFRESH_TOKEN_MISSING` — RT 없음(쿠키 부재 또는 APP body null/blank)
    ```json
    { "errorCode": "REFRESH_TOKEN_MISSING", "message": "Refresh token이 없습니다." }
    ```
  - `401` `REFRESH_TOKEN_INVALID` — 서명 검증 실패, 만료, 또는 token_type != "refresh"
    ```json
    { "errorCode": "REFRESH_TOKEN_INVALID", "message": "유효하지 않은 Refresh token입니다." }
    ```
  - `401` `REFRESH_TOKEN_INVALID` — AT를 RT 자리에 사용 (token_type = "access")
    ```json
    { "errorCode": "REFRESH_TOKEN_INVALID", "message": "유효하지 않은 Refresh token입니다." }
    ```
  > 주의: 리팩토링 이전에는 AT를 RT로 사용하는 케이스에서 message가 `"Access token으로 재발급 불가합니다."`였다. 리팩토링 후 `WrongTokenTypeException` catch에서 동일 메시지를 출력하거나, `"유효하지 않은 Refresh token입니다."`로 통일 여부를 구현 시 결정해야 한다. **errorCode(`REFRESH_TOKEN_INVALID`)와 상태 코드(`401`)는 반드시 동일해야 한다.**

- **인증/권한**:
  - 필요 여부: 불필요 (RT 자체가 자격증명)
  - 역할/스코프: 없음

---

#### POST `/api/v1/auth/logout`

- **목적**: WEB 클라이언트의 at/rt 쿠키를 Max-Age=0으로 만료. APP은 클라이언트 측에서 RT 삭제.
- **연관 todo**: API 변경 없음. `ReissueController`에 함께 위치하며 이번 리팩토링 대상이 아님.
- **요청 헤더**:
  ```
  Client-Type: WEB | APP   (생략 시 WEB)
  ```
- **요청 바디**: 없음

- **응답 (성공)**:
  - 상태: `200 OK`
  - 헤더(WEB): `Set-Cookie: at=; Max-Age=0; ...` + `Set-Cookie: rt=; Max-Age=0; ...`
  - 바디: 없음 (`ResponseEntity<Void>`)

- **인증/권한**:
  - 필요 여부: 불필요 (멱등 — RT 없어도 200)
  - 역할/스코프: 없음

- **비고**: 멱등. 쿠키 없는 상태로 호출해도 200 반환. APP 분기에서는 쿠키 삭제 로직 실행하지 않음.

---

#### POST `/api/v1/auth/signup`

- **목적**: loginId/password 기반 회원 가입
- **연관 todo**: 이번 리팩토링 영향 없음. 기준선 기록 목적으로만 포함.
- **요청 바디**:
  ```json
  {
    "name": "홍길동",
    "loginId": "honggildong",
    "password": "Econo1234!",
    "generation": 30,
    "status": "AM"
  }
  ```
- **응답 (성공)**: `201 Created`, 바디 없음
- **응답 (에러)**:
  - `400` `VALIDATION_FAILED` — Bean Validation 실패
  - `400` `INVALID_PASSWORD_POLICY` — 비밀번호 정책 위반 (대문자·소문자·숫자·특수기호 각 1자 이상)
  - `409` `MEMBER_ALREADY_EXISTS` — loginId 중복
- **인증/권한**: 불필요 (public)

---

#### POST `/api/v1/clients`

- **목적**: 인증된 회원(any role)이 자신의 서비스를 OAuth 클라이언트로 셀프 등록
- **연관 todo**: 이번 리팩토링 영향 없음.
- **요청 헤더**: `X-User-Passport: <Base64(JSON Passport)>`
- **요청 바디**:
  ```json
  {
    "clientName": "내서비스앱",
    "redirectUris": ["https://myapp.example.com/callback"]
  }
  ```
- **응답 (성공)**: `201 Created`
  ```json
  { "clientId": "uuid", "clientSecret": "plain-text-secret" }
  ```
- **응답 (에러)**:
  - `401` `AUTH_UNAUTHORIZED` — X-User-Passport 헤더 누락 또는 파싱 실패
  - `400` `REDIRECT_URI_REQUIRED` — redirectUris 빈 Set
  - `400` `VALIDATION_FAILED` — clientName 빈 문자열 또는 redirectUris null
  - `409` `DUPLICATE_CLIENT_NAME` — clientName 중복
  - `422` `CLIENT_LIMIT_EXCEEDED` — 1인 5개 초과
- **인증/권한**:
  - 필요 여부: 필요
  - 방식: `@PassportAuth` (econo-passport) → `X-User-Passport` 헤더 파싱
  - 역할: any (역할 무관, 인증된 회원이면 허용)

---

#### POST `/api/v1/admin/clients`

- **목적**: ADMIN이 OAuth 클라이언트를 등록 (owner_id=NULL, clientSecret 미발급)
- **연관 todo**: 이번 리팩토링 영향 없음.
- **요청 헤더**: `X-User-Passport: <Base64(JSON Passport)>`
- **요청 바디**:
  ```json
  {
    "clientName": "EEOS 웹앱",
    "redirectUris": ["https://app.econovation.kr/callback"]
  }
  ```
- **응답 (성공)**: `201 Created`
  ```json
  { "clientId": "uuid" }
  ```
  (`clientSecret` 필드 없음)
- **응답 (에러)**:
  - `401` `AUTH_UNAUTHORIZED` — X-User-Passport 헤더 누락 또는 invalid
  - `403` `FORBIDDEN` — ADMIN/SUPER_ADMIN 역할 없음
  - `400` `REDIRECT_URI_REQUIRED` — redirectUris 없음
  - `400` `VALIDATION_FAILED` — clientName 빈 문자열
  - `409` `DUPLICATE_CLIENT_NAME` — clientName 중복
- **인증/권한**:
  - 필요 여부: 필요
  - 방식: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`
  - 역할: ADMIN 또는 SUPER_ADMIN

---

#### GET `/api/v1/admin/clients/{clientId}`

- **목적**: 특정 클라이언트의 redirectUri 목록 포함 조회
- **연관 todo**: 이번 리팩토링 영향 없음.
- **응답 (성공)**: `200 OK`
  ```json
  {
    "clientId": "uuid",
    "clientName": "EEOS 웹앱",
    "redirectUris": ["https://app.econovation.kr/callback"]
  }
  ```
- **응답 (에러)**:
  - `401` `AUTH_UNAUTHORIZED` — Passport 누락/invalid
  - `403` `FORBIDDEN` — 역할 부족
  - `404` `CLIENT_NOT_FOUND` — clientId 미존재
- **인증/권한**: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`

---

#### POST `/api/v1/admin/clients/{clientId}/redirect-uris`

- **목적**: redirectUri 추가 (기존 유지 + 새 URI 추가)
- **요청 바디**: `{ "uri": "https://new.example.com/callback" }`
- **응답 (성공)**: `200 OK`
  ```json
  { "clientId": "uuid", "redirectUris": ["https://existing.com/cb", "https://new.example.com/callback"] }
  ```
- **응답 (에러)**: 401, 403, 404 (위와 동일 패턴)
- **인증/권한**: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`

---

#### DELETE `/api/v1/admin/clients/{clientId}/redirect-uris`

- **목적**: redirectUri 개별 제거
- **요청 바디**: `{ "uri": "https://remove.example.com/callback" }`
- **응답 (성공)**: `200 OK` — 남은 redirectUris Set 반환
- **인증/권한**: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`

---

#### PUT `/api/v1/admin/clients/{clientId}/redirect-uris`

- **목적**: redirectUri 전체 교체
- **요청 바디**: `{ "uris": ["https://new1.example.com/cb", "https://new2.example.com/cb"] }`
- **응답 (성공)**: `200 OK` — 교체된 redirectUris Set 반환
- **인증/권한**: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`

---

#### GET `/api/v1/admin/members`

- **목적**: 회원 목록 페이지 조회 (role 필터 선택)
- **요청 파라미터**: `?page=0&size=20&role=ADMIN` (role 선택)
- **응답 (성공)**: `200 OK`
  ```json
  {
    "content": [
      { "memberId": 1, "name": "홍길동", "loginId": "hong", "generation": 30, "status": "AM", "role": "ADMIN" }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "page": 0,
    "size": 20
  }
  ```
- **응답 (에러)**: `401`, `403`
- **인증/권한**: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`

---

#### PATCH `/api/v1/admin/members/{memberId}/role`

- **목적**: 회원 역할 변경 (SUPER_ADMIN 전용)
- **요청 바디**: `{ "role": "ADMIN" }`
- **응답 (성공)**: `200 OK` — `{ "memberId": 1, "role": "ADMIN" }`
- **응답 (에러)**:
  - `401` `AUTH_UNAUTHORIZED` — Passport 누락/invalid
  - `403` `FORBIDDEN` — SUPER_ADMIN 역할 없음
  - `403` `FORBIDDEN_SELF_ROLE_CHANGE` — 본인 역할 변경 시도
  - `400` `INVALID_ROLE` — 허용되지 않는 역할 값 (USER/ADMIN/SUPER_ADMIN만 허용)
  - `404` `NOT_FOUND` — 존재하지 않는 회원
  - `409` `LAST_SUPER_ADMIN_CANNOT_BE_DEMOTED` — 마지막 SUPER_ADMIN 해제 시도
- **인증/권한**: `@PassportAuth(requiredRoles = {Roles.SUPER_ADMIN})`

---

#### PUT `/api/v1/internal/members/{memberId}/role`

- **목적**: 최초 관리자 부여 등 Bootstrap 목적 CLI 전용 역할 변경
- **요청 헤더**: `X-Internal-Api-Key: <secret>`
- **요청 바디**: `{ "role": "SUPER_ADMIN" }`
- **응답 (성공)**: `200 OK` (body 없음)
- **응답 (에러)**:
  - `401` `UNAUTHORIZED` — X-Internal-Api-Key 불일치 또는 누락
  - `404` `NOT_FOUND` — 존재하지 않는 회원
- **인증/권한**: `X-Internal-Api-Key` 헤더 (타임-세이프 비교). Swagger `@Hidden` — 외부 문서에 노출 안 됨.

---

#### POST `/api/v1/members/batch`

- **목적**: ID 목록으로 회원 정보 다건 조회 (내부 서비스 간 연동용)
- **요청 바디**:
  ```json
  { "ids": [1, 2, 42] }
  ```
  (단건도 배열 1개: `{ "ids": [42] }`)
- **응답 (성공)**: `200 OK`
  ```json
  [
    { "memberId": 1, "name": "홍길동", "loginId": "hong", "generation": 30, "status": "AM" }
  ]
  ```
  (존재하지 않는 ID는 조용히 제외. 결과 0개도 200)
- **응답 (에러)**: `400` — ids 빈 배열 또는 1000개 초과
- **인증/권한**: 불필요 (Gateway 인증 후 내부 경로. SecurityConfig `permitAll` 또는 Gateway가 AT 검증 후 전달)

---

#### GET `/api/v1/admin/jwks`

- **목적**: RS256 공개키(JWKS) 제공 — Gateway의 `BearerToPassportFilter`가 직접 호출
- **응답 (성공)**: `200 OK`
  ```json
  {
    "keys": [
      { "kty": "RSA", "use": "sig", "kid": "econo-auth-rsa-key-v1", "n": "...", "e": "AQAB" }
    ]
  }
  ```
- **인증/권한**: 불필요 (public — SecurityConfig `permitAll`)

---

#### GET `/`

- **목적**: 헬스체크 — 애플리케이션 이름, 기동 시각, uptime 반환
- **응답 (성공)**: `200 OK`
  ```json
  {
    "application": "auth-api",
    "startedAt": "2026-06-13T01:00:00Z",
    "uptime": "PT1H23M"
  }
  ```
- **인증/권한**: 불필요 (public — SecurityConfig `permitAll`)

---

### 핵심: 재발급 에러 응답 보존 상세 명세

`ReissueController`는 이번 리팩토링에서 `JwtDecoder`/`Jwt` 의존을 제거하고 `loginTokenUseCase.verifyRefreshTokenAndGetMemberId(String rawRt)`로 교체한다. 기존 3가지 에러 분기가 리팩토링 후에도 **동일한 HTTP 상태(401)와 errorCode**로 보존되어야 한다.

#### 기존 코드 (리팩토링 이전) vs 리팩토링 이후 대응

| # | 케이스 | 기존 발생 시점 | 기존 응답 | 리팩토링 후 발생 경로 | 리팩토링 후 응답 (보존 목표) |
|---|--------|----------------|-----------|----------------------|----------------------------|
| 1 | RT 없음 (WEB: rt 쿠키 부재, APP: body null/blank) | `rawRt == null \|\| rawRt.isBlank()` 컨트롤러 체크 | `401 REFRESH_TOKEN_MISSING` | 동일 체크 — 컨트롤러에 유지 | `401 REFRESH_TOKEN_MISSING` |
| 2 | 서명 불일치·만료 등 디코딩 실패 | `jwtDecoder.decode(rawRt)` → `JwtException` | `401 REFRESH_TOKEN_INVALID` | `verifyRefreshTokenAndGetMemberId` → `InvalidTokenException` catch | `401 REFRESH_TOKEN_INVALID` |
| 3 | AT를 RT 자리에 사용 (token_type=access) | `loginTokenUseCase.extractMemberIdFromRt(jwt)` → `IllegalArgumentException` | `401 REFRESH_TOKEN_INVALID` | `verifyRefreshTokenAndGetMemberId` → `WrongTokenTypeException` catch | `401 REFRESH_TOKEN_INVALID` |

#### 리팩토링 후 `ReissueController.reissue()` 의사 코드

```
String rawRt = resolveRefreshToken(clientType, body, request);

// 케이스 1: null/blank 체크 — 컨트롤러에 유지 (변경 없음)
if (rawRt == null || rawRt.isBlank()) {
    return 401 REFRESH_TOKEN_MISSING;
}

Long memberId;
try {
    // 케이스 2 + 3: verifyRefreshTokenAndGetMemberId가 내부에서
    //   InvalidTokenException (decode 실패) → 케이스 2
    //   WrongTokenTypeException (token_type != "refresh") → 케이스 3
    memberId = loginTokenUseCase.verifyRefreshTokenAndGetMemberId(rawRt);
} catch (InvalidTokenException | WrongTokenTypeException e) {
    return 401 REFRESH_TOKEN_INVALID;
}

TokenPair tokens = loginTokenUseCase.reissue(memberId);
// ... 이후 동일
```

> `InvalidTokenException`과 `WrongTokenTypeException`은 `GlobalExceptionHandler`에 핸들러가 **없다**. 컨트롤러가 직접 catch해야 한다. 만약 catch 누락 시 `handleGeneric`이 500을 반환하므로, 누락 여부를 회귀 테스트로 반드시 검증한다.

---

### 공통 에러 응답 구조

`GlobalExceptionHandler.ApiError` record 기준:

```json
{
  "errorCode": "MEMBER_NOT_FOUND",
  "message": "...",
  "timestamp": "2026-06-13T10:00:00",
  "fieldErrors": null
}
```

Bean Validation 실패 시 `fieldErrors` 배열 포함:
```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-06-13T10:00:00",
  "fieldErrors": [
    { "field": "loginId", "message": "공백일 수 없습니다" }
  ]
}
```

`ReissueController`의 인라인 `ErrorResponse` record(`errorCode`, `message`)는 `GlobalExceptionHandler.ApiError`와 다른 별도 포맷이다(timestamp, fieldErrors 없음). 리팩토링 후에도 이 포맷이 유지되어야 한다:
```json
{ "errorCode": "REFRESH_TOKEN_MISSING", "message": "Refresh token이 없습니다." }
```

---

### 공통 헤더 정책

| 헤더 | 방향 | 설명 |
|------|------|------|
| `Content-Type: application/json` | 요청 | JSON 바디가 있는 모든 요청 |
| `Client-Type: WEB \| APP` | 요청 | 로그인·재발급·로그아웃 WEB/APP 분기 결정 |
| `X-User-Passport: <Base64>` | 요청 | econo-passport 인증 필요 엔드포인트 |
| `X-Internal-Api-Key: <secret>` | 요청 | 내부 CLI 전용 역할 관리 엔드포인트 |
| `Set-Cookie: at=...; rt=...` | 응답 | WEB 로그인·재발급 시 AT/RT 쿠키. HttpOnly, SameSite=None, Secure, Path=/ |

---

## 체크리스트

- [x] todo의 모든 API 작업이 엔드포인트로 명세됨 (API 변경 없음 선언 포함)
- [x] 각 엔드포인트의 인증/권한이 명시됨 (기본값 의존 X)
- [x] 모든 에러 케이스가 기존 에러 체계(`GlobalExceptionHandler` + inline `ErrorResponse`)로 매핑됨
- [x] 요청·응답 스키마가 실제 본문 예시로 작성됨
- [x] 프로젝트 표준 헤더/메타데이터가 누락 없이 명시됨
- [x] 재발급 3가지 에러 케이스(RT 없음·디코딩 실패·AT-as-RT)의 before/after 대응이 명시됨
- [x] 리팩토링 직접 영향 엔드포인트(login, reissue)와 무영향 엔드포인트가 구분됨

---

## 참고

- `docs/ARCHITECTURE.md` — 에러 코드 체계, 인증 흐름 A/B, 엔드포인트별 인증 정책
- `docs/CONVENTION.md` — REST 컨벤션, ApiError 포맷
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ReissueController.java` — 리팩토링 대상 원본 (기준선 소스)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/JsonLoginAuthenticationFilter.java` — 로그인 처리 원본
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 에러 응답 체계 원본
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` — 회귀 안전망 (전 시나리오 green 유지 필수)
