# Gateway 동적 라우팅 가이드

api-gateway는 **정적 보호 라우트**와 **동적 서비스 라우트**를 공존시킨다. 정적 보호 라우트는 auth-api 핵심 경로를 `GatewayRoutingConfig`에 고정하고, 그 외 신규 서비스 경로는 `service_route` 테이블에서 런타임에 로드하여 재배포 없이 즉시 반영한다. 설계 근거: [ADR-0016](./adr/0016-dynamic-gateway-routing-reintroduction.md)

---

## 라우트 종류

### 정적 보호 라우트 (GatewayRoutingConfig)

다음 경로는 `GatewayRoutingConfig.java`의 `RouteLocator` 빈(`@Order(1)`)에 고정되어 있다. `service_route` 테이블에 등록하거나 Admin API로 수정·삭제할 수 없다.

| 라우트 ID | 경로 패턴 | 업스트림 |
|-----------|----------|---------|
| `auth-api` | `/api/v1/auth/**` | `AUTH_API_URI` |
| `auth-admin` | `/api/v1/admin/**` | `AUTH_API_URI` |
| `auth-clients` | `/api/v1/clients/**` | `AUTH_API_URI` |
| `auth-members` | `/api/v1/members/**` | `AUTH_API_URI` |
| `sas-oauth2` | `/oauth2/**` | `AUTH_API_URI` |
| `sas-well-known` | `/.well-known/**` | `AUTH_API_URI` |
| `sas-userinfo` | `/userinfo` | `AUTH_API_URI` |
| `auth-swagger` | `/swagger-ui/**`, `/v3/api-docs/**` 외 Swagger 경로 | `AUTH_API_URI` |

### 동적 서비스 라우트 (service_route 테이블)

보호 경로 외의 신규 서비스는 Admin API(`POST /api/v1/admin/routes`)로 등록한다. 등록 즉시 api-gateway의 인메모리 캐시(`DynamicRouteDefinitionRepository`)가 갱신되고 `RefreshRoutesEvent`가 발행된다.

StripPrefix를 적용하지 않으므로 클라이언트가 요청한 전체 경로가 업스트림에 그대로 전달된다.

---

## 라우트 등록 (Admin API)

`ADMIN` 또는 `SUPER_ADMIN` 역할의 Passport가 필요하다. Gateway가 Bearer JWT를 검증하고 `X-User-Passport` 헤더를 자동 주입한다.

```bash
# 새 서비스 동적 라우트 등록
curl -X POST https://gateway/api/v1/admin/routes \
  -H "Authorization: Bearer <access-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "pathPrefix": "/api/v2/my-service",
    "upstreamUrl": "http://my-service:8080",
    "enabled": true
  }'
# → 201 Created
# {
#   "routeId": "a316bc69-...",
#   "pathPrefix": "/api/v2/my-service",
#   "upstreamUrl": "http://my-service:8080",
#   "enabled": true,
#   "createdAt": "2026-06-14T10:00:00"
# }
```

등록 후: `GET https://gateway/api/v2/my-service/anything` → `http://my-service:8080/api/v2/my-service/anything`

---

## 라우트 관리 API

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| `POST` | `/api/v1/admin/routes` | 동적 라우트 등록 | ADMIN / SUPER_ADMIN |
| `GET` | `/api/v1/admin/routes` | 전체 라우트 목록 조회 | ADMIN / SUPER_ADMIN |
| `GET` | `/api/v1/admin/routes/{routeId}` | 단건 라우트 조회 | ADMIN / SUPER_ADMIN |
| `PUT` | `/api/v1/admin/routes/{routeId}` | 라우트 수정 | ADMIN / SUPER_ADMIN |
| `DELETE` | `/api/v1/admin/routes/{routeId}` | 라우트 삭제 | ADMIN / SUPER_ADMIN |

**에러 코드:**

| HTTP | 에러 코드 | 발생 조건 |
|------|-----------|-----------|
| 400 | `ROUTE_UPSTREAM_INVALID` | upstreamUrl SSRF 검증 실패 (비허용 스킴, private IP 등) |
| 403 | `ROUTE_PROTECTED` | 보호 경로 패턴과 충돌하는 pathPrefix 등록/수정/삭제 시도 |
| 404 | `ROUTE_NOT_FOUND` | routeId에 해당하는 라우트 없음 |
| 409 | `ROUTE_PATH_CONFLICT` | pathPrefix 중복 |

> 컨트롤러: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminRouteController.java`

---

## 라우트 로딩·갱신 흐름

### 기동 시 초기 로드

```
api-gateway 기동
  → DynamicRouteConfig.onApplicationReady() (@EventListener ApplicationReadyEvent)
    → DynamicRouteDefinitionRepository.reload() — 캐시 전량 교체
      → AuthApiRouteClient.fetchEnabledRoutes()
        → GET AUTH_API_URI/api/v1/internal/routes (X-Internal-Secret 헤더)
    → ApplicationEventPublisher.publishEvent(RefreshRoutesEvent)
