# Gateway 동적 라우팅 가이드

api-gateway는 auth-api에 등록된 `service_route` 테이블을 기반으로 라우팅합니다.

---

## 현재 라우팅 구성 (정적 YAML)

```
/api/v1/auth/**  →  AUTH_API_URI  (auth-api)
/oauth2/jwks     →  AUTH_API_URI
/api/**          →  EEOS_API_URI  (EEOS-BE)
```

새 서비스 추가 시 `GatewayRoutingConfig.java`에 라우트를 추가하고 재배포합니다.

---

## 동적 라우팅 등록

클라이언트 등록 시 `upstreamUrl` + `pathPrefix`를 지정하면 `service_route` 테이블에 라우트가 자동 등록됩니다.
`grantType` 생략(디폴트) 또는 `client_credentials` 명시 모두 가능합니다.

```bash
# 새 서비스 등록 + Gateway 라우팅 자동 추가 (grantType 생략)
curl -X POST http://auth-api:8081/api/v1/admin/clients \
  -H "X-Internal-Api-Key: <KEY>" \
  -d '{
    "clientName": "새로운 서비스",
    "upstreamUrl": "http://new-service:8080",
    "pathPrefix": "/api/new"
  }'
```

등록 후: `GET http://gateway:8082/api/new/**` → `http://new-service:8080`

---

## 라우트 목록 조회

```bash
curl http://auth-api:8081/api/v1/admin/routes \
  -H "X-Internal-Api-Key: <KEY>"
```

**응답:**
```json
{
  "routes": [
    {
      "routeId": "a316bc69-...",
      "pathPrefix": "/api/eeos",
      "upstreamUrl": "http://eeos-server:8080",
      "enabled": true
    }
  ]
}
```

---

## 주의사항

- `pathPrefix`는 UNIQUE — 같은 경로에 두 서비스 등록 불가
- `/oauth2/`, `/.well-known/`, `/api/v1/auth/` 경로는 예약됨
- 라우팅 변경 후 현재는 서버 재시작 필요 (동적 반영 미구현)
