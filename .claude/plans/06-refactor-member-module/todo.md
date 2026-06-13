# refactor-member-module - todo

## 메타

- **작업명**: refactor-member-module
- **문서 타입**: todo
- **작성일**: 2026-06-04
- **관련 문서** (같은 디렉터리):
  - implementation-plan.md (작성 예정)
  - db-design-plan.md (작성 예정)

## 개요

기존 `auth-core`·`auth-infra` 두 모듈을 폐기하고, `service-client` 패턴을 그대로 적용한 헥사고날 구조의 신규 모듈 두 개(`member`, `common-infra`)로 재편한다.
`member` 모듈은 Member 도메인 전체(도메인 객체, 유스케이스, JPA 어댑터, BCrypt 어댑터, 예외)를 흡수하고 SpringBoot AutoConfiguration으로 자기 스캔한다.
`common-infra` 모듈은 `JpaAuditingConfig`만을 담아 JPA Auditing을 AutoConfiguration으로 제공하며, 모든 모듈의 `@EnableJpaAuditing` 의존을 일원화한다.
`auth-api`·`service-client`의 의존 그래프를 새 모듈 체계로 전환한 뒤 기존 두 모듈 디렉터리를 삭제한다.

## 본문

### API 작업

- 해당 없음 (엔드포인트 추가·변경 없음. 모든 Controller는 auth-api에 잔류하며 import 경로만 갱신)

---

### 구현 작업

#### 1. `services/libs/member/` 신규 모듈 — 뼈대

- [ ] `services/libs/member/` 디렉터리 및 Gradle 소스셋 구조 초기화 (`src/main/java`, `src/test/java`, `src/main/resources`)
- [ ] `services/libs/member/build.gradle.kts` 신규 작성 — `java-library` 플러그인, 의존성:
  - `implementation("org.springframework.boot:spring-boot-starter-data-jpa")`
  - `implementation("org.springframework.security:spring-security-crypto")` (BCrypt)
  - `implementation(project(":services:libs:common-infra"))` (JpaAuditingConfig 의존)
  - `runtimeOnly("org.postgresql:postgresql")`
  - `testImplementation("org.testcontainers:postgresql")`
  - `testImplementation("org.testcontainers:junit-jupiter")`
- [ ] `settings.gradle.kts`에 `include("services:libs:member")` 추가 (auth-core·auth-infra 제거는 6번 단계에서 수행)

#### 2. `services/libs/common-infra/` 신규 모듈 — 뼈대

- [ ] `services/libs/common-infra/` 디렉터리 및 Gradle 소스셋 구조 초기화 (`src/main/java`, `src/main/resources`)
- [ ] `services/libs/common-infra/build.gradle.kts` 신규 작성 — `java-library` 플러그인, 의존성:
  - `implementation("org.springframework.boot:spring-boot-starter-data-jpa")`
- [ ] `settings.gradle.kts`에 `include("services:libs:common-infra")` 추가

#### 3. `common-infra` 모듈 — 클래스 및 AutoConfiguration 작성

- [ ] `CommonInfraAutoConfiguration.java` 신규 작성 — `com.econo.auth.commoninfra.config` 패키지, `@AutoConfiguration` + `@EnableJpaAuditing` 직접 부착 (별도 `JpaAuditingConfig` 임포트 없이 하나의 클래스로 통합)
  - 경로: `common-infra/src/main/java/com/econo/auth/commoninfra/config/CommonInfraAutoConfiguration.java`
- [ ] `common-infra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 신규 작성 — 내용: `com.econo.auth.commoninfra.config.CommonInfraAutoConfiguration`

#### 4. `member` 모듈 — 클래스 이동 (패키지 변환)

> 이동 규칙: `com.econo.auth.core.member.*` → `com.econo.auth.member.*`, `com.econo.auth.infra.member.*` → `com.econo.auth.member.*`

- [ ] 도메인 2개 이동 → `member/src/main/java/com/econo/auth/member/domain/`
  - `auth-core` `Member.java`
  - `auth-core` `MemberStatus.java`
- [ ] 인바운드 포트 1개 이동 → `member/src/main/java/com/econo/auth/member/application/port/in/`
  - `auth-core` `SignupUseCase.java` (내부 `SignupCommand` record 포함)
- [ ] 아웃바운드 포트 2개 이동 → `member/src/main/java/com/econo/auth/member/application/port/out/`
  - `auth-core` `MemberRepository.java`
  - `auth-core` `PasswordHasher.java`
- [ ] 유스케이스 구현체 1개 이동 → `member/src/main/java/com/econo/auth/member/application/usecase/`
  - `auth-core` `SignupService.java`
- [ ] Persistence 어댑터 3개 이동 → `member/src/main/java/com/econo/auth/member/adapter/out/persistence/`
  - `auth-infra` `MemberJpaEntity.java` (패키지 선언 및 내부 import 수정)
  - `auth-infra` `MemberJpaRepository.java`
  - `auth-infra` `MemberRepositoryAdapter.java`
- [ ] Security 어댑터 1개 이동 → `member/src/main/java/com/econo/auth/member/adapter/out/security/`
  - `auth-infra` `BCryptPasswordHasherAdapter.java`
- [ ] 예외 4개 이동 → `member/src/main/java/com/econo/auth/member/exception/`
  - `auth-core` `MemberAlreadyExistsException.java`
  - `auth-core` `MemberNotFoundException.java`
  - `auth-core` `InvalidCredentialsException.java`
  - `auth-core` `InvalidPasswordPolicyException.java`

#### 5. `member` 모듈 — AutoConfiguration 설정

- [ ] `MemberAutoConfiguration.java` 신규 작성 — `com.econo.auth.member.config` 패키지
  - `@AutoConfiguration`
  - `@ComponentScan("com.econo.auth.member")`
  - `@EnableJpaRepositories("com.econo.auth.member.adapter.out.persistence")`
  - `@EntityScan("com.econo.auth.member.adapter.out.persistence")`
  - 경로: `member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java`
- [ ] `member/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 신규 작성 — 내용: `com.econo.auth.member.config.MemberAutoConfiguration`

