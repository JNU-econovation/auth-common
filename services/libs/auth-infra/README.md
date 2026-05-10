# auth-infra

JPA Repository, 비밀번호 해싱, JWT 발급/검증 등 인프라 어댑터 계층. `auth-core`가 정의한 포트를 어댑터로 구현한다.

> **상태:** 골격 단계 — `build.gradle.kts`만 존재하고 `src/`는 비어 있다. 실 구현은 `member-auth` 작업에서 도입된다.

## Quick Reference

| 항목 | 값 |
|---|---|
| 패키지 | `com.econo.auth.infra` (추정 — `member-auth` 작업의 `implementation-plan.md`에서 확정) |
| Gradle 의존 (내부) | `implementation(project(":services:libs:auth-infra"))` |
| 외부 의존 | `spring-boot-starter-data-jpa` |

## 비즈니스 규칙

현재 미구현. `member-auth` 작업의 `implementation-plan.md` / `db-design-plan.md`에서 도입 예정 항목:

- Member JPA 엔티티 / 리포지토리 (`members` 테이블, email 유니크 인덱스)
- BCrypt 비밀번호 해싱 어댑터 (`spring-security-crypto` 단독 사용)
- JWT 발급/검증 어댑터 (HMAC-SHA256, OIDC 확장 시 RSA로 교체 가능)
- ⚠️ DB 마이그레이션은 Flyway, V1 파일에서 `members` 테이블 생성

## 코드 진입점

(현재 없음.)

## 에러 코드

인프라 계층은 일반적으로 도메인 예외(`auth-core`)로 변환해 throw한다.

## 관련 모듈

- `auth-core` — 도메인 포트 (이 모듈이 어댑터로 구현)
- `auth-api` — 이 모듈의 어댑터를 통해 인프라에 접근
