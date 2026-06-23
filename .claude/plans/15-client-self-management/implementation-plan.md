# client-self-management - implementation

## 메타
- **작업명**: client-self-management
- **문서 타입**: implementation
- **작성일**: 2026-06-22
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

ADR-0018 이후 회원이 자기 소유 OAuth 클라이언트(+연결 라우트)를 직접 조회·수정·삭제할 수 있는 4개 엔드포인트를 추가한다. `service-client` 라이브러리에 유스케이스·포트·어댑터 확장을 가하고, `auth-api` 애플리케이션에 컨트롤러·DTO·예외 핸들러·빈 등록을 추가한다. Java 21 / Spring Boot 3.2.2 / Gradle 멀티모듈 / 3계층 + 계층별 DIP 아키텍처 위에서 설계된다.

---

## 본문

### 설계 전제: 클라이언트↔라우트 연관은 `registered_client_id`로 한다 (확정)

`service_route` 테이블에는 `registered_client_id`(nullable, FK 제거됨 — V9)와 `owner_id`(nullable — V11) 두 컬럼이 공존한다. `ServiceRoute` 도메인 record에는 현재 `ownerId`만 있고 `registeredClientId`는 없다.

**`ownerId`로 라우트를 식별할 수 없다.** 한 회원(`memberId = ownerId`)은 클라이언트를 최대 5개 가질 수 있으므로, `service_route.owner_id = ownerId`로 조회하면 같은 회원의 **다른 클라이언트에 속한 라우트까지 섞인다**. 그 상태에서 `deleteByOwnerId(ownerId)`를 쓰면 수정·삭제 대상이 아닌 타 클라이언트의 라우트까지 지운다. 즉 `ownerId` 기반 연관은 정합성이 깨진다.

**`registered_client_id`는 클라이언트를 1:1로 식별한다.** `service_client.registered_client_id`는 UNIQUE 제약(V4)으로 보장된 단일 식별자다. `service_route.registered_client_id = ?` 조건이면 해당 클라이언트의 라우트만 정확히 조회·삭제된다. 컬럼 자체는 V4 생성·V9 nullable 전환으로 이미 스키마에 존재하므로 DDL 추가 없이 값만 채우면 된다(인덱스는 db-design-plan의 V13에서 추가).

**채택 결정 (사용자 확정 + db-design-plan 일치)**: 클라이언트↔라우트 연관은 `registered_client_id`로 한다. 이를 위해 다음을 변경한다.

1. `ServiceRoute` 도메인 record에 `registeredClientId` 필드(8번째)를 추가하고, 셀프 등록용 팩토리 `create(pathPrefix, upstreamUrl, enabled, ownerId, registeredClientId)`를 추가한다. 기존 6/7-인자 생성자·어드민용 `create(pathPrefix, upstreamUrl, enabled)`는 `registeredClientId = null`로 유지(어드민 라우트는 V9 분리 의도대로 null).
2. `ServiceRouteJpaEntity.from()`/`fromWithId()`에서 `registeredClientId = null` 고정을 `registeredClientId = route.registeredClientId()`로 바꾸고, `toDomain()`도 `registeredClientId`를 전달하도록 수정한다.
3. `RegisterOAuthClientService.selfRegister`의 라우트 생성을 `ServiceRoute.create(pathPrefix, upstreamUrl, true, ownerId, clientId)`로 바꿔 신규 셀프 라우트에 `registered_client_id`를 채운다(db-design 백필 전략 B — 신규 등록부터만 채움).
4. 라우트 연관 조회/삭제 포트는 `findByRegisteredClientId` / `findByRegisteredClientIdIn`(배치, N+1 방지) / `deleteByRegisteredClientId`로 정의한다. `findByOwnerId`/`deleteByOwnerId`는 쓰지 않는다.

> `ManageRouteService`(어드민 라우트 CRUD)는 `ServiceRoute.create(pathPrefix, upstreamUrl, enabled)` 3-인자 팩토리를 그대로 쓰므로 `registeredClientId = null`이 유지되어 영향받지 않는다.

---

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/libs/service-client` — `application/usecase/ManageOwnClientUseCase.java` | 신규 | 입력 포트 인터페이스. 도메인 의존성만 사용하므로 library 모듈에 위치 |
| `services/libs/service-client` — `application/service/ManageOwnClientService.java` | 신규 | 유스케이스 구현체. `GatewayRefreshClient`를 auth-api에서 주입하므로 `@Service` 없이 수동 @Bean 패턴 |
| `services/libs/service-client` — `application/domain/ServiceRoute.java` | 변경 | `registeredClientId` 필드(8번째) 추가 + 셀프 등록용 `create(pathPrefix, upstreamUrl, enabled, ownerId, registeredClientId)` 팩토리 추가 |
| `services/libs/service-client` — `application/service/RegisterOAuthClientService.java` | 변경 | `selfRegister`의 라우트 생성에 `clientId` 전달 (신규 셀프 라우트에 `registered_client_id` 채움) |
| `services/libs/service-client` — `application/repository/ServiceClientRepository.java` | 변경 | `findByOwnerId`, `findByClientIdAndOwnerId`, `deleteByClientId`, `updateClientName` 포트 메서드 추가 |
| `services/libs/service-client` — `application/repository/ServiceRouteRepository.java` | 변경 | `findByRegisteredClientId`, `findByRegisteredClientIdIn`, `deleteByRegisteredClientId` 포트 메서드 추가 |
| `services/libs/service-client` — `application/repository/SasClientRegistrar.java` | 변경 | `unregisterClient`, `updateClientName` 포트 메서드 추가 |
| `services/libs/service-client` — `exception/RouteNamespaceChangeException.java` | 신규 | 네임스페이스 불변 위반 전용 예외 (400 ROUTE_NAMESPACE_CHANGE_DENIED) |
| `services/libs/service-client` — `persistence/entity/ServiceRouteJpaEntity.java` | 변경 | `from()`/`fromWithId()`의 `registeredClientId = null` 고정 제거 → `route.registeredClientId()` 반영, `toDomain()`도 전달 |
| `services/libs/service-client` — `persistence/repository/ServiceClientJpaRepository.java` | 변경 | `findByOwnerId`, `findByRegisteredClientIdAndOwnerId`, `deleteByRegisteredClientId`, `updateClientNameByRegisteredClientId`(@Modifying) Spring Data 메서드 추가 |
| `services/libs/service-client` — `persistence/repository/ServiceClientRepositoryAdapter.java` | 변경 | 위 4개 포트 메서드 구현 추가 |
| `services/libs/service-client` — `persistence/repository/SasClientRegistrarAdapter.java` | 변경 | `unregisterClient`(JdbcTemplate), `updateClientName`(RegisteredClient rebuild) 구현 추가 |
| `services/libs/service-client` — `persistence/repository/ServiceRouteJpaRepository.java` | 변경 | `findByRegisteredClientId`, `findByRegisteredClientIdIn`, `deleteAllByRegisteredClientId` Spring Data 메서드 추가 |
| `services/libs/service-client` — `persistence/repository/ServiceRouteRepositoryAdapter.java` | 변경 | `findByRegisteredClientId`, `findByRegisteredClientIdIn`, `deleteByRegisteredClientId` 포트 메서드 구현 추가 |
| `services/apis/auth-api` — `presentation/controller/ClientController.java` | 변경 | GET(목록), GET/{clientId}(상세), PUT/{clientId}(수정), DELETE/{clientId}(삭제) 핸들러 4개 추가 |
| `services/apis/auth-api` — `presentation/docs/ClientApiDocs.java` | 변경 | 4개 신규 엔드포인트 Swagger 어노테이션 추가 |
| `services/apis/auth-api` — `presentation/dto/UpdateMyClientRequest.java` | 신규 | PUT 요청 DTO (SelfRegisterClientRequest와 동일 구조, 별도 클래스) |
| `services/apis/auth-api` — `presentation/dto/MyClientRouteInfo.java` | 신규 | 응답 내 라우트 정보 중첩 record |
| `services/apis/auth-api` — `presentation/dto/MyClientItemResponse.java` | 신규 | 목록 단건 / 상세 공용 응답 DTO |
| `services/apis/auth-api` — `presentation/dto/MyClientListResponse.java` | 신규 | 목록 응답 래퍼 DTO |
| `services/apis/auth-api` — `config/ApplicationServiceConfig.java` | 변경 | `ManageOwnClientService` `@Bean` 등록 추가 |
| `services/apis/auth-api` — `exception/GlobalExceptionHandler.java` | 변경 | `RouteNamespaceChangeException` → 400 핸들러 추가 |

---

### 구성 요소 설계

#### 모듈: `services/libs/service-client`

```
application/
├── usecase/
│   └── ManageOwnClientUseCase.java          — 목록/상세/수정/삭제 입력 포트
├── service/
│   ├── ManageOwnClientService.java          — 유스케이스 구현체
│   └── RegisterOAuthClientService.java      — (변경) selfRegister 라우트 생성에 clientId 전달
├── repository/
│   ├── ServiceClientRepository.java         — (변경) 소유자 조회/삭제/이름수정 포트 추가
│   ├── ServiceRouteRepository.java          — (변경) registeredClientId 기반 조회/삭제 포트 추가
│   └── SasClientRegistrar.java              — (변경) unregisterClient/updateClientName 추가
└── domain/
    └── ServiceRoute.java                    — (변경) registeredClientId 필드 + 셀프 등록 팩토리 추가

