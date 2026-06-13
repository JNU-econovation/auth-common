# layered-architecture - api-design

## 메타
- **작업명**: layered-architecture
- **문서 타입**: api-design
- **작성일**: 2026-06-13
- **최종 갱신**: 2026-06-13 (config/security 패키지 확정 반영 — presentation.filter 표기 전면 수정)
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md

---

## 개요

이 작업은 **순수 패키지 구조 리팩토링**이다. 엔드포인트 경로, 요청/응답 스키마, HTTP 상태 코드, 인증/인가 규칙은 **일절 변경하지 않는다**. 신규 엔드포인트는 0개다. 따라서 이 문서는 새 API를 설계하지 않는다. 대신 (1) API 변경 없음을 명확히 선언하고, (2) 패키지 이동 후에도 기존 URL 매핑·요청/응답·상태코드가 동일하게 유지됨을 보장하는 회귀 체크리스트를 제공하며, (3) 엄격 DIP 확정으로 컨트롤러·필터의 주입 타입이 usecase 인터페이스로 전환됨이 외부 계약에 무영향임을 설명한다.

프로토콜: REST (Spring MVC / Spring WebFlux — 모듈별로 다름). 인증 패턴: JWT Stateless + X-User-Passport 헤더.

---

## 본문

### 1. API 변경 없음 선언

이 리팩토링이 변경하는 것은 **자바 패키지 경로(FQCN)** 와 **컨트롤러·필터 내부의 주입 선언 타입** 뿐이다. 외부에서 관찰 가능한 어떤 것도 바뀌지 않는다.

| 범주 | 변경 여부 | 비고 |
|------|-----------|------|
| HTTP 엔드포인트 URL | **불변** | `@RequestMapping` / `@GetMapping` 등 어노테이션 값은 그대로 |
| HTTP 메서드 | **불변** | |
| 요청 바디/파라미터 스키마 | **불변** | |
| 응답 바디 스키마 | **불변** | |
| HTTP 상태 코드 | **불변** | |
| 인증/인가 규칙 | **불변** | SecurityConfig 및 `@PassportAuth` 설정 그대로 |
| 에러 응답 포맷 | **불변** | `GlobalExceptionHandler.ApiError` 및 에러 코드 그대로 |
| Spring Security 필터 체인 | **불변** | `JsonLoginAuthenticationFilter`, `BearerToPassportFilter` 동작 동일 |
| DB 스키마 | **불변** | 별도 선언 — todo.md DB 작업 섹션 참조 |
| 컨트롤러/필터 주입 선언 타입 | **내부 변경** | repository 직접 → usecase 인터페이스 경유. HTTP 계약 무영향. |

---

### 2. presentation 계층 회귀 체크리스트

패키지 이동 전/후 각 컨트롤러·필터가 **동일한 URL 매핑·요청/응답·상태코드**를 유지하는지 검증하기 위한 기준점 목록이다. 아래 목록은 라이브 소스 grep(`@RestController`, `@RequestMapping`, `@GetMapping` 등) 결과를 기준으로 작성되었으며 최근 main 머지로 추가된 `RootController` 2종을 포함한다.

#### 2-1. auth-api 모듈 컨트롤러 (16개 엔드포인트, 9개 클래스)

