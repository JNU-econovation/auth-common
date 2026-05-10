# auth-common-lib

ECONO 마이크로서비스 간 통신용 Passport 도메인 라이브러리. 외부 마이크로서비스에 JitPack / GitHub Packages로 배포된다.

> **사용자 문서:** 라이브러리 사용법은 루트 [`README.md`](../../../README.md)를 참조한다. 본 문서는 내부 기여자용이다.

## Quick Reference

| 항목 | 값 |
|---|---|
| 패키지 | `com.econo.common.auth` |
| Gradle 의존 (내부) | `implementation(project(":services:libs:auth-common-lib"))` |
| Gradle 의존 (외부) | `implementation("com.github.JNU-econovation:auth-common:{version}")` |
| 외부 배포 | JitPack, GitHub Packages (`build.gradle.kts` `publishing` 블록) |
| 자동 설정 | `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| 의존하는 내부 모듈 | (없음 — 외부 배포되는 독립 라이브러리) |

## 비즈니스 규칙

- **불변 객체**: `Passport`의 `roles` 필드는 `List.copyOf()`로 방어적 복사된다. 생성 후 변경 불가.
- **역할 계층**: `SUPER_ADMIN(4) > ADMIN(3) > MANAGER(2) > USER(1)`. `@PassportAuth(includeHigherRoles = true)`로 상위 역할이 하위 역할의 권한을 자동 포함한다.
- **String 기반 역할**: Enum 대신 String을 사용해 동적 역할(`DEPARTMENT_CS_ADMIN` 등)과 외부 시스템 연동을 지원한다.
- **⚠️ 패키지 경계**: `core` 패키지는 Spring Framework에 의존하지 않는 순수 도메인 계층이다. Spring 의존을 끌어들이지 말 것. 웹 어댑터는 `web` 패키지에서만.
- **⚠️ 자동 설정**: 의존만 추가하면 `PassportArgumentResolver`가 자동 등록된다. 수동 설정과 충돌하지 않도록 주의.

> 인증 흐름·역할 계층 등 cross-module 흐름은 [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) 참조.

## 코드 진입점

| 구분 | 파일 |
|---|---|
| Aggregate Root | `src/main/java/com/econo/common/auth/core/passport/Passport.java` |
| 도메인 예외 | `src/main/java/com/econo/common/auth/core/passport/PassportException.java` |
| 역할 상수·유틸 | `src/main/java/com/econo/common/auth/core/passport/Roles.java` |
| 어노테이션 | `src/main/java/com/econo/common/auth/web/annotation/PassportAuth.java` |
| ArgumentResolver | `src/main/java/com/econo/common/auth/web/resolver/PassportArgumentResolver.java` |
| Auto-Configuration | `src/main/java/com/econo/common/auth/config/AuthAutoConfiguration.java` |

## 에러 코드

> 정의: `src/main/java/com/econo/common/auth/core/passport/PassportException.java`
> 코드 체계: [`docs/ARCHITECTURE.md` 에러 코드 체계](../../../docs/ARCHITECTURE.md#에러-코드-체계)

## 관련 모듈

- (없음) — 외부 배포되는 독립 라이브러리. 다른 내부 모듈에 의존하지 않는다.
- 본 라이브러리를 의존하는 내부 모듈: `auth-core`(전이), `api-gateway`
