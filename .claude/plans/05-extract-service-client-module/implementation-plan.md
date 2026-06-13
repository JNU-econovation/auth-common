# extract-service-client-module - implementation

## 메타

- **작업명**: extract-service-client-module
- **문서 타입**: implementation
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md

## 개요

`auth-api` 모듈에 혼재된 ServiceClient·ServiceRoute 도메인 19개 파일(도메인 3 + 포트 3 + 유스케이스 2 + Persistence 어댑터 6 + SAS 어댑터 1 + 예외 4)을 신규 Gradle 라이브러리 모듈 `services/libs/service-client`로 추출한다.
패키지 루트가 `com.econo.auth.api.*` → `com.econo.auth.client.*`로 일괄 변경되며, 클래스 내부 로직·메서드 시그니처는 일절 변경하지 않는다.
Java 21 / Spring Boot 3.2.2 / Gradle Kotlin DSL 멀티모듈 위에서 `java-library` 플러그인으로 빌드되고, Spring Boot 3.x AutoConfiguration(`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)으로 Bean을 자동 등록한다.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/libs/service-client/` | 신규 | ServiceClient 도메인 독립 라이브러리 모듈. `java-library` 플러그인 사용 (배포 단위 아님) |
| `services/libs/service-client/src/main/java/com/econo/auth/client/domain/` | 신규 | 도메인 객체 3개 수용 |
| `services/libs/service-client/src/main/java/com/econo/auth/client/application/port/out/` | 신규 | 아웃바운드 포트 3개 수용 |
| `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/` | 신규 | 유스케이스 구현체 2개 수용 (`ClientRedirectUriService`의 기존 위치와 깊이 달라짐 — 아래 주의사항 참조) |
| `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/` | 신규 | Persistence 어댑터 6개 수용 |
| `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/sas/` | 신규 | SAS 어댑터 1개 수용 |
| `services/libs/service-client/src/main/java/com/econo/auth/client/exception/` | 신규 | 예외 4개 수용 |
| `services/libs/service-client/src/main/java/com/econo/auth/client/config/` | 신규 | `ServiceClientAutoConfiguration` 수용 |
| `services/libs/service-client/src/main/resources/META-INF/spring/` | 신규 | `AutoConfiguration.imports` 파일 수용 |
| `services/libs/service-client/src/test/java/com/econo/auth/client/application/usecase/` | 신규 | `RegisterOAuthClientServiceTest` 이동 대상 |
| `settings.gradle.kts` | 변경 | `include("services:libs:service-client")` 한 줄 추가 |
| `services/apis/auth-api/build.gradle.kts` | 변경 | `implementation(project(":services:libs:service-client"))` 추가 |
| `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/InfraConfig.java` | 변경 | `@EnableJpaRepositories` / `@EntityScan` basePackages 갱신 (런타임 필수) |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` | 변경 | 신모듈 클래스 import 갱신 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/config/DynamicCorsConfigurationSource.java` | 변경 | 신모듈 클래스 import 갱신 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` | 변경 | 이동된 예외 4개 import 갱신 |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java` | 변경 | 신모듈 클래스 import 갱신 |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/application/usecase/RegisterOAuthClientServiceTest.java` | 변경 (이동) | 신모듈 테스트 디렉터리로 이동 + import 갱신 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/AuthApiApplication.java` | 변경 없음 | AutoConfiguration 방식 채택으로 `scanBasePackages` 수정 불필요 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/libs/service-client`

```
service-client/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── java/com/econo/auth/client/
    │   │   ├── config/
    │   │   │   └── ServiceClientAutoConfiguration    — @AutoConfiguration + @ComponentScan, Bean 자동 등록
    │   │   ├── domain/
    │   │   │   ├── ServiceClient                     — ServiceClient 도메인 객체 (불변, 정적 팩토리)
    │   │   │   ├── ServiceRoute                      — ServiceRoute 도메인 객체 (record)
    │   │   │   └── GrantType                         — OAuth 그랜트 타입 enum
    │   │   ├── application/
    │   │   │   ├── port/out/
    │   │   │   │   ├── ServiceClientRepository       — ServiceClient 저장소 아웃바운드 포트
    │   │   │   │   ├── ServiceRouteRepository        — ServiceRoute 저장소 아웃바운드 포트
    │   │   │   │   └── SasClientRegistrar            — SAS 클라이언트 등록 아웃바운드 포트
    │   │   │   └── usecase/
    │   │   │       ├── RegisterOAuthClientService    — OAuth 클라이언트 등록 서비스
    │   │   │       └── ClientRedirectUriService      — redirectUri 관리 서비스
    │   │   ├── adapter/out/
    │   │   │   ├── persistence/
    │   │   │   │   ├── ServiceClientJpaEntity        — service_client 테이블 JPA 엔티티
    │   │   │   │   ├── ServiceClientJpaRepository    — Spring Data JPA 인터페이스
    │   │   │   │   ├── ServiceClientRepositoryAdapter — ServiceClientRepository 포트 구현체
    │   │   │   │   ├── ServiceRouteJpaEntity         — service_route 테이블 JPA 엔티티
    │   │   │   │   ├── ServiceRouteJpaRepository     — Spring Data JPA 인터페이스
    │   │   │   │   └── ServiceRouteRepositoryAdapter — ServiceRouteRepository 포트 구현체
    │   │   │   └── sas/
    │   │   │       └── SasClientRegistrarAdapter     — SasClientRegistrar 포트 구현체
    │   │   └── exception/
    │   │       ├── InvalidClientException            — 클라이언트 미존재 (GlobalExceptionHandler가 404로 매핑)
    │   │       ├── RedirectUriRequiredException      — @ResponseStatus(400) redirectUri 누락/초과
    │   │       ├── UnsupportedGrantTypeException     — @ResponseStatus(400) 미지원 그랜트 타입
    │   │       └── DuplicateClientNameException      — @ResponseStatus(409) 클라이언트 이름 중복
    │   └── resources/META-INF/spring/
    │       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/
        └── java/com/econo/auth/client/application/usecase/
            └── RegisterOAuthClientServiceTest        — 신모듈로 이동된 단위 테스트
```

---

##### `build.gradle.kts` (신규)

- **타입**: Gradle 빌드 스크립트
- **책임**: 신모듈 의존성 선언. `java-library` 플러그인 적용.
- **구체적 내용**:

```kotlin
plugins {
    `java-library`
}

dependencies {
    // JPA 엔티티 + Spring Data, AuditingEntityListener
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 예외 클래스의 @ResponseStatus — spring-web 에 포함
    implementation("org.springframework.boot:spring-boot-starter-web")
    // SasClientRegistrarAdapter: RegisteredClient, RegisteredClientRepository
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    // JpaAuditingConfig 공유 (auth-infra에 @EnableJpaAuditing 선언됨)
    implementation(project(":services:libs:auth-infra"))
}
```

- **결정 근거**:
  - Lombok, `testImplementation` — 루트 `build.gradle.kts` `subprojects` 블록에서 전역 선언됨. 별도 명시 불필요.
  - `runtimeOnly("org.postgresql:postgresql")` — 라이브러리 모듈에 포함하지 않음. PostgreSQL 드라이버는 auth-api 런타임에서 공급.
- **적용 컨벤션**: 기존 `services/libs/auth-infra/build.gradle.kts:1` 패턴(java-library) 미러링
- **연관 todo**: `[ ] services/libs/service-client/build.gradle.kts 신규 작성`

---

##### `settings.gradle.kts` (변경)

- **타입**: Gradle 설정 파일
- **변경 내용**: `// === Libs (공유 라이브러리) ===` 섹션 마지막 줄에 추가

```kotlin
// 변경 전 (settings.gradle.kts:8-10)
include("services:libs:auth-core")
include("services:libs:auth-infra")

// 변경 후
include("services:libs:auth-core")
include("services:libs:auth-infra")
include("services:libs:service-client")
```

- **참조할 기존 코드**: `settings.gradle.kts:8`
- **연관 todo**: `[ ] settings.gradle.kts에 include("services:libs:service-client") 한 줄 추가`

---

##### `ServiceClientAutoConfiguration` (신규)

- **타입**: AutoConfiguration (Config 계층)
- **경로**: `service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java`
- **책임**: auth-api classpath에 신모듈이 올라올 때 `com.econo.auth.client` 패키지 전체를 컴포넌트 스캔하여 `@Service`, `@Component` Bean 자동 등록. `AuthApiApplication.java`의 `scanBasePackages` 변경 없이 동작.

```java
package com.econo.auth.client.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * service-client 모듈 자동 설정
 *
 * <p>META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports에 등록되어
 * Spring Boot 3.x AutoConfiguration으로 동작한다. auth-api의 scanBasePackages 변경 없이
 * com.econo.auth.client 전체를 컴포넌트 스캔한다.
 */
@AutoConfiguration
@ComponentScan("com.econo.auth.client")
public class ServiceClientAutoConfiguration {}
```

- **채택 근거**: `AuthApiApplication.scanBasePackages`에 `"com.econo.auth.client"` 추가 대신 AutoConfiguration을 선택. 향후 도메인 모듈 추출 시 동일 패턴 적용 → 애플리케이션 진입점이 라이브러리 내부 패키지를 알 필요 없음 (역방향 의존 제거). todo 개요: "이후 다른 도메인 모듈 추출의 기준이 될 패턴".
- **주의**: `@EnableJpaRepositories` / `@EntityScan`은 패키지 화이트리스트 방식이므로 AutoConfiguration만으로는 JPA 빈 등록이 안 됨. `InfraConfig.java` 별도 수정 필수.
- **적용 컨벤션**: `{Domain}AutoConfiguration` 네이밍 (`docs/CONVENTION.md:1.2`), 한국어 Javadoc (`docs/CONVENTION.md:4.1`)
- **연관 todo**: `[ ] ServiceClientAutoConfiguration.java 신규 작성`, `[ ] META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 파일 생성`

---

##### `org.springframework.boot.autoconfigure.AutoConfiguration.imports` (신규)

- **경로**: `service-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- **내용** (한 줄):

```
com.econo.auth.client.config.ServiceClientAutoConfiguration
```

- **연관 todo**: `[ ] META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports 파일 생성`

---

##### `InfraConfig.java` (변경 — 런타임 실패 방지 필수)

- **경로**: `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/InfraConfig.java`
- **책임**: JPA Repository 및 Entity 스캔 범위를 신모듈 persistence 패키지로 갱신.

```java
// 변경 전 (InfraConfig.java:9-12)
@EnableJpaRepositories(
    basePackages = {"com.econo.auth.infra", "com.econo.auth.api.adapter.out.persistence"})
@EntityScan(basePackages = {"com.econo.auth.infra", "com.econo.auth.api.adapter.out.persistence"})

// 변경 후
@EnableJpaRepositories(
    basePackages = {"com.econo.auth.infra", "com.econo.auth.client.adapter.out.persistence"})
@EntityScan(basePackages = {"com.econo.auth.infra", "com.econo.auth.client.adapter.out.persistence"})
```

- **누락 시 결과**: Spring 기동 시 `ServiceClientJpaEntity`, `ServiceRouteJpaEntity`가 EntityManagerFactory에 미등록 → `NoSuchBeanDefinitionException` 또는 `HibernateException: could not determine type for GrantType` → 기동 실패.
- **주의**: `"com.econo.auth.infra"` 패키지는 `MemberJpaEntity` 등 기존 엔티티가 있으므로 반드시 유지.
- **참조할 기존 코드**: `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/InfraConfig.java:1`
- **연관 todo**: `[ ] InfraConfig.java 수정 (누락 항목 A — 런타임 실패 방지 필수)`

---

### 16 + 3 이동 매트릭스

(`ClientRedirectUriService` 패키지 깊이 변화에 주의)

| # | 클래스 | 기존 패키지 | 신규 패키지 | 비고 |
|---|---|---|---|---|
| 1 | `ServiceClient` | `...api.domain` | `...client.domain` | |
| 2 | `ServiceRoute` | `...api.domain` | `...client.domain` | record |
| 3 | `GrantType` | `...api.domain` | `...client.domain` | `UnsupportedGrantTypeException` 의존 — 함께 이동 |
| 4 | `ServiceClientRepository` | `...api.application.port.out` | `...client.application.port.out` | |
| 5 | `ServiceRouteRepository` | `...api.application.port.out` | `...client.application.port.out` | |
| 6 | `SasClientRegistrar` | `...api.application.port.out` | `...client.application.port.out` | |
| 7 | `RegisterOAuthClientService` | `...api.application.usecase` | `...client.application.usecase` | 서브패키지 깊이 동일 |
| 8 | `ClientRedirectUriService` | `...api.application` (usecase 없음) | `...client.application.usecase` | **usecase 서브패키지 추가** — 참조 파일 주의 |
| 9 | `ServiceClientJpaEntity` | `...api.adapter.out.persistence` | `...client.adapter.out.persistence` | |
| 10 | `ServiceClientJpaRepository` | `...api.adapter.out.persistence` | `...client.adapter.out.persistence` | |
| 11 | `ServiceClientRepositoryAdapter` | `...api.adapter.out.persistence` | `...client.adapter.out.persistence` | |
| 12 | `ServiceRouteJpaEntity` | `...api.adapter.out.persistence` | `...client.adapter.out.persistence` | |
| 13 | `ServiceRouteJpaRepository` | `...api.adapter.out.persistence` | `...client.adapter.out.persistence` | |
| 14 | `ServiceRouteRepositoryAdapter` | `...api.adapter.out.persistence` | `...client.adapter.out.persistence` | |
| 15 | `SasClientRegistrarAdapter` | `...api.adapter.out.sas` | `...client.adapter.out.sas` | |
| 16 | `RedirectUriRequiredException` | `...api.exception` | `...client.exception` | `@ResponseStatus(400)` |
| 17 | `UnsupportedGrantTypeException` | `...api.exception` | `...client.exception` | `@ResponseStatus(400)` |
| 18 | `DuplicateClientNameException` | `...api.exception` | `...client.exception` | `@ResponseStatus(409)` |
| 19 | `InvalidClientException` | `...api.exception` | `...client.exception` | `@ResponseStatus` 없음 |

---

### `ClientRedirectUriService` 패키지 깊이 변화 영향 표

이 작업에서 유일하게 패키지 깊이가 달라지는 클래스.

| 파일 | 기존 import | 신규 import |
|---|---|---|
| `AdminClientController.java` | `...api.application.ClientRedirectUriService` | `...client.application.usecase.ClientRedirectUriService` |
| `DynamicCorsConfigurationSource.java` | `...api.application.ClientRedirectUriService` | `...client.application.usecase.ClientRedirectUriService` |
| `AdminClientControllerTest.java` | `...api.application.ClientRedirectUriService` | `...client.application.usecase.ClientRedirectUriService` |

---

### auth-api에서 갱신될 import 경로 매핑

#### `AdminClientController.java`

| 변경 전 | 변경 후 |
|---|---|
| `com.econo.auth.api.application.ClientRedirectUriService` | `com.econo.auth.client.application.usecase.ClientRedirectUriService` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult` |
| `com.econo.auth.api.domain.GrantType` | `com.econo.auth.client.domain.GrantType` |

#### `DynamicCorsConfigurationSource.java`

| 변경 전 | 변경 후 |
|---|---|
| `com.econo.auth.api.application.ClientRedirectUriService` | `com.econo.auth.client.application.usecase.ClientRedirectUriService` |
| `com.econo.auth.api.application.port.out.ServiceClientRepository` | `com.econo.auth.client.application.port.out.ServiceClientRepository` |

#### `GlobalExceptionHandler.java`

현재 GlobalExceptionHandler는 `com.econo.auth.api.exception` 패키지에 위치하며, 이동 대상 예외들도 동일 패키지에 있어 import 없이 참조 중. 이동 완료 후에는 반드시 아래 import를 명시 추가해야 함.

| 추가할 import |
|---|
| `com.econo.auth.client.exception.InvalidClientException` |
| `com.econo.auth.client.exception.RedirectUriRequiredException` |
| `com.econo.auth.client.exception.UnsupportedGrantTypeException` |
| `com.econo.auth.client.exception.DuplicateClientNameException` |

#### `AdminClientControllerTest.java`

| 변경 전 | 변경 후 |
|---|---|
| `com.econo.auth.api.application.ClientRedirectUriService` | `com.econo.auth.client.application.usecase.ClientRedirectUriService` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult` |
| `com.econo.auth.api.exception.RedirectUriRequiredException` | `com.econo.auth.client.exception.RedirectUriRequiredException` |

#### `RegisterOAuthClientServiceTest.java` (신모듈 이동)

- 패키지 선언: `package com.econo.auth.api.application.usecase;` → `package com.econo.auth.client.application.usecase;`
- import 전량 갱신:

| 변경 전 | 변경 후 |
|---|---|
| `com.econo.auth.api.application.port.out.SasClientRegistrar` | `com.econo.auth.client.application.port.out.SasClientRegistrar` |
| `com.econo.auth.api.application.port.out.ServiceClientRepository` | `com.econo.auth.client.application.port.out.ServiceClientRepository` |
| `com.econo.auth.api.application.port.out.ServiceRouteRepository` | `com.econo.auth.client.application.port.out.ServiceRouteRepository` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientCommand` |
| `com.econo.auth.api.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult` | `com.econo.auth.client.application.usecase.RegisterOAuthClientService.RegisterOAuthClientResult` |
| `com.econo.auth.api.domain.GrantType` | `com.econo.auth.client.domain.GrantType` |
| `com.econo.auth.api.domain.ServiceClient` | `com.econo.auth.client.domain.ServiceClient` |
| `com.econo.auth.api.domain.ServiceRoute` | `com.econo.auth.client.domain.ServiceRoute` |
| `com.econo.auth.api.exception.DuplicateClientNameException` | `com.econo.auth.client.exception.DuplicateClientNameException` |
| `com.econo.auth.api.exception.RedirectUriRequiredException` | `com.econo.auth.client.exception.RedirectUriRequiredException` |

---

### 호출 흐름

정상 경로 — OAuth 클라이언트 등록:

```
POST /api/v1/clients
  → AdminClientController.registerClient()          [auth-api: adapter.in.web]
    → GrantType.fromString(request.grantType())     [service-client: domain]
    → RegisterOAuthClientService.register(command)  [service-client: application.usecase]
      → ServiceClientRepository.existsByClientName()
        → ServiceClientRepositoryAdapter            [service-client: adapter.out.persistence]
      → SasClientRegistrar.register*Client()
        → SasClientRegistrarAdapter                 [service-client: adapter.out.sas]
          → RegisteredClientRepository (SAS, auth-api)
      → ServiceClientRepository.save(ServiceClient.create(...))
        → ServiceClientJpaEntity.from() → JpaRepository.save()
      → (optional) ServiceRouteRepository.save(new ServiceRoute(...))
        → ServiceRouteJpaEntity.from() → JpaRepository.save()
    ← RegisterOAuthClientResult(clientId, rawSecret, routeId)
  ← 201 Created
```

정상 경로 — CORS 오리진 수집:

```
[모든 HTTP 요청의 CORS 사전 처리]
  → DynamicCorsConfigurationSource.getCorsConfiguration()   [auth-api: config]
    → ServiceClientRepository.findAllRegisteredClientIds()  [service-client: port.out]
      → ServiceClientRepositoryAdapter
    → RegisteredClientRepository.findById() (SAS)
    → ClientRedirectUriService.extractOrigin(uri)           [service-client: application.usecase — static]
  ← 허용 오리진 Set
```

예외 / 실패 경로:

```
GrantType.fromString("unknown")
  → throw UnsupportedGrantTypeException     [service-client: exception]
  → GlobalExceptionHandler                  [auth-api: exception]
  ← 400 UNSUPPORTED_GRANT_TYPE

RegisterOAuthClientService.register() — authorization_code + redirectUris 없음
  → throw RedirectUriRequiredException      [service-client: exception]
  → GlobalExceptionHandler
  ← 400 REDIRECT_URI_REQUIRED

RegisterOAuthClientService.register() — clientName 중복
  → throw DuplicateClientNameException      [service-client: exception]
  → GlobalExceptionHandler
  ← 409 DUPLICATE_CLIENT_NAME

ClientRedirectUriService.findByClientId() — clientId 미존재
  → throw InvalidClientException            [service-client: exception]
  → GlobalExceptionHandler
  ← 404 CLIENT_NOT_FOUND

InfraConfig basePackages 미갱신 시
  → Spring 기동 시 EntityScan 범위에 service-client persistence 패키지 미포함
  → ServiceClientJpaEntity 미등록 → ApplicationContext 생성 실패 (기동 불가)
```

---

### 컨벤션 준수 항목

- **네이밍**:
  - 신모듈 패키지 루트 `com.econo.auth.client`: 역도메인 + 기능명, 소문자 연결 (`docs/CONVENTION.md:1.1`)
  - `ServiceClientAutoConfiguration`: `{Domain}AutoConfiguration` 패턴 (`docs/CONVENTION.md:1.2`)
  - JPA 어댑터 `{Name}JpaEntity` / `{Name}JpaRepository` / `{Name}RepositoryAdapter` 패턴 유지 (`docs/CONVENTION.md:1.2`)

- **의존성 주입**:
  - `@RequiredArgsConstructor` + `private final` 필드. `ServiceClientAutoConfiguration`의 `@ComponentScan`으로 `@Service`, `@Component` Bean 자동 등록.

- **예외 처리**:
  - `RuntimeException` 하위. `@ResponseStatus` — spring-web 의존으로 유지 (build.gradle.kts에 `starter-web` 포함 이유)
  - `GlobalExceptionHandler`는 auth-api에 잔류하며 신모듈 예외를 import해서 처리 — 계층 경계 명확

- **불변성**:
  - `ServiceClient`: `private final` 필드 + 정적 팩토리 `create()` 패턴 유지 (`docs/CONVENTION.md:2.3`)
  - `ServiceRoute`: record 불변 타입 유지

- **Javadoc**:
  - 모든 public 클래스·메서드에 한국어 Javadoc 유지 (`docs/CONVENTION.md:4.1`)
  - `package` 선언 교체 후 파일 내 `@link`, `@see` 패키지 경로도 신규 경로로 갱신

- **테스트 패턴**:
  - `@ExtendWith(MockitoExtension.class)` + BDD 스타일(`given/when/then` 주석) + `@Nested` + 한국어 `@DisplayName` 패턴 유지 (`docs/CONVENTION.md:5.1`, `5.2`)
  - `@Disabled` 어노테이션 이동 후에도 그대로 유지

- **포맷팅**:
  - 모든 변경 완료 후 `./gradlew format` → `./gradlew spotlessCheck` 순 실행. spotless는 루트 `subprojects` 블록에서 전역 적용 → 신모듈도 자동 적용됨.

- **iCloud 충돌본**:
  - 신모듈 디렉터리 생성·파일 이동 후 반드시 `find services -name '* 2.java'` 실행 → 0건 확인. 발견 즉시 `rm` 제거.

---

### 빌드 검증 명령

```bash
# 1. 신모듈 단독 컴파일
./gradlew :services:libs:service-client:compileJava

# 2. 신모듈 단위 테스트
./gradlew :services:libs:service-client:test

# 3. auth-api 테스트
./gradlew :services:apis:auth-api:test

# 4. 전체 빌드 (spotless check 포함)
./gradlew build
```

### 검증 grep 명령 (이전 패키지 잔존 0건 확인)

```bash
# domain 이동 확인
grep -rn "com.econo.auth.api.domain.ServiceClient" services/ --include="*.java"
grep -rn "com.econo.auth.api.domain.ServiceRoute" services/ --include="*.java"
grep -rn "com.econo.auth.api.domain.GrantType" services/ --include="*.java"

# persistence 이동 확인 (InfraConfig 포함)
grep -rn "com.econo.auth.api.adapter.out.persistence" services/ --include="*.java"

# usecase 이동 확인
grep -rn "com.econo.auth.api.application.port.out.ServiceClientRepository" services/ --include="*.java"
grep -rn "com.econo.auth.api.application.ClientRedirectUriService" services/ --include="*.java"

# 예외 이동 확인
grep -rn "com.econo.auth.api.exception.InvalidClientException" services/ --include="*.java"
grep -rn "com.econo.auth.api.exception.RedirectUriRequiredException" services/ --include="*.java"
grep -rn "com.econo.auth.api.exception.UnsupportedGrantTypeException" services/ --include="*.java"
grep -rn "com.econo.auth.api.exception.DuplicateClientNameException" services/ --include="*.java"
```

> 각 명령의 예상 출력: 결과 0건. 단, `com.econo.auth.api.exception.GlobalExceptionHandler` 자신의 `package` 선언 줄은 정상이므로 예외.

### iCloud 충돌본 점검 명령

```bash
# 신모듈 기준
find services/libs/service-client -name '* 2.java'

# 전체 services 기준 (auth-api 기존 파일에도 영향 가능)
find services -name '* 2.java'
# 발견 시 즉시 제거: rm "발견된 경로"
```

### 포맷팅

```bash
./gradlew format
./gradlew spotlessCheck
```

---

## 체크리스트

- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두)

---

## 참고

- `docs/ARCHITECTURE.md` — 헥사고날 계층 구조, AutoConfiguration 패턴, 모듈 의존 관계
- `docs/CONVENTION.md` — 네이밍, Lombok, 불변성, Javadoc, 테스트 패턴
- `services/libs/auth-infra/build.gradle.kts` — java-library 플러그인 패턴 참조
- `services/apis/auth-api/build.gradle.kts` — auth-api 의존성 전체 확인
- `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/InfraConfig.java` — 변경 필수 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/ClientRedirectUriService.java` — `ClientRedirectUriService` 기존 위치 확인 (usecase 서브패키지 없음)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` — import 갱신 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/DynamicCorsConfigurationSource.java` — import 갱신 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — import 추가 대상
- `services/apis/auth-api/src/test/java/com/econo/auth/api/application/usecase/RegisterOAuthClientServiceTest.java` — 신모듈 이동 대상
