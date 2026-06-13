# member-auth - db-design

## 메타
- **작업명**: member-auth
- **문서 타입**: db-design
- **작성일**: 2026-05-10
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

---

## 개요

이 문서는 ECONO 자체 회원 도메인 최초 도입을 위한 `members` 테이블 신규 생성을 다룬다.
loginId/비밀번호 기반 가입·로그인을 지원하며, 향후 OIDC IdP 작업 시 이 테이블이 end-user pool의 토대가 된다.
사용 DB는 **PostgreSQL**, 마이그레이션 도구는 **Flyway**이며 스크립트는
`services/libs/auth-infra/src/main/resources/db/migration/` 에 위치한다.

---

## 본문

### 신규 테이블 / 컬렉션

#### `members`

- **목적**: ECONO 자체 회원 정보(이름, loginId, 비밀번호 해시, 기수, 활동 상태)를 저장하는 핵심 테이블
- **연관 todo**:
  - `[ ] members 테이블 마이그레이션 스크립트 작성 (V1__create_members_table.sql)`
  - `[ ] members.login_id 인덱스 확인`
  - `[ ] MemberJpaEntity 구현`

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | FK / 참조 | 비고 |
|------|------|----------|--------|----|-----------|------|
| `id` | `BIGINT` | Y | `GENERATED ALWAYS AS IDENTITY` | Y | | JPA `GenerationType.IDENTITY` 매핑. `Passport.memberId`가 `Long` 타입이므로 BIGINT 선택 |
| `name` | `VARCHAR(50)` | Y | | | | 회원 이름 (한글/영문, 1~50자). 길이 상한은 VARCHAR 선언으로 충분 |
| `login_id` | `VARCHAR(20)` | Y | | | | 로그인 식별자 (3~19자, 영숫자·`-_.`만 허용). UNIQUE 제약으로 중복 방지 |
| `hashed_password` | `VARCHAR(72)` | Y | | | | BCrypt 출력 결과 고정 60자이나, 알고리즘 버전 prefix(`$2b$`, `$2a$`) 포함 시 최대 60자. 72로 여유 확보 (향후 다른 해시 알고리즘으로 교체 가능성, cost factor 인코딩 변경 대비) |
| `generation` | `INTEGER` | Y | | | | 기수 (ECONO 회원). `CHECK (generation BETWEEN 1 AND 99)` 으로 1~99 범위 강제 |
| `status` | `VARCHAR(2)` | Y | | | | `MemberStatus` enum 문자열 저장: `AM`, `RM`, `CM`, `OB`. `CHECK` 제약으로 유효 값 강제 |
| `created_at` | `TIMESTAMPTZ` | Y | `NOW()` | | | timezone-aware 타임스탬프. JPA `@CreatedDate` + `AuditingEntityListener` 매핑 |

> **`name` / `login_id` 길이 검증 방식**
> 애플리케이션 레이어(Bean Validation, 도메인 검증)에서 길이를 이미 검증하므로
> DB 레벨에서는 VARCHAR 선언 길이(`50`, `20`)로 충분하다. 별도 CHECK 제약 불필요.

> **`hashed_password` 길이 선택 근거**
> BCrypt 표준 출력은 `$2a$12$<22자 salt><31자 hash>` 형태로 정확히 60자이다.
> `$2b$` prefix 등 변형 포함 시에도 60자 이내이나, 향후 Argon2id 등 다른 알고리즘으로 교체 시
> 인코딩 길이가 달라질 수 있으므로 72자로 여유를 두었다.
> todo에서도 동일하게 72자를 명시하고 있어 이를 따른다.

> **`updated_at` 미포함 근거**
> todo의 `Member` 도메인 객체는 불변 설계(`private final` 필드)를 명시하고 있으며,
> 상태 변경(비밀번호 재설정, 탈퇴 등)은 모두 별도 작업 범위이다.
> 이번 작업에서 UPDATE가 발생하는 흐름이 없으므로 `updated_at`은 제외한다.
> 향후 비밀번호 재설정 작업 시 `ADD COLUMN updated_at` 마이그레이션을 별도로 추가한다.

**제약조건**

- PK: `pk_members` on `(id)`
- 유니크: `uq_members_login_id` on `(login_id)` — loginId는 회원 식별자로 중복 불가, 로그인 조회(`findByLoginId`)의 인덱스도 겸함
- CHECK: `chk_members_status` — `status IN ('AM', 'RM', 'CM', 'OB')` — 유효하지 않은 status 값 삽입 방지. 향후 enum 값 추가 시 CHECK 제약 ALTER 필요하나, 잘못된 값 유입 차단 효과가 더 크다고 판단
- CHECK: `chk_members_generation` — `generation BETWEEN 1 AND 99` — 1~99 범위 외 기수 삽입 방지 (ECONO 기수 정책)

