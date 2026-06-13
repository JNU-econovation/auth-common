# layered-architecture - todo

## 메타
- **작업명**: layered-architecture
- **문서 타입**: todo
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - implementation-plan.md (패키지 이동 상세 매핑 및 단계별 검증 순서)

---

## 개요

전체 5개 모듈(services/apis/auth-api, services/apis/api-gateway, services/libs/member, services/libs/service-client, services/libs/common-infra)의 패키지 구조를 **presentation / application(usecase+service) / persistence(entity+repository) 3-layer + 계층별 DIP** 구조로 통일하는 순수 리팩토링이다.

현재 libs(member, service-client)는 헥사고날(adapter/in·out, application/port/in·out) 어휘를 사용하고, auth-api는 adapter.in.web + filter + security + application이 혼재해 있다. 이를 presentation / application / persistence 3계층 + config / exception 공통으로 재편하여 모듈 간 어휘·의존성 규칙을 통일한다. 출력 포트 인터페이스는 application.repository 패키지에, JPA 엔티티·구현체는 persistence.entity / persistence.repository 패키지에 배치한다. 보안 관련 클래스(SecurityConfig, UserDetailsService, Filter, JwtVerifier 등)는 config/security 서브패키지에 배치하며, presentation/filter 패키지는 남기지 않는다. 동작(엔드포인트, 요청/응답, DB 스키마)은 변경하지 않는다.

---

## 본문

### API 작업

- 해당 없음 — 이 작업은 순수 패키지 리팩토링이며, 엔드포인트 경로·요청·응답·HTTP 상태 코드는 일체 변경하지 않는다.

---

### 구현 작업

#### [member 모듈] 헥사고날 → 3-layer 재명명 + DIP 신설

- [ ] `domain/Member`, `domain/MemberStatus` → `application/domain/Member`, `application/domain/MemberStatus` 로 이동 (package 선언 변경, 내부 로직 변경 없음)
- [ ] `application/port/in/SignupUseCase` → `application/usecase/SignupUseCase` 로 이동 (내부 import: `domain.*` → `application.domain.*`)
- [ ] `MemberQueryUseCase` 인터페이스 신설 (`application/usecase/MemberQueryUseCase`) — 엄격 DIP; `AdminMemberController`, `MemberInfoController`, `AdminRoleController`, `MemberUserDetailsService`(config/security)의 presentation/보안→repository 직접 의존을 차단하는 seam. 메서드: `findByLoginId`, `findById`, `findAllByIds`, `findPaged`, `count`, `countByRole`, `updateRole`
- [ ] `application/port/out/MemberRepository` → `application/repository/MemberRepository` 로 이동
- [ ] `application/port/out/PasswordHasher` → `application/repository/PasswordHasher` 로 이동
- [ ] `application/usecase/SignupService` → `application/service/SignupService` 로 이동 (내부 import: `port.in.*` → `application.usecase.*`, `port.out.*` → `application.repository.*`, `domain.*` → `application.domain.*`)
- [ ] `MemberQueryService` 구현체 신설 (`application/service/MemberQueryService`) — `MemberQueryUseCase` 구현; `MemberRepository` 포트에 단순 위임; `@Service`, `@RequiredArgsConstructor`, `@Transactional(readOnly = true)`
- [ ] `adapter/out/persistence/MemberJpaEntity` → `persistence/entity/MemberJpaEntity` 로 이동 (import: `domain.*` → `application.domain.*`)
- [ ] `adapter/out/persistence/MemberJpaRepository` → `persistence/repository/MemberJpaRepository` 로 이동
- [ ] `adapter/out/persistence/MemberRepositoryAdapter` → `persistence/repository/MemberRepositoryAdapter` 로 이동 (import: `port.out.MemberRepository` → `application.repository.MemberRepository`, `domain.*` → `application.domain.*`)
- [ ] `adapter/out/security/BCryptPasswordHasherAdapter` → `persistence/repository/BCryptPasswordHasherAdapter` 로 이동 (import: `port.out.PasswordHasher` → `application.repository.PasswordHasher`)
- [ ] `MemberAutoConfiguration` 내 `@EnableJpaRepositories("…adapter.out.persistence")` → `"…persistence.repository"` 로 갱신
- [ ] `MemberAutoConfiguration` 내 `@EntityScan("…adapter.out.persistence")` → `"…persistence.entity"` 로 갱신
- [ ] member 모듈 테스트 파일 이동 + import 갱신 (`SignupServiceTest`, `MemberRepositoryAdapterTest`, `BCryptPasswordHasherAdapterTest`, `MemberTest`) — implementation-plan.md 테스트 파일 이동 목록 참조
- [ ] 구 패키지 디렉터리 삭제: `application/port/`, `adapter/`, `domain/`

