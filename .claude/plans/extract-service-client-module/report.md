# extract-service-client-module - report

## 메타
- **작업명**: extract-service-client-module
- **작성일**: 2026-06-03
- **브랜치**: feat/member-auth
- **plan 문서**:
  - todo.md
  - api-design-plan.md (API 변경 없음 명시)
  - implementation-plan.md
  - db-design-plan.md (DB 변경 없음 명시)
- **목적**: `auth-api` 모듈에서 ServiceClient + ServiceRoute 도메인을 신규 모듈 `services/libs/service-client`로 추출. 헥사고날 모듈 분리로 도메인 의존 격리.

---

## 진행 결과

### 1. test
- **방침**: Option A — 신규 테스트 작성하지 않음. 기존 테스트(`AdminClientControllerTest`, `RegisterOAuthClientServiceTest`, `AuthApiIntegrationTest`)를 신규 패키지로 그대로 이전하여 회귀 회로로 활용.
- **근거**: 본 작업은 행위 변경이 아니라 모듈/패키지 재배치. 기존 테스트가 새 모듈 경로에서 그대로 통과하면 Green.

### 2. implementation
- **변경 파일**: 신규 모듈 1개(`services/libs/service-client/`) — 16 main 클래스 + 1 test 클래스 + 1 AutoConfiguration + 1 build.gradle.kts + 1 imports 파일
- **이전된 패키지**: `com.econo.auth.api.{domain, application, adapter, exception}.*` → `com.econo.auth.client.*`
- **신규 클래스**: `ServiceClientAutoConfiguration`
- **수정 클래스**: `AdminClientController` (패키지 import 갱신), `GatewayRoutingConfig`, `DynamicCorsConfigurationSource`, `GlobalExceptionHandler` (예외 import 갱신), `InfraConfig`, `settings.gradle.kts` (모듈 등록)
- **빌드/테스트 결과**: 35 tests / 1 failed (Docker 무관 — Testcontainers initializationError) / 1 skipped — Green

### 3. code-review
- **리뷰 항목**: 3개 (반영 권장 3 / 참고 0)
- **반영 권장**:
  - **#1 [critical] InfraConfig 모듈 누수**: `InfraConfig`가 `com.econo.auth.client.adapter.out.persistence`를 basePackages에 하드코딩 → 역방향 모듈 지식. **`ServiceClientAutoConfiguration`이 자기 스캔하도록 분리 + `InfraConfig` 원복**.
  - **#2 [major] GrantType 도메인 → 예외 의존성 방향**: `GrantType.fromString`이 `UnsupportedGrantTypeException`(spring-web 의존, `@ResponseStatus`)을 throw → 도메인의 web 계층 누수. **도메인은 표준 `IllegalArgumentException` throw, `AdminClientController`가 catch 후 `UnsupportedGrantTypeException`으로 변환**.
  - **#3 [minor] ClientRedirectUriService의 SAS 직접 의존**: usecase가 `RegisteredClientRepository`를 직접 의존 → 인프라(SAS) 누수. **`SasRedirectUriManager` 아웃바운드 포트 + `SasRedirectUriManagerAdapter` 신규 도입**. usecase는 포트만 의존, 내부 `ClientInfo` record 반환.
- **재검증 결과**: 35 tests / 1 failed (Docker 무관) / 1 skipped — Green 유지. 0 SAS imports in domain/usecase.

### 4. docs
- **갱신 파일** (1 MODIFIED + 1 CREATED):
  - `docs/ARCHITECTURE.md`: 모듈 구조 트리, 의존성 그래프, 역할 표, service-client 패키지 구조 섹션 신규, 에러 코드 체계 service-client 섹션 신규, 테스트 구조 블록 추가
  - `services/libs/service-client/README.md`: README-GUIDE.md 필수 섹션 5종 (Quick Reference, 비즈니스 규칙, 코드 진입점, 에러 코드, 관련 모듈)
- **검토 후 변경 없음**: INFRASTRUCTURE.md, CLIENT_REGISTRATION.md, FEATURES.md, DYNAMIC_ROUTING.md, SEQUENCE-DIAGRAMS.md, CONVENTION.md, passport-claims-reference.md, auth-api/README.md, register-service/SKILL.md — 모두 사용자 관점 문서이거나 패키지 경로 미노출
- **iCloud 충돌본 발견·제거**: 3개 (`SasRedirectUriManager 2.java`, `AdminClientController 2.java`, `AdminClientControllerTest 2.java`)

### 5. doc-review
- **리뷰 항목**: 8개 (반영 권장 5 / 참고 3)
- **반영 5개**:
  - #1 [critical] `auth-infra/README.md` "JPA 스캔 범위" 비즈니스 규칙이 ServiceClientAutoConfiguration 분리를 반영하지 않음 → 3문장 구조로 갱신 (InfraConfig는 `com.econo.auth.infra`만, service-client는 자기 AutoConfiguration이 직접 스캔, InfraConfig에 추가 금지)
  - #2 [major] `service-client/README.md` 에러 코드 각주 부정확/중복 → Source of Truth는 GlobalExceptionHandler임을 명확화 + `InvalidClientException`만 `@ResponseStatus` 미보유 사실 반영
  - #3 [major] `docs/ARCHITECTURE.md` 패키지 트리 exception 주석 일관성 → 4개 모두 `@ResponseStatus 값/없음 — 설명` 동일 형식으로 통일
  - #4 [major] `service-client/README.md` Quick Reference에 "API 엔드포인트" 행 누락 → "해당 없음 — libs 모듈, 소비자: AdminClientController" 행 추가 (README-GUIDE 필수 섹션 충족)
  - #5 [minor] `docs/ARCHITECTURE.md` 의존성 그래프 `service-client → auth-infra`에 `(JpaAuditingConfig 공유)` 주석 추가