| 클래스 | 현재 패키지 경로 | 이동 후 패키지 경로 | URL 매핑 | HTTP 메서드 | DIP 전환 |
|--------|-----------------|---------------------|----------|-------------|----------|
| `RootController` | `adapter.in.web` | `presentation.controller` | `GET /` | GET | 없음 |
| `MemberController` | `adapter.in.web` | `presentation.controller` | `POST /api/v1/auth/signup` | POST | 없음 (이미 SignupUseCase 주입) |
| `ReissueController` | `adapter.in.web` | `presentation.controller` | `POST /api/v1/auth/reissue` | POST | LoginTokenService → LoginTokenUseCase |
| `ReissueController` | `adapter.in.web` | `presentation.controller` | `POST /api/v1/auth/logout` | POST | 동상 |
| `AdminClientController` | `adapter.in.web` | `presentation.controller` | `POST /api/v1/admin/clients` | POST | RegisterOAuthClientService → RegisterOAuthClientUseCase |
| `AdminClientController` | `adapter.in.web` | `presentation.controller` | `GET /api/v1/admin/clients/{clientId}` | GET | ClientRedirectUriService → ClientRedirectUriUseCase |
| `AdminClientController` | `adapter.in.web` | `presentation.controller` | `POST /api/v1/admin/clients/{clientId}/redirect-uris` | POST | 동상 |
| `AdminClientController` | `adapter.in.web` | `presentation.controller` | `DELETE /api/v1/admin/clients/{clientId}/redirect-uris` | DELETE | 동상 |
| `AdminClientController` | `adapter.in.web` | `presentation.controller` | `PUT /api/v1/admin/clients/{clientId}/redirect-uris` | PUT | 동상 |
| `AdminMemberController` | `adapter.in.web` | `presentation.controller` | `GET /api/v1/admin/members` | GET | **MemberRepository → MemberQueryUseCase** |
| `AdminMemberController` | `adapter.in.web` | `presentation.controller` | `PATCH /api/v1/admin/members/{memberId}/role` | PATCH | 동상 |
| `ClientController` | `adapter.in.web` | `presentation.controller` | `POST /api/v1/clients` | POST | RegisterOAuthClientService → RegisterOAuthClientUseCase |
| `MemberInfoController` | `adapter.in.web` | `presentation.controller` | `POST /api/v1/members/batch` | POST | **MemberRepository → MemberQueryUseCase** |
| `JwksController` | `adapter.in.web` | `presentation.controller` | `GET /api/v1/admin/jwks` | GET | 없음 (JWKSource 주입 — 불변) |
| `AdminRoleController` | `adapter.in.web` | `presentation.controller` | `PUT /api/v1/internal/members/{memberId}/role` | PUT | **MemberRepository → MemberQueryUseCase** |

> **굵게 표시된 DIP 전환** 항목은 라이브 소스에서 `MemberRepository`(`application.port.out`) 직접 주입이 확인된 컨트롤러다.
> 확인 파일: `AdminMemberController.java:54`, `MemberInfoController.java:46`, `AdminRoleController.java:30`

#### 2-2. auth-api 모듈 보안 클래스 (config/security)

> **패키지 확정**: 이전 버전 문서에서 `presentation.filter`로 기재되었던 보안 결합 클래스들의 목표 패키지는 **`config.security`**로 확정되었다. Spring Security `@EnableWebSecurity`에 결합된 설정 클래스(`SecurityConfig`)와 Security 파이프라인 전용 클래스(`MemberUserDetailsService`, `MemberUserDetails`, `JsonLoginAuthenticationFilter`)를 `config/security` 서브패키지에 통합 배치한다. 결과적으로 `presentation/filter` 패키지는 **남지 않는다**.

| 클래스 | 현재 패키지 경로 | 이동 후 패키지 경로 | 역할 | DIP 전환 |
|--------|-----------------|---------------------|------|----------|
| `SecurityConfig` | `config` | `config.security` | `@EnableWebSecurity` 필터 체인 설정 | **LoginTokenService → LoginTokenUseCase, LoginRedirectResolver → LoginRedirectUseCase** |
| `JsonLoginAuthenticationFilter` | `filter` | `config.security` | `POST /api/v1/auth/login` 처리 (Spring Security 필터) | **LoginTokenService → LoginTokenUseCase, LoginRedirectResolver → LoginRedirectUseCase** |
| `MemberUserDetails` | `security` | `config.security` | `UserDetails` 구현체 — Spring Security 내부 사용 | 없음 |
| `MemberUserDetailsService` | `security` | `config.security` | `UserDetailsService` 구현체 — `memberAuthenticationManager` 빈 제공 | **MemberRepository → MemberQueryUseCase** |
| `TokenCookieManager` | `adapter.in.web` | `presentation.util` | AT/RT HttpOnly 쿠키 설정/삭제 헬퍼 (`@Component`, DTO 아님) | 없음 |
| `LoginResponse` | `adapter.in.web` | `presentation.dto` | 로그인 응답 DTO | 없음 |
| `SignupRequest` | `adapter.in.web` | `presentation.dto` | 회원 가입 요청 DTO | 없음 |

