# dynamic-gateway-routing - todo

## 메타
- **작업명**: dynamic-gateway-routing
- **문서 타입**: todo
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요
현재 api-gateway 라우팅은 `GatewayRoutingConfig.java`의 `RouteLocator` + `application.yml` routes 이중 정적 선언 방식으로, 새 서비스 추가 시 코드 수정·재배포가 필수다. `service_route` 테이블은 이미 DB에 존재하나 게이트웨이가 읽지 않는다. 본 작업은 경로→업스트림 매핑을 런타임 동적 라우팅(재배포 없이 즉시 반영, 이벤트 기반)으로 전환하며, auth-api도 동적 관리 업스트림으로 포함시키고 SSRF 방지·보호 경로 불변 보장을 포함하는 보안 정합을 충족한다. ADR-0005(정적 YAML 라우팅 채택)를 supersede하는 ADR-0015를 작성한다.

---

## 본문

### API 작업

> 결정 필요: 라우트 CRUD API를 **auth-api**에 둘지 **api-gateway**에 둘지 미결. auth-api는 Servlet(Spring MVC), api-gateway는 Reactive(WebFlux) — 스택 경계가 갈린다. 아래 항목은 auth-api 소유 기준으로 작성하되 결정 후 수정한다.

- [ ] `POST /api/v1/admin/routes` — 동적 라우트 등록 (ADMIN/SUPER_ADMIN 전용, `@PassportAuth(requiredRoles = {ADMIN, SUPER_ADMIN})`). 요청 바디: `pathPrefix`, `upstreamUrl`, `enabled`. 응답: `routeId`, `pathPrefix`, `upstreamUrl`, `enabled`, `createdAt` (201 Created).
- [ ] `GET /api/v1/admin/routes` — 전체 라우트 목록 조회 (ADMIN/SUPER_ADMIN 전용). 응답: `routes[]` 배열 (200 OK).
- [ ] `GET /api/v1/admin/routes/{routeId}` — 단건 조회 (ADMIN/SUPER_ADMIN 전용). 404 `ROUTE_NOT_FOUND` 포함 (200 OK / 404 Not Found).
- [ ] `PUT /api/v1/admin/routes/{routeId}` — 라우트 수정 (ADMIN/SUPER_ADMIN 전용). `pathPrefix`, `upstreamUrl`, `enabled` 변경 가능. 보호 경로 및 pathPrefix 충돌 검증 포함 (200 OK).
- [ ] `DELETE /api/v1/admin/routes/{routeId}` — 라우트 삭제 (ADMIN/SUPER_ADMIN 전용). 보호 라우트(auth-api 핵심 경로) 삭제 금지 검증 (204 No Content).
- [ ] `POST /api/v1/admin/routes/{routeId}/refresh` — 특정 라우트 즉시 갱신 트리거 (선택적, 게이트웨이 `RefreshRoutesEvent` 수동 발행) — 결정 필요: 자동 이벤트 전파로 충분하면 생략 가능.
- [ ] 에러 코드 정의: `ROUTE_NOT_FOUND`(404), `ROUTE_PATH_CONFLICT`(409), `ROUTE_UPSTREAM_INVALID`(400, SSRF 방지 검증 실패), `ROUTE_PROTECTED`(403, 보호 경로 가로채기·삭제 시도).

---

### 구현 작업

#### service-client 라이브러리 (도메인·유스케이스 계층)

