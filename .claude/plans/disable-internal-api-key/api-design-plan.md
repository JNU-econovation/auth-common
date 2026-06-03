# disable-internal-api-key - api-design

## 메타
- **작업명**: disable-internal-api-key
- **문서 타입**: api-design
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md

---

## 개요

`AdminClientController`의 7개 엔드포인트에서 `X-Internal-Api-Key` 헤더 인증을 제거하고,
등록/라우트 조회 2개는 public으로 전환, redirect-uris CRUD + 클라이언트 조회 4개는
HTTP Basic Auth(`Authorization: Basic base64(clientId:clientSecret)`)로 교체한다 (ADR-0010).
프로토콜은 REST(Spring MVC `@RestController`), 베이스 경로는 `/api/v1/admin`,
응답 포맷은 `AdminClientController.ErrorResponse` record(`errorCode`, `message`, `timestamp`)를 그대로 유지한다.

---

## 본문

### 엔드포인트 목록

| 메서드 | 경로 | 설명 | 기존 인증 | 신규 인증 | 연관 todo |
|--------|------|------|-----------|-----------|-----------|
| POST | `/api/v1/admin/clients` | OAuth 클라이언트 등록 | X-Internal-Api-Key | **없음 (public)** | API 작업 #1 |
| GET | `/api/v1/admin/routes` | Gateway 라우트 목록 조회 | X-Internal-Api-Key | **없음 (public)** | API 작업 #2 |
| GET | `/api/v1/admin/clients/{clientId}` | 클라이언트 상세 조회 | X-Internal-Api-Key | **Basic Auth** | API 작업 #3 |
| POST | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 추가 | X-Internal-Api-Key | **Basic Auth** | API 작업 #4 |
| DELETE | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 제거 | X-Internal-Api-Key | **Basic Auth** | API 작업 #5 |
| PUT | `/api/v1/admin/clients/{clientId}/redirect-uris` | redirectUri 전체 교체 | X-Internal-Api-Key | **Basic Auth** | API 작업 #6 |

