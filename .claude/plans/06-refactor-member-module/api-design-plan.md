# refactor-member-module - api-design

## 메타
- **작업명**: refactor-member-module
- **문서 타입**: api-design
- **작성일**: 2026-06-04
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

본 작업은 `auth-core` · `auth-infra` 두 모듈을 `member` · `common-infra`로 재편하는 **순수 모듈/패키지 리팩터링**이다.
외부에 노출되는 엔드포인트는 단 하나도 추가·변경·삭제되지 않는다.
API 프로토콜은 REST(Spring MVC `@RestController`)이며, 인증 정책(Public/JSON 폼 로그인/Basic Auth/Gateway 위임)도 현행 `SecurityConfig` 그대로 유지된다.
이 문서의 목적은 **변경 없음을 명시적으로 확인**하고, 회귀 검증 범위를 엔드포인트 단위로 열거하는 것이다.

---

## 본문

### 변경되는 엔드포인트

**없음.** 모든 Controller(`MemberController`, `MemberInfoController`, `ReissueController`, `AdminClientController`, `JwksController`)는 `auth-api` 모듈에 그대로 잔류한다. 변경되는 것은 Java `import` 경로뿐이다.

---

### 회귀 검증 대상 엔드포인트 목록

아래 7개 그룹 / 11개 엔드포인트가 리팩터링 후 동일하게 동작해야 한다.

| # | 메서드 | 경로 | 컨트롤러 | 인증/권한 | 변경 여부 |
|---|--------|------|----------|-----------|-----------|
| 1 | POST | `/api/v1/auth/signup` | `MemberController` | Public (permitAll) | 무변경 |
| 2 | POST | `/api/v1/auth/login` | `JsonLoginAuthenticationFilter` | Public (폼 인증 필터) | 무변경 |
| 3 | POST | `/api/v1/auth/reissue` | `ReissueController` | Public (permitAll) | 무변경 |
| 4 | POST | `/api/v1/auth/logout` | `ReissueController` | Public (permitAll) | 무변경 |
| 5 | POST | `/api/v1/members/batch` | `MemberInfoController` | Public — Gateway 인증 위임(permitAll) | 무변경 |
| 6 | POST | `/api/v1/clients` | `AdminClientController` | Public (permitAll) | 무변경 |
| 7 | GET | `/api/v1/routes` | `AdminClientController` | Public (permitAll) | 무변경 |
| 8 | GET | `/api/v1/clients/{clientId}` | `AdminClientController` | Basic Auth (`Authorization: Basic ...`) | 무변경 |
| 9 | POST | `/api/v1/clients/{clientId}/redirect-uris` | `AdminClientController` | Basic Auth | 무변경 |
| 10 | DELETE | `/api/v1/clients/{clientId}/redirect-uris` | `AdminClientController` | Basic Auth | 무변경 |
| 11 | PUT | `/api/v1/clients/{clientId}/redirect-uris` | `AdminClientController` | Basic Auth | 무변경 |
| 12 | GET | `/oauth2/jwks` | `JwksController` | Public (permitAll) | 무변경 |
| 13 | GET | `/.well-known/**` | SAS OIDC Discovery | Public (permitAll) | 무변경 |

---

### 인증/권한 정책 — 무변경 확인

`SecurityConfig.appSecurityFilterChain` 규칙이 그대로 유지된다. 각 정책의 출처는 실제 코드에서 확인했다.

| 정책 | 적용 엔드포인트 | 세부 내용 |
|------|----------------|-----------|
| **Public** | `/api/v1/auth/*`, `/oauth2/jwks`, `/.well-known/**`, `/api/v1/clients/**`, `/api/v1/routes/**`, `/api/v1/members/**` | `permitAll()` — 추가 토큰 검사 없음 |
| **JSON 폼 로그인** | `POST /api/v1/auth/login` | `JsonLoginAuthenticationFilter` — `DaoAuthenticationProvider` + `BCryptPasswordEncoder(12)`. 성공 시 AT/RT 쿠키 발급 |
| **Basic Auth (인라인)** | `GET/POST/DELETE/PUT /api/v1/clients/{clientId}/**` | `AdminClientController.verifyBasicAuth()` 직접 구현. `Authorization: Basic base64(clientId:clientSecret)`, BCrypt 검증 |
| **Gateway 위임** | `POST /api/v1/members/batch` | Gateway가 AT 검증 후 `X-User-Passport` 주입. auth-api 내부는 별도 검증 없이 permitAll |

---

### 에러 코드 — 패키지 경로 변경만 적용, 코드·HTTP 상태 무변경

| 예외 클래스 | HTTP | 에러 코드 | 변경 전 패키지 | 변경 후 패키지 |
|-------------|------|-----------|---------------|---------------|
| `MemberNotFoundException` | 404 | `MEMBER_NOT_FOUND` | `com.econo.auth.core.member.exception` | `com.econo.auth.member.exception` |
| `MemberAlreadyExistsException` | 409 | `MEMBER_ALREADY_EXISTS` | `com.econo.auth.core.member.exception` | `com.econo.auth.member.exception` |
| `InvalidPasswordPolicyException` | 400 | `INVALID_PASSWORD_POLICY` | `com.econo.auth.core.member.exception` | `com.econo.auth.member.exception` |
| `InvalidCredentialsException` | 401 | `INVALID_CREDENTIALS` | `com.econo.auth.core.member.exception` | `com.econo.auth.member.exception` |

