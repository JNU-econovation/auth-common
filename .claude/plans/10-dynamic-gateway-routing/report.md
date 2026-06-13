# 10-dynamic-gateway-routing - report

## 메타
- **작업명**: 10-dynamic-gateway-routing
- **작성일**: 2026-06-14
- **브랜치 / 워크트리**: `feat/dynamic-gateway-routing` (`~/worktrees/auth-common-dynamic-gateway-routing`, 최신 main 위 rebase)
- **plan 문서**:
  - todo.md
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

api-gateway가 정적 보호 라우트(auth-api 핵심 경로)와 **동적 서비스 라우트**를 공존시키도록 재도입했다. auth-api가 `service_route` DB와 라우트 CRUD를 소유하고, 게이트웨이는 기동 시 REST로 라우트를 로드한 뒤 변경 시 auth-api가 게이트웨이 내부 refresh 엔드포인트를 호출해 `RefreshRoutesEvent`로 즉시 반영한다(옵션 B). auth-api 핵심 경로는 정적 보호 라우트로 고정하고 동적 등록을 차단한다. ADR-0005(정적 라우팅)를 supersede한다.

## 진행 결과

### 1. test
- 작성된 테스트 클래스: 7개 (+ 테스트 지원 `TestServiceClientApplication`)
  - service-client: `ServiceRouteTest`, `ManageRouteServiceTest`, `ServiceRouteRepositoryAdapterTest`(Testcontainers)
  - auth-api: `AdminRouteControllerTest`(@WebMvcTest), `ProtectedPathPolicyImplTest`
  - api-gateway: `DynamicRouteDefinitionRepositoryTest`, `RouteRefreshHandlerTest`
- Red 확인 후 구현으로 Green 전환.

### 2. implementation
- service-client(lib): `ServiceRoute` 도메인, `ManageRouteUseCase`/`ManageRouteService`, 포트(`ServiceRouteRepository`·`GatewayRefreshClient`·`ProtectedPathPolicy`), JPA 엔티티/리포지토리/어댑터, Route 예외 4종.
- auth-api: `AdminRouteController`(@PassportAuth ADMIN/SUPER_ADMIN)·`InternalRouteController`, Route DTO 4종, `GatewayRefreshClientImpl`·`RouteBootstrapService`·`GatewayClientConfig`·`ProtectedPathPolicyImpl`, `ApplicationServiceConfig`·`GlobalExceptionHandler` 갱신.
- api-gateway: `DynamicRouteConfig`·`DynamicRouteDefinitionRepository`·`AuthApiRouteClient`·`RouteRefreshHandler`·`InternalRouteRefreshRouter`, `GatewayRoutingConfig`(정적 보호 라우트 @Order(1))·`application.yml`.
- DB: 루트 `db/migration` V9(FK 디커플/nullable) + V10(enabled 인덱스).
- 결과: `service-client`·`auth-api`·`api-gateway` 3모듈 테스트 BUILD SUCCESSFUL (Testcontainers Docker 사용).

### 3. code-review
- 반영 9 / 참고 5.
- 반영(요지): SSRF anyLocal(0.0.0.0/::) 차단, `X-Internal-Secret` 상수시간 비교(`MessageDigest.isEqual`), `/api/v1/internal/**` 보호경로 추가, 게이트웨이 기동 시 초기 로드(`ApplicationReadyEvent`), update 시 기존 보호경로 차단, StripPrefix 미적용으로 통일, enabled DB 레벨 필터(V10 인덱스), `ProtectedPathRegistry` `@Component`→`@Bean`, 어댑터 테스트 Flyway 실경로 적용.
- 재검증: Green 유지.

### 4. docs
- 신규 `docs/adr/0016-dynamic-gateway-routing-reintroduction.md`(ADR-0005 supersede), `docs/adr/0005`에 superseded 표기.
- `docs/DYNAMIC_ROUTING.md`·`docs/ARCHITECTURE.md` 갱신, 모듈 README 3종, `register-service` 스킬, `CLAUDE.md` ADR 목록·자동참조 갱신.

