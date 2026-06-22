# route-self-registration - todo

## 메타
- **작업명**: route-self-registration
- **문서 타입**: todo
- **작성일**: 2026-06-21 (재작성)
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요

기존 설계(별도 `/api/v1/routes` CRUD 5개 엔드포인트)를 폐기하고, **기존 회원 셀프 클라이언트 등록 API(`POST /api/v1/clients`)에 라우트 등록을 흡수**하는 방향으로 전면 재설계한다. 요청 바디에 `pathPrefix` + `upstreamUrl`을 선택적으로 추가하며, 두 필드가 모두 있으면 같은 트랜잭션에서 `service_route`(owner_id = memberId) 1건을 생성하고 게이트웨이를 즉시 갱신한다. 1 클라이언트 = 최대 1 라우트. 라우트 검증 실패 시 요청 전체가 실패하는 원자성을 보장한다. 이전 설계에서 작성된 코드(`RouteController`, `SelfManageRouteUseCase`, `SelfManageRouteService` 등)는 삭제한다.

## 본문

### API 작업

- [ ] `POST /api/v1/clients` 요청/응답 스펙 확장
  - 요청 바디에 선택 필드 추가: `pathPrefix` (String, nullable), `upstreamUrl` (String, nullable)
  - 두 필드가 모두 존재하면 라우트 생성. 하나만 있으면 400 VALIDATION_FAILED (Bean Validation으로 강제)
  - 응답 바디에 라우트 필드 추가: `routeId` (String, nullable), `pathPrefix` (String, nullable), `upstreamUrl` (String, nullable), `enabled` (Boolean, nullable) — 라우트 미생성 시 null
  - 기존 응답 필드 (`clientId`, `clientSecret`)는 그대로 유지
  - 라우트 생성 시 추가 오류 코드: 400 ROUTE_UPSTREAM_INVALID, 400 ROUTE_NAMESPACE_INVALID, 403 ROUTE_NAMESPACE_TAKEN, 409 ROUTE_PATH_CONFLICT
  - `ClientApiDocs` 인터페이스에 위 변경 사항 반영 (기존 `@ApiResponses`에 라우트 관련 응답 코드 추가)
- [ ] `POST /api/v1/admin/routes`, `GET/PUT/DELETE /api/v1/admin/routes/**` — 변경 없이 유지 (어드민 라우트 CRUD 별도 엔드포인트는 그대로)

### 구현 작업

#### 제거 (이전 설계 코드 삭제)

- [ ] `RouteController` 삭제 (`services/apis/auth-api/.../presentation/controller/RouteController.java`)
- [ ] `RouteApiDocs` 삭제 (`services/apis/auth-api/.../presentation/docs/RouteApiDocs.java`)
- [ ] `SelfCreateRouteRequest` 삭제 (`services/apis/auth-api/.../presentation/dto/SelfCreateRouteRequest.java`)
- [ ] `SelfUpdateRouteRequest` 삭제 (`services/apis/auth-api/.../presentation/dto/SelfUpdateRouteRequest.java`)
- [ ] `SelfManageRouteUseCase` 삭제 (`services/libs/service-client/.../application/usecase/SelfManageRouteUseCase.java`)
- [ ] `SelfManageRouteService` 삭제 (`services/libs/service-client/.../application/service/SelfManageRouteService.java`)
- [ ] `RouteAccessDeniedException` 삭제 (`services/libs/service-client/.../exception/RouteAccessDeniedException.java`)
- [ ] `RouteNamespaceImmutableException` 삭제 (`services/libs/service-client/.../exception/RouteNamespaceImmutableException.java`)
- [ ] `RouteQuotaExceededException` 삭제 (`services/libs/service-client/.../exception/RouteQuotaExceededException.java`)
- [ ] `GatewayRoutingConfig`의 `auth-routes` 정적 라우트 항목(`/api/v1/routes/**`) 삭제
- [ ] `ProtectedPathPolicyImpl`의 `/api/v1/routes/**` 보호 경로 항목 삭제
- [ ] `SecurityConfig`의 `.requestMatchers("/api/v1/routes/**").permitAll()` 항목 삭제
- [ ] `GlobalExceptionHandler`의 `RouteAccessDeniedException`, `RouteNamespaceImmutableException`, `RouteQuotaExceededException` 핸들러 삭제 (3개 메서드)
- [ ] `ApplicationServiceConfig`의 `selfManageRouteService` `@Bean` 등록 메서드 삭제
- [ ] `ServiceRouteRepository` 출력 포트에서 `findAllByOwnerId`, `countByOwnerId` 메서드 삭제 (호출처 없어짐) — 단, 다른 곳에서 참조하는지 grep으로 확인 후 제거
  - 확인 대상: `ServiceRouteJpaRepository`, `ServiceRouteRepositoryAdapter`의 대응 메서드도 함께 제거

