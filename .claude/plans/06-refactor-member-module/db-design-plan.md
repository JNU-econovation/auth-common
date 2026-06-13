# refactor-member-module - db-design

## 메타
- **작업명**: refactor-member-module
- **문서 타입**: db-design
- **작성일**: 2026-06-04
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md (작성 예정)

## 개요

본 작업은 순수 모듈/패키지 리팩터링이다. DB 스키마(테이블·컬럼·인덱스·제약)는 단 하나도 변경하지 않는다. 유일한 DB 관련 작업은 Flyway 마이그레이션 파일 5개(`V1`~`V5`)를 폐기 모듈(`auth-infra`)에서 신규 모듈(`member`)로 물리 이동하는 것이다. 사용 DB는 PostgreSQL, 마이그레이션 도구는 Flyway Core (classpath 스캔 방식)이며, ORM은 Spring Data JPA (Hibernate 6)를 사용한다.

---

## 본문

### 신규 테이블 / 컬렉션

없음. 신규 테이블/컬렉션 생성 없음.

---

### 기존 테이블 / 컬렉션 변경

없음. 기존 테이블·컬럼·인덱스·제약 변경 없음.

---

### Flyway 마이그레이션 파일 이동

#### 현재 위치 (확인 완료)

```
services/libs/auth-infra/src/main/resources/db/migration/
  V1__create_members_table.sql
  V2__create_sas_tables.sql
  V3__create_spring_session_tables.sql
  V4__create_service_client_and_route.sql
  V5__make_grant_type_nullable.sql
```

- **연관 todo**: `[ ] Flyway 마이그레이션 파일 5개(V1~V5) auth-infra/src/main/resources/db/migration/ → member/src/main/resources/db/migration/ 이동`

#### 이동 대상 위치

```
services/libs/member/src/main/resources/db/migration/
  V1__create_members_table.sql
  V2__create_sas_tables.sql
  V3__create_spring_session_tables.sql
  V4__create_service_client_and_route.sql
  V5__make_grant_type_nullable.sql
```

#### 이동 결정 사유

- Flyway는 classpath 기반 단일 소스를 권장한다. 복수 모듈에 분산하면 `spring.flyway.locations` 다중 경로 지정이 필요하고, 마이그레이션 버전 충돌 위험이 생긴다.
- `auth-api/src/main/resources/application.yml`에 `spring.flyway.locations: classpath:db/migration`이 **명시적으로 설정**되어 있다 (기본값에 의존하지 않는 명시 설정). `application-test.yml`에도 동일 값이 중복 선언되어 있다.
- `member` 모듈은 `org.flywaydb:flyway-core` 의존성을 `build.gradle.kts`에 추가해야 한다 (현재 `auth-infra`가 보유 중이며 `member`로 이전).
- `common-infra`는 인프라 횡단 관심사(`JpaAuditingConfig`) 만을 담는 모듈이므로 마이그레이션 파일 위치로 부적절하다. `service_client`·`service_route` 마이그레이션(V4, V5)도 `auth-infra`에 있었으므로, 단일 소스 원칙상 `member`로 함께 이동한다.

#### `spring.flyway.locations` 확인

| 파일 | 설정 값 | 상태 |
|------|---------|------|
| `services/apis/auth-api/src/main/resources/application.yml` | `classpath:db/migration` | 명시 설정 — 변경 불필요 |
| `services/apis/auth-api/src/test/resources/application-test.yml` | `classpath:db/migration` | 명시 설정 — 변경 불필요 |

`member` 모듈의 `src/main/resources/db/migration/`은 빌드 후 클래스패스 루트 기준 `db/migration/`에 위치하므로 기존 설정과 경로가 일치한다. 설정 파일 변경은 불필요하다.

---

### 무변경 테이블 및 스키마 현황 (회귀 검증 기준)

#### `members` 테이블

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | 비고 |
|------|------|----------|--------|-----|------|
| `id` | BIGINT GENERATED ALWAYS AS IDENTITY | Y | — | Y | |
| `name` | VARCHAR(50) | Y | — | | |
| `login_id` | VARCHAR(20) | Y | — | | |
| `hashed_password` | VARCHAR(72) | Y | — | | |
| `generation` | INTEGER | Y | — | | CHECK (1~99) |
| `status` | VARCHAR(2) | Y | — | | CHECK ('AM','RM','CM','OB') |
| `created_at` | TIMESTAMPTZ | Y | NOW() | | JPA `@CreatedDate` 자동 채움 |

