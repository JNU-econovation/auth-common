# member-infra

JPA Repository, 비밀번호 해싱 등 인프라 어댑터 계층. `member-core`가 정의한 포트를 어댑터로 구현한다.

## Quick Reference

| 항목 | 값 |
|---|---|
| 패키지 | `com.econo.auth.member.infra` |
| Gradle 의존 (내부) | `implementation(project(":services:libs:member-infra"))` |
| 외부 의존 | `spring-boot-starter-data-jpa`, `spring-security-crypto`, `flyway-core`, `postgresql` |
| 의존하는 내부 모듈 | `member-core` |

## 비즈니스 규칙

- **BCrypt cost=12**: `BCryptPasswordHasherAdapter`는 cost 12로 고정된다. 변경 시 기존 해시와 호환성 문제가 발생한다. `SecurityConfig`의 `BCryptPasswordEncoder(12)`와 동일 cost여야 한다.
- **Flyway 자동 실행**: 애플리케이션 기동 시 `db/migration/` 경로의 마이그레이션을 자동 실행한다. `spring.jpa.hibernate.ddl-auto=validate`로 Hibernate는 스키마를 수정하지 않는다.
- **Flyway 마이그레이션 순서**: V1(members), V2(SAS 3종 테이블), V3(Spring Session 2종 테이블). V2·V3은 `sas-authorization-server` 작업에서 추가됨.
- **JPA 스캔 범위**: `InfraConfig`에서 `@EnableJpaRepositories`, `@EntityScan` 기준 패키지를 `"com.econo.auth.member.infra"`로 명시한다. `auth-api`에서 `member-infra`의 빈이 스캔되려면 이 설정이 반드시 로드되어야 한다.
- **⚠️ Spring Security 풀 스택 미사용**: BCrypt만 필요하므로 `spring-security-crypto` 모듈 단독 의존. `spring-boot-starter-security` 없음. (`jjwt` 의존성은 SAS 도입으로 제거됨)

> Entity 정의: `src/main/java/com/econo/auth/member/infra/adapter/out/persistence/MemberJpaEntity.java`

## 코드 진입점

| 구분 | 파일 |
|---|---|
| JPA Entity | `src/main/java/com/econo/auth/member/infra/adapter/out/persistence/MemberJpaEntity.java` |
| Spring Data Repository | `src/main/java/com/econo/auth/member/infra/adapter/out/persistence/MemberJpaRepository.java` |
| MemberRepository 어댑터 | `src/main/java/com/econo/auth/member/infra/adapter/out/persistence/MemberRepositoryAdapter.java` |
| BCrypt 어댑터 | `src/main/java/com/econo/auth/member/infra/adapter/out/security/BCryptPasswordHasherAdapter.java` |
| JPA Auditing 설정 | `src/main/java/com/econo/auth/member/infra/config/JpaAuditingConfig.java` |
| JPA/Entity 스캔 설정 | `src/main/java/com/econo/auth/member/infra/config/InfraConfig.java` |
| Flyway 마이그레이션 (V1) | `src/main/resources/db/migration/V1__create_members_table.sql` |
| Flyway 마이그레이션 (V2) | `src/main/resources/db/migration/V2__create_sas_tables.sql` |
| Flyway 마이그레이션 (V3) | `src/main/resources/db/migration/V3__create_spring_session_tables.sql` |

> `JwtTokenIssuerAdapter`는 SAS 도입으로 제거됨. 토큰 발급은 Spring Authorization Server가 전담.

## 에러 코드

인프라 계층은 도메인 예외(`member-core`)로 변환해 throw한다. 자체 에러 코드 없음.

> 도메인 예외 정의: [`services/libs/member-core/src/main/java/com/econo/auth/member/exception/`](../member-core/src/main/java/com/econo/auth/member/exception/)

## 관련 모듈

- `member-core` — 도메인 포트 (이 모듈이 어댑터로 구현)
- `auth-api` — 이 모듈의 어댑터를 통해 인프라에 접근