- [ ] `ServiceRoute` 도메인 객체 신규 작성 — `com.econo.auth.client.application.domain.ServiceRoute` (record 또는 불변 클래스). 필드: `routeId`(String UUID), `pathPrefix`(String nullable), `upstreamUrl`(String), `enabled`(boolean). ARCHITECTURE.md에 이미 `ServiceRoute` record로 명시되어 있으나 실제 파일이 없으므로 생성 필요.
- [ ] `ServiceRouteRepository` 아웃바운드 포트 신규 작성 — `com.econo.auth.client.application.repository.ServiceRouteRepository`. 메서드: `save`, `findAll`, `findById`, `deleteById`, `existsByPathPrefix`, `findAllEnabled`.
- [ ] `ServiceRouteJpaEntity` JPA 엔티티 작성 — `com.econo.auth.client.persistence.entity.ServiceRouteJpaEntity`. `service_route` 테이블(V4 마이그레이션 기준) 매핑. `@Table(name = "service_route")`, `@EntityListeners(AuditingEntityListener.class)`.
- [ ] `ServiceRouteJpaRepository` Spring Data JPA 인터페이스 작성 — `findAllByEnabled(boolean enabled)` 포함.
- [ ] `ServiceRouteRepositoryAdapter` 포트 구현체 작성 — `ServiceRouteRepository` 구현, `ServiceRouteJpaRepository` 위임.
- [ ] `ManageRouteUseCase` 인바운드 포트(또는 command record 묶음) 신규 작성 — `createRoute`, `updateRoute`, `deleteRoute`, `listRoutes`, `getRoute` 시그니처 정의.
- [ ] `ManageRouteService` 유스케이스 구현체 작성 — `ManageRouteUseCase` 구현. upstreamUrl 스킴/호스트 SSRF 검증(허용 스킴: `http`, `https`; private IP 차단 또는 화이트리스트), pathPrefix 중복·충돌 검증, 보호 경로 불변 검증 로직 포함.
- [ ] 보호 경로 목록 상수/설정 클래스 작성 — `ProtectedPathRegistry` 또는 `application.yml` `gateway.protected-paths` 바인딩. 초기값: `/api/v1/auth/**`, `/oauth2/**`, `/.well-known/**`, `/userinfo`, `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/**`.
- [ ] 예외 클래스 추가 — `RouteNotFoundException`(404), `RoutePathConflictException`(409), `RouteUpstreamInvalidException`(400), `RouteProtectedException`(403). `GlobalExceptionHandler`에 매핑 추가.
- [ ] `ServiceClientAutoConfiguration` 수정 — `ServiceRouteJpaEntity`, `ServiceRouteJpaRepository` 스캔 대상 패키지 포함 확인 (V4 테이블은 이미 존재하나 AutoConfiguration이 repository를 스캔하는지 확인 필요).

#### auth-api 웹 어댑터 계층

- [ ] `AdminRouteController` 신규 작성 — `com.econo.auth.api.presentation.controller.AdminRouteController`. CRUD 엔드포인트 5종(위 API 작업 기준) 구현. `@PassportAuth(requiredRoles = {"ADMIN", "SUPER_ADMIN"})` 적용. 라우트 등록 후 게이트웨이에 갱신 이벤트 전파 트리거(결정된 방식에 따라 구현).
- [ ] `AdminRouteController` 요청/응답 DTO 작성 — `CreateRouteRequest`, `UpdateRouteRequest`, `RouteResponse` (record). Bean Validation 어노테이션(`@NotBlank`, `@NotNull`) 포함.
- [ ] `ApplicationServiceConfig` 수정 — `ManageRouteService` `@Bean` 등록.
- [ ] `GlobalExceptionHandler` 수정 — 신규 Route 예외 4종 매핑 추가.

#### api-gateway 동적 라우팅 계층

> 결정 필요: 게이트웨이가 DB를 직접 읽는 `RouteDefinitionRepository` 구현(옵션 A)으로 할지, auth-api가 변경 이벤트를 게이트웨이로 push하는 구조(옵션 B)로 할지 미결. 아래는 옵션 A 기준으로 작성. reactive vs servlet 경계, 다중 인스턴스 갱신 전파 방식도 함께 결정 필요.

- [ ] `DynamicRouteDefinitionRepository` 신규 작성 — `com.econo.auth.gateway.config.DynamicRouteDefinitionRepository`. `RouteDefinitionRepository` 구현(Reactive). `service_route` 테이블을 R2DBC 또는 WebClient(auth-api REST)로 읽어 `RouteDefinition` 목록 반환. 옵션 A 선택 시 R2DBC 의존성 추가 필요.
- [ ] `RouteRefreshService` 신규 작성 — `com.econo.auth.gateway.config.RouteRefreshService`. `ApplicationEventPublisher`로 `RefreshRoutesEvent` 발행. auth-api에서 라우트 변경 시 이 서비스를 호출하는 엔드포인트 또는 메시지 수신부.
  > 결정 필요: 다중 인스턴스 전파 방식 — (a) Redis pub/sub, (b) Spring Cloud Bus, (c) 단일 인스턴스 가정으로 local event만 사용.