> Swagger 갱신(API 작업 #7~#9)은 엔드포인트 설계 변경과 1:1로 대응하므로 별도 섹션에서 다룬다.

---

### 공통 사항

#### 공통 요청 헤더

| 헤더 | public 2개 | Basic Auth 4개 |
|------|------------|----------------|
| `Content-Type: application/json` | 바디 있을 때 | 바디 있을 때 |
| `Authorization: Basic <base64>` | 불필요 (무시) | **필수** |

`base64` 값은 `clientId:clientSecret`을 UTF-8 인코딩 후 Base64(RFC 4648) 인코딩한 문자열이다.
`clientSecret`은 `POST /api/v1/admin/clients` 등록 응답에서 **단 1회만** 노출된다.

#### 공통 성공 응답 헤더

별도 공통 응답 헤더 없음. 기존 엔드포인트와 동일하게 `Content-Type: application/json`만 반환한다.

#### 에러 응답 포맷

`AdminClientController.ErrorResponse` record를 그대로 사용한다.

```json
{
  "errorCode": "INVALID_CLIENT_CREDENTIALS",
  "message": "유효하지 않은 클라이언트 자격증명입니다.",
  "timestamp": "2026-06-03T12:00:00.123"
}
```

신규 에러 코드:

| errorCode | HTTP 상태 | 적용 대상 | 발생 조건 |
|-----------|-----------|-----------|-----------|
| `INVALID_CLIENT_CREDENTIALS` | 401 | Basic Auth 4개 엔드포인트 | Authorization 헤더 누락, Base64 디코딩 실패, clientId 미존재, BCrypt 불일치 |
| `FORBIDDEN_CLIENT_MISMATCH` | 403 | Basic Auth 4개 엔드포인트 | path `{clientId}` ≠ Basic Auth에서 추출한 clientId |

기존 에러 코드 `UNAUTHORIZED`는 Basic Auth 4개 엔드포인트에서 더 이상 사용하지 않는다.
public 2개 엔드포인트에서 401 응답 자체가 제거된다.

#### 401 응답 시 추가 헤더

Basic Auth 4개 엔드포인트에서 401을 반환할 때 다음 헤더를 포함한다.

```
WWW-Authenticate: Basic realm="admin"
```

#### 비기능 제약

- **HTTPS 전제**: Basic Auth는 Base64 평문 전송이므로 내부망이더라도 TLS 없는 환경에서는 도청에 취약하다.
- **BCrypt 비용**: `PasswordEncoder` 빈은 `BCryptPasswordEncoder(12)`(cost=12, ~150ms). admin 영역 저빈도 호출 한정 허용. 대량 자동화 루프에서는 사용 금지.
- **내부망 전용**: `POST /api/v1/admin/clients`가 public이므로 외부 인터넷 노출 시 등록 도배 공격 가능. `GatewayRoutingConfig`에 `/api/v1/admin/**` 라우트를 추가해서는 안 된다 (ADR-0010, todo 구현 작업 참조).
- **clientSecret 1회 노출**: 분실 시 재발급 endpoint 없음. 유출 또는 분실 시 클라이언트를 통째로 재등록해야 한다.

---

### 엔드포인트 상세

---

#### POST `/api/v1/admin/clients`

- **목적**: 새 OAuth 클라이언트를 등록하고 `clientId` (및 `clientSecret`)을 발급한다.
- **연관 todo**: `[ ] POST /api/v1/admin/clients — @RequestHeader("X-Internal-Api-Key") 파라미터 및 isValidApiKey() 검증 분기·401 블록 제거 (public 전환)`

- **인증 / 권한**:
  - 필요 여부: **없음 (public)**
  - `X-Internal-Api-Key` 헤더가 포함되어 있어도 서버가 무시한다 (하위 호환).
  - Spring Security `SecurityConfig`에서 `/api/v1/admin/**`는 이미 `permitAll()`로 열려 있으므로 추가 Security 설정 변경 불필요.

- **요청 헤더**:
  ```
  Content-Type: application/json
  ```

- **요청 바디**:
  ```json
  {
    "grantType": "client_credentials",
    "clientName": "EEOS 서버",
    "redirectUris": [],
    "upstreamUrl": "http://eeos-server:8080",
    "pathPrefix": "/api/eeos"
  }
  ```
  - `grantType`: 생략 가능. 생략 시 `client_credentials` 디폴트. `null` 이외의 알 수 없는 값이면 400.
  - `clientName`: 필수. 빈 문자열 불가.
  - `redirectUris`: 생략 가능. `authorization_code` 타입이 명시된 경우 1개 이상 필수.
  - `upstreamUrl`, `pathPrefix`: 선택. 둘 다 지정 시 Gateway 동적 라우트 등록.

- **응답 (성공)**:
  - 상태: `201 Created`
  - 바디:
    ```json
    {
      "clientId": "550e8400-e29b-41d4-a716-446655440000",
      "clientSecret": "raw-secret-xyz-1회-노출",
      "routeId": "route-uuid-001"
    }
    ```
  - `clientSecret`: `client_credentials` 타입에서만 포함. `authorization_code`에서는 필드 자체 없음(`@JsonInclude(NON_NULL)`).
  - `routeId`: `upstreamUrl` + `pathPrefix` 지정 시에만 포함.

- **응답 (에러)**:
  - `400 Bad Request` `REDIRECT_URI_REQUIRED` — `authorization_code` 명시 시 redirectUris 누락
  - `400 Bad Request` `UNSUPPORTED_GRANT_TYPE` — null 이외의 알 수 없는 grantType 값
  - `400 Bad Request` `VALIDATION_FAILED` — clientName 빈 문자열
  - `409 Conflict` `DUPLICATE_RESOURCE` — clientName 또는 pathPrefix 중복

- **비고**: 이 엔드포인트는 public이므로 외부 노출 금지. rate limiting은 이 작업 범위 외이나 ADR-0010에 재검토 조건으로 명시됨.

---

#### GET `/api/v1/admin/routes`

- **목적**: 등록된 `service_route` 전체 목록을 반환한다. 운영 점검용.
- **연관 todo**: `[ ] GET /api/v1/admin/routes — @RequestHeader("X-Internal-Api-Key") 파라미터 및 isValidApiKey() 검증 분기·401 블록 제거 (public 전환)`

- **인증 / 권한**:
  - 필요 여부: **없음 (public)**
  - `X-Internal-Api-Key` 헤더가 포함되어 있어도 무시.

- **요청 헤더**: 없음 (GET, 바디 없음)

- **요청 파라미터**: 없음

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "routes": [
        {
          "routeId": "route-uuid-001",
          "clientId": "550e8400-e29b-41d4-a716-446655440000",
          "upstreamUrl": "http://eeos-server:8080",
          "pathPrefix": "/api/eeos"
        }
      ]
    }
    ```
  - `routes`: 등록된 라우트 없으면 빈 배열 `[]`.

- **응답 (에러)**: 없음 (항상 200 또는 5xx 서버 오류만 가능)

- **비고**: 민감 정보(`clientSecret` 등) 미포함이므로 public 노출 허용 (ADR-0010).

---

#### GET `/api/v1/admin/clients/{clientId}`

- **목적**: 특정 클라이언트의 상세 정보(clientName, redirectUris)를 조회한다.
- **연관 todo**: `[ ] GET /api/v1/admin/clients/{clientId} — @RequestHeader("X-Internal-Api-Key") 파라미터 제거, Basic Auth(Authorization: Basic base64(clientId:clientSecret)) 검증으로 교체. 헤더 누락/디코딩 실패/BCrypt 불일치 시 401 INVALID_CLIENT_CREDENTIALS + WWW-Authenticate: Basic realm="admin". path {clientId} ↔ Basic Auth clientId 불일치 시 403 FORBIDDEN_CLIENT_MISMATCH.`

- **인증 / 권한**:
  - 필요 여부: **필수**
  - 방식: HTTP Basic Auth
  - 필요 자격증명: 해당 클라이언트의 `clientId` + 등록 시 발급된 `clientSecret`
  - 추가 조건: `Authorization` 헤더의 decoded clientId가 path `{clientId}`와 **정확히 일치**해야 한다. 불일치 시 403.
  - Basic Auth 검증 순서:
    1. `Authorization` 헤더 파싱 — `"Basic "` 접두사 제거 후 Base64 디코딩. 누락 또는 실패 시 401
    2. `clientId:clientSecret` 분리 — `:` 첫 번째 위치 기준 split. 파싱 실패 시 401
    3. `RegisteredClientRepository.findByClientId(clientId)` 조회 — 없으면 401
    4. `PasswordEncoder.matches(rawSecret, registeredClient.getClientSecret())` BCrypt 비교 — 불일치 시 401
    5. path `{clientId}` ↔ decoded clientId 일치 확인 — 불일치 시 403

- **요청 헤더**:
  ```
  Authorization: Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ=
  ```
  (`clientId:clientSecret`의 Base64 인코딩)

- **경로 파라미터**:
  - `{clientId}`: 조회할 클라이언트 ID

- **요청 바디**: 없음

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "clientId": "550e8400-e29b-41d4-a716-446655440000",
      "clientName": "EEOS 웹앱",
      "redirectUris": [
        "https://app.econovation.kr/callback",
        "https://dev.econovation.kr/callback"
      ]
    }
    ```

