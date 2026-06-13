# CLAUDE.md

이 파일은 Claude Code(claude.ai/code)가 이 리포지토리에서 작업할 때 필요한 가이드를 제공합니다.

## 프로젝트 개요

`auth-common`은 ECONO 마이크로서비스 생태계의 **통합 인증 인프라**입니다. Gradle 멀티모듈 모노레포로, OIDC Authorization Server(`auth-api`)와 API Gateway(`api-gateway`)를 한 레포에서 관리하며, 다른 서비스들이 의존하는 인증 공유 라이브러리를 함께 제공합니다.

### 기술 스택

| 영역 | 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.2.2 |
| Authorization Server | Spring Authorization Server 1.x (SAS) — OIDC |
| Gateway | Spring Cloud Gateway (WebFlux) |
| Database | PostgreSQL + Spring Data JPA, Flyway 마이그레이션 |
| Session | 표준 서블릿 HttpSession |
| Build | Gradle (Kotlin DSL), 멀티모듈 |
| 포맷 | Spotless + Google Java Format 1.17.0 |
| 테스트 | JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers |
| 배포 | Jib(OCI 이미지) + Docker Hub, docker-compose |

> `Passport`/`@PassportAuth`는 이 레포에 없습니다. **독립 레포 `JNU-econovation/econo-passport`로 분리**되어 JitPack 의존성(`com.github.JNU-econovation:econo-passport:1.0.3`)으로 소비됩니다.

## 프로젝트 문서 구조

| 문서 | 위치 | 용도 |
|---|---|---|
| **CLAUDE.md** | `/CLAUDE.md` | 프로젝트 개요, 아키텍처, Claude 자동 참조 규칙 |
| **아키텍처** | `/docs/ARCHITECTURE.md` | 모듈 구조, 계층 설계, 인증 흐름, 설계 결정, 에러 코드 체계 |
| **코드 컨벤션** | `/docs/CONVENTION.md` | 네이밍, 스타일, 예외, Javadoc, 테스트 패턴 |
| **시퀀스 다이어그램** | `/docs/SEQUENCE-DIAGRAMS.md` | WEB/APP 로그인 등 인증 흐름 시퀀스 |
| **Passport 클레임** | `/docs/passport-claims-reference.md` | 내부 서비스가 받는 회원 정보(클레임) 완전 명세 |
| **인프라 의존성** | `/docs/INFRASTRUCTURE.md` | DB, Flyway, 향후 캐시·MQ 등 외부 인프라 정의 |
| **기능 가이드** | `/docs/FEATURES.md` | 인증 인프라가 제공하는 기능 정리 |
| **클라이언트 등록** | `/docs/CLIENT_REGISTRATION.md` | OAuth 클라이언트 셀프/어드민 등록 가이드 |
| **동적 라우팅** | `/docs/DYNAMIC_ROUTING.md` | `service_route` 기반 Gateway 동적 라우팅 가이드 |
| **문서 가이드** | `/docs/DOC-GUIDE.md` | 문서 유형·경로·작성 규칙 (`docs-writer`/`doc-reviewer`가 참조) |
| **README 작성 가이드** | `/docs/README-GUIDE.md` | README 필수/금지 섹션, 작성·갱신 시점 |
| **ADR** | `/docs/adr/*.md` | Architecture Decision Records (아래 목록 참조) |
| **outcomes** | `/docs/outcomes/*.md` | 인증 인프라가 결국 제공하는 것 |
| **도메인별 README** | `/services/libs/*/README.md`, `/services/apis/*/README.md` | 각 모듈 상세 (비즈니스 규칙 + 기술 참조) |

### ADR 목록 (`docs/adr/`)