#### [service-client 모듈] 헥사고날 → 3-layer 재명명 + DIP 신설

- [ ] `domain/ServiceClient`, `domain/GrantType` → `application/domain/ServiceClient`, `application/domain/GrantType` 로 이동
- [ ] `application/port/out/ServiceClientRepository` → `application/repository/ServiceClientRepository` 로 이동
- [ ] `application/port/out/SasClientRegistrar` → `application/repository/SasClientRegistrar` 로 이동
- [ ] `application/port/out/SasRedirectUriManager` → `application/repository/SasRedirectUriManager` 로 이동
- [ ] `RegisterOAuthClientUseCase` 인터페이스 신설 (`application/usecase/RegisterOAuthClientUseCase`) — 엄격 DIP; `AdminClientController`, `ClientController`의 service 구현체 직접 의존 차단. `RegisterOAuthClientService`에 내포된 command/result record도 이 인터페이스로 이동.
- [ ] `ClientRedirectUriUseCase` 인터페이스 신설 (`application/usecase/ClientRedirectUriUseCase`) — 엄격 DIP; `AdminClientController`의 service 구현체 직접 의존 차단. `ClientRedirectUriService`에 내포된 `ClientInfo` record도 이 인터페이스로 이동.
- [ ] `application/usecase/RegisterOAuthClientService` → `application/service/RegisterOAuthClientService` 로 이동 (import: `port.out.*` → `application.repository.*`, `domain.*` → `application.domain.*`; `RegisterOAuthClientUseCase` 구현체로 선언)
- [ ] `application/usecase/ClientRedirectUriService` → `application/service/ClientRedirectUriService` 로 이동 (동일; `ClientRedirectUriUseCase` 구현체로 선언)
- [ ] `adapter/out/persistence/ServiceClientJpaEntity` → `persistence/entity/ServiceClientJpaEntity` 로 이동 (import: `domain.*` → `application.domain.*`)
- [ ] `adapter/out/persistence/ServiceClientJpaRepository` → `persistence/repository/ServiceClientJpaRepository` 로 이동
- [ ] `adapter/out/persistence/ServiceClientRepositoryAdapter` → `persistence/repository/ServiceClientRepositoryAdapter` 로 이동 (import: `port.out.*` → `application.repository.*`, `domain.*` → `application.domain.*`)
- [ ] `adapter/out/sas/SasClientRegistrarAdapter` → `persistence/repository/SasClientRegistrarAdapter` 로 이동 (import: `port.out.*` → `application.repository.*`)
- [ ] `adapter/out/sas/SasRedirectUriManagerAdapter` → `persistence/repository/SasRedirectUriManagerAdapter` 로 이동 (import: `port.out.*` → `application.repository.*`)
- [ ] `ServiceClientAutoConfiguration` 내 `@EnableJpaRepositories("…adapter.out.persistence")` → `"…persistence.repository"` 로 갱신
- [ ] `ServiceClientAutoConfiguration` 내 `@EntityScan("…adapter.out.persistence")` → `"…persistence.entity"` 로 갱신
- [ ] service-client 모듈 테스트 파일 이동 + import 갱신 (`RegisterOAuthClientServiceTest`, `ClientRedirectUriServiceTest`) — implementation-plan.md 테스트 파일 이동 목록 참조
- [ ] 구 패키지 디렉터리 삭제: `application/port/`, `adapter/`, `domain/`

#### [auth-api 모듈] presentation 계층 정비 + config/security 보안 어댑터 배치 + 엄격 DIP seam 도입