- **응답 (에러)**:
  - `401 Unauthorized` `INVALID_CLIENT_CREDENTIALS` — Authorization 헤더 누락, Base64 디코딩 실패, clientId 미존재, BCrypt 불일치 중 하나
    - 응답 헤더: `WWW-Authenticate: Basic realm="admin"`
  - `403 Forbidden` `FORBIDDEN_CLIENT_MISMATCH` — path clientId ≠ Basic Auth clientId

  ```json
  {
    "errorCode": "INVALID_CLIENT_CREDENTIALS",
    "message": "유효하지 않은 클라이언트 자격증명입니다.",
    "timestamp": "2026-06-03T12:00:00.123"
  }
  ```

---

#### POST `/api/v1/admin/clients/{clientId}/redirect-uris`

- **목적**: 기존 redirectUri를 유지하면서 새 URI를 추가한다.
- **연관 todo**: `[ ] POST /api/v1/admin/clients/{clientId}/redirect-uris — 위와 동일한 Basic Auth 검증으로 교체`

- **인증 / 권한**:
  - GET `/api/v1/admin/clients/{clientId}`와 동일한 Basic Auth 규칙 적용.
  - path `{clientId}` ↔ Basic Auth clientId 일치 강제.

- **요청 헤더**:
  ```
  Authorization: Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ=
  Content-Type: application/json
  ```

- **경로 파라미터**:
  - `{clientId}`: 대상 클라이언트 ID

