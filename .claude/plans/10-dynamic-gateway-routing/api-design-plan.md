# dynamic-gateway-routing - api-design

## 메타
- **작업명**: dynamic-gateway-routing
- **문서 타입**: api-design
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

이 문서는 api-gateway 동적 라우팅 재도입을 위한 라우트 CRUD API 명세를 다룬다. 기존 정적 `GatewayRoutingConfig.java` + `application.yml` 이중 선언을 대체하여, 재배포 없이 런타임에 경로 → 업스트림 매핑을 관리하는 REST 엔드포인트 5종과 선택적 수동 갱신 트리거 1종을 설계한다. 프로젝트는 Spring MVC(auth-api) + Spring Cloud Gateway WebFlux(api-gateway) 혼합 스택이며, 인증은 게이트웨이가 주입한 `X-User-Passport` 헤더를 `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`으로 검증하는 Passport 패턴을 따른다.

---

## 본문

### 아키텍처 결정 선택지 (구현 전 반드시 결정)

아래 두 결정이 API가 어느 모듈에 위치하는지를 직접 결정한다. 각 엔드포인트 상세는 권장안(시나리오 1: auth-api 소유)을 기준으로 작성하며, 시나리오 2(api-gateway 소유) 비교 형태를 별도 섹션으로 제시한다.

---

#### 결정 1: 라우트 CRUD API 소유 모듈

| 항목 | 시나리오 1: auth-api 소유 (권장) | 시나리오 2: api-gateway 소유 |
|------|----------------------------------|------------------------------|
| 스택 | Spring MVC (Servlet) | Spring WebFlux (Reactive) |
| `@PassportAuth` / `PassportArgumentResolver` | 재사용 가능 (이미 등록됨) | 사용 불가 — Reactive 스택에서 HandlerMethodArgumentResolver가 동작하지 않음. 별도 `ServerHttpRequest` 기반 Passport 파싱 구현 필요 |
| `GlobalExceptionHandler` (`@RestControllerAdvice`) | 재사용 가능 | 사용 불가 — WebExceptionHandler로 별도 구현 필요 |
| `RefreshRoutesEvent` 발행 | auth-api → api-gateway로 REST 또는 Redis pub/sub으로 신호 전달 필요 | 로컬 `ApplicationEventPublisher.publishEvent(new RefreshRoutesEvent(this))` 직접 발행 가능 |
| DB 접근 | 기존 JPA (`ServiceRouteRepositoryAdapter`) 그대로 사용 | R2DBC 추가 의존성 필요 (`spring-boot-starter-data-r2dbc`, `r2dbc-postgresql`) |
| 헥사고날 구조 정합 | `ManageRouteUseCase` → `ManageRouteService` → `ServiceRouteRepository` 일관 | api-gateway 모듈은 헥사고날 구조 미적용, 별도 패턴 설계 필요 |
| 보안 검증 로직 재사용 | `ManageRouteService`에서 SSRF·보호경로 검증 — auth-api와 동일 JVM에서 실행 | 검증 로직 중복 또는 auth-api REST 의존 추가 |
| **단점** | RefreshRoutesEvent를 원격으로 전달해야 함 (단일 게이트웨이 인스턴스 가정이면 간단한 REST 호출로 해결 가능) | Passport 인증 재구현, 에러 핸들러 재구현, R2DBC 추가, 헥사고날 구조 이탈 |

**권장안: 시나리오 1 (auth-api 소유)**

기존 `@PassportAuth`, `GlobalExceptionHandler`, JPA 인프라를 그대로 재사용할 수 있으며, SSRF·보호경로 검증 로직을 `ManageRouteService`에 집중할 수 있다. RefreshRoutesEvent 원격 전달 비용은 아래 결정 2에서 해소한다.

---

#### 결정 2: api-gateway 동적 라우팅 로딩 방식

