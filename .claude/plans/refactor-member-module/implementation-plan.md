# refactor-member-module - implementation

## 메타
- **작업명**: refactor-member-module
- **문서 타입**: implementation
- **작성일**: 2026-06-04
- **관련 문서** (같은 디렉터리):
  - todo.md

## 개요

기존 `services/libs/auth-core`(도메인·유스케이스·포트·예외)와 `services/libs/auth-infra`(JPA 어댑터·Flyway·설정)를 폐기하고, `service-client` 모듈과 동일한 헥사고날 + SpringBoot AutoConfiguration 패턴을 적용하여 `services/libs/member`(Member 도메인 전체)와 `services/libs/common-infra`(JPA Auditing 전용) 두 모듈로 재편한다. Java 21, Spring Boot 3.2.2, Gradle Kotlin DSL 멀티모듈 위에서 설계한다. `auth-api`, `service-client`의 의존 그래프를 전환한 뒤 폐기 모듈 디렉터리를 삭제한다.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 | 신규 / 변경 | 사유 |
|---|---|---|
| `services/libs/member/` | 신규 | auth-core + auth-infra의 member 관련 클래스 12 + 4개를 하나의 헥사고날 모듈로 통합. ServiceClientAutoConfiguration 패턴 미러링 |
| `services/libs/common-infra/` | 신규 | JpaAuditingConfig를 독립 AutoConfiguration 모듈로 추출하여 auth-infra 의존성을 제거하고 JPA Auditing 활성화를 일원화 |
| `services/libs/auth-core/` | 삭제 | member 모듈로 흡수 완료 후 디렉터리 전체 제거 |
| `services/libs/auth-infra/` | 삭제 | member + common-infra 모듈로 흡수 완료 후 디렉터리 전체 제거 |
| `services/apis/auth-api/build.gradle.kts` | 변경 | auth-core·auth-infra 제거, member 추가, spring-boot-starter-data-jpa 직접 선언 제거 |
| `services/apis/auth-api/src/main/java/com/econo/auth/api/` | 변경 | 7개 파일 import 경로 갱신 (`com.econo.auth.core.member.*` → `com.econo.auth.member.*`) |
| `services/apis/auth-api/src/test/java/com/econo/auth/api/` | 변경 | 2개 테스트 파일 import 경로 갱신 |
| `services/libs/service-client/build.gradle.kts` | 변경 | auth-infra 제거, common-infra 추가 |

---

### 구성 요소 설계

#### 모듈 / 패키지: `services/libs/common-infra/`

```
services/libs/common-infra/
├── build.gradle.kts
└── src/
    └── main/
        ├── java/com/econo/auth/commoninfra/
        │   └── config/
        │       └── CommonInfraAutoConfiguration.java   — JPA Auditing AutoConfiguration
        └── resources/
            └── META-INF/spring/
                └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

##### `CommonInfraAutoConfiguration`
- **타입**: AutoConfiguration
- **책임**: `@EnableJpaAuditing`을 직접 선언하여 JPA Auditing을 활성화한다. 이 클래스 하나만 존재하므로 별도 `JpaAuditingConfig`를 import하지 않는다.
- **주요 메서드/함수**: 없음 (어노테이션 전용)
- **의존성**: `spring-boot-starter-data-jpa` (AuditingEntityListener 클래스 제공)
- **적용 컨벤션**:
  - 네이밍: `{Domain}AutoConfiguration` → `CommonInfraAutoConfiguration` (`docs/CONVENTION.md` 1.2절)
  - AutoConfiguration 등록: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 FQCN 한 줄 기재 (참조: `services/libs/service-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports:1`)
  - `@ComponentScan` 불필요: 등록할 빈이 없고 `@EnableJpaAuditing`만 활성화하면 충분
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java:15-19` (AutoConfiguration 뼈대 패턴)
- **연관 todo**: `[ ] CommonInfraAutoConfiguration.java 신규 작성` (3번 단계)

