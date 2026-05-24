# Infrastructure

auth-common 프로젝트가 의존하는 외부 인프라(데이터베이스, 마이그레이션 도구, 향후 도입될 캐시·메시지 큐·외부 서비스)를 정의한다.

> **위치 원칙**: 코드 빌드/실행 환경은 `docs/ARCHITECTURE.md`의 기술 스택 섹션이, 모듈별 인프라 사용 방식은 각 모듈 README가, 인프라 자체의 종류·버전·접근 방식은 본 문서가 다룬다.

---

## 인프라 구성 요소

| 구성 요소 | 종류 | 도입 여부 | 사용 모듈 |
|---|---|---|---|
| PostgreSQL | RDB | 도입 완료 (`member-auth` 작업) | `auth-infra` (`auth-api` 통해 연결) |
| Flyway | DB 마이그레이션 | 도입 완료 (`member-auth` 작업) | `auth-infra` |

> 이 표는 작업이 진행되며 확장된다.

---

## 데이터베이스 — PostgreSQL

| 항목 | 값 |
|---|---|
| 종류 | RDB (PostgreSQL) |
| 버전 | (확인 필요 — 운영 정책에 따라 확정. 16+ 권장) |
| 사용 모듈 | `auth-infra` (JPA Entity), `auth-api` (실행 시 연결) |
| JDBC 드라이버 | `org.postgresql:postgresql` (`runtimeOnly`) |
| 마이그레이션 | Flyway (`org.flywaydb:flyway-core`), V1: `members` 테이블 생성 완료 |

### 선택 근거

- 오픈소스 표준 RDB로, Spring Boot 생태계와의 통합이 안정적이다.
- `BIGINT GENERATED ALWAYS AS IDENTITY` 등 표준 SQL을 잘 지원해 마이그레이션 스크립트가 깔끔하다 — `Passport.memberId`(`Long`)와 PK 타입이 자연스럽게 일치한다.
- Testcontainers 공식 PostgreSQL 이미지가 제공되어 통합 테스트 환경을 일관되게 유지할 수 있다.

### 스키마 컨벤션

- **테이블명**: 복수형 snake_case (`members`, 향후 `oauth_clients` 등)
- **PK**: `BIGINT GENERATED ALWAYS AS IDENTITY` (PostgreSQL 10+ 표준 SQL)
- **컬럼명**: snake_case (JPA 기본 물리 네이밍 전략 준수)
- **타임스탬프**: `TIMESTAMPTZ` (timezone-aware)
- **민감 데이터**: 비밀번호 등은 단방향 해시(BCrypt 등)로 저장하며 평문 저장 금지
- **상태 컬럼**: enum 값은 `VARCHAR` + `CHECK` 제약조건으로 강제 (예: `members.status` → `CHECK (status IN ('AM', 'RM', 'CM', 'OB'))`) — 잘못된 값 유입 차단, 향후 확장 시 ALTER 비용 절감

---

## 마이그레이션 — Flyway

| 항목 | 값 |
|---|---|
| 위치 | `services/libs/auth-infra/src/main/resources/db/migration/` |
| 파일명 컨벤션 | `V{version}__{description}.sql` (대문자 V, 숫자 버전, 언더스코어 두 개, snake_case 설명) |
| 자동 실행 | 애플리케이션 기동 시 (`spring.flyway.enabled=true`) |
| DDL 검증 | `spring.jpa.hibernate.ddl-auto=validate` (Hibernate는 스키마를 수정하지 않고 일치 여부만 확인) |

### 버전 점유 규칙

- 모듈/도메인별 버전 충돌을 피하기 위해 **PR 머지 순서대로 다음 번호**를 사용한다.
- 첫 마이그레이션 예: `V1__create_members_table.sql` (`member-auth`)
- 한 PR에 여러 마이그레이션이 필요하면 `V1__...sql`, `V2__...sql` 처럼 PR 안에서 순차 번호를 부여한다.

---

## 환경 변수

| 변수 | 용도 | 사용 서버 | 필수 |
|---|---|---|---|
| `DB_URL` | JDBC 접속 URL (예: `jdbc:postgresql://host:5432/auth`) | `auth-api` | Y |
| `DB_USERNAME` | DB 사용자명 | `auth-api` | Y |
| `DB_PASSWORD` | DB 비밀번호 | `auth-api` | Y |
| `JWT_SECRET` | JWT 서명 비밀키 (최소 256bit). **양 서버에서 동일 값을 사용해야 한다.** | `auth-api`, `api-gateway` | Y |
| `AUTH_API_URI` | Gateway가 `auth-api`로 라우팅하는 URI (예: `http://localhost:8081`) | `api-gateway` | Y |

> 로컬 개발용 기본값은 `application-local.yml` 또는 `.env.example`에 정리하며, 실제 비밀키·비밀번호는 커밋 금지.

---

## 테스트 환경

- **통합 테스트**: Testcontainers의 PostgreSQL 이미지를 사용한다 (`org.testcontainers:postgresql`).
- **컨텍스트 주입**: `@DynamicPropertySource`로 컨테이너의 JDBC URL을 Spring에 주입한다.
- **Flyway**: 테스트에서도 자동 실행되어 스키마를 운영과 동일하게 유지한다.
- **공통 설정**: 현재 각 모듈(`auth-infra`)에 독립적으로 Testcontainer 설정을 보유한다. 여러 모듈 공유가 필요한 경우 Gradle `testFixtures` 방식 도입을 검토한다.

---

## 향후 도입 예정

이 섹션은 작업이 진행되며 채워진다. 현재 알려진 후보:

- **이메일 발송 (SMTP / SES)** — 이메일 인증·비밀번호 재설정 작업에서 도입
- **OIDC 관련 저장소** — `oidc-idp` 작업에서 인가 코드·리프레시 토큰 저장
- **세션/캐시 (Redis 등)** — 필요성 발생 시 검토

---

## 참고

- `docs/ARCHITECTURE.md` — 모듈 구조, 기술 스택(빌드/실행 환경)
- `docs/CONVENTION.md` — 코드 컨벤션 (네이밍, 예외, 테스트)
- `services/libs/auth-infra/README.md` — `auth-infra` 모듈의 인프라 사용 방식
- `.claude/skills/jpa-patterns/SKILL.md` — JPA 엔티티 설계, Flyway 패턴
