# ADR-0016: 동적 DB 기반 Gateway 라우팅 재도입 (ADR-0005 supersede)

- **상태:** Accepted
- **결정일:** 2026-06-14
- **결정자:** econovation 개발팀
- **Supersedes:** [ADR-0005](./0005-static-yaml-routing-over-dynamic.md)

---

## 배경

ADR-0005(2026-06-01)는 "서비스 추가 빈도가 낮고 30초 폴링 복잡도 대비 효용이 낮다"는 이유로 `DynamicRouteLocator`·`RouteDefinitionCache` 등을 제거하고 `GatewayRoutingConfig.java` 정적 라우트로 전환했다. 당시 `service_route` 테이블은 DB에 남겨뒀으나 게이트웨이가 읽지 않는 상태였다.

ADR-0005에서 제시한 재검토 조건("서비스 10개 이상, 무중단 라우팅 추가 필요")이 충족됐다. 이에 더해:

- 신규 서비스 등록 시마다 `GatewayRoutingConfig.java` 수정 + Gateway 재배포가 필요해 운영 부담이 증가했다.
- `service_route` 테이블에 `registered_client_id FK NOT NULL` 제약이 있어 라우트를 OAuth 클라이언트 독립적으로 관리하기 어려웠다.
- 구 구현의 30초 폴링이 아닌 이벤트 기반(`RefreshRoutesEvent`) 즉시 반영이 가능해졌다.

---

## 결정

**api-gateway 라우팅을 정적 보호 라우트(auth-api 핵심 경로) + 동적 DB 라우트(service_route 테이블) 공존 구조로 전환한다.**

구체적으로:

### 아키텍처 선택 (두 가지 핵심 결정)

#### 결정 1: 라우트 CRUD API 소유 — auth-api

auth-api(Spring MVC)가 라우트 CRUD를 소유한다. `@PassportAuth`, `GlobalExceptionHandler`, JPA 인프라를 그대로 재사용할 수 있으며, SSRF·보호경로 검증 로직을 `ManageRouteService`에 집중할 수 있다.

api-gateway(WebFlux)에 CRUD를 두면 `PassportArgumentResolver`(HandlerMethodArgumentResolver) 사용 불가, `WebExceptionHandler` 별도 구현, R2DBC 추가 의존성이 필요하므로 기각.

#### 결정 2: 게이트웨이 라우트 로딩 — 옵션 B (auth-api REST + refresh 엔드포인트)

api-gateway는 DB에 직접 접근하지 않는다. 기동 시 auth-api `GET /api/v1/internal/routes` (WebClient 호출)로 초기 로드하고, 라우트 CRUD 시 auth-api가 api-gateway `POST /api/v1/internal/routes/refresh`를 호출하여 `RefreshRoutesEvent`를 즉시 발행한다.

옵션 A(R2DBC 직접 읽기)는 api-gateway에 DB 의존성이 추가되고, auth-api와 DB 커넥션을 공유하여 모듈 경계가 무너지므로 기각.

### 정적 vs 동적 라우트 구분

| 분류 | 경로 | 관리 방식 |
|------|------|---------|
| 정적 보호 라우트 | `/api/v1/auth/**`, `/api/v1/admin/**`, `/api/v1/clients/**`, `/api/v1/members/**`, `/oauth2/**`, `/.well-known/**`, `/userinfo`, `/swagger-ui/**`, `/v3/api-docs/**` | `GatewayRoutingConfig` Java DSL — 재배포 필요 |
| 동적 서비스 라우트 | 위 보호 경로 외 신규 서비스 경로 | `service_route` 테이블 — 재배포 없이 즉시 반영 |

`GatewayRoutingConfig`에 `@Order(1)`을 부여하고 `DynamicRouteDefinitionRepository`는 `Ordered.LOWEST_PRECEDENCE`로 설정하여 보호 경로가 항상 우선 매칭된다.

### DB 변경

- **V9** — `service_route.registered_client_id` FK 제약 제거 + nullable 전환 (라우트·클라이언트 독립 CRUD)
- **V10** — `idx_service_route_enabled` B-tree 인덱스 추가 (`findAllByEnabled(true)` 풀스캔 방지)

### 보안