exception/
└── RouteNamespaceChangeException.java       — 네임스페이스 변경 시도 400

persistence/
├── entity/
│   └── ServiceRouteJpaEntity.java           — (변경) from/fromWithId/toDomain에 registeredClientId 반영
└── repository/
    ├── ServiceClientJpaRepository.java      — (변경) Spring Data 메서드 추가
    ├── ServiceClientRepositoryAdapter.java  — (변경) 포트 구현 추가
    ├── ServiceRouteJpaRepository.java       — (변경) registeredClientId 메서드 추가
    ├── ServiceRouteRepositoryAdapter.java   — (변경) registeredClientId 포트 구현 추가
    └── SasClientRegistrarAdapter.java       — (변경) unregisterClient/updateClientName 구현
```

---

##### ManageOwnClientUseCase
- **타입**: UseCase (입력 포트 인터페이스)
- **패키지**: `com.econo.auth.client.application.usecase`
- **책임**: 셀프 클라이언트 관리(목록/상세/수정/삭제) 입력 포트를 정의한다. Command/Result record를 내부에 포함한다.
- **내부 record 설계**:
  ```
  record MyClientResult(
      String clientId,           // service_client.registered_client_id
      String clientName,
      Set<String> redirectUris,  // SAS에서 조회
      String routeId,            // null 허용 — 라우트 없는 클라이언트
      String pathPrefix,         // null 허용
      String upstreamUrl,        // null 허용
      Boolean routeEnabled       // null 허용
  )

  record UpdateMyClientCommand(
      String clientId,
      Long ownerId,
      String clientName,
      Set<String> redirectUris,
      String pathPrefix,         // null이면 라우트 제거 의미
      String upstreamUrl         // null이면 라우트 제거 의미
  )

  record DeleteMyClientCommand(String clientId, Long ownerId)
  ```
- **메서드**:
  - `List<MyClientResult> listMyClients(Long ownerId)`
  - `MyClientResult getMyClient(String clientId, Long ownerId)`
  - `MyClientResult updateMyClient(UpdateMyClientCommand command)`
  - `void deleteMyClient(DeleteMyClientCommand command)`
- **적용 컨벤션**:
  - 인터페이스명 규칙: `{Action}UseCase` — `ManageOwnClientUseCase`
  - Command/Result는 인터페이스 내부 record로 선언 (기존 `RegisterOAuthClientUseCase` 패턴 미러링)
  - `presentation/controller`가 구현체(`ManageOwnClientService`)가 아닌 이 인터페이스에만 의존
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientUseCase.java:11`
- **연관 todo**: `[ ] ManageOwnClientUseCase 인터페이스 신설. 내부 Command/Result record 정의`

---

##### ManageOwnClientService
- **타입**: Service (유스케이스 구현체)
- **패키지**: `com.econo.auth.client.application.service`
- **책임**: `ManageOwnClientUseCase`를 구현하며, 소유권 검증·diff 처리·SAS 동기화·게이트웨이 refresh afterCommit을 단일 `@Transactional` 경계 안에서 조율한다.
- **주요 필드**:
  ```java
  private final ServiceClientRepository serviceClientRepository;
  private final ServiceRouteRepository serviceRouteRepository;
  private final SasClientRegistrar sasClientRegistrar;
  private final SasRedirectUriManager sasRedirectUriManager;
  private final GatewayRefreshClient gatewayRefreshClient;
  private final RouteValidator routeValidator;
  private final RouteNamespaceExtractor namespaceExtractor;
  ```
- **주요 메서드**:
  - `listMyClients(Long ownerId)` — `@Transactional(readOnly=true)`. `serviceClientRepository.findByOwnerId(ownerId)`로 클라이언트 목록 조회 → 각 clientId 모아 `serviceRouteRepository.findByRegisteredClientIdIn(clientIds)`로 라우트 IN-query 1회(N+1 방지) → `SasRedirectUriManager`로 redirectUris 조회 후 `MyClientResult` 변환.
  - `getMyClient(String clientId, Long ownerId)` — `@Transactional(readOnly=true)`. `serviceClientRepository.findByClientIdAndOwnerId(clientId, ownerId)` → empty이면 `throw new InvalidClientException()` → `serviceRouteRepository.findByRegisteredClientId(clientId)`로 라우트 조회 후 결과 반환.
  - `updateMyClient(UpdateMyClientCommand)` — `@Transactional`. 상세 흐름은 "호출 흐름 — PUT" 참조.
  - `deleteMyClient(DeleteMyClientCommand)` — `@Transactional`. 상세 흐름은 "호출 흐름 — DELETE" 참조.
  - `triggerRefresh()` — private. `ManageRouteService.triggerRefresh()` 패턴 그대로 복사.
