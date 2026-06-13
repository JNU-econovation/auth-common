# sas-authorization-server - todo

## 메타
- **작업명**: sas-authorization-server
- **문서 타입**: todo
- **작성일**: 2026-05-24
- **관련 문서** (같은 디렉터리):
  - api-design-plan.md
  - implementation-plan.md
  - db-design-plan.md

## 개요
Spring Authorization Server(SAS 1.x)를 채택하여 auth-api를 OIDC Authorization Server(IdP)로 재작업한다.
직전 작업 member-auth에서 구현한 HMAC/쿠키 기반 JWT 발급 계층을 제거하고, 토큰 발급 권위를 SAS로 일원화한다.
증분 [1]은 auth-api에 SAS 코어를 구성하고, 증분 [2]는 api-gateway를 HMAC 검증에서 SAS JWKS(RSA) 검증으로 재작성한다.
자사 프런트는 OAuth public client(PKCE)로 동작하며, 로그인 UI는 SPA가 담당하고 AS는 JSON 인증 엔드포인트를 제공한다.

---

## 본문

### API 작업

#### [1] auth-api — SAS 표준 엔드포인트

- [ ] **GET /.well-known/openid-configuration** — SAS가 자동 노출하는 OIDC Discovery 엔드포인트. 별도 코드 불필요하나, `issuer-uri` 설정값이 정확히 매핑되는지 통합 테스트로 확인
- [ ] **GET /oauth2/jwks** — RSA 공개키를 JWK Set로 노출하는 엔드포인트. SAS 자동 노출; 게이트웨이가 이 URL에서 공개키를 가져오도록 설정 연계 확인
- [ ] **GET /oauth2/authorize** — Authorization Code + PKCE 플로우 진입점. SAS 자동 처리; 이 엔드포인트는 **세션 인증**을 요구하므로, 커스텀 JSON 로그인 엔드포인트(아래)로 세션 수립 후 리다이렉트 되는 흐름을 E2E 테스트로 확인
- [ ] **POST /oauth2/token** — Authorization Code → Access Token/ID Token/Refresh Token 교환. SAS 자동 처리; `OAuth2TokenCustomizer`가 커스텀 클레임을 주입하는지 통합 테스트로 확인
- [ ] **GET /userinfo** — OIDC UserInfo 엔드포인트. SAS 자동 노출; 반환되는 클레임 목록 테스트로 확인
- [ ] **POST /api/v1/auth/login (재작성)** — 기존 JWT 쿠키 발급 로직 제거. JSON 자격증명(`loginId`, `password`)을 받아 **서버 세션(쿠키)을 수립**하고 200 OK 반환. Access/ID/Refresh 토큰은 이 엔드포인트에서 발급하지 않음
- [ ] **POST /api/v1/auth/signup** — 기존 구현 유지. 변경 없음
- [ ] **POST /api/v1/auth/logout** — 세션 무효화 + 세션 쿠키 만료 처리로 재작성 (기존: JWT 쿠키 Max-Age=0)

#### [2] api-gateway — JWKS 기반 검증 인터페이스 변경

- [ ] **Bearer 토큰 수신** — 게이트웨이가 `Authorization: Bearer <token>` 헤더에서 SAS 발급 JWT를 읽도록 진입 방식 변경 (기존: `auth_token` 쿠키 → 신규: Bearer 헤더). 인증 불필요 경로(permit list)는 그대로 유지

---

### 구현 작업

#### [1] auth-api — SAS 설정 및 코어

- [ ] **`AuthorizationServerConfig` 신설** (`config.AuthorizationServerConfig`)
  - `@Configuration` 클래스
  - SAS `AuthorizationServerSettings` 빈 등록: `issuer` URL을 `${auth.issuer-uri}` 환경변수로 주입
  - SAS `SecurityFilterChain` (인가 서버 전용) 빈 등록: `OAuth2AuthorizationServerConfigurer` 적용, OIDC 활성화, 기본 필터체인과 `@Order` 분리
  - SAS 인가 서버 필터체인과 기존 앱 필터체인을 **2개 분리 구조**로 재구성 (`@Order(1)` / `@Order(2)`)