| 항목 | 옵션 A: R2DBC 직접 읽기 | 옵션 B: auth-api REST + 게이트웨이 동기화 (권장) |
|------|-------------------------|--------------------------------------------------|
| 설명 | `DynamicRouteDefinitionRepository`가 R2DBC로 `service_route` 테이블 직접 쿼리 | api-gateway가 기동 시 auth-api `GET /api/v1/admin/routes`를 WebClient로 호출하여 초기 로드, 이후 변경 시 auth-api가 api-gateway의 `/api/v1/internal/routes/refresh` 엔드포인트를 호출하여 `RefreshRoutesEvent` 트리거 |
| 추가 의존성 | `spring-boot-starter-data-r2dbc`, `r2dbc-postgresql` — api-gateway 모듈에 추가 | 없음 (WebClient는 이미 Spring Cloud Gateway 내장) |
| 단일 진실 소스 | api-gateway가 DB에 직접 접근 → auth-api와 DB 커넥션 공유 | auth-api만 DB에 접근 → 단일 진실 소스 유지 |
| 다중 인스턴스 | 각 인스턴스가 독립적으로 DB 폴링 가능 (단, RefreshRoutesEvent는 여전히 로컬) | auth-api가 모든 api-gateway 인스턴스에 refresh 신호를 순차 호출하거나, Redis pub/sub으로 브로드캐스트 |
| 복잡도 | api-gateway에 DB 의존성 추가, R2DBC + JPA 이중 연결 관리 | 모듈 경계 명확, 단순 REST 콜백 |
| 초기 부트스트랩 | `RouteDefinitionRepository` 구현이 기동 시 전체 로드 | `@EventListener(ApplicationReadyEvent.class)` WebClient 호출 |

**권장안: 옵션 B (auth-api REST + 게이트웨이 동기화)**

DB 접근을 auth-api로 일원화하여 모듈 경계를 유지한다. api-gateway는 내부 전용 refresh 엔드포인트(`POST /api/v1/internal/routes/refresh`)를 노출하고, auth-api가 라우트 변경 시 이를 호출한다. 단일 인스턴스 환경에서는 이 방식으로 충분하며, 다중 인스턴스가 필요해지면 Redis pub/sub으로 확장한다.

**이하 모든 엔드포인트 상세는 시나리오 1 + 옵션 B 조합을 기준으로 작성한다.**

---

### 엔드포인트 목록

| 메서드 | 경로 | 설명 | 인증 / 권한 | 연관 todo |
|--------|------|------|-------------|-----------|
| POST | `/api/v1/admin/routes` | 동적 라우트 등록 | 필요 (`ADMIN` 또는 `SUPER_ADMIN`) | API 작업 #1 |
| GET | `/api/v1/admin/routes` | 전체 라우트 목록 조회 | 필요 (`ADMIN` 또는 `SUPER_ADMIN`) | API 작업 #2 |
| GET | `/api/v1/admin/routes/{routeId}` | 단건 라우트 조회 | 필요 (`ADMIN` 또는 `SUPER_ADMIN`) | API 작업 #3 |
| PUT | `/api/v1/admin/routes/{routeId}` | 라우트 수정 | 필요 (`ADMIN` 또는 `SUPER_ADMIN`) | API 작업 #4 |
| DELETE | `/api/v1/admin/routes/{routeId}` | 라우트 삭제 | 필요 (`ADMIN` 또는 `SUPER_ADMIN`) | API 작업 #5 |
| POST | `/api/v1/admin/routes/{routeId}/refresh` | 특정 라우트 즉시 갱신 트리거 (선택적) | 필요 (`ADMIN` 또는 `SUPER_ADMIN`) | API 작업 #6 (생략 가능) |
| POST | `/api/v1/internal/routes/refresh` | 게이트웨이 RefreshRoutesEvent 트리거 (내부 전용) | 게이트웨이 내부망 전용, Passport 불필요 | 구현 작업 (api-gateway 모듈) |

> `/api/v1/internal/routes/refresh`는 api-gateway 모듈에 위치하며 auth-api Admin API가 아님. api-gateway의 `GatewayRoutingConfig` 보호 경로에 포함되지 않도록 별도 처리 필요.

---

### 공통 요청 헤더

| 헤더 | 필수 | 설명 |
|------|------|------|
| `X-User-Passport` | 필수 | 게이트웨이가 JWT 검증 후 주입하는 Base64 인코딩 Passport JSON. `PassportArgumentResolver`가 파싱. |
| `Content-Type: application/json` | POST/PUT 바디 있는 경우 필수 | |

> `Authorization: Bearer <token>` 헤더는 게이트웨이 레벨에서 소비되며, auth-api 컨트롤러까지 전달되지 않는다. auth-api는 오직 `X-User-Passport` 헤더만 참조한다.

---

### 에러 응답 공통 포맷

`GlobalExceptionHandler`의 `ApiError` record를 그대로 사용한다.