- [ ] `LoginTokenUseCase` 인터페이스 신설 (`application/usecase/LoginTokenUseCase`) — 엄격 DIP; `ReissueController`, `JsonLoginAuthenticationFilter`(config/security), `SecurityConfig`(config/security)의 `LoginTokenService` 직접 의존 차단. `LoginTokenService.TokenPair` record도 이 인터페이스로 이동. 메서드: `issue(Member)`, `reissue(Long)`, `extractMemberIdFromRt(Jwt)`
- [ ] `LoginRedirectUseCase` 인터페이스 신설 (`application/usecase/LoginRedirectUseCase`) — 엄격 DIP; `JsonLoginAuthenticationFilter`(config/security), `SecurityConfig`(config/security)의 `LoginRedirectResolver` 직접 의존 차단. 메서드: `resolve(String clientId, String defaultUrl)`
- [ ] `application/LoginTokenService` → `application/service/LoginTokenService` 로 이동 (import: `member.application.port.out.MemberRepository` → `member.application.repository.MemberRepository`, `member.domain.*` → `member.application.domain.*`; `LoginTokenUseCase` 구현체로 선언)
- [ ] `application/LoginRedirectResolver` → `application/service/LoginRedirectResolver` 로 이동 (필드 타입: `ClientRedirectUriService` → `ClientRedirectUriUseCase`; import 갱신; `LoginRedirectUseCase` 구현체로 선언)
- [ ] `adapter/in/web/` 하위 컨트롤러 9종 → `presentation/controller/` 로 이동
  - `AdminClientController` (이동 + DIP 전환: `RegisterOAuthClientService` → `RegisterOAuthClientUseCase`, `ClientRedirectUriService` → `ClientRedirectUriUseCase`)
  - `AdminMemberController` (이동 + DIP 전환: `MemberRepository` → `MemberQueryUseCase`)
  - `AdminRoleController` (이동 + DIP 전환: `MemberRepository` → `MemberQueryUseCase`)
  - `ClientController` (이동 + DIP 전환: `RegisterOAuthClientService` → `RegisterOAuthClientUseCase`)
  - `JwksController` (이동)
  - `MemberController` (이동)
  - `MemberInfoController` (이동 + DIP 전환: `MemberRepository` → `MemberQueryUseCase`)
  - `ReissueController` (이동 + DIP 전환: `LoginTokenService` → `LoginTokenUseCase`)
  - `RootController` (이동 — main 머지 신규 추가 파일)
- [ ] `adapter/in/web/` 하위 DTO 2종 → `presentation/dto/` 로 이동 (`LoginResponse`, `SignupRequest`)
- [ ] `adapter/in/web/TokenCookieManager` → `presentation/util/TokenCookieManager` 로 이동 (`@Component` 쿠키 헬퍼 — DTO 아님)
- [ ] `security/MemberUserDetails` → `config/security/MemberUserDetails` 로 이동 (package 선언 변경; import: `member.domain.Member` → `member.application.domain.Member`)
- [ ] `security/MemberUserDetailsService` → `config/security/MemberUserDetailsService` 로 이동 (DIP 전환: `MemberRepository` 직접 주입 → `MemberQueryUseCase` 주입; import: `member.application.port.out.MemberRepository` → `member.application.usecase.MemberQueryUseCase`; `findByLoginId` 호출 경로 갱신)
- [ ] `filter/JsonLoginAuthenticationFilter` → `config/security/JsonLoginAuthenticationFilter` 로 이동 (DIP 전환: `LoginTokenService` → `LoginTokenUseCase`, `LoginRedirectResolver` → `LoginRedirectUseCase`; import: `LoginResponse` → `presentation.dto`, `TokenCookieManager` → `presentation.util`, `MemberUserDetails` → 동일 패키지 import 제거)
- [ ] `config/SecurityConfig` → `config/security/SecurityConfig` 로 이동 + DIP 전환 (package 선언: `config` → `config.security`; `@Autowired LoginTokenService` → `@Autowired LoginTokenUseCase`, `@Autowired LoginRedirectResolver` → `@Autowired LoginRedirectUseCase`; 생성자 전달 인자 동일 타입 변경; import: `adapter.in.web.TokenCookieManager` → `presentation.util.TokenCookieManager`, `filter.JsonLoginAuthenticationFilter` → `config.security.JsonLoginAuthenticationFilter`)
- [ ] `ApplicationServiceConfig` import + `@Bean` 파라미터 타입 갱신 — `member.application.port.out.*` → `member.application.repository.*`, `member.application.usecase.SignupService` → `member.application.service.SignupService`, `client.application.usecase.ClientRedirectUriService` → `client.application.usecase.ClientRedirectUriUseCase`, `api.application.LoginRedirectResolver` → `api.application.service.LoginRedirectResolver`, `api.application.LoginTokenService` → `api.application.service.LoginTokenService`
- [ ] auth-api 테스트 파일 이동 + import 갱신 (특히 `@MockBean MemberRepository` → `@MockBean MemberQueryUseCase` 전환 포함) — implementation-plan.md 테스트 파일 이동 목록 참조 (10종)
- [ ] 구 패키지 디렉터리 삭제: `adapter/`, `filter/`, `security/`, `application/LoginTokenService.java`, `application/LoginRedirectResolver.java`
  - `presentation/filter` 패키지는 신설하지 않으며, 모든 보안 어댑터는 `config/security`에 배치됨을 확인

#### [api-gateway 모듈] presentation 계층 정비 + config/security 보안 어댑터 배치

