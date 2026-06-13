# dynamic-gateway-routing - implementation

## 메타
- **작업명**: dynamic-gateway-routing
- **문서 타입**: implementation
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

`service_route` 테이블에서 라우트를 동적으로 읽어 api-gateway가 재배포 없이 즉시 반영하도록 전환한다. auth-api(Spring MVC / Servlet)가 라우트 CRUD의 단일 진실 소스를 소유하고, api-gateway(Spring Cloud Gateway / WebFlux)는 기동 시 REST로 초기 로드 후 내부 refresh 엔드포인트로 갱신 이벤트를 수신하는 **옵션 B** 구조다. DB 접근은 auth-api의 JPA 계층(service-client 라이브러리)으로 일원화하며, api-gateway에는 R2DBC를 추가하지 않는다. 언어는 Java 21, 빌드 도구는 Gradle Kotlin DSL 멀티모듈, service-client 모듈의 3계층(presentation/application/persistence) 패턴을 그대로 확장한다.

---

## 본문

### 아키텍처 결정 (확정)

| 결정 항목 | 선택 | 근거 |
|-----------|------|------|
| 라우트 CRUD API 소유 모듈 | **auth-api** | `@PassportAuth`, `GlobalExceptionHandler`, JPA 인프라 재사용. api-gateway에서는 Reactive 스택 제약으로 `PassportArgumentResolver`(HandlerMethodArgumentResolver) 사용 불가 |
| 게이트웨이 라우트 로딩 방식 | **옵션 B**: auth-api REST + refresh 엔드포인트 | DB 접근을 auth-api로 일원화. api-gateway에 R2DBC 의존성 불필요. 모듈 경계 유지 |
| 갱신 이벤트 전파 | **단일 인스턴스 가정** — HTTP POST `/api/v1/internal/routes/refresh` | 현재 compose 환경은 단일 인스턴스. 다중 인스턴스 필요 시 Redis pub/sub 확장 여지 주석으로 명시 |
| auth-api 핵심 경로 처리 | **정적 보호 라우트 유지** (GatewayRoutingConfig Java DSL) | 부트스트랩 순서 문제 회피. auth-api 자체를 동적 라우트로 이동하지 않음 |
| seed/부트스트랩 | **옵션 S-B**: `@EventListener(ApplicationReadyEvent)` 멱등 INSERT | 환경변수 의존성이 Flyway SQL보다 안전 |

---

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---------------|-------------|------|
| `services/libs/service-client` — `application/domain/ServiceRoute` | **신규** | ARCHITECTURE.md 명시 record. 파일 없음 확인 |
| `services/libs/service-client` — `application/repository/ServiceRouteRepository` | **신규** | 아웃바운드 포트 인터페이스 |
| `services/libs/service-client` — `application/usecase/ManageRouteUseCase` | **신규** | 인바운드 포트 (유스케이스 인터페이스) |
| `services/libs/service-client` — `application/service/ManageRouteService` | **신규** | SSRF·보호경로 검증 포함 유스케이스 구현체 |
| `services/libs/service-client` — `persistence/entity/ServiceRouteJpaEntity` | **신규** | `service_route` 테이블 JPA 매핑 |
| `services/libs/service-client` — `persistence/repository/ServiceRouteJpaRepository` | **신규** | Spring Data JPA 인터페이스 |
| `services/libs/service-client` — `persistence/repository/ServiceRouteRepositoryAdapter` | **신규** | 포트 구현체 |
| `services/libs/service-client` — `exception/` (4종) | **신규** | RouteNotFoundException, RoutePathConflictException, RouteUpstreamInvalidException, RouteProtectedException |
| `services/libs/service-client` — `config/ProtectedPathRegistry` | **신규** | 보호 경로 상수 집합 |
| `services/apis/auth-api` — `presentation/controller/AdminRouteController` | **신규** | CRUD 컨트롤러 5종 엔드포인트 |
| `services/apis/auth-api` — `presentation/dto/` (요청/응답 DTO) | **신규** | CreateRouteRequest, UpdateRouteRequest, RouteResponse |
| `services/apis/auth-api` — `config/ApplicationServiceConfig` | **변경** | `ManageRouteService` `@Bean` 등록 추가 |
| `services/apis/auth-api` — `exception/GlobalExceptionHandler` | **변경** | Route 예외 4종 핸들러 추가 |
| `services/apis/auth-api` — `application/service/GatewayRefreshClient` | **신규** | WebClient 래퍼 — api-gateway refresh 호출 |
| `services/apis/auth-api` — `config/GatewayClientConfig` | **신규** | `GatewayRefreshClient` 빈 등록, `GATEWAY_URI` 환경변수 바인딩 |
| `services/apis/auth-api` — `application/service/RouteBootstrapService` | **신규** | 기동 시 시드 라우트 멱등 INSERT |
| `services/apis/api-gateway` — `config/DynamicRouteDefinitionRepository` | **신규** | `RouteDefinitionRepository` 구현, auth-api REST로 초기 로드 + 인메모리 캐시 |
| `services/apis/api-gateway` — `presentation/controller/RouteRefreshHandler` | **신규** | `POST /api/v1/internal/routes/refresh` WebFlux 핸들러, `RefreshRoutesEvent` 발행 |
| `services/apis/api-gateway` — `presentation/controller/InternalRouteRefreshRouter` | **신규** | RouterFunction으로 refresh 엔드포인트 등록 |
| `services/apis/api-gateway` — `config/GatewayRoutingConfig` | **변경** | 정적 `eeos` 라우트 제거, 보호 경로(auth-api 핵심 경로)만 정적 유지, `permittedPaths` 구조 보존 |
| `services/apis/api-gateway` — `application.yml` | **변경** | `spring.cloud.gateway.routes` 정적 선언(중복) 정리, `gateway.internal-secret` 추가 |
| `db/migration/V9__decouple_service_route_from_client.sql` | **신규** | FK 제거 + registered_client_id nullable |
| `db/migration/V10__add_index_service_route_enabled.sql` | **신규** | enabled 인덱스 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/libs/service-client`

```
com.econo.auth.client
├── application/
│   ├── domain/
│   │   └── ServiceRoute                          — 라우팅 정보 불변 도메인 record
│   ├── repository/
│   │   └── ServiceRouteRepository                — 아웃바운드 포트 인터페이스
│   ├── usecase/
│   │   └── ManageRouteUseCase                    — 인바운드 포트 인터페이스
│   └── service/
│       └── ManageRouteService                    — 유스케이스 구현체 (SSRF·보호경로 검증)
├── persistence/
│   ├── entity/
│   │   └── ServiceRouteJpaEntity                 — service_route 테이블 JPA 엔티티
│   └── repository/
│       ├── ServiceRouteJpaRepository             — Spring Data JPA 인터페이스
│       └── ServiceRouteRepositoryAdapter         — ServiceRouteRepository 포트 구현체
├── config/
│   ├── ServiceClientAutoConfiguration            — (변경 없음, EntityScan 범위 이미 포함)
│   └── ProtectedPathRegistry                     — 보호 경로 상수 집합
└── exception/
    ├── RouteNotFoundException                    — 404 ROUTE_NOT_FOUND
    ├── RoutePathConflictException                — 409 ROUTE_PATH_CONFLICT
    ├── RouteUpstreamInvalidException             — 400 ROUTE_UPSTREAM_INVALID
    └── RouteProtectedException                   — 403 ROUTE_PROTECTED