```json
{
  "errorCode": "ROUTE_NOT_FOUND",
  "message": "라우트를 찾을 수 없습니다.",
  "timestamp": "2026-06-13T10:00:00",
  "fieldErrors": null
}
```

`VALIDATION_FAILED`의 경우 `fieldErrors` 배열이 포함된다.

```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-06-13T10:00:00",
  "fieldErrors": [
    { "field": "upstreamUrl", "message": "공백일 수 없습니다" }
  ]
}
```

---

### 신규 에러 코드 정의

todo `에러 코드 정의` 항목에서 식별된 4종을 기존 에러 체계(도메인 예외 → `GlobalExceptionHandler` 매핑)에 추가한다.

| HTTP 상태 | 에러 코드 | 예외 클래스 | 발생 조건 |
|-----------|-----------|-------------|-----------|
| 404 NOT_FOUND | `ROUTE_NOT_FOUND` | `RouteNotFoundException` | routeId에 해당하는 라우트가 존재하지 않음 |
| 409 CONFLICT | `ROUTE_PATH_CONFLICT` | `RoutePathConflictException` | 등록/수정 시 pathPrefix가 다른 라우트와 중복 |
| 400 BAD_REQUEST | `ROUTE_UPSTREAM_INVALID` | `RouteUpstreamInvalidException` | upstreamUrl SSRF 검증 실패 (비허용 스킴, private IP, 빈 호스트 등) |
| 403 FORBIDDEN | `ROUTE_PROTECTED` | `RouteProtectedException` | 보호 경로(auth-api 핵심 경로) 가로채기 또는 삭제 시도 |

> `ROUTE_PATH_CONFLICT`는 `DataIntegrityViolationException`이 DB UNIQUE 제약에서도 발생할 수 있으나, `ManageRouteService`에서 DB 쓰기 전 사전 검증(`existsByPathPrefix`)으로 `RoutePathConflictException`을 먼저 던져 에러 코드를 명확히 한다. 기존 `DUPLICATE_RESOURCE`(DataIntegrityViolationException → 409)와 중복되지 않도록 라우트 관련 충돌은 서비스 레이어에서 선점한다.

---

### 보호 경로 목록 (pathPrefix 등록/수정/삭제 거부)

아래 경로와 겹치는 pathPrefix 등록 시도는 `RouteProtectedException` (403 `ROUTE_PROTECTED`)로 거부한다. `ProtectedPathRegistry` 상수 또는 `application.yml gateway.protected-paths` 바인딩으로 관리한다.

- `/api/v1/auth/**`
- `/oauth2/**`
- `/.well-known/**`
- `/userinfo`
- `/swagger-ui/**`
- `/swagger-ui.html`
- `/v3/api-docs/**`
- `/v3/api-docs`
- `/actuator/**`
- `/api/v1/admin/**` (admin 경로 자체를 동적 라우트로 덮어쓰기 방지)
- `/api/v1/members/**`
- `/api/v1/clients/**`

> JWKS 엔드포인트(`/oauth2/jwks`)는 `/oauth2/**` 패턴에 포함된다. todo 명세의 "JWKS/issuer는 동적 대상 아님" 요구사항과 일치한다.

---

### upstreamUrl 검증 규칙 (SSRF 방지)

`ManageRouteService`에서 등록·수정 시 적용한다.

1. **허용 스킴**: `http`, `https`만 허용. `file://`, `ftp://` 등 거부.
2. **호스트 필수**: 호스트가 비어있으면 거부.
3. **Private IP 차단 (기본 정책)**: 아래 대역 거부.
   - `10.0.0.0/8`
   - `172.16.0.0/12`
   - `192.168.0.0/16`
   - `127.0.0.0/8` (loopback)
   - `169.254.0.0/16` (link-local)
   - `::1` (IPv6 loopback)
   > 내부 서비스(예: `http://auth-api:8081`)가 Docker 네트워크 hostname으로 등록될 경우 hostname 기반 화이트리스트를 별도로 운영하거나, Private IP 차단을 완화하는 설정(`gateway.ssrf.allow-private-hosts: true`)을 환경변수로 제어할 수 있도록 설계한다. 프로덕션 기본값은 `false`.
4. **경로 없는 베이스 URL 권장**: `upstreamUrl`은 `http://service-host:port` 형태. 경로 suffix는 게이트웨이 StripPrefix 필터로 처리하므로 포함하지 않는 것을 권장하나 강제는 하지 않는다.

---

### 엔드포인트 상세

