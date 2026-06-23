# client-self-management - todo

## 메타
- **작업명**: client-self-management
- **문서 타입**: todo
- **작성일**: 2026-06-22
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

ADR-0018(POST /api/v1/clients 라우트 흡수)에 이어지는 후속 기능. 인증된 에코노 회원이 자기가 등록한 OAuth 클라이언트(+연결 라우트)를 직접 조회·수정·삭제할 수 있게 한다. 목록 조회(GET), 상세 조회(GET {clientId}), 전체 교체 수정(PUT {clientId}), 하드 삭제(DELETE {clientId}) 4개 엔드포인트로 구성된다. 타인 소유 리소스 접근은 404(존재 은닉) 처리하며, PUT 시 네임스페이스 변경 거부·라우트 upsert/삭제·게이트웨이 refresh, DELETE 시 service_client·SAS RegisteredClient·service_route 캐스케이드 삭제가 원자적으로 처리된다.

---

## 본문

### API 작업

- [ ] `GET /api/v1/clients` — 내 클라이언트 목록 엔드포인트 노출. 응답에 각 클라이언트별 연결 라우트 정보(routeId, pathPrefix, upstreamUrl, enabled) 포함. clientSecret 미반환. 200 OK.
- [ ] `GET /api/v1/clients/{clientId}` — 단건 상세 조회 엔드포인트 노출. 연결 라우트 정보 포함. clientSecret 미반환. 200 OK. 본인 소유가 아니면 404 `CLIENT_NOT_FOUND`.
- [ ] `PUT /api/v1/clients/{clientId}` — 전체 표현(full representation) 수정 엔드포인트 노출. 요청 바디를 `SelfRegisterClientRequest`와 동일 구조(clientName, redirectUris 필수 + pathPrefix, upstreamUrl 선택)로 설계. 200 OK. 본인 소유가 아니면 404. 네임스페이스 변경 시도 시 400 `ROUTE_NAMESPACE_CHANGE_DENIED`.
- [ ] `DELETE /api/v1/clients/{clientId}` — 하드 삭제 엔드포인트 노출. 204 No Content. 본인 소유가 아니면 404 `CLIENT_NOT_FOUND`.
- [ ] 위 4개 엔드포인트의 에러 응답 스펙 확정 (문서 작업과 병행): `CLIENT_NOT_FOUND` 404, `ROUTE_NAMESPACE_CHANGE_DENIED` 400, 라우트 관련 기존 에러코드(`ROUTE_NAMESPACE_INVALID`, `ROUTE_UPSTREAM_INVALID`, `ROUTE_PROTECTED`, `ROUTE_PATH_CONFLICT`) PUT 시 재적용.

### 구현 작업

#### service-client 라이브러리 (`services/libs/service-client`)

**아웃바운드 포트 확장 (application/repository)**

- [ ] `ServiceRoute` 도메인 record에 `registeredClientId` 필드(8번째) 추가 + 셀프 등록용 `create(pathPrefix, upstreamUrl, enabled, ownerId, registeredClientId)` 팩토리 추가. 기존 6/7-인자 생성자·어드민용 3-인자 `create`는 `registeredClientId = null` 유지.
- [ ] `ServiceRouteJpaEntity.from()`/`fromWithId()`의 `registeredClientId = null` 고정 제거 → `route.registeredClientId()` 반영, `toDomain()`도 전달.
- [ ] `RegisterOAuthClientService.selfRegister`의 라우트 생성에 `clientId` 전달 → `ServiceRoute.create(pathPrefix, upstreamUrl, true, ownerId, clientId)` (신규 셀프 라우트에 `registered_client_id` 채움).
- [ ] `ServiceClientRepository`에 메서드 추가:
  - `List<ServiceClient> findByOwnerId(Long ownerId)` — 목록 조회용 (회원의 클라이언트들 — service_client.owner_id 기준)
  - `Optional<ServiceClient> findByClientIdAndOwnerId(String clientId, Long ownerId)` — 소유 검증 겸 단건 조회 (404 존재 은닉 핵심)
  - `void deleteByClientId(String clientId)` — service_client 하드 삭제
  - `void updateClientName(String clientId, String newName)` — clientName만 JPQL UPDATE (불변 도메인 save로 인한 PK-less INSERT 방지)