- [ ] `ProtectedRouteFilter` 또는 predicate 신규 작성 — 동적 라우트가 보호 경로(`/api/v1/auth/**`, `/oauth2/**`, `/.well-known/**` 등)와 충돌하는 경우 요청을 가로채지 못하도록 라우트 우선순위(Order) 보장 또는 필터에서 차단.
- [ ] `GatewayRoutingConfig` 수정/분리 — 정적 `RouteLocator` Bean 제거 또는 보호 경로(auth-api 핵심, JWKS 등)만 정적으로 유지하고 나머지는 동적으로 전환. `application.yml`의 `spring.cloud.gateway.routes` 중복 선언 제거.
  > 결정 필요: auth-api 자체 라우트(`/api/v1/auth/**`, `/oauth2/**` 등)를 동적 라우트로 옮길지 정적 보호 라우트로 유지할지. 초기 시드/부트스트랩 전략 포함.
- [ ] `BearerToPassportFilter` — 보호 경로 목록을 `gateway.protected-paths` yml 설정에서 읽는 방식 유지하되, 동적 라우트 추가 시 `permitted-paths`도 동적 관리가 필요한지 검토 후 결정.
- [ ] api-gateway `build.gradle.kts` 수정 — 옵션 A(R2DBC 직접 읽기) 선택 시 `spring-boot-starter-data-r2dbc`, `r2dbc-postgresql` 의존성 추가. 옵션 B(WebClient) 선택 시 불필요.

#### 부트스트랩/시드

- [ ] auth-api 기동 시 시드 라우트 자동 등록 로직 작성 — `ApplicationServiceConfig` 또는 `@EventListener(ApplicationReadyEvent.class)` 에서 `service_route` 테이블이 비어있을 때 auth-api 핵심 경로(`/api/v1/auth/**`, `/oauth2/**`, `/.well-known/**`, `/userinfo`)를 멱등 INSERT. `upstreamUrl`은 `AUTH_API_URI` env 바인딩.
  > 결정 필요: auth-api 자체를 동적 라우트로 옮길지 여부와 연동. 정적 보호 라우트로 유지한다면 시드 불필요.

---

### DB 작업

- [ ] `service_route` 테이블 확인 — V4 마이그레이션으로 이미 존재. 스키마 재검토: `registered_client_id` FK 제약이 라우트 독립 CRUD에 걸림돌이 되는지 확인. 동적 라우팅에서 라우트를 ServiceClient 없이도 등록할 수 있어야 한다면 FK nullable 또는 제거 필요.
- [ ] Flyway 마이그레이션 `V9__decouple_service_route_from_client.sql` 작성 (필요 시) — `service_route.registered_client_id` FK 제약을 DROP하거나 nullable로 변경. 라우트를 클라이언트 독립적으로 CRUD할 수 있도록 정합 유지. (main에 V8(세션 테이블 제거) 점유됨 → V9부터, 인덱스는 V10)
- [ ] `service_route` 인덱스 추가 마이그레이션 — `enabled` 컬럼 인덱스(`idx_service_route_enabled`) 추가. 게이트웨이 기동 시 `findAllByEnabled(true)` 풀스캔 방지. 기존 `uq_service_route_path_prefix` UNIQUE 인덱스 존재 확인.
- [ ] `service_route.updated_at` 자동 갱신 트리거 또는 JPA `@LastModifiedDate` 처리 확인 — V4에 `updated_at` 컬럼은 있으나 자동 갱신 메커니즘 부재. Flyway 트리거 추가 또는 JPA Auditing으로 처리.

---

### 기타 작업

