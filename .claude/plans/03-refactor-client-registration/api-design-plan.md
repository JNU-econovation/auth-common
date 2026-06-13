# refactor-client-registration - api-design

## 메타
- **작업명**: refactor-client-registration
- **문서 타입**: api-design
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

이 문서는 `POST /api/v1/admin/clients` 엔드포인트의 요청 스펙 변경(OAuth 필드 optional화)과 에러 응답 매트릭스 갱신을 다룬다. 프로토콜은 REST/JSON이며 Spring MVC `@RestController` 패턴을 따른다. 변경 대상은 단 1개 엔드포인트이고, 나머지 admin 엔드포인트 6개는 API 표면 변경 없이 현행 유지된다.

---

## 본문

### 엔드포인트 목록

| 메서드 | 경로 | 설명 | 인증 / 권한 | 변경 여부 | 연관 todo |
|--------|------|------|-------------|-----------|-----------|
| POST | `/api/v1/admin/clients` | OAuth 클라이언트 등록 | X-Internal-Api-Key 필수 | **변경** (grantType optional) | API-1, API-2 |
| GET | `/api/v1/admin/routes` | Gateway 라우트 목록 조회 | X-Internal-Api-Key 필수 | 변경 없음 | — |
| GET | `/api/v1/admin/clients/{clientId}` | 클라이언트 조회 (redirectUri 포함) | X-Internal-Api-Key 필수 | 변경 없음 | — |
| POST | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 추가 | X-Internal-Api-Key 필수 | 변경 없음 | — |
| DELETE | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 제거 | X-Internal-Api-Key 필수 | 변경 없음 | — |
| PUT | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 전체 교체 | X-Internal-Api-Key 필수 | 변경 없음 | — |

---

### 엔드포인트 상세

#### POST /api/v1/admin/clients

- **목적**: OAuth 클라이언트를 등록하고 `clientId`와 `clientSecret`(client_credentials 및 grantType 생략 시)을 발급한다.
- **연관 todo**: `[ ] [API-1] POST /api/v1/admin/clients 요청 스펙 변경 — grantType optional 명시`, `[ ] [API-2] POST /api/v1/admin/clients 400 오류 코드 표 갱신`

---

#### 전/후 비교 — 요청 스펙

**변경 전 (현행)**

| 필드 | 타입 | 필수 여부 | 비고 |
|------|------|-----------|------|
| `grantType` | String | **필수 (사실상)** | null → `GrantType.fromString(null)` → `UnsupportedGrantTypeException` → 400 |
| `clientName` | String | 필수 (`@NotBlank`) | 빈 문자열 → `VALIDATION_FAILED` |
| `redirectUris` | Set\<String\> | 조건부 필수 | `authorization_code`이면서 null/빈 Set → `REDIRECT_URI_REQUIRED`; null은 빈 Set으로 정규화됨 |
| `upstreamUrl` | String | 선택 | null 허용 |
| `pathPrefix` | String | 선택 | null 허용 |

**변경 후 (이번 작업)**

| 필드 | 타입 | 필수 여부 | 비고 |
|------|------|-----------|------|
| `grantType` | String \| null | **선택** | null → 서비스에서 `CLIENT_CREDENTIALS` 디폴트 적용. 비-null 미지원 값 → `UNSUPPORTED_GRANT_TYPE` |
| `clientName` | String | 필수 (`@NotBlank`) | 빈 문자열 → `VALIDATION_FAILED`. 변경 없음 |
| `redirectUris` | Set\<String\> \| null | 조건부 (변경 없음) | null → 빈 Set 정규화(기존 로직 유지). `grantType=authorization_code`이면서 비어있으면 `REDIRECT_URI_REQUIRED` |
| `upstreamUrl` | String \| null | 선택 | 변경 없음 |
| `pathPrefix` | String \| null | 선택 | 변경 없음 |

---

- **요청 헤더**:

  | 헤더 | 필수 | 값 |
  |------|------|----|
  | `Content-Type` | 필수 | `application/json` |
  | `X-Internal-Api-Key` | 필수 | 환경변수 `AUTH_INTERNAL_API_KEY` 값과 일치해야 함 |

