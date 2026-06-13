# ADR-0014: 3계층 + 계층별 DIP 아키텍처 채택

- **상태:** Accepted
- **결정일:** 2026-06-13
- **결정자:** econovation 개발팀

---

## 배경

기존 `member`·`service-client` lib 모듈은 헥사고날 아키텍처 어휘(`adapter/in`, `adapter/out`, `application/port/in`, `application/port/out`)를 사용했다. `auth-api`는 `adapter/in/web` + `filter/` + `security/` + `application/`이 혼재했다. 이로 인해 다음 문제가 있었다.

1. **어휘 혼재**: 모듈마다 패키지 구조가 달라 신규 진입자가 어느 패키지에 무엇을 두어야 하는지 판단 비용이 높았다.
2. **헥사고날 복잡도**: `port/in`, `port/out`, `adapter/in`, `adapter/out` 4단 중첩 경로가 현 규모의 모듈에 비해 과도했다.
3. **보안 클래스 위치 불명확**: Spring Security에 결합된 클래스(`SecurityConfig`, `UserDetailsService`, `BearerToPassportFilter` 등)가 `security/`·`filter/`·`config/`에 분산되어 있어 배치 기준이 일관되지 않았다.

---

## 결정

**전체 모듈의 패키지 구조를 presentation / application / persistence 3계층 + 계층별 DIP로 통일한다.**

### 계층 및 역할

| 계층 | 서브패키지 | 내용 |
|------|-----------|------|
| presentation | `controller`, `dto`, `util` | HTTP 컨트롤러, 요청·응답 DTO, 컨트롤러 보조 유틸 |
| application | `usecase` (입력 포트 IF), `service` (구현), `repository` (출력 포트 IF), `domain` | 비즈니스 로직, 포트 인터페이스, 도메인 객체 |
| persistence | `entity` (JPA `@Entity`), `repository` (Spring Data + 어댑터) | 영속성 구현, entity↔domain 변환 |
| config | — | 일반 설정·빈 와이어링 |
| config/security | — | Spring Security에 결합된 클래스 전용 |
| exception | — | 도메인 예외 |

### 의존성 불변식

1. **단방향 참조**: presentation → application → persistence. 계층 건너뛰기 금지.
2. **presentation·config/security는 `application.usecase` 인터페이스에만 의존**한다. `application.service` 구현체나 `application.repository`·`persistence`를 직접 참조하지 않는다.
3. **일반 `config/`(보안 아님) 와이어링 클래스는 `application.repository`와 `application.service`를 참조해도 된다.** 빈 등록·CORS 설정 등 설정 책임이므로 허용된다(예: `ApplicationServiceConfig`, `DynamicCorsConfigurationSource`).
4. **`application.repository` 출력 포트 시그니처는 도메인 객체만 사용**한다. JPA 엔티티(`*JpaEntity`)는 `persistence` 계층 바깥으로 나가지 않는다. entity↔domain 변환은 `persistence.repository` 어댑터의 책임이다.

### 모듈별 계층 사용 범위

- **libs** (member, service-client): application + persistence 계층을 포함한다. presentation 계층은 없다.
- **apps** (auth-api, api-gateway): presentation + config/security 계층을 포함한다. presentation이 주된 관심사이며, 앱 고유 orchestration을 위해 얇은 application 계층도 포함한다.

### config/security 서브패키지 배치 기준

Spring Security 프레임워크에 결합된 클래스 — `SecurityConfig`, `UserDetailsService` 구현체, `AuthenticationFilter`, Spring Cloud Gateway `GlobalFilter`, JWT 검증 유틸 — 를 `config/security/` 서브패키지에 배치한다. `presentation/filter` 패키지는 사용하지 않는다.

이 클래스들은 Spring Security가 구동 시 직접 읽어가는 설정 컴포넌트이므로 `config/` 하위에 두는 것이 역할에 맞다. 또한 `presentation.controller`처럼 `application.usecase` 인터페이스에만 의존하도록 DIP를 적용한다.

---

## 근거

### 헥사고날 대비 트레이드오프

| 항목 | 헥사고날 | 3계층 + DIP (채택) |
|------|---------|-------------------|
| 포트·어댑터 명시성 | `port/in`, `port/out`, `adapter/in`, `adapter/out` 명시 | `usecase`(입력 포트), `repository`(출력 포트), `persistence.repository`(어댑터)로 동일 개념을 유지하되 경로 단순화 |
| 패키지 깊이 | 4단 이상 중첩 (`application/port/out/`) | 2단 (`application/repository/`) |
| 보안 클래스 배치 | 별도 `security/`·`filter/` 최상위 패키지 혼재 | `config/security/` 한 곳으로 통일 |
| 진입 장벽 | 헥사고날 용어 학습 필요 | presentation/application/persistence 개념이 범용적 |
| DIP 강제 | 포트 인터페이스로 동일하게 강제 | 동일 — usecase·repository 인터페이스 유지 |

헥사고날 아키텍처의 핵심 장점인 **포트·어댑터를 통한 의존성 역전**은 3계층 DIP 모델에서도 동일하게 유지된다. 이름을 단순화했을 뿐 경계와 방향 규칙은 그대로다. 헥사고날을 명시적으로 supersede하는 ADR이 없었으므로 본 ADR이 패키지 어휘를 새로 확립한다.

---

## 완료된 후속 작업

- **auth-api application 계층 lib 추출 (완료)**: `auth-lib-extraction` 작업으로 `LoginTokenService`·`LoginRedirectResolver`·`LoginTokenUseCase`·`LoginRedirectUseCase`가 `services/libs/login`(`com.econo.auth.login`)으로 추출 완료되었다. 두 서비스는 `LoginAutoConfiguration` 컴포넌트 스캔으로 자동 등록되며, auth-api `ApplicationServiceConfig`의 `loginRedirectResolver` `@Bean` 수동 등록은 제거되었다.
- **토큰 발급 추상화(TokenEncoder/TokenDecoder 포트) (완료)**: `TokenEncoder`(서명)·`TokenDecoder`(검증) 출력 포트(`application/repository/`)를 도입하여 JWT 서명·검증 책임을 login lib 외부로 위임하였다. 구현체 `NimbusTokenManager`(단일 클래스가 두 포트를 모두 구현)는 auth-api의 `config/security/`에 위치하며, login lib은 `spring-security-oauth2` 의존 없이 두 포트 인터페이스에만 의존한다.

---

## 관련 문서

- `docs/ARCHITECTURE.md` — 계층 모델 및 의존성 규칙 섹션
- `docs/CONVENTION.md` — 패키지 컨벤션 1.1·1.2 섹션
- `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java`
- `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java`
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java`
