# member

Member 도메인 라이브러리. 회원 엔티티·회원가입 유스케이스·포트·예외·JPA 어댑터·BCrypt 어댑터·Flyway 마이그레이션을 헥사고날 구조로 포함한다. Spring Boot AutoConfiguration(`MemberAutoConfiguration`)으로 자기 스캔하며, `common-infra`를 통해 JPA Auditing을 소비자에 전이 활성화한다.

---

## Quick Reference

| 항목 | 값 |
|------|-----|
| 패키지 루트 | `com.econo.auth.member` |
| Gradle 의존 경로 | `implementation(project(":services:libs:member"))` |
| 주요 연관 모듈 | `common-infra` (JpaAuditing 제공), `auth-api` (소비자) |
| AutoConfiguration | `com.econo.auth.member.config.MemberAutoConfiguration` |
| API 엔드포인트 | 해당 없음 — libs 모듈. 소비자: `MemberController` (`/api/v1/auth/signup`), `MemberInfoController` (`/api/v1/members/*`) |

---

## 비즈니스 규칙

- **loginId 형식**: 3~19자, 영숫자·`-`·`_`·`.`만 허용. 위반 시 `IllegalArgumentException`(에러 코드 `INVALID_ARGUMENT`) → `GlobalExceptionHandler`가 400으로 매핑.
- **비밀번호 정책**: 8~19자, 대문자·소문자·숫자·특수기호 각 1자 이상 포함. `SignupService.validatePasswordPolicy`가 검증하고 `InvalidPasswordPolicyException.of(reason)`을 throw한다.
- **BCrypt cost**: `BCryptPasswordHasherAdapter`는 `BCryptPasswordEncoder(12)`를 사용한다 (cost=12).
- **generation 범위**: 1~99 사이 정수. 위반 시 `IllegalArgumentException`.
- **name 길이**: 1~50자. 위반 시 `IllegalArgumentException`.
- **loginId 중복**: `MemberRepository.existsByLoginId` 확인 후 중복이면 `MemberAlreadyExistsException.of(loginId)` throw.
- **JPA Auditing**: `Member.createdAt` / `Member.updatedAt` 필드의 `@CreatedDate` / `@LastModifiedDate` 자동 채움은 `common-infra`의 `CommonInfraAutoConfiguration`(`@EnableJpaAuditing`)이 담당한다. `member`가 `common-infra`를 `api` 의존으로 선언하여 소비자(`auth-api`)에 자동 전이된다.
- ⚠️ `SignupService`는 `@Component`가 아닌 일반 클래스다. `auth-api`의 `ApplicationServiceConfig`에서 `@Bean`으로 등록해야 한다.
- ⚠️ `MemberAutoConfiguration`이 `@EnableJpaRepositories` / `@EntityScan`으로 `com.econo.auth.member.adapter.out.persistence`를 직접 스캔한다. 다른 AutoConfiguration에서 이 패키지를 중복 선언하면 충돌이 발생한다.
- `InvalidCredentialsException` 클래스는 보존되어 있으나, SAS 도입 이후 `GlobalExceptionHandler`에 핸들러가 등록되어 있지 않다. `JsonLoginAuthenticationFilter`가 로그인 실패 시 직접 401 응답을 반환한다.

---

## 코드 진입점

| 구분 | 경로 |
|------|------|
| 도메인 | `services/libs/member/src/main/java/com/econo/auth/member/domain/` |
| 인바운드 포트 | `services/libs/member/src/main/java/com/econo/auth/member/application/port/in/` |
| 아웃바운드 포트 | `services/libs/member/src/main/java/com/econo/auth/member/application/port/out/` |
| 유스케이스 | `services/libs/member/src/main/java/com/econo/auth/member/application/usecase/` |
| JPA 어댑터 | `services/libs/member/src/main/java/com/econo/auth/member/adapter/out/persistence/` |
| BCrypt 어댑터 | `services/libs/member/src/main/java/com/econo/auth/member/adapter/out/security/` |
| AutoConfiguration | `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java` |
| 예외 | `services/libs/member/src/main/java/com/econo/auth/member/exception/` |
| Flyway 마이그레이션 | `services/libs/member/src/main/resources/db/migration/` |

---

## 에러 코드

> 에러 정의: `services/libs/member/src/main/java/com/econo/auth/member/exception/`

| 예외 클래스 | HTTP 매핑 | 에러 코드 | 발생 조건 |
|------------|-----------|-----------|-----------|
| `MemberNotFoundException` | 404 | MEMBER_NOT_FOUND | memberId로 회원을 찾을 수 없음 |
| `MemberAlreadyExistsException` | 409 | MEMBER_ALREADY_EXISTS | loginId 중복 가입 |
| `InvalidPasswordPolicyException` | 400 | INVALID_PASSWORD_POLICY | 비밀번호 정책 위반 |
| `InvalidCredentialsException` | 401 (직접 응답) | INVALID_CREDENTIALS | SAS 도입 이후 GlobalExceptionHandler 핸들러 미등록. `JsonLoginAuthenticationFilter`가 직접 401 JSON 응답을 반환한다. |

> 표의 HTTP 매핑은 `GlobalExceptionHandler`(`services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java`)가 명시적 `ResponseEntity`로 반환하는 실제 상태 코드다.

---

## 관련 모듈

| 모듈 | Gradle path | 관계 |
|------|-------------|------|
| common-infra | `:services:libs:common-infra` | JpaAuditing 제공 (`@EnableJpaAuditing` AutoConfiguration 선언 위치) — `api` 의존으로 소비자에 전이 |
| auth-api | `:services:apis:auth-api` | 소비자 — `MemberController`, `MemberInfoController`, `ApplicationServiceConfig`(SignupService 빈 등록), `GlobalExceptionHandler` |