> **MemberUserDetailsService 확인**: 라이브 소스 `MemberUserDetailsService.java:20`에서 `private final MemberRepository memberRepository` 직접 주입 확인.
>
> **Bean 이름 안전**: `MemberUserDetailsService`에 선언된 `@Service`는 기본 빈 이름 `"memberUserDetailsService"`를 그대로 유지한다. 목표 패키지(`config.security`)가 바뀌어도 Spring 기본 이름 유도 규칙(클래스 단순명 camelCase)은 변하지 않으므로 `SecurityConfig`의 `@Qualifier("memberUserDetailsService")` 참조는 안전하다.

#### 2-3. api-gateway 모듈 컨트롤러 및 보안 클래스

> **패키지 확정**: `JwtVerifier`, `PassportBuilder`, `BearerToPassportFilter`, `GatewaySecurityConfig`의 목표 패키지는 **`config.security`**로 확정되었다. 이전 버전 문서에서 `JwtVerifier`/`PassportBuilder`를 `presentation.util`로 기재한 것은 오류였으며 이 문서에서 수정한다. `GatewaySecurityConfig`(`@EnableWebFluxSecurity`)와 보안 파이프라인 전용 클래스를 같은 `config/security` 서브패키지에 통합한다. 결과적으로 `gateway`의 `filter/`, `security/`, `web/` 패키지는 모두 **제거된다**.

| 클래스 | 현재 패키지 경로 | 이동 후 패키지 경로 | 역할 | DIP 전환 |
|--------|-----------------|---------------------|------|----------|
| `RootController` | `web` | `presentation.controller` | `GET /` 헬스체크 (Mono 반환 — WebFlux) | 없음 |
| `GatewaySecurityConfig` | `config` | `config.security` | `@EnableWebFluxSecurity` 필터 체인 설정 | 없음 (JwtVerifier @Bean 선언 — 동일 패키지) |
| `BearerToPassportFilter` | `filter` | `config.security` | GlobalFilter — Bearer → X-User-Passport 주입 | 없음 (JwtVerifier, PassportBuilder — 동일 패키지로 이동) |
| `JwtVerifier` | `security` | `config.security` | JWT 서명 검증 | 없음 |
| `PassportBuilder` | `security` | `config.security` | JWT → Passport 직렬화 | 없음 |

> **BearerToPassportFilter import 처리**: `JwtVerifier`, `PassportBuilder`가 동일 패키지(`config.security`)로 이동하므로 이 두 클래스에 대한 import는 제거된다. `GatewayRoutingConfig`는 `config/`에 잔류하므로 `gateway.config.GatewayRoutingConfig` import는 변경 없이 유지된다.

#### 2-4. SecurityConfig permittedPaths 목록 (변경 없음 확인용)

`SecurityConfig.appSecurityFilterChain` 내 `permitAll()` 경로 — 패키지 이동 후에도 이 목록이 그대로 유지되는지 확인한다.

```
/
/api/v1/auth/signup
/api/v1/auth/login
/api/v1/auth/logout
/api/v1/auth/reissue
/api/v1/admin/jwks
/api/v1/internal/**
/.well-known/**
/swagger-ui/**
/swagger-ui.html
/v3/api-docs/**
/v3/api-docs
/api/v1/admin/**      (Gateway 인증 의존 — permitAll)
/api/v1/members/**    (Gateway 인증 의존 — permitAll)
/api/v1/clients/**    (X-User-Passport 기반 — permitAll)
```

#### 2-5. 로그인 필터 URL 매핑 (변경 없음 확인용)

`JsonLoginAuthenticationFilter`의 `AntPathRequestMatcher`는 하드코딩된 문자열 리터럴이다.

```java
new AntPathRequestMatcher("/api/v1/auth/login", "POST")
```

패키지 이동(`filter` → `config.security`) 후에도 이 상수가 변경되지 않았는지 확인한다.