#### 유지 확인 (변경 없이 재사용)

- [ ] `RouteNamespaceExtractor` — 변경 없이 그대로 사용 (Javadoc의 `SelfManageRouteService` 참조 문구만 수정)
- [ ] `RouteNamespaceInvalidException`, `RouteNamespaceTakenException` — 변경 없이 유지
- [ ] `RouteValidator` (SSRF / 보호경로 / pathPrefix 중복 검증) — 변경 없이 재사용
- [ ] `ServiceRouteRepository.findNamespaceOwner`, `ServiceRouteRepository.existsByPathPrefix` — 변경 없이 유지
- [ ] V11 (`owner_id` 컬럼), V12 (`idx_service_route_owner_id` 인덱스) 마이그레이션 — 변경 없이 유지

#### service-client lib — 유스케이스 확장

- [ ] `RegisterOAuthClientUseCase`에 라우트 포함 셀프 등록 지원을 위한 `SelfRegisterOAuthClientCommand` 확장
  - 기존: `record SelfRegisterOAuthClientCommand(String clientName, Set<String> redirectUris, Long ownerId)`
  - 변경: `pathPrefix` (String, nullable), `upstreamUrl` (String, nullable) 필드 추가
- [ ] `RegisterOAuthClientUseCase`의 `SelfRegisterOAuthClientResult` 확장
  - 기존: `record SelfRegisterOAuthClientResult(String clientId, String clientSecret)`
  - 변경: `routeId` (String, nullable), `pathPrefix` (String, nullable), `upstreamUrl` (String, nullable), `enabled` (Boolean, nullable) 필드 추가 — 라우트 미생성 시 null

#### service-client lib — 서비스 구현 확장

- [ ] `RegisterOAuthClientService.selfRegister` 메서드 확장
  - `command.pathPrefix()`와 `command.upstreamUrl()`이 **둘 다 non-null/non-blank**인 경우에만 라우트 생성 분기 진입
  - 라우트 생성 시 검증 순서 (기존 5단계 클라이언트 검증 완료 후 수행):
    1. `RouteNamespaceExtractor.extract(pathPrefix)` — `RouteNamespaceInvalidException` 400
    2. `serviceRouteRepository.findNamespaceOwner(namespace)` — 타 owner이면 `RouteNamespaceTakenException` 403
    3. `routeValidator.validateUpstreamUrl(upstreamUrl)` — `RouteUpstreamInvalidException` 400
    4. `routeValidator.validatePathPrefix(pathPrefix)` — `RouteProtectedException` 403, `RoutePathConflictException` 409
  - `ServiceRoute.create(pathPrefix, upstreamUrl, enabled=true, ownerId=command.ownerId())` 저장 — 기존 `@Transactional` 경계 내에서 동일 트랜잭션 처리 (원자성 보장)
  - 라우트 저장 후 `GatewayRefreshClient.triggerRefresh()` 호출 — `ManageRouteService`의 afterCommit 패턴 동일하게 적용 (`TransactionSynchronizationManager.registerSynchronization`)
  - `SelfRegisterOAuthClientResult` 반환 시 라우트 필드 포함 (라우트 미생성이면 null 필드)
- [ ] `RegisterOAuthClientService`에 `ServiceRouteRepository`, `GatewayRefreshClient`, `RouteValidator`, `RouteNamespaceExtractor` 의존성 추가
  - `@RequiredArgsConstructor` 기반 생성자 주입 — `ApplicationServiceConfig`에서 빈 와이어링 수정 필요 없음 (`@Service` 자동 스캔이지만 service-client는 AutoConfiguration 자동 스캔 대상이므로 의존성이 자동 주입됨)
  - 단, `RegisterOAuthClientService`가 현재 `@Service` 자동 빈인지 또는 수동 `@Bean`인지 확인 필요 — 현재 `@Service`로 선언되어 있으므로 `ServiceClientAutoConfiguration`의 `@ComponentScan`으로 자동 등록됨. `GatewayRefreshClient` 구현체(`GatewayRefreshClientImpl`)는 `auth-api` 모듈 소속이므로 service-client lib 스캔 밖. `ApplicationServiceConfig`에서 `RegisterOAuthClientService` 빈을 수동 `@Bean`으로 전환하거나, `GatewayRefreshClient` 포트를 `@Autowired(required = false)` 처리하는 방안 중 택일. **모호함 — 결정 필요** (아래 참고)

