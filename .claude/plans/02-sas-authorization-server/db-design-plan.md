# sas-authorization-server - db-design

## 메타
- **작업명**: sas-authorization-server
- **문서 타입**: db-design
- **작성일**: 2026-05-24
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

---

## 개요

Spring Authorization Server(SAS) 1.x와 spring-session-jdbc 도입에 따라 5개의 신규 테이블을 추가한다.
SAS 영속 계층 3종(`oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent`)은 SAS 공식 DDL을 PostgreSQL 방언에 맞게 조정하여 도입하고, 세션 저장소 2종(`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`)은 Spring Session JDBC 공식 PostgreSQL DDL을 그대로 사용한다.
기존 `members` 테이블은 이번 작업에서 변경 없음.
사용 DB: **PostgreSQL** / 마이그레이션 도구: **Flyway** (`V{n}__{description}.sql` 컨벤션, `services/libs/auth-infra/src/main/resources/db/migration/`)

---

## 본문

### 기존 테이블 무변경 확인

#### `members`
- **연관 todo**: `V1__create_members_table.sql` — V1 점유 확인
- 이번 작업(SAS 인증 메커니즘 교체)은 Member 도메인 스키마를 변경하지 않는다.
- SAS가 `members`를 직접 참조하는 FK는 없다. `MemberUserDetailsService`가 애플리케이션 레이어에서 `loginId`로 조회할 뿐이다.
- **변경 없음. 현행 유지.**

---

### 신규 테이블 / 컬렉션

> 아래 5개 테이블은 모두 **벤더(SAS / Spring Session) 제공 공식 스키마**이므로,
> 프로젝트 컨벤션(복수형 snake_case 테이블명, `TIMESTAMPTZ`, `BIGINT GENERATED ALWAYS AS IDENTITY` PK)과 다르더라도
> **원형 유지**를 원칙으로 한다. 단, PostgreSQL 미지원 타입만 호환 타입으로 치환한다.

---

#### `oauth2_registered_client`

- **목적**: SAS `JdbcRegisteredClientRepository`가 OAuth2 클라이언트 등록 정보를 영속화하는 테이블. 자사 프런트 public client(PKCE) seed가 기동 시 여기에 저장된다.
- **연관 todo**: `[ ] V2__create_sas_tables.sql 마이그레이션 작성`, `[ ] RegisteredClientConfig 신설`

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | FK / 참조 | 비고 |
|------|------|----------|--------|-----|-----------|------|
| `id` | `VARCHAR(100)` | Y | | Y | | SAS UUID 문자열 PK |
| `client_id` | `VARCHAR(100)` | Y | | | | 클라이언트 식별자 (예: `front-public-client`) |
| `client_id_issued_at` | `TIMESTAMP` | Y | `CURRENT_TIMESTAMP` | | | SAS 원형은 TIMESTAMP. **PostgreSQL 조정 포인트** 참고 |
| `client_secret` | `VARCHAR(200)` | N | `NULL` | | | public client는 NULL |
| `client_secret_expires_at` | `TIMESTAMP` | N | `NULL` | | | |
| `client_name` | `VARCHAR(200)` | Y | | | | |
| `client_authentication_methods` | `VARCHAR(1000)` | Y | | | | 쉼표 구분 문자열 |
| `authorization_grant_types` | `VARCHAR(1000)` | Y | | | | 쉼표 구분 문자열 |
| `redirect_uris` | `VARCHAR(1000)` | N | `NULL` | | | |
| `post_logout_redirect_uris` | `VARCHAR(1000)` | N | `NULL` | | | |
| `scopes` | `VARCHAR(1000)` | Y | | | | |
| `client_settings` | `VARCHAR(2000)` | Y | | | | JSON 직렬화 |
| `token_settings` | `VARCHAR(2000)` | Y | | | | JSON 직렬화 |

**제약조건**
- PK: `id`
- 유니크: `client_id` — SAS가 클라이언트 ID로 단건 조회하므로 필수

**인덱스**
- (PK 외 추가 인덱스 없음) — SAS 공식 스키마에 인덱스 없음. `id`(PK) 조회와 `client_id` 유니크 조회로 충분하며 테이블 크기가 소규모(등록 클라이언트 수 << 수십)이므로 전체 스캔 비용이 미미하다.