---

### 3. 엄격 DIP 확정 — 주입 타입 전환 상세

#### 3-1. 결정 사항 (이전 미해결 → 결정됨)

> **이전 문서의 미해결 사항**: "AdminMemberController/MemberInfoController/AdminRoleController/MemberUserDetailsService가 `MemberRepository` 포트 인터페이스를 직접 주입받는 구조. DIP 관점 개선 여부는 별도 판단 항목."
>
> **결정됨 (2026-06-13)**: 엄격 DIP 적용으로 확정. presentation 계층 및 `config/security` 보안 어댑터의 모든 repository/service 구현체 직접 주입을 usecase 인터페이스 경유로 전환한다. 신설 usecase 5종(implementation-plan.md 참조)이 이 전환을 담당한다.

#### 3-2. 전환 전/후 주입 타입 매핑

| 컨트롤러/진입점 | 위치 계층 | 전환 전 주입 타입 (현재 라이브 소스 확인) | 전환 후 주입 타입 | HTTP 계약 영향 |
|----------------|-----------|------------------------------------------|-----------------|----------------|
| `AdminMemberController` | `presentation.controller` | `MemberRepository` (`application.port.out`) | `MemberQueryUseCase` (`member.application.usecase`) | **없음** |
| `MemberInfoController` | `presentation.controller` | `MemberRepository` (`application.port.out`) | `MemberQueryUseCase` (`member.application.usecase`) | **없음** |
| `AdminRoleController` | `presentation.controller` | `MemberRepository` (`application.port.out`) | `MemberQueryUseCase` (`member.application.usecase`) | **없음** |
| `MemberUserDetailsService` | `config.security` | `MemberRepository` (`application.port.out`) | `MemberQueryUseCase` (`member.application.usecase`) | **없음** |
| `AdminClientController` | `presentation.controller` | `RegisterOAuthClientService` (구현체 직접) | `RegisterOAuthClientUseCase` (`client.application.usecase`) | **없음** |
| `AdminClientController` | `presentation.controller` | `ClientRedirectUriService` (구현체 직접) | `ClientRedirectUriUseCase` (`client.application.usecase`) | **없음** |
| `ClientController` | `presentation.controller` | `RegisterOAuthClientService` (구현체 직접) | `RegisterOAuthClientUseCase` (`client.application.usecase`) | **없음** |
| `ReissueController` | `presentation.controller` | `LoginTokenService` (구현체 직접, `api.application`) | `LoginTokenUseCase` (`api.application.usecase`) | **없음** |
| `JsonLoginAuthenticationFilter` | `config.security` | `LoginTokenService` (구현체 직접) | `LoginTokenUseCase` (`api.application.usecase`) | **없음** |
| `JsonLoginAuthenticationFilter` | `config.security` | `LoginRedirectResolver` (구현체 직접) | `LoginRedirectUseCase` (`api.application.usecase`) | **없음** |
| `SecurityConfig` | `config.security` | `LoginTokenService` (구현체 직접) | `LoginTokenUseCase` (`api.application.usecase`) | **없음** |
| `SecurityConfig` | `config.security` | `LoginRedirectResolver` (구현체 직접) | `LoginRedirectUseCase` (`api.application.usecase`) | **없음** |
| `MemberController` | `presentation.controller` | `SignupUseCase` (`application.port.in`) | `SignupUseCase` (`member.application.usecase`) | **없음** — 패키지 이동만, 이름 동일 |

> 주입 선언 타입이 바뀌어도 Spring IoC 컨테이너가 동일 구현체를 와이어링하므로 런타임 동작은 동일하다. HTTP 계약(URL·메서드·응답 스키마·상태코드·인증)은 전혀 바뀌지 않는다.

#### 3-3. 신설 usecase 5종 요약

