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
| API 엔드포인트 | 해당 없음 — libs 모듈. 소비자: `ClientController` (`POST /api/v1/clients`, `GET /api/v1/clients`, `GET /api/v1/clients/{clientId}`, `PUT /api/v1/clients/{clientId}`, `DELETE /api/v1/clients/{clientId}`), `AdminClientController` (`/api/v1/admin/clients`), `AdminRouteController` (`/api/v1/admin/routes`) |

---

## 비즈니스 규칙

### ServiceClient 도메인

- `grantType`이 null이면 서비스 계층에서 `CLIENT_CREDENTIALS`로 디폴트 처리한다. 컨트롤러는 null을 그대로 전달한다.
- `authorization_code` 클라이언트는 `clientSecret`을 발급하지 않는다. Basic Auth가 필요한 redirectUri 관리 endpoint에 접근할 수 없다.
- 셀프 등록(`selfRegister`) 시 `clientSecret`을 발급하며, BCrypt(cost=12) 해시를 `service_client.client_secret_hash` 컬럼에 저장한다. SAS `oauth2_registered_client`에는 저장하지 않는다. 원본 secret은 등록 응답에서 1회만 반환한다. 어드민 등록(`register`)은 secret을 발급하지 않으며 `owner_id`·`client_secret_hash` 모두 null이다.
- `apiKeyHash` 필드는 항상 null이다. 향후 API Key 채널 도입 시 부활 예정.
- `GrantType.fromString`은 null 입력 시 null을 반환한다. 알 수 없는 비-null 값이면 `IllegalArgumentException`을 throw하며, `AdminClientController`가 catch하여 `UnsupportedGrantTypeException`으로 변환한다.
- `ClientRedirectUriService.extractAllowedOrigins`는 모든 등록 클라이언트의 redirectUri에서 scheme+host+port 기준으로 CORS 허용 오리진을 추출한다. Gateway의 `DynamicCorsConfigurationSource`가 이 메서드를 호출한다.

### ServiceRoute 도메인 (동적 라우팅)

- 라우트 등록 경로는 두 가지다.
  - **어드민 등록** (`POST /api/v1/admin/routes`): `ManageRouteService`가 오케스트레이션한다. 유스케이스 인터페이스는 `ManageRouteUseCase`. `owner_id=NULL`로 저장.
  - **회원 셀프 등록** (`POST /api/v1/clients`에 흡수): `RegisterOAuthClientService.selfRegister`가 클라이언트 등록 흐름 내에서 라우트를 원자적으로 생성한다. `owner_id=memberId`로 저장. 요청에 `pathPrefix`와 `upstreamUrl`이 **둘 다 non-blank**일 때만 라우트 생성 분기에 진입한다.
- 셀프 등록 시 라우트 검증 순서: ① `RouteNamespaceExtractor`로 `/api/{namespace}` 포맷 확인 → ② `serviceRouteRepository.findNamespaceOwner(namespace)`로 타 owner 선점 확인 → ③ `routeValidator.validateUpstreamUrl` SSRF 검증 → ④ `routeValidator.validatePathPrefix` 보호경로/중복 검증. 검증 실패 시 클라이언트도 롤백된다 (동일 `@Transactional` 경계).
- 셀프 등록: 1 클라이언트 = 최대 1 라우트. 동일 클라이언트에 두 번째 라우트 등록 엔드포인트는 없다.
- `service_route.owner_id`: 셀프 등록 라우트는 `memberId`, 어드민 등록 라우트는 `NULL` (V11 마이그레이션). FK 제약 없음 — `service_client.owner_id`(V7) 패턴과 일관성 유지.
- 등록·수정 시 `upstreamUrl` SSRF 검증: 허용 스킴(`http`/`https`), private IP 차단, 빈 호스트 거부.
- 셀프 등록 `pathPrefix`는 `/api/{namespace}/...` 형태여야 한다 (`RouteNamespaceInvalidException` 400 `ROUTE_NAMESPACE_INVALID`). 두 번째 세그먼트가 네임스페이스.
- `pathPrefix`가 보호 경로 패턴과 일치하면 `RouteProtectedException`(403 `ROUTE_PROTECTED`)을 던진다. 판정은 `ProtectedPathPolicy` 포트로 추상화하며, 보호 경로 값은 배포 환경에 종속되므로 소비자 앱(auth-api)이 `ProtectedPathPolicyImpl`로 제공한다.
- `pathPrefix` 중복은 DB UNIQUE 제약(`uq_service_route_path_prefix`) 사전 검증으로 `RoutePathConflictException`(409 `ROUTE_PATH_CONFLICT`)을 먼저 던진다.
- 라우트 저장 성공 후 `GatewayRefreshClient.triggerRefresh()`로 api-gateway에 refresh를 전파한다. 셀프 등록 시에는 `TransactionSynchronizationManager.afterCommit` 콜백으로 커밋 후 호출한다. refresh 실패 시 라우트는 DB에 유지되며 경고 로그만 남긴다 (최종 일관성).
- `registered_client_id`는 V9 마이그레이션 이후 nullable이다. 라우트와 OAuth 클라이언트는 독립적으로 관리된다. 단, 셀프 등록 라우트부터는 `registered_client_id`를 채워서 저장하며(백필 전략 B), 셀프 관리 API(`ManageOwnClientService`)는 이 필드를 기준으로 클라이언트↔라우트를 연관한다(`owner_id`는 회원의 여러 클라이언트 라우트가 섞여 식별 불가라 사용하지 않음).
- `listEnabledRoutes()`는 `enabled=true`인 라우트만 반환하며, api-gateway 초기 로드 엔드포인트(`InternalRouteController`)가 호출한다.

