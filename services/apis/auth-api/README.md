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
  Body: {"loginId": "user01", "password": "Econo1234!", "clientId": "econovation-web"}
        ← clientId는 선택 필드. WEB·APP 모두 등록된 redirect_uri 조회에 사용됨
  Header: Client-Type: WEB  (또는 APP; 생략 시 WEB)

  WEB 응답 — 302 리다이렉트 (clientId 기반):
    HTTP 302 Found
    Location: <clientId에 등록된 redirect_uri>  (미전달·미등록 시 auth.redirect.default-url)
    Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=3600
    Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=2592000
    Body: 없음
    ※ 토큰은 Location URL에 포함되지 않는다 (쿠키 전용).

  APP 응답 — 200 OK:
    Body: {"accessToken": "...", "accessExpiredTime": ..., "refreshToken": "...", "redirectUrl": "..."}

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
| `clientId` | Body (JSON) | - | OAuth 클라이언트 ID. WEB·APP 모두 등록된 redirect_uri 조회에 사용. WEB은 302 Location, APP은 응답 body의 `redirectUrl`로 전달. 없거나 미등록 시 `auth.redirect.default-url`로 fallback. |
| `Client-Type` | Header | - | `WEB`(기본) 또는 `APP` |

**WEB 응답 — 302 Found:**
```
HTTP 302 Found
Location: https://app.econovation.kr/callback   ← clientId 등록 redirect_uri (또는 default-url)
Set-Cookie: at=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=3600
Set-Cookie: rt=<JWT>; HttpOnly; SameSite=None; Secure; Domain=.econovation.kr; Max-Age=2592000
(Body 없음)
```
- clientId 미전달 / 미등록 / redirect_uri 없음 → `auth.redirect.default-url`로 302 (4xx 거부 없음).
- redirect_uri 복수 등록 시: 알파벳 오름차순 정렬 후 첫 번째 사용.
- 토큰은 Location URL에 포함되지 않는다 (쿠키 전용, open redirect 구조적 불가능).
- 자세한 설계 근거: [ADR-0012](../../docs/adr/0012-backend-decided-login-redirect.md)

**APP 응답 — 200 OK:**
```json
{
  "accessToken": "<JWT>",
  "accessExpiredTime": 1780242839681,
  "refreshToken": "<JWT>",
  "redirectUrl": "https://app.econovation.kr/callback"
}
```
- `redirectUrl`: clientId에 등록된 redirect_uri(또는 `auth.redirect.default-url`). `LoginRedirectResolver`가 결정. `@JsonInclude(NON_NULL)` 적용 — clientId 미전달 시 필드 자체가 null일 수 있다.

---

### `POST /api/v1/auth/reissue`

| 파라미터 | 위치 | WEB | APP |
|---------|------|-----|-----|
| RT | Cookie `rt` | 자동 | - |
| `refreshToken` | Body (JSON) | - | 필수 |
| `Client-Type` | Header | `WEB` | `APP` |

**WEB 응답:** 새 `at` + `rt` 쿠키 교체, body: `{"accessExpiredTime": ...}`

**APP 응답:** `{"accessToken": ..., "accessExpiredTime": ..., "refreshToken": ...}`

**재발급 에러 응답 (WEB/APP 공통):** `{"errorCode": "...", "message": "...", "timestamp": "2026-06-14T10:00:00"}`
<!-- ReissueController가 ErrorResponse(errorCode, message) 2-인수 생성자로 직접 반환하는 3필드 포맷.
     GlobalExceptionHandler가 처리하는 예외는 fieldErrors 필드가 추가된 4필드 포맷(ApiError)을 사용한다. -->

| HTTP | errorCode | 발생 조건 |
|------|-----------|-----------|
| 401 | `REFRESH_TOKEN_MISSING` | RT 누락 (쿠키 없음 또는 바디 null/blank) |
| 401 | `REFRESH_TOKEN_INVALID` | RT 서명 검증 실패·만료·타입 불일치 |

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

클라이언트 등록 경로는 두 가지다. 두 경로 모두 econo-passport 라이브러리(`@PassportAuth`, `PassportArgumentResolver`)가 `X-User-Passport` 헤더를 파싱·검증한다.

| 경로 | 엔드포인트 | 인증 |
|------|-----------|------|
| **셀프 등록** | `POST /api/v1/clients` | X-User-Passport (Gateway 주입, memberId 필수) — ADMIN 역할 불필요 |
| **어드민 등록** | `POST /api/v1/admin/clients` | X-User-Passport ADMIN 또는 SUPER_ADMIN role 필수 |

**에러 코드 요약:**

| HTTP | 코드 | 발생 조건 |
|------|------|-----------|
| 400 | `AUTH_BAD_REQUEST` | `X-User-Passport` Base64/JSON 파싱 불가 |
| 401 | `AUTH_UNAUTHORIZED` | `X-User-Passport` 헤더 누락 또는 invalid passport |
| 403 | `FORBIDDEN` | 어드민 경로에서 ADMIN/SUPER_ADMIN 역할 부족 |

