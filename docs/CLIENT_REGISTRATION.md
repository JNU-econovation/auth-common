# OAuth 클라이언트 등록 가이드

`POST /api/v1/clients` — OAuth 클라이언트를 등록한다.
등록 및 라우트 조회 endpoint는 인증 불필요 (public). redirectUri 관리 4개 endpoint는
`Authorization: Basic base64(clientId:clientSecret)` 헤더 필수 (서버 내부망 전용).

> **중요:** `clientSecret`은 등록 응답에서 단 1회만 노출된다. 분실 시 재등록 필요.

---

## 클라이언트 등록

### 요청 필드

| 필드 | 타입 | 필수 여부 | 설명 |
|------|------|-----------|------|
| `clientName` | String | 필수 | 빈 문자열 불가 |
| `grantType` | String | **선택** | 생략 시 `client_credentials` 디폴트 적용. `authorization_code` / `client_credentials` 외 비-null 값은 400 |
| `redirectUris` | Set\<String\> | 조건부 | `grantType=authorization_code` 명시 시 필수. 생략 시 빈 Set으로 정규화 |
| `upstreamUrl` | String | 선택 | Gateway 라우팅 대상 URL |
| `pathPrefix` | String | 선택 | Gateway 경로 접두사 |

### grantType 생략 (디폴트)

`grantType`을 생략하면 서버가 `client_credentials`로 처리한다. 가장 단순한 등록 방법이다.

```bash
curl -X POST http://auth-api:8081/api/v1/clients \
  -H "Content-Type: application/json" \
  -d '{
    "clientName": "app-b"
  }'
```

**응답:**
```json
{
  "clientId": "550e8400-...",
  "clientSecret": "xK9mL2pQ... (1회만 표시)"
}
```

### authorization_code (SPA / 웹앱)

```bash
curl -X POST http://auth-api:8081/api/v1/clients \
  -H "Content-Type: application/json" \
  -d '{
    "grantType": "authorization_code",
    "clientName": "EEOS 웹앱",
    "redirectUris": ["https://app.econovation.kr/callback"]
  }'
```

**응답:**
```json
{
  "clientId": "a1b2c3d4-..."
}
```

> `clientSecret`은 authorization_code 타입 클라이언트에 대해 생성되지 않는다.
> 응답 바디에 필드가 포함되지 않는다 (`@JsonInclude(NON_NULL)` 적용).

> **주의:** `authorization_code` 클라이언트는 clientSecret이 없으므로 Basic Auth가 필요한 redirectUri 관리 endpoint에 접근할 수 없다. 접근 시도 시 `401 INVALID_CLIENT_CREDENTIALS`를 반환한다.

### client_credentials (서버 간 호출 + Gateway 라우팅)

```bash
curl -X POST http://auth-api:8081/api/v1/clients \
  -H "Content-Type: application/json" \
  -d '{
    "grantType": "client_credentials",
    "clientName": "EEOS 서버",
    "upstreamUrl": "http://eeos-server:8080",
    "pathPrefix": "/api/eeos"
  }'
```

**응답:**
```json
{
  "clientId": "e2d130b5-...",
  "clientSecret": "hMgV6hWvEq... (1회만 표시)",
  "routeId": "a316bc69-..."
}
```

> `clientSecret`은 이 응답에서만 노출. 재조회 불가.

> `routeId`는 `upstreamUrl`을 함께 지정한 경우에만 응답에 포함된다 (`@JsonInclude(NON_NULL)` 적용).

---

## Basic Auth 헤더 구성 방법

redirectUri 관리 4개 endpoint를 호출하려면 등록 응답의 `clientSecret`을 사용해 Basic Auth 헤더를 구성해야 한다.

```bash
# clientId:clientSecret 을 Base64 인코딩
CLIENT_ID="e2d130b5-..."
CLIENT_SECRET="hMgV6hWvEq..."
BASIC_TOKEN=$(echo -n "${CLIENT_ID}:${CLIENT_SECRET}" | base64)

# Authorization 헤더 구성
-H "Authorization: Basic ${BASIC_TOKEN}"
```

---

## redirectUri 관리

### 조회

```bash
curl http://auth-api:8081/api/v1/clients/{clientId} \
  -H "Authorization: Basic base64(clientId:clientSecret)"
```

### 추가 (기존 유지)

```bash
curl -X POST http://auth-api:8081/api/v1/clients/{clientId}/redirect-uris \
  -H "Authorization: Basic base64(clientId:clientSecret)" \
  -H "Content-Type: application/json" \
  -d '{"uri": "https://new.econovation.kr/callback"}'
```

### 삭제

```bash
curl -X DELETE http://auth-api:8081/api/v1/clients/{clientId}/redirect-uris \
  -H "Authorization: Basic base64(clientId:clientSecret)" \
  -H "Content-Type: application/json" \
  -d '{"uri": "https://old.econovation.kr/callback"}'
```

### 전체 교체

```bash
curl -X PUT http://auth-api:8081/api/v1/clients/{clientId}/redirect-uris \
  -H "Authorization: Basic base64(clientId:clientSecret)" \
  -H "Content-Type: application/json" \
  -d '{"uris": ["https://app.econovation.kr/callback", "http://localhost:3000/callback"]}'
```

---

## 에러 코드

| HTTP | 코드 | 설명 |
|------|------|------|
| 400 | `REDIRECT_URI_REQUIRED` | `grantType=authorization_code`를 명시했는데 `redirectUris`가 비어있음. `grantType` 생략(디폴트) 시에는 발생하지 않음 |
| 400 | `UNSUPPORTED_GRANT_TYPE` | `grantType`이 null이 아닌데 `authorization_code` / `client_credentials` 이외의 값인 경우 |
| 400 | `VALIDATION_FAILED` | `clientName`이 빈 문자열 |
| 401 | `INVALID_CLIENT_CREDENTIALS` | Authorization 헤더 누락, Base64 디코딩 실패, clientId 미존재, BCrypt 불일치, `authorization_code` 클라이언트(clientSecret 없음). 응답 헤더: `WWW-Authenticate: Basic realm="admin"` |
| 403 | `FORBIDDEN_CLIENT_MISMATCH` | path `{clientId}` ≠ Basic Auth에서 추출한 clientId |
| 409 | `DUPLICATE_RESOURCE` | `clientName` 또는 `pathPrefix` 중복 |

---

## CORS 자동 허용

`redirectUri`에 등록된 오리진은 `DynamicCorsConfigurationSource`에 의해 자동으로 CORS 허용 목록에 추가됩니다.
별도 CORS 환경변수 설정 불필요.

예: `https://app.econovation.kr/callback` 등록 → `https://app.econovation.kr` 자동 허용