- [ ] **`SecurityConfig` 재작성** (`config.SecurityConfig`)
  - 기존 Stateless + permitAll 단일 필터체인을 제거
  - 앱용 `SecurityFilterChain` (`@Order(2)`) 재정의:
    - 세션 관리: **세션 기반**으로 전환 (`SessionCreationPolicy.IF_REQUIRED`)
    - CSRF: 커스텀 JSON 로그인 엔드포인트만 제외(또는 stateless CSRF 토큰 방식 채택 — designer 판단)
    - `/api/v1/auth/signup`, `/api/v1/auth/login` permit; 나머지는 authenticated
    - 커스텀 JSON 인증 필터를 `UsernamePasswordAuthenticationFilter` 앞에 삽입

- [ ] **`JsonLoginAuthenticationFilter` 신설** (커스텀 인증 필터)
  - `POST /api/v1/auth/login` 요청에서 JSON body(`loginId`, `password`)를 읽어 `UsernamePasswordAuthenticationToken` 생성
  - `AuthenticationManager`에 위임 → 인증 성공 시 `SecurityContextRepository`에 저장하여 서버 세션 수립
  - 성공 응답: `200 OK` (JSON body 없음, 세션 쿠키 Set-Cookie)
  - 실패 응답: `401 Unauthorized` + 기존 `InvalidCredentialsException` 에러 구조 재사용

- [ ] **`MemberUserDetailsService` 신설** (`config.MemberUserDetailsService` 또는 `adapter.in.web`)
  - Spring Security `UserDetailsService` 구현
  - `loginId`로 `MemberRepository.findByLoginId()` 호출 → `UserDetails` 반환
  - 비밀번호 검증은 `PasswordHasher`(`BCrypt`) 위임 — `PasswordEncoder` 빈으로 등록

- [ ] **`MemberController.login` 재작성** (기존 메서드 대체)
  - JWT 쿠키 발급 로직 및 `LoginUseCase` 호출 제거
  - `JsonLoginAuthenticationFilter`가 이 경로를 처리하므로 핸들러 메서드 자체를 제거하거나 필터가 처리 전 컨트롤러를 거치지 않도록 설정 확인

- [ ] **`MemberController.logout` 재작성**
  - `HttpSession.invalidate()` 호출 + 세션 쿠키 만료 처리

- [ ] **`RegisteredClientConfig` 신설** (`config.RegisteredClientConfig`)
  - `JdbcRegisteredClientRepository` 빈 등록
  - 정적 seed: 자사 프런트 public client (grant: `authorization_code` + `refresh_token`, PKCE 필수, scope: `openid profile`, consent 자동 승인 설정)
  - confidential client seed는 선택적으로 추가 가능 (설정 파일 기반)
  - 앱 기동 시 DB에 해당 `clientId`가 없으면 `save()`, 있으면 skip하는 멱등 로직 포함

- [ ] **`OAuth2AuthorizationServiceConfig` 신설** (`config.OAuth2AuthorizationServiceConfig`)
  - `JdbcOAuth2AuthorizationService` 빈 등록 (`DataSource`, `RegisteredClientRepository` 주입)
  - `JdbcOAuth2AuthorizationConsentService` 빈 등록

- [ ] **`RsaKeyConfig` 신설** (`config.RsaKeyConfig`)
  - RSA 키페어를 기동 시 생성하지 않고 환경변수 또는 keystore에서 로드
  - 로딩 전략: `${RSA_PRIVATE_KEY}` (PEM 문자열) 또는 `${KEYSTORE_PATH}` + `${KEYSTORE_PASSWORD}` 중 designer가 결정 (모호함 섹션 참고)
  - `RSAKey` → `JWKSource<SecurityContext>` 빈 등록
  - `JwtEncoder` 빈 등록 (`NimbusJwtEncoder`)