| usecase 인터페이스 | 위치 | 구현 클래스 | 영향 진입점 |
|--------------------|------|------------|------------|
| `MemberQueryUseCase` | `member.application.usecase` | `MemberQueryService` | AdminMemberController, MemberInfoController, AdminRoleController, **MemberUserDetailsService (config.security)** |
| `RegisterOAuthClientUseCase` | `client.application.usecase` | `RegisterOAuthClientService` | AdminClientController, ClientController |
| `ClientRedirectUriUseCase` | `client.application.usecase` | `ClientRedirectUriService` | AdminClientController |
| `LoginTokenUseCase` | `api.application.usecase` | `LoginTokenService` | ReissueController, **JsonLoginAuthenticationFilter (config.security)**, **SecurityConfig (config.security)** |
| `LoginRedirectUseCase` | `api.application.usecase` | `LoginRedirectResolver` | **JsonLoginAuthenticationFilter (config.security)**, **SecurityConfig (config.security)** |

#### 3-4. Spring Bean 와이어링 불변 확인 포인트

- `SecurityConfig`가 `JsonLoginAuthenticationFilter`를 직접 `new`로 생성하는 구조 — 두 클래스가 모두 `config.security`로 이동하므로 `SecurityConfig`의 `import` 문에서 `JsonLoginAuthenticationFilter` import는 **불필요(동일 패키지)**. 필터 동작과 HTTP 계약은 불변.
- `MemberUserDetailsService`의 Spring Bean 이름(`"memberUserDetailsService"`)이 유지됨 — `@Service` 어노테이션의 기본 이름 유도는 FQCN이 아닌 클래스 단순명 기반이므로 패키지 이동과 무관하게 `"memberUserDetailsService"` 불변. `SecurityConfig`의 `@Qualifier("memberUserDetailsService")` 참조 안전.
- `GatewaySecurityConfig`에서 `JwtVerifier @Bean` 선언 — `JwtVerifier`가 동일 패키지(`config.security`)로 이동하므로 import 제거, `@Bean` 메서드 자체는 변경 없음.
- `MemberAutoConfiguration`의 `@ComponentScan("com.econo.auth.member")`가 루트 기준 전체 스캔. `persistence.*`, `application.*` 모두 하위 포함. **변경 불필요**.
- `ServiceClientAutoConfiguration`의 `@ComponentScan("com.econo.auth.client")`도 동일.
- `AuthApiApplication` 기본 컴포넌트 스캔(`com.econo.auth.api`) — `config.security`는 루트의 하위 패키지이므로 `@Configuration` 클래스(`SecurityConfig`)와 `@Service` 클래스(`MemberUserDetailsService`) 모두 자동 인식. `@ComponentScan` 변경 불필요.
- `ApiGatewayApplication` 기본 컴포넌트 스캔(`com.econo.auth.gateway`) — `config.security`도 하위 패키지 포함. `GatewaySecurityConfig`(`@Configuration`) 자동 인식. **변경 불필요**.

---

### 4. 회귀 검증 체크리스트 (패키지 이동·DIP 전환 후 수행)

아래 항목은 이동 완료 후 각 Phase에서 실행한다. 모두 통과해야 외부 API 계약이 유지된 것으로 간주한다.

#### 4-1. URL 매핑 불변 확인

- [ ] `grep -r "@GetMapping\|@PostMapping\|@PutMapping\|@PatchMapping\|@DeleteMapping\|@RequestMapping"` 결과가 이동 전과 동일한 경로 값을 가지는지 확인
- [ ] `RootController` (auth-api, `presentation.controller`) — `GET /` 매핑 유지
- [ ] `RootController` (api-gateway, `presentation.controller`) — `GET /` 매핑 유지 (WebFlux `Mono<String>` 반환 형태 동일)
- [ ] `MemberController` — `POST /api/v1/auth/signup` 유지
- [ ] `ReissueController` — `POST /api/v1/auth/reissue`, `POST /api/v1/auth/logout` 유지
- [ ] `AdminClientController` — `POST/GET/POST/DELETE/PUT /api/v1/admin/clients/**` 유지 (5개)
- [ ] `AdminMemberController` — `GET /api/v1/admin/members`, `PATCH /api/v1/admin/members/{memberId}/role` 유지
- [ ] `ClientController` — `POST /api/v1/clients` 유지
- [ ] `MemberInfoController` — `POST /api/v1/members/batch` 유지
- [ ] `JwksController` — `GET /api/v1/admin/jwks` 유지
- [ ] `AdminRoleController` — `PUT /api/v1/internal/members/{memberId}/role` 유지
- [ ] `JsonLoginAuthenticationFilter` — `AntPathRequestMatcher("/api/v1/auth/login", "POST")` 리터럴 변경 없음 확인 (`filter` → `config.security` 이동 후)