##### `common-infra/build.gradle.kts`
- **타입**: Gradle 빌드 파일
- **책임**: `java-library` 플러그인, `spring-boot-starter-data-jpa` 의존성 선언
- **의존성**: Spring Dependency Management BOM은 루트 `build.gradle`에서 전이되므로 버전 명시 불필요
- **적용 컨벤션**: `docs/CONVENTION.md` 7절 — 라이브러리에 전이되어야 하는 의존성은 `api`, 내부 전용은 `implementation`
- **참조할 기존 코드**: `services/libs/service-client/build.gradle.kts:1-3` (java-library 플러그인 + 의존성 선언 패턴)
- **연관 todo**: `[ ] services/libs/common-infra/build.gradle.kts 신규 작성` (2번 단계)

---

#### 모듈 / 패키지: `services/libs/member/`

```
services/libs/member/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── java/com/econo/auth/member/
    │   │   ├── config/
    │   │   │   └── MemberAutoConfiguration.java          — 모듈 자동 스캔 설정
    │   │   ├── domain/
    │   │   │   ├── Member.java                           — Aggregate Root (불변 도메인 객체)
    │   │   │   └── MemberStatus.java                     — 활동 상태 Enum
    │   │   ├── application/
    │   │   │   ├── port/
    │   │   │   │   ├── in/
    │   │   │   │   │   └── SignupUseCase.java             — 인바운드 포트 + SignupCommand record
    │   │   │   │   └── out/
    │   │   │   │       ├── MemberRepository.java         — 영속성 아웃바운드 포트
    │   │   │   │       └── PasswordHasher.java           — 해싱 아웃바운드 포트
    │   │   │   └── usecase/
    │   │   │       └── SignupService.java                — SignupUseCase 구현체 (no @Component)
    │   │   ├── adapter/out/
    │   │   │   ├── persistence/
    │   │   │   │   ├── MemberJpaEntity.java              — members 테이블 JPA 엔티티
    │   │   │   │   ├── MemberJpaRepository.java          — Spring Data JPA 인터페이스
    │   │   │   │   └── MemberRepositoryAdapter.java      — MemberRepository 포트 구현체
    │   │   │   └── security/
    │   │   │       └── BCryptPasswordHasherAdapter.java  — PasswordHasher 포트 BCrypt 구현체
    │   │   └── exception/
    │   │       ├── MemberAlreadyExistsException.java
    │   │       ├── MemberNotFoundException.java
    │   │       ├── InvalidCredentialsException.java
    │   │       └── InvalidPasswordPolicyException.java
    │   └── resources/
    │       ├── META-INF/spring/
    │       │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       └── db/migration/
    │           ├── V1__create_members_table.sql
    │           ├── V2__create_sas_tables.sql
    │           ├── V3__create_spring_session_tables.sql
    │           ├── V4__create_service_client_and_route.sql
    │           └── V5__make_grant_type_nullable.sql
    └── test/
        └── java/com/econo/auth/member/
            ├── TestMemberApplication.java                — 테스트용 부트스트랩
            ├── domain/
            │   └── MemberTest.java
            ├── application/usecase/
            │   └── SignupServiceTest.java
            └── adapter/out/
                ├── persistence/
                │   └── MemberRepositoryAdapterTest.java
                └── security/
                    └── BCryptPasswordHasherAdapterTest.java
```