- `0001` — 쿠키 기반 SSO (PKCE 대신 HttpOnly 쿠키 채택)
- `0002` — api-gateway를 인증 경계로 사용
- `0003` — auth-common-lib를 독립 레포(econo-passport)로 분리
- `0004` — RS256 JWT에 Passport 클레임 포함
- `0005` — 정적 YAML 라우팅 채택 (동적 DB 폴링 제거)
- `0007` — X-User-Passport 헤더로 서비스 간 사용자 정보 전달
- `0009` — 회원 일괄 조회 API: POST /batch + body 방식
- `0010` — Admin API 인증을 Internal API Key → ClientSecret Self-Service로 전환
- `0011` — 어드민 UI 인증: JWT Passport ADMIN role 체계 도입
- `0012` — 로그인 성공 후 리다이렉트를 백엔드가 clientId로 결정
- `0013` — 클라이언트 등록을 Passport 회원 셀프서비스 모델로
- `0014` — DB 마이그레이션을 모듈 밖으로 전역화하고 flyway 컨테이너로 적용
- `role-management.md` — 역할 관리 결정 노트

> ADR 번호 0006·0008은 결번. 정적 라우팅(ADR-0005)과 동적 라우팅 가이드(`DYNAMIC_ROUTING.md`)는 방향이 상충하므로, 라우팅 작업 시 어느 쪽이 현재 결정인지 먼저 확인할 것.

### outcomes 목록 (`docs/outcomes/`)

- `README.md` — outcomes 문서 모음 개요
- `01-auth-capabilities.md` — 인증 시스템이 제공하는 것
- `02-integrating-new-service.md` — 새 서비스를 연동하면 얻는 것
- `03-frontend-integration.md` — 프론트엔드가 얻는 것

## 모노레포 구조

### 애플리케이션 (`services/apis/` — 배포 단위)

- **api-gateway** — 클라이언트 요청 수신, Bearer JWT를 RS256 검증 → `Passport`로 변환 → `X-User-Passport` 헤더로 다운스트림 전달. SAS OAuth 엔드포인트는 auth-api로 프록시. (WebFlux 스택)
- **auth-api** — SAS 1.x 기반 OIDC Authorization Server. 회원 가입·로그인·로그아웃, OAuth 클라이언트 셀프/어드민 등록. (Spring MVC 스택)

### 공유 라이브러리 (`services/libs/`)

- **member** — Member 도메인·유스케이스·JPA 어댑터·BCrypt 어댑터 (3계층)
- **service-client** — OAuth 클라이언트(ServiceClient)·라우팅(ServiceRoute) 도메인·등록 유스케이스·JPA/SAS 어댑터 (3계층)
- **common-infra** — `@EnableJpaAuditing` AutoConfiguration을 `member`·`service-client`에 일원화 제공

각 모듈의 역할과 상세는 해당 `services/{libs|apis}/{모듈}/README.md`를 참조한다.

### DB 마이그레이션 (전역 관리)

Flyway SQL은 어느 모듈에도 속하지 않고 레포 루트 **`db/migration`**이 단일 소스다. 운영은 전용 flyway 컨테이너(`db/Dockerfile` 이미지)가, 로컬은 공식 이미지+볼륨이, 테스트는 클래스패스 복사로 같은 SQL을 적용한다. `auth-api`는 마이그레이션을 적용하지 않고(`spring.flyway.enabled=false`) `ddl-auto=validate`로 검증만 한다. 상세·근거: `docs/INFRASTRUCTURE.md`, ADR-0014.

### 모듈 의존성

```
api-gateway    ──→ econo-passport (JitPack)
auth-api       ──→ member, service-client, econo-passport
member         ──→ common-infra   (api 의존: JPA Auditing 전이)
service-client ──→ common-infra
```

> 개발 명령어: 아래 **빌드 / 테스트 / 포맷** | 코드 컨벤션: `/docs/CONVENTION.md`

## 빌드 / 테스트 / 포맷

```bash
./gradlew build                 # 전체 빌드 (spotlessCheck + test 포함, pre-commit 훅도 설치됨)
./gradlew check                 # spotlessCheck + test
./gradlew format                # Spotless 포맷 적용 (= spotlessApply)
./gradlew spotlessCheck         # 포맷 검사 (커밋 전 통과 필수)

# 단일 모듈 / 단일 테스트
./gradlew :services:apis:auth-api:test
./gradlew :services:apis:auth-api:test --tests "com.econo.auth.api.application.service.LoginRedirectResolverTest"
./gradlew :services:libs:member:test --tests "*.MemberTest.createWithNullRoles"
```