- [ ] `web/RootController` → `presentation/controller/RootController` 로 이동 (main 머지 신규 추가 파일)
- [ ] `security/JwtVerifier` → `config/security/JwtVerifier` 로 이동 (package 선언 변경; 상호 import는 동일 패키지이므로 제거. `presentation/util`로 배치하던 이전 안은 폐기)
- [ ] `security/PassportBuilder` → `config/security/PassportBuilder` 로 이동 (동일 규칙)
- [ ] `filter/BearerToPassportFilter` → `config/security/BearerToPassportFilter` 로 이동 (package 선언 변경; import: `gateway.security.JwtVerifier` → 동일 패키지 제거, `gateway.security.PassportBuilder` → 동일 패키지 제거; import `gateway.config.GatewayRoutingConfig` → **변경 없음** (GatewayRoutingConfig는 config/에 잔류))
- [ ] `config/GatewaySecurityConfig` → `config/security/GatewaySecurityConfig` 로 이동 (package 선언: `config` → `config.security`; import `gateway.security.JwtVerifier` → 동일 패키지이므로 제거)
- [ ] api-gateway 테스트 파일 이동 + import 갱신 (`BearerToPassportFilterTest`, `JwtVerifierTest`, `PassportBuilderTest`) — implementation-plan.md 테스트 파일 이동 목록 참조
- [ ] 구 패키지 디렉터리 삭제: `filter/`, `security/`, `web/`
  - `presentation/util`, `presentation/filter` 패키지는 신설하지 않으며, 보안 어댑터는 전부 `config/security`에 배치됨을 확인

#### [공통] 패키지 이동 후 의존성 방향 검증

- [ ] presentation → application.usecase(인터페이스) 의존만 존재하는지 확인 (presentation이 application.service 구현체 또는 application.repository를 직접 참조하지 않는지)
- [ ] config/security → application.usecase(인터페이스) 의존만 존재하는지 확인 (보안 어댑터가 application.service 구현체 또는 application.repository/persistence를 직접 참조하지 않는지)
  - auth-api: `grep -rn "application.service\|application.repository\|persistence" services/apis/auth-api/src/main/java/com/econo/auth/api/config/security` → 0건이어야 함
  - api-gateway: `grep -rn "application.service\|application.repository\|persistence" services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/security` → 0건이어야 함
- [ ] application.service → application.repository(인터페이스) 의존만 존재하는지 확인 (application이 persistence 구현체를 직접 참조하지 않는지)
- [ ] `LoginTokenService`(application.service)가 `MemberRepository`(`member.application.repository`) 인터페이스에만 의존하는지 확인
- [ ] `LoginRedirectResolver`(application.service)가 `ClientRedirectUriUseCase`(`client.application.usecase`) 인터페이스에만 의존하는지 확인
- [ ] JPA 엔티티 경계 누수 가드 — `grep -rn "JpaEntity" services/libs/*/src/main/java services/apis/*/src/main/java | grep -E "application|usecase|domain|presentation"` → 0건이어야 함

---

### DB 작업

- 해당 없음 — 테이블/컬럼/인덱스/제약/마이그레이션 일체 변경하지 않는다. 패키지 이동에 의해 JPA 엔티티 클래스의 FQCN이 바뀌지만, Hibernate DDL 생성이 아닌 Flyway 마이그레이션을 사용하므로 스키마에 영향이 없다. `@EnableJpaRepositories` / `@EntityScan` 경로 갱신은 구현 작업에 포함하였다.

---

### 기타 작업

#### AutoConfiguration.imports 불변 확인