##### `MemberAutoConfiguration`
- **타입**: AutoConfiguration
- **책임**: `com.econo.auth.member` 패키지 전체를 컴포넌트 스캔하고, `adapter.out.persistence` 패키지의 JPA Repository와 Entity를 등록한다.
- **주요 메서드/함수**: 없음 (어노테이션 전용)
- **의존성**: `spring-boot-starter-data-jpa`, `common-infra`(JPA Auditing 활성화 전이)
- **적용 컨벤션**:
  - 네이밍: `{Domain}AutoConfiguration` → `MemberAutoConfiguration` (`docs/CONVENTION.md` 1.2절)
  - 어노테이션 구성: `@AutoConfiguration` + `@ComponentScan("com.econo.auth.member")` + `@EnableJpaRepositories("com.econo.auth.member.adapter.out.persistence")` + `@EntityScan("com.econo.auth.member.adapter.out.persistence")` — `ServiceClientAutoConfiguration`과 동일한 4-어노테이션 패턴
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java:15-19`
- **연관 todo**: `[ ] MemberAutoConfiguration.java 신규 작성` (5번 단계)

##### `Member` (이동)
- **타입**: Domain (Aggregate Root)
- **책임**: 회원 불변 도메인 객체. `create`(신규 회원)·`restore`(JPA 복원) 정적 팩토리 메서드 제공.
- **주요 메서드/함수**: `Member.create(name, loginId, hashedPassword, generation, status)`, `Member.restore(..., createdAt)`
- **의존성**: `MemberStatus` (동일 패키지)
- **적용 컨벤션**:
  - 불변성: 모든 필드 `private final`, 방어적 복사 불필요(컬렉션 없음) (`docs/CONVENTION.md` 2.3절)
  - 생성자: 정적 팩토리 메서드 패턴 사용, private 생성자 직접 작성 (`docs/CONVENTION.md` 2.2절 — `@JsonCreator`나 생성자 내부 로직이 필요하므로 직접 작성)
  - Lombok: `@Getter` 클래스 레벨 선언
- **패키지 변환**: `com.econo.auth.core.member.domain` → `com.econo.auth.member.domain`
- **참조할 기존 코드**: `services/libs/auth-core/src/main/java/com/econo/auth/core/member/domain/Member.java:1`
- **연관 todo**: `[ ] 도메인 2개 이동 → member/src/main/java/com/econo/auth/member/domain/` (4번 단계)

##### `MemberStatus` (이동)
- **타입**: Domain (Enum)
- **책임**: 회원 활동 상태 (AM / RM / CM / OB)
- **패키지 변환**: `com.econo.auth.core.member.domain` → `com.econo.auth.member.domain`
- **적용 컨벤션**: 도메인 객체 네이밍 규칙 (`docs/CONVENTION.md` 1.2절)
- **참조할 기존 코드**: `services/libs/auth-core/src/main/java/com/econo/auth/core/member/domain/MemberStatus.java`
- **연관 todo**: `[ ] 도메인 2개 이동` (4번 단계)

##### `SignupUseCase` (이동)
- **타입**: 인바운드 포트 (UseCase 인터페이스)
- **책임**: 회원 가입 인바운드 포트. 내부에 `SignupCommand` record 포함.
- **주요 메서드/함수**: `void signup(SignupCommand command)`
- **패키지 변환**: `com.econo.auth.core.member.application.port.in` → `com.econo.auth.member.application.port.in`
- **적용 컨벤션**: 인바운드 포트 네이밍 `{Action}UseCase` (`docs/CONVENTION.md` 1.2절)
- **참조할 기존 코드**: `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/port/in/SignupUseCase.java:1`
- **연관 todo**: `[ ] 인바운드 포트 1개 이동` (4번 단계)

##### `MemberRepository` (이동)
- **타입**: 아웃바운드 포트 (인터페이스)
- **책임**: 회원 영속성 아웃바운드 포트
- **주요 메서드/함수**: `void save(Member)`, `Optional<Member> findByLoginId(String)`, `boolean existsByLoginId(String)`, `Optional<Member> findById(Long)`, `List<Member> findAllByIds(List<Long>)`
- **패키지 변환**: `com.econo.auth.core.member.application.port.out` → `com.econo.auth.member.application.port.out`
- **적용 컨벤션**: 아웃바운드 포트 네이밍 `{Resource}{Role}` → `MemberRepository` (`docs/CONVENTION.md` 1.2절)
- **연관 todo**: `[ ] 아웃바운드 포트 2개 이동` (4번 단계)

##### `PasswordHasher` (이동)
- **타입**: 아웃바운드 포트 (인터페이스)
- **책임**: 비밀번호 해싱 아웃바운드 포트
- **주요 메서드/함수**: `String hash(String rawPassword)`, `boolean matches(String rawPassword, String hashedPassword)`
- **패키지 변환**: `com.econo.auth.core.member.application.port.out` → `com.econo.auth.member.application.port.out`
- **연관 todo**: `[ ] 아웃바운드 포트 2개 이동` (4번 단계)

##### `SignupService` (이동)
- **타입**: 유스케이스 구현체 (일반 클래스, `@Component` 없음)
- **책임**: `SignupUseCase` 구현. loginId·name·generation·비밀번호 정책 검증 후 회원 저장.
- **주요 메서드/함수**: `void signup(SignupCommand command)`
- **의존성**: `MemberRepository`, `PasswordHasher` (생성자 주입 — `@RequiredArgsConstructor`)
- **적용 컨벤션**:
  - `@Component` 미부착 유지 — `auth-api`의 `ApplicationServiceConfig`에서 `@Bean`으로 수동 등록하는 헥사고날 원칙 (`docs/ARCHITECTURE.md` "핵심 설계 결정 #6")
  - Lombok: `@RequiredArgsConstructor` (`docs/CONVENTION.md` 2.2절)
  - 예외: `InvalidPasswordPolicyException.of(...)` 정적 팩토리, `MemberAlreadyExistsException.of(...)` 정적 팩토리 (`docs/CONVENTION.md` 3.1절)
- **패키지 변환**: `com.econo.auth.core.member.application.usecase` → `com.econo.auth.member.application.usecase`
- **참조할 기존 코드**: `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/usecase/SignupService.java:1`
- **연관 todo**: `[ ] 유스케이스 구현체 1개 이동` (4번 단계), `[ ] SignupService에 @Component 없는지 확인` (8번 단계)

##### `MemberJpaEntity` (이동)
- **타입**: JPA 어댑터 (Entity)
- **책임**: `members` 테이블 JPA 엔티티. `from(Member)` / `toDomain()` 정적 팩토리·변환 메서드 제공.
- **의존성**: `Member`, `MemberStatus` (같은 모듈, 패키지 변환 후 `com.econo.auth.member.domain.*`)
- **적용 컨벤션**:
  - `@EntityListeners(AuditingEntityListener.class)` 유지 — `CommonInfraAutoConfiguration`이 `@EnableJpaAuditing` 활성화 담당
  - Lombok: `@Getter`, `@NoArgsConstructor(access = PROTECTED)` (`docs/CONVENTION.md` 2.2절)
  - JPA 어댑터 네이밍: `{Name}JpaEntity` (`docs/CONVENTION.md` 1.2절)
- **패키지 변환**: `com.econo.auth.infra.member.adapter.out.persistence` → `com.econo.auth.member.adapter.out.persistence`, 내부 import `com.econo.auth.core.member.domain.*` → `com.econo.auth.member.domain.*`
- **참조할 기존 코드**: `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/persistence/MemberJpaEntity.java:1`
- **연관 todo**: `[ ] Persistence 어댑터 3개 이동` (4번 단계)

##### `MemberJpaRepository` (이동)
- **타입**: JPA 어댑터 (Spring Data JPA 인터페이스)
- **책임**: `MemberJpaEntity` Spring Data JPA Repository
- **패키지 변환**: `com.econo.auth.infra.member.adapter.out.persistence` → `com.econo.auth.member.adapter.out.persistence`
- **연관 todo**: `[ ] Persistence 어댑터 3개 이동` (4번 단계)

##### `MemberRepositoryAdapter` (이동)
- **타입**: JPA 어댑터 (`MemberRepository` 포트 구현체)
- **책임**: `MemberRepository` 포트 JPA 구현. `MemberJpaRepository` 위임.
- **의존성**: `MemberJpaRepository` (생성자 주입), `MemberJpaEntity`
- **적용 컨벤션**:
  - `@Component`, `@RequiredArgsConstructor` (`docs/CONVENTION.md` 2.2절)
  - `@Transactional` / `@Transactional(readOnly = true)` 메서드별 선언 유지
  - JPA 어댑터 네이밍: `{Name}RepositoryAdapter` (`docs/CONVENTION.md` 1.2절)
- **패키지 변환**: `com.econo.auth.infra.member.adapter.out.persistence` → `com.econo.auth.member.adapter.out.persistence`, 내부 import 전체 갱신
- **참조할 기존 코드**: `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/persistence/MemberRepositoryAdapter.java:1`
- **연관 todo**: `[ ] Persistence 어댑터 3개 이동` (4번 단계)

##### `BCryptPasswordHasherAdapter` (이동)
- **타입**: Security 어댑터 (`PasswordHasher` 포트 구현체)
- **책임**: BCrypt(cost=12) 기반 비밀번호 해싱·검증
- **의존성**: `spring-security-crypto` (`BCryptPasswordEncoder`)
- **적용 컨벤션**:
  - `@Component` 선언 유지 (`MemberAutoConfiguration`의 `@ComponentScan`이 자동 감지)
  - 보안 어댑터 네이밍: `{Algo}{Role}Adapter` → `BCryptPasswordHasherAdapter` (`docs/CONVENTION.md` 1.2절)
- **패키지 변환**: `com.econo.auth.infra.member.adapter.out.security` → `com.econo.auth.member.adapter.out.security`, import `com.econo.auth.core.member.application.port.out.PasswordHasher` → `com.econo.auth.member.application.port.out.PasswordHasher`
- **참조할 기존 코드**: `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/security/BCryptPasswordHasherAdapter.java:1`
- **연관 todo**: `[ ] Security 어댑터 1개 이동` (4번 단계)

##### 예외 클래스 4개 (이동)
- **타입**: Domain 예외
- **클래스**: `MemberAlreadyExistsException`, `MemberNotFoundException`, `InvalidCredentialsException`, `InvalidPasswordPolicyException`
- **책임**: Member 도메인 예외. 정적 팩토리 메서드 패턴 사용.
- **적용 컨벤션**: 예외 네이밍 `{Domain}Exception` 또는 `{Description}Exception` (`docs/CONVENTION.md` 1.2절); 정적 팩토리 메서드 패턴 (`docs/CONVENTION.md` 3.1절)
- **패키지 변환**: `com.econo.auth.core.member.exception` → `com.econo.auth.member.exception`
- **연관 todo**: `[ ] 예외 4개 이동` (4번 단계)

##### `member/build.gradle.kts`
- **타입**: Gradle 빌드 파일
- **책임**: `java-library` 플러그인, 의존성 선언
- **의존성**:
  - `implementation("org.springframework.boot:spring-boot-starter-data-jpa")` — JPA, AuditingEntityListener
  - `implementation("org.springframework.security:spring-security-crypto")` — BCryptPasswordEncoder
  - `implementation(project(":services:libs:common-infra"))` — JPA Auditing AutoConfiguration 전이 활성화
  - `implementation("org.flywaydb:flyway-core")` — Flyway 마이그레이션 (auth-infra에서 이전)
  - `runtimeOnly("org.postgresql:postgresql")`
  - `testImplementation("org.testcontainers:postgresql")`, `testImplementation("org.testcontainers:junit-jupiter")`
- **참조할 기존 코드**: `services/libs/auth-infra/build.gradle.kts:1-13`, `services/libs/service-client/build.gradle.kts:1-14`
- **연관 todo**: `[ ] services/libs/member/build.gradle.kts 신규 작성` (1번 단계)

##### `TestMemberApplication` (신규)
- **타입**: 테스트 부트스트랩
- **책임**: member 모듈 JPA 슬라이스 테스트(`@DataJpaTest`) 실행 시 필요한 `@SpringBootApplication` 제공.
- **주요 메서드/함수**: 없음 (빈 부트스트랩 클래스)
- **적용 컨벤션**:
  - `@SpringBootApplication` 단독 선언 — `@EnableJpaAuditing`을 직접 붙이지 않음. `CommonInfraAutoConfiguration`이 AutoConfiguration으로 `@EnableJpaAuditing`을 담당하므로 테스트 부트스트랩에서 중복 선언하면 `@DataJpaTest` 슬라이스가 `EnableJpaAuditing` 빈 이중 등록 경고를 발생시킬 수 있다.
  - 단, `@DataJpaTest`는 AutoConfiguration을 로드하지 않으므로 `MemberRepositoryAdapterTest`에서 `@EnableJpaAuditing`이 필요하다면 `TestMemberApplication` 대신 테스트 클래스 자체에 `@Import(CommonInfraAutoConfiguration.class)`를 선언하거나, `TestMemberApplication`에 `@EnableJpaAuditing`을 부착하는 방법 중 선택이 필요하다. `auth-infra`의 `TestInfraApplication`이 `@EnableJpaAuditing`을 직접 선언했던 선례(`services/libs/auth-infra/src/test/java/com/econo/auth/infra/TestInfraApplication.java:8`)를 따라 `TestMemberApplication`에도 `@EnableJpaAuditing`을 부착한다. (프로덕션 코드의 `CommonInfraAutoConfiguration`과는 별도 테스트 컨텍스트이므로 중복 문제 없음)
- **경로**: `member/src/test/java/com/econo/auth/member/TestMemberApplication.java`
- **연관 todo**: `[ ] TestMemberApplication.java 신규 작성` (기타 작업 — 테스트 이동)

---

#### 변경 파일: `services/apis/auth-api/`

##### `build.gradle.kts` (변경)
- `implementation(project(":services:libs:auth-core"))` 제거
- `implementation(project(":services:libs:auth-infra"))` 제거
- `implementation(project(":services:libs:member"))` 추가
- `implementation("org.springframework.boot:spring-boot-starter-data-jpa")` 제거 — `member` 모듈의 `spring-boot-starter-data-jpa`가 `implementation` 전이로 전달되지 않으므로 **삭제 후 `./gradlew :services:apis:auth-api:compileJava`로 컴파일 통과 확인 필수**. 실패 시 다시 추가한다.
- **연관 todo**: `[ ] auth-api/build.gradle.kts 수정` (6번 단계)

##### `ApplicationServiceConfig.java` (변경)
- import 3개 갱신:
  - `com.econo.auth.core.member.application.port.out.MemberRepository` → `com.econo.auth.member.application.port.out.MemberRepository`
  - `com.econo.auth.core.member.application.port.out.PasswordHasher` → `com.econo.auth.member.application.port.out.PasswordHasher`
  - `com.econo.auth.core.member.application.usecase.SignupService` → `com.econo.auth.member.application.usecase.SignupService`
- 로직 변경 없음
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java:3-5`
- **연관 todo**: `[ ] ApplicationServiceConfig.java — import 경로 갱신` (6번 단계)

