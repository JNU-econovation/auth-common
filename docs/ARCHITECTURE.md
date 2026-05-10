# Architecture

auth-common의 아키텍처 문서.

## 개요

auth-common은 **모노레포 멀티모듈 프로젝트**로, Passport 기반 인증 서버와 API Gateway를 포함하는 통합 레포지토리이다.

- **API Gateway**가 클라이언트 요청을 수신하고, 인증이 필요한 요청을 Auth 서버로 포워딩한다.
- **Auth 서버**가 회원 관리, 토큰 발급, Passport 생성을 담당한다.
- **auth-common-lib**는 다른 마이크로서비스가 Passport를 수신·검증하기 위해 사용하는 공유 라이브러리이다.

## 기술 스택

| 항목 | 내용 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.2.2 |
| Build Tool | Gradle (Kotlin DSL), 멀티모듈 |
| 의존성 관리 | Spring Dependency Management 1.1.7 |
| JSON | Jackson (jackson-databind, jackson-datatype-jsr310) |
| Validation | Jakarta Bean Validation 3.0 |
| 코드 생성 | Lombok |
| 코드 포맷팅 | Spotless + Google Java Format 1.17.0 |
| 테스트 | JUnit 5, AssertJ, Mockito, Spring Boot Test (MockMvc) |
| 배포 | JitPack, GitHub Packages |

## 모듈 구조

```
auth-common/                          # 루트 프로젝트
├── services/
│   ├── apis/                         # 배포 단위 (Spring Boot Application)
│   │   ├── api-gateway/              # API Gateway 서버
│   │   └── auth-api/                 # 인증 API 서버 (로그인, 회원가입, 토큰)
│   └── libs/                         # 공유 라이브러리
│       ├── auth-core/                # 도메인 엔티티, 비즈니스 로직
│       ├── auth-infra/               # JPA Repository, 토큰 저장소, 외부 연동
│       └── auth-common-lib/          # Passport, @PassportAuth (외부 서비스용)
├── docs/                             # 문서 (ARCHITECTURE.md, CONVENTION.md, DOC-GUIDE.md, README-GUIDE.md, INFRASTRUCTURE.md)
└── .claude/                          # Claude Code harness (agents, skills, commands)
```

### 모듈 의존성

```
api-gateway ──→ auth-common-lib

auth-api ──→ auth-core
         ──→ auth-infra

auth-infra ──→ auth-core

auth-core ──→ auth-common-lib

auth-common-lib (독립, 외부 서비스에 배포)
```

### 모듈별 역할

| 모듈 | 유형 | 역할 |
|------|------|------|
| **api-gateway** | App | 클라이언트 요청 수신, Auth 서버로 포워딩, JWT 파싱 → Passport 생성 → 헤더 전달 |
| **auth-api** | App | 로그인, 회원가입, 토큰 발급/갱신 API. `main()` 진입점 |
| **auth-core** | Lib | Member 엔티티, 인증 비즈니스 로직, 도메인 규칙 |
| **auth-infra** | Lib | JPA Repository, 토큰 저장소, 외부 시스템 연동 |
| **auth-common-lib** | Lib | Passport 도메인, @PassportAuth 어노테이션, ArgumentResolver. 외부 마이크로서비스가 의존하는 공유 라이브러리 |

## 인증 흐름

```
Client → API Gateway → Auth Server (auth-api)
          │                  │
          │ 1. JWT 파싱       │ 회원가입, 로그인, 토큰 발급
          │ 2. Passport 생성  │
          │ 3. JSON → Base64  │
          │ 4. X-User-Passport│
          │    헤더 설정       │
          │                  │
          └──────────────────→ Other Microservices
                               5. PassportArgumentResolver가 헤더 수신
                               6. Base64 디코딩 → JSON 역직렬화
                               7. @PassportAuth 옵션에 따라 검증
                                  - 만료 검증 (validateExpiry)
                                  - 역할 검증 (requiredRoles, AND/OR)
                                  - 계층 검증 (includeHigherRoles)
                                  - SpEL 조건 검증 (condition)
                               8. Passport 객체를 컨트롤러 파라미터에 주입
```

## auth-common-lib 패키지 구조

```
com.econo.common.auth
├── config/                          # Auto-Configuration
│   └── AuthAutoConfiguration       # WebMvcConfigurer 구현, ArgumentResolver 등록
├── core/passport/                   # 도메인 계층
│   ├── Passport                     # Aggregate Root - 회원 인증 정보
│   ├── PassportException            # 인증/인가 예외
│   └── Roles                        # 역할 상수 및 유틸리티
└── web/                             # 웹 계층
    ├── annotation/
    │   └── PassportAuth             # 파라미터 어노테이션
    └── resolver/
        └── PassportArgumentResolver # Spring MVC ArgumentResolver
```