- **요청 바디 — 시나리오별 예시**:

  *시나리오 A: grantType 생략 (이번 작업으로 신규 허용)*
  ```json
  {
    "clientName": "app-b"
  }
  ```

  *시나리오 B: grantType 생략 + Gateway 라우트 동시 등록*
  ```json
  {
    "clientName": "order-service",
    "upstreamUrl": "http://order-service:8080",
    "pathPrefix": "/api/orders"
  }
  ```

  *시나리오 C: authorization_code 명시 (기존 하위 호환)*
  ```json
  {
    "grantType": "authorization_code",
    "clientName": "테스트 SPA",
    "redirectUris": ["http://localhost:3000/callback"]
  }
  ```

  *시나리오 D: client_credentials 명시 (기존 하위 호환)*
  ```json
  {
    "grantType": "client_credentials",
    "clientName": "배치 서비스"
  }
  ```

- **응답 (성공)**:
  - 상태: `201 Created`
  - 응답 DTO: `RegisterClientResponse` (변경 없음 — `@JsonInclude(NON_NULL)` 적용)

  *시나리오 A/D (client_credentials 또는 grantType 생략)*:
  ```json
  {
    "clientId": "550e8400-e29b-41d4-a716-446655440000",
    "clientSecret": "xK9mL2pQ..."
  }
  ```
  > `clientSecret`은 이 응답에서만 1회 제공. 이후 재조회 불가.

  *시나리오 C (authorization_code)*:
  ```json
  {
    "clientId": "550e8400-e29b-41d4-a716-446655440001"
  }
  ```
  > `clientSecret` 필드 자체가 응답에 포함되지 않음 (`@JsonInclude(NON_NULL)` 동작).

  *시나리오 B (grantType 생략 + upstreamUrl 지정)*:
  ```json
  {
    "clientId": "550e8400-e29b-41d4-a716-446655440002",
    "clientSecret": "aB3cD4eF...",
    "routeId": "route-uuid-001"
  }
  ```

---

### 에러 응답 매트릭스

컨트롤러 자체 에러 DTO (`AdminClientController.ErrorResponse`) 와 전역 핸들러(`GlobalExceptionHandler.ApiError`) 두 종류가 혼재한다. POST /clients 에서 발생하는 에러별 실제 직렬화 형태는 아래와 같다.

#### 401 — 인증 실패 (컨트롤러 직접 응답, `ErrorResponse` 타입)

```json
{
  "errorCode": "UNAUTHORIZED",
  "message": "유효하지 않은 Internal API Key입니다.",
  "timestamp": "2026-06-03T12:00:00"
}
```

> 이번 작업 범위 밖. 변경 없음.

#### 400 에러 코드별 상세 (전역 핸들러 응답, `ApiError` 타입)

| 에러 코드 | HTTP | 발생 조건 | 이번 작업 영향 |
|-----------|------|-----------|----------------|
| `VALIDATION_FAILED` | 400 | `clientName`이 null이거나 빈 문자열 | **유지** — 변경 없음 |
| `UNSUPPORTED_GRANT_TYPE` | 400 | `grantType`이 null이 아닌데 `authorization_code` / `client_credentials` 이외의 값 (예: `"password"`) | **유지**, 단 발생 조건 명세 갱신 ("null이 아닌 알 수 없는 값일 때만 발생") |
| `REDIRECT_URI_REQUIRED` | 400 | `grantType=authorization_code`를 명시했는데 `redirectUris`가 비어있음 | **유지**, 단 발생 조건 명세 갱신 ("grantType이 authorization_code로 명시된 경우에만 발생 가능") |

> `REDIRECT_URI_REQUIRED`가 이번 작업으로 **제거되지 않는다**. grantType을 명시적으로 `authorization_code`로 보내면서 redirectUris를 생략하는 경우는 여전히 400을 반환한다. 생략 자체가 불가능해지는 케이스는 `grantType`을 null로 보내는 경우뿐이며, 이때는 서비스가 `CLIENT_CREDENTIALS`로 디폴트 처리하여 `REDIRECT_URI_REQUIRED`에 진입하지 않는다.

*VALIDATION_FAILED 예시:*
```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-06-03T12:00:00",
  "fieldErrors": [
    { "field": "clientName", "message": "공백일 수 없습니다" }
  ]
}
```