#### 4-2. 응답 스키마 / 상태코드 불변 확인

- [ ] `LoginResponse` DTO — 필드명·직렬화 형태 동일 (패키지 이동 후 `presentation.dto`에서 동일 내용)
- [ ] `SignupRequest` DTO — 필드명·`@Valid` 어노테이션 동일
- [ ] `GlobalExceptionHandler` — `exception` 패키지 위치 유지, 에러 코드 목록 불변 (아래 §5 참조)
- [ ] 각 컨트롤러의 `@ResponseStatus` 또는 `ResponseEntity` 반환 타입이 이동 전후 동일

#### 4-3. 인증/인가 불변 확인

- [ ] `SecurityConfig.appSecurityFilterChain` — `permitAll()` 경로 목록(§2-4) 변경 없음 (`config.security`로 이동 후)
- [ ] `@PassportAuth` 어노테이션 사용 위치 — 컨트롤러 이동 후에도 동일 메서드에 유지
- [ ] `BearerToPassportFilter` — GlobalFilter 등록 순서 및 `X-User-Passport` 헤더 주입 동작 불변 (`filter` → `config.security` 이동 후)
- [ ] `MemberUserDetailsService` Bean 이름 `"memberUserDetailsService"` 유지 — `security` → `config.security` 이동 후에도 `@Service` 기본 이름 `"memberUserDetailsService"` 유지됨, `SecurityConfig @Qualifier` 참조 안전
- [ ] `GatewaySecurityConfig` — `config` → `config.security` 이동 후 `GatewayRoutingConfig` 참조(`BearerToPassportFilter.permittedPaths()`) 경로 불변 (`GatewayRoutingConfig`는 `config/` 잔류)
- [ ] `JwtVerifier`, `PassportBuilder` — `security` → `config.security` 이동 후 `BearerToPassportFilter` 내부 동작 불변

#### 4-4. 보안 클래스 이동 후 패키지 제거 확인 (config/security 확정 반영)

- [ ] auth-api: `filter/` 패키지 완전 제거 — `JsonLoginAuthenticationFilter`가 `config.security`로 이동 완료 후 `filter/` 하위에 잔류 파일 없음
- [ ] auth-api: `security/` 패키지 완전 제거 — `MemberUserDetails`, `MemberUserDetailsService`가 `config.security`로 이동 완료 후 `security/` 하위에 잔류 파일 없음
- [ ] auth-api: `presentation/filter/` 패키지가 **생성되지 않음** — 보안 클래스의 최종 목표 패키지는 `config.security`이며 `presentation.filter`가 아님
- [ ] api-gateway: `filter/` 패키지 완전 제거 — `BearerToPassportFilter`가 `config.security`로 이동 완료 후 잔류 없음
- [ ] api-gateway: `security/` 패키지 완전 제거 — `JwtVerifier`, `PassportBuilder`가 `config.security`로 이동 완료 후 잔류 없음
- [ ] api-gateway: `web/` 패키지 완전 제거 — `RootController`가 `presentation.controller`로 이동 완료 후 잔류 없음

#### 4-5. DIP 전환 후 컴파일/테스트 통과 확인

