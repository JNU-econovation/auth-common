# api-gateway

Spring Cloud Gateway 기반 인증 게이트웨이. JWT/쿠키 검증 후 Passport를 생성하여 내부 서비스로 전달.
정적 보호 라우트(auth-api 핵심 경로)와 동적 서비스 라우트(`service_route` 테이블)를 공존시킨다.

---

## 전체 요청 흐름

```
클라이언트 (WEB: at 쿠키 자동 전송 / APP: Authorization: Bearer)
        ↓
api-gateway
  BearerToPassportFilter (GlobalFilter, @Order(-1) — 라우팅 prefix 재작성 전 실행):
    ⓪ 인바운드 X-User-Passport 무조건 제거 (위조 방지 — 항상, 경로·토큰 유무 무관)
    ① Bearer 헤더에서 AT 추출  ← APP
    ② 없으면 at 쿠키에서 추출  ← WEB

    [토큰 없음]
      ③ 경로 무관 passthrough → 내부 서비스 (@PassportAuth가 인증 강제)

    [유효 토큰]
      ③ JWT RS256 로컬 검증 (JWKS 캐시: AUTH_JWKS_URI → auth-api /oauth2/jwks) ※ 인트로스펙션 아님
      ④ Passport JSON → Base64 인코딩 → X-User-Passport 헤더 주입

    [무효 토큰 (형식 오류·만료·서명 오류·issuer 불일치)]
      ③ 보호 경로 → HTTP 401 반환
         permitted-paths 경로 → passthrough (X-User-Passport 없음)
        ↓
  라우팅 (우선순위 순):
    [정적 보호, @Order(1)] GatewayRoutingConfig.RouteLocator:
      /api/v1/auth/**    → auth-api
      /api/v1/admin/**   → auth-api
      /api/v1/clients/** → auth-api
      /api/v1/members/** → auth-api
      /oauth2/**         → auth-api
      /.well-known/**    → auth-api
      /userinfo          → auth-api
      /swagger-ui/** 외  → auth-api
    [동적, Ordered.LOWEST_PRECEDENCE] DynamicRouteDefinitionRepository:
      service_route 테이블에서 Admin API로 등록된 서비스 경로
        ↓
내부 서비스
  X-User-Passport: eyJ... (Base64 JSON) — 유효 토큰인 경우만 존재
```

> **[인증 경계 변경 — ADR-0017]**
> 기존: 미토큰 + 보호 경로 → 게이트웨이 401
> 변경: 미토큰 + 보호 경로 → passthrough (다운스트림 `@PassportAuth`가 401 처리)
> 이유: 동적 라우팅 환경에서 게이트웨이는 prefix 재작성 전 경로를 보므로 `/eeos/guest/…`가 `permitted-paths`의 `/api/guest/**`와 불일치하여 오 401이 발생한다.

---

## 라우팅 구조

### 정적 보호 라우트

`GatewayRoutingConfig`(`@Order(1)`)에 auth-api 핵심 경로가 고정되어 있다. 동적 라우트가 이 경로를 가로채지 못하도록 우선순위를 보장한다. 변경 시 재배포 필요.

### 동적 서비스 라우트

`DynamicRouteDefinitionRepository`(`Ordered.LOWEST_PRECEDENCE`)가 인메모리 캐시로 동적 라우트를 관리한다.

- **기동 시**: `DynamicRouteConfig`가 `ApplicationReadyEvent`에서 auth-api `GET /api/v1/internal/routes`를 호출하여 `enabled=true` 라우트 전량을 로드.
- **즉시 갱신**: auth-api에서 라우트 CRUD 후 `POST /api/v1/internal/routes/refresh`를 호출하면 캐시를 교체하고 `RefreshRoutesEvent`를 발행.

StripPrefix 미적용 — 전체 경로가 업스트림에 그대로 전달된다.

---

## 내부 refresh 엔드포인트

`POST /api/v1/internal/routes/refresh` — auth-api만 호출하는 게이트웨이 내부 전용 엔드포인트.

- Spring Cloud Gateway 라우팅 테이블(`service_route`)에 등록되지 않으므로 외부에서 접근 불가.
- `InternalRouteRefreshRouter`(`RouterFunction`)로 등록.
- `RouteRefreshHandler`가 `X-Internal-Secret` 헤더를 상수시간 비교(`MessageDigest.isEqual`) 후 `DynamicRouteDefinitionRepository.reload()` + `RefreshRoutesEvent` 발행.
- Secret 불일치 시 403 반환.

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

Gateway가 유효 토큰 검증 성공 시 자동 주입. 내부 서비스는 이 헤더를 신뢰한다.

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

> **위조 방지**: 인바운드 `X-User-Passport` 헤더는 토큰 유무·경로에 관계없이 항상 제거된다. Gateway만 이 헤더를 신뢰값으로 설정할 수 있다. 위조 헤더를 포함한 요청이 유효 토큰과 함께 오면, 위조 헤더를 제거한 뒤 검증된 Passport로 덮어쓴다.

> **보안**: 내부 서비스는 반드시 Gateway 뒤에서만 실행. 직접 공개 시 X-User-Passport 없는 요청이 다운스트림에 도달한다.

---

## 인증 불필요 경로 (`permitted-paths`)

`application.yml`의 `gateway.permitted-paths`에서 관리한다.

> **역할 변경 (ADR-0017)**: `permitted-paths`는 **무효 토큰(만료·서명 오류) 분기에서만** 통과/거부를 판별한다. 미토큰 요청은 `permitted-paths` 참조 없이 경로 무관 passthrough한다.

| 경로 | 설명 |
|------|------|
| `/api/v1/auth/login` | 로그인 |
| `/api/v1/auth/signup` | 회원가입 |
| `/api/v1/auth/logout` | 로그아웃 |
| `/api/v1/auth/reissue` | AT/RT 재발급 (rt 쿠키 기반) |
| `/oauth2/**` | JWKS 공개키, 토큰 발급 등 SAS 엔드포인트 |
| `/.well-known/**` | OIDC Discovery |
| `/actuator/**` | Gateway 헬스 체크 |
| `/swagger-ui/**`, `/v3/api-docs/**` | API 문서 공개 열람 |

---

## 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `AUTH_API_URI` | auth-api 내부 주소 (정적 라우팅 + 동적 라우트 초기 로드) | `http://localhost:8081` |
| `AUTH_JWKS_URI` | JWKS 조회 URI (auth-api 직접 — Gateway URL 사용 시 자기참조 루프) | `http://localhost:8081/oauth2/jwks` |
| `AUTH_ISSUER_URI` | JWT `iss` 클레임 검증값 (Gateway 공개 URL) | `http://localhost:8080` |
| `GATEWAY_INTERNAL_SECRET` | auth-api → api-gateway 내부 refresh 공유 시크릿 | `dev-secret` |
| `CORS_ALLOWED_ORIGINS` | CORS 허용 오리진 | `http://localhost:3000` |

> `GATEWAY_INTERNAL_SECRET`은 auth-api와 api-gateway 양쪽에 동일 값을 주입해야 한다.

---

## 로컬 실행

```bash
# 사전 조건: auth-api가 :8081에서 실행 중이어야 함
AUTH_API_URI=http://localhost:8081 \
AUTH_JWKS_URI=http://localhost:8081/oauth2/jwks \
AUTH_ISSUER_URI=http://localhost:8081 \
GATEWAY_INTERNAL_SECRET=dev-secret \
CORS_ALLOWED_ORIGINS=http://localhost:3000 \
./gradlew :services:apis:api-gateway:bootRun --args='--server.port=8082'
```