#### 6. `auth-api` — 의존성 및 import 전환

- [ ] `auth-api/build.gradle.kts` 수정:
  - `implementation(project(":services:libs:auth-core"))` 제거
  - `implementation(project(":services:libs:auth-infra"))` 제거
  - `implementation(project(":services:libs:member"))` 추가
  - `implementation(project(":services:libs:service-client"))` 유지
  - `implementation("org.springframework.boot:spring-boot-starter-data-jpa")` 제거 (member 모듈이 전이 의존으로 제공)
- [ ] `ApplicationServiceConfig.java` — import 경로 갱신:
  - `com.econo.auth.core.member.application.port.out.MemberRepository` → `com.econo.auth.member.application.port.out.MemberRepository`
  - `com.econo.auth.core.member.application.port.out.PasswordHasher` → `com.econo.auth.member.application.port.out.PasswordHasher`
  - `com.econo.auth.core.member.application.usecase.SignupService` → `com.econo.auth.member.application.usecase.SignupService`
- [ ] `MemberUserDetailsService.java` — import 경로 갱신:
  - `com.econo.auth.core.member.application.port.out.MemberRepository` → `com.econo.auth.member.application.port.out.MemberRepository`
- [ ] `MemberUserDetails.java` — Member 도메인 import 경로 갱신 (참조 확인 후 적용):
  - `com.econo.auth.core.member.*` → `com.econo.auth.member.*`
- [ ] `MemberController.java`, `MemberInfoController.java` — 사용하는 member 패키지 import 갱신 (파일 열어 참조 확인 후 적용)
- [ ] `GlobalExceptionHandler.java` — 예외 import 경로 갱신:
  - `com.econo.auth.core.member.exception.MemberAlreadyExistsException` → `com.econo.auth.member.exception.MemberAlreadyExistsException`
  - `com.econo.auth.core.member.exception.MemberNotFoundException` → `com.econo.auth.member.exception.MemberNotFoundException`
  - `com.econo.auth.core.member.exception.InvalidPasswordPolicyException` → `com.econo.auth.member.exception.InvalidPasswordPolicyException`

#### 7. `service-client` — 의존성 전환

- [ ] `service-client/build.gradle.kts` 수정:
  - `implementation(project(":services:libs:auth-infra"))` 제거
  - `implementation(project(":services:libs:common-infra"))` 추가

#### 8. `auth-api` — `ApplicationServiceConfig` 빈 등록 방식 검토

- [ ] `SignupService`가 `member` 모듈로 이동하고 `MemberAutoConfiguration`이 `@ComponentScan`으로 `com.econo.auth.member` 전체를 스캔하면 `SignupService`가 자동 스캔 대상이 될 수 있음 — `SignupService`에 `@Component` 어노테이션이 없으므로 `ApplicationServiceConfig`의 `@Bean` 방식 유지. 이동 후 `SignupService` 클래스에 `@Component`가 없는지 확인하고 의도적으로 제외된 상태 유지

#### 9. 기존 모듈 폐기

- [ ] `services/libs/auth-core/` 디렉터리 전체 삭제
- [ ] `services/libs/auth-infra/` 디렉터리 전체 삭제
- [ ] `settings.gradle.kts`에서 `include("services:libs:auth-core")` 제거
- [ ] `settings.gradle.kts`에서 `include("services:libs:auth-infra")` 제거

---

### DB 작업

- [ ] Flyway 마이그레이션 파일 5개(`V1`~`V5`) `auth-infra/src/main/resources/db/migration/` → `member/src/main/resources/db/migration/` 이동
  - `V1__create_members_table.sql`
  - `V2__create_sas_tables.sql`
  - `V3__create_spring_session_tables.sql`
  - `V4__create_service_client_and_route.sql`
  - `V5__make_grant_type_nullable.sql`
  - 파일 내용 무변경. 모든 마이그레이션을 `member` 모듈 단일 소스로 유지