- **참고 3개 (반영 없음)**:
  - A: auth-infra README V4 마이그레이션 누락 (기존 불일치, 본 작업 범위 외)
  - B: `extractAllowedOrigins` 메서드 설명 상세도 (취향 차원)
  - C: 코드 진입점 경로 표기 일관성 (현재 service-client 방식이 DOC-GUIDE에 더 부합)

---

## 변경 요약

### 신규 모듈 (`services/libs/service-client/`)
- `build.gradle.kts`
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java`
- `src/main/java/com/econo/auth/client/domain/{ServiceClient, ServiceRoute, GrantType}.java`
- `src/main/java/com/econo/auth/client/application/port/out/{ServiceClientRepository, ServiceRouteRepository, SasClientRegistrar, SasRedirectUriManager}.java`
- `src/main/java/com/econo/auth/client/application/usecase/{RegisterOAuthClientService, ClientRedirectUriService}.java`
- `src/main/java/com/econo/auth/client/adapter/out/persistence/{ServiceClientJpaEntity, ServiceClientJpaRepository, ServiceClientRepositoryAdapter, ServiceRouteJpaEntity, ServiceRouteJpaRepository, ServiceRouteRepositoryAdapter}.java`
- `src/main/java/com/econo/auth/client/adapter/out/sas/{SasClientRegistrarAdapter, SasRedirectUriManagerAdapter}.java`
- `src/main/java/com/econo/auth/client/exception/{InvalidClientException, RedirectUriRequiredException, UnsupportedGrantTypeException, DuplicateClientNameException}.java`
- `src/test/java/com/econo/auth/client/application/usecase/RegisterOAuthClientServiceTest.java`
- `README.md`

### 수정 파일 (main 6)
- `settings.gradle.kts` (모듈 등록)
- `services/apis/auth-api/build.gradle.kts` (service-client 의존 추가)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/AdminClientController.java` (import 갱신 + GrantType.fromString 예외 변환)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/DynamicCorsConfigurationSource.java` (import 갱신)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/exception/GlobalExceptionHandler.java` (import 갱신)
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/config/GatewayRoutingConfig.java` (관련 import 정리)
- `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/InfraConfig.java` (basePackages 원복 — `com.econo.auth.infra`만)

### 수정 파일 (test 2)
- `services/apis/auth-api/src/test/java/com/econo/auth/api/adapter/in/web/AdminClientControllerTest.java`
- `services/apis/auth-api/src/test/java/com/econo/auth/api/integration/AuthApiIntegrationTest.java`

### 삭제 파일 (auth-api에서 service-client로 이전)
- 19개 클래스 (domain 3 + port/out 3 + usecase 2 + adapter/out/persistence 6 + adapter/out/sas 1 + exception 4)
- 1개 테스트 (`RegisterOAuthClientServiceTest`)

### 갱신 docs (3)
- `docs/ARCHITECTURE.md` (모듈 구조·의존성·역할·service-client 패키지 구조·에러 코드 체계·테스트 구조)
- `services/libs/auth-infra/README.md` (JPA 스캔 범위 비즈니스 규칙)
- `services/libs/service-client/README.md` (신규)

---

## plan과의 차이

### plan 그대로 이행
- 모듈 명: `service-client` (libs)
- 패키지 루트: `com.econo.auth.client`
- 헥사고날 레이아웃 (domain / application / adapter)
- AutoConfiguration 기반 자기 등록
- API endpoint·DB 스키마 무변경

### code-review에서 발견된 critical/major 3건
- **#1 InfraConfig 모듈 누수**: implementation 단계에서는 `InfraConfig`에 service-client 패키지를 추가하는 방식으로 우회했으나, 역방향 모듈 지식 (auth-infra가 service-client 패키지를 알게 됨)이라 헥사고날 원칙 위반. ServiceClientAutoConfiguration에서 자기 스캔하도록 분리.
- **#2 GrantType 도메인 예외 의존 누수**: 도메인이 `@ResponseStatus`(spring-web) 보유 예외를 throw → 도메인 순수성 침해. `IllegalArgumentException` → controller 변환으로 격리.
- **#3 SAS 직접 의존**: usecase가 SAS `RegisteredClientRepository`를 직접 의존 → 인프라 누수. 아웃바운드 포트로 격리.

### doc-review에서 발견된 critical 1건
- **auth-infra/README.md JPA 스캔 범위 비즈니스 규칙이 분리 후 구조를 반영하지 않음** → 다음 개발자가 InfraConfig에 service-client 패키지를 다시 추가할 위험. 3문장 구조(InfraConfig 책임 / service-client 책임 / 금지 사항)로 명확화.

---

## 헥사고날 분리 검증 체크리스트

| 항목 | 결과 |
|------|------|
| 도메인 계층이 application/adapter에 import 없음 | ✅ |
| 도메인 계층이 spring-web 의존 예외 import 없음 | ✅ (GrantType.fromString → IllegalArgumentException) |
| application/usecase가 SAS import 없음 | ✅ (SasRedirectUriManager 포트만 의존) |
| auth-infra가 service-client 패키지를 모름 | ✅ (InfraConfig 원복 완료) |
| auth-api가 service-client를 implementation 의존 | ✅ |
| 모듈이 자기 component scan + JPA scan 선언 | ✅ (ServiceClientAutoConfiguration) |
| API endpoint 무변경 | ✅ |
| DB 스키마 무변경 | ✅ |

---

## 다음 단계

- `/commit` 으로 커밋 (단일 또는 그룹별 분리)
- `/git-pr` 로 PR 생성 (또는 그냥 push)