- [ ] **`PassportTokenCustomizer` 신설** (`config.PassportTokenCustomizer`)
  - `OAuth2TokenCustomizer<JwtEncodingContext>` 구현
  - Access Token 및 ID Token에 Passport 커스텀 클레임 주입: `memberId`, `loginId`, `name`, `generation`, `status`, `roles`
  - 클레임 값은 `SecurityContext`에서 `MemberUserDetails` (또는 `UserDetails` 확장)를 꺼내 채움
  - `@Bean`으로 등록하면 SAS가 자동으로 토큰 생성 시 호출

- [ ] **`ApplicationServiceConfig` 재작성**
  - `TokenIssuer` 포트 의존 제거 → `LoginService` 빈 등록에서 `TokenIssuer` 파라미터 제거
  - `LoginService` 자체를 제거하고 Spring Security 인증 파이프라인으로 대체할 수도 있음 — designer 판단

- [ ] **`auth-core` — `TokenIssuer` 포트 제거**
  - `application.port.out.TokenIssuer` 인터페이스 삭제
  - `LoginUseCase` / `LoginService`: `TokenIssuer` 의존 제거. `LoginResult`에서 `jwtToken` 필드 제거 (또는 `LoginUseCase` 자체를 인증 필터로 대체하고 제거) — designer 판단
  - 관련 단위 테스트(`LoginServiceTest`)에서 `TokenIssuer` mock 제거

- [ ] **`auth-infra` — `JwtTokenIssuerAdapter` 제거**
  - `adapter.out.token.JwtTokenIssuerAdapter` 클래스 삭제
  - `jjwt` 의존성을 `auth-infra`에서 제거 (SAS가 JWT 발급 담당하므로 불필요)
  - 관련 테스트(`JwtTokenIssuerAdapterTest`) 삭제

- [ ] **`GlobalExceptionHandler` 재작성** (`exception.GlobalExceptionHandler`)
  - SAS/Spring Security가 처리하는 인증 에러(`AuthenticationException`, `AccessDeniedException`)는 핸들러에서 제거하거나 SAS 에러 응답 형식과 충돌하지 않도록 조정
  - 기존 도메인 예외 처리 (`MemberAlreadyExistsException`, `InvalidPasswordPolicyException` 등)는 유지

#### [2] api-gateway — JWKS 기반 검증으로 재작성

- [ ] **`JwtVerifier` 재작성** (`security.JwtVerifier`)
  - 기존: `JWT_SECRET` 환경변수로 HMAC 서명 검증
  - 신규: `${auth.jwks-uri}` (예: `http://auth-api:8081/oauth2/jwks`) 에서 RSA 공개키를 가져와 RS256 서명 검증
  - 공개키 갱신 전략(캐시 TTL 또는 JWK rotation 지원) — designer 판단. 최소한 기동 시 fetch + 캐시 구현 필요
  - `JWT_SECRET` 환경변수 의존 제거

- [ ] **`JwtCookieToPassportFilter` 재작성** (`filter.JwtCookieToPassportFilter`)
  - 쿠키 파싱 → Bearer 헤더 파싱으로 변경 (`Authorization: Bearer <token>`)
  - 클래스명 변경 검토: `BearerToPassportFilter` 또는 유지 — designer 판단
  - 나머지 Passport 구성·헤더 주입·인증 필요 경로 판별 로직은 유지

- [ ] **`PassportBuilder` 재확인** (`security.PassportBuilder`)
  - SAS가 발급하는 JWT 클레임 구조에 맞게 클레임 매핑 확인
  - `PassportTokenCustomizer`가 주입한 커스텀 클레임(`memberId`, `loginId`, `name`, `generation`, `status`, `roles`)을 Passport 필드로 올바르게 매핑하는지 검증

- [ ] **`GatewayRoutingConfig` 재확인** (`config.GatewayRoutingConfig`)
  - permit 경로 목록에서 `/api/v1/auth/login`의 의미 변경 반영: 로그인은 여전히 permit이나, 목적이 세션 수립임을 주석으로 명시
  - `/oauth2/authorize`, `/oauth2/token` 등 SAS 엔드포인트의 게이트웨이 라우팅 처리 방식 결정 (게이트웨이를 거치지 않고 직접 노출하는지, 통과시키는지) — designer 판단