#### POST /api/v1/admin/routes

- **목적**: 새 동적 라우트를 등록하고 api-gateway에 즉시 반영 트리거.
- **연관 todo**: `[ ] POST /api/v1/admin/routes — 동적 라우트 등록 (ADMIN/SUPER_ADMIN 전용, @PassportAuth(requiredRoles = {ADMIN, SUPER_ADMIN})). 요청 바디: pathPrefix, upstreamUrl, enabled. 응답: routeId, pathPrefix, upstreamUrl, enabled, createdAt (201 Created).`
- **요청 헤더**:
  ```
  X-User-Passport: <Base64 encoded Passport JSON>
  Content-Type: application/json
  ```
- **요청 바디**:
  ```json
  {
    "pathPrefix": "/api/v2/new-service",
    "upstreamUrl": "http://new-service:8080",
    "enabled": true
  }
  ```
  - `pathPrefix`: 문자열, `/`로 시작해야 함, `@NotBlank`. 보호 경로 패턴 검증 대상.
  - `upstreamUrl`: 문자열, `@NotBlank`. SSRF 검증 대상.
  - `enabled`: boolean, `@NotNull`.

- **응답 (성공)**:
  - 상태: `201 Created`
  - 바디:
    ```json
    {
      "routeId": "a316bc69-1234-5678-abcd-ef0123456789",
      "pathPrefix": "/api/v2/new-service",
      "upstreamUrl": "http://new-service:8080",
      "enabled": true,
      "createdAt": "2026-06-13T10:00:00"
    }
    ```

- **응답 (에러)**:
  - `400 BAD_REQUEST` `VALIDATION_FAILED` — `pathPrefix` 또는 `upstreamUrl`이 blank, `enabled`가 null
  - `400 BAD_REQUEST` `ROUTE_UPSTREAM_INVALID` — SSRF 검증 실패 (비허용 스킴, private IP 등)
  - `401 UNAUTHORIZED` `AUTH_UNAUTHORIZED` — `X-User-Passport` 헤더 누락 또는 passport 파싱 실패
  - `403 FORBIDDEN` `FORBIDDEN` — `ADMIN` / `SUPER_ADMIN` 역할 부족
  - `403 FORBIDDEN` `ROUTE_PROTECTED` — `pathPrefix`가 보호 경로 패턴에 해당
  - `409 CONFLICT` `ROUTE_PATH_CONFLICT` — `pathPrefix` 중복 (동일 경로 이미 존재)

- **인증 / 권한**:
  - 필요 여부: 필수
  - 구현: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})` 파라미터 어노테이션
  - 헤더 누락·invalid → `PassportException.unauthorized()` → `GlobalExceptionHandler` → `401 AUTH_UNAUTHORIZED`
  - 역할 부족 → `PassportException.forbidden()` → `GlobalExceptionHandler` → `403 FORBIDDEN`
  - `includeHigherRoles`: 기본값(`false`) 사용. `ADMIN`과 `SUPER_ADMIN`을 명시적으로 열거하므로 계층 상속 불필요.

- **비고**:
  - 등록 성공 후 `ManageRouteService`가 api-gateway `POST /api/v1/internal/routes/refresh`를 WebClient 비동기 호출하여 `RefreshRoutesEvent` 트리거. 게이트웨이 응답 실패 시 라우트 등록은 롤백하지 않고 경고 로그만 남긴다 (최종 일관성 수용).
  - `routeId`는 서버 측에서 UUID로 생성한다.

---

#### GET /api/v1/admin/routes

- **목적**: 등록된 전체 라우트 목록 조회.
- **연관 todo**: `[ ] GET /api/v1/admin/routes — 전체 라우트 목록 조회 (ADMIN/SUPER_ADMIN 전용). 응답: routes[] 배열 (200 OK).`
- **요청 헤더**:
  ```
  X-User-Passport: <Base64 encoded Passport JSON>
  ```
- **요청 파라미터**: 없음 (페이지네이션 미적용 — 라우트 수는 수십 건 이내 가정).

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "routes": [
        {
          "routeId": "a316bc69-1234-5678-abcd-ef0123456789",
          "pathPrefix": "/api/v2/new-service",
          "upstreamUrl": "http://new-service:8080",
          "enabled": true,
          "createdAt": "2026-06-13T10:00:00",
          "updatedAt": "2026-06-13T10:00:00"
        },
        {
          "routeId": "b427cd70-...",
          "pathPrefix": "/api/eeos",
          "upstreamUrl": "http://eeos-server:8080",
          "enabled": true,
          "createdAt": "2026-06-01T09:00:00",
          "updatedAt": "2026-06-01T09:00:00"
        }
      ]
    }
    ```