#### ADR 및 문서

- [ ] ADR-0015 작성 — `docs/adr/0015-dynamic-gateway-routing.md`. ADR-0005(`정적 YAML 라우팅 채택`) supersede. 재도입 근거(서비스 확장, 무중단 라우팅 필요), 채택한 아키텍처 선택지(라우트 저장 위치, 이벤트 전파 방식), 폐기된 대안, 결과·트레이드오프 명시.
- [ ] `docs/DYNAMIC_ROUTING.md` 개정 — 현재 문서가 구현 전 기획 상태로 부정확. 실제 구현된 API 경로, 라우트 등록 절차, 보호 경로 목록, 다중 인스턴스 고려사항으로 갱신.
- [ ] `docs/ARCHITECTURE.md` 수정 — `GatewayRoutingConfig` 정적 설명 제거. 동적 라우팅 구조(DynamicRouteDefinitionRepository, RefreshRoutesEvent 흐름) 추가. 모듈 의존성 다이어그램 갱신(api-gateway → DB 직접 접근 여부 포함).
- [ ] `register-service` 스킬(`/.claude/skills/register-service/SKILL.md`) 수정 — Step 2(GatewayRoutingConfig 직접 수정)를 Admin API 호출(`POST /api/v1/admin/routes`)로 대체. 재배포 없이 즉시 반영 절차로 업데이트.

#### 테스트

- [ ] `ManageRouteServiceTest` 단위 테스트 작성 — `@ExtendWith(MockitoExtension.class)`. SSRF 검증(private IP 거부, 허용 스킴), pathPrefix 중복, 보호 경로 가로채기 시도, 정상 등록 시나리오. `@Nested` + `@DisplayName` 한글.
- [ ] `AdminRouteControllerTest` 웹 레이어 테스트 작성 — `@WebMvcTest(AdminRouteController.class)`. ADMIN 역할 정상 응답(201/200/204), ADMIN 미만 역할 403, Passport 헤더 없음 401, 잘못된 upstreamUrl 400, pathPrefix 충돌 409. MockMvc 기반.
- [ ] `DynamicRouteDefinitionRepositoryTest` 또는 통합 테스트 — 게이트웨이가 `service_route` 테이블에서 라우트를 로드하고 `RefreshRoutesEvent` 후 즉시 반영되는지 검증. `@SpringBootTest` 또는 `@DataR2dbcTest`(옵션 A 선택 시).
- [ ] `ServiceRouteRepositoryAdapterTest` JPA 슬라이스 테스트 — `@DataJpaTest` + Testcontainers PostgreSQL. `save`, `findAllEnabled`, `existsByPathPrefix` 검증.

#### 설정 및 환경변수

- [ ] api-gateway `application.yml` 정리 — `spring.cloud.gateway.routes` 정적 선언 제거(동적 전환 후). `gateway.protected-paths` 섹션 신규 추가(보호 경로 목록, `permitted-paths`와 별도 관리).
- [ ] api-gateway 환경변수 정리 — 옵션 A(R2DBC) 선택 시 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` env 바인딩 추가. `AUTH_API_URI`/`EEOS_API_URI` env는 시드 등록 후 제거 또는 부트스트랩 전용으로 유지.
- [ ] CI/CD — api-gateway Docker 이미지 빌드 시 신규 환경변수 주입 확인. `.github/workflows` 배포 스크립트 환경변수 목록 업데이트.

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] "결정 필요" 항목 4개가 명시적으로 표기됨
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java`
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/services/apis/api-gateway/src/main/resources/application.yml`
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/BearerToPassportFilter.java`
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/db/migration/V4__create_service_client_and_route.sql`
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/adr/0005-static-yaml-routing-over-dynamic.md`
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/DYNAMIC_ROUTING.md`
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/docs/ARCHITECTURE.md`
- `/Users/kimjongmin/worktrees/auth-common-dynamic-gateway-routing/.claude/skills/register-service/SKILL.md`
- Spring Cloud Gateway `RouteDefinitionRepository` / `RefreshRoutesEvent` 공식 문서
