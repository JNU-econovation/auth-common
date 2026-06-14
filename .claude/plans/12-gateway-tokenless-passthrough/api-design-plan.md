# gateway-tokenless-passthrough - api-design

## 메타
- **작업명**: gateway-tokenless-passthrough
- **문서 타입**: api-design
- **작성일**: 2026-06-14
- **관련 문서** (같은 디렉터리):
  - todo.md

---

## 개요

이 작업은 **신규 엔드포인트가 없다.** 변경 대상은 `api-gateway`의 `BearerToPassportFilter`(GlobalFilter) 동작이다.
변경 전후에 걸쳐 게이트웨이가 요청을 어떻게 처리하는지, 즉 "인증 경계(auth boundary)"의 책임 분할을 명세한다.
프로토콜은 Spring Cloud Gateway WebFlux GlobalFilter 체인이며, 외부 클라이언트와 게이트웨이 사이는 REST(HTTP/1.1)다.

---

## 본문

### 엔드포인트 변경 없음 선언

이 작업으로 추가·제거·시그니처 변경되는 엔드포인트는 **없다.**
변경되는 것은 `BearerToPassportFilter`의 내부 분기 로직과 그에 따른 게이트웨이 동작(passthrough vs 401)뿐이다.

---

### 요청 처리 매트릭스

> 경로 분류 기준:
> - **permitted-paths** — `application.yml`의 `gateway.permitted-paths` 목록에 매칭되는 경로 (변경 후에는 무효 토큰 분기에서만 사용)
> - **보호경로** — permitted-paths에 매칭되지 않는 모든 경로

| 토큰 상태 | 경로 종류 | 변경 전(Before) 게이트웨이 동작 | 변경 후(After) 게이트웨이 동작 |
|-----------|-----------|-------------------------------|-------------------------------|
| 토큰 없음 | 보호경로 | `401 Unauthorized` (게이트웨이 거부) | **passthrough** — X-User-Passport 미주입, 다운스트림에 위임 |
| 토큰 없음 | permitted-paths | passthrough (X-User-Passport 미주입) | passthrough (X-User-Passport 미주입) ← 동일 |
| 유효 토큰 | 모든 경로 | JWKS 로컬 검증 → X-User-Passport 주입 → passthrough | JWKS 로컬 검증 → X-User-Passport 주입 → passthrough ← 동일 |
| 무효 토큰 (만료·서명·issuer 불일치) | 보호경로 | `401 Unauthorized` (게이트웨이 거부) | `401 Unauthorized` (게이트웨이 거부) ← 동일 |
| 무효 토큰 (만료·서명·issuer 불일치) | permitted-paths | passthrough (X-User-Passport 미주입) | passthrough (X-User-Passport 미주입) ← 동일 |
| 모든 경우 | 모든 경로 | 인바운드 X-User-Passport 제거 안 함 (위조 위험) | **인바운드 X-User-Passport 항상 제거** (분기 이전에 먼저 실행) |

#### 동작 변화 요약

- **변경되는 케이스**: 토큰 없음 + 보호경로 → 게이트웨이 401 **삭제** → 게이트웨이가 passthrough하고 다운스트림 `@PassportAuth`가 401 처리
- **새로 추가되는 불변 동작**: 인바운드 `X-User-Passport` 헤더를 토큰 유무·경로 분류 이전에 **항상** 제거

#### 영향 받는 대표 경로

| 경로 예시 | 토큰 없음 — 변경 전 | 토큰 없음 — 변경 후 | 최종 HTTP 응답 |
|----------|-------------------|-------------------|---------------|
| `/api/v1/admin/**` | 게이트웨이 401 | 게이트웨이 passthrough | 다운스트림 `@PassportAuth(role=ADMIN)` → **401** (동일 결과, 책임만 이동) |
| `/api/v1/members/**` | 게이트웨이 401 | 게이트웨이 passthrough | 다운스트림 `@PassportAuth` → **401** (동일 결과) |
| `/api/v1/clients/**` | 게이트웨이 401 | 게이트웨이 passthrough | 다운스트림 `@PassportAuth` → **401** (동일 결과) |
| `/eeos/guest/**` (동적 라우트) | 게이트웨이 401 (오 401 — 버그) | 게이트웨이 passthrough | 다운스트림이 guest 허용이면 **200** (버그 해소) |
| `/eeos/public/**` (동적 라우트) | 게이트웨이 401 (오 401 — 버그) | 게이트웨이 passthrough | 다운스트림이 public 허용이면 **200** (버그 해소) |
| `/api/v1/auth/login` | passthrough (permitted) | passthrough (permitted) | 동일 |
| `/oauth2/**` | passthrough (permitted) | passthrough (permitted) | 동일 |