**제약조건**
- PK: `pk_members` — `(id)`
- 유니크: `uq_members_login_id` — `(login_id)`
- 체크: `chk_members_status` — `status IN ('AM','RM','CM','OB')`
- 체크: `chk_members_generation` — `generation BETWEEN 1 AND 99`

**인덱스**
- (별도 명시 인덱스 없음 — PK와 UNIQUE 제약 인덱스만 존재)

> `updated_at` 컬럼 없음. `members` 테이블은 `@CreatedDate`(`created_at`)만 사용하며 `@LastModifiedDate`는 없다.

---

#### `oauth2_registered_client` 테이블 (SAS 표준 DDL)

**제약조건**
- PK: `pk_oauth2_registered_client` — `(id)`

**인덱스**
- `uq_oauth2_registered_client_client_id` — `(client_id)` UNIQUE

---

#### `oauth2_authorization` 테이블 (SAS 표준 DDL)

**제약조건**
- PK: `pk_oauth2_authorization` — `(id)`

**인덱스**
- (별도 인덱스 없음)

---

#### `oauth2_authorization_consent` 테이블 (SAS 표준 DDL)

**제약조건**
- 복합 PK: `pk_oauth2_authorization_consent` — `(registered_client_id, principal_name)`

---

#### `SPRING_SESSION` 테이블

**제약조건**
- PK: `SPRING_SESSION_PK` — `(PRIMARY_ID)`

**인덱스**
- `SPRING_SESSION_IX1` — `(SESSION_ID)` UNIQUE
- `SPRING_SESSION_IX2` — `(EXPIRY_TIME)` — cleanup 쿼리용
- `SPRING_SESSION_IX3` — `(PRINCIPAL_NAME)` — 전체 로그아웃 쿼리용

---

#### `SPRING_SESSION_ATTRIBUTES` 테이블

**제약조건**
- 복합 PK: `SPRING_SESSION_ATTRIBUTES_PK` — `(SESSION_PRIMARY_ID, ATTRIBUTE_NAME)`
- FK: `SPRING_SESSION_ATTRIBUTES_FK` — `SESSION_PRIMARY_ID → SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE`

---

#### `service_client` 테이블

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | 비고 |
|------|------|----------|--------|-----|------|
| `id` | BIGSERIAL | Y | — | Y | |
| `registered_client_id` | VARCHAR(100) | Y | — | | UNIQUE |
| `client_name` | VARCHAR(100) | Y | — | | UNIQUE |
| `grant_type` | VARCHAR(30) | N | NULL | | V5에서 NOT NULL 제거됨 |
| `api_key_hash` | VARCHAR(64) | N | NULL | | |
| `created_at` | TIMESTAMP | Y | NOW() | | JPA `@CreatedDate` 자동 채움 |
| `created_by` | VARCHAR(200) | N | NULL | | JPA Auditing 미사용 |

**제약조건**
- PK: BIGSERIAL 기본 PK
- 유니크: `uq_service_client_registered_client_id` — `(registered_client_id)`
- 유니크: `uq_service_client_name` — `(client_name)`

---

#### `service_route` 테이블

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | 비고 |
|------|------|----------|--------|-----|------|
| `id` | BIGSERIAL | Y | — | Y | |
| `route_id` | VARCHAR(100) | Y | — | | UNIQUE |
| `registered_client_id` | VARCHAR(100) | Y | — | | FK → service_client |
| `path_prefix` | VARCHAR(200) | N | NULL | | UNIQUE (nullable) |
| `upstream_url` | VARCHAR(500) | Y | — | | |
| `enabled` | BOOLEAN | Y | TRUE | | |
| `created_at` | TIMESTAMP | Y | NOW() | | JPA `@CreatedDate` 자동 채움 |
| `updated_at` | TIMESTAMP | Y | NOW() | | JPA `@LastModifiedDate` 자동 채움 |

**제약조건**
- 유니크: `uq_service_route_route_id` — `(route_id)`
- 유니크: `uq_service_route_path_prefix` — `(path_prefix)`
- FK: `fk_service_route_client` — `registered_client_id → service_client(registered_client_id) ON DELETE CASCADE`

---

### 마이그레이션 순서

새 마이그레이션 파일 추가 없음. 기존 V1~V5 파일을 내용 무변경으로 이동한다.

1. **신규 모듈 디렉터리 생성** — `services/libs/member/src/main/resources/db/migration/` 생성
2. **파일 이동** — `auth-infra/src/main/resources/db/migration/V*.sql` 5개를 위 경로로 이동 (내용 변경 없음)
3. **auth-infra 빌드 스크립트 정리** — `auth-infra/build.gradle.kts`에서 `implementation("org.flywaydb:flyway-core")` 제거 (모듈 폐기 단계에서 디렉터리 전체 삭제로 대체 가능)
4. **member 빌드 스크립트에 Flyway 추가** — `member/build.gradle.kts`에 `implementation("org.flywaydb:flyway-core")` 추가