- **의존성**: `ServiceClientRepository`, `ServiceRouteRepository`, `SasClientRegistrar`, `SasRedirectUriManager`, `GatewayRefreshClient`, `RouteValidator`, `RouteNamespaceExtractor`
- **적용 컨벤션**:
  - `@Slf4j` + `@RequiredArgsConstructor` (CONVENTION.md 2.2)
  - `@Service` 어노테이션 없음 — `ApplicationServiceConfig`에서 수동 `@Bean` 등록 (`RegisterOAuthClientService` 패턴 동일: `GatewayRefreshClient`가 auth-api 소속 빈이라 service-client 스캔 범위 밖)
  - 쓰기 메서드: `@Transactional` / 읽기 메서드: `@Transactional(readOnly = true)`
  - `afterCommit` refresh: `TransactionSynchronizationManager.registerSynchronization(...)` 패턴
  - 예외: 소유권 없으면 `InvalidClientException` throw (생성자 직접 호출 — 기존 `ClientRedirectUriService` 패턴과 동일. 정적 팩토리가 현재 미정의이므로 `new InvalidClientException()` 사용. 예외 클래스에 정적 팩토리 추가 시 이 서비스도 갱신)
- **참조할 기존 코드**:
  - `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java:90` (selfRegister 패턴)
  - `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageRouteService.java:155` (triggerRefresh 패턴)
- **연관 todo**: `[ ] ManageOwnClientService 클래스 신설`, `[ ] listMyClients 구현`, `[ ] getMyClient 구현`, `[ ] updateMyClient 구현`, `[ ] deleteMyClient 구현`, `[ ] triggerRefresh() private 헬퍼`

---

##### ServiceClientRepository (포트 확장)
- **타입**: Repository 아웃바운드 포트 (인터페이스)
- **패키지**: `com.econo.auth.client.application.repository`
- **추가 메서드**:
  ```java
  /** ownerId로 클라이언트 목록 조회 */
  List<ServiceClient> findByOwnerId(Long ownerId);

  /**
   * clientId + ownerId 복합 조회 — 소유권 검증 겸 단건 조회 (타인 소유 → empty로 404 존재 은닉)
   * @param clientId service_client.registered_client_id
   * @param ownerId  service_client.owner_id
   */
  Optional<ServiceClient> findByClientIdAndOwnerId(String clientId, Long ownerId);

  /** clientId로 hard delete */
  void deleteByClientId(String clientId);

  /**
   * clientName만 수정 (PUT diff용) — 불변 도메인 save 대신 JPQL UPDATE로 처리해 PK-less INSERT 방지
   * @param clientId service_client.registered_client_id
   * @param newName  새 클라이언트 이름
   */
  void updateClientName(String clientId, String newName);
  ```
- **`updateClientName`이 별도 포트인 이유**: `ServiceClient` 도메인은 불변이라 `save(ServiceClient)`로 덮어쓰면 PK(id) 없는 새 엔티티가 되어 INSERT가 발생할 수 있다. 따라서 어댑터는 `@Modifying @Query("UPDATE ... SET clientName WHERE registeredClientId = ?")`로 1회 UPDATE한다(아래 ServiceClientJpaRepository 참조).
- **적용 컨벤션**: Javadoc 필수 (public 인터페이스), `@param`·`@return` 태그 (CONVENTION.md 4.1)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceClientRepository.java:6`
- **연관 todo**: `[ ] ServiceClientRepository에 메서드 추가`

---

##### ServiceRouteRepository (포트 확장)
- **타입**: Repository 아웃바운드 포트 (인터페이스)
- **패키지**: `com.econo.auth.client.application.repository`
- **추가 메서드**:
  ```java
  /**
   * registeredClientId로 라우트 단건 조회 (클라이언트당 라우트 최대 1개 전제 — 상세/수정용)
   * @param registeredClientId service_client.registered_client_id (= 라우트의 소유 클라이언트)
   */
  Optional<ServiceRoute> findByRegisteredClientId(String registeredClientId);

  /**
   * registeredClientId 배치 조회 — N+1 방지, listMyClients용
   * @param registeredClientIds 조회 대상 clientId 목록
   */
  List<ServiceRoute> findByRegisteredClientIdIn(List<String> registeredClientIds);

  /** registeredClientId로 연결 라우트 hard delete (클라이언트 삭제·라우트 제거 캐스케이드) */
  void deleteByRegisteredClientId(String registeredClientId);
  ```
- **설계 결정 — Optional vs List**: 현재 셀프 등록(`RegisterOAuthClientService.selfRegister`)은 클라이언트당 라우트 최대 1개를 생성한다. `registered_client_id`는 클라이언트를 1:1로 식별하므로 `findByRegisteredClientId(String) → Optional<ServiceRoute>` 채택. 향후 1클라이언트 N라우트 지원 시 List로 전환.
- **`ownerId` 기반 조회를 쓰지 않는 이유**: 한 회원이 클라이언트를 여러 개 가지면 `owner_id`로는 라우트를 특정 클라이언트로 좁힐 수 없어, 수정·삭제 시 타 클라이언트의 라우트까지 건드린다(설계 전제 참조). 따라서 연관 조회·삭제는 전부 `registered_client_id` 기준이다.
- **N+1 방지**: 목록 조회(`listMyClients`)는 클라이언트 목록의 clientId들을 모아 `findByRegisteredClientIdIn(List<String>)`으로 IN-query 1회 처리한다.
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceRouteRepository.java:8`
- **연관 todo**: `[ ] ServiceRouteRepository 포트에 메서드 추가`, `[ ] 목록은 N+1 피하도록 findByRegisteredClientIdIn 등 배치 조회 고려`

---

##### ServiceRoute (도메인 record 확장)
- **타입**: 도메인 record
- **패키지**: `com.econo.auth.client.application.domain`
- **변경**: `registeredClientId` 필드(8번째)를 추가하고 셀프 등록용 5-인자 팩토리를 추가한다. 기존 6/7-인자 생성자와 어드민용 3-인자 `create`는 `registeredClientId = null`로 유지(어드민 라우트는 클라이언트 비연관 — V9 분리 의도 보존).
  ```java
  public record ServiceRoute(
      String routeId,
      String pathPrefix,
      String upstreamUrl,
      boolean enabled,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      Long ownerId,
      String registeredClientId) {   // ← 신규 (nullable)

    // 기존 6-인자 생성자: ownerId=null, registeredClientId=null
    // 기존 7-인자 생성자(+ownerId): registeredClientId=null 위임 추가
    // 어드민용: create(pathPrefix, upstreamUrl, enabled) → registeredClientId=null 유지

    /** 셀프 등록용 — ownerId + registeredClientId 포함 */
    public static ServiceRoute create(
        String pathPrefix, String upstreamUrl, boolean enabled, Long ownerId, String registeredClientId) {
      if (ownerId == null || ownerId <= 0) {
        throw new IllegalArgumentException("ownerId는 양수여야 합니다. ownerId=" + ownerId);
      }
      return new ServiceRoute(
          UUID.randomUUID().toString(), pathPrefix, upstreamUrl, enabled, null, null, ownerId, registeredClientId);
    }
  }
  ```