```

auth-api가 아직 기동되지 않은 경우 예외를 흡수하고 빈 캐시로 기동한다. 이후 첫 라우트 CRUD 시 refresh로 캐시가 채워진다.

### CRUD 후 즉시 반영

```
ADMIN → POST /api/v1/admin/routes (auth-api)
  → ManageRouteService.createRoute()
    → SSRF / 보호경로 / 중복 검증
    → ServiceRouteRepository.save() — DB INSERT
    → GatewayRefreshClient.triggerRefresh()
      → POST GATEWAY_URI/api/v1/internal/routes/refresh (X-Internal-Secret)
        → RouteRefreshHandler (api-gateway)
          → DynamicRouteDefinitionRepository.reload()
          → RefreshRoutesEvent 발행 → 즉시 반영
  → 201 Created
```

refresh 실패(api-gateway 다운, 네트워크 오류 등) 시 라우트는 DB에 저장되며 경고 로그만 남긴다. api-gateway 재기동 시 초기 로드로 자동 복구된다.

---

## SSRF 방지 규칙

`ManageRouteService`가 `upstreamUrl` 등록·수정 시 검증한다(`validateUpstreamUrl` / `isPrivateOrLoopback`).

1. **허용 스킴**: `http`, `https`만 허용. 그 외 스킴은 즉시 거부.
2. **호스트 필수**: 빈 호스트 거부.
3. **localhost 차단**: 호스트가 `localhost`이면 DNS 조회 없이 즉시 거부.
4. **명시적 IP 차단**: 호스트가 IP 주소 형식이면 `InetAddress.getByName()`이 DNS 조회 없이 직접 파싱하므로 아래 범위를 즉시 차단한다.
   - loopback: `127.0.0.0/8`, `::1`
   - site-local(RFC 1918 private): `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
   - link-local: `169.254.0.0/16`, `fe80::/10`
   - anyLocal(와일드카드): `0.0.0.0`, `::`
5. **hostname DNS 조회 실패는 허용**: 호스트가 hostname 형식이고 DNS 조회에 실패(`UnknownHostException`)하면 내부망 컨테이너 hostname으로 간주하여 허용한다. Docker 내부 hostname(`http://my-service:8080`) 등록이 가능한 이유다. 단, 등록 시점 DNS 검증이므로 DNS rebinding 공격에 대한 완전한 방어는 되지 않으며, 내부망 전제 운영 환경에서 수용하는 트레이드오프다.

---

## 보호 경로 목록

`ProtectedPathPolicy`(포트)에 정의된 아래 경로 패턴과 충돌하는 pathPrefix는 등록·수정·삭제가 거부된다.

```
/api/v1/auth/**
/oauth2/**
/.well-known/**
/userinfo
/swagger-ui/**
/swagger-ui.html
/v3/api-docs/**
/v3/api-docs
/actuator/**
/api/v1/admin/**
/api/v1/members/**
/api/v1/clients/**
/api/v1/internal/**     # 게이트웨이 전용 내부 엔드포인트 보호
```

> 판정 포트: `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ProtectedPathPolicy.java`
> 값·구현체(소비자 앱 소유): `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ProtectedPathPolicyImpl.java`
>
> 보호 경로 값은 배포 환경(게이트웨이 정적 라우트)에 종속되므로 auth-api가 소유하며, api-gateway의 `GatewayRoutingConfig`와 수동 동기화한다.

---

## 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `GATEWAY_INTERNAL_SECRET` | auth-api↔api-gateway 내부 통신 공유 시크릿 | `dev-secret` |
| `GATEWAY_URI` | auth-api가 api-gateway refresh를 호출할 URI | `http://localhost:8080` |
| `AUTH_API_URI` | api-gateway가 auth-api를 호출할 URI | `http://localhost:8081` |

`GATEWAY_INTERNAL_SECRET`은 auth-api와 api-gateway 양쪽에 동일 값을 주입해야 한다.

---

## 주의사항

- `pathPrefix`는 UNIQUE — 같은 경로에 두 라우트 등록 불가 (`ROUTE_PATH_CONFLICT`)
- 루트 경로 `/` 등록 금지 — 전체 트래픽을 단일 업스트림으로 보내는 위험한 설정
- 동적 라우트는 StripPrefix를 적용하지 않는다. `/api/v2/my-service/users`로 요청하면 업스트림도 `/api/v2/my-service/users` 전체를 수신한다
- 다중 인스턴스 환경에서는 refresh가 단일 인스턴스에만 전달된다. 확장이 필요하면 `GatewayRefreshClient`를 Redis pub/sub 방식으로 교체한다