```

##### `ServiceRoute` (application/domain record)
- **타입**: Domain
- **책임**: Gateway 라우트 식별자·경로·업스트림·활성 여부를 담는 불변 값 객체
- **주요 메서드/함수**:
  - `record ServiceRoute(String routeId, String pathPrefix, String upstreamUrl, boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt)` — 전체 필드 canonical constructor
  - `static ServiceRoute create(String pathPrefix, String upstreamUrl, boolean enabled)` — routeId=`UUID.randomUUID().toString()`, 타임스탬프 null(JPA Auditing에 위임)
- **의존성**: 없음 (순수 도메인)
- **적용 컨벤션**:
  - record를 사용하여 불변성 보장 (CONVENTION.md §2.3 불변성)
  - 도메인 객체 네이밍: `{Name}` PascalCase (CONVENTION.md §1.2)
  - 정적 팩토리 메서드 `create` 패턴 (CONVENTION.md §1.3, `create` 접두사)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceClient.java` — 동일 패키지 도메인 객체 패턴
- **연관 todo**: `[ ] ServiceRoute 도메인 객체 신규 작성`

##### `ServiceRouteRepository` (application/repository 인터페이스)
- **타입**: 아웃바운드 포트
- **책임**: service_route 영속성 추상화. `ManageRouteService`가 구현 기술에 의존하지 않도록 격리
- **주요 메서드/함수**:
  - `ServiceRoute save(ServiceRoute route)` — 저장 후 도메인 반환
  - `List<ServiceRoute> findAll()` — 전체 목록 (createdAt 오름차순)
  - `Optional<ServiceRoute> findById(String routeId)` — 단건
  - `void deleteById(String routeId)` — 삭제
  - `boolean existsByPathPrefix(String pathPrefix)` — pathPrefix 중복 검증
  - `boolean existsByPathPrefixAndRouteIdNot(String pathPrefix, String routeId)` — 수정 시 자기 자신 제외 중복 검증
  - `List<ServiceRoute> findAllEnabled()` — enabled=true 전량 (게이트웨이 초기 로드용)
- **의존성**: 없음 (인터페이스)
- **적용 컨벤션**:
  - 아웃바운드 포트 네이밍: `{Resource}Repository` (CONVENTION.md §1.2)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceClientRepository.java`
- **연관 todo**: `[ ] ServiceRouteRepository 아웃바운드 포트 신규 작성`

##### `ManageRouteUseCase` (application/usecase 인터페이스)
- **타입**: 인바운드 포트
- **책임**: 라우트 CRUD 진입점 계약 정의
- **주요 메서드/함수**:
  - `RouteResult createRoute(CreateRouteCommand command)`
  - `RouteResult updateRoute(String routeId, UpdateRouteCommand command)`
  - `void deleteRoute(String routeId)`
  - `List<RouteResult> listRoutes()`
  - `RouteResult getRoute(String routeId)`
  - Command/Result는 `ManageRouteUseCase` 내부 record로 선언 (RegisterOAuthClientUseCase 패턴 미러링)
- **의존성**: `ServiceRoute` 도메인
- **적용 컨벤션**:
  - 인바운드 포트 네이밍: `{Action}UseCase` (CONVENTION.md §1.2)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java:36` — Command/Result record 패턴
- **연관 todo**: `[ ] ManageRouteUseCase 인바운드 포트 신규 작성`

##### `ManageRouteService`
- **타입**: 유스케이스 구현체 (`ManageRouteUseCase` 구현)
- **책임**: 라우트 CRUD 오케스트레이션. upstreamUrl SSRF 검증, pathPrefix 충돌·보호경로 검증, DB 저장, 게이트웨이 refresh 트리거를 순서대로 실행
- **주요 메서드/함수**:
  - `RouteResult createRoute(CreateRouteCommand command)` — 검증→저장→refresh 트리거 (refresh 실패 시 경고 로그만, 롤백 없음)
  - `RouteResult updateRoute(String routeId, UpdateRouteCommand command)` — 조회→검증→수정→refresh 트리거
  - `void deleteRoute(String routeId)` — 조회→보호경로 확인→삭제→refresh 트리거
  - `List<RouteResult> listRoutes()` — 전체 조회 (createdAt 오름차순)
  - `RouteResult getRoute(String routeId)` — 단건 조회
  - `private void validateUpstreamUrl(String url)` — SSRF 검증 (허용 스킴: http/https; private IP 차단; 호스트 필수; `gateway.ssrf.allow-private-hosts` 설정 참조)
  - `private void validatePathPrefix(String prefix)` — `/`로 시작 여부, 보호경로 패턴 매칭, 중복 확인
  - `private void validatePathPrefixForUpdate(String prefix, String routeId)` — 수정 시 자기 자신 제외 중복
- **의존성**: `ServiceRouteRepository`, `GatewayRefreshClient`, `ProtectedPathRegistry`
- **적용 컨벤션**:
  - 유스케이스 구현 네이밍: `{Action}Service` (CONVENTION.md §1.2)
  - `@Service`가 아닌 일반 클래스 — `auth-api`의 `ApplicationServiceConfig`에서 `@Bean` 등록 (ARCHITECTURE.md §6, SignupService 패턴)
  - `@RequiredArgsConstructor`로 생성자 주입 (CONVENTION.md §2.2)
  - `@Transactional` 쓰기 메서드, `@Transactional(readOnly = true)` 조회 메서드
  - 예외: 정적 팩토리 패턴 사용 (CONVENTION.md §3.1) — `throw new RouteNotFoundException(routeId)` 등
- **참조할 기존 코드**:
  - `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java` — 검증→저장 패턴
- **연관 todo**: `[ ] ManageRouteService 유스케이스 구현체 작성`