---

### 동작 변화 세부 (Before / After)

#### Before

```
클라이언트 → api-gateway (BearerToPassportFilter)
  1. extractBearerToken()
  2. 토큰 없음
     → isProtectedPath(path)?
       → 보호경로: 401 반환 (체인 중단)
       → permitted: chain.filter(exchange) passthrough
  3. 토큰 있음
     → jwtVerifier.verify()
       → 성공: X-User-Passport 주입 → chain.filter()
       → 실패 onErrorResume:
         → 보호경로: 401 반환
         → permitted: chain.filter() passthrough
```

#### After

```
클라이언트 → api-gateway (BearerToPassportFilter)
  0. [항상 먼저] 인바운드 X-User-Passport 헤더 제거 (위조 방지)
  1. extractBearerToken()
  2. 토큰 없음
     → 경로 무관 chain.filter(exchange) passthrough (X-User-Passport 미주입)
       ↓ 다운스트림에서 @PassportAuth가 Passport 부재 → 401
  3. 토큰 있음
     → jwtVerifier.verify()  ← JWKS 로컬 RS256/만료/issuer 검증 (auth-api 호출 아님)
       → 성공: X-User-Passport 주입 (strip 후 검증된 값 set) → chain.filter()
       → 실패 onErrorResume:
         → 보호경로: 401 반환 (기존과 동일)
         → permitted: chain.filter() passthrough (기존과 동일)
```

---

### 컴포넌트 역할 정의

이 섹션은 ARCHITECTURE 반영을 위한 정확한 메커니즘 명세다.

#### ApiGateway (api-gateway)

**역할**:
1. **라우팅** — 정적 보호 라우트(`GatewayRoutingConfig`, `@Order(1)`) + 동적 DB 라우트(`DynamicRouteDefinitionRepository`, `Ordered.LOWEST_PRECEDENCE`)
2. **Passport Enrich** — Bearer 헤더 또는 `at` 쿠키에서 JWT 추출 → auth-api가 발급한 JWKS 공개키(`AUTH_JWKS_URI`)로 **로컬 RS256 서명 검증 + 만료 확인 + issuer 일치 확인** → Passport JSON 직렬화 → `X-User-Passport` 헤더로 주입
3. **인바운드 위조 Passport 제거** — 클라이언트가 주입한 `X-User-Passport`를 **모든 요청, 모든 분기 이전에** 무조건 제거. 게이트웨이만 이 헤더를 set할 수 있음을 보장
4. **무효 토큰 거부** — 토큰이 있으나 검증 실패 시 보호경로에 한해 `401 Unauthorized` 반환
5. **미토큰 passthrough** — 토큰이 없으면 경로 무관 passthrough. 인증 강제(401)는 다운스트림에 위임

**게이트웨이가 하지 않는 것**:
- auth-api에 토큰을 보내는 Token Introspection(원격 검증) — JWKS 캐시를 이용한 로컬 검증만 수행
- 미토큰 요청의 401 거부 (변경 후) — 다운스트림 `@PassportAuth`에 위임

#### AuthApi (auth-api)

**역할**:
1. **토큰 발급** — Spring Authorization Server(SAS) OIDC Authorization Server. AT(RS256 JWT, Passport 클레임 포함) 발급
2. **JWKS 제공** — `GET /oauth2/jwks` — Gateway가 로컬 검증에 사용하는 RSA 공개키 제공
3. **자체 인증/인가** — 자신의 엔드포인트(`/api/v1/admin/**`, `/api/v1/members/**`, `/api/v1/clients/**` 등)에서 `@PassportAuth`로 인증·인가 처리. 게이트웨이에서 passthrough된 미토큰 요청은 여기서 401 반환
4. **라우트 CRUD** — `service_route` 테이블 관리. CRUD 후 api-gateway에 `POST /api/v1/internal/routes/refresh` 콜백
5. **회원·권한·서비스클라이언트 관리**

---

### permitted-paths의 역할 변화

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| 미토큰 분기에서 사용 | 예 (보호경로 판별 → 401 or passthrough) | **아니오** (미토큰은 경로 무관 passthrough) |
| 무효 토큰 분기에서 사용 | 예 | **예** (역할 유지) |
| 사실상 역할 | "미토큰/무효토큰 모두에서 401 게이트 경계" | **"무효 토큰 분기 전용 passthrough 허용 목록"** |

