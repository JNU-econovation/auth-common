# api-gateway

Spring Cloud Gateway 기반 인증 게이트웨이. JWT 검증 후 Passport를 생성하여 내부 서비스로 전달한다.

---

## 전체 요청 흐름

```
클라이언트
  │  Authorization: Bearer <RS256 JWT>
  ▼
api-gateway (:8080)
  ├─ BearerToPassportFilter (GlobalFilter, order=-1)
  │    ① Authorization 헤더에서 Bearer 토큰 추출
  │    ② auth-api JWKS(/oauth2/jwks)로 RS256 서명 검증
  │    ③ JWT 클레임 → Passport JSON → Base64 인코딩
  │    ④ X-User-Passport 헤더 주입
  │    ⑤ Authorization 헤더 제거 (removeRequestHeader 라우트 필터)
  │       → EEOS-BE 자체 HMAC 검증기와 충돌 방지
  │
  ├─ /api/v1/auth/**  →  auth-api (:8081)  [회원가입·로그인·재발급]
  ├─ /oauth2/**       →  auth-api (:8081)  [SAS OAuth2 엔드포인트]
  ├─ /.well-known/**  →  auth-api (:8081)  [OIDC Discovery]
  ├─ /userinfo        →  auth-api (:8081)
  └─ /api/**          →  EEOS-BE  (:8080)  [비즈니스 API]

EEOS-BE
  │  X-User-Passport: eyJ... (Base64 JSON, Authorization 헤더 없음)
  ▼
PassportAuthenticationFilter
  → Base64 디코딩 → memberId, roles 추출 → JwtAuthentication 설정
  → @Member Long memberId 주입
```

---

## X-User-Passport 헤더 형식

Gateway가 JWT 검증 성공 시 자동 주입. 내부 서비스는 신뢰한다.

```
X-User-Passport: Base64(UTF-8 JSON)
```

디코딩 예시:

```json
{
  "memberId": 1,
  "loginId": "flowtest01",
  "name": "홍길동",
  "generation": 30,
  "status": "AM",
  "roles": ["USER"],
  "issuedAt": "2026-05-29T10:00:00",
  "expiresAt": "2026-05-29T11:00:00"
}
```

> **보안 주의**: 내부 서비스는 반드시 Gateway 뒤에서만 실행해야 한다. 직접 공개 노출 시 Passport 위조가 가능하다.

---

## 인증 불필요 경로 (Bearer 토큰 없이 통과)

| 경로 접두사 | 대상 서버 | 설명 |
|-------------|-----------|------|
| `/api/v1/auth/signup` | auth-api | 회원 가입 |
| `/api/v1/auth/login` | auth-api | 로그인 (JWT 발급) |
| `/api/v1/auth/logout` | auth-api | 로그아웃 |
| `/api/v1/auth/reissue` | auth-api | AT/RT 재발급 |
| `/oauth2/` | auth-api | SAS OAuth2 표준 엔드포인트 |
| `/.well-known/` | auth-api | OIDC Discovery |
| `/userinfo` | auth-api | UserInfo |
| `/actuator/` | Gateway 자체 | 헬스 체크 |
| `/api/health-check` | EEOS-BE | EEOS 헬스 체크 |
| `/api/auth/` | EEOS-BE | EEOS 자체 로그인 (레거시) |
| `/api/guest/` | EEOS-BE | 게스트 접근 |
| `/api/slack/events` | EEOS-BE | Slack 이벤트 수신 |

---

## 로그인 흐름 (클라이언트 관점)

### WEB (브라우저)

```bash
# 1. 로그인
POST /api/v1/auth/login
Client-Type: WEB          # 생략 시 WEB이 기본값
{ "loginId": "user01", "password": "Econo1234!" }

# 응답
HTTP 200
Set-Cookie: rt=<RT>; HttpOnly; SameSite=None; Secure; Max-Age=2592000
{ "accessToken": "<AT>", "accessExpiredTime": 1780040000000 }

# 2. API 호출 (Gateway가 검증 후 X-User-Passport 주입)
GET /api/programs?category=attend&programStatus=BEFORE_ACTIVITY&page=0&size=10
Authorization: Bearer <AT>

# 3. AT 만료 시 재발급 (브라우저가 rt 쿠키 자동 전송)
POST /api/v1/auth/reissue
Client-Type: WEB
```

### APP (모바일)

```bash
# 1. 로그인
POST /api/v1/auth/login
Client-Type: APP
{ "loginId": "user01", "password": "Econo1234!" }

# 응답 (쿠키 없음, AT + RT 모두 body)
{ "accessToken": "<AT>", "accessExpiredTime": ..., "refreshToken": "<RT>" }

# 2. API 호출
Authorization: Bearer <AT>

# 3. 재발급
POST /api/v1/auth/reissue
Client-Type: APP
{ "refreshToken": "<RT>" }
```

---

## 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `AUTH_API_URI` | auth-api 내부 주소 | `http://localhost:8081` |
| `EEOS_API_URI` | EEOS-BE 내부 주소 | `http://localhost:8080` |
| `AUTH_JWKS_URI` | JWKS 조회 URI — **auth-api 직접 주소** (Gateway 공개 URL 사용 시 자기참조 루프 발생) | `http://localhost:8081/oauth2/jwks` |
| `AUTH_ISSUER_URI` | JWT `iss` 클레임 검증값 (Gateway 공개 URL과 동일) | `http://localhost:8080` |

---

## 모듈 구조

```
services/apis/api-gateway/
└── src/main/java/com/econo/auth/gateway/
    ├── ApiGatewayApplication.java
    ├── filter/
    │   └── BearerToPassportFilter.java   # GlobalFilter (order=-1)
    ├── security/
    │   ├── JwtVerifier.java              # JWKS 기반 RS256 검증
    │   └── PassportBuilder.java          # JWT 클레임 → Passport 직렬화
    └── config/
        ├── GatewayRoutingConfig.java     # 정적 라우트 + removeRequestHeader(Authorization)
        └── GatewaySecurityConfig.java    # SecurityWebFilterChain (anyExchange().permitAll())
```

---

## 로컬 실행

```bash
# 사전 조건: auth-api가 :8081에서 실행 중이어야 함
AUTH_API_URI=http://localhost:8081 \
AUTH_JWKS_URI=http://localhost:8081/oauth2/jwks \
AUTH_ISSUER_URI=http://localhost:8081 \
EEOS_API_URI=http://localhost:8080 \
./gradlew :services:apis:api-gateway:bootRun --args='--server.port=8082'
```