### 5. doc-review
- 반영 7 / 참고 4.
- 반영(요지): 존재하지 않는 SSRF env 서술 제거 + 실제 정책 기술, internal 경로 접근 설명 정정, ADR 0014 중복 파일명 병기, 초기로드 흐름 호출계층 정정, register-service SKILL API 스펙 정정, ARCHITECTURE 결번 처리, 자동참조 표 ADR-0016 반영.

## 변경 요약

### 커밋 (모두 `feat/dynamic-gateway-routing`)
- `8e565e0` docs(plan): plan 4종을 ProtectedPathPolicy 포트화·ADR-0016으로 갱신
- `432cf5a` feat(db): service_route 클라이언트 디커플 및 enabled 인덱스 (V9/V10)
- `818b563` feat(service-client): ServiceRoute 도메인 및 라우트 관리 유스케이스
- `4eaa6bd` feat(auth-api): 라우트 CRUD API 및 게이트웨이 refresh 연동
- `8481649` feat(api-gateway): 동적 라우트 로딩 및 내부 refresh 엔드포인트
- `8c253de` docs: 동적 라우팅 문서화 및 ADR-0016 추가

### 신규 파일 (요약)
- service-client: 도메인 1, 유스케이스/서비스 2, 포트 3, persistence 3, 예외 4, 테스트 3(+지원 1)
- auth-api: 컨트롤러 2, DTO 4, 서비스/구현 4(`GatewayRefreshClientImpl`·`RouteBootstrapService`·`ProtectedPathPolicyImpl`·`GatewayClientConfig`), 테스트 2
- api-gateway: config 3, presentation 2, 테스트 2
- db: V9, V10
- docs: ADR-0016

### 수정 파일 (요약)
- auth-api: `ApplicationServiceConfig`, `GlobalExceptionHandler`
- api-gateway: `GatewayRoutingConfig`, `application.yml`
- service-client: `ServiceClientAutoConfiguration`, `build.gradle.kts`
- docs/README/SKILL/CLAUDE.md

## plan과의 차이

1. **보호 경로 포트화** — plan은 service-client `config/ProtectedPathRegistry` 상수 클래스였으나, 보호 경로 값이 배포 환경(게이트웨이 정적 라우트)에 종속되므로 `ProtectedPathPolicy`(포트, service-client) + `ProtectedPathPolicyImpl`(값·매칭, auth-api 소유)로 분리. lib가 앱 전용 경로를 하드코딩하던 결합과 dangling `@link`를 제거. (`GatewayRefreshClient` 패턴과 일치)
2. **StripPrefix 미적용** — plan/초안은 `StripPrefix=1`이었으나, 정적 보호 라우트가 전체 경로를 업스트림에 전달하는 것과 통일하기 위해 동적 라우트도 StripPrefix를 적용하지 않음.
3. **ADR 번호 0015 → 0016** — plan 작성 시 0015를 의도했으나, main 통합 과정에서 0015가 `flyway-container-managed-migration`에 점유되어 0016으로 확정.
4. **enabled 조회 DB 필터** — InternalRouteController가 Java 스트림 필터 대신 `listEnabledRoutes()`/`findAllEnabled()`로 DB 레벨 조회(V10 인덱스 활용).
5. **기동 초기 로드 트리거** — `DynamicRouteConfig`의 `@EventListener(ApplicationReadyEvent)`에서 `reload()` + `RefreshRoutesEvent` 발행(auth-api 미기동 시 빈 캐시로 기동 계속).
6. **RouteBootstrapService seed 미사용** — auth 핵심 경로를 정적 보호 라우트로 유지하기로 결정해 시드 INSERT는 호출하지 않음(클래스는 향후 확장 여지로 보존).
7. **main 통합** — 작업 도중 main이 auth-lib 추출(application 계층 → login lib)·컨트롤러 rename·ADR 번호 정리로 전진하여 워크트리를 rebase하고 `ApplicationServiceConfig`(loginRedirectResolver 빈 이동) 충돌을 해소.

## 다음 단계
- `/git-pr` 로 PR 생성 (base: main)
- 머지 전 최신 main 재확인(필요 시 rebase)