##### `MemberUserDetailsService.java` (변경)
- import 1개 갱신: `com.econo.auth.core.member.application.port.out.MemberRepository` → `com.econo.auth.member.application.port.out.MemberRepository`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetailsService.java:4`
- **연관 todo**: `[ ] MemberUserDetailsService.java — import 경로 갱신` (6번 단계)

##### `MemberUserDetails.java` (변경)
- import 1개 갱신: `com.econo.auth.core.member.domain.Member` → `com.econo.auth.member.domain.Member`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetails.java:3`
- **연관 todo**: `[ ] MemberUserDetails.java — Member 도메인 import 경로 갱신` (6번 단계)

##### `MemberController.java` (변경)
- import 2개 갱신:
  - `com.econo.auth.core.member.application.port.in.SignupUseCase` → `com.econo.auth.member.application.port.in.SignupUseCase`
  - `com.econo.auth.core.member.application.port.in.SignupUseCase.SignupCommand` → `com.econo.auth.member.application.port.in.SignupUseCase.SignupCommand`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberController.java:3-4`
- **연관 todo**: `[ ] MemberController.java — import 갱신` (6번 단계)

##### `MemberInfoController.java` (변경)
- import 2개 갱신:
  - `com.econo.auth.core.member.application.port.out.MemberRepository` → `com.econo.auth.member.application.port.out.MemberRepository`
  - `com.econo.auth.core.member.domain.Member` → `com.econo.auth.member.domain.Member`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberInfoController.java:3-4`