- [ ] `auth-api`의 `application.yml`(또는 `application.properties`) 에서 `spring.flyway.locations` 설정이 없다면 기본값(`classpath:db/migration`)이 `member` 모듈 경로와 일치하는지 확인. 경로 불일치 시 `spring.flyway.locations=classpath:db/migration` 명시 추가

---

### 기타 작업

#### 테스트 이동

- [ ] `auth-core` `SignupServiceTest.java` → `member/src/test/java/com/econo/auth/member/application/usecase/SignupServiceTest.java` 이동 및 import 경로 갱신 (`com.econo.auth.core.member.*` → `com.econo.auth.member.*`)
- [ ] `auth-core` `MemberTest.java` → `member/src/test/java/com/econo/auth/member/domain/MemberTest.java` 이동 및 import 경로 갱신
- [ ] `auth-infra` `MemberRepositoryAdapterTest.java` → `member/src/test/java/com/econo/auth/member/adapter/out/persistence/MemberRepositoryAdapterTest.java` 이동 및 import 경로 갱신
  - `@Import(MemberRepositoryAdapter.class)` 경로 변경 반영
  - `com.econo.auth.core.member.*` → `com.econo.auth.member.*`
  - `com.econo.auth.infra.member.*` → `com.econo.auth.member.*`
- [ ] `auth-infra` `BCryptPasswordHasherAdapterTest.java` → `member/src/test/java/com/econo/auth/member/adapter/out/security/BCryptPasswordHasherAdapterTest.java` 이동 및 import 경로 갱신
- [ ] `member` 모듈 테스트용 부트스트랩 클래스 `TestMemberApplication.java` 신규 작성 (`@SpringBootApplication` — `@EnableJpaAuditing`은 `CommonInfraAutoConfiguration`이 담당하므로 생략)
  - 경로: `member/src/test/java/com/econo/auth/member/TestMemberApplication.java`
- [ ] `auth-api` 통합 테스트(`AuthApiIntegrationTest.java`, `SasAuthorizationServerIntegrationTest.java`) — member 예외 import 경로 갱신 (참조 있는 경우만)
- [ ] `auth-api` 웹 레이어 테스트(`MemberControllerTest.java`) — member 예외 import 경로 갱신

#### 문서 갱신

- [ ] `docs/ARCHITECTURE.md` — 모듈 구조 표·의존 그래프·패키지 구조 섹션을 신규 모듈 체계로 갱신:
  - `auth-core`, `auth-infra` 항목을 `member`, `common-infra`로 교체
  - 의존성 그래프 다이어그램 갱신
- [ ] `service-client` README — "주요 연관 모듈" 항목에서 `auth-infra` → `common-infra` 갱신

#### 검증

- [ ] `./gradlew :services:libs:common-infra:compileJava` — 신모듈 단독 컴파일 통과
- [ ] `./gradlew :services:libs:member:compileJava` — 신모듈 단독 컴파일 통과
- [ ] `./gradlew :services:libs:member:test` — 단위·JPA 통합 테스트 Green
- [ ] `./gradlew :services:apis:auth-api:test` — 기존 테스트 Green (Testcontainers 포함)
- [ ] `./gradlew build` — 전체 빌드 통과 (CI 기준 동일)
- [ ] `grep -rn "com.econo.auth.core" services/ --include="*.java"` → 결과 0건 (auth-core 패키지 참조 잔존 없음)
- [ ] `grep -rn "com.econo.auth.infra" services/ --include="*.java"` → 결과 0건 (auth-infra 패키지 참조 잔존 없음)
- [ ] `find services -name '* 2.java'` → iCloud 충돌본 0건 확인, 발견 시 즉시 `rm` 제거

#### 포맷

- [ ] `./gradlew format` (Spotless apply) 실행 후 `./gradlew spotlessCheck` 통과 확인

## 체크리스트

- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 implementer가 바로 작업 가능

## 참고

- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/settings.gradle.kts` — 모듈 등록 파일
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/auth-core/` — 폐기 대상 모듈 (도메인·유스케이스·포트·예외 이동 원본)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/auth-infra/` — 폐기 대상 모듈 (JPA 어댑터·Flyway·설정 이동 원본)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java` — AutoConfiguration 작성 기준 패턴
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/libs/service-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — imports 파일 형식 기준 패턴
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 예외 import 갱신 대상
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — SignupService 빈 등록 (import 갱신 대상)
- `/Users/kimjongmin/Library/Mobile Documents/com~apple~CloudDocs/대학/에코노베이션/Project/auth-common/docs/ARCHITECTURE.md`, `docs/CONVENTION.md` — 헥사고날 패키지 구조, AutoConfiguration 컨벤션 기준
- `.claude/plans/05-extract-service-client-module/todo.md` — 직전 패턴 참고 (신모듈 뼈대, AutoConfiguration, import 갱신 절차)
- `.claude/memory/icloud-duplicate-files-gotcha.md` — iCloud 충돌본 주의사항