#### auth-api — DTO 수정

- [ ] `SelfRegisterClientRequest` 확장 — `pathPrefix` (String, nullable), `upstreamUrl` (String, nullable) 필드 추가
  - Bean Validation: 두 필드가 하나만 있는 경우를 `@AssertTrue` 커스텀 검증 또는 `@Schema(nullable = true)` + 서비스 레이어 검증으로 처리 — 어느 계층에서 처리할지 **결정 필요** (아래 참고)
- [ ] `SelfRegisterClientResponse` 확장 — `routeId` (String, nullable), `pathPrefix` (String, nullable), `upstreamUrl` (String, nullable), `enabled` (Boolean, nullable) 필드 추가
  - `@Schema(nullable = true)` 각 필드에 추가

#### auth-api — 컨트롤러 수정

- [ ] `ClientController.registerClient` 수정
  - `SelfRegisterOAuthClientCommand` 생성 시 `request.pathPrefix()`, `request.upstreamUrl()` 추가 전달
  - `SelfRegisterOAuthClientResult`에서 라우트 필드를 꺼내 `SelfRegisterClientResponse` 생성에 반영

#### auth-api — 설정 수정

- [ ] `ApplicationServiceConfig`에서 `SelfManageRouteService` 빈 등록 메서드 제거 (이미 제거 작업 항목에 있음)
- [ ] `RouteNamespaceExtractor` 인스턴스 생성 위치 확인 — `SelfManageRouteService` 내부 `new`에서 사용했으므로, `RegisterOAuthClientService` 주입 방식으로 전환 시 `ApplicationServiceConfig`에서 `@Bean` 등록하거나 서비스 내부 `new` 유지 결정

### DB 작업

- [ ] 해당 없음 — V11(`owner_id` 컬럼), V12(`idx_service_route_owner_id` 인덱스) 마이그레이션은 이전 설계에서 이미 작성·적용 완료 상태. 신규 마이그레이션 없음.
  - 단, V11/V12가 실제로 `db/migration/`에 존재하고 적용되었는지 현재 파일 목록으로 확인 필요

### 기타 작업

#### 제거 테스트

- [ ] `RouteControllerTest` 삭제 (`services/apis/auth-api/.../presentation/controller/RouteControllerTest.java`)
- [ ] `SelfManageRouteServiceTest` 삭제 (`services/libs/service-client/.../application/service/SelfManageRouteServiceTest.java`)

#### 신규/수정 테스트

- [ ] `ClientControllerTest` 수정 (`@WebMvcTest(ClientController.class)`) — 기존 케이스 유지 + 라우트 흡수 케이스 추가
  - 라우트 필드 없는 요청 → 201 Created, 응답 라우트 필드 null
  - 라우트 필드 둘 다 있는 요청 → 201 Created, 응답 라우트 필드 포함
  - `pathPrefix`만 있고 `upstreamUrl` 없는 요청 → 400 (어느 계층 검증인지에 따라 구현 결정 후 작성)
  - 네임스페이스 포맷 위반 → 400 ROUTE_NAMESPACE_INVALID
  - 네임스페이스 선점 → 403 ROUTE_NAMESPACE_TAKEN
  - pathPrefix 중복 → 409 ROUTE_PATH_CONFLICT
  - SSRF URL → 400 ROUTE_UPSTREAM_INVALID
- [ ] `RegisterOAuthClientServiceTest` 수정 (`@ExtendWith(MockitoExtension.class)`) — 라우트 생성 분기 케이스 추가
  - 라우트 필드 null → 클라이언트만 생성, 라우트 저장 미호출
  - 라우트 필드 둘 다 있음 → 클라이언트 + 라우트 저장, refresh 트리거 호출
  - 네임스페이스 검증 실패 → 전체 트랜잭션 롤백 (클라이언트 저장 안 됨)
  - 라우트 저장 실패 → 전체 트랜잭션 롤백

#### 문서

