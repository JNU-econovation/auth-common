# refactor-member-module - report

## 메타
- **작업명**: refactor-member-module
- **작성일**: 2026-06-04
- **브랜치**: feat/member-auth
- **plan 문서**:
  - todo.md
  - api-design-plan.md (API 변경 없음 명시)
  - implementation-plan.md
  - db-design-plan.md (DB 변경 없음 명시)
- **목적**: `services/libs/auth-core` + `services/libs/auth-infra` 두 기존 모듈을 폐기하고, 직전 `service-client` 모듈과 동일한 헥사고날 + AutoConfiguration 패턴의 신규 모듈 2개(`member`, `common-infra`)로 재편.

---

## 진행 결과

### 1. test
- **방침**: Option A — 신규 테스트 작성 없음. 기존 4개 테스트 + TestInfraApplication을 새 모듈로 이전하여 회귀 회로로 활용.
- **근거**: 본 작업은 순수 모듈/패키지 리팩터링. 행위 변화 없음 → 새 검증 대상 없음. `extract-service-client-module`과 동일 패턴.

### 2. implementation
- **신규 모듈**: 2개 (`services/libs/member/`, `services/libs/common-infra/`)
- **신규 파일 합계**: 39파일 (member 27 + common-infra 5 + 기타)
- **삭제 디렉터리**: 2개 (`services/libs/auth-core/`, `services/libs/auth-infra/`)
- **변경 파일**: auth-api 9파일 (build.gradle.kts + AuthApiApplication + 8 import 갱신) + service-client 2파일 (build.gradle.kts + README) + settings.gradle.kts
- **빌드/테스트 결과**:
  - `./gradlew clean build` — BUILD SUCCESSFUL (52 tasks)
  - `./gradlew test` — Docker 무관 실패 2건 유지 (`MemberRepositoryAdapterTest`, `AuthApiIntegrationTest`의 Testcontainers initializationError, baseline 동일)
  - member 모듈: 30 tests / 1 failed (Docker baseline) — Green
  - auth-api 모듈: 35 tests / 1 failed / 1 skipped (Docker baseline) — Green
- **iCloud 충돌본**: 0건
- **plan과의 차이 (정당 사유)**:
  - `AuthApiApplication.scanBasePackages` 제거 (auth-core/auth-infra 패키지 삭제로 인한 필수 조정. AutoConfiguration이 빈 등록 책임)
  - `LoginTokenService.java`, `SignupRequest.java` import 갱신 (plan 체크리스트 미포함이나 빌드 필수)
  - `spring-boot-starter-data-jpa`는 auth-api에 재추가 — plan "실패 시 재추가" 지침대로 처리. 사유: GlobalExceptionHandler가 `DataIntegrityViolationException`, RegisteredClientConfig가 `JdbcOperations` 직접 사용.

### 3. code-review
- **리뷰 항목**: 10건 (반영 권장 5 / 참고 5)
- **반영 5건**:
  - #1 [major] `MemberNotFoundException` 정적 팩토리 `of(Long)` 패턴 적용 — 형제 예외(3개)와 일관성. `LoginTokenService:74` 호출부 동시 갱신.
  - #2 [minor] `MemberRepository.findById` Javadoc 추가 — 형제 메서드 4개와 일관성.
  - #4 [major] `member/build.gradle.kts`의 `common-infra` 의존을 `implementation` → `api` — `CommonInfraAutoConfiguration`이 활성화하는 `@EnableJpaAuditing`을 소비자(auth-api)에 전이 명시. (CONVENTION.md 7절)
  - 참고 #2 [minor] `MemberRepositoryAdapterTest`에서 `org.springframework.dao.DataAccessException` FQN을 짧은 이름 + import로 (메모리 import 컨벤션).
  - 참고 #3 [minor] `GlobalExceptionHandler.handleMemberNotFound` Javadoc 복붙 오류("loginId 중복 예외 처리" → 정확한 회원 미존재 설명) 수정.
- **반영 보류**:
  - #3 [major] `spring-boot-starter-data-jpa` 재추가 — implementer가 plan "실패 시 재추가" 지침대로 결정 완료한 사항. 추가 반영 불필요로 판정.
  - #5 [minor] `docs/ARCHITECTURE.md` 미갱신 — 4단계 docs에서 일괄 처리 예정.
- **재검증 결과**: Green 유지 (Docker baseline 동일).