- [ ] `SasClientRegistrar` 포트에 메서드 추가:
  - `void unregisterClient(String clientId)` — SAS `oauth2_registered_client` 하드 삭제 (`RegisteredClientRepository` 표준 인터페이스에 delete 없음 → 커스텀 구현 필요)
  - `void updateClientName(String clientId, String newName)` — SAS 측 clientName 수정
- [ ] `ServiceRouteRepository` 포트에 메서드 추가 (클라이언트↔라우트 연관은 `registered_client_id` 기준 — `owner_id`는 회원의 여러 클라이언트 라우트가 섞여 식별 불가):
  - `Optional<ServiceRoute> findByRegisteredClientId(String registeredClientId)` — 클라이언트별 라우트 단건 조회 (1:1 전제)
  - `List<ServiceRoute> findByRegisteredClientIdIn(List<String> registeredClientIds)` — 목록 조회 N+1 방지용 배치
  - `void deleteByRegisteredClientId(String registeredClientId)` — 클라이언트 삭제·라우트 제거 캐스케이드용

**유스케이스 인터페이스 (application/usecase)**

- [ ] `ManageOwnClientUseCase` 인터페이스 신설. 내부 Command/Result record 정의:
  - `MyClientResult(String clientId, String clientName, Set<String> redirectUris, String routeId, String pathPrefix, String upstreamUrl, Boolean routeEnabled)`
  - `UpdateMyClientCommand(String clientId, Long ownerId, String clientName, Set<String> redirectUris, String pathPrefix, String upstreamUrl)`
  - `DeleteMyClientCommand(String clientId, Long ownerId)`
  - 메서드: `List<MyClientResult> listMyClients(Long ownerId)`, `MyClientResult getMyClient(String clientId, Long ownerId)`, `MyClientResult updateMyClient(UpdateMyClientCommand)`, `void deleteMyClient(DeleteMyClientCommand)`

**유스케이스 서비스 구현 (application/service)**

- [ ] `ManageOwnClientService` 클래스 신설 (`ManageOwnClientUseCase` 구현체). `ManageRouteService` + `RegisterOAuthClientService.selfRegister` 패턴 미러링. `@Transactional` 경계 내 처리, afterCommit refresh 패턴 적용.
- [ ] `listMyClients` 구현: `serviceClientRepository.findByOwnerId` → clientId들 모아 `serviceRouteRepository.findByRegisteredClientIdIn`로 라우트 IN-query 1회(N+1 방지) 매핑 → `MyClientResult` 목록 반환. `@Transactional(readOnly = true)`.
- [ ] `getMyClient` 구현: `serviceClientRepository.findByClientIdAndOwnerId` → empty이면 `InvalidClientException`(재사용, 404 `CLIENT_NOT_FOUND`) → 라우트 조회 후 `MyClientResult` 반환. `@Transactional(readOnly = true)`.
- [ ] `updateMyClient` 구현 (단일 `@Transactional`):
  - 소유권 확인 → empty이면 `InvalidClientException` (404 은닉).
  - clientName 변경 분 감지 → `SasClientRegistrar.updateClientName` + service_client 업데이트.
  - redirectUris 변경 분 감지 → `SasRedirectUriManager.updateRedirectUris` (기존 `replaceRedirectUris` 패턴 활용).
  - 라우트 diff 처리 (네임스페이스 불변 검증 포함):
    - 기존 라우트 있고 요청 라우트 필드 없음 → 라우트 삭제 + afterCommit refresh.
    - 요청 라우트 필드 있고 기존 없음 → 네임스페이스·SSRF·보호경로·중복 검증 후 생성 + afterCommit refresh.
    - 요청 라우트 필드 있고 기존 있음 → 네임스페이스 변경 여부 검사 (`RouteNamespaceChangeException` 400) → 변경 분 update + afterCommit refresh.
    - 기존 없고 요청도 없음 → no-op.
