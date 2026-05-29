# OAuth 클라이언트 등록 API 가이드

auth-api가 제공하는 OAuth 클라이언트 등록 및 라우트 조회 API 문서.

> **참고**: auth-api의 `build.gradle.kts`에 springdoc 의존성이 없으므로 Swagger UI를 제공하지 않는다. 이 문서가 API 명세를 대신한다.

## 엔드포인트 목록

| 메서드 | 경로 | 인증 | 설명 |
|--------|------|------|------|
| POST | `/api/v1/admin/clients` | 없음 (Admin 내부망 전용) | OAuth 클라이언트 등록 |
| GET | `/api/v1/admin/routes` | `X-Internal-Api-Key` 헤더 | Gateway용 라우트 목록 조회 |

---

## POST `/api/v1/admin/clients`

### 설명

OAuth 2.0 클라이언트를 등록한다. 그랜트 타입에 따라 동작이 다르다.

- `authorization_code`: PKCE 필수, 클라이언트 인증 방식 `NONE`, 스코프 `openid`
- `client_credentials`: BCrypt 해시된 시크릿 발급, SHA-256 API 키 해시 저장, 스코프 `read`

### 인증

별도 인증 없음. 내부 관리 API이므로 외부망에 노출하지 않는다.

### 요청

**Content-Type**: `application/json`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `grantType` | string | O | `authorization_code` 또는 `client_credentials` |
| `clientName` | string | O | 클라이언트 이름 (UNIQUE) |
| `redirectUris` | string[] | `authorization_code`일 때 필수 | 허용 리다이렉트 URI 목록 |
| `upstreamUrl` | string | X | Gateway가 라우팅할 업스트림 서비스 URL (`http://` 또는 `https://` 스키마) |
| `pathPrefix` | string | X | Gateway 경로 접두사 (예: `/api/eeos`). `upstreamUrl`과 함께 사용 |

#### 요청 예시 — `authorization_code`

```json
{
  "grantType": "authorization_code",
  "clientName": "eeos-frontend",
  "redirectUris": [
    "https://eeos.econovation.kr/callback",
    "http://localhost:3000/callback"
  ],
  "upstreamUrl": null,
  "pathPrefix": null
}
```

#### 요청 예시 — `client_credentials`

```json
{
  "grantType": "client_credentials",
  "clientName": "eeos-backend",
  "redirectUris": null,
  "upstreamUrl": "http://eeos-service:8080",
  "pathPrefix": "/api/eeos"
}
```

### 응답

**HTTP 201 Created**

`clientSecret`는 `client_credentials` 타입일 때만 반환된다. 이후 조회 불가 — 최초 응답에서 반드시 저장할 것.

`routeId`는 `upstreamUrl`이 있을 때만 반환된다.

```json
{
  "clientId": "a3f7c2d1-85b4-4e9a-bf32-1c0e7d9fa821",
  "clientSecret": "xKz3Qp9mRvLs7wNt2YhJ4dUiOeAn0BfCgXvPqWmE5c",
  "routeId": "e9b1d4a7-3f2c-4b8e-9d06-5a7c1f3e2b90"
}
```

| 필드 | 타입 | 조건 |
|------|------|------|
| `clientId` | string (UUID) | 항상 반환 |
| `clientSecret` | string | `client_credentials` 전용, 1회만 반환 |
| `routeId` | string (UUID) | `upstreamUrl` 있을 때만 반환 |

### 오류

| HTTP | errorCode | 발생 조건 |
|------|-----------|-----------|
| 400 | `VALIDATION_FAILED` | Bean Validation 실패 (`clientName` 누락 등) |
| 400 | `REDIRECT_URI_REQUIRED` | `authorization_code` 타입인데 `redirectUris`가 비어있음 |
| 400 | `UNSUPPORTED_GRANT_TYPE` | `grantType`이 `authorization_code`, `client_credentials` 이외의 값 |
| 409 | `DUPLICATE_CLIENT_NAME` | 동일 `clientName`이 이미 등록되어 있음 |

#### 오류 응답 형식

```json
{
  "errorCode": "DUPLICATE_CLIENT_NAME",
  "message": "이미 등록된 클라이언트 이름입니다: eeos-frontend",
  "timestamp": "2026-05-29T14:00:00"
}
```