### 4. docs
- **갱신 MODIFIED (3)**:
  - `docs/ARCHITECTURE.md` — 모듈 트리·의존성 그래프·역할 표·`member` 패키지 구조 섹션·핵심 설계 결정 #6·에러 코드 체계·테스트 구조 전반 갱신. `INVALID_LOGIN_ID_FORMAT` → `INVALID_ARGUMENT` 실제 코드와 일치하도록 수정.
  - `docs/INFRASTRUCTURE.md` — Flyway 위치 (`services/libs/member/src/main/resources/db/migration/`), 모듈 참조 갱신.
  - `docs/CONVENTION.md` — 1.1 패키지 섹션 모듈 예시 갱신.
- **신규 CREATED (2)**: README-GUIDE 필수 섹션 5종 준수
  - `services/libs/member/README.md`
  - `services/libs/common-infra/README.md`
- **검토 후 변경 없음**: SEQUENCE-DIAGRAMS.md, FEATURES.md, CLIENT_REGISTRATION.md, DYNAMIC_ROUTING.md, passport-claims-reference.md, outcomes/ 전체, auth-api/README.md, register-service/SKILL.md — 모두 패키지 경로 미노출.

### 5. doc-review
- **리뷰 항목**: 7건 (반영 권장 4 / 참고 3)
- **반영 4건**:
  - #1 [critical] `services/libs/member/README.md` 에러 코드 표 `InvalidCredentialsException` HTTP 상태 401 명시 (이전 `(미등록)`은 응답 없음 오해 가능).
  - #2 [major] `GlobalExceptionHandler.java:106` Javadoc `@return 400 INVALID_LOGIN_ID_FORMAT` → `INVALID_ARGUMENT` (실제 코드와 정합).
  - #3 [major] `docs/README-GUIDE.md:78` 예시 경로 `auth-infra` → `member` (폐기 모듈 참조 제거).
  - #4 [minor] `docs/README-GUIDE.md:13` Quick Reference Gradle 예시 경로 `auth-core` → `member`.
- **참고 3건 (반영 없음)**:
  - A: service-client/README.md의 `common-infra` 관계 서술이 member와 비대칭 (둘 의존 방식 `implementation` vs `api` 차이 미명시).
  - B: common-infra README "비즈니스 규칙" 섹션 형식 (내용은 적절).
  - C: INFRASTRUCTURE.md "테스트 환경" 표현 검토 (사실관계 OK).

---

## 변경 요약

### 신규 모듈 1: `services/libs/common-infra/` (5파일)
- `build.gradle.kts`
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `src/main/java/com/econo/auth/commoninfra/config/CommonInfraAutoConfiguration.java`
- `src/main/java/com/econo/auth/commoninfra/config/JpaAuditingConfig.java`
- `README.md`

### 신규 모듈 2: `services/libs/member/` (27파일)
- `build.gradle.kts`, `README.md`
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `src/main/resources/db/migration/{V1__create_members_table, V2__create_sas_tables, V3__create_spring_session_tables, V4__create_service_client_and_route, V5__make_grant_type_nullable}.sql` (5개)
- `src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java`
- `src/main/java/com/econo/auth/member/domain/{Member, MemberStatus}.java`
- `src/main/java/com/econo/auth/member/application/port/in/SignupUseCase.java`
- `src/main/java/com/econo/auth/member/application/port/out/{MemberRepository, PasswordHasher}.java`
- `src/main/java/com/econo/auth/member/application/usecase/SignupService.java`
- `src/main/java/com/econo/auth/member/adapter/out/persistence/{MemberJpaEntity, MemberJpaRepository, MemberRepositoryAdapter}.java`
- `src/main/java/com/econo/auth/member/adapter/out/security/BCryptPasswordHasherAdapter.java`
- `src/main/java/com/econo/auth/member/exception/{MemberNotFoundException, MemberAlreadyExistsException, InvalidPasswordPolicyException, InvalidCredentialsException}.java`
- `src/test/java/com/econo/auth/member/TestMemberApplication.java`
- `src/test/java/com/econo/auth/member/{domain/MemberTest, application/usecase/SignupServiceTest, adapter/out/persistence/MemberRepositoryAdapterTest, adapter/out/security/BCryptPasswordHasherAdapterTest}.java`

### 수정 main (9)
- `settings.gradle.kts` (auth-core/auth-infra 제거, member/common-infra 추가)
- `services/apis/auth-api/build.gradle.kts` (모듈 의존 갱신; spring-boot-starter-data-jpa 재추가)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/AuthApiApplication.java` (scanBasePackages 제거)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` (import 갱신)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/security/{MemberUserDetails, MemberUserDetailsService}.java` (import 갱신)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/{MemberController, MemberInfoController, SignupRequest}.java` (import 갱신)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/LoginTokenService.java` (import 갱신 + `MemberNotFoundException.of(id)` 호출)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` (import 갱신 + Javadoc 2건 수정)
- `services/libs/service-client/build.gradle.kts` (auth-infra → common-infra)

