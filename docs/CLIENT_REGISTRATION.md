# OAuth 클라이언트 등록 가이드

`POST /api/v1/admin/clients` — OAuth 클라이언트를 등록하고 API Key를 발급한다.
모든 Admin API는 `X-Internal-Api-Key` 헤더 인증 필수 (서버 내부망 전용).

---

## 클라이언트 등록

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
  "clientId": "a1b2c3d4-...",
  "clientSecret": null,
  "routeId": null
}
```

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
| 400 | `REDIRECT_URI_REQUIRED` | authorization_code인데 redirectUris 없음 |
| 400 | `UNSUPPORTED_GRANT_TYPE` | 지원하지 않는 grantType |
| 400 | `VALIDATION_FAILED` | clientName이 빈 문자열 |
| 401 | `UNAUTHORIZED` | X-Internal-Api-Key 없거나 틀림 |
| 409 | `DUPLICATE_RESOURCE` | clientName 또는 pathPrefix 중복 |

---

## CORS 자동 허용

`redirectUri`에 등록된 오리진은 `DynamicCorsConfigurationSource`에 의해 자동으로 CORS 허용 목록에 추가됩니다.
별도 CORS 환경변수 설정 불필요.

예: `https://app.econovation.kr/callback` 등록 → `https://app.econovation.kr` 자동 허용