- **응답 (에러)**:
  - `401 UNAUTHORIZED` `AUTH_UNAUTHORIZED` — `X-User-Passport` 헤더 누락 또는 passport 파싱 실패
  - `403 FORBIDDEN` `FORBIDDEN` — 역할 부족

- **인증 / 권한**:
  - 필요 여부: 필수
  - 구현: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`
  - 추가 조건: 없음 (역할만으로 충분)

- **비고**: 결과는 `createdAt` 오름차순 정렬. 빈 목록인 경우 `"routes": []` 반환 (404 사용 안 함).

---

#### GET /api/v1/admin/routes/{routeId}

- **목적**: 특정 routeId에 해당하는 라우트 단건 조회.
- **연관 todo**: `[ ] GET /api/v1/admin/routes/{routeId} — 단건 조회 (ADMIN/SUPER_ADMIN 전용). 404 ROUTE_NOT_FOUND 포함 (200 OK / 404 Not Found).`
- **요청 헤더**:
  ```
  X-User-Passport: <Base64 encoded Passport JSON>
  ```
- **경로 파라미터**:
  - `routeId`: UUID 문자열 (예: `a316bc69-1234-5678-abcd-ef0123456789`)

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "routeId": "a316bc69-1234-5678-abcd-ef0123456789",
      "pathPrefix": "/api/v2/new-service",
      "upstreamUrl": "http://new-service:8080",
      "enabled": true,
      "createdAt": "2026-06-13T10:00:00",
      "updatedAt": "2026-06-13T10:00:00"
    }
    ```

- **응답 (에러)**:
  - `401 UNAUTHORIZED` `AUTH_UNAUTHORIZED` — 헤더 누락 또는 passport 파싱 실패
  - `403 FORBIDDEN` `FORBIDDEN` — 역할 부족
  - `404 NOT_FOUND` `ROUTE_NOT_FOUND` — routeId에 해당하는 라우트 없음

- **인증 / 권한**:
  - 필요 여부: 필수
  - 구현: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`
  - 추가 조건: 없음

---

#### PUT /api/v1/admin/routes/{routeId}

- **목적**: 기존 라우트의 `pathPrefix`, `upstreamUrl`, `enabled` 변경. 변경 후 게이트웨이 즉시 반영.
- **연관 todo**: `[ ] PUT /api/v1/admin/routes/{routeId} — 라우트 수정 (ADMIN/SUPER_ADMIN 전용). pathPrefix, upstreamUrl, enabled 변경 가능. 보호 경로 및 pathPrefix 충돌 검증 포함 (200 OK).`
- **요청 헤더**:
  ```
  X-User-Passport: <Base64 encoded Passport JSON>
  Content-Type: application/json
  ```
- **경로 파라미터**:
  - `routeId`: UUID 문자열

- **요청 바디** (전체 필드 필수 — partial update 지원하지 않음):
  ```json
  {
    "pathPrefix": "/api/v2/renamed-service",
    "upstreamUrl": "http://renamed-service:8080",
    "enabled": false
  }
  ```
  - `pathPrefix`: `@NotBlank`. 보호 경로 패턴 및 중복 검증 대상 (단, 현재 라우트 자신의 pathPrefix와 동일하면 충돌로 처리하지 않음).
  - `upstreamUrl`: `@NotBlank`. SSRF 검증 대상.
  - `enabled`: `@NotNull`.

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    {
      "routeId": "a316bc69-1234-5678-abcd-ef0123456789",
      "pathPrefix": "/api/v2/renamed-service",
      "upstreamUrl": "http://renamed-service:8080",
      "enabled": false,
      "createdAt": "2026-06-13T10:00:00",
      "updatedAt": "2026-06-13T10:30:00"
    }
    ```

