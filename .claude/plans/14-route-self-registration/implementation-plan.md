# route-self-registration - implementation

## 메타
- **작업명**: route-self-registration
- **문서 타입**: implementation
- **작성일**: 2026-06-21 (재작성)
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - db-design-plan.md

## 개요

별도 `/api/v1/routes` CRUD 엔드포인트 설계를 폐기하고, 기존 셀프 클라이언트 등록 흐름(`POST /api/v1/clients`)에 라우트 생성을 흡수한다. `pathPrefix`/`upstreamUrl` 두 필드를 모두 제공하면 동일 `@Transactional` 경계 안에서 `ServiceRoute`를 원자적으로 생성하고 커밋 후 게이트웨이 refresh를 트리거한다. 이전 설계 산출물(`SelfManageRouteService`, `SelfManageRouteUseCase`, 관련 예외·컨트롤러·DTO·테스트)은 전량 삭제한다. Java 21, Spring Boot 3.2.2, Gradle 멀티모듈(service-client lib + auth-api app) 위에서 3계층(presentation/application/persistence) + 계층별 DIP 아키텍처를 따른다.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 / 삭제 | 사유 |
|---|---|---|
| `services/libs/service-client/.../application/usecase/RegisterOAuthClientUseCase` | 변경 | `SelfRegisterOAuthClientCommand`에 `pathPrefix`/`upstreamUrl` 추가, `SelfRegisterOAuthClientResult`에 라우트 결과 필드 추가 |
| `services/libs/service-client/.../application/service/RegisterOAuthClientService` | 변경 | 라우트 분기 + afterCommit refresh 추가; `@Service` 제거 → 수동 `@Bean` 전환 |
| `services/libs/service-client/.../application/service/SelfManageRouteService` | **삭제** | 라우트 등록 기능이 `RegisterOAuthClientService`에 흡수됨 |
| `services/libs/service-client/.../application/usecase/SelfManageRouteUseCase` | **삭제** | 참조처 없어짐 |
| `services/libs/service-client/.../exception/RouteAccessDeniedException` | **삭제** | 소유권 분기 없어짐 |
| `services/libs/service-client/.../exception/RouteNamespaceImmutableException` | **삭제** | 수정 흐름 없어짐 |
| `services/libs/service-client/.../exception/RouteQuotaExceededException` | **삭제** | 1클라이언트=1라우트 정책으로 쿼터 개념 폐기 |
| `services/libs/service-client/.../application/repository/ServiceRouteRepository` | 변경 | `findAllByOwnerId`·`countByOwnerId` 메서드 제거 |
| `services/libs/service-client/.../persistence/repository/ServiceRouteRepositoryAdapter` | 변경 | 위 두 메서드 구현 제거 |
| `services/libs/service-client/.../persistence/repository/ServiceRouteJpaRepository` | 변경 | 위 두 메서드 쿼리 제거 |
| `services/apis/auth-api/.../presentation/dto/SelfRegisterClientRequest` | 변경 | `pathPrefix`/`upstreamUrl` 필드 + 클래스 레벨 `@AssertTrue` |
| `services/apis/auth-api/.../presentation/dto/SelfRegisterClientResponse` | 변경 | `routeId`/`pathPrefix`/`upstreamUrl`/`enabled` nullable 필드 추가 |
| `services/apis/auth-api/.../presentation/controller/ClientController` | 변경 | Command/Result 라우트 필드 전달 |
| `services/apis/auth-api/.../presentation/docs/ClientApiDocs` | 변경 | 라우트 관련 응답 코드 추가 |
| `services/apis/auth-api/.../presentation/controller/RouteController` | **삭제** | |
| `services/apis/auth-api/.../presentation/docs/RouteApiDocs` | **삭제** | |
| `services/apis/auth-api/.../presentation/dto/SelfCreateRouteRequest` | **삭제** | |
| `services/apis/auth-api/.../presentation/dto/SelfUpdateRouteRequest` | **삭제** | |
| `services/apis/auth-api/.../exception/GlobalExceptionHandler` | 변경 | `RouteAccessDeniedException`·`RouteNamespaceImmutableException`·`RouteQuotaExceededException` 핸들러 3개 제거 |
| `services/apis/auth-api/.../config/ApplicationServiceConfig` | 변경 | `selfManageRouteService` `@Bean` 제거; `registerOAuthClientService` 수동 `@Bean` 추가 |
| `services/apis/auth-api/.../config/security/SecurityConfig` | 변경 | `.requestMatchers("/api/v1/routes/**").permitAll()` 블록 제거 |
| `services/apis/auth-api/.../config/ProtectedPathPolicyImpl` | 변경 | `"/api/v1/routes/**"` 항목 제거 |
| `services/apis/api-gateway/.../config/GatewayRoutingConfig` | 변경 | `auth-routes` 정적 라우트 항목 제거 |
| `services/apis/auth-api/.../presentation/controller/ClientControllerTest` | 변경 | 라우트 흡수 케이스 추가 |
| `services/libs/service-client/.../application/service/RegisterOAuthClientServiceTest` | 변경 | 라우트 분기 케이스 추가 + 신규 의존성 Mock 추가 |
| `services/apis/auth-api/.../presentation/controller/RouteControllerTest` | **삭제** | |
| `services/libs/service-client/.../application/service/SelfManageRouteServiceTest` | **삭제** | |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/libs/service-client`

```
application/
├── usecase/
│   └── RegisterOAuthClientUseCase       — Command/Result record 확장
└── service/
    └── RegisterOAuthClientService       — 라우트 분기 + afterCommit refresh 추가; @Service 제거
