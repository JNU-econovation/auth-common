# auth-core

ECONO 자체 인증 도메인 모델과 비즈니스 로직. Spring Framework에 의존하지 않는 순수 도메인 계층.

## Quick Reference

| 항목 | 값 |
|---|---|
| 패키지 | `com.econo.auth.core` |
| Gradle 의존 (내부) | `implementation(project(":services:libs:auth-core"))` |
| 전이 의존 | `auth-common-lib` (`api` 의존) |
| 의존하는 내부 모듈 | `auth-common-lib` |

## 비즈니스 규칙

- **loginId 형식**: 3~19자, 영숫자·`-`·`_`·`.`만 허용 (`^[a-zA-Z0-9\-_.]{3,19}$`). 위반 시 `IllegalArgumentException`.
- **비밀번호 정책**: 8~19자, 대문자·소문자·숫자·특수기호 각 1자 이상 필수. 위반 시 `InvalidPasswordPolicyException` (HTTP 400).
- **generation 범위**: 1~99 사이 정수. 위반 시 `IllegalArgumentException`.
- **loginId 중복**: `MemberAlreadyExistsException` (HTTP 409). `MemberRepository.existsByLoginId()` 선행 체크 후 저장.
- **⚠️ 사용자 열거 방지**: 로그인 시 loginId 미존재·비밀번호 불일치 모두 동일 응답. SAS 도입 이후 Spring Security `DaoAuthenticationProvider`가 검증하고, `JsonLoginAuthenticationFilter`가 `INVALID_CREDENTIALS` 에러를 반환한다.
- **SAS JWT 클레임 계약**: `sub = String(memberId)`, `memberId(Long)`, `loginId`, `name`, `generation`, `status`, `roles(["USER"])`. `PassportTokenCustomizer`가 SAS 토큰 생성 시 주입. `roles`는 `["USER"]` 고정 — status는 활동 상태이지 권한이 아님.
- **⚠️ 프레임워크 의존성 금지**: `auth-core` 도메인 패키지는 Spring Framework에 의존하지 않는 순수 도메인 계층이다. 예외 클래스에 `HttpStatus`가 필드로 존재하나 이는 계층 경계에서 참조용으로만 사용한다.
- **빈 등록 책임**: `SignupService`는 `@Component`가 아닌 일반 클래스다. 헥사고날 아키텍처 원칙에 따라 `auth-api`의 `ApplicationServiceConfig`에서 `@Bean`으로 등록한다. `LoginService`는 SAS 도입으로 `MemberUserDetailsService`로 대체되어 제거됨.

> cross-module 인증 흐름은 [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) 참조.

## 코드 진입점

| 구분 | 파일 |
|---|---|
| Aggregate Root | `src/main/java/com/econo/auth/core/member/domain/Member.java` |
| 활동 상태 Enum | `src/main/java/com/econo/auth/core/member/domain/MemberStatus.java` |
| 인바운드 포트 (가입) | `src/main/java/com/econo/auth/core/member/application/port/in/SignupUseCase.java` |
| 아웃바운드 포트 | `src/main/java/com/econo/auth/core/member/application/port/out/MemberRepository.java` |
| 아웃바운드 포트 | `src/main/java/com/econo/auth/core/member/application/port/out/PasswordHasher.java` |
| 유스케이스 구현 | `src/main/java/com/econo/auth/core/member/application/usecase/SignupService.java` |
| 도메인 예외 | `src/main/java/com/econo/auth/core/member/exception/` |

> `LoginUseCase`, `LoginService`, `TokenIssuer`는 SAS 도입으로 제거됨. 로그인·토큰 발급은 Spring Authorization Server + `MemberUserDetailsService`가 전담.

## 에러 코드

> 에러 정의: `src/main/java/com/econo/auth/core/member/exception/`

| 예외 클래스 | HTTP | 에러 코드 |
|---|---|---|
| `MemberAlreadyExistsException` | 409 | `MEMBER_ALREADY_EXISTS` |
| `InvalidCredentialsException` | 401 | (클래스 보존, SAS 도입 후 `GlobalExceptionHandler` 핸들러 미등록) |
| `InvalidPasswordPolicyException` | 400 | `INVALID_PASSWORD_POLICY` |

> `loginId` 형식 및 `generation` 범위 위반 시 `IllegalArgumentException` → auth-api에서 HTTP 400 `INVALID_LOGIN_ID_FORMAT`으로 변환.

> 로그인 실패(`INVALID_CREDENTIALS`)는 `JsonLoginAuthenticationFilter`가 직접 401 JSON 응답을 반환하며, `GlobalExceptionHandler`를 거치지 않는다.

## 관련 모듈

- `auth-common-lib` — Passport 도메인 (`api` 전이 의존)
- `auth-infra` — 이 모듈이 정의한 포트(`MemberRepository`, `PasswordHasher`)를 어댑터로 구현
- `auth-api` — 이 모듈의 유스케이스를 호출, 유스케이스 빈 등록 책임 보유. `MemberUserDetailsService`가 `MemberRepository` 포트를 직접 의존.