- [ ] Phase 1 완료 후: `./gradlew :services:libs:member:compileJava` 성공
- [ ] Phase 1 완료 후: `./gradlew :services:libs:member:test` 전체 통과
- [ ] Phase 2 완료 후: `./gradlew :services:libs:service-client:compileJava` 성공
- [ ] Phase 2 완료 후: `./gradlew :services:libs:service-client:test` 전체 통과
- [ ] Phase 3 완료 후: `./gradlew :services:apis:auth-api:compileJava` 성공 (`MemberQueryUseCase` 주입 전환 포함, `config.security` 패키지 이동 포함)
- [ ] Phase 3 완료 후: `./gradlew :services:apis:auth-api:test` 전체 통과 (MockBean 대상 `MemberRepository` → `MemberQueryUseCase` 갱신 포함)
- [ ] Phase 4 완료 후: `./gradlew :services:apis:api-gateway:compileJava` 성공 (`config.security` 패키지 이동 포함)
- [ ] Phase 4 완료 후: `./gradlew :services:apis:api-gateway:test` 전체 통과
- [ ] Phase 5: `./gradlew clean build` 클린 빌드 통과
- [ ] Phase 5: `find . -name "* 2.java" -not -path "./.git/*"` — iCloud 충돌본 0건

#### 4-6. 테스트 MockBean 전환 확인 (DIP 영향 테스트)

아래 테스트 파일은 `@MockBean MemberRepository` → `@MockBean MemberQueryUseCase`로 변경이 필요하다. 이동 후 해당 변경이 적용되었는지 확인한다.

- [ ] `AdminMemberControllerTest` — `@MockBean MemberRepository` → `@MockBean MemberQueryUseCase`
- [ ] `AdminRoleControllerTest` — 동일
- [ ] `AdminClientControllerTest` — `@MockBean RegisterOAuthClientService` → `@MockBean RegisterOAuthClientUseCase`; `@MockBean ClientRedirectUriService` → `@MockBean ClientRedirectUriUseCase`
- [ ] `ClientControllerTest` — `@MockBean RegisterOAuthClientService` → `@MockBean RegisterOAuthClientUseCase`

#### 4-7. 계층 순서 검증 (Phase 5 통합 검증)

아래 grep 결과는 모두 **0건**이어야 한다.

```bash
# presentation 계층이 service/repository 구현체를 직접 참조하지 않는지
grep -rn "application.service\|application.repository\|persistence" \
  services/apis/auth-api/src/main/java/com/econo/auth/api/presentation

# config/security 계층도 동일하게 usecase 인터페이스만 참조하는지
grep -rn "application.service\|application.repository\|persistence" \
  services/apis/auth-api/src/main/java/com/econo/auth/api/config/security

# presentation/filter 패키지가 생성되지 않음을 확인
find services/apis/auth-api/src/main/java -type d -name "filter" | grep presentation
find services/apis/api-gateway/src/main/java -type d -name "filter" | grep presentation
```

---

### 5. 에러 응답 체계 — 변경 없음

`GlobalExceptionHandler`는 `exception` 패키지에 그대로 유지된다. 에러 응답 포맷도 동일하다.

```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "요청 값이 올바르지 않습니다.",
  "timestamp": "2026-06-13T12:00:00",
  "fieldErrors": [
    { "field": "loginId", "message": "loginId는 필수입니다." }
  ]
}
```

현재 에러 코드 목록 (변경 없음):

| 에러 코드 | HTTP 상태 | 발생 케이스 |
|-----------|-----------|------------|
| `AUTH_UNAUTHORIZED` | 401 | PassportException — 인증 실패 |
| `FORBIDDEN` | 403 | PassportException — 권한 부족 |
| `VALIDATION_FAILED` | 400 | Bean Validation 실패 |
| `MEMBER_NOT_FOUND` | 404 | MemberNotFoundException |
| `MEMBER_ALREADY_EXISTS` | 409 | MemberAlreadyExistsException |
| `CLIENT_NOT_FOUND` | 404 | InvalidClientException |
| `INVALID_PASSWORD_POLICY` | 400 | InvalidPasswordPolicyException |
| `INVALID_ARGUMENT` | 400 | IllegalArgumentException |
| `REDIRECT_URI_REQUIRED` | 400 | RedirectUriRequiredException |
| `UNSUPPORTED_GRANT_TYPE` | 400 | UnsupportedGrantTypeException |
| `DUPLICATE_CLIENT_NAME` | 409 | DuplicateClientNameException |
| `CLIENT_LIMIT_EXCEEDED` | 422 | ClientLimitExceededException |
| `DUPLICATE_RESOURCE` | 409 | DataIntegrityViolationException |
| `INTERNAL_SERVER_ERROR` | 500 | 예상치 못한 예외 |