`VALIDATION_FAILED` 시에는 `fieldErrors` 배열이 추가된다.

```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-05-29T14:00:00",
  "fieldErrors": [
    { "field": "clientName", "message": "공백일 수 없습니다" }
  ]
}
```

---

## GET `/api/v1/admin/routes`

### 설명

등록된 모든 서비스 라우트 목록을 반환한다. API Gateway(`RouteDefinitionCache`)가 30초마다 이 엔드포인트를 폴링하여 라우트 캐시를 갱신한다.

### 인증

`X-Internal-Api-Key` 헤더. 값은 환경변수 `AUTH_INTERNAL_API_KEY`와 일치해야 한다.

비교는 `MessageDigest.isEqual()`로 상수 시간 비교하므로 타이밍 공격에 안전하다.

### 요청

```
GET /api/v1/admin/routes
X-Internal-Api-Key: <AUTH_INTERNAL_API_KEY 값>
```

### 응답

**HTTP 200 OK**

```json
{
  "routes": [
    {
      "routeId": "e9b1d4a7-3f2c-4b8e-9d06-5a7c1f3e2b90",
      "clientId": "a3f7c2d1-85b4-4e9a-bf32-1c0e7d9fa821",
      "upstreamUrl": "http://eeos-service:8080",
      "pathPrefix": "/api/eeos"
    }
  ]
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `routeId` | string (UUID) | 라우트 식별자 |
| `clientId` | string (UUID) | 연결된 OAuth 클라이언트 ID |
| `upstreamUrl` | string | 업스트림 서비스 URL |
| `pathPrefix` | string | 경로 접두사 (null 가능) |

### 오류

| HTTP | errorCode | 발생 조건 |
|------|-----------|-----------|
| 401 | `UNAUTHORIZED` | `X-Internal-Api-Key` 헤더 누락 또는 불일치 |

---

## DB 스키마

클라이언트 등록 결과는 두 테이블에 저장된다.

### `service_client`

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `registered_client_id` | VARCHAR(100) UNIQUE | OAuth 클라이언트 ID (SAS 등록 ID와 동일) |
| `client_name` | VARCHAR(100) UNIQUE | 클라이언트 이름 |
| `grant_type` | VARCHAR(30) | `AUTHORIZATION_CODE` 또는 `CLIENT_CREDENTIALS` |
| `api_key_hash` | VARCHAR(64) | SHA-256 해시된 API 키 (client_credentials 전용, nullable) |

### `service_route`

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `route_id` | VARCHAR(100) UNIQUE | 라우트 UUID |
| `registered_client_id` | VARCHAR(100) | 연결된 클라이언트 (FK → service_client) |
| `path_prefix` | VARCHAR(200) UNIQUE | 경로 접두사 (nullable) |
| `upstream_url` | VARCHAR(500) | 업스트림 서비스 URL |
| `enabled` | BOOLEAN | 라우트 활성화 여부 (기본값 true) |

`service_route.registered_client_id`는 `service_client`를 참조하며, 클라이언트 삭제 시 라우트도 CASCADE 삭제된다.

---

## 사용 흐름

### 새 서비스 등록 예시 (client_credentials + Gateway 라우팅)

```bash
# 1. 클라이언트 등록
curl -X POST http://auth-api:8081/api/v1/admin/clients \
  -H "Content-Type: application/json" \
  -d '{
    "grantType": "client_credentials",
    "clientName": "my-service",
    "upstreamUrl": "http://my-service:8080",
    "pathPrefix": "/api/my"
  }'

# 응답에서 clientId, clientSecret, routeId 저장
# clientSecret은 이 시점 이후 다시 조회 불가

# 2. 최대 30초 후 Gateway가 라우트를 자동 반영 (RouteDefinitionCache 폴링 주기)
```

### 새 프런트엔드 앱 등록 예시 (authorization_code)

```bash
curl -X POST http://auth-api:8081/api/v1/admin/clients \
  -H "Content-Type: application/json" \
  -d '{
    "grantType": "authorization_code",
    "clientName": "my-frontend",
    "redirectUris": ["https://my-app.example.com/callback"]
  }'

# 반환된 clientId를 프런트엔드 OAuth 설정에 사용
# authorization_code 타입은 clientSecret이 없음 (PKCE 사용)
```