- [ ] `deleteMyClient` 구현 (단일 `@Transactional`):
  - 소유권 확인 → empty이면 `InvalidClientException` (404 은닉).
  - 연결 라우트 존재 시 `serviceRouteRepository.deleteByRegisteredClientId(clientId)` 삭제 (라우트 있었으면 afterCommit refresh 등록).
  - `serviceClientRepository.deleteByClientId(clientId)`.
  - `sasClientRegistrar.unregisterClient(clientId)`.
- [ ] `triggerRefresh()` private 헬퍼 — `ManageRouteService`의 afterCommit `TransactionSynchronization` 패턴 동일 적용.

**예외 클래스 (exception)**

- [ ] `RouteNamespaceChangeException` 신설 (`services/libs/service-client/src/main/java/com/econo/auth/client/exception/`). 정적 팩토리 패턴(CONVENTION.md 준수). 메시지에 기존·신규 네임스페이스 포함.

**JPA 어댑터 (persistence)**

- [ ] `ServiceClientJpaRepository`에 Spring Data 메서드 추가: `List<ServiceClientJpaEntity> findByOwnerId(Long ownerId)`, `Optional<ServiceClientJpaEntity> findByRegisteredClientIdAndOwnerId(String registeredClientId, Long ownerId)`, `@Modifying @Query` 기반 `deleteByRegisteredClientId(String registeredClientId)`, `@Modifying @Query` 기반 `updateClientNameByRegisteredClientId(String clientId, String newName)`.
- [ ] `ServiceClientRepositoryAdapter`에 `findByOwnerId`, `findByClientIdAndOwnerId`, `deleteByClientId`, `updateClientName` 구현 추가.
- [ ] `SasClientRegistrarAdapter`에 `unregisterClient` 구현 추가. `JdbcTemplate`으로 `oauth2_registered_client` 테이블 직접 DELETE (`client_id` 컬럼 기준). SAS `1.x` 기준 테이블명/컬럼명 의존성 주석으로 명시.
- [ ] `SasClientRegistrarAdapter`에 `updateClientName` 구현 추가. 기존 `RegisteredClient`를 `registeredClientRepository.findByClientId`로 조회 → `RegisteredClient.from(existing).clientName(newName).build()` rebuild → `registeredClientRepository.save`로 덮어쓰기.
- [ ] `ServiceRouteJpaRepository`에 `Optional<ServiceRouteJpaEntity> findByRegisteredClientId(String registeredClientId)`, `List<ServiceRouteJpaEntity> findByRegisteredClientIdIn(List<String> registeredClientIds)`, `@Modifying @Query` 기반 `deleteAllByRegisteredClientId(String registeredClientId)` 추가.
- [ ] `ServiceRouteRepositoryAdapter`에 `findByRegisteredClientId`, `findByRegisteredClientIdIn`, `deleteByRegisteredClientId` 구현 추가.

**테스트 (service-client)**

- [ ] `ManageOwnClientServiceTest` 단위 테스트 작성 (Mockito). 시나리오: 목록 조회 정상/빈 목록, 단건 조회 정상, 단건 조회 타인 소유 404, PUT 라우트 생략 시 삭제 + refresh, PUT 라우트 추가 시 생성 + refresh, PUT 네임스페이스 변경 시도 400, PUT 기존 라우트 수정, DELETE 라우트 있음 + refresh, DELETE 라우트 없음, DELETE 타인 소유 404.
- [ ] `ServiceClientRepositoryAdapterTest` 확장 (`@DataJpaTest`, Testcontainers PostgreSQL): `findByOwnerId`, `findByRegisteredClientIdAndOwnerId`, `deleteByRegisteredClientId` 검증.
- [ ] `ServiceRouteRepositoryAdapterTest` 확장: `findByRegisteredClientId`, `findByRegisteredClientIdIn`, `deleteByRegisteredClientId` 검증. + `ServiceRouteJpaEntity.from`/`toDomain`의 `registeredClientId` round-trip 검증.
- [ ] `SasClientRegistrarAdapter` `unregisterClient` 검증 테스트 — SAS 테이블에 실제 저장 후 DELETE 동작 확인.