> 전체 에러 코드 및 비즈니스 규칙: [docs/CLIENT_REGISTRATION.md](../../docs/CLIENT_REGISTRATION.md)

```bash
# 셀프 등록 (인증된 회원 — X-User-Passport 필수, Gateway 경유 시 자동 주입)
curl -X POST http://localhost:8081/api/v1/clients \
  -H "Content-Type: application/json" \
  -H "X-User-Passport: <passport>" \
  -d '{
    "clientName": "EEOS 웹",
    "redirectUris": ["https://app.econovation.kr/callback"]
  }'
# → 201 {"clientId": "...", "clientSecret": "... (1회만 노출)"}
# 회원당 최대 5개. 초과 시 422 CLIENT_LIMIT_EXCEEDED.

# 어드민 등록 (ADMIN 또는 SUPER_ADMIN role 필요)
curl -X POST http://localhost:8081/api/v1/admin/clients \
  -H "Content-Type: application/json" \
  -H "X-User-Passport: <admin-passport>" \
  -d '{
    "clientName": "내부 서비스",
    "redirectUris": ["https://internal.econovation.kr/callback"]
  }'
```

---

## 동적 게이트웨이 라우트 관리

`ADMIN` 또는 `SUPER_ADMIN` 역할의 Passport가 필요하다. Gateway가 Bearer JWT를 검증하고 `X-User-Passport` 헤더를 자동 주입한다.

> 전체 가이드: [docs/DYNAMIC_ROUTING.md](../../docs/DYNAMIC_ROUTING.md)

### Admin API (`AdminRouteController`)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/v1/admin/routes` | 동적 라우트 등록 (201 Created) |
| `GET` | `/api/v1/admin/routes` | 전체 라우트 목록 조회 (200 OK) |
| `GET` | `/api/v1/admin/routes/{routeId}` | 단건 라우트 조회 (200 OK / 404) |
| `PUT` | `/api/v1/admin/routes/{routeId}` | 라우트 수정 (200 OK) |
| `DELETE` | `/api/v1/admin/routes/{routeId}` | 라우트 삭제 (204 No Content) |

요청 바디 (`POST`/`PUT`):

```json
{
  "pathPrefix": "/api/v2/my-service",
  "upstreamUrl": "http://my-service:8080",
  "enabled": true
}
```

에러 코드 요약:

| HTTP | 코드 | 발생 조건 |
|------|------|-----------|
| 400 | `ROUTE_UPSTREAM_INVALID` | upstreamUrl SSRF 검증 실패 |
| 403 | `ROUTE_PROTECTED` | 보호 경로 패턴 충돌 |
| 404 | `ROUTE_NOT_FOUND` | routeId 미존재 |
| 409 | `ROUTE_PATH_CONFLICT` | pathPrefix 중복 |

### 내부 라우트 조회 (`InternalRouteController`)

`GET /api/v1/internal/routes` — api-gateway 기동 시 호출하는 내부 전용 엔드포인트. `X-Internal-Secret` 헤더로 보호한다 (`GATEWAY_INTERNAL_SECRET` 환경변수와 동일 값 필요).

이 경로는 보호 경로(`ProtectedPathPolicyImpl`)에 `/api/v1/internal/**`로 등록되어 동적 라우트 등록이 차단된다. api-gateway의 `AuthApiRouteClient`(`DynamicRouteDefinitionRepository` 내부)가 `AUTH_API_URI`로 직접 WebClient 호출하여 Gateway 라우팅 테이블을 우회한다. Passport 불필요, `X-Internal-Secret` 헤더 검증으로 보호.

---

## 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `DB_URL` | PostgreSQL JDBC URL | 필수 |
| `DB_USERNAME` / `DB_PASSWORD` | DB 인증 | 필수 |
| `RSA_PRIVATE_KEY` | PKCS#8 PEM 개인키 | 필수 |
| `RSA_PUBLIC_KEY` | X.509 PEM 공개키 | 필수 |
| `AUTH_ISSUER_URI` | JWT `iss` 클레임 (Gateway 공개 URL) | `http://localhost:8080` |
| `REDIRECT_DEFAULT_URL` | WEB 로그인 302 fallback 목적지 (`auth.redirect.default-url`). clientId 미전달·미등록·redirect_uri 없음·인프라 오류 시 사용 (fail-safe) | `http://localhost:3000` |
| `FRONTEND_LOGIN_URL` | SAS `/oauth2/authorize` 미인증 진입 시 리다이렉트할 SPA 로그인 URL (`auth.frontend-login-url`) | `http://localhost:3000/login` |
| `GATEWAY_URI` | api-gateway 내부 주소 (라우트 refresh 콜백 호출용) | `http://localhost:8080` |
| `GATEWAY_INTERNAL_SECRET` | api-gateway refresh 엔드포인트 인증 시크릿 (`GATEWAY_INTERNAL_SECRET`과 동일 값) | `dev-secret` |
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
