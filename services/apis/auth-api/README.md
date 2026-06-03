# auth-api

JWT 기반 인증 서버. 로그인 / 재발급 / 로그아웃 / OAuth 클라이언트 관리 API 제공.

---

## 전체 인증 플로우

```
┌──────────────────────────────────────────────────────────┐
│                    전체 인증 흐름                          │
└──────────────────────────────────────────────────────────┘

[1단계] 로그인
  POST /api/v1/auth/login
  Body: {"loginId": "user01", "password": "Econo1234!"}
  Header: Client-Type: WEB  (또는 APP)

  WEB 응답:
    Set-Cookie: at=<JWT>; HttpOnly; Domain=.econovation.kr; Max-Age=3600
    Set-Cookie: rt=<JWT>; HttpOnly; Domain=.econovation.kr; Max-Age=2592000
    Body: {"accessExpiredTime": 1780242839681}

  APP 응답:
    Body: {"accessToken": "...", "accessExpiredTime": ..., "refreshToken": "..."}

[2단계] API 호출 (Gateway 경유)
  WEB:
    브라우저가 at 쿠키 자동 전송
    → api-gateway: at 쿠키에서 JWT 추출 → 검증 → X-User-Passport 주입 → 서비스

  APP:
    Authorization: Bearer <accessToken>
    → api-gateway: Bearer 헤더에서 JWT 추출 → 검증 → X-User-Passport 주입 → 서비스

[3단계] AT 만료 시 자동 갱신 (재로그인 없음)
  WEB:
    브라우저가 rt 쿠키 자동 전송
    POST /api/v1/auth/reissue
    → 새 at + rt 쿠키 교체

  APP:
    POST /api/v1/auth/reissue
    Body: {"refreshToken": "<RT>"}
    → Body: {"accessToken": "...", "refreshToken": "..."}

[4단계] 로그아웃
  WEB: POST /api/v1/auth/logout → at + rt 쿠키 삭제
  APP: POST /api/v1/auth/logout → noop (클라이언트가 직접 토큰 폐기)

[SSO: 서비스 A → 서비스 B 이동]
  WEB: at 쿠키 Domain=.econovation.kr → 모든 서브도메인에서 자동 전송 → 재로그인 없음
  APP: 클라이언트가 AT를 저장해두고 재사용
```

---

## API 레퍼런스

### `POST /api/v1/auth/login`

| 파라미터 | 위치 | 필수 | 설명 |
|---------|------|------|------|
| `loginId` | Body (JSON) | ✅ | 로그인 아이디 |
| `password` | Body (JSON) | ✅ | 비밀번호 |
| `Client-Type` | Header | - | `WEB`(기본) 또는 `APP` |

**WEB 응답:**
```
HTTP 200
Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=3600
Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=2592000

{"accessExpiredTime": 1780242839681}
```

**APP 응답:**
```json
{
  "accessToken": "<JWT>",
  "accessExpiredTime": 1780242839681,
  "refreshToken": "<JWT>"
}
```

---

### `POST /api/v1/auth/reissue`

| 파라미터 | 위치 | WEB | APP |
|---------|------|-----|-----|
| RT | Cookie `rt` | 자동 | - |
| `refreshToken` | Body (JSON) | - | 필수 |
| `Client-Type` | Header | `WEB` | `APP` |

**WEB 응답:** 새 `at` + `rt` 쿠키 교체, body: `{"accessExpiredTime": ...}`

**APP 응답:** `{"accessToken": ..., "accessExpiredTime": ..., "refreshToken": ...}`

---

### `POST /api/v1/auth/logout`

**WEB:** `at` + `rt` 쿠키 Max-Age=0 삭제
**APP:** 200 OK (클라이언트가 직접 토큰 폐기)

---

### `POST /api/v1/auth/signup`

```bash
curl -X POST http://localhost:8081/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "name": "홍길동",
    "loginId": "honggildong",
    "password": "Econo1234!",
    "generation": 30,
    "status": "AM"
  }'
```

---

### `GET /oauth2/jwks`

api-gateway가 JWT 서명 검증에 사용하는 공개키. 직접 호출 불필요.

---

## OAuth 클라이언트 관리

등록(`POST /clients`) 및 라우트 조회(`GET /routes`)는 인증 불필요 (public).
redirectUri 관리 4개 endpoint는 `Authorization: Basic base64(clientId:clientSecret)` 헤더 필수.

> 자세한 내용: [docs/CLIENT_REGISTRATION.md](../../docs/CLIENT_REGISTRATION.md)

```bash
# 클라이언트 등록 (public — 인증 불필요)
curl -X POST http://localhost:8081/api/v1/clients \
  -H "Content-Type: application/json" \
  -d '{"grantType": "authorization_code", "clientName": "EEOS 웹", "redirectUris": ["https://app.econovation.kr/callback"]}'

# redirectUri 추가 (Basic Auth 필요 — clientId:clientSecret 을 Base64 인코딩)
BASIC_TOKEN=$(echo -n "{clientId}:{clientSecret}" | base64)
curl -X POST http://localhost:8081/api/v1/clients/{clientId}/redirect-uris \
  -H "Authorization: Basic ${BASIC_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"uri": "https://app2.econovation.kr/callback"}'
```

---

## 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `DB_URL` | PostgreSQL JDBC URL | 필수 |
| `DB_USERNAME` / `DB_PASSWORD` | DB 인증 | 필수 |
| `RSA_PRIVATE_KEY` | PKCS#8 PEM 개인키 | 필수 |
| `RSA_PUBLIC_KEY` | X.509 PEM 공개키 | 필수 |
| `AUTH_ISSUER_URI` | JWT `iss` 클레임 (Gateway 공개 URL) | `http://localhost:8080` |
| `COOKIE_DOMAIN` | 쿠키 도메인 (`.econovation.kr`) | 빈값 |
| `COOKIE_SECURE` | HTTPS 전용 쿠키 | `false` |
| `AT_EXPIRY_SECONDS` | AT 유효시간 (초) | `3600` (1시간) |
| `RT_EXPIRY_SECONDS` | RT 유효시간 (초) | `2592000` (30일) |

---

## 로컬 실행

```bash
# 1. PostgreSQL 실행
docker compose -f ../../docker-compose-local.yml up -d

# 2. RSA 키 생성 (최초 1회)
openssl genrsa -out private.pem 2048
openssl pkcs8 -topk8 -nocrypt -in private.pem -out private-pkcs8.pem
openssl rsa -in private.pem -pubout -out public.pem

# 3. 서버 실행
DB_URL=jdbc:postgresql://localhost:5433/authdb \
DB_USERNAME=auth DB_PASSWORD=auth1234 \
RSA_PRIVATE_KEY="$(cat private-pkcs8.pem)" \
RSA_PUBLIC_KEY="$(cat public.pem)" \
AUTH_ISSUER_URI=http://localhost:8081 \
COOKIE_SECURE=false \
./gradlew :services:apis:auth-api:bootRun --args='--server.port=8081'
```
