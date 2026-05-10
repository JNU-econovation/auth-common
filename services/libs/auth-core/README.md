# auth-core

ECONO 자체 인증 도메인 모델과 비즈니스 로직. Spring Framework에 의존하지 않는 순수 도메인 계층.

> **상태:** 골격 단계 — `build.gradle.kts`만 존재하고 `src/`는 비어 있다. 실 구현은 `member-auth` 작업에서 도입된다.

## Quick Reference

| 항목 | 값 |
|---|---|
| 패키지 | `com.econo.auth.core` (추정 — `member-auth` 작업의 `implementation-plan.md`에서 확정) |
| Gradle 의존 (내부) | `implementation(project(":services:libs:auth-core"))` |
| 외부 의존 | (없음 — 순수 도메인) |
| 전이 의존 | `auth-common-lib` (`api` 의존) |

## 비즈니스 규칙

현재 미구현. `member-auth` 작업의 `implementation-plan.md`에서 도입 예정 항목:

- Member 엔티티(이메일·비밀번호 해시·상태)
- 회원 가입·로그인 도메인 서비스
- 비밀번호 해싱 포트(`auth-infra`가 BCrypt 어댑터로 구현)
- JWT 발급/검증 포트(`auth-infra`가 어댑터로 구현)

## 코드 진입점

(현재 없음.)

## 에러 코드

(현재 없음. 도입 시 `auth-common-lib`의 `PassportException` 정적 팩토리 패턴을 따른다.)

## 관련 모듈

- `auth-common-lib` — Passport 도메인 (`api` 전이 의존)
- `auth-infra` — 이 모듈이 정의한 포트를 어댑터로 구현
- `auth-api` — 이 모듈의 도메인 서비스를 호출
