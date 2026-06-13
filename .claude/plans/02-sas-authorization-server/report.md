# sas-authorization-server - report

## 메타
- **작업명**: sas-authorization-server
- **작성일**: 2026-05-25
- **브랜치**: feat/member-auth (member-auth 위에 이어서 진행)
- **plan 문서**: todo.md / api-design-plan.md / implementation-plan.md / db-design-plan.md

## 개요
기존 member-auth(자체 JWT 발급 + HttpOnly 쿠키 + Gateway HMAC 검증)를 **Spring Authorization Server(SAS 1.x) 기반 OIDC Authorization Server(IdP)**로 전환했다. 토큰 발급 권위를 SAS로 일원화(Model A)하고, auth-api를 헤드리스 OIDC AS로, api-gateway를 oauth2-resource-server(JWKS) 기반 Passport 변환기로 재작성했다. 증분 [1](auth-api SAS 코어) + [2](api-gateway JWKS)를 한 사이클로 진행.

## 진행 결과

### 1. test
- 신규/재작성 6개: api-gateway `BearerToPassportFilterTest`·`JwtVerifierTest`(재작성)·`PassportBuilderTest`, auth-api `MemberControllerTest`(재작성)·`AuthApiIntegrationTest`(재작성)·`SasAuthorizationServerIntegrationTest`(신규)
- 폐기 5개 삭제: `JwtTokenIssuerAdapterTest`, `LoginServiceTest`, 구 `JwtCookieToPassportFilterTest`·`JwtVerifierTest`(HMAC)·`PassportSerializerTest`
- Red 확인: api-gateway 컴파일 Red, auth-api `MemberControllerTest` 12/12 실패. 기존 단위(auth-core·auth-common-lib) 통과 유지.

### 2. implementation
- **auth-core**: `TokenIssuer`/`LoginUseCase`/`LoginService` 제거
- **auth-infra**: `JwtTokenIssuerAdapter` 제거, jjwt 의존성 제거
- **auth-api**: SAS 설정·보안 신규 — `AuthorizationServerConfig`·`RegisteredClientConfig`·`OAuth2AuthorizationServiceConfig`·`RsaKeyConfig`·`PassportTokenCustomizer`·`MemberUserDetailsService`·`MemberUserDetails`·`JsonLoginAuthenticationFilter`. `SecurityConfig`·`MemberController`·`GlobalExceptionHandler`·`ApplicationServiceConfig` 재작성. `LoginRequest`·`JwtCookieProperties` 제거. SAS+spring-session-jdbc 의존성 추가.
- **api-gateway**: `BearerToPassportFilter`(쿠키 필터 대체)·`GatewaySecurityConfig` 신규. `JwtVerifier`(JWKS+iss 검증)·`PassportBuilder`(Jwt 타입)·`GatewayRoutingConfig` 재작성. oauth2-resource-server 교체.
- **DB**: Flyway `V2`(oauth2_registered_client/authorization/authorization_consent), `V3`(SPRING_SESSION/SPRING_SESSION_ATTRIBUTES). members 무변경.
- 검증: 전체 클린 컴파일 성공. 실행 가능 테스트(auth-core·auth-common-lib·api-gateway·auth-api @WebMvcTest) Green. 통합 테스트(Testcontainers)는 **Docker 미가용으로 컴파일까지만 확인, 실행은 CI 위임**.

### 3. code-review
- 반영 권장 9개(치명 2·주요 4·경미 3) + 트리비얼 2개 **전부 반영**.
  - 치명: RegisteredClient 고정 UUID(멱등), RSA 고정 kid(재기동 시 토큰 검증 보장)
  - 주요: `fieldErrors` JSON null, `@Autowired`→`@RequiredArgsConstructor`, GatewaySecurityConfig 빈 중복 방지 명시, `sub`/`memberId` 정합(NumberFormatException 방지)
  - 경미: clientName 설정, CORS 경로별 제한, MemberControllerTest false-green 정리
- 재검증: 클린 컴파일 + 실행 가능 테스트 Green 유지.

### 4. docs
- 갱신 7개: `docs/ARCHITECTURE.md`, `docs/INFRASTRUCTURE.md`, 루트 `README.md`, 모듈 README 4종(api-gateway·auth-api·auth-core·auth-infra). (CONVENTION.md는 docs 단계엔 불변, 문서리뷰에서 추가 갱신)

### 5. doc-review
- 반영 권장 5개(치명 1·주요 2·경미 2) + 참고 3개.
  - 치명 #1(JwtVerifier가 iss를 실제로 검증하지 않음 — 문서와 불일치): **문서 약화 대신 코드에 iss 검증을 추가**(`JwtValidators.createDefaultWithIssuer`)하여 plan 보안 의도 충족. JwtVerifierTest에 iss 불일치 거부 케이스 추가. api-gateway에 `AUTH_ISSUER_URI` 주입.
  - 주요/경미: auth-infra README stale "JWT 발급/검증" 제거, INFRASTRUCTURE 환경변수 표 보완(`FIRST_PARTY_CLIENT_ID`·`FIRST_PARTY_REDIRECT_URI` 추가, `AUTH_ISSUER_URI` 양 서버로 갱신), CONVENTION.md `@DataJpaTest+Testcontainers` 행 추가, 루트 README `## 개요` 헤딩 추가.
- 참고 3개는 선택사항으로 제외.

## 변경 요약
- **신규 main 소스 9개**(auth-api 8 + api-gateway 1) + Flyway 2개 + 신규 테스트 3개
- **수정**: 코드/설정 + 문서 다수, **삭제 11개**(폐기 클래스·테스트)
- **갱신 docs 8개**: ARCHITECTURE, INFRASTRUCTURE, CONVENTION, 루트 README, 모듈 README 4종

## plan과의 차이
- `GatewaySecurityConfig`(신규) — plan에 없었으나 WebFlux+oauth2-resource-server의 `JwtVerifier` 빈 단일 소스 제공용. 타당(code-review 확인).
- `GlobalExceptionHandler` `instanceof ErrorResponse` 패턴 — Spring 6.1에서 `NoResourceFoundException`이 `ResponseStatusException`을 더 이상 상속하지 않는 것에 대응.
- **iss 검증 추가** — 초기 구현이 서명·만료만 검증(iss 누락)했던 것을 문서리뷰에서 발견, plan 의도대로 코드에 추가.

## 환경 이슈 (기록)
- 프로젝트가 iCloud Drive 경로에 있어, 에이전트의 파일 대량 생성 시 `Foo 2.java`/`Foo 2.class` 충돌 복사본이 생겨 "duplicate class"·"테스트 클래스 실행 실패"를 반복 유발. 매 단계 후 소스 중복 `rm` + 필요 시 `./gradlew clean`으로 .class 중복 제거하여 해소. (메모리 기록 완료)

## 다음 단계
- `/commit` 으로 커밋
- `/git-pr` 로 PR 생성 (base: 향후 main 또는 stacked)
- **운영 전 필수**: Docker 환경(CI)에서 Testcontainers 통합 테스트(SAS E2E·AuthApiIntegrationTest·MemberRepositoryAdapterTest) 실행하여 Green 확인.