**인덱스**

- `uq_members_login_id` — `(login_id)` UNIQUE — 사유: `POST /api/v1/auth/login` 시 loginId로 단건 조회(`findByLoginId`)가 매 요청마다 발생하므로 필수. UNIQUE 제약조건이 내부적으로 B-Tree 인덱스를 생성하므로 별도 `CREATE INDEX` 불필요. Flyway 스크립트에서 인덱스 이름을 명시적으로 지정(`CONSTRAINT uq_members_login_id UNIQUE (login_id)`)해 JPA `@Table(uniqueConstraints=...)` 선언과 일치시킨다.

> **추가 인덱스 미생성 근거**
> `status` 컬럼 인덱스: 현재 이번 작업에서 상태별 목록 조회 기능이 없으며, 값이 4가지뿐이어서 선택도가 낮아 인덱스 효과 없음. 향후 상태별 목록 조회 기능이 생기면 그 시점에 추가한다.
> `generation` 컬럼 인덱스: 현재 기수별 조회 접근 패턴이 없으므로 미생성. 향후 기수별 조회 기능 도입 시 추가한다.

---

**DDL (실제 사용 가능한 PostgreSQL SQL)**

```sql
CREATE TABLE members (
  id              BIGINT       GENERATED ALWAYS AS IDENTITY,
  name            VARCHAR(50)  NOT NULL,
  login_id        VARCHAR(20)  NOT NULL,
  hashed_password VARCHAR(72)  NOT NULL,
  generation      INTEGER      NOT NULL,
  status          VARCHAR(2)   NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

  CONSTRAINT pk_members PRIMARY KEY (id),
  CONSTRAINT uq_members_login_id UNIQUE (login_id),
  CONSTRAINT chk_members_status CHECK (status IN ('AM', 'RM', 'CM', 'OB')),
  CONSTRAINT chk_members_generation CHECK (generation BETWEEN 1 AND 99)
);

COMMENT ON TABLE  members                  IS 'ECONO 회원 정보';
COMMENT ON COLUMN members.id              IS '회원 식별자 (PK, auto-increment)';
COMMENT ON COLUMN members.name            IS '회원 이름 (한글/영문, 1~50자)';
COMMENT ON COLUMN members.login_id        IS '로그인 식별자 (3~19자, 영숫자·-_·., UNIQUE)';
COMMENT ON COLUMN members.hashed_password IS 'BCrypt 단방향 해시값 (평문 저장 금지)';
COMMENT ON COLUMN members.generation      IS '기수 (ECONO 회원, 1~99)';
COMMENT ON COLUMN members.status          IS '활동 상태: AM | RM | CM | OB';
COMMENT ON COLUMN members.created_at      IS '계정 생성 시각 (timezone-aware)';
```

> `BIGINT GENERATED ALWAYS AS IDENTITY` 는 PostgreSQL 10+ 표준 SQL identity 구문으로,
> `BIGSERIAL`(shorthand)과 동일하게 동작하나 SQL 표준에 더 부합한다.
> Spring Data JPA `GenerationType.IDENTITY`와 호환된다.

---

### 기존 테이블 / 컬렉션 변경

없음 — 이번 작업은 신규 테이블 생성만 포함한다.

---

### 마이그레이션 순서

Flyway 컨벤션: `V{version}__{description}.sql` (대문자 V, 숫자 버전, 언더스코어 두 개, 스네이크 케이스 설명).
파일 위치: `services/libs/auth-infra/src/main/resources/db/migration/`

이번 작업은 단일 파일, 단일 단계로 완성된다.

| 단계 | 파일명 | 내용 | 롤백 가능 여부 | 위험도 |
|------|--------|------|----------------|--------|
| 1 | `V1__create_members_table.sql` | `members` 테이블 신규 생성 + UNIQUE 제약 + CHECK 제약 2개 | Flyway 미지원(수동 DROP TABLE 필요) | **낮음** — 신규 테이블이므로 기존 데이터 없음. 운영 락 없음 |

> **단일 단계로 처리 가능한 이유**
> - 신규 테이블 생성이므로 기존 데이터에 영향 없음
> - NOT NULL 컬럼에 DB 기본값이 있거나(`created_at DEFAULT NOW()`) 애플리케이션이 반드시 제공(`name`, `login_id`, `hashed_password`, `generation`, `status`)하므로 백필 불필요
> - UNIQUE 인덱스가 제약조건과 함께 생성되므로 별도 인덱스 빌드 단계 불필요