- [ ] `services/libs/member/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — FQCN `com.econo.auth.member.config.MemberAutoConfiguration` 불변 확인 (변경 불필요)
- [ ] `services/libs/service-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — FQCN `com.econo.auth.client.config.ServiceClientAutoConfiguration` 불변 확인 (변경 불필요)
- [ ] `services/libs/common-infra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — `CommonInfraAutoConfiguration` FQCN 불변 확인 (common-infra는 이번 리팩토링 대상 패키지 없음)

#### iCloud Drive 충돌본 방지 및 빌드 검증

- [ ] 각 Phase(1~4) 완료 직후 해당 모듈 단위 컴파일 체크 수행 (`./gradlew :<module>:compileJava`)
- [ ] 각 Phase 컴파일 성공 후 단위 테스트 체크 (`./gradlew :<module>:test`)
- [ ] 전체 Phase 완료 후 `./gradlew clean build` 클린 재컴파일 수행
- [ ] iCloud 충돌본 스캔 및 제거 (`find . -name "* 2.java" -not -path "./.git/*"`)
- [ ] 모든 기존 테스트가 그대로 통과하는지 최종 확인 (`./gradlew test`)

#### ADR 문서화

- [ ] `docs/adr/0014-3-layer-dip-architecture.md` 신규 작성
  - 제목: 3-Layer + 계층별 인터페이스(DIP) 아키텍처 채택
  - 필수 포함: 배경(헥사고날 어휘 혼재 → 진입 장벽), 결정(presentation/application/persistence 3계층 + DIP), 헥사고날 대비 트레이드오프, 모듈 책임 분리 근거(libs = application+persistence, api 모듈 = presentation+application), config/security 서브패키지 배치 결정 근거, 상태: Accepted

#### ARCHITECTURE.md 갱신

- [ ] `docs/ARCHITECTURE.md` 의 "member 패키지 구조 (헥사고날)" 섹션을 신규 3-layer 구조로 교체
  - 새 패키지 트리: `application/` (usecase 인터페이스 + service 구현 + repository 인터페이스 + domain), `persistence/` (entity/ + repository/), `config/`, `exception/`
- [ ] `docs/ARCHITECTURE.md` 의 "service-client 패키지 구조 (헥사고날)" 섹션을 신규 3-layer 구조로 교체
  - 새 패키지 트리: `application/` (usecase 인터페이스 + service 구현 + repository 인터페이스 + domain), `persistence/` (entity/ + repository/), `config/`, `exception/`
- [ ] `docs/ARCHITECTURE.md` 에 "계층 모델 및 의존성 규칙" 섹션 신규 추가
  - 의존성 방향 다이어그램: `presentation.controller → application.usecase(IF) ← application.service → application.repository(IF) ← persistence.repository`. `config/security`(보안 어댑터)도 `application.usecase`에만 의존.
  - 모듈별 계층 사용 범위 (libs = application+persistence, api 모듈 = presentation+application+config/security)
- [ ] `docs/ARCHITECTURE.md` 의 "핵심 설계 결정 #6 헥사고날 아키텍처 (member)" 항목을 3-layer + DIP로 내용 교체 (ADR-0014 참조 링크 추가)

#### CONVENTION.md 갱신

- [ ] `docs/CONVENTION.md` 의 "1.1 패키지" 섹션에서 헥사고날 어댑터 패키지 설명(`adapter.in.web`, `adapter.out.persistence` 등)을 신규 3-layer 어휘(`presentation.controller`, `presentation.dto`, `presentation.util`, `application.usecase`, `application.service`, `application.repository`, `persistence.entity`, `persistence.repository`, `config.security`)로 교체
  - `config/security` 배치 기준 명시: Spring Security에 결합된 클래스(SecurityConfig, UserDetailsService, AuthenticationFilter, GlobalFilter, JWT 처리 유틸)는 `config/security` 서브패키지에 배치. `presentation/filter` 패키지는 사용하지 않음.
- [ ] `docs/CONVENTION.md` 의 "1.2 클래스" 네이밍 표에 신규 계층별 접미사 규칙 추가·정비
  - usecase 인터페이스: `{Action}UseCase`, service 구현체: `{Action}Service` 또는 `{Action}Resolver`, repository 인터페이스(출력 포트): `{Resource}Repository`, JPA 구현체: `{Resource}RepositoryAdapter`, JPA 인터페이스: `{Resource}JpaRepository`, JPA 엔티티: `{Resource}JpaEntity`

---

## 체크리스트

- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 참고

- `.claude/plans/09-layered-architecture/implementation-plan.md` — 모듈별 현재→목표 클래스 매핑 전체표, 신설 인터페이스 5종, Phase 1~5 단계별 순서
- `docs/ARCHITECTURE.md` — 현재 헥사고날 패키지 구조 기술 (member, service-client 섹션)
- `docs/CONVENTION.md` — 현재 패키지 네이밍 컨벤션
- `docs/adr/0013-passport-member-self-registration.md` — 최신 ADR (다음 번호: 0014)
- `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java` — @EnableJpaRepositories / @EntityScan 경로 갱신 필요
- `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java` — 동일
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — import + 파라미터 타입 갱신 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — config/security 이동 + DIP 전환 대상 (단순 import 갱신 아님)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/filter/JsonLoginAuthenticationFilter.java` — config/security 이동 + DIP 전환 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/security/MemberUserDetailsService.java` — config/security 이동 + DIP 전환 대상
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewaySecurityConfig.java` — config/security 이동 + import 갱신 대상
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/BearerToPassportFilter.java` — config/security 이동 + GatewayRoutingConfig import 유지 확인
- `services/libs/member/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `services/libs/service-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `services/libs/common-infra/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
