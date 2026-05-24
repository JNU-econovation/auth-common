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
- **⚠️ 사용자 열거 방지**: 로그인 시 loginId 미존재·비밀번호 불일치 모두 `InvalidCredentialsException`으로 동일 응답. 메시지 "아이디 또는 비밀번호가 올바르지 않습니다" 고정.
- **JWT 클레임 계약**: `sub(memberId)`, `loginId`, `name`, `generation`, `status`, `roles(["USER"])`, `iat`, `exp`. `roles`는 항상 `["USER"]` 고정 — status는 활동 상태이지 권한이 아님.
- **⚠️ 프레임워크 의존성 금지**: `auth-core` 도메인 패키지는 Spring Framework에 의존하지 않는 순수 도메인 계층이다. 예외 클래스에 `HttpStatus`가 필드로 존재하나 이는 계층 경계에서 참조용으로만 사용한다.
- **빈 등록 책임**: `SignupService`, `LoginService`는 `@Component`가 아닌 일반 클래스다. 헥사고날 아키텍처 원칙에 따라 `auth-api`의 `ApplicationServiceConfig`에서 `@Bean`으로 등록한다.

> cross-module 인증 흐름은 [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) 참조.

## 코드 진입점

| 구분 | 파일 |
|---|---|
| Aggregate Root | `src/main/java/com/econo/auth/core/member/domain/Member.java` |
| 활동 상태 Enum | `src/main/java/com/econo/auth/core/member/domain/MemberStatus.java` |
| 인바운드 포트 (가입) | `src/main/java/com/econo/auth/core/member/application/port/in/SignupUseCase.java` |
| 인바운드 포트 (로그인) | `src/main/java/com/econo/auth/core/member/application/port/in/LoginUseCase.java` |
| 아웃바운드 포트 | `src/main/java/com/econo/auth/core/member/application/port/out/MemberRepository.java` |
| 아웃바운드 포트 | `src/main/java/com/econo/auth/core/member/application/port/out/PasswordHasher.java` |
| 아웃바운드 포트 | `src/main/java/com/econo/auth/core/member/application/port/out/TokenIssuer.java` |
| 유스케이스 구현 | `src/main/java/com/econo/auth/core/member/application/usecase/SignupService.java` |
| 유스케이스 구현 | `src/main/java/com/econo/auth/core/member/application/usecase/LoginService.java` |
| 도메인 예외 | `src/main/java/com/econo/auth/core/member/exception/` |

## 에러 코드

> 에러 정의: `src/main/java/com/econo/auth/core/member/exception/`

| 예외 클래스 | HTTP | 에러 코드 |
|---|---|---|
| `MemberAlreadyExistsException` | 409 | `MEMBER_ALREADY_EXISTS` |
| `InvalidCredentialsException` | 401 | `INVALID_CREDENTIALS` |
| `InvalidPasswordPolicyException` | 400 | `INVALID_PASSWORD_POLICY` |

> `loginId` 형식 및 `generation` 범위 위반 시 `IllegalArgumentException` → auth-api에서 HTTP 400 `INVALID_LOGIN_ID_FORMAT`으로 변환.

## 관련 모듈

- `auth-common-lib` — Passport 도메인 (`api` 전이 의존)
- `auth-infra` — 이 모듈이 정의한 포트(`MemberRepository`, `PasswordHasher`, `TokenIssuer`)를 어댑터로 구현
- `auth-api` — 이 모듈의 유스케이스를 호출, 유스케이스 빈 등록 책임 보유