- 들여쓰기는 **탭(2칸 너비)**. 수동 정렬 금지 — `./gradlew format`으로 처리한다.
- `./gradlew build` 시 `.git/hooks/pre-commit`에 Spotless 검사 훅이 자동 설치된다.
- **통합 테스트 env**: `auth-api`의 `@SpringBootTest`는 RSA 키와 DB·issuer 환경변수가 있어야 컨텍스트가 뜬다. 로컬에서 컨텍스트 로딩으로 깨지면 `RSA_PRIVATE_KEY`/`RSA_PUBLIC_KEY`, `DB_URL`, `AUTH_ISSUER_URI`, `AUTH_JWKS_URI`, `FRONTEND_LOGIN_URL`, `CORS_ALLOWED_ORIGINS` 누락을 먼저 의심한다(설정 예시는 `.github/workflows/ci.yml`). `@DataJpaTest`는 Testcontainers PostgreSQL을 쓰므로 Docker 데몬이 필요하다.

## 아키텍처 패턴

### 3계층 + 계층별 DIP

전체 모듈은 `presentation` → `application` → `persistence` 3계층이며, 계층 경계마다 인터페이스로 의존성을 역전한다.
자세한 규칙은 `/docs/ARCHITECTURE.md`를 참조한다.

### 라이브러리 AutoConfiguration 자기 스캔

라이브러리 모듈은 Spring Boot AutoConfiguration으로 자기 빈을 등록한다(`MemberAutoConfiguration`, `ServiceClientAutoConfiguration`이 각자 `@ComponentScan` + `@EnableJpaRepositories`(`persistence.repository`) + `@EntityScan`(`persistence.entity`)으로 자신의 패키지를 스캔). 따라서:

- 소비자(`auth-api`)는 별도 컴포넌트 스캔 없이 라이브러리 빈을 활성화한다.
- **JPA 패키지를 다른 AutoConfiguration에서 중복 선언하면 빈 충돌**이 난다. 새 엔티티는 해당 모듈의 `persistence/entity`, 리포지토리는 `persistence/repository` 안에 둔다.
- `common-infra`는 `@EnableJpaAuditing`만 제공하며, `member`가 `api` 의존으로 끌어와 소비자에 전이한다.

### 인증 흐름 (상세: `docs/ARCHITECTURE.md`, `docs/SEQUENCE-DIAGRAMS.md`)

- **경로 A — JSON 로그인** (`POST /api/v1/auth/login`): `JsonLoginAuthenticationFilter`가 자격증명을 받아 AT/RT JWT를 직접 발급. WEB은 쿠키 세팅 후 `clientId` 등록 redirect_uri로 302, APP(`Client-Type: APP`)은 200 + body. (ADR-0012)
- **경로 B — OAuth2 Authorization Code + PKCE** (`/oauth2/authorize` → `/oauth2/token`): SAS 표준 흐름. 미인증 진입 시 `auth.frontend-login-url`로 302.
- **Gateway 변환**: `BearerToPassportFilter`가 Bearer JWT를 JWKS(RS256) 검증 → `PassportBuilder`가 클레임을 `Passport`로 빌드 → Base64 → `X-User-Passport` 주입. 라우팅의 진실은 yml이 아니라 `GatewayRoutingConfig`(`RouteLocator` 빈)에 있다.

## Claude 자동 참조 규칙

도메인 작업 시, 횡단 흐름·설계 배경은 `docs/`에서, 모듈 내부 구현(포트/어댑터·엔드포인트)은 해당 모듈 README에서 먼저 읽는다:

| 작업 영역 | 먼저 읽을 문서 |
|---|---|
| 인증/로그인 흐름 | `docs/ARCHITECTURE.md`, `docs/SEQUENCE-DIAGRAMS.md` |
| Passport / 클레임 | `docs/passport-claims-reference.md` |
| OAuth 클라이언트 등록 | `docs/CLIENT_REGISTRATION.md` |
| Gateway 라우팅 | `docs/DYNAMIC_ROUTING.md` (+ ADR-0005) |
| 역할/권한 | `docs/features/role-management.md` |
| 인프라(DB·마이그레이션) | `docs/INFRASTRUCTURE.md` |
| 설계 의사결정 배경 | `docs/adr/*.md` |
| 모듈 내부 세부 (포트/어댑터·에러 코드) | `services/libs/{모듈}/README.md` |
| 앱 내부 세부 (엔드포인트·인증·횡단 관심사) | `services/apis/{앱}/README.md` |

복수 도메인에 걸친 작업 시 관련 문서와 모듈 README를 모두 읽는다.

### 자동 참조 규칙 적용

질문을 받으면 기본적으로 다음 순서로 진행:

1. 질문 유형 분석 (인증/로그인, 회원, OAuth 클라이언트, 라우팅 등)
2. 해당 도메인의 `docs/` 문서 + 모듈 README 읽기 (우선)
3. 실제 구현 코드 분석
4. 답변 제공

**문서 건너뛰기 조건:**

- 매우 간단한 질문 (단일 파일 읽기, 특정 함수 확인 등)
- 긴급한 디버깅이 필요한 경우
- 구체적인 파일 경로가 제공된 경우
- 사용자가 명시적으로 빠른 답변을 요청한 경우

**반드시 문서를 읽어야 하는 경우:**

- 새로운 기능 개발 계획
- 도메인 전반적인 이해가 필요한 경우
- 모듈 간 관계 파악이 필요한 경우
- 아키텍처 패턴 확인이 필요한 경우

코드를 새로 작성하거나 수정할 때는 `/docs/CONVENTION.md`의 해당 섹션을 참조한다(네이밍, Controller/Service/DTO, 예외 처리, JPA/트랜잭션, 테스트, 의존성 선언 규칙).

## 중요 지침

코드나 파일을 생성하지 마세요. 요청받은 것만 수행하세요.
기존 파일 편집을 새 파일 생성보다 항상 선호하세요.
사용자가 명시적으로 요청하지 않는 한 문서 파일(\*.md)이나 README 파일을 사전에 생성하지 마세요.

---

## 문서 자동 동기화 (Doc Sync)

코드 변경 시 관련 문서를 반드시 업데이트합니다.

`docs/DOC-GUIDE.md`에 문서 매핑 규칙, 작성 규칙, 리뷰 기준이 정의되어 있습니다. `docs-writer`·`doc-reviewer` 에이전트가 이 파일을 자동으로 참조합니다. 수동 실행이 필요한 경우 `/docs` 커맨드를 사용합니다.

---

## 개발 워크플로우 (`.claude/` 하네스)

이 프로젝트는 `.claude/`의 커맨드·에이전트로 구성된 **plan → develop** TDD 프로세스를 따릅니다.

### 워크플로우

```
Phase 1 (설계): /plan [기능 설명]
  → 명확화 Q&A → todo + api/구현/db 설계 4종 문서를 .claude/plans/[작업명]/ 에 생성
Phase 2 (구현): /develop [작업명]
  → test → implement → code-review → document → doc-review 5단계 사이클
```

기능 정책 우선 개발은 `/new-feature`, 기술 결정 기록은 `/adr`, 새 서비스 연동은 `register-service` 스킬을 사용합니다.

### 핵심 규칙

1. **플래너 우선** — `/plan`으로 todo 작성 + 사용자 컨펌 후 구현 진입
2. **질문 필수** — 모호한 요구사항은 `/plan`의 명확화 게이트에서 질문으로 해소
3. **TDD 원칙** — 테스트 먼저(Red), 구현은 나중
4. **CONVENTION 준수** — 네이밍, 예외(정적 팩토리), 에러 코드, 테스트 규칙 준수
5. **문서화 필수** — 코드 변경 시 관련 문서(`docs/`, 모듈 README) 반드시 업데이트
6. **순서 준수** — 워크플로우 단계 순서대로 진행 (건너뛰기 금지)
