# client-self-registration-and-app-redirect - api-design

## 메타
- **작업명**: client-self-registration-and-app-redirect
- **문서 타입**: api-design
- **작성일**: 2026-06-07
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

두 기능의 API 변경을 명세한다. 기능 A는 인증된 에코노 회원이 SSO 클라이언트를 직접 등록할 수 있는 셀프 등록 엔드포인트 신설이고, 기능 B는 기존 APP 로그인 응답 body에 `redirectUrl` 필드를 추가하는 변경이다. 모든 엔드포인트는 REST + JSON이며, 인증은 Gateway가 주입하는 `X-User-Passport` 헤더(Base64 인코딩된 Passport JSON)를 통해 수행한다.

---

## 설계 결정 — 경로 방향 (A-API-1, A-API-3)

### 결정: 신규 `POST /api/v1/clients` 신설 (선택지 a)

기존 `POST /api/v1/admin/clients`는 **유지**하고, 셀프 등록 전용 경로를 `/api/v1/clients`로 신설한다.

**근거:**

1. **Gateway 경로 규칙과의 충돌 방지.** `/admin` prefix는 운영 레벨 액션(ADMIN 전용)을 의미하는 관례로 정착해 있다. 기존 `admin/clients` 경로의 나머지 엔드포인트(조회, redirect-uris CRUD)는 여전히 ADMIN 역할을 요구하므로, 같은 prefix에 다른 권한 요구사항이 혼재하면 Gateway·운영 정책 서술이 혼란스러워진다.

2. **기존 ADMIN 엔드포인트 유지.** 운영팀은 `POST /api/v1/admin/clients`를 통해 `ownerId` 없이 시스템 클라이언트를 계속 등록할 수 있어야 한다. 두 경로는 기능상 다르다 — ADMIN 경로는 소유자 개념이 없고, 셀프 등록 경로는 `ownerId`와 5개 제한이 적용된다.

3. **역할 분리가 명확함.** `/api/v1/clients`는 인증된 일반 회원, `/api/v1/admin/clients`는 ADMIN. 경로만 보고 인증 수준을 즉시 구분 가능하다.

### 기존 AdminClientController 엔드포인트 처리 방향 (A-API-3)

| 엔드포인트 | 처리 방향 | 이유 |
|---|---|---|
| `POST /api/v1/admin/clients` | **유지 (ADMIN 전용)** | 소유자 없는 시스템 클라이언트 등록용으로 존속 |
| `GET /api/v1/admin/clients/{clientId}` | **유지 (ADMIN 전용)** | ADMIN의 클라이언트 조회 권한 그대로 |
| `POST /api/v1/admin/clients/{clientId}/redirect-uris` | **유지 (ADMIN 전용)** | ADMIN이 소유자 대신 redirect-uris 관리 가능해야 함 |
| `DELETE /api/v1/admin/clients/{clientId}/redirect-uris` | **유지 (ADMIN 전용)** | 위와 동일 |
| `PUT /api/v1/admin/clients/{clientId}/redirect-uris` | **유지 (ADMIN 전용)** | 위와 동일 |

redirect-uris CRUD는 셀프 등록 컨텍스트에서도 필요하다. 단, 이 작업 범위에서는 일반 회원 전용 redirect-uris 관리 엔드포인트는 신설하지 않는다 — 이는 후속 작업(클라이언트 셀프 관리 API)으로 분리한다. 현재 셀프 등록 요청에 `redirectUris`를 함께 제출하는 것으로 충분하다.

### clientSecret과 SAS 공존 방식 (B안 확정)

셀프 등록 클라이언트의 SAS 등록 방식은 **기존 `ClientAuthenticationMethod.NONE`(public PKCE)을 그대로 유지**한다. `CLIENT_SECRET_BASIC`을 SAS `RegisteredClient`에 추가하지 않는다.

- **SAS 등록**: `ClientAuthenticationMethod.NONE` 단독 유지. `SasClientRegistrarAdapter.registerAuthorizationCodeClient`는 변경 없이 기존 방식 그대로 호출된다.
- **secret 저장**: 셀프 등록 시 발급된 `clientSecret` 평문을 BCrypt(cost 12)로 해시하여 `service_client.client_secret_hash` 컬럼에 저장한다. SAS `oauth2_registered_client.client_secret`에는 저장하지 않는다.
- **secret 검증**: SAS 표준 secret 검증 흐름(`/oauth2/token`의 `CLIENT_SECRET_BASIC` 인증)을 사용하지 않는다. 검증이 필요할 때는 우리가 직접(커스텀) `service_client.client_secret_hash`를 조회하여 수행한다.
- **현재 in-scope 용도**: 이번 작업 범위에서 `clientSecret`을 소비하는 엔드포인트는 없다. 등록 시 secret을 생성·해시 저장하여 **선발급·보관**하는 것이 이번 변경의 전부다. 향후 redirect-uri 셀프 관리 API가 신설될 때 `Authorization: Basic base64(clientId:clientSecret)` 인증 자격증명으로 활용될 예정이다.