---

### DB 작업

- [ ] **`V2__create_sas_tables.sql` 마이그레이션 작성** (V1은 `members` 테이블이 점유)
  - 위치: `services/libs/auth-infra/src/main/resources/db/migration/`
  - 파일명 컨벤션 준수: `V2__create_sas_tables.sql`
  - SAS 제공 표준 스키마 3개 테이블 포함:
    - `oauth2_registered_client` (RegisteredClient 영속)
    - `oauth2_authorization` (Authorization Code, 토큰 영속)
    - `oauth2_authorization_consent` (동의 정보 영속)
  - DDL은 spring-authorization-server GitHub `oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization/` 공식 스크립트를 그대로 사용하되, PostgreSQL 방언에 맞게 수정 (PostgreSQL 미지원 타입 치환 여부 확인 필요)
  - `COMMENT ON TABLE` / `COMMENT ON COLUMN` 추가 (프로젝트 스키마 컨벤션)

- [ ] **세션 저장소 선택 결정** (모호함 섹션 참고)
  - `HttpSession` 기반 서버 세션이 필요하므로 기본 in-memory 세션은 단일 인스턴스에서만 동작
  - Redis 또는 DB 기반 세션 저장소 도입 여부를 결정해야 함
  - 현 인프라 구성(`docs/INFRASTRUCTURE.md`)에는 Redis 미도입 — 이번 작업 범위에서는 in-memory 세션 사용 후 추후 도입 검토를 허용할지, 아니면 JDBC 세션(`spring-session-jdbc`) 을 즉시 도입할지 결정 필요
  - **JDBC 세션을 도입하는 경우**: `V3__create_spring_session_tables.sql` 마이그레이션 추가 필요 (Spring Session JDBC 스키마)

---

### 기타 작업

#### 의존성 변경

- [ ] **`auth-api/build.gradle.kts` 의존성 추가**
  - `implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")` — SAS 코어
  - Nimbus JOSE 관련 라이브러리는 SAS가 전이 의존으로 포함하므로 별도 추가 불필요 확인
  - `jjwt` 관련 의존성 제거 (`auth-infra` 에서 `JwtTokenIssuerAdapter` 제거에 연동)

- [ ] **`auth-infra/build.gradle.kts` 의존성 제거**
  - `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson` 제거 (`JwtTokenIssuerAdapter` 제거에 연동)
  - SAS Jdbc 지원 클래스는 `spring-authorization-server` 가 포함하나, `auth-infra`가 직접 SAS에 의존할지 아니면 `auth-api`에서만 의존할지 확인 필요 — `JdbcOAuth2AuthorizationService` 빈 등록 위치에 따라 결정

- [ ] **`api-gateway/build.gradle.kts` 의존성 변경**
  - `jjwt` 3종을 SAS 공개키 검증에 계속 사용할지, 또는 `spring-security-oauth2-resource-server` 로 교체할지 결정
  - JWKS URI 기반 검증만 필요하므로 `spring-boot-starter-oauth2-resource-server` 도입이 단순할 수 있음 — designer 판단

#### 설정 파일 변경

- [ ] **`auth-api/src/main/resources/application.yml` 재작성**
  - 제거: `auth.jwt.expiry-seconds`, `auth.jwt.cookie-name`, `JWT_SECRET` 참조
  - 추가: `spring.security.oauth2.authorizationserver.issuer` 또는 `auth.issuer-uri` 설정
  - 추가: RSA 키 로딩 설정 (`auth.rsa.private-key`, `auth.rsa.public-key` 또는 keystore 경로)
  - SAS `JdbcRegisteredClientRepository` 등이 사용할 DataSource는 기존 `spring.datasource.*` 그대로 재사용
  - `spring.session.*` 설정 (세션 저장소 결정 후 반영)
  - 세션 기반으로 전환에 따라 `spring.jpa.hibernate.ddl-auto=validate` 유지, `spring.flyway.enabled=true` 유지

