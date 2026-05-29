# Gateway 동적 라우팅 설계 및 운영 가이드

API Gateway가 auth-api에서 라우트 정보를 주기적으로 가져와 요청을 다운스트림 서비스로 전달하는 동적 라우팅 메커니즘을 설명한다.

## 개요

API Gateway는 Spring Cloud Gateway(SCG) 위에서 동작한다. 라우트 정보는 정적 설정 파일 대신 auth-api의 `service_route` 테이블에서 관리된다. Gateway는 이 정보를 폴링하여 인메모리 캐시에 유지하고, SCG `RouteLocator`로 노출한다.

```
Client → API Gateway
           ├─ BearerToPassportFilter (JWT 검증, Passport 헤더 주입)
           └─ DynamicRouteLocator
                └─ RouteDefinitionCache (30초 폴링)
                     └─ AuthApiRouteClient
                          └─ GET /api/v1/admin/routes → auth-api
```

---

## 구성 요소

### RouteDefinitionCache

**파일**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/route/RouteDefinitionCache.java`

auth-api에서 라우트 정의를 30초마다 폴링하여 인메모리 캐시에 유지한다.

**주요 특성**:
- `AtomicReference<List<RouteDefinition>>`으로 스레드 안전성 보장
- 기동 시(`@PostConstruct`) 즉시 1회 초기화 시도
- 폴링 실패 시 이전 캐시 데이터를 유지한다 (AtomicReference를 업데이트하지 않음)
- 폴링 주기: `fixedDelay = 30_000ms` (이전 폴링 완료 후 30초)

```java
@Scheduled(fixedDelay = 30_000L)
public void refreshRoutes() { ... }
```

**운영 함의**: 라우트를 새로 등록하거나 삭제해도 최대 30초 지연 후 Gateway에 반영된다.

### AuthApiRouteClient

**파일**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/route/AuthApiRouteClient.java`

`GET {AUTH_API_URI}/api/v1/admin/routes` 를 WebClient로 호출한다. `X-Internal-Api-Key` 헤더를 필수로 포함한다.

**환경변수**:

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `AUTH_API_URI` | `http://localhost:8081` | auth-api 내부 주소 |
| `AUTH_INTERNAL_API_KEY` | (필수) | 내부 API 키 (auth-api와 동일 값 설정) |

응답이 null이거나 `routes`가 null이면 빈 목록을 반환하고 캐시를 업데이트하지 않는다.

### DynamicRouteLocator

**파일**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/route/DynamicRouteLocator.java`

`RouteDefinitionCache`에서 읽은 `RouteDefinition` 목록을 SCG `Route` 객체로 변환하여 `Flux<Route>`로 반환한다.

**라우트 매칭 규칙**:

1. `pathPrefix`가 있는 경우: 요청 경로가 `{pathPrefix}/`로 시작하거나 `{pathPrefix}`와 정확히 일치할 때 매칭
2. `pathPrefix`가 없거나 공백인 경우: 모든 경로에 매칭

**비정상 라우트 처리**:
- `upstreamUrl`이 null, 공백, 잘못된 URI 형식이거나 `http`/`https` 스키마가 아닌 경우: 해당 라우트를 건너뜀 (WARN 로그)
- 개별 라우트 변환 중 예외 발생 시: 해당 라우트만 건너뜀, 나머지 라우트는 정상 처리
- 캐시 조회 자체가 실패하는 경우: 빈 `Flux` 반환 (서킷 브레이커 역할)

### RouteDefinition

**파일**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/route/RouteDefinition.java`

```java
public record RouteDefinition(
    String routeId,    // 라우트 UUID
    String clientId,   // 연결된 OAuth 클라이언트 ID
    String upstreamUrl, // 업스트림 서비스 URL
    String pathPrefix   // 경로 접두사 (nullable)
)
```

---

## 라우트 등록 절차

새 마이크로서비스를 Gateway 라우팅에 추가하려면 auth-api의 클라이언트 등록 API를 호출한다.

```bash
curl -X POST http://<auth-api>/api/v1/admin/clients \
  -H "Content-Type: application/json" \
  -d '{
    "grantType": "client_credentials",
    "clientName": "new-service",
    "upstreamUrl": "http://new-service:8080",
    "pathPrefix": "/api/new"
  }'
```

응답 예시:

```json
{
  "clientId": "a3f7c2d1-85b4-4e9a-bf32-1c0e7d9fa821",
  "clientSecret": "xKz3Qp9mRvLs7wNt2YhJ4dUiOeAn0BfCgXvPqWmE5c",
  "routeId": "e9b1d4a7-3f2c-4b8e-9d06-5a7c1f3e2b90"
}
```

등록 후 Gateway는 최대 30초 내에 새 라우트를 반영한다.

---

## 설정 주의사항

### pathPrefix 중복

`service_route.path_prefix`에 UNIQUE 제약이 있다. 동일 `pathPrefix`로 두 번 등록하면 DB 오류가 발생한다.

### upstreamUrl 유효성

`http://` 또는 `https://` 스키마만 허용된다. 다른 스키마나 잘못된 URI 문자열을 등록하면 Gateway가 해당 라우트를 무시한다 (WARN 로그 발생).

### AUTH_INTERNAL_API_KEY 동기화

auth-api와 api-gateway 양쪽에 동일한 `AUTH_INTERNAL_API_KEY` 환경변수를 설정해야 한다. 불일치 시 Gateway가 라우트를 가져오지 못하고 빈 캐시 상태를 유지한다.

---

## 장애 시나리오 및 동작

| 시나리오 | Gateway 동작 |
|---------|-------------|
| auth-api 일시 중단 | 마지막 성공 캐시를 계속 사용 (트래픽 유지) |
| Gateway 기동 시 auth-api 미응답 | 빈 라우트 캐시로 시작 (WARN 로그), auth-api 복구 후 30초 내 자동 반영 |
| 개별 라우트 upstreamUrl 오류 | 해당 라우트만 제외, 나머지 정상 처리 |
| 캐시 전체 조회 실패 | 빈 Flux 반환, 모든 동적 라우트 일시 중단 |

---

## 연관 문서

- `docs/CLIENT_REGISTRATION.md` — OAuth 클라이언트 등록 API 명세
- `docs/ARCHITECTURE.md` — 전체 시스템 아키텍처 (Gateway 역할 포함)