*UNSUPPORTED_GRANT_TYPE 예시 (grantType: "password"):*
```json
{
  "errorCode": "UNSUPPORTED_GRANT_TYPE",
  "message": "지원하지 않는 그랜트 타입입니다: password",
  "timestamp": "2026-06-03T12:00:00",
  "fieldErrors": null
}
```

*REDIRECT_URI_REQUIRED 예시 (grantType: "authorization_code", redirectUris 생략):*
```json
{
  "errorCode": "REDIRECT_URI_REQUIRED",
  "message": "authorization_code 타입은 redirectUris가 필요합니다.",
  "timestamp": "2026-06-03T12:00:00",
  "fieldErrors": null
}
```

#### 409 — 중복 (전역 핸들러 응답)

| 에러 코드 | 발생 조건 | 이번 작업 영향 |
|-----------|-----------|----------------|
| `DUPLICATE_CLIENT_NAME` | `clientName` 중복 | 유지 |
| `DUPLICATE_RESOURCE` | DB UNIQUE 제약 위반 (pathPrefix 또는 clientName) | 유지 |

---

### 하위 호환성 보장 시나리오

다음 두 시나리오가 이번 변경 후에도 동일하게 `201`을 반환해야 한다.

**시나리오 1 — authorization_code (기존 클라이언트)**
```
요청: { "grantType": "authorization_code", "clientName": "app-b", "redirectUris": ["https://app-b.example.com/callback"] }
기대: 201 + clientId (clientSecret 없음)
```

**시나리오 2 — client_credentials (기존 클라이언트)**
```
요청: { "grantType": "client_credentials", "clientName": "batch-job" }
기대: 201 + clientId + clientSecret
```

**시나리오 3 — grantType 생략 (신규 허용)**
```
요청: { "clientName": "app-b" }
기대: 201 + clientId + clientSecret (CLIENT_CREDENTIALS 디폴트 적용)
```

이 세 시나리오는 컨트롤러 → 서비스 흐름에서 `grantType` nullable 처리에 의해 모두 동일한 성공 경로를 통과한다.

---

### 영향 받지 않는 엔드포인트

아래 6개 엔드포인트는 이번 작업의 API 표면 변경 대상이 아니다. 기존 계약을 그대로 유지하며, 이번 작업이 이들의 동작을 깨지 않는지 확인만 필요하다.

| 엔드포인트 | 확인 포인트 |
|-----------|------------|
| `GET /api/v1/admin/routes` | 변경 없음. `RegisterOAuthClientService.getRoutes()` 경로 미수정. |
| `GET /api/v1/admin/clients/{clientId}` | 변경 없음. `ClientRedirectUriService.findByClientId()` 경로 미수정. |
| `POST /api/v1/admin/clients/{clientId}/redirect-uris` | 변경 없음. redirectUri 추가 로직 미수정. |
| `DELETE /api/v1/admin/clients/{clientId}/redirect-uris` | 변경 없음. redirectUri 제거 로직 미수정. |
| `PUT /api/v1/admin/clients/{clientId}/redirect-uris` | 변경 없음. redirectUri 전체 교체 로직 미수정. |

redirect-uris CRUD 3개는 `AdminClientController`의 `redirectUriService` 의존을 통해 동작하며, 이번 작업에서 수정되는 `RegisterOAuthClientService` / `GrantType` / `ServiceClientJpaEntity`와 코드 경로가 분리되어 있다.

---

### 통합 테스트 시나리오

test-writer(`TEST-1` ~ `TEST-5`, `BUILD-1`) 가 참고할 시나리오 명세.

#### [T-1] 단순 등록 — grantType 생략 (신규, todo TEST-2/TEST-3/TEST-4에 대응)

```
전제조건: DB 클린 상태
요청: POST /api/v1/admin/clients
      X-Internal-Api-Key: {valid}
      { "clientName": "no-grant-app" }
기대:
  - HTTP 201
  - 응답 바디에 clientId 존재 (UUID 형식)
  - 응답 바디에 clientSecret 존재 (비어있지 않은 문자열)
  - routeId 없음 (JSON 필드 자체 미포함)
  - DB: service_client.grant_type = NULL
  - DB: service_client.api_key_hash != NULL (SHA-256 hex)
```