---

### 데이터 정합성 / 운영 고려사항

**기존 데이터 백필**
불필요. `members` 테이블이 신규 생성이므로 기존 row가 없다.

**NOT NULL 컬럼 처리**
모든 NOT NULL 컬럼은 아래 중 하나를 만족한다.
- DB 기본값 존재: `created_at DEFAULT NOW()`
- 애플리케이션 필수 제공: `name`, `login_id`, `hashed_password`, `generation`, `status` — `SignupUseCase`에서 검증 후 삽입

**인덱스 빌드 락**
`UNIQUE` 제약조건은 테이블 생성 시 동시에 생성되므로 별도 `CREATE INDEX CONCURRENTLY` 불필요.
(이미 운영 중인 대형 테이블이 아니므로 해당 없음)

**마이그레이션 도중 애플리케이션 호환성**
- `spring.jpa.hibernate.ddl-auto=validate` 설정으로 Hibernate가 스키마를 직접 수정하지 않음
- `spring.flyway.enabled=true` 설정으로 애플리케이션 기동 시 Flyway가 자동 실행
- 마이그레이션 전 구버전 코드가 없으므로(첫 구현) 호환성 이슈 없음

**향후 OIDC 작업과의 공존**
- PK `id BIGINT`는 향후 OIDC 관련 테이블(`authorization_codes`, `access_tokens` 등)이 `members.id`를 FK로 참조할 때 호환된다. `Passport.memberId`가 `Long` 타입이므로 일관성 유지.
- Flyway 버전 2번 이후(`V2__*`)는 OIDC 작업에서 사용. 본 작업은 `V1`만 점유.
- `status` CHECK 제약에 `AM`, `RM`, `CM`, `OB` 4가지 값을 선언해 DB 레벨 유효성을 보장한다. 향후 enum 값 추가 시 `ALTER TABLE members DROP CONSTRAINT chk_members_status; ALTER TABLE members ADD CONSTRAINT chk_members_status CHECK (status IN (...));` 으로 처리한다.

**보안 고려사항**
- `hashed_password` 컬럼에 평문 비밀번호 절대 저장 금지 — BCrypt(`cost=12`) 해시 후 저장
- 로그, 에러 메시지, 응답 Body에 `hashed_password` 값 노출 금지 (`@JsonIgnore` 또는 DTO 미포함)

---

## 체크리스트

- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨
  - `V1__create_members_table.sql` 스크립트 설계 완료
  - `uq_members_login_id` UNIQUE 인덱스 (제약조건 겸용) 설계 완료
  - (TestContainersConfig는 DB 스키마 설계 범위 외 — 구현 작업)
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨
- [x] 모든 인덱스에 사유가 있음
- [x] FK/참조 정책이 명시됨 (이번 작업에서 FK 없음 — 신규 독립 테이블)
- [x] 마이그레이션 순서와 위험도가 명시됨
- [x] 기존 데이터 처리 방안이 있음 (백필 불필요 사유 명시)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수
  - 테이블명 복수형 snake_case (`members`) — `docs/INFRASTRUCTURE.md` 스키마 컨벤션 준수
  - PK: `BIGINT GENERATED ALWAYS AS IDENTITY` — PostgreSQL 10+ 표준 SQL, `Passport.memberId Long` 타입 일치
  - 타임스탬프: `created_at TIMESTAMPTZ` — timezone-aware, JPA AuditingEntityListener 연동
  - 컬럼명 snake_case — JPA 기본 물리 네이밍 전략 준수
  - enum 값: `VARCHAR` + `CHECK` 제약 — `docs/INFRASTRUCTURE.md` 스키마 컨벤션 준수

---

## 참고

- `docs/ARCHITECTURE.md` — 모듈 구조 및 기술 스택 (Java 21, Spring Boot 3.2.2)
- `docs/CONVENTION.md` — 네이밍 규칙, Lombok 사용 패턴
- `docs/INFRASTRUCTURE.md` — PostgreSQL/Flyway 스키마 컨벤션 (BIGINT IDENTITY, VARCHAR + CHECK enum, TIMESTAMPTZ, snake_case, 마이그레이션 파일 위치·버전 점유 규칙)
- `.claude/skills/jpa-patterns/SKILL.md` — Entity 설계, Flyway 마이그레이션 패턴
- `services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java` — `memberId: Long` 타입 확인 → PK BIGINT 결정 근거
- `services/libs/auth-infra/build.gradle.kts` — `spring-boot-starter-data-jpa` 의존성 확인
- `.claude/plans/member-auth/todo.md` — DB 작업 섹션 (`V1__create_members_table.sql` 명세)