application/repository/
└── ServiceRouteRepository               — findAllByOwnerId·countByOwnerId 제거
persistence/repository/
├── ServiceRouteRepositoryAdapter        — 대응 메서드 구현 제거
└── ServiceRouteJpaRepository            — 대응 쿼리 제거
```

##### RegisterOAuthClientUseCase (변경)
- **타입**: Use Case 입력 포트 인터페이스
- **책임**: `SelfRegisterOAuthClientCommand`에 라우트 옵션 필드를, `SelfRegisterOAuthClientResult`에 라우트 결과 필드를 추가한다.
- **변경 내용**:
  - `SelfRegisterOAuthClientCommand` record 확장 — 기존 3개 필드 유지, 뒤에 신규 필드 추가:
    ```
    record SelfRegisterOAuthClientCommand(
        String clientName,
        Set<String> redirectUris,
        Long ownerId,
        String pathPrefix,    // 신규 — null 허용
        String upstreamUrl    // 신규 — null 허용
    ) {}
    ```
  - `SelfRegisterOAuthClientResult` record 확장 — 기존 2개 필드 유지, 뒤에 신규 필드 추가:
    ```
    record SelfRegisterOAuthClientResult(
        String clientId,
        String clientSecret,
        String routeId,       // 신규 — 라우트 미생성 시 null
        String pathPrefix,    // 신규 — 라우트 미생성 시 null
        String upstreamUrl,   // 신규 — 라우트 미생성 시 null
        Boolean enabled       // 신규 — 라우트 미생성 시 null
    ) {}
    ```
- **주의**: `SelfRegisterOAuthClientResult(String clientId, String clientSecret)` 2인자 생성자는 기존 테스트 호환을 위해 compact constructor 또는 static factory로 유지하거나, 기존 테스트를 일괄 수정한다. record는 canonical constructor만 자동 생성되므로, 기존 `new SelfRegisterOAuthClientResult(clientId, secret)` 호출부를 `new SelfRegisterOAuthClientResult(clientId, secret, null, null, null, null)`로 수정해야 한다.
- **적용 컨벤션**: Javadoc `@param` 추가 필수.
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientUseCase.java:35-44`
- **연관 todo**: `[ ] RegisterOAuthClientUseCase에 라우트 포함 셀프 등록 지원을 위한 SelfRegisterOAuthClientCommand 확장`, `[ ] SelfRegisterOAuthClientResult 확장`

---

##### RegisterOAuthClientService (변경)
- **타입**: Use Case 구현 서비스
- **책임**: 셀프 클라이언트 등록 시 라우트 필드가 둘 다 non-blank이면 동일 트랜잭션에서 `ServiceRoute`를 생성하고 커밋 후 게이트웨이 refresh를 트리거한다.
- **빈 등록 방식 결정 — 수동 `@Bean` 전환 (선택지 A)**:
  - `@Service` 어노테이션 **제거**. `ServiceClientAutoConfiguration`의 `@ComponentScan("com.econo.auth.client")`에 포함되어 자동 등록되던 방식을 중단한다.
  - `GatewayRefreshClient` 구현체(`GatewayRefreshClientImpl`)가 auth-api 모듈 소속 빈이어서 service-client 스캔 범위 밖이므로, `ApplicationServiceConfig`에서 명시적으로 와이어링하는 수동 `@Bean` 방식이 유일하게 안전하다. (`ManageRouteService` 동일 패턴)
- **신규 final 필드**: `ServiceRouteRepository serviceRouteRepository`, `GatewayRefreshClient gatewayRefreshClient`, `RouteValidator routeValidator`, `RouteNamespaceExtractor namespaceExtractor`
- **`selfRegister` 메서드 확장 로직**:
  1. 기존 (1) clientName 검증 → (2) redirectUris 검증 → (3) 1인 5개 제한 → (4) clientName 중복 검증 → SAS 등록 → ServiceClient 저장 순서 **그대로 유지**
  2. `command.pathPrefix()`와 `command.upstreamUrl()`이 **둘 다 non-null · non-blank**이면 라우트 생성 분기 진입:
     - ① `namespaceExtractor.extract(pathPrefix)` → `RouteNamespaceInvalidException` 400
     - ② `serviceRouteRepository.findNamespaceOwner(namespace)` → 결과가 있고 `ownerId`가 다르면 `RouteNamespaceTakenException` 403
     - ③ `routeValidator.validateUpstreamUrl(upstreamUrl)` → `RouteUpstreamInvalidException` 400
     - ④ `routeValidator.validatePathPrefix(pathPrefix)` → `RouteProtectedException` 403, `RoutePathConflictException` 409
     - ⑤ `serviceRouteRepository.save(ServiceRoute.create(pathPrefix, upstreamUrl, true, command.ownerId()))`
     - ⑥ `triggerRefresh()` 호출
  3. 라우트 분기 미진입(null 쌍)이면 라우트 필드 4개 모두 `null`로 Result 반환