##### `ProtectedPathRegistry`
- **타입**: Config (상수 클래스)
- **책임**: pathPrefix 등록/수정/삭제 거부 대상 경로 패턴 목록을 단일 진실 소스로 관리. `ManageRouteService`와 `GatewayRoutingConfig` 모두 이 목록을 참조
- **주요 메서드/함수**:
  - `static final List<String> PROTECTED_PATHS` — 보호 경로 패턴 목록 (아래 참조)
  - `static boolean isProtected(String pathPrefix)` — `PathPatternParser` 기반 패턴 매칭 (BearerToPassportFilter와 동일 방식)
- **보호 경로 초기값** (api-design-plan.md §보호 경로 목록 기준):
  - `/api/v1/auth/**`, `/oauth2/**`, `/.well-known/**`, `/userinfo`
  - `/swagger-ui/**`, `/swagger-ui.html`, `/v3/api-docs/**`, `/v3/api-docs`
  - `/actuator/**`
  - `/api/v1/admin/**`, `/api/v1/members/**`, `/api/v1/clients/**`
- **의존성**: `PathPatternParser` (Spring Web, 이미 service-client 의존성에 포함)
- **적용 컨벤션**:
  - 상수 클래스 네이밍: `{Name}s` 복수형 → 단, Registry 패턴이 더 명확하므로 `ProtectedPathRegistry` 사용 (CONVENTION.md §1.2 `{Name}` 도메인 객체 예외 허용)
  - 상수: `UPPER_SNAKE_CASE` (CONVENTION.md §1.4)
- **연관 todo**: `[ ] 보호 경로 목록 상수/설정 클래스 작성`

##### `ServiceRouteJpaEntity`
- **타입**: JPA 어댑터 (persistence/entity)
- **책임**: `service_route` 테이블 ORM 매핑. V9 마이그레이션 후 스키마(FK 제거, registered_client_id nullable) 기준
- **주요 메서드/함수**:
  - `static ServiceRouteJpaEntity from(ServiceRoute route)` — 도메인 → 엔티티
  - `ServiceRoute toDomain()` — 엔티티 → 도메인
- **필드 매핑**:
  - `@Id @GeneratedValue(strategy = IDENTITY) Long id`
  - `@Column(name = "route_id", nullable = false, unique = true, length = 100) String routeId`
  - `@Column(name = "registered_client_id", nullable = true, length = 100) String registeredClientId` — V9 이후 nullable
  - `@Column(name = "path_prefix", nullable = true, length = 200) String pathPrefix`
  - `@Column(name = "upstream_url", nullable = false, length = 500) String upstreamUrl`
  - `@Column(name = "enabled", nullable = false) boolean enabled`
  - `@CreatedDate @Column(name = "created_at", nullable = false, updatable = false) LocalDateTime createdAt`
  - `@LastModifiedDate @Column(name = "updated_at", nullable = false) LocalDateTime updatedAt`
- **의존성**: `AuditingEntityListener`, `ServiceRoute` 도메인
- **적용 컨벤션**:
  - JPA 어댑터 네이밍: `{Name}JpaEntity` (CONVENTION.md §1.2)
  - `@Entity`, `@Table(name = "service_route")`, `@EntityListeners(AuditingEntityListener.class)`
  - `@NoArgsConstructor(access = AccessLevel.PROTECTED)` (ServiceClientJpaEntity 패턴 미러링)
  - `@Getter` 클래스 레벨 (CONVENTION.md §2.2)
  - `@LastModifiedDate`로 `updated_at` 자동 갱신 (db-design-plan.md 확정안)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/entity/ServiceClientJpaEntity.java`
- **연관 todo**: `[ ] ServiceRouteJpaEntity JPA 엔티티 작성`, `[ ] service_route.updated_at 자동 갱신 트리거 또는 JPA @LastModifiedDate 처리 확인`

##### `ServiceRouteJpaRepository`
- **타입**: Spring Data JPA 인터페이스
- **책임**: service_route 기본 CRUD + 도메인 쿼리
- **주요 메서드/함수**:
  - `List<ServiceRouteJpaEntity> findAllByOrderByCreatedAtAsc()` — 전체 목록 createdAt 오름차순
  - `List<ServiceRouteJpaEntity> findAllByEnabled(boolean enabled)` — enabled 필터 (V10 인덱스 사용)
  - `Optional<ServiceRouteJpaEntity> findByRouteId(String routeId)` — routeId 단건
  - `boolean existsByPathPrefix(String pathPrefix)` — 중복 검증
  - `boolean existsByPathPrefixAndRouteIdNot(String pathPrefix, String routeId)` — 수정 시 자기 제외
  - `void deleteByRouteId(String routeId)` — routeId 삭제
- **의존성**: `JpaRepository<ServiceRouteJpaEntity, Long>` 상속
- **적용 컨벤션**:
  - JPA 인터페이스 네이밍: `{Name}JpaRepository` (CONVENTION.md §1.2)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/ServiceClientJpaRepository.java`
- **연관 todo**: `[ ] ServiceRouteJpaRepository Spring Data JPA 인터페이스 작성`

##### `ServiceRouteRepositoryAdapter`
- **타입**: 아웃바운드 어댑터 (`ServiceRouteRepository` 구현)
- **책임**: `ServiceRouteJpaRepository`에 위임하여 포트 계약 충족
- **주요 메서드/함수**: `ServiceRouteRepository` 인터페이스 전체 구현
- **의존성**: `ServiceRouteJpaRepository`
- **적용 컨벤션**:
  - 어댑터 네이밍: `{Name}RepositoryAdapter` (CONVENTION.md §1.2)
  - `@Component`, `@RequiredArgsConstructor`
  - 쓰기: `@Transactional`, 읽기: `@Transactional(readOnly = true)`
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/ServiceClientRepositoryAdapter.java`
- **연관 todo**: `[ ] ServiceRouteRepositoryAdapter 포트 구현체 작성`

##### 예외 클래스 4종
- **타입**: Domain Exception
- **책임**: 라우트 관련 예외 식별자 제공 — `GlobalExceptionHandler`에서 HTTP 상태로 매핑
- **클래스 목록**:
  - `RouteNotFoundException(String routeId)` — 404
  - `RoutePathConflictException(String pathPrefix)` — 409
  - `RouteUpstreamInvalidException(String reason)` — 400
  - `RouteProtectedException(String pathPrefix)` — 403
- **적용 컨벤션**:
  - 예외 네이밍: `{Domain}Exception` (CONVENTION.md §1.2)
  - `@ResponseStatus` 없음 — `GlobalExceptionHandler`가 매핑 책임 (기존 `InvalidClientException` 패턴)
  - `RuntimeException` 상속
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/exception/InvalidClientException.java`
- **연관 todo**: `[ ] 예외 클래스 추가`