- **연관 todo**: `[ ] MemberController.java, MemberInfoController.java — import 갱신` (6번 단계)

##### `GlobalExceptionHandler.java` (변경)
- import 3개 갱신:
  - `com.econo.auth.core.member.exception.InvalidPasswordPolicyException` → `com.econo.auth.member.exception.InvalidPasswordPolicyException`
  - `com.econo.auth.core.member.exception.MemberAlreadyExistsException` → `com.econo.auth.member.exception.MemberAlreadyExistsException`
  - `com.econo.auth.core.member.exception.MemberNotFoundException` → `com.econo.auth.member.exception.MemberNotFoundException`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java:7-9`
- **연관 todo**: `[ ] GlobalExceptionHandler.java — 예외 import 경로 갱신` (6번 단계)

##### `MemberControllerTest.java` (변경 — 테스트)
- import 3개 갱신:
  - `com.econo.auth.core.member.application.port.in.SignupUseCase` → `com.econo.auth.member.application.port.in.SignupUseCase`
  - `com.econo.auth.core.member.exception.InvalidPasswordPolicyException` → `com.econo.auth.member.exception.InvalidPasswordPolicyException`
  - `com.econo.auth.core.member.exception.MemberAlreadyExistsException` → `com.econo.auth.member.exception.MemberAlreadyExistsException`
- **참조할 기존 코드**: `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/MemberControllerTest.java:10-12`
- **연관 todo**: `[ ] auth-api 웹 레이어 테스트 — member 예외 import 경로 갱신` (기타 작업)

##### `AuthApiIntegrationTest.java` (변경 — 테스트)
- 통합 테스트 파일에서 `com.econo.auth.core.member.*` import 참조 여부 확인 후 갱신. (현재 확인된 import 없음, 갱신 불필요 가능성 있으나 검증 필수)
- **연관 todo**: `[ ] auth-api 통합 테스트 — member 예외 import 경로 갱신 (참조 있는 경우만)` (기타 작업)

---

#### 변경 파일: `services/libs/service-client/`

##### `build.gradle.kts` (변경)
- `implementation(project(":services:libs:auth-infra"))` 제거
- `implementation(project(":services:libs:common-infra"))` 추가
- 코드 자체 변경 없음 — `JpaAuditingConfig`를 직접 import하지 않고 AutoConfiguration 메커니즘으로 활성화되므로
- **참조할 기존 코드**: `services/libs/service-client/build.gradle.kts:13`
- **연관 todo**: `[ ] service-client/build.gradle.kts 수정` (7번 단계)

---

#### 변경 파일: `settings.gradle.kts`

- `include("services:libs:auth-core")` 제거
- `include("services:libs:auth-infra")` 제거
- `include("services:libs:member")` 추가
- `include("services:libs:common-infra")` 추가
- **참조할 기존 코드**: `settings.gradle.kts:7-10`
- **연관 todo**: (1번 단계) `[ ] settings.gradle.kts에 include("services:libs:member") 추가`, (2번 단계) `[ ] settings.gradle.kts에 include("services:libs:common-infra") 추가`, (9번 단계) auth-core·auth-infra 제거

---

### 호출 흐름

#### 회원 가입 (정상 경로)
```
POST /api/v1/auth/signup
  → MemberController.signup(SignupRequest)
  → SignupUseCase.signup(SignupCommand)                     [auth-api → member 모듈 포트]
  → SignupService.signup(SignupCommand)                     [member 모듈 usecase]
      → validateLoginId / validateName / validateGeneration / validatePasswordPolicy
      → MemberRepository.existsByLoginId(loginId)          [member 포트 out]
      → PasswordHasher.hash(password)                      [member 포트 out]
      → Member.create(...)                                  [member 도메인]
      → MemberRepository.save(member)                      [member 포트 out]
  → MemberRepositoryAdapter.save(member)                   [member adapter out persistence]
      → MemberJpaEntity.from(member)
      → MemberJpaRepository.save(entity)                   [Spring Data JPA]
  → 201 Created