- **요청 바디**:
  ```json
  {
    "uri": "https://dev.econovation.kr/callback"
  }
  ```

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "clientId": "550e8400-e29b-41d4-a716-446655440000",
      "redirectUris": [
        "https://app.econovation.kr/callback",
        "https://dev.econovation.kr/callback"
      ]
    }
    ```

- **응답 (에러)**:
  - `401 Unauthorized` `INVALID_CLIENT_CREDENTIALS` + `WWW-Authenticate: Basic realm="admin"`
  - `403 Forbidden` `FORBIDDEN_CLIENT_MISMATCH`

---

#### DELETE `/api/v1/admin/clients/{clientId}/redirect-uris`

- **목적**: 특정 redirectUri를 삭제한다.
- **연관 todo**: `[ ] DELETE /api/v1/admin/clients/{clientId}/redirect-uris — 위와 동일한 Basic Auth 검증으로 교체`

- **인증 / 권한**: POST redirect-uris와 동일.

- **요청 헤더**:
  ```
  Authorization: Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ=
  Content-Type: application/json
  ```

- **경로 파라미터**:
  - `{clientId}`: 대상 클라이언트 ID

- **요청 바디**:
  ```json
  {
    "uri": "https://dev.econovation.kr/callback"
  }
  ```

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "clientId": "550e8400-e29b-41d4-a716-446655440000",
      "redirectUris": [
        "https://app.econovation.kr/callback"
      ]
    }
    ```

- **응답 (에러)**:
  - `401 Unauthorized` `INVALID_CLIENT_CREDENTIALS` + `WWW-Authenticate: Basic realm="admin"`
  - `403 Forbidden` `FORBIDDEN_CLIENT_MISMATCH`

- **비고**: 존재하지 않는 URI 삭제 시 동작은 `ClientRedirectUriService` 구현에 위임. 이 설계 범위 외.

---

#### PUT `/api/v1/admin/clients/{clientId}/redirect-uris`

- **목적**: redirectUri 전체를 교체한다 (기존 목록 삭제 후 신규 목록으로 대체).
- **연관 todo**: `[ ] PUT /api/v1/admin/clients/{clientId}/redirect-uris — 위와 동일한 Basic Auth 검증으로 교체`

- **인증 / 권한**: POST redirect-uris와 동일.

- **요청 헤더**:
  ```
  Authorization: Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ=
  Content-Type: application/json
  ```

- **경로 파라미터**:
  - `{clientId}`: 대상 클라이언트 ID