이 결정은 ADR-0013에 기록한다.

---

## 본문

### 엔드포인트 목록

| 메서드 | 경로 | 설명 | 인증 / 권한 | 연관 todo |
|--------|------|------|-------------|-----------|
| POST | `/api/v1/clients` | SSO 클라이언트 셀프 등록 | 필요 (인증된 회원, 역할 무관) | A-API-1 |
| POST | `/api/v1/auth/login` | JSON 로그인 (APP 분기 변경) | 불필요 (permitted path) | B-API-1 |

기존 `AdminClientController` 엔드포인트 5개는 변경 없이 유지된다 (위 결정 참조).

---

### 엔드포인트 상세

---

#### POST `/api/v1/clients`

- **목적**: 인증된 에코노 회원이 자기 서비스 앱을 SSO 클라이언트로 직접 등록한다. `clientId`와 `clientSecret`을 1회 발급하고, 소유자(`ownerId`)를 기록한다. 회원당 최대 5개 제한이 적용된다.

- **연관 todo**: `[ ] [A-API-1] 셀프 등록 전용 엔드포인트 경로 및 인증 방식 확정`, `[ ] [A-API-2] 셀프 등록 에러 응답 코드 정의`

- **요청 헤더**:

  | 헤더 | 필수 | 설명 |
  |------|------|------|
  | `X-User-Passport` | 필수 | Gateway가 주입하는 Base64(Passport JSON). `memberId` 추출용. 누락·파싱 실패 시 401. |
  | `Content-Type` | 필수 | `application/json` |

- **요청 바디**:
  ```json
  {
    "clientName": "EEOS 모바일 앱",
    "redirectUris": [
      "https://app.econovation.kr/callback",
      "myapp://callback"
    ]
  }
  ```

  | 필드 | 타입 | 필수 | 설명 |
  |------|------|------|------|
  | `clientName` | String | 필수 | 빈 문자열 불가 (`@NotBlank`) |
  | `redirectUris` | Set\<String\> | 필수 | 비어있으면 `REDIRECT_URI_REQUIRED` |

- **응답 (성공)**:
  - 상태: `201 Created`
  - 바디:
    ```json
    {
      "clientId": "550e8400-e29b-41d4-a716-446655440000",
      "clientSecret": "xK9mL2pQrT8nVwJ3hY7cF1sDuBzAeGiN"
    }
    ```
  - `clientSecret`은 이 응답에서만 1회 노출된다. 생성 흐름: `UUID.randomUUID().toString()` 평문 생성 → BCrypt(cost 12) 해시 → `service_client.client_secret_hash`에 해시만 저장 → 평문은 이 응답에만 포함. `@JsonInclude(NON_NULL)` 적용 클래스이므로 null 필드는 직렬화에서 제외된다.

- **응답 (에러)** — 기존 `GlobalExceptionHandler`·`ApiError` 체계 사용:

  | HTTP 상태 | errorCode | 발생 조건 |
  |-----------|-----------|-----------|
  | 401 | `AUTH_UNAUTHORIZED` | `X-User-Passport` 헤더 누락 또는 Base64 디코딩 실패 (PassportException) |
  | 400 | `VALIDATION_FAILED` | `clientName` 빈 문자열 (MethodArgumentNotValidException → GlobalExceptionHandler) |
  | 400 | `REDIRECT_URI_REQUIRED` | `redirectUris` 누락 또는 빈 Set (RedirectUriRequiredException → GlobalExceptionHandler) |
  | 409 | `DUPLICATE_CLIENT_NAME` | 동일 clientName 이미 존재 (DuplicateClientNameException → GlobalExceptionHandler) |
  | 422 | `CLIENT_LIMIT_EXCEEDED` | 해당 memberId 소유 클라이언트 수 >= 5 (ClientLimitExceededException → GlobalExceptionHandler에 신규 등록 필요) |

  > **`CLIENT_LIMIT_EXCEEDED` HTTP 상태 422 선택 근거**: 400은 요청 형식 오류, 429는 속도 제한(rate limit) 의미론. 5개 제한은 "유효한 요청이지만 사전 조건(비즈니스 규칙)을 충족하지 못해 처리 불가"이므로 422 Unprocessable Content가 의미론적으로 정확하다. 기존 `RedirectUriRequiredException`(400)과 혼동되지 않으며, 클라이언트가 "요청을 수정해도 해결되지 않는다"는 신호를 명확히 준다.

  에러 바디 형식 (기존 `ApiError` record 그대로):
  ```json
  {
    "errorCode": "CLIENT_LIMIT_EXCEEDED",
    "message": "클라이언트 등록은 최대 5개까지 가능합니다.",
    "timestamp": "2026-06-07T10:30:00",
    "fieldErrors": null
  }
  ```