- **하위호환 주의**: 기존 7-인자 생성자 `new ServiceRoute(routeId, pathPrefix, upstreamUrl, enabled, createdAt, updatedAt, ownerId)`를 호출하는 코드(예: PUT 케이스 D의 라우트 재생성, `toDomain()`)는 8-인자로 갱신하거나, 7-인자 생성자를 `registeredClientId=null`로 위임하는 보조 생성자로 유지한다. **PUT 수정 케이스 D에서 라우트를 재저장할 때는 기존 `registeredClientId`를 보존**해야 하므로 8-인자 생성자를 직접 사용한다.
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceRoute.java:14`
- **연관 todo**: `[ ] ServiceRoute 도메인에 registeredClientId 필드/팩토리 추가`

---

##### ServiceRouteJpaEntity (변환 로직 변경)
- **타입**: JPA 엔티티
- **패키지**: `com.econo.auth.client.persistence.entity`
- **변경**: `from()`/`fromWithId()`의 `entity.registeredClientId = null` 고정을 제거하고 도메인 값을 반영, `toDomain()`도 `registeredClientId`를 전달한다.
  ```java
  // from() / fromWithId() 내부:
  entity.registeredClientId = route.registeredClientId();   // null 고정 → 도메인 값 반영

  // toDomain():
  return new ServiceRoute(
      routeId, pathPrefix, upstreamUrl, enabled, createdAt, updatedAt, ownerId, registeredClientId);
  ```
- **스키마 영향 없음**: 컬럼·`@Column` 매핑은 이미 존재(`@Column(name="registered_client_id", nullable=true, length=100)`)하므로 `ddl-auto=validate` 통과. db-design-plan 참조.
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/entity/ServiceRouteJpaEntity.java:63`
- **연관 todo**: `[ ] ServiceRouteJpaEntity.from/fromWithId/toDomain에 registeredClientId 반영`

---

##### RegisterOAuthClientService (selfRegister 라우트 생성 변경)
- **타입**: Service (기존 유스케이스 구현체)
- **패키지**: `com.econo.auth.client.application.service`
- **변경**: 셀프 등록 라우트 생성 시 `clientId`를 전달해 `registered_client_id`를 채운다(백필 전략 B — 신규 등록부터).
  ```java
  // 기존:
  serviceRouteRepository.save(ServiceRoute.create(pathPrefix, upstreamUrl, true, command.ownerId()));
  // 변경:
  serviceRouteRepository.save(ServiceRoute.create(pathPrefix, upstreamUrl, true, command.ownerId(), clientId));
  ```
- **영향 범위**: `selfRegister` 메서드 내 라우트 저장 1줄. `register`(비-셀프)·`triggerRefresh`는 무변경. 어드민 경로(`ManageRouteService`)는 3-인자 팩토리를 그대로 써 영향 없음.
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java:155`
- **연관 todo**: `[ ] selfRegister 라우트 생성에 clientId 전달`

---

##### SasClientRegistrar (포트 확장)
- **타입**: Repository 아웃바운드 포트 (인터페이스)
- **패키지**: `com.econo.auth.client.application.repository`
- **추가 메서드**:
  ```java
  /**
   * SAS oauth2_registered_client 하드 삭제
   *
   * <p>SAS {@link RegisteredClientRepository}는 표준 delete 메서드를 제공하지 않는다.
   * 구현체는 JdbcTemplate으로 직접 DELETE 쿼리를 실행한다 (SAS 1.x 테이블명 의존성 수반).
   *
   * @param clientId 삭제할 클라이언트 ID
   */
  void unregisterClient(String clientId);

  /**
   * SAS oauth2_registered_client 클라이언트 이름 수정
   *
   * @param clientId 수정할 클라이언트 ID
   * @param newName  새 클라이언트 이름
   */
  void updateClientName(String clientId, String newName);
  ```
- **연관 todo**: `[ ] SasClientRegistrar 포트에 메서드 추가`

---

##### RouteNamespaceChangeException
- **타입**: 도메인 예외
- **패키지**: `com.econo.auth.client.exception`
- **책임**: PUT 수정 시 pathPrefix의 네임스페이스가 기존과 달라졌을 때 발생. 400 `ROUTE_NAMESPACE_CHANGE_DENIED` 에 매핑.
- **설계**:
  ```java
  public class RouteNamespaceChangeException extends RuntimeException {
    public RouteNamespaceChangeException(String existingNamespace, String newNamespace) {
      super("네임스페이스는 변경할 수 없습니다. existing=" + existingNamespace + ", requested=" + newNamespace);
    }
  }
  ```
- **정적 팩토리 여부**: 기존 예외(`RouteNamespaceTakenException`, `RouteNamespaceInvalidException`, `InvalidClientException`)가 생성자 직접 사용 패턴이므로 이 예외도 동일하게 생성자 직접 사용. 단, todo에서 "정적 팩토리 패턴(CONVENTION.md 준수)"을 명시했으므로, 정적 팩토리 추가를 권장하나 기존 예외 패턴과의 일관성을 감안해 생성자 방식도 허용. **구현자가 둘 중 한 방향을 선택 후 나머지 예외들도 일괄 통일하는 것이 이상적.**
- **적용 컨벤션**: `{Domain}Exception` 네이밍 규칙에서 벗어나지 않음 (CONVENTION.md 1.2)
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/exception/RouteNamespaceTakenException.java:4`
- **연관 todo**: `[ ] RouteNamespaceChangeException 신설`

---

##### ServiceClientJpaRepository (확장)
- **타입**: Spring Data JPA 인터페이스
- **패키지**: `com.econo.auth.client.persistence.repository`
- **추가 메서드**:
  ```java
  List<ServiceClientJpaEntity> findByOwnerId(Long ownerId);

  Optional<ServiceClientJpaEntity> findByRegisteredClientIdAndOwnerId(
      String registeredClientId, Long ownerId);

  @Modifying
  @Query("DELETE FROM ServiceClientJpaEntity e WHERE e.registeredClientId = :registeredClientId")
  void deleteByRegisteredClientId(@Param("registeredClientId") String registeredClientId);

  @Modifying
  @Query("UPDATE ServiceClientJpaEntity e SET e.clientName = :newName WHERE e.registeredClientId = :clientId")
  void updateClientNameByRegisteredClientId(
      @Param("clientId") String clientId, @Param("newName") String newName);
  ```