각 단계 롤백 가능 여부:
- 1~4 단계 모두 **롤백 가능** (파일 복사/이동이며 DB 스키마 변경 없음)
- 위험도: **낮음** (운영 DB에 DDL/DML 실행 없음)

---

### 데이터 정합성 / 운영 고려사항

#### JPA Auditing 회귀 검증

`JpaAuditingConfig`가 `auth-infra`에서 `common-infra`의 `CommonInfraAutoConfiguration`으로 이동하더라도 `@EnableJpaAuditing`은 동일하게 선언된다. 따라서 `@CreatedDate`와 `@LastModifiedDate`가 자동으로 채워지는 동작은 유지된다.

검증 포인트:
- `members.created_at` — `MemberJpaEntity`의 `@CreatedDate` 필드. `CommonInfraAutoConfiguration`이 AutoConfiguration으로 로드되면 `AuditingEntityListener`가 정상 작동해야 한다.
- `service_client.created_at` — `ServiceClientJpaEntity`의 `@CreatedDate` 필드. `service-client` 모듈이 `common-infra`를 의존하는 것으로 전환된 후에도 동일하게 작동해야 한다.
- `service_route.created_at`, `service_route.updated_at` — `ServiceRouteJpaEntity`의 `@CreatedDate`·`@LastModifiedDate` 필드.

> `@EnableJpaAuditing`은 Spring 컨텍스트 당 한 번만 선언되어야 한다. `CommonInfraAutoConfiguration` 하나에 집중하므로 중복 선언 위험이 해소된다.

#### Flyway 체크섬 영향 없음

파일 내용이 변경되지 않으므로 Flyway `flyway_schema_history` 테이블의 체크섬 검증을 통과한다. 이미 적용된 마이그레이션은 재실행되지 않는다.

#### 기존 데이터 백필

없음. 스키마 변경이 없으므로 기존 데이터 처리 불필요.

#### 운영 중 호환성

마이그레이션 파일 이동은 빌드 시점 변경이다. 구버전 코드가 배포된 상태에서 신버전 빌드가 배포되더라도 DB 스키마가 변경되지 않으므로 호환성 위반이 발생하지 않는다.

---

## 체크리스트

- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨
  - `[ ] Flyway 마이그레이션 파일 5개 이동` → "Flyway 마이그레이션 파일 이동" 섹션에 반영
  - `[ ] spring.flyway.locations 설정 확인` → 명시 설정(`classpath:db/migration`) 확인 완료, 변경 불필요
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 (회귀 검증 대상 테이블 기준)
- [x] 모든 인덱스에 사유가 있음 (SPRING_SESSION 인덱스 포함)
- [x] FK/참조 정책이 명시됨 (`fk_service_route_client ON DELETE CASCADE`, `SPRING_SESSION_ATTRIBUTES_FK ON DELETE CASCADE`)
- [x] 마이그레이션 순서와 위험도가 명시됨 (위험도: 낮음)
- [x] 기존 데이터 처리 방안이 있음 — 스키마 변경 없으므로 백필 불필요
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 — snake_case 테이블/컬럼, BIGINT IDENTITY PK, `created_at`/`updated_at` TIMESTAMP, `@CreatedDate`/`@LastModifiedDate`

---

## 참고

- `docs/ARCHITECTURE.md` — 모듈 구조, 헥사고날 패키지 구조 기준
- `docs/CONVENTION.md` — 네이밍, Lombok, 불변성 컨벤션
- `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/JpaAuditingConfig.java` — 이동 대상 Auditing 설정 원본
- `services/libs/auth-infra/src/main/java/com/econo/auth/infra/member/adapter/out/persistence/MemberJpaEntity.java` — `@CreatedDate` 사용 패턴 확인
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceClientJpaEntity.java` — `@CreatedDate` 사용 패턴 확인
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceRouteJpaEntity.java` — `@CreatedDate`·`@LastModifiedDate` 사용 패턴 확인
- `services/libs/auth-infra/src/main/resources/db/migration/V1~V5` — 이동 대상 마이그레이션 파일 (내용 확인 완료)
- `services/apis/auth-api/src/main/resources/application.yml` — `spring.flyway.locations: classpath:db/migration` 명시 설정 확인
- `services/apis/auth-api/src/test/resources/application-test.yml` — 동일 설정 확인