### 셀프 클라이언트 관리 (`ManageOwnClientService`)

- 목록·단건 조회(`listMyClients`, `getMyClient`)는 `@Transactional(readOnly = true)`, 수정·삭제(`updateMyClient`, `deleteMyClient`)는 단일 `@Transactional` 경계 내 처리.
- 소유권 검증은 `serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId)`로 수행한다. empty이면 `InvalidClientException`(404 `CLIENT_NOT_FOUND`)을 던져 존재 자체를 은닉한다.
- 목록 조회 시 `serviceRouteRepository.findByRegisteredClientIdIn`으로 IN-query 1회 처리하여 N+1을 방지한다.
- ⚠️ **네임스페이스 불변**: PUT 수정 시 기존 라우트가 있고 요청 `pathPrefix`의 네임스페이스(두 번째 세그먼트)가 다르면 `RouteNamespaceChangeException`(400 `ROUTE_NAMESPACE_CHANGE_DENIED`)을 던진다. 다른 네임스페이스로 이동하려면 기존 클라이언트 삭제 후 재등록해야 한다.
- ⚠️ **DELETE 삭제 순서**: ① 소유권 검증(실패 시 404 `CLIENT_NOT_FOUND`) → ② 라우트 존재 확인 + 있으면 `service_route` 삭제 + afterCommit refresh 등록 → ③ `service_client` 삭제 → ④ SAS `oauth2_registered_client` 삭제(`SasClientRegistrarAdapter.unregisterClient`가 `JdbcTemplate`으로 직접 DELETE — `RegisteredClientRepository`에 delete 인터페이스 없음, SAS 1.x 의존성).
- 라우트 변동(신규 생성·수정·삭제) 시 `TransactionSynchronizationManager.afterCommit`으로 게이트웨이 refresh를 등록한다. refresh 실패는 경고 로그만 남기고 진행한다.

### AutoConfiguration 스캔 범위

`ServiceClientAutoConfiguration`이 `@EnableJpaRepositories` / `@EntityScan`으로 `com.econo.auth.client.persistence.repository`와 `com.econo.auth.client.persistence.entity`를 직접 스캔한다. 다른 AutoConfiguration에서 이 패키지를 중복 선언하면 충돌이 발생한다.

---

## 코드 진입점

| 구분 | 경로 |
|------|------|
| OAuth 클라이언트 도메인 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceClient.java` |
| 라우트 도메인 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceRoute.java` |
| 클라이언트+라우트 셀프 등록 유스케이스 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientUseCase.java` |
| 클라이언트+라우트 셀프 등록 서비스 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java` |
| 셀프 클라이언트 관리 유스케이스 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ManageOwnClientUseCase.java` |
| 셀프 클라이언트 관리 서비스 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageOwnClientService.java` |
| 라우트 어드민 CRUD 유스케이스 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ManageRouteUseCase.java` |
| 라우트 어드민 CRUD 서비스 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageRouteService.java` |
| 라우트 아웃바운드 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceRouteRepository.java` |
| SAS redirectUri 아웃바운드 포트 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/SasRedirectUriManager.java` |
| 네임스페이스 추출·검증 | `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RouteNamespaceExtractor.java` |
| 라우트 검증 (SSRF·보호경로·중복) | `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RouteValidator.java` |
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
| `ClientLimitExceededException` | 422 | CLIENT_LIMIT_EXCEEDED | 회원당 최대 5개 등록 한도 초과 (셀프 등록 전용) |

### ServiceRoute 도메인 (동적 라우팅)

| 예외 클래스 | HTTP 매핑 | 에러 코드 | 발생 조건 |
|------------|-----------|-----------|-----------|
| `RouteNotFoundException` | 404 | ROUTE_NOT_FOUND | routeId 미존재 |
| `RoutePathConflictException` | 409 | ROUTE_PATH_CONFLICT | pathPrefix 중복 |
| `RouteUpstreamInvalidException` | 400 | ROUTE_UPSTREAM_INVALID | SSRF 검증 실패 |
| `RouteProtectedException` | 403 | ROUTE_PROTECTED | 보호 경로 패턴 가로채기·삭제 시도 |
| `RouteNamespaceInvalidException` | 400 | ROUTE_NAMESPACE_INVALID | pathPrefix가 `/api/{namespace}` 형태가 아님 (셀프 등록 전용) |
| `RouteNamespaceTakenException` | 403 | ROUTE_NAMESPACE_TAKEN | 네임스페이스를 다른 ownerId 회원이 선점 (셀프 등록 전용) |
| `RouteNamespaceChangeException` | 400 | ROUTE_NAMESPACE_CHANGE_DENIED | PUT 수정 시 pathPrefix의 네임스페이스(두 번째 세그먼트)가 기존 라우트와 다름 |

> 표의 HTTP 매핑은 `GlobalExceptionHandler`(`services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`)가 명시적 `ResponseEntity`로 반환하는 실제 상태 코드다.

---

## 관련 모듈

| 모듈 | Gradle path | 관계 |
|------|-------------|------|
| common-infra | `:services:libs:common-infra` | JpaAuditingConfig 공유 (`@EnableJpaAuditing` AutoConfiguration 선언 위치) |
| auth-api | `:services:apis:auth-api` | 소비자 — `AdminClientController`, `AdminRouteController`, `InternalRouteController`, `GlobalExceptionHandler` |