#### auth-api 애플리케이션 (`services/apis/auth-api`)

**컨트롤러 (presentation/controller)**

- [ ] `ClientController`에 핸들러 메서드 4개 추가:
  - `@GetMapping` (목록): `@PassportAuth Passport passport` → `ManageOwnClientUseCase.listMyClients(passport.getMemberId())` → `MyClientListResponse` 반환.
  - `@GetMapping("/{clientId}")` (상세): `ManageOwnClientUseCase.getMyClient` 호출.
  - `@PutMapping("/{clientId}")` (수정): `@Valid @RequestBody UpdateMyClientRequest` → `ManageOwnClientUseCase.updateMyClient` 호출.
  - `@DeleteMapping("/{clientId}")` (삭제): `ManageOwnClientUseCase.deleteMyClient` → 204 반환.

**DTO (presentation/dto)**

- [ ] `MyClientRouteInfo` 중첩 record/클래스 (또는 내부 record) 작성 — `String routeId, String pathPrefix, String upstreamUrl, Boolean enabled` (null 허용 — 라우트 없는 클라이언트 포함).
- [ ] `MyClientItemResponse` 작성 — `String clientId, String clientName, Set<String> redirectUris, MyClientRouteInfo route`.
- [ ] `MyClientListResponse` 작성 — `List<MyClientItemResponse> clients`.
- [ ] `MyClientDetailResponse` 작성 — `String clientId, String clientName, Set<String> redirectUris, MyClientRouteInfo route`. (`MyClientItemResponse`와 동일 구조이면 통합 가능 — 결정 필요)
- [ ] `UpdateMyClientRequest` 작성 — `SelfRegisterClientRequest`와 동일 필드(clientName `@NotBlank`, redirectUris `@NotNull`, pathPrefix, upstreamUrl) + `@AssertTrue isRouteFields()` 동일 적용.

**OpenAPI 문서**

- [ ] `ClientApiDocs` 인터페이스에 4개 신규 엔드포인트 Swagger `@Operation`, `@ApiResponse` 어노테이션 추가.

**빈 등록 (config)**

- [ ] `ApplicationServiceConfig`에 `ManageOwnClientService` `@Bean` 등록. 필요 의존성: `ServiceClientRepository`, `ServiceRouteRepository`, `SasClientRegistrar`, `SasRedirectUriManager`, `GatewayRefreshClient`, `RouteValidator`, `RouteNamespaceExtractor` 주입. (`SasRedirectUriManager`가 `ClientRedirectUriService`와 공유되므로 빈 충돌 없음 — 확인 필요)

**예외 핸들러 (exception)**

- [ ] `GlobalExceptionHandler`에 `RouteNamespaceChangeException` → 400 `ROUTE_NAMESPACE_CHANGE_DENIED` 핸들러 추가.
- [ ] 소유권 실패(타인 소유) 처리: `InvalidClientException` 재사용 시 기존 핸들러(404 `CLIENT_NOT_FOUND`)가 자동 처리 — 추가 핸들러 불필요. 재사용 여부 결정 확인.

**설정**

- [ ] `SecurityConfig` — `GET/PUT/DELETE /api/v1/clients/**` 경로가 기존 Security 설정에서 올바르게 처리되는지 확인. (Passport 검증은 econo-passport `@PassportAuth`가 담당하므로 별도 URL 패턴 추가 불필요할 가능성 높음 — 확인 필요)

**테스트 (auth-api)**

- [ ] `ClientControllerTest`에 4개 엔드포인트 MockMvc 테스트 추가. 시나리오: 목록 200, 상세 200, 상세 404, PUT 200, PUT 네임스페이스 변경 400, PUT 라우트 검증 실패(SSRF 등) 기존 에러코드, DELETE 204, DELETE 404, `@PassportAuth` 누락 401.