`GlobalExceptionHandler`의 `@ExceptionHandler` 메서드 시그니처, HTTP 상태, `ApiError` 응답 바디 구조는 일체 변경되지 않는다. 변경 대상은 파일 상단 `import` 3줄뿐이다.

**공통 에러 응답 스키마 (무변경)**

```json
{
  "errorCode": "MEMBER_NOT_FOUND",
  "message": "해당 회원이 존재하지 않습니다.",
  "timestamp": "2026-06-04T10:00:00",
  "fieldErrors": null
}
```

Bean Validation 실패 시 `fieldErrors` 배열 포함:

```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-06-04T10:00:00",
  "fieldErrors": [
    { "field": "loginId", "message": "공백일 수 없습니다" }
  ]
}
```

---

### 요청·응답 스키마 — 무변경

아래는 회귀 검증 시 기준점으로 사용할 실제 스키마다.

#### POST `/api/v1/auth/signup`

요청:
```json
{
  "name": "홍길동",
  "loginId": "hong123",
  "password": "Pass@1234",
  "generation": 10,
  "status": "AM"
}
```

응답 (성공):
```
HTTP 201 Created
(body 없음)
```

#### POST `/api/v1/auth/login`

요청 (`Content-Type: application/json`):
```json
{
  "loginId": "hong123",
  "password": "Pass@1234"
}
```

응답 (성공, WEB):
```
HTTP 200 OK
Set-Cookie: at=<JWT>; HttpOnly; Path=/; Max-Age=...
Set-Cookie: rt=<JWT>; HttpOnly; Path=/; Max-Age=...
```

#### POST `/api/v1/auth/reissue`

요청 (WEB, `Client-Type: WEB` 기본):
```
Cookie: rt=<refresh_token>
```

요청 (APP, `Client-Type: APP`):
```json
{ "refreshToken": "<refresh_token>" }
```

응답 (성공, APP):
```json
{
  "accessToken": "<JWT>",
  "accessExpiredAt": "2026-06-04T11:00:00",
  "refreshToken": "<JWT>"
}
```

#### POST `/api/v1/members/batch`

요청:
```json
{ "ids": [1, 2, 42] }
```

응답 (성공):
```json
[
  { "memberId": 1, "name": "홍길동", "loginId": "hong123", "generation": 10, "status": "AM" },
  { "memberId": 2, "name": "김영희", "loginId": "kim456",  "generation": 11, "status": "RM" }
]
```

존재하지 않는 ID는 결과에서 조용히 제외 (오류 없음).

#### POST `/api/v1/clients`

요청:
```json
{
  "grantType": "client_credentials",
  "clientName": "my-service",
  "redirectUris": [],
  "upstreamUrl": "http://my-service:8080",
  "pathPrefix": "/my"
}
```

응답 (성공):
```
HTTP 201 Created
```
```json
{
  "clientId": "uuid-xxx",
  "clientSecret": "raw-secret-1회만-반환",
  "routeId": "uuid-yyy"
}
```

#### GET `/api/v1/routes`

응답 (성공):
```json
{
  "routes": [
    { "routeId": "uuid-yyy", "clientId": "uuid-xxx", "upstreamUrl": "http://my-service:8080", "pathPrefix": "/my" }
  ]
}
```

#### GET `/api/v1/clients/{clientId}`

요청 헤더:
```
Authorization: Basic base64(clientId:clientSecret)
```

응답 (성공):
```json
{
  "clientId": "uuid-xxx",
  "clientName": "my-service",
  "redirectUris": ["https://app.example.com/callback"]
}
```

#### GET `/oauth2/jwks`

응답 (성공):
```json
{
  "keys": [
    { "kty": "RSA", "kid": "...", "n": "...", "e": "AQAB" }
  ]
}
```

---

### 공통 헤더/메타데이터

| 헤더 | 방향 | 용도 | 비고 |
|------|------|------|------|
| `Content-Type: application/json` | 요청 | JSON 바디 전송 | 로그인/배치 조회 필수 |
| `Authorization: Basic ...` | 요청 | Basic Auth | `/api/v1/clients/{clientId}/**` 전용 |
| `Client-Type: WEB\|APP` | 요청 | 재발급/로그아웃 채널 구분 | 기본값 WEB |
| `Set-Cookie: at / rt` | 응답 | WEB 채널 JWT 전달 | HttpOnly, Secure |
| `X-User-Passport` | 요청 (Gateway 주입) | 회원 ID 전달 | `/api/v1/members/**` 내부 전달용 |

트레이스 헤더(`X-Request-Id` 등) 및 페이지네이션 헤더는 현재 프로젝트에서 사용되지 않는다.

---

## 체크리스트

- [x] todo의 모든 API 작업이 엔드포인트로 명세됨 — todo `API 작업` 섹션: "해당 없음 (엔드포인트 추가·변경 없음)" 확인
- [x] 각 엔드포인트의 인증/권한이 명시됨 (기본값 의존 X) — Public/JSON Login/Basic Auth/Gateway 위임 4가지 정책 모두 명시
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨 — `GlobalExceptionHandler.ApiError` 레코드 사용, 신규 에러 코드 없음
- [x] 요청·응답 스키마가 실제 본문 예시로 작성됨 — 주요 엔드포인트 JSON 예시 포함
- [x] 프로젝트 표준 헤더/메타데이터가 누락 없이 명시됨 — 공통 헤더 표 작성 완료

---

## 참고

- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — authorizeHttpRequests 규칙, 인증 정책 출처
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — ApiError 응답 스키마, 예외-HTTP 매핑 출처
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberController.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberInfoController.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/ReissueController.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/JwksController.java`