- **원자성 보장**: 기존 `@Transactional` 경계에서 클라이언트 저장과 라우트 저장이 동일 DataSource·Connection을 공유하므로 라우트 검증/저장 실패 시 클라이언트 저장도 함께 롤백된다. `triggerRefresh()`는 `afterCommit`에서 실행된다.
- **`triggerRefresh()` 구현**: `ManageRouteService`와 완전히 동일한 패턴 사용:
  ```java
  private void triggerRefresh() {
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
          TransactionSynchronizationManager.registerSynchronization(
              new TransactionSynchronization() {
                  @Override
                  public void afterCommit() { doTriggerRefresh(); }
              });
      } else {
          doTriggerRefresh();
      }
  }
  private void doTriggerRefresh() {
      try { gatewayRefreshClient.triggerRefresh(); }
      catch (Exception e) { log.warn("Gateway refresh 트리거 실패: {}", e.getMessage()); }
  }
  ```
- **의존성**: 기존 `SasClientRegistrar`, `ServiceClientRepository`, `PasswordEncoder` + 신규 `ServiceRouteRepository`, `GatewayRefreshClient`, `RouteValidator`, `RouteNamespaceExtractor`
- **적용 컨벤션**:
  - `@Slf4j` 추가 (refresh 실패 경고 로그용)
  - `@RequiredArgsConstructor` 유지
  - `@Service` **제거** — `ManageRouteService`와 동일한 수동 `@Bean` 패턴
- **참조할 기존 코드**:
  - `services/libs/service-client/.../service/RegisterOAuthClientService.java:74-115`
  - `services/libs/service-client/.../service/ManageRouteService.java:155-175` (triggerRefresh 패턴)
  - `services/libs/service-client/.../service/SelfManageRouteService.java:55-88` (라우트 검증 순서)
- **연관 todo**: `[ ] RegisterOAuthClientService.selfRegister 메서드 확장`

---

##### ServiceRouteRepository (변경)
- **타입**: 출력 포트 인터페이스
- **책임**: `findAllByOwnerId`·`countByOwnerId` 제거. 제거 전 grep으로 다른 호출처 없음 확인 필수.
- **제거 메서드**: `findAllByOwnerId(Long ownerId)` (line 70), `countByOwnerId(Long ownerId)` (line 78)
- **유지 메서드**: `save`, `findAll`, `findById`, `deleteById`, `existsByPathPrefix`, `existsByPathPrefixAndRouteIdNot`, `findAllEnabled`, `findNamespaceOwner`
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceRouteRepository.java:65-78`
- **연관 todo**: `[ ] ServiceRouteRepository 출력 포트에서 findAllByOwnerId, countByOwnerId 메서드 삭제`

---

##### ServiceRouteRepositoryAdapter + ServiceRouteJpaRepository (변경)
- **타입**: 출력 포트 구현 어댑터 + Spring Data JPA 인터페이스
- **책임**: 포트에서 제거된 두 메서드에 대응하는 어댑터 메서드(`findAllByOwnerId`, `countByOwnerId`)와 JPA 인터페이스 메서드(`findAllByOwnerIdOrderByCreatedAtAsc`, `countByOwnerId`) 제거.
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/repository/ServiceRouteRepositoryAdapter.java:122-139`
- **연관 todo**: `[ ] ServiceRouteJpaRepository, ServiceRouteRepositoryAdapter의 대응 메서드도 함께 제거`

---

#### 모듈 / 패키지: `services/apis/auth-api`

```
presentation/
├── dto/
│   ├── SelfRegisterClientRequest        — pathPrefix/upstreamUrl 추가 + @AssertTrue
│   └── SelfRegisterClientResponse       — routeId/pathPrefix/upstreamUrl/enabled nullable 추가
├── docs/
│   └── ClientApiDocs                    — 라우트 관련 응답 코드 추가
└── controller/
    └── ClientController                 — Command/Result 라우트 필드 전달
config/
├── ApplicationServiceConfig             — selfManageRouteService 제거, registerOAuthClientService 수동 등록
├── ProtectedPathPolicyImpl              — /api/v1/routes/** 항목 제거
└── security/
    └── SecurityConfig                   — /api/v1/routes/** permitAll 블록 제거
exception/
└── GlobalExceptionHandler               — 3개 핸들러 제거
```

##### SelfRegisterClientRequest (변경)
- **타입**: 요청 DTO (record)
- **책임**: `pathPrefix`·`upstreamUrl` 선택 필드를 수용하고, 두 필드 중 하나만 있는 요청을 Bean Validation으로 거부한다.
- **변경 내용**:
  ```java
  public record SelfRegisterClientRequest(
      @NotBlank
      @Schema(description = "OAuth 클라이언트 이름", example = "에코노 SPA")
      String clientName,

      @NotNull
      @Schema(description = "허용할 리다이렉트 URI 목록",
              example = "[\"http://localhost:3000/callback\"]")
      Set<String> redirectUris,

      @Schema(description = "라우트 경로 접두사 (예: /api/my-service/**)", nullable = true)
      String pathPrefix,

      @Schema(description = "업스트림 서비스 URL (예: https://my-service.example.com)", nullable = true)
      String upstreamUrl
  ) {
      @AssertTrue(message = "pathPrefix와 upstreamUrl은 둘 다 있거나 둘 다 없어야 합니다.")
      public boolean isRouteFieldsConsistent() {
          boolean hasPrefix = pathPrefix != null && !pathPrefix.isBlank();
          boolean hasUpstream = upstreamUrl != null && !upstreamUrl.isBlank();
          return hasPrefix == hasUpstream;
      }
  }
  ```