---

#### 모듈 / 패키지: `services/apis/auth-api`

```
com.econo.auth.api
├── presentation/
│   ├── controller/
│   │   ├── AdminRouteController          — 라우트 CRUD 엔드포인트 5종
│   │   └── InternalRouteController       — GET /api/v1/internal/routes (게이트웨이 초기 로드용)
│   └── dto/
│       ├── CreateRouteRequest            — POST 요청 바디 record
│       ├── UpdateRouteRequest            — PUT 요청 바디 record
│       └── RouteResponse                 — 응답 바디 record
├── application/
│   └── service/
│       ├── GatewayRefreshClient          — api-gateway refresh HTTP 호출 추상화
│       └── RouteBootstrapService         — 기동 시 시드 라우트 멱등 INSERT
├── config/
│   ├── ApplicationServiceConfig          — (변경) ManageRouteService @Bean 추가
│   └── GatewayClientConfig               — GatewayRefreshClient 빈 등록
└── exception/
    └── GlobalExceptionHandler            — (변경) Route 예외 4종 핸들러 추가
```

##### `AdminRouteController`
- **타입**: Controller (presentation/controller)
- **책임**: `/api/v1/admin/routes` CRUD 5종 엔드포인트. `@PassportAuth(requiredRoles = {Roles.ADMIN, Roles.SUPER_ADMIN})` 인증·인가. 요청 DTO → Command 변환 후 `ManageRouteUseCase` 위임
- **주요 메서드/함수**:
  - `@PostMapping("/routes") ResponseEntity<RouteResponse> createRoute(@PassportAuth Passport passport, @Valid @RequestBody CreateRouteRequest request)` — 201 Created
  - `@GetMapping("/routes") ResponseEntity<RouteListResponse> listRoutes(@PassportAuth Passport passport)` — 200 OK
  - `@GetMapping("/routes/{routeId}") ResponseEntity<RouteResponse> getRoute(@PassportAuth Passport passport, @PathVariable String routeId)` — 200 OK / 404
  - `@PutMapping("/routes/{routeId}") ResponseEntity<RouteResponse> updateRoute(@PassportAuth Passport passport, @PathVariable String routeId, @Valid @RequestBody UpdateRouteRequest request)` — 200 OK
  - `@DeleteMapping("/routes/{routeId}") ResponseEntity<Void> deleteRoute(@PassportAuth Passport passport, @PathVariable String routeId)` — 204 No Content