- **인증 / 권한**:
  - 필요 여부: 필요 (인증된 회원이면 모두 허용)
  - 필요 역할: 없음 (역할 무관 — `ADMIN`, `SUPER_ADMIN` 불필요. `USER`도 허용)
  - 인증 수단: `X-User-Passport` 헤더에서 `memberId` 추출. `memberId`가 non-null이면 인증된 회원으로 판단.
  - `AdminClientController`의 `isAdmin()` 패턴을 참조하되, 역할 검사 없이 **memberId non-null 여부만** 확인하는 `isAuthenticated()` 가드로 구현한다.
  - Gateway permittedPath 설정 외부: `/api/v1/clients`는 `X-User-Passport`가 있어야 하므로 Gateway에서 인증된 요청에만 허용 (permittedPath에 추가하지 않음).

- **비고**:
  - `clientId`는 `UUID.randomUUID().toString()` (기존 `RegisterOAuthClientService.register()` 패턴 동일).
  - `clientSecret` 처리 (B안): `UUID.randomUUID().toString()` 평문 생성 → BCrypt(cost 12) 해시 → `service_client.client_secret_hash` 저장 → 평문은 응답에만 포함. SAS `RegisteredClient`는 `ClientAuthenticationMethod.NONE` 단독 유지 (secret을 SAS에 전달하지 않음).
  - 현재 `clientSecret`을 검증하는 in-scope 엔드포인트는 없다. 향후 redirect-uri 셀프 관리 API 신설 시 `Authorization: Basic base64(clientId:clientSecret)` 자격증명으로 활용 예정.
  - 멱등성 없음. 동일 요청을 중복 호출하면 `DUPLICATE_CLIENT_NAME`(409) 반환.

---

#### POST `/api/v1/auth/login` (APP 분기 변경)

- **목적**: 기존 JSON 로그인 엔드포인트의 APP 분기(`Client-Type: APP`) 응답 body에 `redirectUrl` 필드를 추가한다. WEB 분기(302 리다이렉트)는 변경 없음.

- **연관 todo**: `[ ] [B-API-1] POST /api/v1/auth/login — APP 분기 응답 스펙 변경`

- **요청 헤더**:

  | 헤더 | 필수 | 설명 |
  |------|------|------|
  | `Client-Type` | 선택 | `APP`이면 APP 분기. 생략 또는 `WEB`이면 WEB 분기(302). |
  | `Content-Type` | 필수 | `application/json` |

- **요청 바디**:
  ```json
  {
    "loginId": "user@econovation.kr",
    "password": "P@ssw0rd!",
    "clientId": "550e8400-e29b-41d4-a716-446655440000"
  }
  ```

  | 필드 | 타입 | 필수 | 설명 |
  |------|------|------|------|
  | `loginId` | String | 필수 | 회원 로그인 ID |
  | `password` | String | 필수 | 비밀번호 |
  | `clientId` | String | 선택 | 없으면 `redirectUrl`이 `default-url`로 fallback |

- **응답 (성공) — WEB 분기 (변경 없음)**:
  - 상태: `302 Found`
  - 헤더:
    ```
    Location: https://app.econovation.kr/
    Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=3600
    Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Path=/; Max-Age=2592000
    ```
  - 바디: 없음

- **응답 (성공) — APP 분기 (변경)**:
  - 상태: `200 OK`
  - 헤더: `Content-Type: application/json`
  - 바디 (변경 전):
    ```json
    {
      "accessToken": "eyJhbGci...",
      "accessExpiredTime": 1749290400,
      "refreshToken": "eyJhbGci..."
    }
    ```
  - 바디 (변경 후):
    ```json
    {
      "accessToken": "eyJhbGci...",
      "accessExpiredTime": 1749290400,
      "refreshToken": "eyJhbGci...",
      "redirectUrl": "https://app.econovation.kr/"
    }
    ```

  | 필드 | 타입 | 설명 |
  |------|------|------|
  | `accessToken` | String | AT JWT (기존) |
  | `accessExpiredTime` | Long | AT 만료 epoch millis (기존) |
  | `refreshToken` | String | RT JWT (기존) |
  | `redirectUrl` | String | **신규** — `LoginRedirectResolver.resolve(clientId, defaultUrl)` 결과. `clientId` 누락·미등록·오류 시 `auth.redirect.default-url` |

  `LoginResponse`는 `@JsonInclude(NON_NULL)`이 이미 적용되어 있다. `redirectUrl`이 null인 경우는 이번 설계에서 발생하지 않는다 — `LoginRedirectResolver`는 항상 non-null을 반환(fallback이 `default-url`이므로). 단, null-safe를 위해 `@JsonInclude` 보호를 그대로 유지한다.

  > WEB 분기에서는 `LoginResponse` body가 직렬화되지 않으므로 `redirectUrl` 필드 추가가 WEB 응답에 영향을 미치지 않는다.

