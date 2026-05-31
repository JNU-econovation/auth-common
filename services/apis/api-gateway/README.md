# api-gateway

Spring Cloud Gateway 기반 인증 게이트웨이. JWT/쿠키 검증 후 Passport를 생성하여 내부 서비스로 전달.

---

## 전체 요청 흐름

```
클라이언트 (WEB: at 쿠키 자동 전송 / APP: Authorization: Bearer)
        ↓
api-gateway
  BearerToPassportFilter (order=-1):
    ① Bearer 헤더에서 AT 추출  ← APP
    ② 없으면 at 쿠키에서 추출  ← WEB
    ③ JWT RS256 검증 (JWKS: auth-api /oauth2/jwks)
    ④ Passport JSON → Base64 인코딩 → X-User-Passport 헤더 주입
    ⑤ removeRequestHeader("Authorization")  ← EEOS-BE 충돌 방지
        ↓
  라우팅:
    /api/v1/auth/**  →  auth-api :8081
    /oauth2/jwks     →  auth-api :8081
    /api/**          →  EEOS-BE  :8080
        ↓
내부 서비스
  X-User-Passport: eyJ... (Base64 JSON)
  → PassportAuthenticationFilter → @Member Long memberId 주입
```

---

## SSO 동작 방식

```
[로그인]
  POST /api/v1/auth/login  → at 쿠키 (Domain=.econovation.kr, Max-Age=3600)
                           → rt 쿠키 (Domain=.econovation.kr, Max-Age=2592000)

[app-a.econovation.kr → Service A]
  브라우저: at 쿠키 자동 전송 → Gateway 검증 → Service A ✅

[app-b.econovation.kr → Service B]  ← 재로그인 없음
  브라우저: at 쿠키 자동 전송 (Domain=.econovation.kr이라 B도 해당) → Service B ✅

[AT 만료]
  POST /api/v1/auth/reissue ← rt 쿠키 자동 전송 → 새 at 쿠키 발급 → 재로그인 없음
```

**운영 필수**: `COOKIE_DOMAIN=.econovation.kr` + `COOKIE_SECURE=true`

---

## X-User-Passport 헤더

Gateway가 JWT 검증 성공 시 자동 주입. 내부 서비스는 이 헤더를 신뢰한다.

```
X-User-Passport: Base64(UTF-8 JSON)
```

디코딩 예시:

```json
{
  "memberId": 1,
  "loginId": "user01",
  "name": "홍길동",
  "generation": 30,
  "status": "AM",
  "roles": ["USER"],
  "issuedAt": "2026-06-01T10:00:00",
  "expiresAt": "2026-06-01T11:00:00"
}
```

> **보안**: 내부 서비스는 반드시 Gateway 뒤에서만 실행. 직접 공개 시 X-User-Passport 위조 가능.

---

## 인증 불필요 경로 (토큰/쿠키 없이 통과)

| 경로 | 설명 |
|------|------|
| `/api/v1/auth/login` | 로그인 |
| `/api/v1/auth/signup` | 회원가입 |
| `/api/v1/auth/logout` | 로그아웃 |
| `/api/v1/auth/reissue` | AT/RT 재발급 (rt 쿠키 기반) |
| `/oauth2/jwks` | JWKS 공개키 |
| `/.well-known/**` | OIDC Discovery |
| `/actuator/` | Gateway 헬스 체크 |
| `/api/health-check` | EEOS-BE 헬스 체크 |
| `/api/auth/` | EEOS 자체 로그인 (레거시) |
| `/api/guest/` | 게스트 접근 |
| `/api/slack/events` | Slack 이벤트 수신 |

---

## 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `AUTH_API_URI` | auth-api 내부 주소 | `http://localhost:8081` |
| `EEOS_API_URI` | EEOS-BE 내부 주소 | `http://localhost:8080` |
| `AUTH_JWKS_URI` | JWKS 조회 URI (auth-api 직접 — Gateway URL 사용 시 자기참조 루프) | `http://localhost:8081/oauth2/jwks` |
| `AUTH_ISSUER_URI` | JWT `iss` 클레임 검증값 (Gateway 공개 URL) | `http://localhost:8080` |
| `CORS_ALLOWED_ORIGINS` | CORS 허용 오리진 | `http://localhost:3000` |

---

## 로컬 실행

```bash
# 사전 조건: auth-api가 :8081에서 실행 중이어야 함
AUTH_API_URI=http://localhost:8081 \
AUTH_JWKS_URI=http://localhost:8081/oauth2/jwks \
AUTH_ISSUER_URI=http://localhost:8081 \
EEOS_API_URI=http://localhost:8080 \
CORS_ALLOWED_ORIGINS=http://localhost:3000 \
./gradlew :services:apis:api-gateway:bootRun --args='--server.port=8082'
```