- [ ] **`api-gateway/src/main/resources/application.yml` 재작성**
  - 제거: `JWT_SECRET` 환경변수 참조
  - 추가: `auth.jwks-uri: ${AUTH_JWKS_URI:http://localhost:8081/oauth2/jwks}` 설정

#### 환경 변수 문서화

- [ ] **`docs/INFRASTRUCTURE.md` 환경 변수 섹션 갱신**
  - 제거: `JWT_SECRET` (양 서버 공유 HMAC 시크릿)
  - 추가: `RSA_PRIVATE_KEY` (또는 `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`) — auth-api RSA 서명키
  - 추가: `AUTH_JWKS_URI` — api-gateway가 공개키를 가져오는 URL
  - 추가: `AUTH_ISSUER_URI` — SAS issuer URL (예: `https://auth.econo.com`)
  - OIDC 관련 저장소(이미 "향후 도입 예정"에 기술됨) → 도입 완료로 상태 업데이트

#### 테스트

- [ ] **`LoginServiceTest` 재작성** (`auth-core`)
  - `TokenIssuer` 의존 제거에 맞게 테스트 수정
  - `LoginService`가 제거되는 경우 테스트 파일 자체 삭제

- [ ] **`JwtTokenIssuerAdapterTest` 삭제** (`auth-infra`)
  - `JwtTokenIssuerAdapter` 제거와 연동하여 테스트 파일 삭제

- [ ] **`JwtVerifierTest` 재작성** (`api-gateway`)
  - 기존 HMAC 기반 서명 검증 테스트 → RSA/JWKS 기반으로 교체
  - 테스트에서 RSA 키페어를 직접 생성하여 mock JWK Set 서버를 구성하거나 `WireMock`으로 JWKS 엔드포인트를 스텁

- [ ] **`JwtCookieToPassportFilterTest` 재작성** (`api-gateway`)
  - 쿠키 기반 → Bearer 헤더 기반 테스트로 교체
  - RSA 서명된 JWT를 직접 생성하여 검증 경로 테스트

- [ ] **커스텀 JSON 로그인 통합 테스트** (`auth-api`)
  - `POST /api/v1/auth/login` 성공 시 세션 쿠키가 Set-Cookie로 내려오는지 확인
  - 잘못된 자격증명 시 401 반환 확인
  - 세션 수립 후 `GET /oauth2/authorize` 요청이 redirect 되는지 확인

- [ ] **SAS 토큰 발급 통합 테스트** (`auth-api`)
  - Authorization Code + PKCE 전체 플로우 (로그인 → authorize → token 교환) E2E 테스트
  - 발급된 Access Token의 커스텀 클레임(`memberId`, `loginId`, `name`, `generation`, `status`, `roles`) 포함 여부 확인
  - `/oauth2/jwks` 엔드포인트가 공개키를 올바르게 반환하는지 확인
  - `/.well-known/openid-configuration` issuer 값이 설정과 일치하는지 확인

- [ ] **Passport 변환 통합 테스트** (`api-gateway`)
  - SAS 발급 JWT(RS256)를 Bearer 헤더로 전달 → 게이트웨이가 `X-User-Passport` 헤더로 변환하는지 확인

#### 코드 품질

- [ ] **Spotless 포맷팅 검사** — 재작성·신설 파일 전체 대상 (`./gradlew spotlessCheck`)

---

## 체크리스트
- [ ] todo가 빠짐없이 추출됨
- [ ] 각 항목이 PR 단위로 충분히 잘게 쪼개짐
- [ ] 카테고리 매핑이 명확함
- [ ] 모호함 없이 후속 designer가 바로 작업 가능

---

## 모호한 점 (designer가 결정 후 반영 필요)

아래 항목은 요구사항에서 확정되지 않았거나 구현 시 분기가 생기는 지점입니다. 임의로 가정하지 않고 기록합니다.

1. **RSA 키 외부화 형식**: `RSA_PRIVATE_KEY`/`RSA_PUBLIC_KEY` 환경변수(PEM 문자열)로 주입하는 방식과 keystore 파일(JKS/PKCS12) 경로+비밀번호로 주입하는 방식 중 어떤 것을 사용할지 미확정. 운영 배포 환경(Docker/K8s Secret 또는 파일 마운트)에 따라 결정 필요.

