# layered-architecture - implementation

## 메타
- **작업명**: layered-architecture
- **문서 타입**: implementation
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - todo.md

---

## 개요

전체 5개 모듈(auth-api, api-gateway, member, service-client, common-infra)의 패키지 구조를 헥사고날(adapter/port) 어휘에서 `presentation / application(usecase+service+repository+domain) / persistence(entity+repository) / config / exception 계층 어휘로 통일하는 순수 리팩토링이다. Java 21 / Spring Boot 3.2.2 / Gradle Kotlin DSL 멀티모듈 위에서 동작한다. 엔드포인트·요청·응답·DB 스키마는 일체 변경하지 않으며 모든 기존 테스트가 통과해야 한다. 이 문서의 핵심 산출물은 모듈별 현재→목표 패키지 클래스 매핑표, 신규 usecase/repository 인터페이스 목록, config/security 배치 결정, imports/스캔 갱신 명세, 단계별 이동·검증 순서이다.

---

## 확정 불변식 (구현 전 체 공통)

다음 규칙은 이 문서 전체에서 예외 없이 적용된다.

1. **계층 의존 순서**: `presentation → application → persistence` 단방향만. presentation 및 `config/security`(보안 어댑터)는 `application.usecase` 인터페이스에만 의존. `repository` 직접 참조 금지. 계층 건너뛰기 금지.
2. **repository 경계 = 도메인 전용**: `application/repository` 출력 포트 인터페이스의 모든 메서드 시그니처(파라미터·반환)는 도메인 객체만 사용. `*JpaEntity`는 절대 포트 경계를 넘지 않음. entity↔domain 변환은 `persistence/repository`의 `*RepositoryAdapter` 책임.
3. **엄격 DIP**: presentation/보안 어댑터가 repository나 service 구현체를 직접 주입하는 모든 지점을 usecase 인터페이스 주입으로 전환. 신설 usecase 5종: `MemberQueryUseCase`, `RegisterOAuthClientUseCase`, `ClientRedirectUriUseCase`, `LoginTokenUseCase`, `LoginRedirectUseCase`.
4. **동작 중립**: 엔드포인트·요청·응답·DB 스키마 불변. 기존 테스트 그대로 통과.

---

## 본문

### 모듈 / 패키지 배치

| 모듈 / 패키지 경로 | 신규 / 변경 | 사유 |
|---|---|---|
| `…member/application/usecase/` | **신규** | `application/port/in/` 이동. 입력 포트 인터페이스 계층 |
| `…member/application/service/` | **신규** | `application/usecase/SignupService` 이동. usecase 구현체 계층 |
| `…member/application/repository/` | **신규** | `application/port/out/` 이동. 출력 포트(레포지토리 명세) 인터페이스 계층 |
| `…member/application/domain/` | **신규** | `domain/` 이동. 도메인 모델 계층 |
| `…member/persistence/entity/` | **신규** | `adapter/out/persistence/MemberJpaEntity` 이동. JPA 엔티티 계층 |
| `…member/persistence/repository/` | **신규** | `adapter/out/persistence/MemberJpaRepository·MemberRepositoryAdapter` + `adapter/out/security/BCryptPasswordHasherAdapter` 이동. 포트 구현체 계층 |
| `…client/application/usecase/` | **신규** | usecase 인터페이스 2종 신설. 기존 `application/usecase/` 경로는 service 구현체가 점유 → 패키지 재정의 |
| `…client/application/service/` | **신규** | `application/usecase/RegisterOAuthClientService·ClientRedirectUriService` 이동 |
| `…client/application/repository/` | **신규** | `application/port/out/` 이동. 출력 포트 인터페이스 계층 |
| `…client/application/domain/` | **신규** | `domain/` 이동 |
| `…client/persistence/entity/` | **신규** | `adapter/out/persistence/ServiceClientJpaEntity` 이동 |
| `…client/persistence/repository/` | **신규** | `adapter/out/persistence/ServiceClientJpaRepository·ServiceClientRepositoryAdapter` + `adapter/out/sas/*` 이동 |
| `…api/presentation/controller/` | **신규** | `adapter/in/web/` 컨트롤러 10종 이동 |
| `…api/presentation/dto/` | **신규** | `adapter/in/web/LoginResponse·SignupRequest` 이동 |
| `…api/presentation/util/` | **신규** | `adapter/in/web/TokenCookieManager` 이동 (`@Component` 쿠키 헬퍼) |
| `…api/application/usecase/` | **신규** | `LoginTokenUseCase`, `LoginRedirectUseCase` 신설 (엄격 DIP) |
| `…api/application/service/` | **신규** | `LoginTokenService`, `LoginRedirectResolver` 이동 |
| `…api/config/security/` | **신규** | `SecurityConfig` 이동 + `security/MemberUserDetailsService·MemberUserDetails`, `filter/JsonLoginAuthenticationFilter` 이동. 보안 어댑터 전용 서브패키지 |
| `…gateway/config/security/` | **신규** | `GatewaySecurityConfig` 이동 + `security/JwtVerifier·PassportBuilder`, `filter/BearerToPassportFilter` 이동. 보안 어댑터 전용 서브패키지 |
| `…gateway/presentation/controller/` | **신규** | `web/RootController` 이동 |
| `…commoninfra/config/` | **현행 유지** | `@EnableJpaAuditing` 단일 책임. 레이어 구조 불필요 |

---

### 구성 요소 설계

#### 모듈: `services/libs/member`

최종 타깃 패키지 트리:

```
com.econo.auth.member
├── config/
│   └── MemberAutoConfiguration          — @AutoConfiguration; @EntityScan/@EnableJpaRepositories 값 갱신
├── exception/
│   └── (변경 없음)
├── application/
│   ├── usecase/
│   │   ├── SignupUseCase                 — 입력 포트 인터페이스 (이동)
│   │   └── MemberQueryUseCase            — 입력 포트 인터페이스 (신설; 엄격 DIP)
│   ├── service/
│   │   ├── SignupService                 — SignupUseCase 구현체 (이동)
│   │   └── MemberQueryService            — MemberQueryUseCase 구현체 (신설; 엄격 DIP)
│   ├── repository/
│   │   ├── MemberRepository              — 출력 포트 인터페이스 (이동)
│   │   └── PasswordHasher                — 출력 포트 인터페이스 (이동)
│   └── domain/
│       ├── Member                        — (이동)
│       └── MemberStatus                  — (이동)
└── persistence/
    ├── entity/
    │   └── MemberJpaEntity               — JPA @Entity (이동)
    └── repository/
        ├── MemberJpaRepository           — Spring Data JPA 인터페이스 (이동)
        ├── MemberRepositoryAdapter       — MemberRepository 구현체 (이동)
        └── BCryptPasswordHasherAdapter   — PasswordHasher 구현체 (이동)
```

##### `SignupUseCase` (이동)
- **타입**: UseCase 입력 포트 인터페이스
- **현재 경로**: `com.econo.auth.member.application.port.in.SignupUseCase`
- **목표 경로**: `com.econo.auth.member.application.usecase.SignupUseCase`
- **책임**: 회원 가입 인바운드 포트. `SignupCommand` record 내포. 내부 import: `domain.*` → `application.domain.*`
- **적용 컨벤션**: 인터페이스 접미사 `{Action}UseCase`
- **참조할 기존 코드**: `services/libs/member/src/main/java/com/econo/auth/member/application/port/in/SignupUseCase.java:1`
- **연관 todo**: `[ ] application/port/in/SignupUseCase → application/usecase/SignupUseCase`

##### `MemberQueryUseCase` (신설 — 엄격 DIP)
- **타입**: UseCase 입력 포트 인터페이스 (신규)
- **목표 경로**: `com.econo.auth.member.application.usecase.MemberQueryUseCase`
- **책임**: `AdminMemberController`, `MemberInfoController`, `AdminRoleController`, `MemberUserDetailsService`(→ `config/security`)가 `MemberRepository`를 직접 주입하는 것을 막기 위한 presentation → usecase 경계 seam.
- **주요 메서드/함수**:
  - `findByLoginId(String loginId)` — `Optional<Member>` 반환
  - `findById(Long memberId)` — `Optional<Member>` 반환
  - `findAllByIds(List<Long> ids)` — `List<Member>` 반환
  - `findPaged(int page, int size, String role)` — `List<Member>` 반환
  - `count(String role)` — `long` 반환
  - `countByRole(String role)` — `long` 반환
  - `updateRole(Long memberId, String role)` — `void`
- **의존성**: 없음 (인터페이스)
- **적용 컨벤션**: 인터페이스 접미사 `{Resource}QueryUseCase`
- **연관 todo**: `[ ] AdminMemberController, MemberInfoController, AdminRoleController, MemberUserDetailsService — MemberRepository 직접 의존 제거, MemberQueryUseCase 신설`

##### `SignupService` (이동)
- **타입**: Service (SignupUseCase 구현체)
- **현재 경로**: `com.econo.auth.member.application.usecase.SignupService`
- **목표 경로**: `com.econo.auth.member.application.service.SignupService`
- **책임**: `SignupUseCase` 구현. 입력 검증·중복 확인·비밀번호 해싱·회원 저장.
- **주요 메서드**: `signup(SignupCommand)` — 전체 가입 흐름
- **의존성**: `application.repository.MemberRepository`, `application.repository.PasswordHasher`
- **빈 등록 방식**: `ApplicationServiceConfig.signupService()` 수동 `@Bean` 유지. import 갱신.
- **적용 컨벤션**: `@RequiredArgsConstructor` 유지
- **참조할 기존 코드**: `services/libs/member/src/main/java/com/econo/auth/member/application/usecase/SignupService.java:1`
- **연관 todo**: `[ ] application/usecase/SignupService → application/service/SignupService`

##### `MemberQueryService` (신설 — 엄격 DIP)
- **타입**: Service (MemberQueryUseCase 구현체, 신규)
- **목표 경로**: `com.econo.auth.member.application.service.MemberQueryService`
- **책임**: `MemberQueryUseCase` 구현. `MemberRepository` 포트에 단순 위임. 비즈니스 로직 없음 — 계층 경계 역할만.
- **주요 메서드**: `MemberQueryUseCase`의 모든 메서드를 `MemberRepository`에 위임
- **의존성**: `application.repository.MemberRepository`
- **빈 등록 방식**: `@Service` 추가. `MemberAutoConfiguration`의 `@ComponentScan("com.econo.auth.member")`가 자동 인식.
- **적용 컨벤션**: `@Service`, `@RequiredArgsConstructor`, `@Transactional(readOnly = true)` (조회 메서드), `@Transactional` (`updateRole`)
- **연관 todo**: `[ ] MemberQueryService 신설`

##### `MemberRepository`, `PasswordHasher` (이동)
- **타입**: Repository 출력 포트 인터페이스
- **현재 경로**: `com.econo.auth.member.application.port.out.MemberRepository`, `…PasswordHasher`
- **목표 경로**: `com.econo.auth.member.application.repository.MemberRepository`, `…PasswordHasher`
- **책임**: 회원 영속성 조작 포트. 메서드 시그니처 변경 없음. 파라미터·반환 타입은 `application.domain.*` 도메인 객체만 사용(불변식 2).
- **참조할 기존 코드**: `services/libs/member/src/main/java/com/econo/auth/member/application/port/out/MemberRepository.java:1`
- **연관 todo**: `[ ] application/port/out/MemberRepository → application/repository/MemberRepository`, `[ ] application/port/out/PasswordHasher → application/repository/PasswordHasher`

##### `Member`, `MemberStatus` (이동)
- **타입**: Domain 모델
- **현재 경로**: `com.econo.auth.member.domain.*`
- **목표 경로**: `com.econo.auth.member.application.domain.*`
- **책임**: 도메인 모델. 변경 없음. `application.usecase`·`application.service`·`application.repository`에서 참조.
- **연관 todo**: `[ ] domain/* → application/domain/*`

##### `MemberJpaEntity` (이동)
- **타입**: JPA @Entity
- **현재 경로**: `com.econo.auth.member.adapter.out.persistence.MemberJpaEntity`
- **목표 경로**: `com.econo.auth.member.persistence.entity.MemberJpaEntity`
- **책임**: `members` 테이블 엔티티. `@EntityScan` 대상 경로 변경. 내부 import: `domain.*` → `application.domain.*`
- **참조할 기존 코드**: `services/libs/member/src/main/java/com/econo/auth/member/adapter/out/persistence/MemberJpaEntity.java:1`
- **연관 todo**: `[ ] adapter/out/persistence/MemberJpaEntity → persistence/entity/MemberJpaEntity`

##### `MemberJpaRepository`, `MemberRepositoryAdapter`, `BCryptPasswordHasherAdapter` (이동)
- **타입**: persistence.repository (포트 구현체)
- **현재 경로**: `com.econo.auth.member.adapter.out.persistence.*`, `com.econo.auth.member.adapter.out.security.*`
- **목표 경로**: `com.econo.auth.member.persistence.repository.*`
- **책임**:
  - `MemberJpaRepository` — Spring Data JPA 인터페이스. `@EnableJpaRepositories` 대상 경로 변경.
  - `MemberRepositoryAdapter` — `application.repository.MemberRepository` 구현. `@Component` 유지.
  - `BCryptPasswordHasherAdapter` — `application.repository.PasswordHasher` 구현. `@Component` 유지.
- **의존성 내부 갱신**: `application.port.out.MemberRepository` → `application.repository.MemberRepository`, `application.port.out.PasswordHasher` → `application.repository.PasswordHasher`. domain import → `application.domain.*`
- **연관 todo**: `[ ] adapter/out/persistence/* → persistence/repository/*`, `[ ] adapter/out/security/* → persistence/repository/*`

##### `MemberAutoConfiguration` (갱신)
- **현재 경로**: `com.econo.auth.member.config.MemberAutoConfiguration` — **이동 없음**
- **갱신 내용**:
  - `@EnableJpaRepositories("com.econo.auth.member.adapter.out.persistence")` → `@EnableJpaRepositories("com.econo.auth.member.persistence.repository")`
  - `@EntityScan("com.econo.auth.member.adapter.out.persistence")` → `@EntityScan("com.econo.auth.member.persistence.entity")`
  - `@ComponentScan("com.econo.auth.member")` — 루트 기준 전체 스캔. `persistence.*`, `application.*` 모두 하위 포함. **변경 불필요.**
- **imports 파일**: FQCN `com.econo.auth.member.config.MemberAutoConfiguration` 불변 → **변경 불필요**
- **참조할 기존 코드**: `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java:17`
- **연관 todo**: `[ ] MemberAutoConfiguration @EnableJpaRepositories / @EntityScan 경로 갱신`

---

#### 모듈: `services/libs/service-client`

최종 타깃 패키지 트리:

```
com.econo.auth.client
├── config/
│   └── ServiceClientAutoConfiguration   — @AutoConfiguration; @EntityScan/@EnableJpaRepositories 값 갱신
├── exception/
│   └── (변경 없음)
├── application/
│   ├── usecase/
│   │   ├── RegisterOAuthClientUseCase    — 입력 포트 인터페이스 (신설; 엄격 DIP)
│   │   └── ClientRedirectUriUseCase      — 입력 포트 인터페이스 (신설; 엄격 DIP)
│   ├── service/
│   │   ├── RegisterOAuthClientService    — RegisterOAuthClientUseCase 구현체 (이동)
│   │   └── ClientRedirectUriService      — ClientRedirectUriUseCase 구현체 (이동)
│   ├── repository/
│   │   ├── ServiceClientRepository       — 출력 포트 인터페이스 (이동)
│   │   ├── SasClientRegistrar            — 출력 포트 인터페이스 (이동)
│   │   └── SasRedirectUriManager         — 출력 포트 인터페이스 (이동)
│   └── domain/
│       ├── ServiceClient                 — (이동)
│       └── GrantType                     — (이동)
└── persistence/
    ├── entity/
    │   └── ServiceClientJpaEntity        — JPA @Entity (이동)
    └── repository/
        ├── ServiceClientJpaRepository    — Spring Data JPA 인터페이스 (이동)
        ├── ServiceClientRepositoryAdapter — ServiceClientRepository 구현체 (이동)
        ├── SasClientRegistrarAdapter     — SasClientRegistrar 구현체 (이동)
        └── SasRedirectUriManagerAdapter  — SasRedirectUriManager 구현체 (이동)
```

##### `RegisterOAuthClientUseCase` (신설 — 엄격 DIP)
- **타입**: UseCase 입력 포트 인터페이스 (신규)
- **목표 경로**: `com.econo.auth.client.application.usecase.RegisterOAuthClientUseCase`
- **책임**: `AdminClientController`와 `ClientController`가 `RegisterOAuthClientService` 구현체를 직접 주입하는 것을 막는 seam.
- **주요 메서드**:
  - `register(RegisterOAuthClientCommand)` — `RegisterOAuthClientResult` 반환
  - `selfRegister(SelfRegisterOAuthClientCommand)` — `SelfRegisterOAuthClientResult` 반환
  - command/result record들은 현재 `RegisterOAuthClientService`에 내포된 것을 이 인터페이스로 이동
- **의존성**: 없음 (인터페이스)
- **연관 todo**: `[ ] RegisterOAuthClientUseCase 신설`

##### `ClientRedirectUriUseCase` (신설 — 엄격 DIP)
- **타입**: UseCase 입력 포트 인터페이스 (신규)
- **목표 경로**: `com.econo.auth.client.application.usecase.ClientRedirectUriUseCase`
- **책임**: `AdminClientController`가 `ClientRedirectUriService` 구현체를 직접 주입하는 것을 막는 seam. `LoginRedirectResolver`(→ `api.application.service`)도 이 인터페이스에 의존.
- **주요 메서드**:
  - `findByClientId(String clientId)` — `ClientInfo` 반환
  - `addRedirectUri(String clientId, String uri)` — `Set<String>` 반환
  - `removeRedirectUri(String clientId, String uri)` — `Set<String>` 반환
  - `replaceRedirectUris(String clientId, Set<String> uris)` — `Set<String>` 반환
  - `extractAllowedOrigins(Set<String> additionalOrigins)` — `Set<String>` 반환
  - `ClientInfo` record는 현재 `ClientRedirectUriService`에 내포된 것을 이 인터페이스로 이동
- **의존성**: 없음 (인터페이스)
- **연관 todo**: `[ ] ClientRedirectUriUseCase 신설`

##### `RegisterOAuthClientService` (이동)
- **타입**: Service (RegisterOAuthClientUseCase 구현체)
- **현재 경로**: `com.econo.auth.client.application.usecase.RegisterOAuthClientService`
- **목표 경로**: `com.econo.auth.client.application.service.RegisterOAuthClientService`
- **책임**: OAuth 클라이언트 등록(어드민·셀프). `@Service` + `@Transactional` 유지.
- **의존성**: `application.repository.SasClientRegistrar`, `application.repository.ServiceClientRepository`, `PasswordEncoder`
- **import 갱신**: `application.port.out.*` → `application.repository.*`, `domain.*` → `application.domain.*`
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/RegisterOAuthClientService.java:1`
- **연관 todo**: `[ ] application/usecase/RegisterOAuthClientService → application/service/RegisterOAuthClientService`

##### `ClientRedirectUriService` (이동)
- **타입**: Service (ClientRedirectUriUseCase 구현체)
- **현재 경로**: `com.econo.auth.client.application.usecase.ClientRedirectUriService`
- **목표 경로**: `com.econo.auth.client.application.service.ClientRedirectUriService`
- **책임**: redirectUri 관리(추가·삭제·교체·CORS 오리진 추출). `@Service` 유지.
- **의존성**: `application.repository.SasRedirectUriManager`, `application.repository.ServiceClientRepository`
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ClientRedirectUriService.java:1`
- **연관 todo**: `[ ] application/usecase/ClientRedirectUriService → application/service/ClientRedirectUriService`

##### `ServiceClientRepository`, `SasClientRegistrar`, `SasRedirectUriManager` (이동)
- **타입**: Repository 출력 포트 인터페이스
- **현재 경로**: `com.econo.auth.client.application.port.out.*`
- **목표 경로**: `com.econo.auth.client.application.repository.*`
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/application/port/out/ServiceClientRepository.java:1`
- **연관 todo**: `[ ] application/port/out/* → application/repository/*`

##### `ServiceClient`, `GrantType` (이동)
- **타입**: Domain 모델
- **현재 경로**: `com.econo.auth.client.domain.*`
- **목표 경로**: `com.econo.auth.client.application.domain.*`
- **연관 todo**: `[ ] domain/* → application/domain/*`

##### `ServiceClientJpaEntity` (이동)
- **현재 경로**: `com.econo.auth.client.adapter.out.persistence.ServiceClientJpaEntity`
- **목표 경로**: `com.econo.auth.client.persistence.entity.ServiceClientJpaEntity`
- **갱신 내용**: import `domain.GrantType` → `application.domain.GrantType`, import `domain.ServiceClient` → `application.domain.ServiceClient`
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceClientJpaEntity.java:1`
- **연관 todo**: `[ ] adapter/out/persistence/ServiceClientJpaEntity → persistence/entity/ServiceClientJpaEntity`

##### `ServiceClientJpaRepository`, `ServiceClientRepositoryAdapter`, `SasClientRegistrarAdapter`, `SasRedirectUriManagerAdapter` (이동)
- **현재 경로**: `com.econo.auth.client.adapter.out.persistence.*`, `com.econo.auth.client.adapter.out.sas.*`
- **목표 경로**: `com.econo.auth.client.persistence.repository.*`
- **갱신 내용**: import `application.port.out.*` → `application.repository.*`, import `domain.*` → `application.domain.*`
- **연관 todo**: `[ ] adapter/out/persistence/* → persistence/repository/*`, `[ ] adapter/out/sas/* → persistence/repository/*`

##### `ServiceClientAutoConfiguration` (갱신)
- **현재 경로**: `com.econo.auth.client.config.ServiceClientAutoConfiguration` — **이동 없음**
- **갱신 내용**:
  - `@EnableJpaRepositories("com.econo.auth.client.adapter.out.persistence")` → `@EnableJpaRepositories("com.econo.auth.client.persistence.repository")`
  - `@EntityScan("com.econo.auth.client.adapter.out.persistence")` → `@EntityScan("com.econo.auth.client.persistence.entity")`
  - `@ComponentScan("com.econo.auth.client")` — **변경 불필요**
- **imports 파일**: FQCN 불변 → **변경 불필요**
- **참조할 기존 코드**: `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java:17`
- **연관 todo**: `[ ] ServiceClientAutoConfiguration @EnableJpaRepositories / @EntityScan 경로 갱신`

---

#### 모듈: `services/apis/auth-api`

최종 타깃 패키지 트리:

```
com.econo.auth.api
├── AuthApiApplication               — (변경 없음)
├── presentation/
│   ├── controller/
│   │   ├── AdminClientController    — (이동 + DIP 전환)
│   │   ├── AdminMemberController    — (이동 + DIP 전환)
│   │   ├── AdminRoleController      — (이동 + DIP 전환)
│   │   ├── ClientController         — (이동 + DIP 전환)
│   │   ├── JwksController           — (이동)
│   │   ├── MemberController         — (이동)
│   │   ├── MemberInfoController     — (이동 + DIP 전환)
│   │   ├── ReissueController        — (이동 + DIP 전환)
│   │   └── RootController           — (이동)
│   ├── dto/
│   │   ├── LoginResponse            — (이동)
│   │   └── SignupRequest            — (이동)
│   └── util/
│       └── TokenCookieManager       — (이동; @Component 쿠키 헬퍼)
├── application/
│   ├── usecase/
│   │   ├── LoginTokenUseCase        — 입력 포트 인터페이스 (신설; 엄격 DIP)
│   │   └── LoginRedirectUseCase     — 입력 포트 인터페이스 (신설; 엄격 DIP)
│   └── service/
│       ├── LoginTokenService        — LoginTokenUseCase 구현체 (이동)
│       └── LoginRedirectResolver    — LoginRedirectUseCase 구현체 (이동)
├── config/
│   ├── security/                    — 보안 어댑터 전용 서브패키지 (신규)
│   │   ├── SecurityConfig           — (이동 + DIP 전환: LoginTokenService→LoginTokenUseCase, LoginRedirectResolver→LoginRedirectUseCase)
│   │   ├── MemberUserDetailsService — (이동 + DIP 전환: MemberRepository→MemberQueryUseCase)
│   │   └── MemberUserDetails        — (이동)
│   ├── ApplicationServiceConfig     — (변경 없음 — import 갱신)
│   ├── DynamicCorsConfigurationSource — (변경 없음)
│   ├── RegisteredClientConfig       — (변경 없음)
│   ├── RsaKeyConfig                 — (변경 없음)
│   └── AuthRedirectProperties       — (변경 없음)
└── exception/
    └── GlobalExceptionHandler       — (변경 없음)
```

> **auth-api config/security 배치 근거**: `SecurityConfig`는 Spring Security `@EnableWebSecurity`에 결합된 보안 설정이다. `MemberUserDetailsService`, `MemberUserDetails`, `JsonLoginAuthenticationFilter`는 Security 파이프라인에서만 사용되므로 같은 `config/security` 서브패키지에 배치한다. 결과적으로 `presentation/filter` 패키지는 남지 않으며 `presentation`은 `controller/dto/util`만 포함한다.

> **불변식 1 준수**: `config/security`의 보안 어댑터(`MemberUserDetailsService`, `SecurityConfig`)가 `application.usecase` 인터페이스에 의존하는 것은 허용. `repository` 직접 의존 금지.

##### 컨트롤러 9종 (이동)
- **타입**: Controller (presentation 계층)
- **현재 경로**: `com.econo.auth.api.adapter.in.web.*Controller`
- **목표 경로**: `com.econo.auth.api.presentation.controller.*Controller`
- **연관 todo**: `[ ] adapter/in/web/ 하위 컨트롤러 전체 → presentation/controller/`

`RootController`는 main 머지 신규 추가 파일. 현재 `com.econo.auth.api.adapter.in.web.RootController` — 동일 규칙으로 이동.

##### `AdminMemberController` (이동 + DIP 전환)
- **현재 의존**: `com.econo.auth.member.application.port.out.MemberRepository` (포트 인터페이스를 presentation이 직접 주입 — DIP 위반)
- **목표 의존**: `com.econo.auth.member.application.usecase.MemberQueryUseCase`
- **갱신 내용**: 필드 타입 + 내부 `memberRepository.*` 호출 → `memberQueryUseCase.*` 전환
- **연관 todo**: `[ ] AdminMemberController — MemberRepository 직접 의존 제거`

##### `MemberInfoController` (이동 + DIP 전환)
- **현재 의존**: `MemberRepository` 직접
- **목표 의존**: `MemberQueryUseCase`
- **갱신 내용**: `memberRepository.findAllByIds(...)` → `memberQueryUseCase.findAllByIds(...)`
- **연관 todo**: `[ ] MemberInfoController — MemberRepository 직접 의존 제거`

##### `AdminRoleController` (이동 + DIP 전환)
- **현재 의존**: `MemberRepository` 직접
- **목표 의존**: `MemberQueryUseCase`
- **갱신 내용**: `memberRepository.findById(...)` → `memberQueryUseCase.findById(...)`, `memberRepository.updateRole(...)` → `memberQueryUseCase.updateRole(...)`
- **연관 todo**: `[ ] AdminRoleController — MemberRepository 직접 의존 제거`

##### `AdminClientController` (이동 + DIP 전환)
- **현재 의존**: `RegisterOAuthClientService` 구현체 직접, `ClientRedirectUriService` 구현체 직접
- **목표 의존**: `RegisterOAuthClientUseCase`, `ClientRedirectUriUseCase`
- **갱신 내용**: 필드 타입 + 내부 호출 메서드 변경. command/result record는 usecase 인터페이스에서 참조.
- **연관 todo**: `[ ] AdminClientController — service 구현체 직접 의존 제거`

##### `ClientController` (이동 + DIP 전환)
- **현재 의존**: `RegisterOAuthClientService` 구현체 직접
- **목표 의존**: `RegisterOAuthClientUseCase`
- **연관 todo**: `[ ] ClientController — service 구현체 직접 의존 제거`

##### `ReissueController` (이동 + DIP 전환)
- **현재 의존**: `LoginTokenService` 구현체 직접
- **목표 의존**: `LoginTokenUseCase`
- **갱신 내용**: 필드 타입 변경, `LoginTokenService.TokenPair` → `LoginTokenUseCase.TokenPair`
- **연관 todo**: `[ ] ReissueController — LoginTokenService 직접 의존 제거`

##### `LoginResponse`, `SignupRequest` (이동)
- **타입**: DTO
- **현재 경로**: `com.econo.auth.api.adapter.in.web.*`
- **목표 경로**: `com.econo.auth.api.presentation.dto.*`
- **연관 todo**: `[ ] adapter/in/web/ 하위 DTO → presentation/dto/`

##### `TokenCookieManager` (이동)
- **타입**: 웹 유틸리티 (`@Component`, `HttpServletResponse`에 AT/RT 쿠키 세팅 — 데이터 객체 아님)
- **현재 경로**: `com.econo.auth.api.adapter.in.web.TokenCookieManager`
- **목표 경로**: `com.econo.auth.api.presentation.util.TokenCookieManager`
- **연관 todo**: `[ ] adapter/in/web/TokenCookieManager → presentation/util/`

##### `LoginTokenUseCase` (신설 — 엄격 DIP)
- **타입**: UseCase 입력 포트 인터페이스 (신규)
- **목표 경로**: `com.econo.auth.api.application.usecase.LoginTokenUseCase`
- **책임**: `ReissueController`와 `JsonLoginAuthenticationFilter`(→ `config/security`)가 `LoginTokenService` 구현체를 직접 주입하는 것을 막는 seam.
- **주요 메서드**:
  - `issue(Member member)` — `TokenPair` 반환
  - `reissue(Long memberId)` — `TokenPair` 반환
  - `extractMemberIdFromRt(Jwt jwt)` — `Long` 반환
  - `TokenPair` record는 인터페이스에 내포 (현재 `LoginTokenService.TokenPair`에서 이동)
- **연관 todo**: `[ ] LoginTokenUseCase 신설`

##### `LoginRedirectUseCase` (신설 — 엄격 DIP)
- **타입**: UseCase 입력 포트 인터페이스 (신규)
- **목표 경로**: `com.econo.auth.api.application.usecase.LoginRedirectUseCase`
- **책임**: `JsonLoginAuthenticationFilter`(→ `config/security`)와 `SecurityConfig`(→ `config/security`)가 `LoginRedirectResolver` 구현체를 직접 주입하는 것을 막는 seam.
- **주요 메서드**: `resolve(String clientId, String defaultUrl)` — `String` 반환
- **연관 todo**: `[ ] LoginRedirectUseCase 신설`

##### `LoginTokenService` (이동)
- **현재 경로**: `com.econo.auth.api.application.LoginTokenService`
- **목표 경로**: `com.econo.auth.api.application.service.LoginTokenService`
- **책임**: `LoginTokenUseCase` 구현. AT/RT 발급·재발급. `@Service` 어노테이션 현재 있음.
- **현재 의존**: `MemberRepository` (`member.application.port.out.MemberRepository`) — application 계층이 application.repository 인터페이스에 의존하는 것은 올바름. import만 갱신.
- **갱신 내용**:
  - import `member.application.port.out.MemberRepository` → `member.application.repository.MemberRepository`
  - import `member.domain.*` → `member.application.domain.*`
  - `implements LoginTokenUseCase` 선언 추가
  - `TokenPair` record를 `LoginTokenUseCase`로 이동 후 참조
- **빈 등록**: `@Service`가 있으므로 컴포넌트 스캔 자동 인식. `ApplicationServiceConfig`에서 `LoginTokenService` 수동 빈 등록이 없음을 확인(현재 `ApplicationServiceConfig`는 `SignupService`, `LoginRedirectResolver`만 수동 등록). **수동 등록 신설 불필요.**
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/LoginTokenService.java:1`
- **연관 todo**: `[ ] application/LoginTokenService → application/service/LoginTokenService`

##### `LoginRedirectResolver` (이동)
- **현재 경로**: `com.econo.auth.api.application.LoginRedirectResolver`
- **목표 경로**: `com.econo.auth.api.application.service.LoginRedirectResolver`
- **책임**: `LoginRedirectUseCase` 구현. clientId 기반 redirect_uri 결정.
- **갱신 내용**:
  - `implements LoginRedirectUseCase` 선언 추가
  - 필드 타입 `ClientRedirectUriService` → `ClientRedirectUriUseCase`
  - import `client.application.usecase.ClientRedirectUriService` → `client.application.usecase.ClientRedirectUriUseCase`
  - import `client.application.usecase.ClientRedirectUriService.ClientInfo` → `client.application.usecase.ClientRedirectUriUseCase.ClientInfo`
- **빈 등록**: `ApplicationServiceConfig.loginRedirectResolver()` 수동 `@Bean` 유지. `@Bean` 파라미터 타입을 `ClientRedirectUriUseCase`로 변경.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/application/LoginRedirectResolver.java:1`
- **연관 todo**: `[ ] application/LoginRedirectResolver → application/service/LoginRedirectResolver`

##### `SecurityConfig` (이동 + DIP 전환)
- **현재 경로**: `com.econo.auth.api.config.SecurityConfig`
- **목표 경로**: `com.econo.auth.api.config.security.SecurityConfig`
- **현재 의존**: `LoginTokenService` 구현체 직접, `LoginRedirectResolver` 구현체 직접 (`appSecurityFilterChain` 파라미터)
- **목표 의존**: `LoginTokenUseCase`, `LoginRedirectUseCase` (불변식 1: config/security → application.usecase만)
- **갱신 내용**:
  - package 선언: `config` → `config.security`
  - `@Autowired(required = false) LoginTokenService loginTokenService` → `@Autowired(required = false) LoginTokenUseCase loginTokenUseCase`
  - `@Autowired(required = false) LoginRedirectResolver loginRedirectResolver` → `@Autowired(required = false) LoginRedirectUseCase loginRedirectUseCase`
  - `new JsonLoginAuthenticationFilter(…, loginTokenService, …, loginRedirectResolver, …)` → `new JsonLoginAuthenticationFilter(…, loginTokenUseCase, …, loginRedirectUseCase, …)`
  - import `com.econo.auth.api.adapter.in.web.TokenCookieManager` → `com.econo.auth.api.presentation.util.TokenCookieManager`
  - import `com.econo.auth.api.filter.JsonLoginAuthenticationFilter` → `com.econo.auth.api.config.security.JsonLoginAuthenticationFilter`
  - import `LoginTokenService`, `LoginRedirectResolver` → `LoginTokenUseCase`, `LoginRedirectUseCase`
- **스캔 영향**: `config.security` 패키지는 `AuthApiApplication`의 기본 컴포넌트 스캔(`com.econo.auth.api`) 하위. `@ComponentScan` 변경 불필요. `@Configuration` 어노테이션으로 자동 인식.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java:1`
- **연관 todo**: `[ ] SecurityConfig → config/security/SecurityConfig, DIP 전환`

##### `JsonLoginAuthenticationFilter` (이동 + DIP 전환)
- **현재 경로**: `com.econo.auth.api.filter.JsonLoginAuthenticationFilter`
- **목표 경로**: `com.econo.auth.api.config.security.JsonLoginAuthenticationFilter`
- **현재 의존**: `LoginTokenService` 구현체 직접, `LoginRedirectResolver` 구현체 직접
- **목표 의존**: `LoginTokenUseCase`, `LoginRedirectUseCase`
- **갱신 내용**:
  - package 선언: `filter` → `config.security`
  - 필드 타입 `LoginTokenService` → `LoginTokenUseCase`, `LoginRedirectResolver` → `LoginRedirectUseCase`
  - 생성자 파라미터 타입 동일 변경
  - `loginTokenService.issue(...)` → `loginTokenUseCase.issue(...)`
  - `loginRedirectResolver.resolve(...)` → `loginRedirectUseCase.resolve(...)`
  - `LoginTokenService.TokenPair` → `LoginTokenUseCase.TokenPair`
  - import `LoginResponse` → `presentation.dto.LoginResponse`
  - import `TokenCookieManager` → `presentation.util.TokenCookieManager`
  - import `MemberUserDetails` → `config.security.MemberUserDetails`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java:1`
- **연관 todo**: `[ ] filter/JsonLoginAuthenticationFilter → config/security/JsonLoginAuthenticationFilter`

##### `MemberUserDetails` (이동)
- **현재 경로**: `com.econo.auth.api.security.MemberUserDetails`
- **목표 경로**: `com.econo.auth.api.config.security.MemberUserDetails`
- **갱신 내용**: package 선언 변경. import `member.domain.Member` → `member.application.domain.Member`
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetails.java:1`
- **연관 todo**: `[ ] security/MemberUserDetails → config/security/MemberUserDetails`

##### `MemberUserDetailsService` (이동 + DIP 전환)
- **현재 경로**: `com.econo.auth.api.security.MemberUserDetailsService`
- **목표 경로**: `com.econo.auth.api.config.security.MemberUserDetailsService`
- **현재 의존**: `MemberRepository` 직접 (`member.application.port.out.MemberRepository`)
- **목표 의존**: `MemberQueryUseCase` (`member.application.usecase.MemberQueryUseCase`)
- **갱신 내용**:
  - package 선언: `security` → `config.security`
  - 필드 `MemberRepository memberRepository` → `MemberQueryUseCase memberQueryUseCase`
  - `memberRepository.findByLoginId(...)` → `memberQueryUseCase.findByLoginId(...)`
  - import 갱신: `member.application.port.out.MemberRepository` → `member.application.usecase.MemberQueryUseCase`
  - `MemberUserDetails` import → `config.security.MemberUserDetails` (동일 패키지이므로 import 불필요)
- **빈 이름**: `@Service` → Spring 기본 이름 `"memberUserDetailsService"`. `SecurityConfig`의 `@Qualifier("memberUserDetailsService")` 참조 유지됨.
- **참조할 기존 코드**: `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetailsService.java:1`
- **연관 todo**: `[ ] security/MemberUserDetailsService → config/security/MemberUserDetailsService`

##### `ApplicationServiceConfig` (import 갱신 + 빈 파라미터 타입 변경)
- **패키지**: `config` 유지 (이동 없음)
- **갱신 내용**:
  - `import com.econo.auth.member.application.port.out.MemberRepository` → `com.econo.auth.member.application.repository.MemberRepository`
  - `import com.econo.auth.member.application.port.out.PasswordHasher` → `com.econo.auth.member.application.repository.PasswordHasher`
  - `import com.econo.auth.member.application.usecase.SignupService` → `com.econo.auth.member.application.service.SignupService`
  - `import com.econo.auth.client.application.usecase.ClientRedirectUriService` → `com.econo.auth.client.application.usecase.ClientRedirectUriUseCase` (파라미터 타입 변경)
  - `import com.econo.auth.api.application.LoginRedirectResolver` → `com.econo.auth.api.application.service.LoginRedirectResolver`
  - `loginRedirectResolver(@Autowired ClientRedirectUriService …)` → `loginRedirectResolver(@Autowired ClientRedirectUriUseCase …)`
- **연관 todo**: `[ ] ApplicationServiceConfig import + 파라미터 타입 갱신`

---

#### 모듈: `services/apis/api-gateway`

최종 타깃 패키지 트리:

```
com.econo.auth.gateway
├── ApiGatewayApplication            — (변경 없음)
├── presentation/
│   └── controller/
│       └── RootController           — (이동)
└── config/
    ├── security/                    — 보안 어댑터 전용 서브패키지 (신규)
    │   ├── GatewaySecurityConfig    — (이동 + import 갱신)
    │   ├── JwtVerifier              — (이동)
    │   ├── PassportBuilder          — (이동)
    │   └── BearerToPassportFilter   — (이동 + import 갱신)
    └── GatewayRoutingConfig         — (변경 없음)
```

> **api-gateway config/security 배치 근거**: `GatewaySecurityConfig`는 `@EnableWebFluxSecurity`에 결합된 보안 설정이다. `JwtVerifier`, `PassportBuilder`는 JWT 처리 유틸로 보안 파이프라인(`BearerToPassportFilter`)에서만 사용된다. 이들을 `config/security` 서브패키지로 묶는 것이 호출자 확정 지시이며, 이전 플랜의 `presentation/util` 배치안은 **폐기**한다. 결과적으로 `gateway presentation`은 `controller(RootController)`만 남고 `filter/`, `security/` 패키지는 제거된다.

> **주의**: `BearerToPassportFilter`는 `GatewayRoutingConfig`에도 의존한다(`routingConfig.permittedPaths()`). `GatewayRoutingConfig`는 `config/`에 잔류하므로 이동 후 import `gateway.config.GatewayRoutingConfig`는 변경 없음.

##### `RootController` (이동)
- **타입**: Controller (presentation 계층, WebFlux `@RestController`)
- **현재 경로**: `com.econo.auth.gateway.web.RootController`
- **목표 경로**: `com.econo.auth.gateway.presentation.controller.RootController`
- **연관 todo**: `[ ] web/RootController → presentation/controller/RootController`

##### `GatewaySecurityConfig` (이동 + import 갱신)
- **현재 경로**: `com.econo.auth.gateway.config.GatewaySecurityConfig`
- **목표 경로**: `com.econo.auth.gateway.config.security.GatewaySecurityConfig`
- **갱신 내용**:
  - package 선언: `config` → `config.security`
  - import `com.econo.auth.gateway.security.JwtVerifier` → `com.econo.auth.gateway.config.security.JwtVerifier` (동일 패키지이므로 import 불필요)
  - `@Bean public JwtVerifier jwtVerifier()` — 동일 패키지이므로 참조 유지
- **스캔 영향**: `@Configuration`이므로 `ApiGatewayApplication`의 기본 스캔(`com.econo.auth.gateway`) 하위 `config.security`는 자동 인식. 변경 불필요.
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewaySecurityConfig.java:1`
- **연관 todo**: `[ ] GatewaySecurityConfig → config/security/GatewaySecurityConfig`

##### `JwtVerifier`, `PassportBuilder` (이동)
- **현재 경로**: `com.econo.auth.gateway.security.JwtVerifier`, `…PassportBuilder`
- **목표 경로**: `com.econo.auth.gateway.config.security.JwtVerifier`, `…PassportBuilder`
- **갱신 내용**: package 선언 변경. 상호 import는 동일 패키지이므로 제거.
- **연관 todo**: `[ ] security/JwtVerifier, security/PassportBuilder → config/security/`

##### `BearerToPassportFilter` (이동 + import 갱신)
- **현재 경로**: `com.econo.auth.gateway.filter.BearerToPassportFilter`
- **목표 경로**: `com.econo.auth.gateway.config.security.BearerToPassportFilter`
- **갱신 내용**:
  - package 선언: `filter` → `config.security`
  - import `gateway.security.JwtVerifier` → 동일 패키지이므로 제거
  - import `gateway.security.PassportBuilder` → 동일 패키지이므로 제거
  - import `gateway.config.GatewayRoutingConfig` → **변경 없음** (GatewayRoutingConfig는 `config/`에 잔류)
- **참조할 기존 코드**: `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/BearerToPassportFilter.java:1`
- **연관 todo**: `[ ] filter/BearerToPassportFilter → config/security/BearerToPassportFilter`

---

#### 모듈: `services/libs/common-infra`

- **변경 없음**. `CommonInfraAutoConfiguration`은 `@EnableJpaAuditing` 단일 책임으로 계층 구조 적용 불필요.
- `AutoConfiguration.imports` FQCN(`com.econo.auth.commoninfra.config.CommonInfraAutoConfiguration`) 불변.
- **연관 todo**: `[ ] common-infra 변경 불필요 확인`

---

### 현재 → 목표 패키지 클래스 매핑 전체표

#### member 모듈

| 현재 FQCN | 목표 FQCN | 종류 |
|---|---|---|
| `…member.application.port.in.SignupUseCase` | `…member.application.usecase.SignupUseCase` | 이동 |
| _(없음)_ | `…member.application.usecase.MemberQueryUseCase` | **신설** |
| `…member.application.usecase.SignupService` | `…member.application.service.SignupService` | 이동 |
| _(없음)_ | `…member.application.service.MemberQueryService` | **신설** |
| `…member.application.port.out.MemberRepository` | `…member.application.repository.MemberRepository` | 이동 |
| `…member.application.port.out.PasswordHasher` | `…member.application.repository.PasswordHasher` | 이동 |
| `…member.domain.Member` | `…member.application.domain.Member` | 이동 |
| `…member.domain.MemberStatus` | `…member.application.domain.MemberStatus` | 이동 |
| `…member.adapter.out.persistence.MemberJpaEntity` | `…member.persistence.entity.MemberJpaEntity` | 이동 |
| `…member.adapter.out.persistence.MemberJpaRepository` | `…member.persistence.repository.MemberJpaRepository` | 이동 |
| `…member.adapter.out.persistence.MemberRepositoryAdapter` | `…member.persistence.repository.MemberRepositoryAdapter` | 이동 |
| `…member.adapter.out.security.BCryptPasswordHasherAdapter` | `…member.persistence.repository.BCryptPasswordHasherAdapter` | 이동 |
| `…member.config.MemberAutoConfiguration` | **불변** | 어노테이션 값만 갱신 |

#### service-client 모듈

| 현재 FQCN | 목표 FQCN | 종류 |
|---|---|---|
| _(없음)_ | `…client.application.usecase.RegisterOAuthClientUseCase` | **신설** |
| _(없음)_ | `…client.application.usecase.ClientRedirectUriUseCase` | **신설** |
| `…client.application.usecase.RegisterOAuthClientService` | `…client.application.service.RegisterOAuthClientService` | 이동 |
| `…client.application.usecase.ClientRedirectUriService` | `…client.application.service.ClientRedirectUriService` | 이동 |
| `…client.application.port.out.ServiceClientRepository` | `…client.application.repository.ServiceClientRepository` | 이동 |
| `…client.application.port.out.SasClientRegistrar` | `…client.application.repository.SasClientRegistrar` | 이동 |
| `…client.application.port.out.SasRedirectUriManager` | `…client.application.repository.SasRedirectUriManager` | 이동 |
| `…client.domain.ServiceClient` | `…client.application.domain.ServiceClient` | 이동 |
| `…client.domain.GrantType` | `…client.application.domain.GrantType` | 이동 |
| `…client.adapter.out.persistence.ServiceClientJpaEntity` | `…client.persistence.entity.ServiceClientJpaEntity` | 이동 |
| `…client.adapter.out.persistence.ServiceClientJpaRepository` | `…client.persistence.repository.ServiceClientJpaRepository` | 이동 |
| `…client.adapter.out.persistence.ServiceClientRepositoryAdapter` | `…client.persistence.repository.ServiceClientRepositoryAdapter` | 이동 |
| `…client.adapter.out.sas.SasClientRegistrarAdapter` | `…client.persistence.repository.SasClientRegistrarAdapter` | 이동 |
| `…client.adapter.out.sas.SasRedirectUriManagerAdapter` | `…client.persistence.repository.SasRedirectUriManagerAdapter` | 이동 |
| `…client.config.ServiceClientAutoConfiguration` | **불변** | 어노테이션 값만 갱신 |

#### auth-api 모듈

| 현재 FQCN | 목표 FQCN | 종류 |
|---|---|---|
| `…api.adapter.in.web.AdminClientController` | `…api.presentation.controller.AdminClientController` | 이동 + DIP 전환 |
| `…api.adapter.in.web.AdminMemberController` | `…api.presentation.controller.AdminMemberController` | 이동 + DIP 전환 |
| `…api.adapter.in.web.AdminRoleController` | `…api.presentation.controller.AdminRoleController` | 이동 + DIP 전환 |
| `…api.adapter.in.web.ClientController` | `…api.presentation.controller.ClientController` | 이동 + DIP 전환 |
| `…api.adapter.in.web.JwksController` | `…api.presentation.controller.JwksController` | 이동 |
| `…api.adapter.in.web.MemberController` | `…api.presentation.controller.MemberController` | 이동 |
| `…api.adapter.in.web.MemberInfoController` | `…api.presentation.controller.MemberInfoController` | 이동 + DIP 전환 |
| `…api.adapter.in.web.ReissueController` | `…api.presentation.controller.ReissueController` | 이동 + DIP 전환 |
| `…api.adapter.in.web.RootController` | `…api.presentation.controller.RootController` | 이동 |
| `…api.adapter.in.web.LoginResponse` | `…api.presentation.dto.LoginResponse` | 이동 |
| `…api.adapter.in.web.SignupRequest` | `…api.presentation.dto.SignupRequest` | 이동 |
| `…api.adapter.in.web.TokenCookieManager` | `…api.presentation.util.TokenCookieManager` | 이동 |
| `…api.filter.JsonLoginAuthenticationFilter` | `…api.config.security.JsonLoginAuthenticationFilter` | 이동 + DIP 전환 |
| `…api.security.MemberUserDetails` | `…api.config.security.MemberUserDetails` | 이동 |
| `…api.security.MemberUserDetailsService` | `…api.config.security.MemberUserDetailsService` | 이동 + DIP 전환 |
| `…api.config.SecurityConfig` | `…api.config.security.SecurityConfig` | 이동 + DIP 전환 |
| `…api.application.LoginTokenService` | `…api.application.service.LoginTokenService` | 이동 |
| `…api.application.LoginRedirectResolver` | `…api.application.service.LoginRedirectResolver` | 이동 |
| _(없음)_ | `…api.application.usecase.LoginTokenUseCase` | **신설** |
| _(없음)_ | `…api.application.usecase.LoginRedirectUseCase` | **신설** |

#### api-gateway 모듈

| 현재 FQCN | 목표 FQCN | 종류 |
|---|---|---|
| `…gateway.web.RootController` | `…gateway.presentation.controller.RootController` | 이동 |
| `…gateway.filter.BearerToPassportFilter` | `…gateway.config.security.BearerToPassportFilter` | 이동 + import 갱신 |
| `…gateway.security.JwtVerifier` | `…gateway.config.security.JwtVerifier` | 이동 |
| `…gateway.security.PassportBuilder` | `…gateway.config.security.PassportBuilder` | 이동 |
| `…gateway.config.GatewaySecurityConfig` | `…gateway.config.security.GatewaySecurityConfig` | 이동 + import 갱신 |

---

### 신설 usecase / repository 인터페이스 목록

엄격 DIP 적용으로 신설하는 인터페이스 전체:

| 인터페이스 FQCN | 구현 클래스 FQCN | 신설 이유 |
|---|---|---|
| `…member.application.usecase.MemberQueryUseCase` | `…member.application.service.MemberQueryService` | `AdminMemberController`, `MemberInfoController`, `AdminRoleController`, `MemberUserDetailsService`(config/security)의 presentation/보안→repository 직접 의존 제거 |
| `…client.application.usecase.RegisterOAuthClientUseCase` | `…client.application.service.RegisterOAuthClientService` | `AdminClientController`, `ClientController`의 presentation→service구현체 직접 의존 제거 |
| `…client.application.usecase.ClientRedirectUriUseCase` | `…client.application.service.ClientRedirectUriService` | `AdminClientController`의 presentation→service구현체 직접 의존 제거 + `LoginRedirectResolver`의 service→service 직접 의존 제거 |
| `…api.application.usecase.LoginTokenUseCase` | `…api.application.service.LoginTokenService` | `ReissueController`, `JsonLoginAuthenticationFilter`(config/security), `SecurityConfig`(config/security)의 presentation/보안→service구현체 직접 의존 제거 |
| `…api.application.usecase.LoginRedirectUseCase` | `…api.application.service.LoginRedirectResolver` | `JsonLoginAuthenticationFilter`(config/security), `SecurityConfig`(config/security)의 presentation/보안→service구현체 직접 의존 제거 |

---

### 호출 흐름

#### 흐름 A: 회원 가입 (auth-api → member)

정상 경로:
```
MemberController (presentation.controller)
  → SignupUseCase (member.application.usecase)         ← 인터페이스
    → SignupService (member.application.service)       — 구현체
      → MemberRepository (member.application.repository)  ← 인터페이스
        → MemberRepositoryAdapter (member.persistence.repository)
          → MemberJpaRepository (member.persistence.repository)
      → PasswordHasher (member.application.repository) ← 인터페이스
        → BCryptPasswordHasherAdapter (member.persistence.repository)
```

예외 / 실패 경로:
```
SignupService.signup()
  → loginId 형식 위반          → IllegalArgumentException → GlobalExceptionHandler → 400
  → 중복 loginId               → MemberAlreadyExistsException → GlobalExceptionHandler → 409
  → 비밀번호 정책 위반          → InvalidPasswordPolicyException → GlobalExceptionHandler → 400
  → DB 오류                    → DataAccessException → GlobalExceptionHandler → 500
```

#### 흐름 B: JSON 로그인 (auth-api config/security 내부 + application)

정상 경로:
```
JsonLoginAuthenticationFilter (config.security)
  → DaoAuthenticationProvider
    → MemberUserDetailsService (config.security)
      → MemberQueryUseCase (member.application.usecase)   ← 인터페이스
        → MemberQueryService (member.application.service)
          → MemberRepository (member.application.repository) ← 인터페이스
            → MemberRepositoryAdapter (member.persistence.repository)
  → 인증 성공 → LoginTokenUseCase (api.application.usecase)   ← 인터페이스
    → LoginTokenService (api.application.service) → AT+RT 발급
  → LoginRedirectUseCase (api.application.usecase)            ← 인터페이스
    → LoginRedirectResolver (api.application.service)
      → ClientRedirectUriUseCase (client.application.usecase) ← 인터페이스
        → ClientRedirectUriService (client.application.service)
          → SasRedirectUriManager (client.application.repository) ← 인터페이스
            → SasRedirectUriManagerAdapter (client.persistence.repository)
  → WEB: Set-Cookie + 302 / APP: 200 OK + body
```

예외 / 실패 경로:
```
MemberUserDetailsService.loadUserByUsername()
  → MemberQueryUseCase.findByLoginId() → 없으면 UsernameNotFoundException
DaoAuthenticationProvider 비밀번호 불일치
  → BadCredentialsException
    → JsonLoginAuthenticationFilter.unsuccessfulAuthentication() → 401
LoginRedirectResolver.resolve()
  → InvalidClientException → defaultUrl fallback
  → 그 외 RuntimeException → defaultUrl fallback (fail-safe)
```

#### 흐름 C: OAuth 클라이언트 등록 (auth-api → service-client)

정상 경로:
```
AdminClientController / ClientController (presentation.controller)
  → RegisterOAuthClientUseCase (client.application.usecase)    ← 인터페이스
    → RegisterOAuthClientService (client.application.service)
      → ServiceClientRepository (client.application.repository) ← 인터페이스
        → ServiceClientRepositoryAdapter (client.persistence.repository)
      → SasClientRegistrar (client.application.repository)      ← 인터페이스
        → SasClientRegistrarAdapter (client.persistence.repository)
          → RegisteredClientRepository (SAS 인프라)
```

예외 / 실패 경로:
```
RegisterOAuthClientService.selfRegister()
  → ClientLimitExceededException (5개 초과) → GlobalExceptionHandler → 422
  → DuplicateClientNameException            → GlobalExceptionHandler → 409
  → RedirectUriRequiredException            → GlobalExceptionHandler → 400
```

#### 흐름 D: Bearer → Passport 변환 (api-gateway config/security)

정상 경로:
```
BearerToPassportFilter (config.security) — GlobalFilter
  → JwtVerifier (config.security)    — RS256 검증
  → PassportBuilder (config.security) — 클레임 → Passport → Base64
  → GatewayRoutingConfig (config)     — permittedPaths 참조 (변경 없음)
  → X-User-Passport 헤더 주입 → Downstream
```

예외 / 실패 경로:
```
JwtVerifier 검증 실패 (서명·만료·iss)
  → BearerToPassportFilter → 401 응답 (라우팅 차단)
Bearer 헤더 없음 (허용 경로)
  → BearerToPassportFilter → 헤더 주입 없이 통과 (permittedPaths 처리)
```

#### 흐름 E: 회원 목록 조회 (AdminMemberController — 엄격 DIP 반영)

정상 경로:
```
AdminMemberController (presentation.controller)
  → MemberQueryUseCase (member.application.usecase)    ← 인터페이스
    → MemberQueryService (member.application.service)  — 구현체 (위임)
      → MemberRepository (member.application.repository) ← 인터페이스
        → MemberRepositoryAdapter (member.persistence.repository)
```

예외 / 실패 경로:
```
MemberQueryUseCase.findById() → Optional.empty() → AdminMemberController에서 404 직접 응답
MemberQueryUseCase.countByRole() → DB 오류 → DataAccessException → GlobalExceptionHandler → 500
```

---

### AutoConfiguration.imports 및 스캔 갱신 상세

| 파일 | 갱신 여부 | 갱신 내용 |
|---|---|---|
| `member/.../AutoConfiguration.imports` | **불필요** | FQCN `com.econo.auth.member.config.MemberAutoConfiguration` 불변 |
| `service-client/.../AutoConfiguration.imports` | **불필요** | FQCN `com.econo.auth.client.config.ServiceClientAutoConfiguration` 불변 |
| `common-infra/.../AutoConfiguration.imports` | **불필요** | FQCN `com.econo.auth.commoninfra.config.CommonInfraAutoConfiguration` 불변 |
| `MemberAutoConfiguration` 본문 | **필요** | `@EnableJpaRepositories("…adapter.out.persistence")` → `"…persistence.repository"`, `@EntityScan("…adapter.out.persistence")` → `"…persistence.entity"` |
| `ServiceClientAutoConfiguration` 본문 | **필요** | `@EnableJpaRepositories("…adapter.out.persistence")` → `"…persistence.repository"`, `@EntityScan("…adapter.out.persistence")` → `"…persistence.entity"` |

#### @ComponentScan 스캔 범위 확인

- `MemberAutoConfiguration`: `@ComponentScan("com.econo.auth.member")` — `persistence.*` + `application.*` 모두 하위. **변경 불필요.**
- `ServiceClientAutoConfiguration`: `@ComponentScan("com.econo.auth.client")` — 동일. **변경 불필요.**
- auth-api: Spring Boot Application 기준 컴포넌트 스캔. `presentation.*`, `application.*`, `config.*`, `config.security.*` 모두 하위. **변경 불필요.**
- api-gateway: 동일. `config.security.*`도 `com.econo.auth.gateway` 하위. **변경 불필요.**

#### SecurityConfig/GatewaySecurityConfig 이동이 스캔에 미치는 영향

- **auth-api `SecurityConfig`**: `com.econo.auth.api.config.SecurityConfig` → `com.econo.auth.api.config.security.SecurityConfig`. `AuthApiApplication`의 기본 스캔은 `com.econo.auth.api` 루트 기준이므로 `config.security` 서브패키지 포함. `@Configuration`으로 자동 인식. `@Import` / `@ComponentScan`에서 `SecurityConfig`를 FQCN으로 명시한 곳이 있다면 갱신 필요. 현재 코드에서 `@Import(SecurityConfig.class)` 패턴은 없음 — **영향 없음**.
- **api-gateway `GatewaySecurityConfig`**: `com.econo.auth.gateway.config.GatewaySecurityConfig` → `com.econo.auth.gateway.config.security.GatewaySecurityConfig`. 동일 이유로 `@Configuration` 자동 인식. **영향 없음**.

---

### 테스트 파일 import 갱신 목록

#### member 테스트

| 테스트 파일 | 갱신 대상 import | 파일 이동 여부 |
|---|---|---|
| `application/usecase/SignupServiceTest.java` | `port.in.SignupUseCase.SignupCommand` → `usecase.SignupUseCase.SignupCommand`; `port.out.MemberRepository` → `repository.MemberRepository`; `port.out.PasswordHasher` → `repository.PasswordHasher`; `usecase.SignupService` → `service.SignupService` | 이동: `application/service/SignupServiceTest.java` |
| `adapter/out/persistence/MemberRepositoryAdapterTest.java` | `adapter.out.persistence.*` → `persistence.repository.*`; `adapter.out.persistence.MemberJpaEntity` → `persistence.entity.MemberJpaEntity` | 이동: `persistence/repository/MemberRepositoryAdapterTest.java` |
| `adapter/out/security/BCryptPasswordHasherAdapterTest.java` | `adapter.out.security.BCryptPasswordHasherAdapter` → `persistence.repository.BCryptPasswordHasherAdapter`; `port.out.PasswordHasher` → `repository.PasswordHasher` | 이동: `persistence/repository/BCryptPasswordHasherAdapterTest.java` |
| `domain/MemberTest.java` | `domain.Member` → `application.domain.Member`; `domain.MemberStatus` → `application.domain.MemberStatus` | 이동: `application/domain/MemberTest.java` |

#### service-client 테스트

| 테스트 파일 | 갱신 대상 import | 파일 이동 여부 |
|---|---|---|
| `application/usecase/RegisterOAuthClientServiceTest.java` | `port.out.SasClientRegistrar` → `repository.SasClientRegistrar`; `port.out.ServiceClientRepository` → `repository.ServiceClientRepository`; `usecase.RegisterOAuthClientService` → `service.RegisterOAuthClientService`; `domain.*` → `application.domain.*` | 이동: `application/service/RegisterOAuthClientServiceTest.java` |
| `application/usecase/ClientRedirectUriServiceTest.java` | `port.out.SasRedirectUriManager` → `repository.SasRedirectUriManager`; `port.out.ServiceClientRepository` → `repository.ServiceClientRepository`; `usecase.ClientRedirectUriService` → `service.ClientRedirectUriService` | 이동: `application/service/ClientRedirectUriServiceTest.java` |

#### auth-api 테스트

| 테스트 파일 | 갱신 대상 import | 파일 이동 여부 |
|---|---|---|
| `adapter/in/web/AdminMemberControllerTest.java` | `member.application.port.out.MemberRepository` → `member.application.usecase.MemberQueryUseCase`; `adapter.in.web.AdminMemberController` → `presentation.controller.AdminMemberController`; `@MockBean MemberRepository` → `@MockBean MemberQueryUseCase` | 이동: `presentation/controller/AdminMemberControllerTest.java` |
| `adapter/in/web/MemberControllerTest.java` | `member.application.port.in.SignupUseCase` → `member.application.usecase.SignupUseCase`; `adapter.in.web.MemberController` → `presentation.controller.MemberController` | 이동: `presentation/controller/MemberControllerTest.java` |
| `adapter/in/web/AdminClientControllerTest.java` | `client.application.usecase.RegisterOAuthClientService` → `client.application.usecase.RegisterOAuthClientUseCase`; `client.application.usecase.ClientRedirectUriService` → `client.application.usecase.ClientRedirectUriUseCase`; 컨트롤러 경로 갱신 | 이동: `presentation/controller/AdminClientControllerTest.java` |
| `adapter/in/web/AdminRoleControllerTest.java` | `adapter.in.web.AdminRoleController` → `presentation.controller.AdminRoleController`; `MemberRepository` → `MemberQueryUseCase` | 이동: `presentation/controller/AdminRoleControllerTest.java` |
| `adapter/in/web/ClientControllerTest.java` | 컨트롤러 경로 갱신; `RegisterOAuthClientService` → `RegisterOAuthClientUseCase` | 이동: `presentation/controller/ClientControllerTest.java` |
| `adapter/in/web/JwksControllerTest.java` | 컨트롤러 경로 갱신 | 이동: `presentation/controller/JwksControllerTest.java` |
| `adapter/in/web/LoginResponseTest.java` | `adapter.in.web.LoginResponse` → `presentation.dto.LoginResponse` | 이동: `presentation/dto/LoginResponseTest.java` |
| `application/LoginTokenServiceTest.java` | `member.application.port.out.MemberRepository` → `member.application.repository.MemberRepository`; `api.application.LoginTokenService` → `api.application.service.LoginTokenService` | 이동: `application/service/LoginTokenServiceTest.java` |
| `application/LoginRedirectResolverTest.java` | `client.application.usecase.ClientRedirectUriService` → `client.application.usecase.ClientRedirectUriUseCase`; `api.application.LoginRedirectResolver` → `api.application.service.LoginRedirectResolver` | 이동: `application/service/LoginRedirectResolverTest.java` |
| `integration/AuthApiIntegrationTest.java` | 컨트롤러·DTO·서비스 import 전체 갱신 | 위치 유지 (integration은 패키지 미러링 강제 아님) |

#### api-gateway 테스트

| 테스트 파일 | 갱신 대상 import | 파일 이동 여부 |
|---|---|---|
| `filter/BearerToPassportFilterTest.java` | `gateway.filter.BearerToPassportFilter` → `gateway.config.security.BearerToPassportFilter`; `gateway.security.*` → `gateway.config.security.*` | 이동: `config/security/BearerToPassportFilterTest.java` |
| `security/JwtVerifierTest.java` | `gateway.security.JwtVerifier` → `gateway.config.security.JwtVerifier` | 이동: `config/security/JwtVerifierTest.java` |
| `security/PassportBuilderTest.java` | `gateway.security.PassportBuilder` → `gateway.config.security.PassportBuilder` | 이동: `config/security/PassportBuilderTest.java` |

---

### 단계별 이동 · 검증 순서

리프 라이브러리 → 상위 앱 순서로 진행. 단계별 컴파일 체크포인트 수행.

#### Phase 1: `member` 모듈 리팩토링

1. 신규 패키지 디렉터리 생성: `application/usecase/`, `application/service/`, `application/repository/`, `application/domain/`, `persistence/entity/`, `persistence/repository/`
2. 파일 이동 (package 선언 + import 갱신):
   - `domain/Member`, `domain/MemberStatus` → `application/domain/`
   - `application/port/out/MemberRepository` → `application/repository/MemberRepository`
   - `application/port/out/PasswordHasher` → `application/repository/PasswordHasher`
   - `application/port/in/SignupUseCase` → `application/usecase/SignupUseCase` (내부 import: `domain.*` → `application.domain.*`)
   - `application/usecase/SignupService` → `application/service/SignupService` (내부 import: `port.in` → `application.usecase`, `port.out` → `application.repository`, `domain.*` → `application.domain.*`)
   - `adapter/out/persistence/MemberJpaEntity` → `persistence/entity/MemberJpaEntity` (import: `domain.*` → `application.domain.*`)
   - `adapter/out/persistence/MemberJpaRepository` → `persistence/repository/MemberJpaRepository`
   - `adapter/out/persistence/MemberRepositoryAdapter` → `persistence/repository/MemberRepositoryAdapter` (import: `port.out.MemberRepository` → `application.repository.MemberRepository`, `domain.*` → `application.domain.*`)
   - `adapter/out/security/BCryptPasswordHasherAdapter` → `persistence/repository/BCryptPasswordHasherAdapter` (import: `port.out.PasswordHasher` → `application.repository.PasswordHasher`)
3. `MemberQueryUseCase` 인터페이스 신설 (`application/usecase/`)
4. `MemberQueryService` 구현체 신설 (`application/service/`) — `MemberRepository`에 위임
5. `MemberAutoConfiguration`: `@EnableJpaRepositories` / `@EntityScan` 값 갱신
6. **컴파일 체크**: `./gradlew :services:libs:member:compileJava`
7. 테스트 파일 이동 + import 갱신
8. **테스트 체크**: `./gradlew :services:libs:member:test`
9. 구 패키지 디렉터리 삭제: `application/port/`, `adapter/`, `domain/`

#### Phase 2: `service-client` 모듈 리팩토링

1. 신규 패키지 디렉터리 생성: `application/usecase/`, `application/service/`, `application/repository/`, `application/domain/`, `persistence/entity/`, `persistence/repository/`
2. 파일 이동 (package 선언 + import 갱신):
   - `domain/ServiceClient`, `domain/GrantType` → `application/domain/`
   - `application/port/out/*` → `application/repository/*` (3종)
   - `application/usecase/RegisterOAuthClientService` → `application/service/RegisterOAuthClientService` (내부 import: `port.out.*` → `application.repository.*`, `domain.*` → `application.domain.*`)
   - `application/usecase/ClientRedirectUriService` → `application/service/ClientRedirectUriService` (동일)
   - `adapter/out/persistence/ServiceClientJpaEntity` → `persistence/entity/ServiceClientJpaEntity` (import: `domain.*` → `application.domain.*`)
   - `adapter/out/persistence/ServiceClientJpaRepository` → `persistence/repository/`
   - `adapter/out/persistence/ServiceClientRepositoryAdapter` → `persistence/repository/` (import: `port.out.*` → `application.repository.*`, `domain.*` → `application.domain.*`)
   - `adapter/out/sas/SasClientRegistrarAdapter` → `persistence/repository/` (import: `port.out.*` → `application.repository.*`)
   - `adapter/out/sas/SasRedirectUriManagerAdapter` → `persistence/repository/` (import: `port.out.*` → `application.repository.*`)
3. `RegisterOAuthClientUseCase`, `ClientRedirectUriUseCase` 인터페이스 신설 (`application/usecase/`)
   - `RegisterOAuthClientService`에 내포된 command/result record를 `RegisterOAuthClientUseCase`로 이동
   - `ClientRedirectUriService`에 내포된 `ClientInfo` record를 `ClientRedirectUriUseCase`로 이동
4. `ServiceClientAutoConfiguration`: `@EnableJpaRepositories` / `@EntityScan` 값 갱신
5. **컴파일 체크**: `./gradlew :services:libs:service-client:compileJava`
6. 테스트 파일 이동 + import 갱신
7. **테스트 체크**: `./gradlew :services:libs:service-client:test`
8. 구 패키지 디렉터리 삭제: `application/port/`, `adapter/`, `domain/`

#### Phase 3: `auth-api` 모듈 리팩토링

(Phase 1, 2 완료 후 진행 — auth-api가 member, service-client에 의존)

1. 신규 패키지 디렉터리 생성: `presentation/controller/`, `presentation/dto/`, `presentation/util/`, `config/security/`, `application/usecase/`, `application/service/`
2. `LoginTokenUseCase`, `LoginRedirectUseCase` 인터페이스 신설 (`application/usecase/`)
   - `LoginTokenService.TokenPair` record를 `LoginTokenUseCase`로 이동
3. `application/LoginTokenService` → `application/service/LoginTokenService` (import 갱신: `member.application.port.out.MemberRepository` → `member.application.repository.MemberRepository`, `member.domain.*` → `member.application.domain.*`; `implements LoginTokenUseCase` 선언 추가)
4. `application/LoginRedirectResolver` → `application/service/LoginRedirectResolver` (필드 타입 + import: `ClientRedirectUriService` → `ClientRedirectUriUseCase`; `implements LoginRedirectUseCase` 선언 추가)
5. `security/MemberUserDetails` → `config/security/MemberUserDetails` (import: `member.domain.Member` → `member.application.domain.Member`)
6. `security/MemberUserDetailsService` → `config/security/MemberUserDetailsService` (DIP 전환: `MemberRepository` → `MemberQueryUseCase`)
7. `filter/JsonLoginAuthenticationFilter` → `config/security/JsonLoginAuthenticationFilter` (DIP 전환: `LoginTokenService` → `LoginTokenUseCase`, `LoginRedirectResolver` → `LoginRedirectUseCase`; import: `LoginResponse` → `presentation.dto`, `TokenCookieManager` → `presentation.util`, `MemberUserDetails` → 동일 패키지 import 제거)
8. `config/SecurityConfig` → `config/security/SecurityConfig` (DIP 전환: `LoginTokenService` → `LoginTokenUseCase`, `LoginRedirectResolver` → `LoginRedirectUseCase`; import: `TokenCookieManager` → `presentation.util`, `JsonLoginAuthenticationFilter` → 동일 패키지 import 제거)
9. `adapter/in/web/` 전체 이동:
   - 컨트롤러 9종 → `presentation/controller/` (package + import 갱신)
   - DTO 2종(`LoginResponse`, `SignupRequest`) → `presentation/dto/`
   - `TokenCookieManager` → `presentation/util/`
   - 각 컨트롤러 내부 import 갱신:
     - `member.application.port.in.SignupUseCase` → `member.application.usecase.SignupUseCase`
     - `client.application.usecase.RegisterOAuthClientService` → `client.application.usecase.RegisterOAuthClientUseCase`
     - `client.application.usecase.ClientRedirectUriService` → `client.application.usecase.ClientRedirectUriUseCase`
     - `member.application.port.out.MemberRepository` → `member.application.usecase.MemberQueryUseCase` (DIP 전환 대상 컨트롤러)
     - `member.domain.*` → `member.application.domain.*`
     - `api.application.LoginTokenService` → `api.application.usecase.LoginTokenUseCase` (ReissueController)
     - `adapter.in.web.*DTO` → `presentation.dto.*`
10. `ApplicationServiceConfig` import + `@Bean` 파라미터 타입 갱신 (상세 목록: "구성 요소 설계 - ApplicationServiceConfig" 참고)
11. **컴파일 체크**: `./gradlew :services:apis:auth-api:compileJava`
12. 테스트 파일 이동 + import 갱신 (특히 `@MockBean MemberRepository` → `@MockBean MemberQueryUseCase`)
13. **테스트 체크**: `./gradlew :services:apis:auth-api:test`
14. 구 패키지 디렉터리 삭제: `adapter/`, `filter/`, `security/`, `application/LoginTokenService.java`, `application/LoginRedirectResolver.java`

#### Phase 4: `api-gateway` 모듈 리팩토링

(Phase 1~3와 독립 — api-gateway는 member/service-client에 의존하지 않음. 병렬 진행 가능)

1. 신규 패키지 디렉터리 생성: `presentation/controller/`, `config/security/`
2. 파일 이동:
   - `web/RootController` → `presentation/controller/RootController`
   - `security/JwtVerifier`, `security/PassportBuilder` → `config/security/` (package 선언 변경, 상호 import 정리)
   - `filter/BearerToPassportFilter` → `config/security/BearerToPassportFilter` (import: `gateway.security.JwtVerifier` → 동일 패키지 제거, `gateway.security.PassportBuilder` → 동일 패키지 제거; `gateway.config.GatewayRoutingConfig` import 유지)
   - `config/GatewaySecurityConfig` → `config/security/GatewaySecurityConfig` (package 선언 변경, `JwtVerifier` import 제거)
3. **컴파일 체크**: `./gradlew :services:apis:api-gateway:compileJava`
4. 테스트 파일 이동 + import 갱신 (테스트 파일 목록: "테스트 파일 import 갱신 목록 - api-gateway 테스트" 참고)
5. **테스트 체크**: `./gradlew :services:apis:api-gateway:test`
6. 구 패키지 디렉터리 삭제: `filter/`, `security/`, `web/`

#### Phase 5: 전체 통합 검증

1. **클린 빌드**: `./gradlew clean build`
2. **iCloud 충돌본 스캔**: `find . -name "* 2.java" -not -path "./.git/*"` — 발견 시 즉시 `rm`
3. **전체 테스트**: `./gradlew test` — 모든 기존 테스트 통과 확인
4. **포맷팅**: `./gradlew format` 후 `./gradlew spotlessCheck`
5. **repository 경계 누수 가드**:
   ```
   grep -rn "JpaEntity" services/libs/*/src/main/java services/apis/*/src/main/java \
     | grep -E "application|usecase|domain|presentation"
   ```
   결과가 **0건**이어야 한다 (JPA 엔티티가 repository 경계 밖으로 누수되지 않음). `application/repository/*` 출력 포트 인터페이스의 모든 메서드 시그니처에 `*JpaEntity` 타입이 없는지 확인.
6. **계층 순서 검증** (presentation이 service/repository 구현체를 직접 참조하지 않는지):
   ```
   grep -rn "application.service\|application.repository\|persistence" \
     services/apis/auth-api/src/main/java/com/econo/auth/api/presentation
   ```
   결과가 **0건**이어야 한다. `config/security` 패키지도 동일하게 확인:
   ```
   grep -rn "application.service\|application.repository\|persistence" \
     services/apis/auth-api/src/main/java/com/econo/auth/api/config/security
   ```
   결과가 **0건**이어야 한다.

---

### 컨벤션 준수 항목

- **네이밍 — 패키지**: 소문자만. `presentation`, `application`, `persistence`, `usecase`, `service`, `repository`, `domain`, `entity`, `controller`, `dto`, `util`, `config`, `security`, `exception`
- **네이밍 — 클래스**:
  - usecase 인터페이스: `{Action}UseCase` 또는 `{Resource}QueryUseCase` (SignupUseCase, MemberQueryUseCase, LoginTokenUseCase 등)
  - service 구현체: `{Action}Service` 또는 `{Action}Resolver` (SignupService, MemberQueryService, LoginRedirectResolver)
  - repository 인터페이스(출력 포트): `{Resource}Repository` (MemberRepository, ServiceClientRepository)
  - JPA 어댑터: `{Name}JpaEntity` / `{Name}JpaRepository` / `{Name}RepositoryAdapter`
  - 보안 어댑터: `BCryptPasswordHasherAdapter` (기존 접미사 유지)
- **계층 의존 순서 (불변식 1)**: 참조는 항상 `presentation → application → persistence` 한 방향. `config/security`도 `application.usecase` 인터페이스에만 의존. `repository`/`persistence` 직접 참조 금지.
- **repository 경계 = 도메인 전용 (불변식 2)**: `application/repository` 출력 포트 인터페이스의 모든 메서드 시그니처는 도메인 객체만 사용. `*JpaEntity`는 포트 경계를 넘지 않음. entity↔domain 변환은 `persistence/repository`의 어댑터 구현체 책임.
- **config/security 보안 어댑터 배치**: Spring Security에 결합된 클래스(`@EnableWebSecurity`, `@EnableWebFluxSecurity`, `UserDetailsService`, `AbstractAuthenticationProcessingFilter`, `GlobalFilter`)는 `config/security` 서브패키지에 배치. presentation/filter 패키지는 남기지 않는다.
- **의존성 주입**: `@RequiredArgsConstructor` 우선 사용 유지. `SignupService`, `LoginRedirectResolver`는 수동 `@Bean` 등록 방식 유지. `LoginTokenService`는 `@Service`로 컴포넌트 스캔.
- **불변성**: 도메인 객체 `private final` 필드, `List.copyOf()` 방어적 복사 — 이번 작업에서 변경 없음
- **예외 처리**: 정적 팩토리 메서드 패턴 유지. 이동 후에도 동일 패턴 유지
- **테스트 위치**: src 구조 미러링 원칙. 프로덕션 이동 경로에 맞게 테스트도 동일 경로로 이동
- **iCloud Drive 환경**: 대량 파일 이동 후 반드시 `./gradlew clean build` + `find . -name "* 2.java"` 스캔

---

## 체크리스트

- [x] todo의 모든 구현 작업이 구성 요소로 매핑됨
- [x] 모든 구성 요소가 적절한 모듈/계층에 배치됨
- [x] 모든 구성 요소가 적용 컨벤션을 명시함
- [x] 호출 흐름에 빠진 분기가 없음 (정상/예외 모두)
- [x] 라이브 소스 전수 점검 완료 (전체 80개 .java 파일 반영)
- [x] 엄격 DIP 대상 전수 식별 (AdminMemberController, MemberInfoController, AdminRoleController, MemberUserDetailsService, AdminClientController, ClientController, ReissueController, JsonLoginAuthenticationFilter, SecurityConfig)
- [x] 신설 인터페이스 5종 전부 매핑표에 반영
- [x] @EntityScan / @EnableJpaRepositories 갱신 경로 확정 (persistence.entity / persistence.repository)
- [x] AutoConfiguration.imports FQCN 불변 확인
- [x] config/security 배치 확정 (auth-api: SecurityConfig·MemberUserDetailsService·MemberUserDetails·JsonLoginAuthenticationFilter; api-gateway: GatewaySecurityConfig·JwtVerifier·PassportBuilder·BearerToPassportFilter)
- [x] presentation/filter 패키지 제거 확인 (auth-api, api-gateway 모두)
- [x] SecurityConfig DIP 전환 명시 (LoginTokenService→LoginTokenUseCase, LoginRedirectResolver→LoginRedirectUseCase)
- [x] BearerToPassportFilter의 GatewayRoutingConfig 의존 처리 확인 (config/ 잔류 → import 변경 없음)
- [x] GatewaySecurityConfig 이동 경로 반영 (config/ → config/security/)

---

## 참고

- `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java` — @EnableJpaRepositories / @EntityScan 갱신 대상
- `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java` — 동일
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — import + 파라미터 타입 갱신 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — config/security 이동 + DIP 전환 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` — config/security 이동 + DIP 전환 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetailsService.java` — config/security 이동 + DIP 전환 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/LoginTokenService.java` — application/service 이동 + LoginTokenUseCase 구현 선언
- `services/apis/auth-api/src/main/java/com/econo/auth/api/application/LoginRedirectResolver.java` — application/service 이동 + LoginRedirectUseCase 구현 선언
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewaySecurityConfig.java` — config/security 이동
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/BearerToPassportFilter.java` — config/security 이동 + GatewayRoutingConfig import 유지 확인
- `services/libs/member/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `services/libs/service-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `services/libs/common-infra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