---

## 체크리스트

- [x] todo의 모든 API 작업이 "해당 없음"으로 확인됨 (todo.md API 작업 섹션 — "해당 없음 — 순수 패키지 리팩토링")
- [x] API 변경 없음이 명시적으로 선언됨 (신규 엔드포인트 0개)
- [x] 이동 대상 컨트롤러 9개(auth-api) + 1개(api-gateway) 전체가 회귀 기준점으로 목록화됨 (RootController 2종 포함)
- [x] 이동 대상 보안 클래스: auth-api 4개(SecurityConfig, JsonLoginAuthenticationFilter, MemberUserDetails, MemberUserDetailsService) + api-gateway 4개(GatewaySecurityConfig, JwtVerifier, PassportBuilder, BearerToPassportFilter) 전체 목표 패키지 **`config.security`**로 확정
- [x] **이전 문서 오류 수정**: `presentation.filter`(auth-api), `presentation.util`(api-gateway의 JwtVerifier/PassportBuilder) 표기 → `config.security`로 전면 수정
- [x] SecurityConfig permittedPaths 목록이 현재 코드 기준으로 기록됨
- [x] 로그인 필터 URL 매핑 상수가 기록됨
- [x] 엄격 DIP 확정 — 컨트롤러/필터 13개 진입점의 repository→usecase 전환이 외부 계약에 무영향임이 설명됨 (SecurityConfig 2건 추가 반영)
- [x] 이전 미해결 사항("AdminMemberController 등 MemberRepository 직접 주입") → "결정됨: usecase 경유로 전환"으로 갱신됨
- [x] 신설 usecase 5종과 영향 진입점이 명시됨 (MemberUserDetailsService, JsonLoginAuthenticationFilter, SecurityConfig가 config.security 표기로 통일)
- [x] 에러 응답 체계가 현재 GlobalExceptionHandler 기준으로 문서화됨
- [x] Bean 와이어링 변경 포인트(MemberUserDetailsService Bean 이름 "memberUserDetailsService" 유지, SecurityConfig @Qualifier 참조 안전, 동일 패키지 이동으로 인한 import 제거 경우)가 명시됨
- [x] 회귀 검증 체크리스트 4-1 ~ 4-7이 라이브 소스 기반으로 작성됨
- [x] DIP 전환 대상 테스트 MockBean 변경 항목이 명시됨
- [x] presentation/filter 패키지 미생성 확인 항목(4-4)이 추가됨

---

## 참고

- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — permittedPaths 및 필터 체인 설정 (목표: `config.security.SecurityConfig`)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` — 에러 응답 체계
- `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` — 로그인 필터 (목표: `config.security.JsonLoginAuthenticationFilter`)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetailsService.java` — MemberRepository 직접 주입 확인 (line 20) (목표: `config.security.MemberUserDetailsService`)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetails.java` — (목표: `config.security.MemberUserDetails`)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/` — 이동 대상 컨트롤러 전체 (DIP 위반 3종 포함)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewaySecurityConfig.java` — (목표: `config.security.GatewaySecurityConfig`)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/BearerToPassportFilter.java` — 게이트웨이 GlobalFilter (목표: `config.security.BearerToPassportFilter`)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/JwtVerifier.java` — (목표: `config.security.JwtVerifier`)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/PassportBuilder.java` — (목표: `config.security.PassportBuilder`)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/web/RootController.java` — 게이트웨이 헬스체크 (목표: `presentation.controller.RootController`)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/RootController.java` — auth-api 헬스체크 (목표: `presentation.controller.RootController`)
- `docs/CONVENTION.md` — 현재 패키지/클래스 네이밍 컨벤션 (이번 작업 후 갱신 대상)
- `.claude/plans/09-layered-architecture/implementation-plan.md` — 신설 usecase 5종 상세 설계 및 Phase별 이동 순서 (config/security 최종 확정본)