**PostgreSQL 조정 포인트**
- SAS 기본 DDL(`oauth2-registered-client-schema.sql`)은 H2 기준으로 작성되어 있다. PostgreSQL에서는 `TIMESTAMP` 타입이 유효하나, 프로젝트 컨벤션(`TIMESTAMPTZ` 선호)과 다르다.
  - **선택**: SAS 원형 유지 원칙에 따라 `TIMESTAMP`(timezone 미포함)로 사용. 이 테이블은 SAS 내부 메타데이터이며 애플리케이션이 직접 읽는 컬럼이 아니므로 혼용 위험이 낮다.
  - 향후 운영 타임존을 UTC로 고정하는 경우 문제 없음. DB 서버 타임존이 UTC가 아니면 `TIMESTAMPTZ`로 변경 검토.
- **H2 전용 문법 제거 필요**: SAS GitHub DDL에 H2 전용 `IF NOT EXISTS`나 `COMMENT`가 없는 경우가 있으므로 실제 파일을 복사한 뒤 PostgreSQL 문법으로 확인 필요.
- `COMMENT ON TABLE` / `COMMENT ON COLUMN` 추가 (프로젝트 스키마 컨벤션).

---

#### `oauth2_authorization`

- **목적**: SAS `JdbcOAuth2AuthorizationService`가 Authorization Code, Access Token, Refresh Token, ID Token 등 진행 중/완료된 인가 요청을 영속화하는 테이블.
- **연관 todo**: `[ ] V2__create_sas_tables.sql 마이그레이션 작성`, `[ ] OAuth2AuthorizationServiceConfig 신설`

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | FK / 참조 | 비고 |
|------|------|----------|--------|-----|-----------|------|
| `id` | `VARCHAR(100)` | Y | | Y | | |
| `registered_client_id` | `VARCHAR(100)` | Y | | | | 논리적 참조 (FK 제약 없음 — SAS 설계) |
| `principal_name` | `VARCHAR(200)` | Y | | | | 인증 주체 (loginId) |
| `authorization_grant_type` | `VARCHAR(100)` | Y | | | | |
| `authorized_scopes` | `VARCHAR(1000)` | N | `NULL` | | | |
| `attributes` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `state` | `VARCHAR(500)` | N | `NULL` | | | |
| `authorization_code_value` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `authorization_code_issued_at` | `TIMESTAMP` | N | `NULL` | | | |
| `authorization_code_expires_at` | `TIMESTAMP` | N | `NULL` | | | |
| `authorization_code_metadata` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `access_token_value` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `access_token_issued_at` | `TIMESTAMP` | N | `NULL` | | | |
| `access_token_expires_at` | `TIMESTAMP` | N | `NULL` | | | |
| `access_token_metadata` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `access_token_type` | `VARCHAR(100)` | N | `NULL` | | | |
| `access_token_scopes` | `VARCHAR(1000)` | N | `NULL` | | | |
| `oidc_id_token_value` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `oidc_id_token_issued_at` | `TIMESTAMP` | N | `NULL` | | | |
| `oidc_id_token_expires_at` | `TIMESTAMP` | N | `NULL` | | | |
| `oidc_id_token_metadata` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `refresh_token_value` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `refresh_token_issued_at` | `TIMESTAMP` | N | `NULL` | | | |
| `refresh_token_expires_at` | `TIMESTAMP` | N | `NULL` | | | |
| `refresh_token_metadata` | `TEXT` | N | `NULL` | | | **PostgreSQL 조정** |
| `user_code_value` | `TEXT` | N | `NULL` | | | Device Flow용 (미사용이라도 DDL 포함) |
| `user_code_issued_at` | `TIMESTAMP` | N | `NULL` | | | |
| `user_code_expires_at` | `TIMESTAMP` | N | `NULL` | | | |
| `user_code_metadata` | `TEXT` | N | `NULL` | | | |
| `device_code_value` | `TEXT` | N | `NULL` | | | |
| `device_code_issued_at` | `TIMESTAMP` | N | `NULL` | | | |
| `device_code_expires_at` | `TIMESTAMP` | N | `NULL` | | | |
| `device_code_metadata` | `TEXT` | N | `NULL` | | | |