- **SSRF 방지**: `ManageRouteService.validateUpstreamUrl()`이 허용 스킴(`http`/`https`), private IP 대역(`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `169.254.0.0/16`), anyLocal 차단.
- **보호 경로 불변**: `ProtectedPathPolicy`(포트)에 정의된 경로와 충돌하는 pathPrefix 등록/수정/삭제 시도는 `RouteProtectedException` (403 `ROUTE_PROTECTED`)로 거부. 보호 경로 값은 배포 환경에 종속되므로 소비자 앱 auth-api의 `ProtectedPathPolicyImpl`이 소유한다.
- **내부 통신 보호**: `X-Internal-Secret` 헤더 상수시간 비교(`MessageDigest.isEqual`)로 타이밍 공격 방지.

---

## 대안

### 옵션 A: R2DBC 직접 읽기

api-gateway가 `spring-boot-starter-data-r2dbc`로 `service_route` 테이블을 직접 쿼리.

기각 이유: DB 접근이 auth-api와 api-gateway에 이중화되어 모듈 경계가 무너진다. api-gateway에 DB 환경변수를 별도 관리해야 하고, 스키마 변경 시 두 모듈이 동시에 배포되어야 한다.

### 30초 폴링 방식 (ADR-0005 이전 구현)

기각 이유: 30초 지연이 운영 환경에서 허용 불가. 변경이 즉시 반영되지 않으면 장애 복구 시간이 늘어난다.

### Redis pub/sub 기반 다중 인스턴스 브로드캐스트

현재 단일 인스턴스 환경에서는 과도한 인프라 추가다. 단기에는 HTTP refresh 콜백으로 충분하며, 다중 인스턴스 필요 시 `GatewayRefreshClient`만 교체하면 된다.

---

## 결과

### 긍정적

- 신규 서비스 연동 시 Gateway 재배포 없이 Admin API(`POST /api/v1/admin/routes`)만으로 즉시 반영.
- 라우트와 OAuth 클라이언트를 독립적으로 관리 가능 (FK 제거).
- auth-api의 기존 `@PassportAuth`, `GlobalExceptionHandler`, JPA 인프라를 재사용하여 구현 복잡도 최소화.
- `RefreshRoutesEvent` 이벤트 기반으로 폴링 지연 없이 즉시 반영.

### 부정적 / 주의

- auth-api → api-gateway 내부 HTTP 콜백 의존이 생긴다. api-gateway가 다운된 상태에서 라우트 CRUD를 실행하면 refresh가 실패한다. 단, 라우트는 DB에 저장되므로 api-gateway 재기동 시 자동 복구된다 (최종 일관성 수용).
- `GATEWAY_INTERNAL_SECRET` 환경변수 관리가 필요하다. auth-api와 api-gateway 양쪽에 동일 값을 주입해야 한다.
- 다중 인스턴스 환경에서는 추가 확장이 필요하다 (단기: 순차 HTTP 호출, 장기: Redis pub/sub).
- `GatewayRoutingConfig` 보호 경로를 변경하려면 여전히 재배포가 필요하다.

---

## 관련 파일

- `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceRoute.java` — 라우트 도메인 record
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageRouteService.java` — SSRF·보호경로 검증 + CRUD + refresh 트리거
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ProtectedPathPolicy.java` — 보호 경로 판정 포트(인터페이스)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ProtectedPathPolicyImpl.java` — 보호 경로 값·매칭 구현체(소비자 앱 소유)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminRouteController.java` — 라우트 CRUD API (ADMIN/SUPER_ADMIN)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/InternalRouteController.java` — 게이트웨이 초기 로드용 내부 엔드포인트
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/DynamicRouteDefinitionRepository.java` — 인메모리 캐시 + 재로드
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — 정적 보호 라우트 (eeos 제거, `@Order(1)`)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/presentation/controller/RouteRefreshHandler.java` — refresh 엔드포인트 핸들러
- `db/migration/V9__decouple_service_route_from_client.sql` — FK 제거 + nullable
- `db/migration/V10__add_index_service_route_enabled.sql` — enabled 인덱스
- `docs/DYNAMIC_ROUTING.md` — 동적 라우팅 운영 가이드