- **요청 바디**:
  ```json
  {
    "uris": [
      "https://app.econovation.kr/callback",
      "https://staging.econovation.kr/callback"
    ]
  }
  ```

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "clientId": "550e8400-e29b-41d4-a716-446655440000",
      "redirectUris": [
        "https://app.econovation.kr/callback",
        "https://staging.econovation.kr/callback"
      ]
    }
    ```

- **응답 (에러)**:
  - `401 Unauthorized` `INVALID_CLIENT_CREDENTIALS` + `WWW-Authenticate: Basic realm="admin"`
  - `403 Forbidden` `FORBIDDEN_CLIENT_MISMATCH`

---

### 인증 전/후 매트릭스 (전체 비교)

| 엔드포인트 | 기존 | 신규 | 기존 401 에러코드 | 신규 401/403 에러코드 |
|-----------|------|------|-------------------|----------------------|
| POST /api/v1/admin/clients | X-Internal-Api-Key 필수 | **public** | `UNAUTHORIZED` | 해당 없음 |
| GET /api/v1/admin/routes | X-Internal-Api-Key 필수 | **public** | `UNAUTHORIZED` | 해당 없음 |
| GET /api/v1/admin/clients/{clientId} | X-Internal-Api-Key 필수 | Basic Auth | `UNAUTHORIZED` | `INVALID_CLIENT_CREDENTIALS` / `FORBIDDEN_CLIENT_MISMATCH` |
| POST .../redirect-uris | X-Internal-Api-Key 필수 | Basic Auth | `UNAUTHORIZED` | `INVALID_CLIENT_CREDENTIALS` / `FORBIDDEN_CLIENT_MISMATCH` |
| DELETE .../redirect-uris | X-Internal-Api-Key 필수 | Basic Auth | `UNAUTHORIZED` | `INVALID_CLIENT_CREDENTIALS` / `FORBIDDEN_CLIENT_MISMATCH` |
| PUT .../redirect-uris | X-Internal-Api-Key 필수 | Basic Auth | `UNAUTHORIZED` | `INVALID_CLIENT_CREDENTIALS` / `FORBIDDEN_CLIENT_MISMATCH` |

---

### 통합 테스트 시나리오

각 시나리오는 `AdminClientControllerTest`(MockMvc, `@WebMvcTest`) 및 `AuthApiIntegrationTest`(Testcontainers) 양쪽에서 커버한다.

#### public 전환 확인 (RegisterClientTest / GetRoutesTest 갱신)

| # | 엔드포인트 | 시나리오 | 기대 결과 |
|---|-----------|---------|-----------|
| P-1 | POST /clients | Authorization 헤더 없이 요청 | 201 Created |
| P-2 | POST /clients | X-Internal-Api-Key 헤더 포함 요청 (무시 확인) | 201 Created |
| P-3 | GET /routes | Authorization 헤더 없이 요청 | 200 OK, `routes` 배열 |
| P-4 | GET /routes | X-Internal-Api-Key 헤더 포함 요청 (무시 확인) | 200 OK |

#### Basic Auth 4개 엔드포인트 공통 (각 엔드포인트마다 4케이스)

신규 Nested class `GetClientBasicAuthTest`, `AddRedirectUriBasicAuthTest`, `RemoveRedirectUriBasicAuthTest`, `ReplaceRedirectUrisBasicAuthTest` 각각:

| # | 시나리오 | 기대 결과 |
|---|---------|-----------|
| B-1 | 올바른 clientId + clientSecret으로 Basic Auth | 200 OK (또는 해당 성공 상태) |
| B-2 | 올바른 clientId + **잘못된** clientSecret | 401, `errorCode: INVALID_CLIENT_CREDENTIALS`, `WWW-Authenticate: Basic realm="admin"` 헤더 포함 |
| B-3 | path clientId ≠ Basic Auth clientId (다른 클라이언트 자격증명 사용) | 403, `errorCode: FORBIDDEN_CLIENT_MISMATCH` |
| B-4 | Authorization 헤더 누락 | 401, `errorCode: INVALID_CLIENT_CREDENTIALS`, `WWW-Authenticate: Basic realm="admin"` 헤더 포함 |

#### 통합 테스트 추가 시나리오 (`AuthApiIntegrationTest`)

| # | 시나리오 | 기대 결과 |
|---|---------|-----------|
| I-1 | 등록 후 반환된 clientSecret으로 GET /clients/{clientId} | 200 OK |
| I-2 | 잘못된 secret으로 GET /clients/{clientId} | 401 `INVALID_CLIENT_CREDENTIALS` |
| I-3 | 다른 클라이언트의 자격증명으로 GET /clients/{clientId} | 403 `FORBIDDEN_CLIENT_MISMATCH` |
| I-4 | Authorization 헤더 없이 POST redirect-uris | 401 `INVALID_CLIENT_CREDENTIALS` |

#### 헬퍼 메서드

```java
// AdminClientControllerTest
private String basicAuthHeader(String clientId, String secret) {
    String raw = clientId + ":" + secret;
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
}

// AuthApiIntegrationTest
private String buildBasicAuthHeader(String clientId, String rawSecret) {
    return "Basic " + Base64.getEncoder()
        .encodeToString((clientId + ":" + rawSecret).getBytes(StandardCharsets.UTF_8));
}
```

---

### Swagger 갱신 명세

**연관 todo**: API 작업 #7, #8, #9

#### `@Tag` (클래스 레벨)

```
기존: "모든 엔드포인트는 X-Internal-Api-Key 헤더 인증 필요 (서버 간 내부망 전용)."
신규: "등록(POST /clients) 및 라우트 조회(GET /routes)는 인증 불필요 (public). 
      클라이언트 조회 및 redirectUri 관리(4개 엔드포인트)는 
      Authorization: Basic base64(clientId:clientSecret) 헤더 필수 (서버 내부망 전용)."