**제약조건**
- PK: `id`
- `registered_client_id`는 SAS 공식 설계 상 DB FK를 걸지 않는다(서비스 재시작 시 lazy 참조 허용). 원형 유지.

**인덱스**
- (별도 인덱스 없음) — SAS 공식 스키마는 PK 단독. `principal_name` 조회는 SAS 내부에서 `id`나 토큰 값 해시 기반이므로 현재 트래픽 규모에서 추가 인덱스 불필요. 향후 토큰 조회 성능 문제가 관측되면 `access_token_value`(해시 prefix) 인덱스 추가 검토.

**PostgreSQL 조정 포인트**
- SAS H2 기본 DDL에서 토큰/메타데이터 컬럼은 `BLOB`로 선언된 경우가 있다. PostgreSQL에서는 **`TEXT`로 치환**한다. SAS `JdbcOAuth2AuthorizationService`는 내부적으로 이 컬럼들을 문자열(직렬화된 JWT / JSON)로 다루므로 `TEXT`가 올바르다.
- `BLOB` → `TEXT` 치환 여부는 **현행 SAS 버전의 실제 DDL 파일을 대조하여 확인** 필요 (SAS 1.x 버전에 따라 이미 `TEXT`로 변경되었을 수 있음).
- `COMMENT ON TABLE` / `COMMENT ON COLUMN` 추가.

---

#### `oauth2_authorization_consent`

- **목적**: SAS `JdbcOAuth2AuthorizationConsentService`가 사용자별 scope 동의 정보를 영속화하는 테이블. 자사 1st-party 클라이언트에 `requireAuthorizationConsent(false)` 설정 시 실제 row가 삽입되지 않을 수 있으나, 테이블 자체는 필요하다.
- **연관 todo**: `[ ] V2__create_sas_tables.sql 마이그레이션 작성`, `[ ] OAuth2AuthorizationServiceConfig 신설`

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | FK / 참조 | 비고 |
|------|------|----------|--------|-----|-----------|------|
| `registered_client_id` | `VARCHAR(100)` | Y | | Y (복합) | | |
| `principal_name` | `VARCHAR(200)` | Y | | Y (복합) | | |
| `authorities` | `VARCHAR(1000)` | Y | | | | 동의된 scope 목록 |

**제약조건**
- PK: `(registered_client_id, principal_name)` 복합 PK — 사용자·클라이언트 쌍으로 단일 consent row 보장

**인덱스**
- (복합 PK가 곧 인덱스) — 조회 패턴이 항상 `(registered_client_id, principal_name)` 조합이므로 추가 인덱스 불필요.

**PostgreSQL 조정 포인트**
- H2 기본 DDL과 PostgreSQL DDL 간 타입 차이가 없는 테이블이다. 단, H2 전용 `COMMENT` 문법이 있을 경우 제거하고 PostgreSQL `COMMENT ON TABLE/COLUMN` 문법으로 교체.
- `COMMENT ON TABLE` / `COMMENT ON COLUMN` 추가.

---

#### `SPRING_SESSION`

- **목적**: spring-session-jdbc가 HTTP 세션 메타데이터(세션 ID, 만료 시각, 주체 이름 등)를 저장하는 테이블. `/api/v1/auth/login` 성공 후 수립되는 서버 세션이 여기에 저장된다.
- **연관 todo**: `[ ] 세션 저장소 선택 결정 — JDBC 세션 도입 시 V3 마이그레이션 추가`

> 이 테이블의 도입 여부는 **세션 저장소 모호함(todo 2번)**이 해소된 후 확정된다. JDBC 세션을 채택하는 경우에 한해 `V3__create_spring_session_tables.sql`에 포함한다.

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | FK / 참조 | 비고 |
|------|------|----------|--------|-----|-----------|------|
| `primary_id` | `CHAR(36)` | Y | | Y | | UUID |
| `session_id` | `CHAR(36)` | Y | | | | 클라이언트에 노출되는 세션 ID (유니크) |
| `creation_time` | `BIGINT` | Y | | | | epoch millis |
| `last_access_time` | `BIGINT` | Y | | | | epoch millis |
| `max_inactive_interval` | `INT` | Y | | | | 초 단위 비활성 만료 간격 |
| `expiry_time` | `BIGINT` | Y | | | | epoch millis, 만료 스캔용 |
| `principal_name` | `VARCHAR(100)` | N | `NULL` | | | 인증 주체 이름 |

