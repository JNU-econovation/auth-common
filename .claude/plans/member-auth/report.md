# member-auth 개발 보고서

> `/develop member-auth` 실행 결과. 5단계 TDD 사이클(테스트 → 구현 → 코드리뷰 → 문서화 → 문서리뷰) 완료.
> 작성일: 2026-05-24

---

## 1. 작업 개요

회원 인증(member-auth) 기능을 헥사고날 아키텍처로 구현했다. 회원가입·로그인·로그아웃 3개 엔드포인트를 제공하며, 인증 성공 시 JWT를 HttpOnly 쿠키로 발급하고 API Gateway가 이를 검증해 `Passport`로 변환·전파한다.

### 핵심 결정
- **인증 방식**: JWT (jjwt 0.12.x, HMAC-SHA256). SSO를 위해 쿠키에 토큰을 담되, 서버 부담을 줄이기 위해 세션 대신 JWT 채택.
- **쿠키 정책**: HttpOnly, `SameSite=Strict`, `Secure`, `Path=/`.
- **비밀번호**: BCrypt(cost 12, spring-security-crypto) 단방향 해시.
- **DB**: PostgreSQL + Flyway 마이그레이션, Testcontainers 통합 테스트.
- **Passport 확장**: `email` → `loginId` 필드명 변경 + `generation`(Integer)·`status`(String) 추가 (auth-common-lib 변경 포함).

### Member 도메인
| 필드 | 타입 | 제약 |
|---|---|---|
| name | String | 이름 |
| loginId | String | 3~19자, `^[a-zA-Z0-9\-_.]{3,19}$`, UNIQUE |
| password | String | 8~19자, 대문자+소문자+숫자+특수기호 모두 필수 (해시 저장) |
| generation | Integer | 1~99 (기수) |
| status | MemberStatus | AM / RM / CM / OB (가입 시 사용자 직접 입력) |

---

## 2. 단계별 진행 결과

| 단계 | 에이전트 | 결과 |
|---|---|---|
| 1. 테스트 작성 | test-writer | 도메인·유스케이스·어댑터·웹·통합 테스트 작성 (Red) |
| 2. 구현 | implementer | plan대로 구현, 전체 테스트 통과 (Green) |
| 3. 코드리뷰 | code-reviewer | 9개 항목(치명 2·주요 4·경미 3) 도출 → 전부 반영 |
| 4. 문서화 | docs-writer | 9개 문서 갱신 (루트 README, docs 3종, 모듈 README 5종) |
| 5. 문서리뷰 | doc-reviewer | 반영 권장 7개 도출 → 전부 반영 (4개 문서) |

### 코드리뷰 반영 내역 (9건)
- **치명**: ① api-gateway `application.yml`의 `jwt.secret` 평문 블록 제거(환경변수화), ② auth-core 유스케이스에서 `@Service` 제거 → auth-api `ApplicationServiceConfig`에서 빈 등록(헥사고날 순수성).
- **주요**: ③ Gateway 필터에 `GatewayRoutingConfig` 주입(허용 경로 일원화), ④ permittedPaths에 logout 포함, ⑤ `ExpiredJwtException`/`JwtException` 로깅 분리, ⑥ `InfraConfig` 신설(`@EnableJpaRepositories`+`@EntityScan`).
- **경미**: ⑦ `GlobalExceptionHandler`에 `IllegalArgumentException` → 400 `INVALID_LOGIN_ID_FORMAT` 추가, ⑧ `JpaAuditingConfig` 중복 `@EnableJpaAuditing` 정리, ⑨ 빌드 `-parameters` 컴파일 옵션 추가.

### 문서리뷰 반영 내역 (7건)
- `README.md`: `hasAllRoles` 예시 stale 값 `"ACTIVE"` → `"ADMIN"`.
- `docs/ARCHITECTURE.md`: auth-api 역할 "토큰 갱신"(미구현) → "로그아웃", 테스트 구조에 auth-api·api-gateway 트리 추가, 트리 ASCII 정정, `VALIDATION_FAILED` 귀속을 auth-api 웹 레이어로 정정.
- `auth-core/README.md`: `INVALID_LOGIN_ID_FORMAT` 변환 흐름 노트 추가.
- `auth-api/README.md`: 헥사고날 구조 도메인 README 공백 보완 노트.

---

## 3. 변경 파일

### 신규 (46)
- **auth-core (15)**: `domain/Member`·`MemberStatus`, `port/in/{Signup,Login}UseCase`, `port/out/{MemberRepository,PasswordHasher,TokenIssuer}`, `usecase/{Signup,Login}Service`, `exception/{MemberAlreadyExists,InvalidCredentials,InvalidPasswordPolicy}Exception` + 테스트 3.
- **auth-infra (12)**: `config/{Infra,JpaAuditing}Config`, `member/adapter/out/persistence/{MemberJpaEntity,MemberJpaRepository,MemberRepositoryAdapter}`, `.../security/BCryptPasswordHasherAdapter`, `.../token/JwtTokenIssuerAdapter`, `db/migration/V1__create_members_table.sql` + 테스트 3 + `TestInfraApplication`.
- **auth-api (11)**: `adapter/in/web/{MemberController,SignupRequest,LoginRequest}`, `config/{ApplicationServiceConfig,JwtCookieProperties,SecurityConfig}`, `exception/GlobalExceptionHandler`, `application.yml` + 테스트 2 + `application-test.yml`.
- **api-gateway (8)**: `config/GatewayRoutingConfig`, `filter/JwtCookieToPassportFilter`, `security/{JwtVerifier,PassportBuilder}`, `application.yml` + 테스트 3.

### 수정 (19)
- **코드**: `auth-common-lib/Passport.java`(+테스트 3), `AuthApiApplication.java`, 루트 `build.gradle.kts`, `auth-api`·`api-gateway`·`auth-infra` `build.gradle.kts`.
- **문서**: 루트 `README.md`, `docs/{ARCHITECTURE,CONVENTION,INFRASTRUCTURE}.md`, 모듈 README 5종, `plans/member-auth/todo.md`.

---

## 4. 테스트 결과

- 구현·코드리뷰 반영 완료 시점 기준 **전체 통과** (마지막 측정 169개). 5단계는 문서만 변경되어 테스트 영향 없음.
- 레이어별: 도메인 단위(MemberTest), 유스케이스(Signup/LoginServiceTest), 어댑터(`@DataJpaTest`+Testcontainer, BCrypt, JWT), 웹(`@WebMvcTest` MemberControllerTest), 통합(`@SpringBootTest`+Testcontainer AuthApiIntegrationTest), Gateway 필터·검증.

---

## 5. 잔여 / 후속 사항

- **미커밋**: 1~5단계의 모든 구현·문서 변경이 워킹 트리에 남아 있다. `/commit` → `/git-pr`로 커밋·PR 진행 필요 (브랜치: `feat/member-auth`).
- **범위 외(향후 작업)**: 토큰 갱신(refresh), 이메일 인증, 비밀번호 재설정, OIDC.
- **확인 권장**: PostgreSQL 운영 버전 확정(현재 16+ 권장), 운영 환경변수(`DB_*`, `JWT_SECRET`, `AUTH_API_URI`) 주입 체계.