### DB 작업

- [ ] **V13 신규 마이그레이션 작성**: `V13__add_index_service_route_registered_client_id.sql` — `CREATE INDEX idx_service_route_registered_client_id ON service_route (registered_client_id)`. 사유: `findByRegisteredClientId`/`deleteByRegisteredClientId` 조회·삭제 경로. (Flyway 트랜잭션 내 실행이라 `CONCURRENTLY` 미사용 — db-design-plan 참조.)
- [ ] `service_client.registered_client_id` 컬럼에 이미 `UNIQUE` 제약(= 암묵적 인덱스, V4)이 있으므로 `deleteByClientId`·소유권 단건 조회는 별도 인덱스 불필요 — 확인 후 확정.
- [ ] `service_client.owner_id`는 V7에서 `idx_service_client_owner_id` 이미 생성 → `findByOwnerId`(클라이언트 목록) 인덱스 추가 불필요.
- [ ] `service_route.registered_client_id` 백필: 백필 전략 B(신규 등록부터만 채움) 채택. 배포 전 운영 DB에서 `SELECT COUNT(*) FROM service_route WHERE owner_id IS NOT NULL AND registered_client_id IS NULL` 확인 — 0건이면 B 확정, 1건 이상이면 수동 백필/어드민 정리.
- [ ] SAS `oauth2_registered_client` 테이블 직접 삭제 시 테이블명·컬럼명 고정 의존성 확인 (`client_id` VARCHAR 컬럼, `spring-authorization-server 1.x` 기준). 이 의존성을 `SasClientRegistrarAdapter` 주석으로 문서화.

### 기타 작업

**문서 업데이트**

- [ ] `docs/CLIENT_REGISTRATION.md` — "셀프 관리 API" 섹션 추가: 4개 엔드포인트 요청/응답 예시, PUT 라우트 diff 동작 설명, 에러코드 테이블(`CLIENT_NOT_FOUND`, `ROUTE_NAMESPACE_CHANGE_DENIED`, 기존 라우트 에러코드 재사용 목록).
- [ ] `services/libs/service-client/README.md` — "코드 진입점" 테이블에 `ManageOwnClientUseCase`, `ManageOwnClientService`, `RouteNamespaceChangeException` 추가. "에러 코드" 표에 `RouteNamespaceChangeException → 400 ROUTE_NAMESPACE_CHANGE_DENIED` 추가.
- [ ] `services/apis/auth-api/README.md` 존재 여부 확인 후 엔드포인트 목록 갱신.

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고
- `/docs/CLIENT_REGISTRATION.md` — 셀프 등록 운영 가이드 및 에러코드 (이번 작업의 전편)
- `/docs/DYNAMIC_ROUTING.md` — 라우트 관리 API 설계 패턴 (어드민 CRUD afterCommit 패턴)
- `/docs/adr/0018-route-self-registration-via-client.md` — 이번 작업의 선행 결정, "재검토 조건" 항목
- `/docs/adr/0013-passport-member-self-registration.md` — 셀프서비스 모델 근거
- `/services/libs/service-client/README.md` — 포트/어댑터 진입점 목록, 에러 코드 표
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageRouteService.java` — afterCommit refresh + update/delete 패턴 참고
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java` — selfRegister 라우트 흡수·원자성 패턴 참고
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/SasClientRegistrar.java` — 포트 확장 대상
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceClientRepository.java` — 포트 확장 대상
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceRouteRepository.java` — 포트 확장 대상
- `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/SasClientRegistrarAdapter.java` — SAS 삭제 커스텀 구현 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ClientController.java` — 핸들러 추가 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — 빈 등록 패턴 및 추가 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 예외 핸들러 추가 대상
- `db/migration/V12__add_indexes_to_service_route.sql` — 현재 최신 마이그레이션 (V13부터 이어받음)
