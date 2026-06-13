# extract-service-client-module - todo

## 메타

- **작업명**: extract-service-client-module
- **문서 타입**: todo
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - implementation-plan.md

## 개요

`auth-api` 모듈 안에 혼재된 ServiceClient·ServiceRoute 도메인 코드를 독립 라이브러리 모듈 `services/libs/service-client`로 추출한다.
도메인별 모듈 분리의 첫 단계로, 이후 다른 도메인 모듈 추출의 기준이 될 패턴을 확립한다.
이동 대상은 Domain 3개·포트 3개·유스케이스 2개·Persistence 어댑터 6개·SAS 어댑터 1개·예외 4개이며, 이동 시 클래스 내부 로직은 변경하지 않고 패키지 경로(`com.econo.auth.api.*` → `com.econo.auth.client.*`)만 변경한다.
신모듈은 Spring Boot AutoConfiguration(`ServiceClientAutoConfiguration` + `META-INF/spring/...imports`)으로 Bean을 자동 등록하며, `AuthApiApplication`의 `scanBasePackages`는 변경하지 않는다.

## 본문

### API 작업

- 해당 없음 (엔드포인트 추가·변경 없음. `AdminClientController`는 auth-api에 잔류하며 import 경로만 갱신)

### 구현 작업

#### 1. 신규 모듈 뼈대

- [ ] `services/libs/service-client/` 디렉터리 생성 및 Gradle 소스셋 구조 (`src/main/java`, `src/test/java`, `src/main/resources`) 초기화
- [ ] `services/libs/service-client/build.gradle.kts` 신규 작성 — `java-library` 플러그인, 아래 의존성 포함:
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-web` (예외 `@ResponseStatus` 어노테이션용)
  - `spring-boot-starter-oauth2-authorization-server` (SAS `RegisteredClient` 사용)
  - `implementation(project(":services:libs:auth-infra"))` (`JpaAuditingConfig` 등 공유 인프라)
  - Lombok (`compileOnly` + `annotationProcessor` — 루트 `build.gradle.kts` 상속이므로 별도 선언 불필요, 확인 후 생략 가능)
  - 테스트 의존성 (`testImplementation("org.springframework.boot:spring-boot-starter-test")` — 루트 상속 확인 후 생략 가능)
- [ ] `settings.gradle.kts`에 `include("services:libs:service-client")` 한 줄 추가

#### 2. 클래스 이동 (패키지 `com.econo.auth.api.*` → `com.econo.auth.client.*`)

- [ ] Domain 3개 이동 → `service-client/src/main/java/com/econo/auth/client/domain/`
  - `ServiceClient.java`
  - `ServiceRoute.java`
  - `GrantType.java`
- [ ] Out 포트 3개 이동 → `service-client/src/main/java/com/econo/auth/client/application/port/out/`
  - `ServiceClientRepository.java`
  - `ServiceRouteRepository.java`
  - `SasClientRegistrar.java`
- [ ] UseCase 2개 이동 → `service-client/src/main/java/com/econo/auth/client/application/usecase/`
  - `RegisterOAuthClientService.java`
  - `ClientRedirectUriService.java` (**주의**: 기존 경로 `com.econo.auth.api.application.ClientRedirectUriService`에서 `com.econo.auth.client.application.usecase.ClientRedirectUriService`로 변경됨 — `usecase` 서브패키지 추가됨)
- [ ] Persistence 어댑터 6개 이동 → `service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/`
  - `ServiceClientJpaEntity.java`
  - `ServiceClientJpaRepository.java`
  - `ServiceClientRepositoryAdapter.java`
  - `ServiceRouteJpaEntity.java`
  - `ServiceRouteJpaRepository.java`
  - `ServiceRouteRepositoryAdapter.java`
- [ ] SAS 어댑터 1개 이동 → `service-client/src/main/java/com/econo/auth/client/adapter/out/sas/`
  - `SasClientRegistrarAdapter.java`
- [ ] 예외 4개 이동 → `service-client/src/main/java/com/econo/auth/client/exception/`
  - `RedirectUriRequiredException.java`
  - `UnsupportedGrantTypeException.java`
  - `DuplicateClientNameException.java`
  - `InvalidClientException.java`

#### 3. 신모듈 AutoConfiguration 설정

- [ ] `ServiceClientAutoConfiguration.java` 신규 작성 (`@AutoConfiguration` 또는 `@Configuration` + `@ComponentScan("com.econo.auth.client")`) — 경로: `service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java`
- [ ] `service-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일 생성 — 내용: `com.econo.auth.client.config.ServiceClientAutoConfiguration` 한 줄 등록

#### 4. auth-infra 수정 (누락 항목 A — 런타임 실패 방지 필수)

- [ ] `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/InfraConfig.java` 수정
  - `@EnableJpaRepositories`의 `basePackages`에서 `"com.econo.auth.api.adapter.out.persistence"` 제거하고 `"com.econo.auth.client.adapter.out.persistence"` 추가
  - `@EntityScan`의 `basePackages`에서 `"com.econo.auth.api.adapter.out.persistence"` 제거하고 `"com.econo.auth.client.adapter.out.persistence"` 추가
  - 현재 `"com.econo.auth.infra"` 패키지는 반드시 유지 (`MemberJpaEntity` 등 기존 엔티티 매핑 보존)
  - 변경 후 두 패키지 모두 포함: `{"com.econo.auth.infra", "com.econo.auth.client.adapter.out.persistence"}`

#### 5. auth-api 수정