### 수정 test (1)
- `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/MemberControllerTest.java` (import 갱신)

### 삭제 디렉터리 (2)
- `services/libs/auth-core/` 전체 (12 main + 2 test 클래스)
- `services/libs/auth-infra/` 전체 (5 main + 1 config + 2 test + 5 Flyway)

### 갱신 docs (4) + 신규 README (2)
- `docs/ARCHITECTURE.md` (모듈 트리·의존성·역할·헥사고날 구조·핵심 설계 결정·에러 코드 체계·테스트 구조)
- `docs/INFRASTRUCTURE.md` (Flyway 위치·모듈 참조)
- `docs/CONVENTION.md` (1.1 패키지 예시)
- `docs/README-GUIDE.md` (예시 경로 2곳 폐기 모듈 참조 제거)
- `services/libs/member/README.md` (신규)
- `services/libs/common-infra/README.md` (신규)
- `services/libs/service-client/README.md` (관련 모듈 참조: `auth-infra` → `common-infra`)

---

## 헥사고날 분리 검증 체크리스트

| 항목 | 결과 |
|------|------|
| `member` 패키지 루트 일관 (`com.econo.auth.member`) | ✅ |
| `common-infra` 패키지 루트 일관 (`com.econo.auth.commoninfra`) | ✅ |
| 도메인 계층이 인프라/spring-web 의존 없음 | ✅ |
| usecase 계층이 port에만 의존 | ✅ |
| `MemberAutoConfiguration` 자기 스캔 (`@ComponentScan` + `@EnableJpaRepositories` + `@EntityScan`) | ✅ |
| `CommonInfraAutoConfiguration` `@EnableJpaAuditing` 활성화 | ✅ |
| `member → common-infra` `api` 의존 (전이 명시) | ✅ |
| `service-client → common-infra` `implementation` 의존 | ✅ |
| auth-api → member 의존, auth-core/auth-infra 잔재 없음 | ✅ |
| Flyway 5개 마이그레이션 위치 이전, 내용 무변경 | ✅ |
| API endpoint 무변경 | ✅ |
| DB 스키마 무변경 | ✅ |
| 예외 정적 팩토리 컨벤션 일관 (`of()` 패턴) | ✅ |
| iCloud 충돌본 0건 | ✅ |

---

## plan과의 차이

### plan 그대로 이행
- 신규 모듈 2개 생성 (`member`, `common-infra`)
- 헥사고날 + AutoConfiguration 자기 스캔 (`service-client` 패턴 일치)
- 기존 auth-core, auth-infra 폐기
- 의존성 그래프 재편 (auth-api → member → common-infra, service-client → common-infra)
- Flyway 마이그레이션 `member` 모듈로 이전 (`spring.flyway.locations: classpath:db/migration` 그대로 동작)
- auth-api 회원 inbound 어댑터(Controller, Filter, UserDetailsService)는 유지
- API endpoint·DB 스키마 무변경

### implementation 단계 정당 차이
- `AuthApiApplication.scanBasePackages` 제거 (auth-core/auth-infra 패키지 삭제 → 필수 조정)
- `LoginTokenService`, `SignupRequest` import 갱신 (plan 체크리스트 미포함이나 빌드 필수)
- `spring-boot-starter-data-jpa` 재추가 (plan 지침 "실패 시 재추가" 그대로 따름)
- `member/build.gradle.kts` common-infra 의존을 `api`로 (code-review 반영 결과)

### code-review에서 발견된 major 1건
- **`MemberNotFoundException` 정적 팩토리 컨벤션 위반** — 형제 예외 3개는 `private 생성자 + of()` 패턴인데 이 클래스만 public 생성자. CONVENTION.md 3.1절. 호출부 1곳(`LoginTokenService:74`) 함께 갱신.

### doc-review에서 발견된 critical 1건
- **`member/README.md` 에러 코드 표 `InvalidCredentialsException` HTTP 상태 누락** — `(미등록)` 표기가 응답이 없다는 오해를 줌. 실제는 `JsonLoginAuthenticationFilter`가 401 JSON 응답 반환. `401 (직접 응답)`으로 명확화.

---

## 다음 단계

- `/commit` 으로 커밋 (단일 또는 그룹별 분리)
- `/git-pr` 로 PR 생성 (또는 그냥 push)