- **응답 (에러)**:

  | HTTP 상태 | errorCode | 발생 조건 |
  |-----------|-----------|-----------|
  | 401 | `INVALID_CREDENTIALS` | 아이디 또는 비밀번호 불일치 (`JsonLoginAuthenticationFilter.unsuccessfulAuthentication()` 직접 처리 — `GlobalExceptionHandler` 미경유) |

  에러 바디 (기존 형식 그대로):
  ```json
  {
    "errorCode": "INVALID_CREDENTIALS",
    "message": "아이디 또는 비밀번호가 올바르지 않습니다.",
    "timestamp": "2026-06-07T10:30:00",
    "fieldErrors": null
  }
  ```

- **인증 / 권한**:
  - 필요 여부: 없음 (Gateway permittedPath)
  - 필요 역할: 없음
  - 추가 조건: `clientId`는 선택 필드. 누락·미등록(`InvalidClientException`)·기타 오류 시 `LoginRedirectResolver`가 `default-url`로 fallback하여 로그인 자체는 성공 처리 (ADR-0012 결정 그대로).

- **비고**:
  - `redirectUrl` 결정 로직: `LoginRedirectResolver.resolve(clientId, defaultRedirectUrl)`. APP 분기에서 `request.getAttribute("clientId")`로 추출한 값을 전달. WEB 분기와 동일한 `LoginRedirectResolver` 인스턴스 재사용.
  - `clientId`가 null(요청에 미포함)이면 resolver 내부에서 `default-url` 반환.
  - WEB 분기(`sendRedirect`)와 APP 분기(`body`) 모두 `LoginRedirectResolver`를 호출하여 동일한 목적지 결정 로직을 공유한다.

---

## 신규 에러 코드 추가

이번 작업에서 기존 에러 체계에 추가되는 코드:

| HTTP 상태 | errorCode | 예외 클래스 | 위치 | 발생 조건 |
|-----------|-----------|-------------|------|-----------|
| 422 | `CLIENT_LIMIT_EXCEEDED` | `ClientLimitExceededException` (신규) | `service-client` 모듈 `exception` 패키지 | 동일 memberId 소유 클라이언트 수 >= 5 |

`GlobalExceptionHandler`에 신규 `@ExceptionHandler(ClientLimitExceededException.class)` 핸들러 추가 필요 (A-IMPL-7, A-IMPL-8).

---

## 체크리스트
- [x] todo의 모든 API 작업이 엔드포인트로 명세됨 (A-API-1, A-API-2, A-API-3, B-API-1)
- [x] 각 엔드포인트의 인증/권한이 명시됨 (기본값 의존 X)
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨
- [x] 요청·응답 스키마가 실제 본문 예시로 작성됨
- [x] 프로젝트 표준 헤더/메타데이터(`X-User-Passport`, `Client-Type`, `Content-Type`)가 명시됨
- [x] clientSecret 1회 노출 방식 명시 (`@JsonInclude(NON_NULL)` 보호 하에 응답에만 포함)
- [x] WEB vs APP 응답 분기 명확화
- [x] clientSecret + SAS 공존 방식 B안 확정 — SAS는 NONE 유지, secret은 `service_client.client_secret_hash`에 저장, 검증은 직접 구현 (현재 in-scope 검증 엔드포인트 없음)

---

## 참고
- `docs/ARCHITECTURE.md` — 에러 코드 체계, 인증 흐름 A/B, 헥사고날 구조
- `docs/CONVENTION.md` — 네이밍·JSON 직렬화 컨벤션
- `docs/adr/0010-client-secret-self-service-auth.md` — clientSecret Basic Auth 모델 (supersede 대상)
- `docs/adr/0012-backend-decided-login-redirect.md` — APP 분기 redirectUrl 설계 근거
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` — 기존 ADMIN 등록 엔드포인트, Passport 파싱 패턴
- `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` — APP 분기 successfulAuthentication 구현
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/LoginResponse.java` — @JsonInclude(NON_NULL) 적용 현황
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — ApiError 표준 구조
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientService.java` — 기존 등록 서비스 (clientId UUID 생성 패턴)
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/sas/SasClientRegistrarAdapter.java` — SAS 등록 어댑터 (ClientAuthenticationMethod.NONE 현황)