- **응답 (에러)**:
  - `400 BAD_REQUEST` `VALIDATION_FAILED` — 필드 blank 또는 null
  - `400 BAD_REQUEST` `ROUTE_UPSTREAM_INVALID` — SSRF 검증 실패
  - `401 UNAUTHORIZED` `AUTH_UNAUTHORIZED` — 헤더 누락 또는 passport 파싱 실패
  - `403 FORBIDDEN` `FORBIDDEN` — 역할 부족
  - `403 FORBIDDEN` `ROUTE_PROTECTED` — 변경하려는 `pathPrefix`가 보호 경로 패턴에 해당
  - `404 NOT_FOUND` `ROUTE_NOT_FOUND` — routeId에 해당하는 라우트 없음
  - `409 CONFLICT` `ROUTE_PATH_CONFLICT` — 변경하려는 `pathPrefix`가 다른 라우트와 중복

- **인증 / 권한**:
  - 필요 여부: 필수
  - 구현: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`
  - 추가 조건: 없음

- **비고**:
  - `updatedAt`은 `@LastModifiedDate` JPA Auditing 또는 Flyway 트리거로 자동 갱신 (todo DB 작업 항목 참조).
  - 수정 후 POST와 동일한 방식으로 게이트웨이 refresh 트리거.
  - idempotency: 동일 값으로 PUT 재호출 시 정상 200 반환 (변경 없어도 갱신 이벤트 발행).

---

#### DELETE /api/v1/admin/routes/{routeId}

- **목적**: 라우트 삭제. 보호 라우트(auth-api 핵심 경로) 삭제 금지. 삭제 후 게이트웨이 즉시 반영.
- **연관 todo**: `[ ] DELETE /api/v1/admin/routes/{routeId} — 라우트 삭제 (ADMIN/SUPER_ADMIN 전용). 보호 라우트(auth-api 핵심 경로) 삭제 금지 검증 (204 No Content).`
- **요청 헤더**:
  ```
  X-User-Passport: <Base64 encoded Passport JSON>
  ```
- **경로 파라미터**:
  - `routeId`: UUID 문자열

- **응답 (성공)**:
  - 상태: `204 No Content`
  - 바디: 없음

- **응답 (에러)**:
  - `401 UNAUTHORIZED` `AUTH_UNAUTHORIZED` — 헤더 누락 또는 passport 파싱 실패
  - `403 FORBIDDEN` `FORBIDDEN` — 역할 부족
  - `403 FORBIDDEN` `ROUTE_PROTECTED` — 삭제하려는 라우트의 `pathPrefix`가 보호 경로 패턴에 해당 (또는 라우트에 `protected=true` 플래그가 있는 경우)
  - `404 NOT_FOUND` `ROUTE_NOT_FOUND` — routeId에 해당하는 라우트 없음

- **인증 / 권한**:
  - 필요 여부: 필수
  - 구현: `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`
  - 추가 조건: 없음

- **비고**: 삭제 성공 후 게이트웨이 refresh 트리거. idempotency: 이미 삭제된 routeId에 재호출 시 `ROUTE_NOT_FOUND` 반환 (멱등 삭제 미지원 — 404가 더 명확한 피드백).

---

#### POST /api/v1/admin/routes/{routeId}/refresh (선택적)

- **목적**: 특정 라우트의 게이트웨이 즉시 갱신을 수동으로 트리거.
- **연관 todo**: `[ ] POST /api/v1/admin/routes/{routeId}/refresh — 특정 라우트 즉시 갱신 트리거 (선택적, 게이트웨이 RefreshRoutesEvent 수동 발행) — 결정 필요: 자동 이벤트 전파로 충분하면 생략 가능.`

**권장: 생략.** CRUD 각 엔드포인트가 성공 시 자동으로 refresh를 트리거하므로 수동 엔드포인트는 불필요하다. 운영 중 게이트웨이 refresh 실패 복구가 필요한 경우에는 `GET /api/v1/admin/routes` → `PUT /api/v1/admin/routes/{routeId}` 재호출로 동일 효과를 낼 수 있다. 이 엔드포인트는 **구현하지 않는다.**

---

#### POST /api/v1/internal/routes/refresh (api-gateway 모듈, 내부 전용)

- **목적**: auth-api가 라우트 변경 후 api-gateway에 `RefreshRoutesEvent`를 발행하도록 요청하는 내부 전용 엔드포인트. 외부 클라이언트가 직접 호출하는 Public API가 아님.
- **연관 todo**: `[ ] RouteRefreshService 신규 작성 — RefreshRoutesEvent 발행. auth-api에서 라우트 변경 시 이 서비스를 호출하는 엔드포인트 또는 메시지 수신부.`
- **모듈**: api-gateway (`com.econo.auth.gateway.presentation.controller.RouteRefreshController` 또는 유사)
- **요청 헤더**:
  ```
  X-Internal-Secret: <shared secret>
  ```
  > 내부 전용이므로 게이트웨이 `BearerToPassportFilter`의 인증 검증 대상에서 제외(`permitted-paths`에 추가 불가 — 오히려 외부 노출 방지를 위해 게이트웨이 라우팅 테이블에 이 경로를 추가하지 않아야 한다). auth-api → api-gateway 직접 내부 통신(Docker 네트워크)에서만 호출되므로 shared secret 또는 IP 허용 리스트 수준의 보호를 적용한다.

- **요청 바디**: 없음

- **응답 (성공)**:
  - 상태: `200 OK`
  - 바디:
    ```json
    { "refreshed": true }
    ```

- **응답 (에러)**:
  - `403 FORBIDDEN` — shared secret 불일치

- **인증 / 권한**:
  - 필요 여부: `X-Internal-Secret` 헤더 검증 (Passport 기반 아님)
  - 필요 역할: 없음 (내부 서비스 간 신뢰 모델)
  - 추가 조건: api-gateway가 수신하는 내부망 포트를 외부에 노출하지 않는 것으로 1차 보호.

---

### 시나리오 2 (api-gateway 소유) 비교 명세 요약

권장안을 채택하지 않는 경우를 대비한 비교 정보. 시나리오 2에서는 모든 CRUD 엔드포인트가 api-gateway 모듈에 위치한다.

| 항목 | 시나리오 1 (권장) | 시나리오 2 |
|------|-------------------|------------|
| 경로 형태 | `/api/v1/admin/routes/**` @ auth-api | 동일 경로, api-gateway 모듈에서 처리 |
| 인증 구현 | `@PassportAuth(requiredRoles = {ADMIN, SUPER_ADMIN})` | `ServerHttpRequest`에서 `X-User-Passport` 수동 파싱 + 역할 검증 (커스텀 구현) |
| 에러 응답 | `GlobalExceptionHandler` 재사용 | `WebExceptionHandler` 커스텀 구현 |
| RefreshRoutesEvent | auth-api → api-gateway REST 호출 | 로컬 `ApplicationEventPublisher` 직접 발행 |
| DB 접근 | JPA (`ServiceRouteRepositoryAdapter`) | R2DBC 추가 (옵션 A) 또는 auth-api REST (옵션 B와 동일) |
| 권장 여부 | **권장** | 미권장 (인증 재구현 비용, 헥사고날 구조 이탈) |

---

## 체크리스트
- [x] todo의 모든 API 작업이 엔드포인트로 명세됨 (6종 중 refresh 선택적 포함, 생략 권장 명시)
- [x] 각 엔드포인트의 인증/권한이 명시됨 (`@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})`, `includeHigherRoles=false` 기본값 확인)
- [x] 모든 에러 케이스가 기존 에러 체계로 매핑됨 (신규 4종 예외 클래스 + `GlobalExceptionHandler` 매핑 명시)
- [x] 요청·응답 스키마가 실제 JSON 바디 예시로 작성됨
- [x] 프로젝트 표준 헤더(`X-User-Passport`)가 모든 엔드포인트에 명시됨
- [x] 아키텍처 결정 선택지 2종(API 소유 모듈, 동적 로딩 방식)이 장단점+권장안으로 제시됨
- [x] SSRF 방지 규칙이 API 레벨에서 명시됨
- [x] 보호 경로 목록이 명시됨
- [x] 내부 전용 refresh 엔드포인트가 별도 표기됨 (Public API와 혼동 방지)

---

## 참고
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminClientController.java` — 기존 Admin 컨트롤러 패턴 참조
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 에러 체계 및 `ApiError` record
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — 현재 정적 라우팅 구성, 보호 경로 목록 도출 기준
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/services/apis/api-gateway/src/main/resources/application.yml` — `gateway.permitted-paths` 현황
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/db/migration/V4__create_service_client_and_route.sql` — `service_route` 테이블 스키마 (FK 제약 확인)
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/adr/0005-static-yaml-routing-over-dynamic.md` — ADR-0015에서 supersede될 대상
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/ARCHITECTURE.md` — 에러 코드 체계, 모듈 의존성
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/CONVENTION.md` — 네이밍·예외 처리 컨벤션
- Spring Cloud Gateway `RefreshRoutesEvent` 공식 문서: https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway/retrieving-information-about-a-particular-route.html