- [ ] `auth-api/build.gradle.kts`에 `implementation(project(":services:libs:service-client"))` 추가 (기존 `auth-infra` 직접 의존은 유지 — `SecurityConfig` 등 다른 auth-infra Bean 사용 중)
- [ ] `AdminClientController.java` — 이동된 클래스들 import 경로를 `com.econo.auth.client.*`로 전체 갱신:
  - `ClientRedirectUriService` → `com.econo.auth.client.application.usecase.ClientRedirectUriService` (**주의**: `usecase` 서브패키지 포함)
  - `RegisterOAuthClientService` → `com.econo.auth.client.application.usecase.RegisterOAuthClientService`
  - `GrantType` → `com.econo.auth.client.domain.GrantType`
- [ ] `DynamicCorsConfigurationSource.java` — import 경로 갱신:
  - `ClientRedirectUriService` → `com.econo.auth.client.application.usecase.ClientRedirectUriService` (**주의**: `usecase` 서브패키지 포함)
  - `ServiceClientRepository` → `com.econo.auth.client.application.port.out.ServiceClientRepository`
- [ ] `GlobalExceptionHandler.java` — 이동된 예외 4개 import 경로를 `com.econo.auth.client.exception.*`로 갱신:
  - `InvalidClientException`, `RedirectUriRequiredException`, `UnsupportedGrantTypeException`, `DuplicateClientNameException`
- [ ] `AuthApiApplication.java` — `scanBasePackages`에 `"com.econo.auth.client"` 추가 불필요 (`ServiceClientAutoConfiguration`의 AutoConfiguration으로 자동 Bean 등록됨, 변경하지 않음)

### DB 작업

- 해당 없음 (`service_client`, `service_route` 테이블의 Flyway 마이그레이션(V4)은 `auth-infra`에 그대로 유지. JPA 엔티티의 `@Entity`·`@Table` 테이블/컬럼 매핑 값 변경 없음. 마이그레이션 스크립트 변경 없음)

### 기타 작업

#### 테스트

- [ ] `RegisterOAuthClientServiceTest.java`를 신모듈(`service-client/src/test/java/com/econo/auth/client/application/usecase/`)로 이동 및 import 경로 갱신 (`com.econo.auth.api.*` → `com.econo.auth.client.*`)
- [ ] `AdminClientControllerTest.java` — auth-api에 잔류, 이동된 클래스들의 import 경로 갱신:
  - `ClientRedirectUriService` → `com.econo.auth.client.application.usecase.ClientRedirectUriService` (**주의**: `usecase` 서브패키지 포함)
  - `RegisterOAuthClientService` → `com.econo.auth.client.application.usecase.RegisterOAuthClientService`
  - `RedirectUriRequiredException` → `com.econo.auth.client.exception.RedirectUriRequiredException`
- [ ] `AuthApiIntegrationTest.java` — auth-api에 잔류, 이동된 클래스 import 경로 갱신 (참조 있는 경우만)

#### 검증

- [ ] `./gradlew :services:libs:service-client:compileJava` — 신모듈 단독 컴파일 통과 확인
- [ ] `./gradlew :services:libs:service-client:test` Green (신모듈 단위 테스트)
- [ ] `./gradlew :services:apis:auth-api:test` Green (Docker/Testcontainers 제외)
- [ ] `./gradlew build` 전체 빌드 통과 확인
- [ ] `grep -rn "com.econo.auth.api.domain.ServiceClient" services/ --include="*.java"` → 결과 0건
- [ ] `grep -rn "com.econo.auth.api.domain.ServiceRoute" services/ --include="*.java"` → 결과 0건
- [ ] `grep -rn "com.econo.auth.api.adapter.out.persistence" services/ --include="*.java"` → 결과 0건 (InfraConfig 포함)
- [ ] `find services -name '* 2.java'` → iCloud 충돌본 0건 확인, 발견 시 즉시 `rm` 제거

#### 포맷

- [ ] `./gradlew format` (spotless apply) 실행 후 전체 파일 포맷 통과 확인 (`./gradlew spotlessCheck`)

## 체크리스트

- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

## 참고

- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/settings.gradle.kts` — 모듈 등록 위치
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/build.gradle.kts` — 루트 빌드 설정 (spotless, java 21, BOM, Lombok 전역 선언)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/build.gradle.kts` — auth-api 의존성 참고
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/auth-infra/build.gradle.kts` — auth-infra 의존성 참고 (Flyway, JPA, PostgreSQL)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/InfraConfig.java` — **누락 항목 A 수정 대상** (`@EntityScan`, `@EnableJpaRepositories` basePackages)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/AuthApiApplication.java` — scanBasePackages 변경 불필요 확인됨 (AutoConfiguration 방식 채택)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 예외 import 경로 갱신 대상
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/config/DynamicCorsConfigurationSource.java` — 신모듈 클래스 의존 import 갱신 대상 (usecase 서브패키지 주의)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` — import 경로 갱신 대상 (usecase 서브패키지 주의)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java` — import 경로 갱신 대상 (usecase 서브패키지 주의)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/test/java/com/econo/auth/api/application/usecase/RegisterOAuthClientServiceTest.java` — 신모듈로 이동 대상
- `docs/ARCHITECTURE.md`, `docs/CONVENTION.md` — 헥사고날 패키지 구조, AutoConfiguration 컨벤션 참고
- `.claude/memory/icloud-duplicate-files-gotcha.md` — iCloud 충돌본 주의사항