- **주의**: `deleteByRegisteredClientId`·`updateClientNameByRegisteredClientId`는 `@Modifying`+JPQL. 불변 도메인 `save`로 인한 PK-less INSERT를 피해 1회 UPDATE/DELETE로 처리. 기존 `ServiceRouteJpaRepository.deleteByRouteId` 패턴과 동일.
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/ServiceRouteJpaRepository.java:60` (`deleteByRouteId` 패턴)
- **연관 todo**: `[ ] ServiceClientJpaRepository에 Spring Data 메서드 추가`

---

##### ServiceClientRepositoryAdapter (확장)
- **타입**: 아웃바운드 포트 구현 어댑터
- **패키지**: `com.econo.auth.client.persistence.repository`
- **추가 메서드 구현**:
  ```java
  @Override
  @Transactional(readOnly = true)
  public List<ServiceClient> findByOwnerId(Long ownerId) {
    return serviceClientJpaRepository.findByOwnerId(ownerId).stream()
        .map(ServiceClientJpaEntity::toDomain).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ServiceClient> findByClientIdAndOwnerId(String clientId, Long ownerId) {
    return serviceClientJpaRepository
        .findByRegisteredClientIdAndOwnerId(clientId, ownerId)
        .map(ServiceClientJpaEntity::toDomain);
  }

  @Override
  @Transactional
  public void deleteByClientId(String clientId) {
    serviceClientJpaRepository.deleteByRegisteredClientId(clientId);
  }

  @Override
  @Transactional
  public void updateClientName(String clientId, String newName) {
    serviceClientJpaRepository.updateClientNameByRegisteredClientId(clientId, newName);
  }
  ```
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/ServiceClientRepositoryAdapter.java:13`
- **연관 todo**: `[ ] ServiceClientRepositoryAdapter에 findByOwnerId, findByClientIdAndOwnerId, deleteByClientId, updateClientName 구현 추가`

---

##### SasClientRegistrarAdapter (확장)
- **타입**: 아웃바운드 포트 구현 어댑터
- **패키지**: `com.econo.auth.client.persistence.repository`
- **추가 의존성**: `JdbcTemplate` (SAS 1.x 테이블 직접 접근용)
- **`unregisterClient` 구현 결정 — JdbcTemplate vs 캐스팅**:
  - **캐스팅 방식**: `JdbcRegisteredClientRepository`로 다운캐스트 후 내부 메서드 리플렉션 접근 — SAS 내부 API 결합 위험, 유지보수 불안정.
  - **JdbcTemplate 방식**: `DELETE FROM oauth2_registered_client WHERE client_id = ?` 직접 실행 — SAS 1.x 테이블명/컬럼명에 의존하나 SQL이 명시적이어서 추적 가능. SAS 버전 업그레이드 시 테이블 스키마 변경이 있으면 어댑터 주석을 통해 즉시 인지 가능.
  - **채택**: JdbcTemplate 방식. 의존성 결합을 명시적 SQL 주석으로 문서화.
  ```java
  // SAS 1.x 기준: 테이블명 oauth2_registered_client, 컬럼명 client_id.
  // SAS 버전 업그레이드 시 이 쿼리 검증 필요.
  jdbcTemplate.update(
      "DELETE FROM oauth2_registered_client WHERE client_id = ?", clientId);
  ```
- **`updateClientName` 구현**:
  ```java
  // RegisteredClient.from(existing)으로 기존 설정 복사 후 clientName만 교체하여 save
  RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
  if (existing == null) return;
  RegisteredClient updated = RegisteredClient.from(existing).clientName(newName).build();
  registeredClientRepository.save(updated);
  ```
  (`SasRedirectUriManagerAdapter.updateRedirectUris` 패턴 미러링 — `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/SasRedirectUriManagerAdapter.java:41`)
- **연관 todo**: `[ ] SasClientRegistrarAdapter에 unregisterClient 구현 추가`, `[ ] SasClientRegistrarAdapter에 updateClientName 구현 추가`

---

##### ServiceRouteJpaRepository (확장)
- **타입**: Spring Data JPA 인터페이스
- **패키지**: `com.econo.auth.client.persistence.repository`
- **추가 메서드**:
  ```java
  Optional<ServiceRouteJpaEntity> findByRegisteredClientId(String registeredClientId);

  List<ServiceRouteJpaEntity> findByRegisteredClientIdIn(List<String> registeredClientIds);

  @Modifying
  @Query("DELETE FROM ServiceRouteJpaEntity e WHERE e.registeredClientId = :registeredClientId")
  void deleteAllByRegisteredClientId(@Param("registeredClientId") String registeredClientId);
  ```
- **인덱스**: `WHERE registered_client_id = ?`는 db-design-plan의 V13 `idx_service_route_registered_client_id`를 탄다.
- **연관 todo**: `[ ] ServiceRouteJpaRepository에 findByRegisteredClientId, deleteAllByRegisteredClientId 추가`

---

##### ServiceRouteRepositoryAdapter (확장)
- **타입**: 아웃바운드 포트 구현 어댑터
- **패키지**: `com.econo.auth.client.persistence.repository`
- **추가 메서드 구현**:
  ```java
  @Override
  @Transactional(readOnly = true)
  public Optional<ServiceRoute> findByRegisteredClientId(String registeredClientId) {
    return serviceRouteJpaRepository.findByRegisteredClientId(registeredClientId)
        .map(ServiceRouteJpaEntity::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ServiceRoute> findByRegisteredClientIdIn(List<String> registeredClientIds) {
    return serviceRouteJpaRepository.findByRegisteredClientIdIn(registeredClientIds).stream()
        .map(ServiceRouteJpaEntity::toDomain).toList();
  }

  @Override
  @Transactional
  public void deleteByRegisteredClientId(String registeredClientId) {
    serviceRouteJpaRepository.deleteAllByRegisteredClientId(registeredClientId);
  }
  ```
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/ServiceRouteRepositoryAdapter.java:16`
- **연관 todo**: `[ ] ServiceRouteRepositoryAdapter에 findByRegisteredClientId, deleteByRegisteredClientId 구현 추가`

---

#### 모듈: `services/apis/auth-api`

```
presentation/
├── controller/
│   └── ClientController.java        — (변경) 핸들러 4개 추가
├── docs/
│   └── ClientApiDocs.java           — (변경) Swagger @Operation 4개 추가
└── dto/
    ├── UpdateMyClientRequest.java   — 신규: PUT 요청 DTO
    ├── MyClientRouteInfo.java       — 신규: 응답 내 라우트 정보 record
    ├── MyClientItemResponse.java    — 신규: 목록 단건 / 상세 공용 응답 DTO
    └── MyClientListResponse.java    — 신규: 목록 응답 래퍼

config/
└── ApplicationServiceConfig.java   — (변경) ManageOwnClientService @Bean 추가

exception/
└── GlobalExceptionHandler.java     — (변경) RouteNamespaceChangeException 핸들러 추가
```

---

##### ClientController (변경)
- **타입**: Controller
- **패키지**: `com.econo.auth.api.presentation.controller`
- **추가 핸들러**:
  ```java
  @Override
  @GetMapping
  public ResponseEntity<MyClientListResponse> listMyClients(@PassportAuth Passport passport) { ... }

  @Override
  @GetMapping("/{clientId}")
  public ResponseEntity<MyClientItemResponse> getMyClient(
      @PassportAuth Passport passport, @PathVariable String clientId) { ... }

  @Override
  @PutMapping("/{clientId}")
  public ResponseEntity<MyClientItemResponse> updateMyClient(
      @PassportAuth Passport passport,
      @PathVariable String clientId,
      @Valid @RequestBody UpdateMyClientRequest request) { ... }

  @Override
  @DeleteMapping("/{clientId}")
  public ResponseEntity<Void> deleteMyClient(
      @PassportAuth Passport passport, @PathVariable String clientId) { ... }
  ```
- **의존성 추가**: `ManageOwnClientUseCase` 주입 (기존 `RegisterOAuthClientUseCase`와 병행)
- **적용 컨벤션**:
  - `implements ClientApiDocs` — Swagger 어노테이션은 `ClientApiDocs` 인터페이스에 격리, 컨트롤러에는 `@Override`만 (CONVENTION.md 8.1)
  - `@RequiredArgsConstructor` 유지 (final 필드 추가 시 자동 반영)
  - `DELETE` → `ResponseEntity.noContent().build()` (204)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ClientController.java:31`
- **연관 todo**: `[ ] ClientController에 핸들러 메서드 4개 추가`

---

##### UpdateMyClientRequest
- **타입**: DTO (요청)
- **패키지**: `com.econo.auth.api.presentation.dto`
- **설계**:
  ```java
  public record UpdateMyClientRequest(
      @NotBlank @Schema(description = "OAuth 클라이언트 이름") String clientName,
      @NotNull @Schema(description = "허용 리다이렉트 URI 목록") Set<String> redirectUris,
      @Schema(description = "라우트 경로 접두사", nullable = true) String pathPrefix,
      @Schema(description = "업스트림 서비스 URL", nullable = true) String upstreamUrl) {

    @Schema(hidden = true)
    @AssertTrue(message = "pathPrefix와 upstreamUrl은 둘 다 있거나 둘 다 없어야 합니다.")
    public boolean isRouteFields() { ... }  // SelfRegisterClientRequest와 동일 로직
  }
  ```
- **SelfRegisterClientRequest와의 분리 이유**: 용도가 다르고(POST 등록 vs PUT 수정), 향후 PUT 전용 필드(예: enabled 직접 제어) 추가 시 분리가 용이.
- **적용 컨벤션**: `@Schema` 필드별 필수 (CONVENTION.md 8.7), Bean Validation 어노테이션 (2.5)
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/SelfRegisterClientRequest.java:10`
- **연관 todo**: `[ ] UpdateMyClientRequest 작성`

---

##### MyClientRouteInfo
- **타입**: DTO (중첩 record)
- **패키지**: `com.econo.auth.api.presentation.dto`
- **설계**:
  ```java
  public record MyClientRouteInfo(
      @Schema(nullable = true) String routeId,
      @Schema(nullable = true) String pathPrefix,
      @Schema(nullable = true) String upstreamUrl,
      @Schema(nullable = true) Boolean enabled) {}
  ```
- **null 허용**: 라우트 없는 클라이언트는 route 필드 자체를 null로 응답 (MyClientItemResponse.route = null)
- **연관 todo**: `[ ] MyClientRouteInfo 중첩 record/클래스 작성`

---

##### MyClientItemResponse
- **타입**: DTO (응답 — 목록 단건 / 상세 공용)
- **패키지**: `com.econo.auth.api.presentation.dto`
- **설계**:
  ```java
  public record MyClientItemResponse(
      String clientId,
      String clientName,
      Set<String> redirectUris,
      @Schema(nullable = true) MyClientRouteInfo route) {}
  ```
- **`MyClientDetailResponse` 통합 결정**: todo에서 "동일 구조이면 통합 가능"으로 열어 뒀으므로 `MyClientItemResponse` 단일 클래스로 상세/목록 모두 커버. 별도 클래스를 만들지 않는다.
- **연관 todo**: `[ ] MyClientItemResponse 작성`, `[ ] MyClientDetailResponse 작성`

---

##### MyClientListResponse
- **타입**: DTO (응답 래퍼)
- **패키지**: `com.econo.auth.api.presentation.dto`
- **설계**:
  ```java
  public record MyClientListResponse(List<MyClientItemResponse> clients) {}
  ```
- **연관 todo**: `[ ] MyClientListResponse 작성`

---

##### ClientApiDocs (변경)
- **타입**: Swagger 문서 인터페이스
- **패키지**: `com.econo.auth.api.presentation.docs`
- **추가 메서드 시그니처** (컨트롤러와 동일):
  ```java
  ResponseEntity<MyClientListResponse> listMyClients(Passport passport);
  ResponseEntity<MyClientItemResponse> getMyClient(Passport passport, String clientId);
  ResponseEntity<MyClientItemResponse> updateMyClient(Passport passport, String clientId, UpdateMyClientRequest request);
  ResponseEntity<Void> deleteMyClient(Passport passport, String clientId);
  ```
- **Swagger 에러코드 매핑** (각 `@ApiResponse` `description` 테이블):
  - GET 목록/상세: 200, 401(`AUTH_UNAUTHORIZED`), 404(`CLIENT_NOT_FOUND`)
  - PUT: 200, 400(`ROUTE_NAMESPACE_CHANGE_DENIED`·`ROUTE_NAMESPACE_INVALID`·`ROUTE_UPSTREAM_INVALID`·`VALIDATION_FAILED`), 401, 403(`ROUTE_NAMESPACE_TAKEN`·`ROUTE_PROTECTED`), 404(`CLIENT_NOT_FOUND`), 409(`ROUTE_PATH_CONFLICT`)
  - DELETE: 204, 401, 404(`CLIENT_NOT_FOUND`)
- **적용 컨벤션**: Swagger 어노테이션은 인터페이스에만 (CONVENTION.md 8.1), `@Tag(name = "Client")` 기존 태그 유지
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ClientApiDocs.java:17`
- **연관 todo**: `[ ] ClientApiDocs 인터페이스에 4개 신규 엔드포인트 Swagger 어노테이션 추가`

---

##### ApplicationServiceConfig (변경)
- **타입**: Config
- **패키지**: `com.econo.auth.api.config`
- **추가 @Bean**:
  ```java
  @Bean
  public ManageOwnClientService manageOwnClientService(
      ServiceClientRepository serviceClientRepository,
      ServiceRouteRepository serviceRouteRepository,
      SasClientRegistrar sasClientRegistrar,
      SasRedirectUriManager sasRedirectUriManager,
      GatewayRefreshClient gatewayRefreshClient,
      RouteValidator routeValidator) {
    return new ManageOwnClientService(
        serviceClientRepository,
        serviceRouteRepository,
        sasClientRegistrar,
        sasRedirectUriManager,
        gatewayRefreshClient,
        routeValidator,
        new RouteNamespaceExtractor());
  }
  ```
- **SasRedirectUriManager 빈 충돌 확인**: `SasRedirectUriManagerAdapter`는 `@Component`로 자동 등록. `ClientRedirectUriService`가 `@Service`로 `SasRedirectUriManager`를 주입하며, 이 `@Bean`도 동일 타입 주입. Spring이 타입으로 단일 구현체를 찾으므로 충돌 없음. 단, `ApplicationServiceConfig`에서 수동 생성 시 Spring 컨텍스트의 `SasRedirectUriManager` 빈을 파라미터로 받으면 된다.
- **적용 컨벤션**: 수동 `@Bean` 패턴 (CONVENTION.md 1.1 "일반 config/ 와이어링 클래스는 application.repository와 application.service를 참조해도 됨")
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java:111`
- **연관 todo**: `[ ] ApplicationServiceConfig에 ManageOwnClientService @Bean 등록`

---

##### GlobalExceptionHandler (변경)
- **타입**: 전역 예외 핸들러
- **패키지**: `com.econo.auth.api.exception`
- **추가 핸들러**:
  ```java
  @ExceptionHandler(RouteNamespaceChangeException.class)
  public ResponseEntity<ApiError> handleRouteNamespaceChange(RouteNamespaceChangeException ex) {
    return ResponseEntity.badRequest()
        .body(new ApiError("ROUTE_NAMESPACE_CHANGE_DENIED", ex.getMessage()));
  }
  ```
- **InvalidClientException 재사용 확인**: 이미 `handleInvalidClient` → 404 `CLIENT_NOT_FOUND` 핸들러가 존재 (GlobalExceptionHandler.java:117). 소유권 검증 실패 시 `new InvalidClientException()` throw → 기존 핸들러 자동 처리. **추가 핸들러 불필요.**
- **SecurityConfig 확인**: `GET/PUT/DELETE /api/v1/clients/**` 경로는 `@PassportAuth`가 Passport 검증을 담당. Spring Security URL 패턴 레벨에서는 별도 permitAll/authenticated 설정 불필요할 가능성 높음 (기존 POST와 같은 패턴). 구현 시 `SecurityConfig`에서 `/api/v1/clients/**` 경로 허용 여부 확인 필요.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java:260`
- **연관 todo**: `[ ] GlobalExceptionHandler에 RouteNamespaceChangeException → 400 핸들러 추가`

---

### 호출 흐름

#### GET /api/v1/clients (목록 조회)

```
ClientController.listMyClients(@PassportAuth Passport)
  → ManageOwnClientUseCase.listMyClients(passport.getMemberId())
    → ManageOwnClientService.listMyClients(ownerId) [@Transactional(readOnly=true)]
      → ServiceClientRepository.findByOwnerId(ownerId)               # service_client WHERE owner_id=?
      → ServiceRouteRepository.findByRegisteredClientIdIn(clientIds) # IN-query 1회 — N+1 방지
      → SasRedirectUriManager.findRedirectUrisByClientId(clientId) * N회  # SAS 조회
        (※ redirectUris 조회가 N회이므로 성능 주의. 현재 수용 — 향후 SAS 캐시/배치 고려)
      → List<MyClientResult> 반환
  → MyClientListResponse(items) → 200 OK
```

예외 경로:
```
목록이 비어있어도 200 OK + 빈 리스트 반환 (예외 없음)
```

---

#### GET /api/v1/clients/{clientId} (상세 조회)

```
ClientController.getMyClient(@PassportAuth Passport, @PathVariable clientId)
  → ManageOwnClientUseCase.getMyClient(clientId, passport.getMemberId())
    → ManageOwnClientService.getMyClient(clientId, ownerId) [@Transactional(readOnly=true)]
      → ServiceClientRepository.findByClientIdAndOwnerId(clientId, ownerId)
          → empty → throw new InvalidClientException()
                   → GlobalExceptionHandler.handleInvalidClient → 404 CLIENT_NOT_FOUND
          → present → ServiceRouteRepository.findByRegisteredClientId(clientId)
                     → SasRedirectUriManager.findRedirectUrisByClientId(clientId)
                     → MyClientResult 반환
  → MyClientItemResponse → 200 OK
```

---

#### PUT /api/v1/clients/{clientId} (수정)

```
ClientController.updateMyClient(@PassportAuth Passport, @PathVariable clientId, @Valid @RequestBody UpdateMyClientRequest)
  → Bean Validation 실패 → 400 VALIDATION_FAILED
  → ManageOwnClientUseCase.updateMyClient(UpdateMyClientCommand)
    → ManageOwnClientService.updateMyClient(command) [@Transactional]

    [1단계] 소유권 검증
      → ServiceClient existing = ServiceClientRepository.findByClientIdAndOwnerId(clientId, ownerId)
          → empty → throw new InvalidClientException() → 404 CLIENT_NOT_FOUND

    [2단계] clientName diff
      → existing.clientName() 과 command.clientName() 비교 (1단계에서 이미 로드 — 추가 조회 불필요)
      → 변경됐으면:
          → serviceClientRepository.updateClientName(clientId, newName)   # JPQL UPDATE (PK-less INSERT 방지)
          → SasClientRegistrar.updateClientName(clientId, newName)         # SAS oauth2_registered_client 동기화

    [3단계] redirectUris diff
      → SasRedirectUriManager.findRedirectUrisByClientId(clientId)  # 현재 uris 조회
      → 변경됐으면:
          → SasRedirectUriManager.updateRedirectUris(clientId, command.redirectUris())

    [4단계] 라우트 diff (4가지 케이스)
      → ServiceRouteRepository.findByRegisteredClientId(clientId) → existing 라우트

      케이스 A: existing 없음 + 요청 라우트 없음 → no-op
      케이스 B: existing 없음 + 요청 라우트 있음 (pathPrefix+upstreamUrl) →
          → RouteNamespaceExtractor.extract(pathPrefix)  → RouteNamespaceInvalidException (400) 가능
          → ServiceRouteRepository.findNamespaceOwner(namespace)
              → 타인 소유 → throw RouteNamespaceTakenException → 403
          → RouteValidator.validateUpstreamUrl(upstreamUrl) → RouteUpstreamInvalidException (400) 가능
          → RouteValidator.validatePathPrefix(pathPrefix)   → RouteProtectedException (403) / RoutePathConflictException (409) 가능
          → ServiceRouteRepository.save(ServiceRoute.create(pathPrefix, upstreamUrl, true, ownerId, clientId))
          → triggerRefresh()  # afterCommit 등록
      케이스 C: existing 있음 + 요청 라우트 없음 →
          → ServiceRouteRepository.deleteByRegisteredClientId(clientId)
          → triggerRefresh()
      케이스 D: existing 있음 + 요청 라우트 있음 →
          → RouteNamespaceExtractor.extract(pathPrefix) → newNamespace
          → RouteNamespaceExtractor.extract(existing.pathPrefix()) → existingNamespace
          → newNamespace != existingNamespace → throw new RouteNamespaceChangeException(existingNamespace, newNamespace) → 400
          → RouteValidator.validateUpstreamUrl(newUpstreamUrl)
          → RouteValidator.validatePathPrefixForUpdate(newPathPrefix, existing.routeId())  # 자신 제외 중복 검사
          → ServiceRouteRepository.save(new ServiceRoute(existing.routeId(), newPathPrefix, newUpstreamUrl, existing.enabled(), existing.createdAt(), null, ownerId, clientId))  # registeredClientId 보존
          → triggerRefresh()

    → MyClientResult 반환 (업데이트 후 최종 상태)
  → MyClientItemResponse → 200 OK
```

> **clientName 업데이트 처리 방식**: `ServiceClient` 도메인이 불변이라 `save(ServiceClient)`로 덮어쓰면 `ServiceClientJpaEntity.from`이 PK(id) 없는 엔티티를 만들어 INSERT가 발생할 수 있다. 이를 피하려고 `ServiceClientRepository.updateClientName` 포트 + `ServiceClientJpaRepository.updateClientNameByRegisteredClientId`(@Modifying JPQL UPDATE)로 1회 UPDATE 처리한다. SAS 측 이름은 `SasClientRegistrar.updateClientName`으로 동기화한다. (정의는 위 "ServiceClientRepository (포트 확장)" / "ServiceClientJpaRepository (확장)" / "SasClientRegistrarAdapter (확장)" 섹션 참조)

---

#### DELETE /api/v1/clients/{clientId} (삭제)

```
ClientController.deleteMyClient(@PassportAuth Passport, @PathVariable clientId)
  → ManageOwnClientUseCase.deleteMyClient(DeleteMyClientCommand)
    → ManageOwnClientService.deleteMyClient(command) [@Transactional]

    [1단계] 소유권 검증
      → ServiceClientRepository.findByClientIdAndOwnerId(clientId, ownerId)
          → empty → throw new InvalidClientException() → 404 CLIENT_NOT_FOUND

    [2단계] 라우트 캐스케이드 삭제
      → ServiceRouteRepository.findByRegisteredClientId(clientId)
          → present → ServiceRouteRepository.deleteByRegisteredClientId(clientId)
                      triggerRefresh()  # afterCommit 등록
          → empty → no-op (refresh 없음)

    [3단계] service_client 삭제
      → ServiceClientRepository.deleteByClientId(clientId)

    [4단계] SAS 삭제
      → SasClientRegistrar.unregisterClient(clientId)
        (JdbcTemplate: DELETE FROM oauth2_registered_client WHERE client_id = ?)

    → 트랜잭션 커밋 → afterCommit: gatewayRefreshClient.triggerRefresh()

  → 204 No Content
```

예외 경로:
```
[2단계] 이미 라우트가 없어도 정상 처리 (404 아님)
[4단계] SAS unregister 실패 시 → RuntimeException → 트랜잭션 롤백 (단계 3도 롤백)
        단, refresh 실패는 log.warn만 (롤백 없음, catch 처리)
```

---

#### afterCommit refresh 패턴

```java
private void triggerRefresh() {
  if (TransactionSynchronizationManager.isSynchronizationActive()) {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            doTriggerRefresh();
          }
        });
  } else {
    doTriggerRefresh();  // 트랜잭션 없는 단위 테스트 환경 대응
  }
}

private void doTriggerRefresh() {
  try {
    gatewayRefreshClient.triggerRefresh();
  } catch (Exception e) {
    log.warn("Gateway refresh 트리거 실패: {}", e.getMessage());
  }
}
```

참조: `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageRouteService.java:155`

---

### 컨벤션 준수 항목

- **네이밍**:
  - UseCase 인터페이스: `ManageOwnClientUseCase` (`{Action}UseCase` 규칙)
  - 서비스 구현체: `ManageOwnClientService` (`{Action}Service` 규칙)
  - 예외: `RouteNamespaceChangeException` (`{Domain}Exception` 규칙 — 예외적으로 `Route` 도메인 사용)
  - DTO: `UpdateMyClientRequest`, `MyClientItemResponse`, `MyClientListResponse`, `MyClientRouteInfo` (PascalCase + 역할 접미사)
  - JPA Repository: `ServiceClientJpaRepository`, `ServiceRouteJpaRepository` (`{Name}JpaRepository`)
  - 어댑터: `ServiceClientRepositoryAdapter`, `ServiceRouteRepositoryAdapter`, `SasClientRegistrarAdapter` (`{Name}RepositoryAdapter` / `{Algo}{Role}Adapter`)

- **의존성 주입**:
  - `ManageOwnClientService`: `@Slf4j` + `@RequiredArgsConstructor` (CONVENTION.md 2.2)
  - `ApplicationServiceConfig`에서 수동 `@Bean` 등록 (CLAUDE.md 아키텍처 패턴: GatewayRefreshClient 주입 필요)
  - 컨트롤러는 `ManageOwnClientUseCase` 인터페이스에만 의존 (구현체 직접 참조 금지, CONVENTION.md 1.1)

- **예외 처리**:
  - 소유권 없음 → `new InvalidClientException()` → 기존 404 핸들러 재사용 (존재 은닉 목적)
  - 네임스페이스 변경 → `new RouteNamespaceChangeException(existing, requested)` → 새 400 핸들러
  - 라우트 유효성 → 기존 `RouteUpstreamInvalidException`(400), `RouteProtectedException`(403), `RoutePathConflictException`(409) 재사용
  - gateway refresh 실패 → log.warn, 예외 미전파 (최종 일관성 수용)

- **불변성**:
  - `ServiceClient`, `ServiceRoute` 도메인은 불변 record/class — 수정은 새 인스턴스 생성 또는 JPQL UPDATE 직접 실행
  - DTO는 record 사용 (`MyClientItemResponse`, `MyClientRouteInfo`, `UpdateMyClientRequest`, `MyClientListResponse`)
  - 컬렉션: `Set<String> redirectUris`는 `Set.copyOf()` 또는 unmodifiable 처리 권장

- **트랜잭션**:
  - 읽기 전용 메서드: `@Transactional(readOnly = true)`
  - 쓰기 메서드: `@Transactional` (단일 경계 — service_client + service_route + SAS가 동일 DataSource)
  - afterCommit에서만 gateway refresh 트리거 (커밋 전 트리거 금지)

- **API 문서화**:
  - Swagger 어노테이션은 `ClientApiDocs` 인터페이스에만 (CONVENTION.md 8.1)
  - `@PassportAuth Passport` 파라미터는 `SpringDocUtils.addRequestWrapperToIgnore(Passport.class)`로 이미 전역 숨김 처리됨 (확인 후 추가 불필요)
  - 각 필드 `@Schema(description, example, nullable)` 필수 (CONVENTION.md 8.7)
  - 보안: `@SecurityRequirement(name = "cookieAuth")` 적용 (CONVENTION.md 8.4)

- **테스트 패턴**:
  - `ManageOwnClientServiceTest`: `@ExtendWith(MockitoExtension.class)`, `@Nested` + `@DisplayName` 한글, Given-When-Then 주석 (CONVENTION.md 5.1, 5.2)
  - JPA 슬라이스: `@DataJpaTest` + Testcontainers PostgreSQL + `@Import(어댑터 클래스)` (CONVENTION.md 5.3)
  - 컨트롤러: MockMvc `@SpringBootTest` (기존 `ClientControllerTest` 확장)

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨 (service-client lib: application/persistence 계층, auth-api: presentation/config 계층)
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두)

---

## 참고
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java` — selfRegister 패턴 (라우트 upsert + afterCommit 원자성)
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageRouteService.java` — afterCommit triggerRefresh 패턴
- `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/SasRedirectUriManagerAdapter.java` — RegisteredClient.from() rebuild 패턴
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — 수동 @Bean 등록 패턴
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 예외 핸들러 패턴
- `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ClientApiDocs.java` — Swagger 문서 분리 패턴
- `db/migration/V7__add_owner_id_to_service_client.sql` — `idx_service_client_owner_id` (service_client `findByOwnerId` 성능 보장)
- `db/migration/V13__add_index_service_route_registered_client_id.sql` — `idx_service_route_registered_client_id` (라우트 `findByRegisteredClientId`/`deleteByRegisteredClientId` 성능 보장 — db-design-plan 신규)
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceRoute.java` — registeredClientId 필드/팩토리 추가 대상
- `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/entity/ServiceRouteJpaEntity.java` — from/fromWithId/toDomain registeredClientId 반영 대상