#### [T-2] 단순 등록 — grantType null, redirectUris 빈 Set (신규)

```
요청: { "clientName": "no-grant-app-2", "redirectUris": [] }
기대: HTTP 201, clientSecret 포함 (CLIENT_CREDENTIALS 디폴트 경로 진입)
  - REDIRECT_URI_REQUIRED 발생하지 않음
```

#### [T-3] 하위 호환 — authorization_code (기존 유지)

```
요청: { "grantType": "authorization_code", "clientName": "spa-app", "redirectUris": ["https://spa.example.com/callback"] }
기대: HTTP 201, clientSecret 없음
```

#### [T-4] 하위 호환 — client_credentials (기존 유지)

```
요청: { "grantType": "client_credentials", "clientName": "batch-job" }
기대: HTTP 201, clientSecret 있음
```

#### [T-5] 에러 — authorization_code이면서 redirectUris 없음 (기존 동작 유지)

```
요청: { "grantType": "authorization_code", "clientName": "bad-spa" }
기대: HTTP 400, errorCode = "REDIRECT_URI_REQUIRED"
```

#### [T-6] 에러 — grantType이 비-null 미지원 값 (기존 동작 유지)

```
요청: { "grantType": "password", "clientName": "old-app" }
기대: HTTP 400, errorCode = "UNSUPPORTED_GRANT_TYPE"
```

#### [T-7] 에러 — clientName 빈 문자열 (기존 동작 유지)

```
요청: { "clientName": "" }
기대: HTTP 400, errorCode = "VALIDATION_FAILED", fieldErrors[0].field = "clientName"
```

#### [T-8] 에러 — X-Internal-Api-Key 없음 (기존 동작 유지)

```
요청: POST /api/v1/admin/clients (헤더 없음), { "clientName": "app" }
기대: HTTP 401, errorCode = "UNAUTHORIZED"
```

#### [T-9] DB 검증 — grantType NULL 저장 확인 (todo TEST-4에 대응)

```
[T-1] 실행 후 JDBC로 service_client 테이블 직접 조회:
  SELECT grant_type FROM service_client WHERE client_name = 'no-grant-app'
기대: grant_type IS NULL
```

#### [T-10] 기존 테스트 삭제/변경 — grantType null 예외 테스트 (todo TEST-1에 대응)

```
RegisterOAuthClientServiceTest의 "grantType null이면 IllegalArgumentException" 케이스:
  - 삭제 또는 아래로 교체:
    "grantType null → CLIENT_CREDENTIALS 디폴트 적용, rawSecret 반환"
    기대: 예외 없음, result.clientSecret() != null
```

---

## 체크리스트
- [x] todo의 모든 API 작업(API-1, API-2)이 엔드포인트로 명세됨
- [x] 각 엔드포인트의 인증/권한이 명시됨 (기본값 의존 X — X-Internal-Api-Key 헤더 방식 명시)
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨 (GlobalExceptionHandler 예외 클래스 기준)
- [x] 요청·응답 스키마가 실제 본문 예시로 작성됨 (시나리오 A~D)
- [x] 프로젝트 표준 헤더(X-Internal-Api-Key, Content-Type) 누락 없이 명시됨
- [x] 에러 응답 타입 이중 구조(ErrorResponse vs ApiError) 명시됨
- [x] 영향 받지 않는 엔드포인트 6개 명시됨
- [x] 하위 호환 시나리오 3개 명시됨
- [x] 통합 테스트 시나리오 10개 작성됨

---

## 참고
- 컨트롤러 소스: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java`
- 서비스 소스: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/usecase/RegisterOAuthClientService.java`
- 전역 예외 핸들러: `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`
- 도메인 enum: `services/apis/auth-api/src/main/java/com/econo/auth/api/domain/GrantType.java`
- 기존 웹 레이어 테스트: `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java`
- 에러 응답 이중 구조 주의: `AdminClientController.ErrorResponse`(컨트롤러 내부 record, `{errorCode, message, timestamp}`)와 `GlobalExceptionHandler.ApiError`(`{errorCode, message, timestamp, fieldErrors}`)는 별개 타입이나 JSON 직렬화 형태는 유사함.