2. **세션 저장소**: 커스텀 JSON 로그인이 서버 세션을 수립하므로 in-memory 세션은 단일 인스턴스에서만 안전하다. 현재 인프라에 Redis 미도입 상태이므로, `spring-session-jdbc`(JDBC 세션)를 이번 작업 범위에 포함할지 아니면 초기에는 in-memory로 허용하고 추후 도입할지 결정 필요. JDBC 세션 도입 시 `V3__create_spring_session_tables.sql` 마이그레이션이 추가된다.

3. **`LoginService` / `LoginUseCase` 존치 여부**: `TokenIssuer` 제거 후 `LoginService`는 `MemberRepository.findByLoginId()` + `PasswordHasher.matches()` 검증만 수행한다. 이 로직을 `MemberUserDetailsService`로 통합하고 `LoginUseCase`/`LoginService`를 제거하는 것이 자연스러우나, auth-core 포트 구조 변경이 수반된다. 제거 범위를 결정 필요.

4. **게이트웨이 SAS 엔드포인트 라우팅**: `/oauth2/authorize`, `/oauth2/token`, `/.well-known/openid-configuration`, `/oauth2/jwks` 같은 SAS 표준 엔드포인트를 게이트웨이를 통해 노출할지(프록시) 아니면 auth-api를 직접 외부에 노출할지 아키텍처 결정 필요. 게이트웨이를 통하면 `GatewayRoutingConfig`에 추가 라우트가 필요하다.

5. **api-gateway JWT 검증 라이브러리**: JWKS 기반 RSA 검증에 기존 `jjwt`를 계속 사용할지(JWKS URL fetch 로직을 직접 구현) 아니면 `spring-boot-starter-oauth2-resource-server`로 교체할지 결정 필요. 후자는 자동 키 갱신(rotation) 지원이 내장되어 있어 구현이 단순하나 gateway 의존성이 추가된다.

6. **자동 동의(consent 스킵) 설정 위치**: SAS에서 1st-party client의 consent를 자동 승인하는 방법은 `RegisteredClient` 설정에서 `requireAuthorizationConsent(false)`로 처리하거나 커스텀 `OAuth2AuthorizationConsentService`를 구현하는 방법이 있다. 어느 방식을 채택할지 designer가 SAS 1.x 문서를 확인 후 결정.

---

## 참고
- `docs/ARCHITECTURE.md` — 모듈 구조, 헥사고날 패키지 컨벤션
- `docs/CONVENTION.md` — 코드 컨벤션 (네이밍, 테스트, Lombok)
- `docs/INFRASTRUCTURE.md` — PostgreSQL/Flyway 컨벤션, 환경 변수 목록 (이번 작업에서 갱신 대상)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — 재작성 대상 (현재 Stateless 단일 체인)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/in/web/MemberController.java` — `login()` 재작성 대상
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/ApplicationServiceConfig.java` — `TokenIssuer` 제거 후 재작성 대상
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/security/JwtVerifier.java` — HMAC → RSA/JWKS 재작성 대상
- `services/apis/api-gateway/src/main/java/com/econo/auth/gateway/filter/JwtCookieToPassportFilter.java` — 쿠키 → Bearer 재작성 대상
- `services/libs/auth-core/src/main/java/com/econo/auth/core/member/application/port/in/LoginUseCase.java` — `LoginResult.jwtToken` 제거 검토 대상
- `services/libs/auth-infra/src/main/resources/db/migration/V1__create_members_table.sql` — 버전 V1 점유 확인 (SAS 스키마는 V2 이후 사용)
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java` — 게이트웨이가 SAS 토큰 클레임으로 Passport 구성하는 매핑 확인
- Spring Authorization Server 공식 스키마 DDL (GitHub: spring-projects/spring-authorization-server)
- Spring Authorization Server 1.x 레퍼런스 문서 (https://docs.spring.io/spring-authorization-server/reference/)
