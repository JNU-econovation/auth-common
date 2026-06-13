# common-infra

공통 인프라 설정 라이브러리. `@EnableJpaAuditing`을 Spring Boot AutoConfiguration(`CommonInfraAutoConfiguration`)으로 제공하여 `member`·`service-client` 등 JPA Auditing이 필요한 모듈이 공통으로 의존할 수 있게 한다.

---

## Quick Reference

| 항목 | 값 |
|------|-----|
| 패키지 루트 | `com.econo.auth.commoninfra` |
| Gradle 의존 경로 | `implementation(project(":services:libs:common-infra"))` |
| 주요 연관 모듈 | `member` (소비자), `service-client` (소비자) |
| AutoConfiguration | `com.econo.auth.commoninfra.config.CommonInfraAutoConfiguration` |
| API 엔드포인트 | 해당 없음 — 인프라 설정 전용 libs 모듈 |

---

## 비즈니스 규칙

- `CommonInfraAutoConfiguration`에 `@EnableJpaAuditing`이 선언되어 있어, 이 모듈에 의존하는 소비자는 별도 `@EnableJpaAuditing` 설정 없이 JPA Auditing이 자동 활성화된다.
- `@CreatedDate` / `@LastModifiedDate` 어노테이션이 붙은 엔티티 필드는 이 AutoConfiguration이 활성화된 상태에서 자동으로 채워진다.
- ⚠️ `member` 모듈은 `common-infra`를 `api` 의존으로 선언하여 소비자(`auth-api`)에 `CommonInfraAutoConfiguration`을 전이 활성화한다. `service-client`는 `implementation` 의존으로 직접 활성화한다.
- 이 모듈 자체에는 비즈니스 로직·엔티티·예외가 없다. AutoConfiguration 클래스 하나만 포함한다.

---

## 코드 진입점

| 구분 | 경로 |
|------|------|
| AutoConfiguration | `services/libs/common-infra/src/main/java/com/econo/auth/commoninfra/config/CommonInfraAutoConfiguration.java` |

---

## 에러 코드

해당 없음. 인프라 설정만 제공하며 예외를 정의하지 않는다.

---

## 관련 모듈

| 모듈 | Gradle path | 관계 |
|------|-------------|------|
| member | `:services:libs:member` | 소비자 — `api` 의존으로 JpaAuditing 소비 및 auth-api에 전이 |
| service-client | `:services:libs:service-client` | 소비자 — `implementation` 의존으로 JpaAuditing 소비 |