```

#### `@Operation` description 변경

| 엔드포인트 | 변경 내용 |
|-----------|----------|
| POST /clients | "**인증:** `X-Internal-Api-Key` 헤더 필수 (서버 내부망 전용)" → "**인증:** 불필요 (public)" |
| GET /routes | 동일 |
| GET /clients/{clientId} | "**인증:** X-Internal-Api-Key" → "**인증:** `Authorization: Basic base64(clientId:clientSecret)` 필수. path clientId와 Basic Auth clientId가 일치해야 함." |
| POST/DELETE/PUT redirect-uris | 동일 |

#### `@ApiResponse` 변경

| 엔드포인트 | 변경 내용 |
|-----------|----------|
| POST /clients | `responseCode = "401"` 항목 **삭제** |
| GET /routes | `responseCode = "401"` 항목 **삭제** |
| Basic Auth 4개 | `responseCode = "401"` description: "X-Internal-Api-Key 없거나 틀림" → "`INVALID_CLIENT_CREDENTIALS` — Authorization 헤더 누락, 디코딩 실패, clientId 미존재, BCrypt 불일치" |
| Basic Auth 4개 | `responseCode = "403"` **신규 추가**: "`FORBIDDEN_CLIENT_MISMATCH` — path clientId ≠ Basic Auth clientId" |

---

### `register-service` 스킬 영향

**연관 todo**: 스킬 갱신 항목

`.claude/skills/register-service/SKILL.md` 변경점:

1. **전제 조건 섹션** — 항목 2 "auth-api Internal API Key" 제거. 번호 재정렬.

2. **Step 1 curl 예시**:
   ```bash
   # 기존
   curl -X POST ${AUTH_API_URL:-http://localhost:8081}/api/v1/admin/clients \
     -H "X-Internal-Api-Key: ${AUTH_INTERNAL_API_KEY}" \
     -H "Content-Type: application/json" \
     ...

   # 신규 (X-Internal-Api-Key 줄 제거)
   curl -X POST ${AUTH_API_URL:-http://localhost:8081}/api/v1/admin/clients \
     -H "Content-Type: application/json" \
     ...
   ```

3. **완료 체크리스트** — clientId/clientSecret 저장 항목에 "(clientSecret은 등록 응답에서 1회만 노출 — 반드시 즉시 저장)" 추가.

---

## 체크리스트
- [x] todo의 모든 API 작업(#1~#9)이 엔드포인트/섹션으로 명세됨
- [x] 각 엔드포인트의 인증/권한이 명시됨 (기본값 의존 X — public 2개, Basic Auth 4개 각각 명시)
- [x] 모든 에러 케이스가 기존 에러 체계(`AdminClientController.ErrorResponse`)로 매핑됨
- [x] 신규 에러 코드 2개(`INVALID_CLIENT_CREDENTIALS`, `FORBIDDEN_CLIENT_MISMATCH`) 별도 항목 명시
- [x] 요청/응답 스키마가 실제 JSON 본문 예시로 작성됨
- [x] 인증 전/후 비교 매트릭스 포함
- [x] 통합 테스트 시나리오(각 엔드포인트 × 4케이스)와 헬퍼 메서드 시그니처 명시
- [x] HTTPS 전제, BCrypt 비용 등 비기능 제약 명시
- [x] `register-service` 스킬과의 영향 명시
- [x] `WWW-Authenticate: Basic realm="admin"` 헤더 조건 명시

---

## 참고
- `docs/adr/0010-client-secret-self-service-auth.md` — 이 작업의 근거 ADR. 인증 매트릭스 결정, 비기능 제약(HTTPS, BCrypt 비용, public 등록 도배 위험) 출처.
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` — 인증/응답 패턴 확인. `isValidApiKey()` L267-271, `ErrorResponse` record L103-107, 7개 엔드포인트 `@RequestHeader` 패턴 확인.
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — `BCryptPasswordEncoder(12)` 빈 등록 위치(L81-83), `/api/v1/admin/**` permitAll 설정(L61-62) 확인.
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java` — `JdbcRegisteredClientRepository` 빈 등록 위치. Basic Auth 검증 시 `findByClientId()` 사용.
- `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java` — 기존 테스트 구조(Nested class, @DisplayName 한글, Given-When-Then) 컨벤션 확인.
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java` — `DynamicPropertySource` 패턴, `mockMvc` 헬퍼 메서드 구조 확인.
- `.claude/skills/register-service/SKILL.md` — Step 1 curl L39-48, 전제 조건 L29, 완료 체크리스트 L167 갱신 대상 확인.
- RFC 7617 — HTTP Basic Authentication Scheme (Base64 인코딩 표준)
