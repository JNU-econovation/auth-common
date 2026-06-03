# OAuth 클라이언트 등록 가이드

`POST /api/v1/admin/clients` — OAuth 클라이언트를 등록한다.
모든 Admin API는 `X-Internal-Api-Key` 헤더 인증 필수 (서버 내부망 전용).

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
curl -X POST http://auth-api:8081/api/v1/admin/clients \
  -H "X-Internal-Api-Key: <KEY>" \
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
curl -X POST http://auth-api:8081/api/v1/admin/clients \
  -H "X-Internal-Api-Key: <KEY>" \
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

> `clientSecret`은 authorization_code 타입에서 발급되지 않는다 (`@JsonInclude(NON_NULL)` — 필드 자체가 응답에 포함되지 않음).

### client_credentials (서버 간 호출 + Gateway 라우팅)

```bash
curl -X POST http://auth-api:8081/api/v1/admin/clients \
  -H "X-Internal-Api-Key: <KEY>" \
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

## redirectUri 관리

### 조회

```bash
curl http://auth-api:8081/api/v1/admin/clients/{clientId} \
  -H "X-Internal-Api-Key: <KEY>"
```

### 추가 (기존 유지)

```bash
curl -X POST http://auth-api:8081/api/v1/admin/clients/{clientId}/redirect-uris \
  -H "X-Internal-Api-Key: <KEY>" \
  -H "Content-Type: application/json" \
  -d '{"uri": "https://new.econovation.kr/callback"}'
```

### 삭제

```bash
curl -X DELETE http://auth-api:8081/api/v1/admin/clients/{clientId}/redirect-uris \
  -H "X-Internal-Api-Key: <KEY>" \
  -H "Content-Type: application/json" \
  -d '{"uri": "https://old.econovation.kr/callback"}'
```

### 전체 교체

```bash
curl -X PUT http://auth-api:8081/api/v1/admin/clients/{clientId}/redirect-uris \
  -H "X-Internal-Api-Key: <KEY>" \
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
| 401 | `UNAUTHORIZED` | `X-Internal-Api-Key` 없거나 틀림 |
| 409 | `DUPLICATE_RESOURCE` | `clientName` 또는 `pathPrefix` 중복 |

---

## CORS 자동 허용

`redirectUri`에 등록된 오리진은 `DynamicCorsConfigurationSource`에 의해 자동으로 CORS 허용 목록에 추가됩니다.
별도 CORS 환경변수 설정 불필요.

예: `https://app.econovation.kr/callback` 등록 → `https://app.econovation.kr` 자동 허용