## 계층 설계

### core (도메인 계층)

Spring Framework에 의존하지 않는 순수 도메인 로직.

- **Passport**: 회원 인증 정보를 담는 불변 객체 (Aggregate Root). `memberId`, `email`, `name`, `roles`, `issuedAt`, `expiresAt` 필드를 가지며, 역할 확인(`hasRole`, `isAdmin`), 유효성 검증(`isValid`, `isExpired`, `isActive`), 접근 제어(`canAccessMember`) 메서드를 제공한다.
- **PassportException**: HTTP 상태 코드와 에러 코드를 포함하는 커스텀 예외. 정적 팩토리 메서드(`unauthorized`, `forbidden`, `badRequest`, `expired`, `invalid`)로 생성한다.
- **Roles**: 역할 상수(`USER`, `MANAGER`, `ADMIN`, `SUPER_ADMIN`)와 동적 역할 생성 헬퍼, 역할 계층 비교 유틸리티를 제공한다.

### web (웹 계층)

Spring MVC에 의존하는 웹 어댑터 계층.

- **@PassportAuth**: 컨트롤러 메서드 파라미터에 붙이는 어노테이션. `required`, `validateExpiry`, `requiredRoles`, `requireAllRoles`, `includeHigherRoles`, `condition` 옵션을 제공한다.
- **PassportArgumentResolver**: `HandlerMethodArgumentResolver` 구현체. `X-User-Passport` 헤더를 디코딩하고, 어노테이션 옵션에 따라 검증 후 Passport 객체를 주입한다.

### config (설정 계층)

- **AuthAutoConfiguration**: `WebMvcConfigurer`를 구현하여 `PassportArgumentResolver`를 자동 등록한다. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 선언되어 Spring Boot 3.x Auto-Configuration으로 동작한다.

## 핵심 설계 결정

### 1. Gateway 책임 분리

JWT 파싱과 검증은 API Gateway의 책임이다. 다른 마이크로서비스는 이미 검증된 Passport를 전달받아 사용하므로, JWT 관련 의존성이 없다.

### 2. 불변 객체

Passport의 `roles` 필드는 `List.copyOf()`로 방어적 복사를 수행한다. 생성 이후 상태 변경이 불가능하다.

### 3. String 기반 역할 체계

Enum 대신 String 기반 역할을 사용한다. 이유:
- 동적 역할 생성 지원 (`DEPARTMENT_CS_ADMIN`, `PROJECT_2024_MEMBER`)
- 외부 시스템과의 연동 용이
- Spring Security 패러다임과 일치

### 4. 역할 계층

`SUPER_ADMIN(4) > ADMIN(3) > MANAGER(2) > USER(1)` 순서로 계층이 정의되어 있다. `includeHigherRoles = true` 옵션으로 상위 역할이 하위 역할의 권한을 자동 포함할 수 있다.

### 5. SpEL 조건 표현식

`@PassportAuth(condition = "#{passport.memberId == #userId or passport.isAdmin()}")` 형태로 복잡한 권한 로직을 선언적으로 표현할 수 있다. PathVariable과 RequestParam이 SpEL 컨텍스트에 자동 바인딩된다.

## 에러 코드 체계

| HTTP 상태 | 에러 코드 | 발생 조건 |
|-----------|-----------|-----------|
| 401 UNAUTHORIZED | AUTH_UNAUTHORIZED | 헤더 누락, 인증 실패 |
| 401 UNAUTHORIZED | AUTH_TOKEN_EXPIRED | Passport 만료 |
| 401 UNAUTHORIZED | AUTH_PASSPORT_INVALID | Passport 구조 유효성 실패 |
| 403 FORBIDDEN | AUTH_FORBIDDEN | 권한 부족 |
| 400 BAD_REQUEST | AUTH_BAD_REQUEST | 디코딩/파싱 실패 |

## 테스트 구조

```
services/libs/auth-common-lib/src/test/java/com/econo/common/auth/
├── core/passport/
│   ├── PassportTest.java              # Passport 도메인 단위 테스트
│   ├── PassportExceptionTest.java     # 예외 단위 테스트
│   └── RolesTest.java                 # 역할 유틸리티 단위 테스트
├── integration/
│   └── PassportAuthIntegrationTest.java  # MockMvc 통합 테스트
└── web/resolver/
    └── PassportArgumentResolverTest.java # ArgumentResolver 단위 테스트
```

- 단위 테스트: 도메인 로직 검증 (Spring 컨텍스트 불필요)
- 통합 테스트: `@SpringBootTest` + `MockMvc`로 E2E 흐름 검증