**제약조건**
- PK: `primary_id`
- 유니크: `session_id` — 세션 쿠키 값으로 단건 조회

**인덱스**
- `spring_session_ix1` — `(session_id)` — 세션 쿠키로 세션 조회 시 사용하는 주 접근 패턴
- `spring_session_ix2` — `(expiry_time)` — 만료된 세션 정리(cleanup) 배치 쿼리용
- `spring_session_ix3` — `(principal_name)` — 특정 사용자의 모든 세션 무효화(전체 로그아웃) 쿼리용

> Spring Session JDBC PostgreSQL 공식 DDL(`schema-postgresql.sql`)에 위 인덱스가 포함되어 있다. 원형 그대로 사용.

**PostgreSQL 조정 포인트**
- Spring은 DB별로 별도 DDL(`schema-postgresql.sql`)을 제공한다. H2용 스크립트가 아니라 **PostgreSQL 전용 스크립트**를 그대로 사용하면 추가 조정이 불필요하다.
- 테이블명이 대문자(`SPRING_SESSION`)인 것은 벤더 제공 원형이므로 유지. PostgreSQL은 식별자를 소문자로 fold하므로 실제 저장은 `spring_session`으로 동일하게 동작한다.
- `COMMENT ON TABLE` / `COMMENT ON COLUMN` 추가 (프로젝트 컨벤션).

---

#### `SPRING_SESSION_ATTRIBUTES`

- **목적**: spring-session-jdbc가 세션에 저장된 개별 attribute를 key-value 형태로 저장하는 테이블. `SecurityContext`(인증 정보) 등이 직렬화되어 저장된다.
- **연관 todo**: `[ ] 세션 저장소 선택 결정 — JDBC 세션 도입 시 V3 마이그레이션 추가`

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | FK / 참조 | 비고 |
|------|------|----------|--------|-----|-----------|------|
| `session_primary_id` | `CHAR(36)` | Y | | Y (복합) | `SPRING_SESSION.primary_id` ON DELETE CASCADE | |
| `attribute_name` | `VARCHAR(200)` | Y | | Y (복합) | | |
| `attribute_bytes` | `BYTEA` | Y | | | | 직렬화된 attribute 값 |

**제약조건**
- PK: `(session_primary_id, attribute_name)` 복합 PK
- FK: `session_primary_id → SPRING_SESSION(primary_id) ON DELETE CASCADE` — 세션 삭제 시 속성 자동 정리

**인덱스**
- (복합 PK가 곧 인덱스) — 항상 세션 ID + attribute 이름 조합으로 접근하므로 추가 인덱스 불필요.

**PostgreSQL 조정 포인트**
- Spring PostgreSQL DDL에서 attribute 값 컬럼은 `BYTEA`를 사용한다. H2의 `BLOB` 대응 타입이다. PostgreSQL 전용 DDL에는 이미 `BYTEA`로 선언되어 있으므로 추가 치환 불필요.
- `COMMENT ON TABLE` / `COMMENT ON COLUMN` 추가.

---

### 마이그레이션 순서

Flyway 파일명 컨벤션: `V{version}__{description}.sql` — V1이 `members` 테이블을 점유하므로 V2부터 시작.

#### 파일 분할 제안

| 버전 | 파일명 | 내용 | 근거 |
|------|--------|------|------|
| V2 | `V2__create_sas_tables.sql` | `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent` 3개 테이블 | SAS 영속 계층 3개는 서로 논리적으로 묶이며 동시에 필요하다. 한 파일로 묶으면 트랜잭션 단위로 원자적 롤백 가능. |
| V3 | `V3__create_spring_session_tables.sql` | `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` 2개 테이블 | 세션 저장소 선택 결정(todo 모호함 2번) 후 포함 여부를 확정한다. JDBC 세션 미채택 시 이 파일은 생성하지 않는다. |

#### 단계별 순서 및 위험도

**1단계 — V2: SAS 테이블 생성** (위험도: 낮음)
- 신규 테이블 생성이며 기존 데이터에 영향 없다.
- `members` 테이블을 참조하는 FK가 없으므로 순서 제약 없다.
- 롤백: Flyway `undo` 또는 수동 `DROP TABLE`로 가능.