- [ ] `docs/CLIENT_REGISTRATION.md` — 셀프 등록 API 요청/응답 스펙에 라우트 필드 추가 섹션 반영
- [ ] `docs/DYNAMIC_ROUTING.md` — 셀프 라우트 등록 방법을 "클라이언트 등록과 함께" 단락으로 추가 (별도 엔드포인트 없음, `POST /api/v1/clients`에 흡수됨 명시)
- [ ] `docs/ARCHITECTURE.md` — 에러 코드 체계 서비스-client(ServiceRoute 도메인) 테이블에서 `ROUTE_ACCESS_DENIED` (삭제됨), `ROUTE_NAMESPACE_IMMUTABLE` (삭제됨) 제거. ADR-0013 관련 섹션(13번 핵심 설계 결정)에 라우트 흡수 설명 한 줄 추가
- [ ] `services/libs/service-client/README.md` — `SelfManageRouteUseCase` / `SelfManageRouteService` 항목 제거. `RegisterOAuthClientService` 설명에 라우트 선택적 생성 언급 추가
- [ ] `services/apis/auth-api/README.md` — `POST /api/v1/clients` 엔드포인트 설명에 라우트 필드 추가 내용 반영. `/api/v1/routes/**` 엔드포인트 항목 제거

#### 범위 밖 (문서에만 기록)

- [ ] 라우트 조회/수정/삭제 별도 엔드포인트는 이번 범위 밖 — 추후 클라이언트 목록/상세 조회 API 설계 시 포함 예정으로만 기록

## 모호함 — 결정 필요

아래 두 항목은 설계 결정이 필요하므로 구현 착수 전 호출자에게 확인한다.

**1. `RegisterOAuthClientService`에 `GatewayRefreshClient` 주입 방법**

`RegisterOAuthClientService`는 현재 `@Service`로 `ServiceClientAutoConfiguration` 컴포넌트 스캔 대상이다. `GatewayRefreshClient` 구현체(`GatewayRefreshClientImpl`)는 `auth-api` 모듈 소속 빈이어서 service-client lib 스캔 범위 밖이지만, Spring 컨텍스트 전체에서는 접근 가능하다. 두 선택지가 있다.

- **선택지 A**: `RegisterOAuthClientService`를 `@Service`에서 일반 클래스로 바꾸고 `ApplicationServiceConfig`에서 `@Bean`으로 수동 등록 — `ManageRouteService`와 동일한 패턴, `GatewayRefreshClient` + `RouteValidator` + `RouteNamespaceExtractor` 명시적 와이어링 가능
- **선택지 B**: `@Service` 유지 + `@Autowired(required = false) GatewayRefreshClient` 선택 주입 — 라우트 필드가 있는데 빈이 없으면 refresh 미호출 (silent fail). service-client lib 단위 테스트에서 Mock 주입 편의

**2. `pathPrefix`/`upstreamUrl` 한 필드만 있는 요청의 400 검증 위치**

- **선택지 A**: `SelfRegisterClientRequest`에 클래스 레벨 `@AssertTrue` Bean Validation 추가 — 컨트롤러 계층에서 즉시 거부, 서비스 레이어 진입 전
- **선택지 B**: `RegisterOAuthClientService.selfRegister`에서 명시적 if 조건으로 `IllegalArgumentException` throw — 서비스 레이어 검증, 컨트롤러는 단순 위임

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

## 참고
- `services/apis/auth-api/.../presentation/controller/ClientController.java` — 흡수 대상 컨트롤러
- `services/libs/service-client/.../application/service/RegisterOAuthClientService.java` — 확장 대상 서비스
- `services/libs/service-client/.../application/usecase/RegisterOAuthClientUseCase.java` — Command/Result 확장 대상
- `services/apis/auth-api/.../presentation/dto/SelfRegisterClientRequest.java`, `SelfRegisterClientResponse.java` — DTO 확장 대상
- `services/libs/service-client/.../application/service/RouteValidator.java` — 재사용
- `services/libs/service-client/.../application/service/RouteNamespaceExtractor.java` — 재사용
- `services/libs/service-client/.../application/service/ManageRouteService.java` — triggerRefresh 패턴 참조
- `services/apis/auth-api/.../config/ApplicationServiceConfig.java` — 빈 와이어링 수정 대상
- `services/apis/auth-api/.../exception/GlobalExceptionHandler.java` — 핸들러 제거 대상
- `services/apis/api-gateway/.../config/GatewayRoutingConfig.java` — auth-routes 항목 제거 대상
- `services/apis/auth-api/.../config/ProtectedPathPolicyImpl.java` — /api/v1/routes/** 항목 제거 대상
- `docs/adr/0013-passport-member-self-registration.md`
- `docs/DYNAMIC_ROUTING.md`
- `docs/CLIENT_REGISTRATION.md`