`application.yml`의 `gateway.permitted-paths` 설정값 자체는 변경하지 않는다. 단, 블록 주석에 용도 변경 내용을 명시한다.

---

### 보안 회귀 체크

| 보안 속성 | 변경 전 보장 여부 | 변경 후 보장 여부 | 근거 |
|----------|-----------------|-----------------|------|
| 위조 X-User-Passport 무력화 | 불완전 (헤더 strip 로직 없음) | **보장** | 모든 분기 이전 무조건 제거 |
| 보호경로 미토큰 → 최종 401 | 보장 (게이트웨이) | **보장** (다운스트림 `@PassportAuth`) | 책임이 이동했으나 결과 동일 |
| 보호경로 무효 토큰 → 401 | 보장 (게이트웨이) | **보장** (게이트웨이, 동일) | 이 분기는 변경 없음 |
| permitted-paths 무효 토큰 → passthrough | 보장 | **보장** (동일) | 이 분기는 변경 없음 |
| 게이트웨이 로컬 검증 (auth-api 비의존) | 보장 (JWKS 캐시) | **보장** (동일) | introspection 없음 |
| 내부 서비스 직접 공개 시 X-User-Passport 위조 | 취약 (네트워크 정책 필요) | 취약 (동일, 네트워크 정책 필요) | 변경 없음 — ADR-0002, ADR-0007 전제 조건 |

**핵심 보안 논리**: 다운스트림 내부 서비스는 게이트웨이 뒤에서만 실행되므로(직접 공개 금지, 네트워크 정책), 게이트웨이가 인바운드 `X-User-Passport`를 항상 제거하면 다운스트림이 받는 `X-User-Passport`는 오직 게이트웨이가 검증 후 주입한 것만 가능하다. 미토큰 요청은 `X-User-Passport`가 없는 상태로 다운스트림에 도달하므로 `@PassportAuth`가 Passport 부재를 감지하여 401을 반환한다.

---

### 내부 통신 엔드포인트 (참고 — 변경 없음)

이 작업과 직접 관련은 없으나, 게이트웨이의 전체 인증 경계 이해를 위해 기록한다.

`POST /api/v1/internal/routes/refresh`
- 호출자: auth-api (라우트 CRUD 후 콜백)
- 보호: `X-Internal-Secret` 헤더 상수시간 비교 (HMAC 타이밍 공격 방지)
- 이 엔드포인트는 Spring Cloud Gateway 라우팅 테이블에 등록되지 않으므로 외부 클라이언트 접근 불가
- 변경 없음

---

## 체크리스트
- [x] todo의 모든 API 작업이 명세됨 (API 작업: 해당 없음 — 선언됨)
- [x] 각 토큰 상태 × 경로 종류 조합의 게이트웨이 동작이 명시됨
- [x] 인바운드 X-User-Passport strip 동작이 명시됨
- [x] Before/After 동작 변화가 구체적인 경로 예시와 함께 명시됨
- [x] 컴포넌트 역할(ApiGateway, AuthApi)이 정확한 메커니즘 기준으로 정의됨
- [x] permitted-paths의 역할 변화가 명시됨
- [x] 보안 회귀 체크가 수행됨

---

## 참고

- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security/BearerToPassportFilter.java` — 변경 대상 필터 (현재 동작 기준)
- `services/apis/api-gateway/src/main/resources/application.yml` — `gateway.permitted-paths` 현재 목록
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — 정적 보호 라우트 및 permittedPaths()
- `services/apis/api-gateway/src/test/java/com/econo/auth/gateway/config/security/BearerToPassportFilterTest.java` — 현재 테스트 (Before 동작 기준)
- `services/apis/api-gateway/README.md` — 전체 요청 흐름 다이어그램
- `docs/adr/0002-gateway-as-auth-boundary.md` — Gateway 인증 경계 원칙 (이 변경이 보완하는 ADR)
- `docs/adr/0007-x-user-passport-header-pattern.md` — X-User-Passport 헤더 패턴
- `docs/adr/0004-rs256-jwt-with-passport-claims.md` — RS256 JWT + 로컬 JWKS 검증 근거
- `docs/adr/0016-dynamic-gateway-routing-reintroduction.md` — 동적 라우팅 (이 변경의 동기: prefix 재작성으로 인한 permitted-paths 불일치)