```

#### 회원 가입 (예외 경로)
```
SignupService.validateLoginId()
  → IllegalArgumentException("loginId는 3~19자...")
  → GlobalExceptionHandler.handleIllegalArgument() → 400 INVALID_ARGUMENT

SignupService.validatePasswordPolicy()
  → InvalidPasswordPolicyException.of("...")              [com.econo.auth.member.exception]
  → GlobalExceptionHandler.handleInvalidPasswordPolicy() → 400 INVALID_PASSWORD_POLICY

MemberRepository.existsByLoginId() → true
  → MemberAlreadyExistsException.of(loginId)             [com.econo.auth.member.exception]
  → GlobalExceptionHandler.handleMemberAlreadyExists() → 409 MEMBER_ALREADY_EXISTS
```

#### 로그인 (정상 경로)
```
POST /api/v1/auth/login (JSON)
  → JsonLoginAuthenticationFilter
  → DaoAuthenticationProvider
  → MemberUserDetailsService.loadUserByUsername(loginId)  [auth-api, MemberRepository 포트 사용]
  → MemberRepository.findByLoginId(loginId)               [member 포트 out]
  → MemberRepositoryAdapter.findByLoginId()               [member adapter out persistence]
  → DaoAuthenticationProvider: BCrypt 비밀번호 검증       [BCryptPasswordEncoder 직접 사용, auth-api SecurityConfig에서 주입]
  → 세션 수립 → 200 OK + Set-Cookie: SESSION=...
