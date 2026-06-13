# service-client

OAuth 클라이언트 등록·redirectUri 관리·Gateway 동적 라우팅 도메인 라이브러리.
ServiceClient·ServiceRoute 도메인, 헥사고날 구조(포트/어댑터), Spring Boot AutoConfiguration을 포함한다.

---

## Quick Reference

| 항목 | 값 |
|------|-----|
| 패키지 루트 | `com.econo.auth.client` |
| Gradle 의존 경로 | `implementation(project(":services:libs:service-client"))` |
| 주요 연관 모듈 | `common-infra` (JpaAuditing 공유), `auth-api` (소비자) |
| AutoConfiguration | `com.econo.auth.client.config.ServiceClientAutoConfiguration` |
| API 엔드포인트 | 해당 없음 — libs 모듈. 소비자: `AdminClientController` (`/api/v1/clients`), `AdminRouteController` (`/api/v1/admin/routes`) |

---

## 비즈니스 규칙

### ServiceClient 도메인

- `grantType`이 null이면 서비스 계층에서 `CLIENT_CREDENTIALS`로 디폴트 처리한다. 컨트롤러는 null을 그대로 전달한다.
- `authorization_code` 클라이언트는 `clientSecret`을 발급하지 않는다. Basic Auth가 필요한 redirectUri 관리 endpoint에 접근할 수 없다.
- `clientSecret`(BCrypt 해시)은 SAS `RegisteredClient`에 저장되며 `service_client` 테이블에는 저장되지 않는다. 원본 secret은 등록 응답에서 1회만 반환한다.
- `apiKeyHash` 필드는 항상 null이다. 향후 API Key 채널 도입 시 부활 예정.
- `GrantType.fromString`은 null 입력 시 null을 반환한다. 알 수 없는 비-null 값이면 `IllegalArgumentException`을 throw하며, `AdminClientController`가 catch하여 `UnsupportedGrantTypeException`으로 변환한다.
- `ClientRedirectUriService.extractAllowedOrigins`는 모든 등록 클라이언트의 redirectUri에서 scheme+host+port 기준으로 CORS 허용 오리진을 추출한다. Gateway의 `DynamicCorsConfigurationSource`가 이 메서드를 호출한다.

### ServiceRoute 도메인 (동적 라우팅)

- `ManageRouteService`가 라우트 CRUD를 오케스트레이션한다. 유스케이스 인터페이스는 `ManageRouteUseCase`.
- 등록·수정 시 `upstreamUrl` SSRF 검증: 허용 스킴(`http`/`https`), private IP 차단, 빈 호스트 거부.
- `pathPrefix`가 보호 경로 패턴과 일치하면 `RouteProtectedException`(403 `ROUTE_PROTECTED`)을 던진다. 판정은 `ProtectedPathPolicy` 포트로 추상화하며, 보호 경로 값은 배포 환경에 종속되므로 소비자 앱(auth-api)이 `ProtectedPathPolicyImpl`로 제공한다.
- `pathPrefix` 중복은 DB UNIQUE 제약(`uq_service_route_path_prefix`) 사전 검증으로 `RoutePathConflictException`(409 `ROUTE_PATH_CONFLICT`)을 먼저 던진다.
- 라우트 저장 성공 후 `GatewayRefreshClient.triggerRefresh()`로 api-gateway에 refresh를 전파한다. refresh 실패 시 라우트는 DB에 유지되며 경고 로그만 남긴다 (최종 일관성).
- `registered_client_id`는 V9 마이그레이션 이후 nullable이다. 라우트와 OAuth 클라이언트는 독립적으로 관리된다.
- `listEnabledRoutes()`는 `enabled=true`인 라우트만 반환하며, api-gateway 초기 로드 엔드포인트(`InternalRouteController`)가 호출한다.

### AutoConfiguration 스캔 범위

`ServiceClientAutoConfiguration`이 `@EnableJpaRepositories` / `@EntityScan`으로 `com.econo.auth.client.persistence.repository`와 `com.econo.auth.client.persistence.entity`를 직접 스캔한다. 다른 AutoConfiguration에서 이 패키지를 중복 선언하면 충돌이 발생한다.

---

## 코드 진입점

| 구분 | 경로 |
|------|------|
| OAuth 클라이언트 도메인 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceClient.java` |
| 라우트 도메인 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceRoute.java` |
| 라우트 유스케이스 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ManageRouteUseCase.java` |
| 라우트 유스케이스 구현 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageRouteService.java` |
| 라우트 아웃바운드 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceRouteRepository.java` |
| 보호 경로 판정 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ProtectedPathPolicy.java` (구현체: auth-api `ProtectedPathPolicyImpl`) |
| JPA 어댑터 (라우트) | `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/ServiceRouteRepositoryAdapter.java` |
| AutoConfiguration | `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java` |
| 예외 | `services/libs/service-client/src/main/java/com/econo/auth/client/exception/` |

---

## 에러 코드

> 에러 정의: `services/libs/service-client/src/main/java/com/econo/auth/client/exception/`

### ServiceClient 도메인

| 예외 클래스 | HTTP 매핑 | 에러 코드 | 발생 조건 |
|------------|-----------|-----------|-----------|
| `InvalidClientException` | 404 | CLIENT_NOT_FOUND | clientId 미존재 |
| `RedirectUriRequiredException` | 400 | REDIRECT_URI_REQUIRED | redirectUri 누락·한도 초과·유효하지 않은 URI |
| `UnsupportedGrantTypeException` | 400 | UNSUPPORTED_GRANT_TYPE | 지원하지 않는 grantType |
| `DuplicateClientNameException` | 409 | DUPLICATE_CLIENT_NAME | clientName 중복 |

### ServiceRoute 도메인 (동적 라우팅)

| 예외 클래스 | HTTP 매핑 | 에러 코드 | 발생 조건 |
|------------|-----------|-----------|-----------|
| `RouteNotFoundException` | 404 | ROUTE_NOT_FOUND | routeId 미존재 |
| `RoutePathConflictException` | 409 | ROUTE_PATH_CONFLICT | pathPrefix 중복 |
| `RouteUpstreamInvalidException` | 400 | ROUTE_UPSTREAM_INVALID | SSRF 검증 실패 |
| `RouteProtectedException` | 403 | ROUTE_PROTECTED | 보호 경로 패턴 가로채기·삭제 시도 |

> 표의 HTTP 매핑은 `GlobalExceptionHandler`(`services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`)가 명시적 `ResponseEntity`로 반환하는 실제 상태 코드다.

---

## 관련 모듈

| 모듈 | Gradle path | 관계 |
|------|-------------|------|
| common-infra | `:services:libs:common-infra` | JpaAuditingConfig 공유 (`@EnableJpaAuditing` AutoConfiguration 선언 위치) |
| auth-api | `:services:apis:auth-api` | 소비자 — `AdminClientController`, `AdminRouteController`, `InternalRouteController`, `GlobalExceptionHandler` |