**2단계 — V3: Spring Session 테이블 생성** (위험도: 낮음, 조건부)
- JDBC 세션 채택 시에만 실행. 신규 테이블 생성이므로 기존 데이터 영향 없다.
- `SPRING_SESSION_ATTRIBUTES`의 FK가 `SPRING_SESSION`을 참조하므로 동일 파일 내에서 `SPRING_SESSION`을 먼저 CREATE해야 한다.
- 롤백: 수동 `DROP TABLE SPRING_SESSION_ATTRIBUTES; DROP TABLE SPRING_SESSION;` (FK 순서 역순).

**운영 중 위험 작업 없음** — 이번 마이그레이션은 모두 신규 테이블 추가이며, 기존 테이블 변경(`NOT NULL` 추가, 컬럼 타입 변경, 대형 인덱스 빌드)이 없다.

---

### 데이터 정합성 / 운영 고려사항

**기존 데이터 백필**
- 없음. 신규 테이블만 추가하며 기존 `members` 데이터에 대한 백필 작업은 불필요하다.

**NOT NULL 추가 없음**
- 이번 마이그레이션은 모두 신규 테이블이므로 기존 row에 대한 NOT NULL 추가 문제가 없다.

**인덱스 빌드 락**
- 신규 테이블 생성 시점에 인덱스를 함께 생성하므로 운영 데이터에 대한 락 영향 없다.

**애플리케이션 호환성 (배포 순서)**
- V2 마이그레이션이 먼저 실행된 상태에서 구버전 코드(SAS 미사용)가 기동해도 새 테이블을 참조하지 않으므로 충돌 없다.
- 신버전 코드가 기동되고 SAS 빈이 초기화될 때 `JdbcRegisteredClientRepository`가 `oauth2_registered_client` 테이블에 접근한다. 따라서 **코드 배포 전에 Flyway V2가 완료된 상태여야 한다**.
- `RegisteredClientConfig`의 멱등 seed 로직(`clientId` 존재 여부 확인 후 `save()`)은 재기동 시 중복 삽입을 방지한다.

**세션 저장소 결정(todo 모호함 2번) 미해결 시 영향**
- in-memory 세션 채택 시: V3 파일 생성 불필요. 단, 다중 인스턴스 배포 시 세션 공유 불가 — 단일 인스턴스 운영을 유지하거나 sticky session 설정 필요.
- JDBC 세션 채택 시: V3 파일을 V2와 동일 PR에 포함하는 것을 권장(기동 시 세션 테이블이 없으면 서버 에러 발생).

**SAS DDL 버전 대조 플래그**
- 이 문서의 컬럼 목록은 SAS 1.x 표준 스키마를 기준으로 작성되었다.
- **마이그레이션 파일 작성 전, 실제 사용할 SAS 버전(`spring-authorization-server` artifact 버전)의 GitHub 태그에서 다음 파일을 직접 복사하여 컬럼 누락·타입 변경 여부를 대조해야 한다**:
  - `oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization/oauth2-registered-client-schema.sql`
  - `oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql`
  - `oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql`
  - Spring Session: `spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql`

---

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨
- [x] 모든 인덱스에 사유가 있음
- [x] FK/참조 정책이 명시됨 (`SPRING_SESSION_ATTRIBUTES` → `SPRING_SESSION` CASCADE)
- [x] 마이그레이션 순서와 위험도가 명시됨
- [x] 기존 데이터 처리 방안이 있음 (백필 없음 확인)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (벤더 제공 테이블은 원형 유지 근거 명시)

---

## 참고
- `docs/INFRASTRUCTURE.md` — PostgreSQL/Flyway 컨벤션, 스키마 컨벤션 (snake_case, TIMESTAMPTZ, BIGINT GENERATED ALWAYS AS IDENTITY)
- `services/libs/auth-infra/src/main/resources/db/migration/V1__create_members_table.sql` — 현행 마이그레이션 파일, V1 점유 확인, 컨벤션 학습
- SAS 공식 DDL (spring-projects/spring-authorization-server GitHub, 사용 버전 태그 기준 대조 필수)
- Spring Session JDBC PostgreSQL DDL (`schema-postgresql.sql`, spring-session 저장소)