```

#### 로그인 (예외 경로)
```
MemberUserDetailsService.loadUserByUsername() — loginId 미존재
  → UsernameNotFoundException
  → JsonLoginAuthenticationFilter.onAuthenticationFailure() → 401 INVALID_CREDENTIALS
  (GlobalExceptionHandler 경유 없음)
```

#### JPA Auditing 활성화 경로
```
common-infra 모듈 로드 시
  → CommonInfraAutoConfiguration (AutoConfiguration)
  → @EnableJpaAuditing 활성화
  → AuditingEntityListener가 @CreatedDate / @LastModifiedDate 자동 적용
  → MemberJpaEntity.createdAt 자동 주입
  → ServiceClientJpaEntity 등 모든 JPA 엔티티도 동일하게 적용
```

---

### 컨벤션 준수 항목

- **네이밍**: 패키지는 소문자 연결 `com.econo.auth.member`, `com.econo.auth.commoninfra`. 클래스는 PascalCase + 역할 접미사. `{Domain}AutoConfiguration`, `{Name}RepositoryAdapter`, `{Algo}{Role}Adapter` (`docs/CONVENTION.md` 1.1–1.2절)
- **의존성 주입**: `@RequiredArgsConstructor` + `private final` 필드 주입 우선. 직접 생성자는 생성자 내부 로직이 필요한 경우만 (`docs/CONVENTION.md` 2.2절)
- **예외 처리**: 정적 팩토리 메서드 패턴 (`MemberAlreadyExistsException.of(...)`, `InvalidPasswordPolicyException.of(...)`). `GlobalExceptionHandler`에서 HTTP 상태 코드·에러 코드 매핑 (`docs/CONVENTION.md` 3.1절)
- **불변성**: 도메인 객체 필드 `private final`, 컬렉션은 `List.copyOf()` (`docs/CONVENTION.md` 2.3절)
- **테스트 패턴**: `@Nested` + `@DisplayName` 한글, Given-When-Then 주석, AssertJ. JPA 통합 테스트는 `@DataJpaTest` + Testcontainers PostgreSQL (`docs/CONVENTION.md` 5절)
- **Javadoc**: 모든 public 클래스·메서드에 Javadoc 작성, `@param`/`@return` 태그 필수 (`docs/CONVENTION.md` 4절)
- **AutoConfiguration 등록**: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 FQCN 한 줄 등록 (Spring Boot 3.x 방식)
- **iCloud 충돌본 주의**: 파일 이동 후 `find services -name '* 2.java'` 실행하여 충돌본 0건 확인. 발견 시 즉시 `rm` 제거 (`.claude/memory/icloud-duplicate-files-gotcha.md`)
- **코드 포맷팅**: 모든 변경 후 `./gradlew format` 적용 → `./gradlew spotlessCheck` 통과 확인 (`docs/CONVENTION.md` 2.1절)

---

## 체크리스트

- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두)

---

## 참고
- `docs/ARCHITECTURE.md` — 헥사고날 패키지 구조, AutoConfiguration 컨벤션 기준
- `docs/CONVENTION.md` — 네이밍, Lombok, 불변성, 예외 처리, 테스트 패턴
- `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java` — AutoConfiguration 작성 기준 패턴
- `services/libs/service-client/build.gradle.kts` — java-library 모듈 빌드 패턴
- `services/libs/auth-infra/src/test/java/com/econo/auth/infra/TestInfraApplication.java` — 테스트 부트스트랩에서 @EnableJpaAuditing 선언 선례
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — SignupService @Bean 수동 등록 패턴
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 예외 import 갱신 대상 (3개)