- **의존성**: `ManageRouteUseCase`
- **적용 컨벤션**:
  - `@Slf4j`, `@RestController`, `@RequestMapping("/api/v1/admin")`, `@RequiredArgsConstructor`
  - Swagger `@Tag`, `@Operation`, `@ApiResponses` 어노테이션 (AdminClientController 패턴 미러링)
  - DTO를 컨트롤러 내부 record가 아닌 별도 파일로 분리 (`presentation/dto/` 패키지) — DTO 재사용 가능성 고려
  - `@Valid` 어노테이션으로 Bean Validation 위임 (CONVENTION.md §2.5)
  - Passport 파라미터는 메서드 첫 번째 파라미터 (AdminClientController 패턴 일치)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminClientController.java`
- **연관 todo**: `[ ] AdminRouteController 신규 작성`, `[ ] AdminRouteController 요청/응답 DTO 작성`

##### `CreateRouteRequest`, `UpdateRouteRequest`, `RouteResponse`
- **타입**: DTO (record)
- **책임**: 요청 바디 역직렬화 + Bean Validation, 응답 바디 직렬화
- **필드**:
  - `CreateRouteRequest`: `@NotBlank String pathPrefix`, `@NotBlank String upstreamUrl`, `@NotNull Boolean enabled`
  - `UpdateRouteRequest`: `@NotBlank String pathPrefix`, `@NotBlank String upstreamUrl`, `@NotNull Boolean enabled`
  - `RouteResponse`: `String routeId`, `String pathPrefix`, `String upstreamUrl`, `boolean enabled`, `LocalDateTime createdAt`, `LocalDateTime updatedAt`
  - `RouteListResponse`: `List<RouteResponse> routes`
- **적용 컨벤션**:
  - `@NotBlank`/`@NotNull` (CONVENTION.md §2.5)
  - record 사용 (CONVENTION.md §2.3 불변성)
- **연관 todo**: `[ ] AdminRouteController 요청/응답 DTO 작성`

##### `GatewayRefreshClient`
- **타입**: 응용 서비스 (application/service, HTTP 클라이언트 래퍼)
- **책임**: api-gateway의 `POST /api/v1/internal/routes/refresh` 비동기 호출. 게이트웨이 응답 실패 시 경고 로그만 남기고 예외를 상위로 전파하지 않음 (최종 일관성 수용 — api-design-plan.md §POST /api/v1/admin/routes 비고)
- **주요 메서드/함수**:
  - `void triggerRefresh()` — `WebClient`로 POST 호출. `onErrorResume`으로 예외 흡수 + `log.warn`
- **의존성**: `WebClient`
- **구현 주의**: auth-api는 Spring MVC(Servlet) 스택이지만 `WebClient`는 Reactive이다. `RestTemplate` 대신 `WebClient`를 사용하되 `.block()` 타임아웃을 설정하여 Servlet 스레드 블로킹 시간을 제한한다. 대안: `RestClient`(Spring 6.1+) 사용 검토 — auth-api의 Spring Boot 버전(3.2.2)에서 지원됨.
  > 권장: `RestClient`(Servlet 네이티브)로 구현하여 Reactive 라이브러리 혼용 회피. `RestClient.create()` → `.post()` → `.uri(gatewayUri + "/api/v1/internal/routes/refresh")` → `.header("X-Internal-Secret", secret)` → `.retrieve()`.
- **적용 컨벤션**:
  - `@Slf4j`, `@RequiredArgsConstructor`
  - `log.warn()` 패턴으로 에러 로깅 (CONVENTION.md §3.2)
- **연관 todo**: (refresh 트리거 구현 — API 작업의 게이트웨이 refresh 호출 부분)

##### `GatewayClientConfig`
- **타입**: Config
- **책임**: `GatewayRefreshClient` 빈 등록. `GATEWAY_URI` + `GATEWAY_INTERNAL_SECRET` 환경변수 바인딩
- **주요 메서드/함수**:
  - `@Bean GatewayRefreshClient gatewayRefreshClient(@Value("${GATEWAY_URI:http://localhost:8080}") String gatewayUri, @Value("${GATEWAY_INTERNAL_SECRET:dev-secret}") String internalSecret)`
- **의존성**: Spring `@Configuration`
- **연관 todo**: (구현 작업 auth-api 게이트웨이 통지 부분)

##### `RouteBootstrapService`
- **타입**: 응용 서비스 (application/service, 부트스트랩)
- **책임**: auth-api 기동 시 `service_route` 테이블이 비어있으면 핵심 정적 라우트 시드 INSERT. 멱등 — 이미 있는 pathPrefix는 건너뜀 (`existsByPathPrefix` 확인)
- **주요 메서드/함수**:
  - `@EventListener(ApplicationReadyEvent.class) void bootstrap()` — auth-api 핵심 경로(`/api/v1/auth/**`, `/oauth2/**`, `/.well-known/**`, `/userinfo`) 등 시드
  - `private void seedIfAbsent(String pathPrefix, String upstreamUrl)` — 중복 없으면 `ServiceRouteRepository.save`
- **의존성**: `ServiceRouteRepository`, `@Value("${AUTH_API_URI:http://localhost:8081}") String authApiUri`
- **적용 컨벤션**:
  - `@EventListener` — Spring 이벤트 리스너
  - `@Component`로 등록 (`ServiceClientAutoConfiguration`의 `@ComponentScan("com.econo.auth.client")` 범위 밖이므로 **auth-api 모듈** `com.econo.auth.api.application.service` 패키지에 위치시키고 `ApplicationServiceConfig`에서 `@Bean` 등록 또는 별도 `@Component` 스캔)
  > 주의: `RouteBootstrapService`는 auth-api 애플리케이션 계층에 위치 (`com.econo.auth.api.application.service`). `ServiceClientAutoConfiguration`은 `com.econo.auth.client` 패키지만 스캔하므로 auth-api 패키지는 auth-api 자체 컴포넌트 스캔이 담당.
- **연관 todo**: `[ ] auth-api 기동 시 시드 라우트 자동 등록 로직 작성`

##### `ApplicationServiceConfig` (변경)
- **타입**: Config (변경)
- **책임**: (기존 역할 유지) `ManageRouteService` `@Bean` 추가 등록
- **추가 내용**:
  ```
  @Bean ManageRouteService manageRouteService(
      ServiceRouteRepository serviceRouteRepository,
      GatewayRefreshClient gatewayRefreshClient,
      ProtectedPathRegistry protectedPathRegistry)
  ```
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java`
- **연관 todo**: `[ ] ApplicationServiceConfig 수정 — ManageRouteService @Bean 등록`

##### `GlobalExceptionHandler` (변경)
- **타입**: Exception Handler (변경)
- **책임**: Route 예외 4종 → HTTP 상태·에러 코드 매핑 핸들러 추가
- **추가 핸들러**:
  - `@ExceptionHandler(RouteNotFoundException.class)` → 404 `ROUTE_NOT_FOUND`
  - `@ExceptionHandler(RoutePathConflictException.class)` → 409 `ROUTE_PATH_CONFLICT`
  - `@ExceptionHandler(RouteUpstreamInvalidException.class)` → 400 `ROUTE_UPSTREAM_INVALID`
  - `@ExceptionHandler(RouteProtectedException.class)` → 403 `ROUTE_PROTECTED`
- **적용 컨벤션**:
  - `DataIntegrityViolationException` 핸들러(409 `DUPLICATE_RESOURCE`)보다 Route 예외 핸들러가 먼저 실행되도록 `ManageRouteService`에서 DB 쓰기 전 사전 검증으로 `RoutePathConflictException`을 먼저 던짐 (api-design-plan.md §신규 에러 코드 정의 비고)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`
- **연관 todo**: `[ ] GlobalExceptionHandler 수정 — 신규 Route 예외 4종 매핑 추가`

---

#### 모듈 / 패키지: `services/apis/api-gateway`

```
com.econo.auth.gateway
├── presentation/
│   └── controller/
│       ├── RouteRefreshHandler               — WebFlux 핸들러, RefreshRoutesEvent 발행
│       └── InternalRouteRefreshRouter        — RouterFunction 빈 등록
└── config/
    ├── DynamicRouteDefinitionRepository      — RouteDefinitionRepository 구현 (인메모리 캐시)
    └── GatewayRoutingConfig                  — (변경) 보호경로 정적 라우트만 유지, eeos 제거
```

##### `DynamicRouteDefinitionRepository`
- **타입**: Gateway 컴포넌트 (`RouteDefinitionRepository` 구현, config 패키지)
- **책임**: Spring Cloud Gateway의 `RouteDefinitionRepository` 계약 구현. 인메모리 `ConcurrentHashMap<String, RouteDefinition>` 캐시를 primary 저장소로 사용. 기동 시(`@EventListener(ApplicationReadyEvent.class)` 또는 `InitializingBean.afterPropertiesSet`) auth-api `GET /api/v1/internal/routes`를 `WebClient`로 호출하여 enabled=true 라우트 전량 로드. `RefreshRoutesEvent` 발행 직전에 재로드
- **주요 메서드/함수**:
  - `Flux<RouteDefinition> getRouteDefinitions()` — 캐시에서 반환 (Reactive 계약)
  - `Mono<Void> save(Mono<RouteDefinition> route)` — 캐시에 추가 (Spring 내부 사용)
  - `Mono<Void> delete(Mono<String> routeId)` — 캐시에서 제거
  - `void reload()` — auth-api `GET /api/v1/internal/routes`를 `WebClient` `.block()`으로 동기 호출하여 캐시 전량 교체 (또는 `Mono.block()` 타임아웃 5초)
  - `private RouteDefinition toRouteDefinition(ServiceRouteDto route)` — pathPrefix → `PathRoutePredicateFactory` 매핑 + `StripPrefix(1)` 필터 적용
- **의존성**: `WebClient`(WebFlux 내장), `ApplicationEventPublisher`, `@Value("${AUTH_API_URI}")`, `@Value("${GATEWAY_INTERNAL_SECRET}")`
- **라우트 변환 규칙**:
  - 라우트 ID: `route.routeId()`
  - Predicate: `Path={route.pathPrefix()}/**`
  - Filter: `StripPrefix=1` (pathPrefix 이후 경로를 업스트림에 전달)
  - URI: `route.upstreamUrl()`
- **적용 컨벤션**:
  - `@Component`, `@RequiredArgsConstructor`, `@Slf4j`
  - `@Order(Ordered.LOWEST_PRECEDENCE)` — 정적 보호 라우트(`GatewayRoutingConfig`의 `RouteLocator`)가 먼저 매칭되도록 낮은 우선순위 부여
  > 우선순위 중요: Spring Cloud Gateway는 `RouteLocator` 빈(정적)을 `RouteDefinitionRepository`(동적) 보다 Order가 낮으면 먼저 처리한다. `GatewayRoutingConfig`의 `RouteLocator` 빈에 `@Order(1)`을 부여하고 `DynamicRouteDefinitionRepository`는 `@Order(100)` 또는 낮은 우선순위를 설정하여 보호 경로 가로채기를 방지
- **연관 todo**: `[ ] DynamicRouteDefinitionRepository 신규 작성`

##### `RouteRefreshHandler`
- **타입**: WebFlux 핸들러 (HandlerFunction, presentation/controller)
- **책임**: `X-Internal-Secret` 헤더 검증 후 `DynamicRouteDefinitionRepository.reload()` + `ApplicationEventPublisher.publishEvent(new RefreshRoutesEvent(this))` 수행. 게이트웨이 내부 전용 엔드포인트로 라우팅 테이블에 노출하지 않음
- **주요 메서드/함수**:
  - `Mono<ServerResponse> handle(ServerRequest request)` — 시크릿 검증 → reload → RefreshRoutesEvent → 200 `{"refreshed": true}`
  - 시크릿 불일치 시 403 반환
- **의존성**: `DynamicRouteDefinitionRepository`, `ApplicationEventPublisher`, `@Value("${GATEWAY_INTERNAL_SECRET:dev-secret}")`
- **적용 컨벤션**:
  - `@Component`, `@RequiredArgsConstructor`, `@Slf4j`
  - WebFlux `HandlerFunction<ServerResponse>` 패턴 (GatewaySecurityConfig와 동일 WebFlux 스택)
- **연관 todo**: `[ ] RouteRefreshService 신규 작성 — RefreshRoutesEvent 발행`

##### `InternalRouteRefreshRouter`
- **타입**: Config (`RouterFunction` 빈, presentation/controller)
- **책임**: `/api/v1/internal/routes/refresh` 경로를 `RouteRefreshHandler`에 연결하는 `RouterFunction<ServerResponse>` 빈 등록. 이 경로는 Spring Cloud Gateway의 라우팅 테이블(`service_route`)에 등록되지 않으므로 외부에서 접근 불가
- **주요 메서드/함수**:
  - `@Bean RouterFunction<ServerResponse> internalRefreshRoute(RouteRefreshHandler handler)` — `RouterFunctions.route(POST("/api/v1/internal/routes/refresh"), handler)`
- **의존성**: `RouteRefreshHandler`
- **적용 컨벤션**:
  - `@Configuration`
- **연관 todo**: `[ ] RouteRefreshService 신규 작성` (연관 엔드포인트 등록)

##### `GatewayRoutingConfig` (변경)
- **타입**: Config (변경)
- **책임**: 동적 라우팅 전환 후 보호 경로(auth-api 핵심 + Admin + Members + Clients + Swagger)만 정적으로 유지. `eeos` 등 동적으로 이동할 수 있는 라우트는 제거 (또는 초기 시드에 포함). `permittedPaths` 관리 구조는 그대로 유지
- **변경 내용**:
  - 제거: `.route("eeos", ...)` — `service_route` 동적 라우트로 이관 (RouteBootstrapService가 시드)
  - 유지: `auth-api`, `auth-admin`, `auth-clients`, `auth-members`, `sas-oauth2`, `sas-well-known`, `sas-userinfo`, `auth-swagger` — 보호 경로 정적 고정
  - 추가: `@Order(1)` 또는 `@Primary` — `DynamicRouteDefinitionRepository`보다 높은 우선순위 보장
  - `permittedPaths` 유지 (BearerToPassportFilter 참조 구조 변경 없음)
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java`
- **연관 todo**: `[ ] GatewayRoutingConfig 수정/분리`

##### `application.yml` (변경)
- **변경 내용**:
  - `spring.cloud.gateway.routes` 정적 선언 제거 또는 축소 (Java DSL `GatewayRoutingConfig`와 중복 선언 제거)
  - `gateway.internal-secret: ${GATEWAY_INTERNAL_SECRET:dev-secret}` 추가
  - `AUTH_API_URI` 환경변수는 기존 유지 (DynamicRouteDefinitionRepository가 참조)
- **연관 todo**: `[ ] api-gateway application.yml 정리`

---

#### 모듈 / 패키지: `services/libs/member` (DB 마이그레이션)

```
src/main/resources/db/migration/
├── V9__decouple_service_route_from_client.sql   — FK 제거 + registered_client_id nullable
└── V10__add_index_service_route_enabled.sql     — enabled 인덱스 추가
```

##### `V9__decouple_service_route_from_client.sql`
- **타입**: Flyway 마이그레이션 (신규)
- **책임**: `fk_service_route_client` FK 제약 DROP + `registered_client_id NOT NULL → nullable`
- **연관 todo**: `[ ] Flyway 마이그레이션 V9__decouple_service_route_from_client.sql 작성`

##### `V10__add_index_service_route_enabled.sql`
- **타입**: Flyway 마이그레이션 (신규)
- **책임**: `idx_service_route_enabled` B-tree 인덱스 추가
- **연관 todo**: `[ ] service_route 인덱스 추가 마이그레이션`

---

#### auth-api에 추가되는 내부 라우트 조회 엔드포인트

api-gateway의 `DynamicRouteDefinitionRepository`가 기동 시 호출하는 auth-api 내부 엔드포인트.

##### `InternalRouteController`
- **타입**: Controller (auth-api, 내부 전용)
- **패키지**: `com.econo.auth.api.presentation.controller`
- **책임**: `GET /api/v1/internal/routes` — enabled=true 라우트 전량 반환. `X-Internal-Secret` 헤더 검증. Passport 불필요
- **주요 메서드/함수**:
  - `@GetMapping ResponseEntity<InternalRouteListResponse> listEnabledRoutes(@RequestHeader("X-Internal-Secret") String secret)`
  - 시크릿 불일치 시 `ResponseEntity.status(403).build()`
- **의존성**: `ManageRouteUseCase`
- **적용 컨벤션**: `@RestController`, `@RequestMapping("/api/v1/internal/routes")`
- **게이트웨이 라우팅**: 이 경로는 `GatewayRoutingConfig`의 `/api/v1/admin/**` 정적 라우트에 포함되어 auth-api로 전달됨. 단, `BearerToPassportFilter`의 `permittedPaths`에는 추가하지 않음 — `X-Internal-Secret` 검증으로 보호. Bearer 토큰이 없어도 `/api/v1/admin/**`은 `permittedPaths`에 없으므로 `BearerToPassportFilter`가 401 반환할 수 있음.
  > 보완: `gateway.permitted-paths`에 `/api/v1/internal/**`를 추가하여 BearerToPassportFilter 우회 + X-Internal-Secret으로 이중 보호. 또는 api-gateway가 auth-api 내부망 직접 호출(라우팅 없이 `AUTH_API_URI` 직접)로 BearerToPassportFilter 우회.
  > **권장**: api-gateway → auth-api 직접 HTTP 호출 (`AUTH_API_URI`). 게이트웨이 라우팅 테이블을 통과하지 않으므로 BearerToPassportFilter 적용 없음. `GatewayRoutingConfig` 변경 불필요.
- **연관 todo**: (DynamicRouteDefinitionRepository의 초기 로드 엔드포인트)

---

### 호출 흐름

#### 흐름 1: 라우트 등록 (정상 경로)

```
클라이언트 (ADMIN)
  → POST /api/v1/admin/routes (Authorization: Bearer <token>)
  → api-gateway: BearerToPassportFilter → JWT 검증 → X-User-Passport 주입
  → api-gateway: GatewayRoutingConfig (auth-admin: /api/v1/admin/**) → auth-api 라우팅
  → auth-api: AdminRouteController.createRoute()
    → @PassportAuth(requiredRoles={ADMIN,SUPER_ADMIN}) → PassportArgumentResolver 검증
    → CreateRouteRequest @Valid → Bean Validation
    → ManageRouteService.createRoute(command)
      → validateUpstreamUrl(url) — SSRF 검증 통과
      → validatePathPrefix(prefix) — ProtectedPathRegistry, existsByPathPrefix 통과
      → ServiceRouteRepository.save(route) — DB INSERT (JPA Auditing: createdAt, updatedAt)
      → GatewayRefreshClient.triggerRefresh()
        → RestClient.post("/api/v1/internal/routes/refresh") X-Internal-Secret 헤더 포함
        → api-gateway: InternalRouteRefreshRouter → RouteRefreshHandler.handle()
          → X-Internal-Secret 검증 통과
          → DynamicRouteDefinitionRepository.reload()
            → WebClient.get(AUTH_API_URI + "/api/v1/internal/routes") → InternalRouteController
            → 캐시 전량 교체
          → ApplicationEventPublisher.publishEvent(new RefreshRoutesEvent(this))
          → 200 {"refreshed": true}
    → ManageRouteService 반환: RouteResult
  → AdminRouteController → 201 Created + RouteResponse body
```

#### 흐름 2: 게이트웨이 기동 초기 로드 (정상 경로)

```
api-gateway 기동
  → DynamicRouteDefinitionRepository.afterPropertiesSet() (또는 ApplicationReadyEvent)
    → WebClient.get(AUTH_API_URI + "/api/v1/internal/routes") X-Internal-Secret 헤더
    → auth-api: InternalRouteController.listEnabledRoutes() → ManageRouteUseCase.listRoutes() (enabled 필터)
    → ServiceRouteRepository.findAllEnabled() → DB SELECT WHERE enabled=true (idx_service_route_enabled 사용)
    → RouteDefinition 변환 (pathPrefix → Path predicate, StripPrefix=1 filter, upstreamUrl → URI)
    → ConcurrentHashMap 캐시 초기화
  → Spring Cloud Gateway: getRouteDefinitions() 호출 → 캐시에서 반환
  → 라우팅 준비 완료
```

#### 흐름 3: 라우트 삭제 (정상 경로)

```
클라이언트 (ADMIN)
  → DELETE /api/v1/admin/routes/{routeId}
  → (흐름 1과 동일하게 인증 통과)
  → ManageRouteService.deleteRoute(routeId)
    → ServiceRouteRepository.findById(routeId) → 존재 확인
    → ProtectedPathRegistry.isProtected(route.pathPrefix()) → 보호경로 아님 확인
    → ServiceRouteRepository.deleteById(routeId)
    → GatewayRefreshClient.triggerRefresh() → (흐름 1과 동일)
  → 204 No Content
```

#### 예외 / 실패 경로

```
[SSRF 검증 실패]
  ManageRouteService.validateUpstreamUrl()
    → private IP 또는 비허용 스킴 탐지
    → throw new RouteUpstreamInvalidException(reason)
  GlobalExceptionHandler.handleRouteUpstreamInvalid()
    → 400 {"errorCode": "ROUTE_UPSTREAM_INVALID", ...}

[보호 경로 가로채기 시도]
  ManageRouteService.validatePathPrefix()
    → ProtectedPathRegistry.isProtected("/api/v1/auth/hijack") → true
    → throw new RouteProtectedException(pathPrefix)
  GlobalExceptionHandler.handleRouteProtected()
    → 403 {"errorCode": "ROUTE_PROTECTED", ...}

[pathPrefix 중복]
  ManageRouteService.validatePathPrefix()
    → ServiceRouteRepository.existsByPathPrefix(prefix) → true
    → throw new RoutePathConflictException(prefix)
  GlobalExceptionHandler.handleRoutePathConflict()
    → 409 {"errorCode": "ROUTE_PATH_CONFLICT", ...}

[routeId 미존재]
  ManageRouteService.getRoute() / updateRoute() / deleteRoute()
    → ServiceRouteRepository.findById(routeId) → empty Optional
    → throw new RouteNotFoundException(routeId)
  GlobalExceptionHandler.handleRouteNotFound()
    → 404 {"errorCode": "ROUTE_NOT_FOUND", ...}

[Passport 미인증]
  AdminRouteController: @PassportAuth → PassportArgumentResolver
    → X-User-Passport 헤더 없음 / 파싱 실패
    → throw PassportException.unauthorized(...)
  GlobalExceptionHandler.handlePassportException()
    → 401 {"errorCode": "AUTH_UNAUTHORIZED", ...}

[권한 부족]
  @PassportAuth(requiredRoles={ADMIN,SUPER_ADMIN}) → 역할 불일치
    → throw PassportException.forbidden(...)
  GlobalExceptionHandler.handlePassportException()
    → 403 {"errorCode": "FORBIDDEN", ...}

[게이트웨이 refresh 실패 (최종 일관성)]
  GatewayRefreshClient.triggerRefresh()
    → RestClient 호출 타임아웃 / 연결 실패 / 403
    → log.warn("Gateway refresh failed: ...") 경고 로그
    → 예외 흡수 (상위 트랜잭션 롤백 없음)
  → 라우트는 DB에 정상 저장됨. 게이트웨이는 다음 재기동 시 또는 수동 retry까지 구버전 라우트 사용

[X-Internal-Secret 불일치 (게이트웨이 refresh 엔드포인트)]
  RouteRefreshHandler.handle()
    → 헤더 값 != GATEWAY_INTERNAL_SECRET
    → 403 반환 (Mono<ServerResponse>.status(FORBIDDEN))
```

---

### 컨벤션 준수 항목

- **네이밍**:
  - 도메인 record: `ServiceRoute` (CONVENTION.md §1.2)
  - 포트 인터페이스: `ServiceRouteRepository`, `ManageRouteUseCase`
  - 유스케이스: `ManageRouteService`
  - JPA: `ServiceRouteJpaEntity`, `ServiceRouteJpaRepository`, `ServiceRouteRepositoryAdapter`
  - 예외: `RouteNotFoundException`, `RoutePathConflictException`, `RouteUpstreamInvalidException`, `RouteProtectedException`
  - 컨트롤러: `AdminRouteController`, `InternalRouteController`
  - 메서드 접두사: `validate*`, `find*`, `exists*`, `trigger*` (§1.3)

- **의존성 주입**:
  - service-client 라이브러리 클래스: `@RequiredArgsConstructor` + final 필드 (CONVENTION.md §2.2)
  - `ManageRouteService`: `@Service` 없음 — `ApplicationServiceConfig`에서 `@Bean` 등록 (3계층 컨벤션, SignupService 패턴)
  - 어댑터 클래스: `@Component`

- **예외 처리**:
  - 정적 팩토리 패턴 미적용 (Route 예외는 단순 RuntimeException 상속, 생성자 직접 호출) — `PassportException`만 정적 팩토리 강제 (CONVENTION.md §3.1은 PassportException 전용 예시)
  - `@ResponseStatus` 없음 — `GlobalExceptionHandler`에서 매핑 (InvalidClientException 패턴)
  - 에러 전파: Route 예외는 서비스 레이어에서 throw, GlobalExceptionHandler가 catch

- **불변성**:
  - `ServiceRoute` record — 모든 필드 불변 (CONVENTION.md §2.3)
  - DTO record — 불변

- **테스트 패턴** (CONVENTION.md §5):
  - `ManageRouteServiceTest`: `@ExtendWith(MockitoExtension.class)`, `@Nested` + `@DisplayName` 한글
  - `AdminRouteControllerTest`: `@WebMvcTest(AdminRouteController.class)`, MockMvc
  - `ServiceRouteRepositoryAdapterTest`: `@DataJpaTest` + Testcontainers PostgreSQL
  - Given-When-Then 주석 패턴, AssertJ

- **Javadoc**: 모든 public 클래스·메서드에 Javadoc 작성 (CONVENTION.md §4.1)

- **포맷팅**: Spotless + Google Java Format 1.17.0 적용 (`./gradlew format`)

---

### 미해결 사항 / 리스크

1. **`DynamicRouteDefinitionRepository` 우선순위 보장**: Spring Cloud Gateway에서 `RouteLocator`(정적)과 `RouteDefinitionRepository`(동적)의 Order 충돌이 발생할 수 있다. `GatewayRoutingConfig`의 `routes()` 빈에 `@Order(Ordered.HIGHEST_PRECEDENCE)` 부여가 필요한지 실제 실행으로 검증 필요.

2. **`InternalRouteController` 경로 접근 보호**: `BearerToPassportFilter`가 `/api/v1/internal/**` 경로에도 적용된다. api-gateway → auth-api를 게이트웨이 라우팅 없이 `AUTH_API_URI` 직접 호출하는 방식을 권장(§구성 요소 설계 InternalRouteController 비고 참조). 실구현 시 확정 필요.

3. **`RestClient` vs `WebClient`**: auth-api는 Servlet 스택이므로 Reactive `WebClient` 혼용 시 스레드 블로킹 문제가 발생할 수 있다. Spring 6.1(Boot 3.2.x) `RestClient` 사용이 권장되나 명시적 검증 필요.

4. **다중 인스턴스 갱신 전파**: 현재 설계는 단일 인스턴스 가정. api-gateway 인스턴스가 2개 이상이면 refresh 신호가 한 인스턴스에만 전달된다. 단기: `GatewayRefreshClient`가 모든 인스턴스 URL에 순차 호출. 장기: Redis pub/sub.

5. **auth-api `ServiceClientAutoConfiguration` EntityScan 범위**: `@EntityScan("com.econo.auth.client.persistence.entity")`가 `ServiceRouteJpaEntity`를 자동으로 스캔하는지 확인 필요. 현재 `ServiceRouteJpaEntity` 파일이 존재하지 않으므로 기동 후 검증 필요.

6. **`StripPrefix` 필터 적용 여부**: `DynamicRouteDefinitionRepository`에서 pathPrefix → RouteDefinition 변환 시 `StripPrefix` 필터를 항상 적용할지, 선택적으로 적용할지 결정 필요. 현재 `GatewayRoutingConfig`의 기존 라우트들은 StripPrefix를 사용하지 않으므로 일관성 검토 필요.

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨 (service-client 라이브러리 7종, auth-api 7종, api-gateway 4종, DB 2종)
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨 (service-client 3계층, auth-api presentation/application/config, api-gateway presentation+config 패키지)
- [x] 모든 구성 요소가 적용 컨벤션을 명시함 (네이밍, DI, 예외, 불변성)
- [x] 호출 흐름에 빠진 분기가 없음 (정상: 등록/기동, 예외: SSRF/보호경로/중복/미존재/인증/refresh실패)
- [x] api-design-plan.md (옵션 B, 시나리오 1) 및 db-design-plan.md (V9/V10, JPA Auditing) 권장안과 정합
- [x] reactive(gateway) vs servlet(auth-api) 경계 반영 (RestClient 권장, RouterFunction 사용)
- [x] 다중 인스턴스 확장 여지 명시 (미해결 사항 #4)

---

## 참고
- `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/entity/ServiceClientJpaEntity.java` — JPA 엔티티 컨벤션 기준
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java` — Command/Result record, @Transactional, 검증 패턴
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/AdminClientController.java` — @PassportAuth 컨트롤러 패턴
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 예외 핸들러 추가 기준
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — @Bean 등록 패턴
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` — 정적 라우트 변경 기준
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security/BearerToPassportFilter.java` — PathPatternParser 패턴 참조
- `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java` — EntityScan/ComponentScan 범위 확인
- `db/migration/V4__create_service_client_and_route.sql` — 기존 스키마 기준
- `docs/ARCHITECTURE.md` — 3계층 구조, ServiceRoute record 명시, 에러 코드 체계
- `docs/CONVENTION.md` — 네이밍, DI, 예외, 불변성, 테스트 컨벤션