- **검증 위치 결정 — 선택지 A (컨트롤러 계층 `@AssertTrue`) 채택**:
  - 이유: 즉시 400 반환으로 서비스 레이어 진입 차단. 한 필드만 있는 상태는 비즈니스 로직이 아닌 입력 형식 오류이므로 프레젠테이션 계층 검증이 적절하다.
  - `@AssertTrue` 메서드명은 `is` 접두사 규칙 준수 (`isRouteFieldsConsistent`).
  - `MethodArgumentNotValidException` → `GlobalExceptionHandler.handleValidation` → 400 VALIDATION_FAILED (기존 핸들러 그대로 동작).
- **적용 컨벤션**: `@Schema(nullable = true)` 신규 필드 필수.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/SelfRegisterClientRequest.java`
- **연관 todo**: `[ ] SelfRegisterClientRequest 확장`

---

##### SelfRegisterClientResponse (변경)
- **타입**: 응답 DTO (record)
- **책임**: 라우트 생성 결과를 nullable 필드로 노출한다. 라우트 미생성 시 4개 신규 필드 모두 `null`.
- **변경 내용**:
  ```java
  public record SelfRegisterClientResponse(
      @Schema(description = "발급된 OAuth 클라이언트 ID (UUID)",
              example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
      String clientId,

      @Schema(description = "1회 반환되는 클라이언트 시크릿 (재조회 불가)",
              example = "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
      String clientSecret,

      @Schema(description = "생성된 라우트 ID (UUID)", nullable = true)
      String routeId,

      @Schema(description = "등록된 경로 접두사", nullable = true, example = "/api/my-service/**")
      String pathPrefix,

      @Schema(description = "업스트림 서비스 URL", nullable = true,
              example = "https://my-service.example.com")
      String upstreamUrl,

      @Schema(description = "라우트 활성화 여부", nullable = true)
      Boolean enabled
  ) {}
  ```
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/dto/SelfRegisterClientResponse.java`
- **연관 todo**: `[ ] SelfRegisterClientResponse 확장`

---

##### ClientController (변경)
- **타입**: Controller
- **책임**: `registerClient` 메서드에서 `SelfRegisterOAuthClientCommand` 생성 시 라우트 필드를 전달하고, `SelfRegisterOAuthClientResult`에서 라우트 필드를 꺼내 응답 DTO에 매핑한다.
- **변경 내용**:
  ```java
  @Override
  @PostMapping
  public ResponseEntity<?> registerClient(
          @PassportAuth Passport passport,
          @Valid @RequestBody SelfRegisterClientRequest request) {
      SelfRegisterOAuthClientCommand command =
          new SelfRegisterOAuthClientCommand(
              request.clientName(),
              request.redirectUris(),
              passport.getMemberId(),
              request.pathPrefix(),    // 신규
              request.upstreamUrl()    // 신규
          );
      SelfRegisterOAuthClientResult result = registerOAuthClientUseCase.selfRegister(command);
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(new SelfRegisterClientResponse(
              result.clientId(),
              result.clientSecret(),
              result.routeId(),        // 신규 — null 가능
              result.pathPrefix(),     // 신규 — null 가능
              result.upstreamUrl(),    // 신규 — null 가능
              result.enabled()         // 신규 — null 가능
          ));
  }
  ```
- **의존성**: `RegisterOAuthClientUseCase` (변경 없음, 인터페이스 의존)
- **적용 컨벤션**: `presentation` 계층은 `application.usecase` 인터페이스에만 의존. `@Override` 유지.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/controller/ClientController.java`
- **연관 todo**: `[ ] ClientController.registerClient 수정`

---

##### ClientApiDocs (변경)
- **타입**: Swagger 문서 인터페이스
- **책임**: `registerClient` 엔드포인트의 `@ApiResponses`에 라우트 관련 응답 코드 추가.
- **추가 응답 코드**:
  - 기존 400 응답 설명 테이블에 행 추가:
    - `ROUTE_NAMESPACE_INVALID` — pathPrefix가 `/api/{namespace}/` 포맷 위반
    - `ROUTE_UPSTREAM_INVALID` — SSRF 또는 비허용 URL
    - `VALIDATION_FAILED (라우트)` — pathPrefix/upstreamUrl 한 필드만 있음
  - 403 응답 추가: `ROUTE_NAMESPACE_TAKEN` (네임스페이스 선점), `ROUTE_PROTECTED` (보호 경로)
  - 409 응답 추가: `ROUTE_PATH_CONFLICT` (pathPrefix 중복)
- **적용 컨벤션**: 기존 `@ApiResponse` 표 방식 유지; `content = @Content` (빈 body) 패턴 유지.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/presentation/docs/ClientApiDocs.java`
- **연관 todo**: `[ ] ClientApiDocs 인터페이스에 위 변경 사항 반영`

---

##### ApplicationServiceConfig (변경)
- **타입**: 설정 (`@Configuration`)
- **책임**: `selfManageRouteService` `@Bean` 제거 + `registerOAuthClientService` 수동 `@Bean` 추가.
- **제거**: `selfManageRouteService(...)` `@Bean` 메서드 전체 (lines 91-100)
- **추가**:
  ```java
  /**
   * RegisterOAuthClientService 빈 등록
   *
   * <p>@Service 자동 스캔 대신 수동 등록 — GatewayRefreshClient(auth-api 소속 빈)를
   * 명시적으로 주입하기 위함. ManageRouteService와 동일 패턴.
   *
   * @param sasClientRegistrar SAS 클라이언트 등록 포트
   * @param serviceClientRepository ServiceClient 저장소 포트
   * @param passwordEncoder 비밀번호 해시 인코더
   * @param serviceRouteRepository ServiceRoute 저장소 포트
   * @param gatewayRefreshClient Gateway refresh 트리거 포트
   * @param routeValidator 라우트 검증기
   * @return RegisterOAuthClientService 인스턴스
   */
  @Bean
  public RegisterOAuthClientService registerOAuthClientService(
          SasClientRegistrar sasClientRegistrar,
          ServiceClientRepository serviceClientRepository,
          PasswordEncoder passwordEncoder,
          ServiceRouteRepository serviceRouteRepository,
          GatewayRefreshClient gatewayRefreshClient,
          RouteValidator routeValidator) {
      return new RegisterOAuthClientService(
          sasClientRegistrar,
          serviceClientRepository,
          passwordEncoder,
          serviceRouteRepository,
          gatewayRefreshClient,
          routeValidator,
          new RouteNamespaceExtractor());
  }
  ```
  - `RouteNamespaceExtractor`는 Spring 빈이 아니므로 직접 `new`로 생성. 기존 `selfManageRouteService` `@Bean`의 `new RouteNamespaceExtractor()` 패턴과 동일.
- **import 변경**: `SelfManageRouteService` import 제거; `RegisterOAuthClientService`, `SasClientRegistrar`, `PasswordEncoder`, `ServiceClientRepository` import 추가.
- **`routeValidator` `@Bean`은 유지**: `ManageRouteService`가 계속 사용하므로 제거하지 않는다.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java:88-100`
- **연관 todo**: `[ ] ApplicationServiceConfig에서 selfManageRouteService @Bean 등록 메서드 삭제`, `[ ] RouteNamespaceExtractor 인스턴스 생성 위치 확인`

---

##### GlobalExceptionHandler (변경)
- **타입**: `@RestControllerAdvice`
- **제거 핸들러 3개**:
  - `handleRouteQuotaExceeded(RouteQuotaExceededException)` (lines 265-269)
  - `handleRouteAccessDenied(RouteAccessDeniedException)` (lines 289-293)
  - `handleRouteNamespaceImmutable(RouteNamespaceImmutableException)` (lines 313-318)
- **유지 핸들러**: `RouteNamespaceInvalidException`, `RouteNamespaceTakenException`, `RoutePathConflictException`, `RouteUpstreamInvalidException`, `RouteProtectedException`, `RouteNotFoundException` — 어드민 라우트 CRUD 및 새 흡수 흐름에서 계속 사용.
- **import 정리**: 제거된 3개 예외 클래스 import 삭제 (`RouteAccessDeniedException`, `RouteNamespaceImmutableException`, `RouteQuotaExceededException`).
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java:265-318`
- **연관 todo**: `[ ] GlobalExceptionHandler의 RouteAccessDeniedException, RouteNamespaceImmutableException, RouteQuotaExceededException 핸들러 삭제`

---

##### ProtectedPathPolicyImpl (변경)
- **타입**: 보호 경로 포트 구현체
- **책임**: `PROTECTED_PATHS` 목록에서 `"/api/v1/routes/**"` 항목 제거.
- **주석 정리**: "셀프 라우트 CRUD — 동적 라우트가 이 경로 자체를 가로채지 못하도록 보호" 주석 라인도 함께 제거.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ProtectedPathPolicyImpl.java:33-35`
- **연관 todo**: `[ ] ProtectedPathPolicyImpl의 /api/v1/routes/** 보호 경로 항목 삭제`

---

##### SecurityConfig (변경)
- **타입**: Spring Security 설정
- **책임**: `authorizeHttpRequests` 체인에서 `/api/v1/routes/**` permitAll 블록(lines 81-83) 제거.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/SecurityConfig.java:81-83`
- **연관 todo**: `[ ] SecurityConfig의 .requestMatchers("/api/v1/routes/**").permitAll() 항목 삭제`

---

#### 모듈 / 패키지: `services/apis/api-gateway`

```
config/
└── GatewayRoutingConfig     — auth-routes 정적 라우트 항목 제거
```

##### GatewayRoutingConfig (변경)
- **타입**: Spring Cloud Gateway 정적 라우팅 설정
- **책임**: `routes` `@Bean` 메서드에서 `auth-routes` 라우트 항목(line 66) 제거:
  ```java
  // 제거 대상
  .route("auth-routes", r -> r.path("/api/v1/routes/**").uri(authApiUri))
  ```
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java:66`
- **연관 todo**: `[ ] GatewayRoutingConfig의 /api/v1/routes/** 정적 라우트 항목 삭제`

---

### 호출 흐름

#### 정상 경로 A — 라우트 없이 클라이언트만 등록

```
POST /api/v1/clients { clientName, redirectUris }
  → @Valid SelfRegisterClientRequest
      → isRouteFieldsConsistent(): pathPrefix=null, upstreamUrl=null → true (통과)
  → ClientController.registerClient(passport, request)
      → SelfRegisterOAuthClientCommand(clientName, redirectUris, memberId, null, null)
      → RegisterOAuthClientService.selfRegister(command)    ← @Transactional 시작
          → (1)~(4) 기존 클라이언트 검증
          → sasClientRegistrar.registerAuthorizationCodeClient(...)
          → serviceClientRepository.save(ServiceClient)
          → pathPrefix=null → 라우트 분기 미진입
          → return SelfRegisterOAuthClientResult(clientId, secret, null, null, null, null)
      ← @Transactional 커밋
  → SelfRegisterClientResponse(clientId, secret, null, null, null, null)
  → 201 Created
```

#### 정상 경로 B — 라우트와 함께 등록

```
POST /api/v1/clients { clientName, redirectUris, pathPrefix: "/api/my/**", upstreamUrl: "https://my.example.com" }
  → @Valid SelfRegisterClientRequest
      → isRouteFieldsConsistent(): 둘 다 있음 → true (통과)
  → ClientController.registerClient(passport, request)
      → SelfRegisterOAuthClientCommand(clientName, redirectUris, memberId, "/api/my/**", "https://my.example.com")
      → RegisterOAuthClientService.selfRegister(command)    ← @Transactional 시작
          → (1)~(4) 클라이언트 검증 통과
          → sasClientRegistrar.registerAuthorizationCodeClient(...)
          → serviceClientRepository.save(ServiceClient)
          → pathPrefix + upstreamUrl 모두 non-blank → 라우트 분기 진입
              ① namespaceExtractor.extract("/api/my/**") → "my"
              ② serviceRouteRepository.findNamespaceOwner("my") → empty (신규 선점)
              ③ routeValidator.validateUpstreamUrl("https://my.example.com") → 통과
              ④ routeValidator.validatePathPrefix("/api/my/**") → 보호경로 ×, 중복 ×
              ⑤ serviceRouteRepository.save(ServiceRoute.create("/api/my/**", "https://my.example.com", true, memberId))
              ⑥ triggerRefresh() → TransactionSynchronizationManager.registerSynchronization(afterCommit)
          → return SelfRegisterOAuthClientResult(clientId, secret, routeId, "/api/my/**", "https://my.example.com", true)
      ← @Transactional 커밋
      ← afterCommit: GatewayRefreshClient.triggerRefresh()
  → SelfRegisterClientResponse(clientId, secret, routeId, "/api/my/**", "https://my.example.com", true)
  → 201 Created
```

#### 예외 경로 1 — 한 필드만 있는 요청 (Bean Validation)

```
POST /api/v1/clients { ..., pathPrefix: "/api/my/**" }  (upstreamUrl 없음)
  → @Valid SelfRegisterClientRequest
      → isRouteFieldsConsistent(): hasPrefix=true, hasUpstream=false → false
      → MethodArgumentNotValidException
  → GlobalExceptionHandler.handleValidation
  → 400 VALIDATION_FAILED
     fieldErrors: [{ field: "routeFieldsConsistent", message: "pathPrefix와 upstreamUrl은 둘 다 있거나 둘 다 없어야 합니다." }]
  (서비스 레이어 미진입)
```

#### 예외 경로 2 — 네임스페이스 포맷 위반

```
RegisterOAuthClientService.selfRegister(command)  ← @Transactional 시작
  → 클라이언트 저장 완료
  → 라우트 분기 진입
  → namespaceExtractor.extract("/not-api/my/**") → RouteNamespaceInvalidException
  → @Transactional 롤백 (클라이언트 저장도 롤백)
  → GlobalExceptionHandler.handleRouteNamespaceInvalid
  → 400 ROUTE_NAMESPACE_INVALID
```

#### 예외 경로 3 — 네임스페이스 선점

```
RegisterOAuthClientService.selfRegister(command)  ← @Transactional 시작
  → 클라이언트 저장 완료
  → namespaceExtractor.extract("/api/eeos/**") → "eeos"
  → serviceRouteRepository.findNamespaceOwner("eeos") → Optional.of(otherMemberId)
  → otherMemberId != memberId → RouteNamespaceTakenException("eeos")
  → @Transactional 롤백
  → GlobalExceptionHandler.handleRouteNamespaceTaken
  → 403 ROUTE_NAMESPACE_TAKEN
```

#### 예외 경로 4 — SSRF URL

```
RegisterOAuthClientService.selfRegister(command)  ← @Transactional 시작
  → namespaceExtractor 통과, findNamespaceOwner 통과
  → routeValidator.validateUpstreamUrl("http://192.168.1.1/admin") → RouteUpstreamInvalidException
  → @Transactional 롤백
  → GlobalExceptionHandler.handleRouteUpstreamInvalid
  → 400 ROUTE_UPSTREAM_INVALID
```

#### 예외 경로 5 — pathPrefix 중복

```
RegisterOAuthClientService.selfRegister(command)  ← @Transactional 시작
  → routeValidator.validatePathPrefix("/api/my/**")
      → serviceRouteRepository.existsByPathPrefix("/api/my/**") → true
      → RoutePathConflictException
  → @Transactional 롤백
  → GlobalExceptionHandler.handleRoutePathConflict
  → 409 ROUTE_PATH_CONFLICT
```

#### 예외 경로 6 — 보호경로 가로채기 시도

```
RegisterOAuthClientService.selfRegister(command)  ← @Transactional 시작
  → routeValidator.validatePathPrefix("/api/v1/auth/**")
      → protectedPathPolicy.isProtected("/api/v1/auth/**") → true
      → RouteProtectedException
  → @Transactional 롤백
  → GlobalExceptionHandler.handleRouteProtected
  → 403 ROUTE_PROTECTED
```

#### 예외 경로 7 — Gateway refresh 실패 (커밋 이후, 롤백 없음)

```
afterCommit: GatewayRefreshClient.triggerRefresh() → Exception 발생
  → log.warn("Gateway refresh 트리거 실패: {}", e.getMessage())
  → 예외 삼킴 (최종 일관성 수용)
  → 201 응답은 이미 반환됨, 클라이언트+라우트 DB 저장은 커밋 완료 상태
```

---

### 테스트 계획

#### 삭제할 테스트
- `services/apis/auth-api/src/test/.../presentation/controller/RouteControllerTest.java`
- `services/libs/service-client/src/test/.../application/service/SelfManageRouteServiceTest.java`

---

#### ClientControllerTest 확장 (`@WebMvcTest(ClientController.class)`)

기존 케이스(`RegisterSuccessTest`, `AuthenticationFailureTest`, `BusinessRuleViolationTest`) **전부 유지**.

기존 테스트의 `new SelfRegisterOAuthClientResult(expectedClientId, expectedSecret)` stub을 `new SelfRegisterOAuthClientResult(expectedClientId, expectedSecret, null, null, null, null)`으로 수정 필요.

**RegisterRouteAbsorptionTest (`@Nested @DisplayName("POST /api/v1/clients — 라우트 흡수 등록")`)** 추가:

| 메서드명 | @DisplayName | 검증 포인트 |
|---|---|---|
| `selfRegister_withBothRouteFields_returns201WithRouteFields` | 라우트 필드 둘 다 있는 요청 → 201 + 라우트 필드 포함 | stub → `SelfRegisterOAuthClientResult(clientId, secret, "route-uuid", "/api/my/**", "https://my.example.com", true)` → `$.routeId`, `$.pathPrefix`, `$.enabled` 검증 |
| `selfRegister_withoutRouteFields_returns201WithNullRouteFields` | 라우트 필드 없는 요청 → 201 + 라우트 필드 null 또는 부재 | stub → routeId=null → `$.routeId` null 또는 jsonPath 미존재 |
| `selfRegister_withOnlyPathPrefix_returns400ValidationFailed` | `pathPrefix`만 있고 `upstreamUrl` 없음 → 400 VALIDATION_FAILED | `@AssertTrue` 거부, `$.errorCode == "VALIDATION_FAILED"` |
| `selfRegister_withOnlyUpstreamUrl_returns400ValidationFailed` | `upstreamUrl`만 있고 `pathPrefix` 없음 → 400 VALIDATION_FAILED | 동일 |
| `selfRegister_whenNamespaceInvalid_returns400` | 네임스페이스 포맷 위반 → 400 ROUTE_NAMESPACE_INVALID | stub → `RouteNamespaceInvalidException`, `$.errorCode == "ROUTE_NAMESPACE_INVALID"` |
| `selfRegister_whenNamespaceTaken_returns403` | 네임스페이스 선점 → 403 ROUTE_NAMESPACE_TAKEN | stub → `RouteNamespaceTakenException`, `$.errorCode == "ROUTE_NAMESPACE_TAKEN"` |
| `selfRegister_whenPathConflict_returns409` | pathPrefix 중복 → 409 ROUTE_PATH_CONFLICT | stub → `RoutePathConflictException`, `$.errorCode == "ROUTE_PATH_CONFLICT"` |
| `selfRegister_whenUpstreamInvalid_returns400` | SSRF URL → 400 ROUTE_UPSTREAM_INVALID | stub → `RouteUpstreamInvalidException`, `$.errorCode == "ROUTE_UPSTREAM_INVALID"` |

Mock 추가 없음 — `RegisterOAuthClientUseCase`가 `@MockBean`이므로 stub 반환값/예외만 변경.

---

#### RegisterOAuthClientServiceTest 확장 (`@ExtendWith(MockitoExtension.class)`)

**setUp 변경**:
- `@Mock ServiceRouteRepository serviceRouteRepository` 추가
- `@Mock GatewayRefreshClient gatewayRefreshClient` 추가
- `@Mock RouteValidator routeValidator` 추가
- `new RegisterOAuthClientService(sasClientRegistrar, serviceClientRepository, passwordEncoder, serviceRouteRepository, gatewayRefreshClient, routeValidator, new RouteNamespaceExtractor())` 생성자 변경

기존 케이스 **전부 유지**. 기존 케이스의 `SelfRegisterOAuthClientCommand` 생성자 호출에 `null, null` 인자 추가 필요.

**SelfRegisterWithRouteTest (`@Nested @DisplayName("selfRegister — 라우트 분기")`)** 추가:

| 메서드명 | @DisplayName | Given / When / Then |
|---|---|---|
| `selfRegister_withNullRouteFields_doesNotSaveRoute` | 라우트 필드 null → 라우트 저장 미호출 | command pathPrefix=null, upstreamUrl=null → `then(serviceRouteRepository).should(never()).save(any(ServiceRoute.class))` |
| `selfRegister_withBothRouteFields_savesClientAndRoute` | 둘 다 있음 → 클라이언트 + 라우트 저장 1회씩 호출 | `findNamespaceOwner` empty stub, `routeValidator` 정상 → `serviceRouteRepository.save(any(ServiceRoute.class))` times(1) |
| `selfRegister_withBothRouteFields_returnsRouteInfoInResult` | 라우트 저장 시 Result에 라우트 필드 포함 | saved `ServiceRoute`의 routeId → result.routeId() notBlank, result.enabled() == true |
| `selfRegister_withBothRouteFields_savesRouteWithOwnerId` | 저장된 ServiceRoute에 ownerId가 포함됨 | `ArgumentCaptor<ServiceRoute>` → `saved.ownerId() == memberId` |
| `selfRegister_whenNamespaceInvalid_throwsAndRollback` | 네임스페이스 검증 실패 → RouteNamespaceInvalidException 전파 | pathPrefix="/not-api/x" → `assertThatThrownBy(...).isInstanceOf(RouteNamespaceInvalidException.class)` |
| `selfRegister_whenNamespaceTaken_throwsRouteNamespaceTakenException` | 타 owner 네임스페이스 → RouteNamespaceTakenException | `findNamespaceOwner("my")` → `Optional.of(otherMemberId)` stub → 예외 발생 |
| `selfRegister_whenRouteStoreFails_propagatesException` | 라우트 저장 실패 → 예외 전파 | `serviceRouteRepository.save(any(ServiceRoute.class))` throws `RuntimeException` |
| `selfRegister_withBothRouteFields_triggersRefreshAfterCommit` | 라우트 저장 후 refresh 트리거 (트랜잭션 비활성 환경에서 즉시 호출) | `TransactionSynchronizationManager` 비활성 상태에서 `gatewayRefreshClient.triggerRefresh()` times(1) 검증 |

---

### 컨벤션 준수 항목

- **네이밍**: `isRouteFieldsConsistent()` — `is` 접두사 규칙. `triggerRefresh()` / `doTriggerRefresh()` — 기존 `ManageRouteService` 패턴 동일.
- **의존성 주입**: `@RequiredArgsConstructor` 기반 생성자 주입. `ApplicationServiceConfig` 수동 `@Bean` 등록 시 파라미터 = Spring 관리 빈, `RouteNamespaceExtractor`는 직접 `new`.
- **계층 의존성 방향**: `ClientController` → `RegisterOAuthClientUseCase` 인터페이스만 참조. `ApplicationServiceConfig`는 구현체(`RegisterOAuthClientService`) 참조 허용 (설정 책임). `presentation/controller`·`config/security`는 `application.service` 직접 참조 금지.
- **예외 처리**: 라우트 예외는 정적 팩토리 없이 생성자 직접 호출 패턴 (기존 `RouteXxxException` 패턴). `triggerRefresh` 실패는 `log.warn` 후 무시.
- **불변성**: `ServiceRoute.create(pathPrefix, upstreamUrl, true, ownerId)` 팩토리 메서드 활용. Record 기반 Command/Result.
- **테스트 패턴**: `@Nested` + `@DisplayName` 한글. Given-When-Then 주석. `@WebMvcTest` + `@MockBean` (컨트롤러), `@ExtendWith(MockitoExtension.class)` (서비스).
- **Swagger/OpenAPI**: `ClientApiDocs`에만 어노테이션, `ClientController`에는 `@Override`만. `@Schema(nullable = true)` 신규 nullable 필드 필수.
- **포맷**: `./gradlew format` 적용 후 커밋.

---

## 체크리스트
- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상 A/B, 예외 경로 7가지 모두)

---

## 참고
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RegisterOAuthClientService.java`
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientUseCase.java`
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/ManageRouteService.java` (triggerRefresh 패턴)
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/SelfManageRouteService.java` (라우트 검증 순서)
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RouteValidator.java`
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/service/RouteNamespaceExtractor.java`
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceRoute.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ProtectedPathPolicyImpl.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/security/SecurityConfig.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java`
- `docs/CONVENTION.md` — 네이밍/Lombok/불변성/테스트/Swagger 컨벤션
- `db/migration/V11__add_owner_id_to_service_route.sql` (변경 없음)
- `db/migration/V12__add_indexes_to_service_route.sql` (변경 없음)
