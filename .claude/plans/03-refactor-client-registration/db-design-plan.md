# refactor-client-registration - db-design

## 메타
- **작업명**: refactor-client-registration
- **문서 타입**: db-design
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

이 문서는 `service_client.grant_type` 컬럼의 `NOT NULL` 제약을 제거하는 단일 DDL 변경을 다룬다.
`api_key_hash`는 V4 스키마에서 이미 `NULL` 허용으로 선언되어 있으므로 변경 대상이 아니다.
`oauth2_registered_client`, `service_route` 테이블은 이번 작업 범위 밖이다.

- **DB**: PostgreSQL
- **마이그레이션 도구**: Flyway (파일 위치: `services/libs/auth-infra/src/main/resources/db/migration/`, 네이밍 규칙: `V{N}__{설명}.sql`)

---

## 본문

### 신규 테이블 / 컬렉션

없음.

---

### 기존 테이블 / 컬렉션 변경

#### `service_client`
- **연관 todo**: `[DB-1] Flyway 마이그레이션 V5__make_grant_type_nullable.sql 작성`

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| MODIFY CONSTRAINT | `grant_type` | `VARCHAR(30) NOT NULL` → `VARCHAR(30) NULL` | grantType 생략 시 서비스에서 CLIENT_CREDENTIALS 디폴트 적용, DB 레벨 강제 불필요 | 낮음 |

**변경하지 않는 항목**

| 컬럼 | 현재 상태 | 사유 |
|------|-----------|------|
| `api_key_hash` | `VARCHAR(64) NULL` (V4에서 이미 nullable) | 변경 불필요 — 마이그레이션 대상 아님 |

**변경하지 않는 테이블**

| 테이블 | 사유 |
|--------|------|
| `oauth2_registered_client` | SAS 표준 테이블 — 이번 작업 범위 밖 |
| `service_route` | 이번 작업과 무관 |

---

### 마이그레이션 파일

프로젝트 Flyway 컨벤션(V{N}\_\_{설명}.sql, `services/libs/auth-infra/src/main/resources/db/migration/`)을 따른다.
기존 최신 버전이 V4이므로 V5를 사용한다.

**파일명**: `V5__make_grant_type_nullable.sql`

**위치**: `services/libs/auth-infra/src/main/resources/db/migration/V5__make_grant_type_nullable.sql`

**내용**:
```sql
-- [DB-1] service_client.grant_type NOT NULL 제약 제거
-- grantType 생략 요청 시 애플리케이션에서 CLIENT_CREDENTIALS 디폴트를 적용하므로
-- DB 레벨에서 NOT NULL 강제를 유지할 이유가 없다.
ALTER TABLE service_client
    ALTER COLUMN grant_type DROP NOT NULL;

COMMENT ON COLUMN service_client.grant_type
    IS '그랜트 타입 (AUTHORIZATION_CODE | CLIENT_CREDENTIALS | NULL=디폴트 처리)';

-- 롤백 SQL (별도 파일 없음, 필요 시 수동 실행):
-- ALTER TABLE service_client ALTER COLUMN grant_type SET NOT NULL;
-- COMMENT ON COLUMN service_client.grant_type
--     IS '그랜트 타입 (AUTHORIZATION_CODE | CLIENT_CREDENTIALS)';
```

---

### 마이그레이션 순서

| 단계 | 내용 | 롤백 가능 | 위험도 |
|------|------|-----------|--------|
| 1 | `V5__make_grant_type_nullable.sql` 적용 — `grant_type DROP NOT NULL` | 가능 (SET NOT NULL 수동 실행) | 낮음 |

단일 단계이며 데이터 백필 없음. 기존 데이터는 모두 non-null 값을 가지고 있으므로 제약 해제 직후에도 테이블 데이터는 그대로 유지된다.

---

### 데이터 정합성 / 운영 고려사항

**운영 락 영향**

PostgreSQL에서 `ALTER COLUMN ... DROP NOT NULL`은 시스템 카탈로그(`pg_attribute.attnotnull`) 메타데이터만 변경한다. 테이블 행 재작성(rewrite)이 없고, 획득하는 락은 `ACCESS EXCLUSIVE`이지만 메타데이터 업데이트만 수행하므로 실제 보유 시간이 수 밀리초 수준이다. 운영 트래픽에 대한 영향은 사실상 없다.

**기존 데이터 처리**

변경 없음. `grant_type`이 기존에 `NOT NULL`이었으므로 모든 기존 row는 non-null 값을 가진다. `DROP NOT NULL`은 새 row에 null 삽입을 허용할 뿐이며 기존 데이터에 아무 영향을 주지 않는다.

**데이터 백필 필요 여부**

불필요.

**애플리케이션 호환성**

마이그레이션 전후 모두 애플리케이션이 정상 동작한다.
- 마이그레이션 적용 전(구버전 코드): `grant_type`이 NOT NULL이며 항상 non-null 값이 삽입되므로 문제 없음.
- 마이그레이션 적용 후(신버전 코드): null 삽입이 허용되나 애플리케이션에서 CLIENT_CREDENTIALS 디폴트를 적용하므로 실제 null 저장은 없음 (통합 테스트 `[TEST-4]`에서 null 저장 경로를 별도 검증).

**롤백 절차**

Flyway는 Community 에디션 기준 롤백 파일(U{N}__)을 공식 지원하지 않으므로 롤백은 수동 SQL로 처리한다.

1. 기존 row 중 `grant_type IS NULL`인 row가 있다면 먼저 값을 채워야 한다 (예: `UPDATE service_client SET grant_type = 'CLIENT_CREDENTIALS' WHERE grant_type IS NULL`).
2. 그 후 `ALTER TABLE service_client ALTER COLUMN grant_type SET NOT NULL;` 실행.
3. Flyway `flyway_schema_history` 테이블에서 V5 레코드를 삭제하거나 `repair`를 실행하여 히스토리를 정리한다.

**JPA 엔티티 주의사항**

`ServiceClientJpaEntity`의 `@Column(name = "grant_type", nullable = false, length = 30)`에서 `nullable = false`를 `nullable = true`로 함께 변경해야 한다 (`[IMPL-10]`). JPA의 `nullable = false` 속성은 DDL 자동 생성(`hbm2ddl`) 및 Bean Validation 연동에만 영향을 미치지만, Hibernate의 INSERT/UPDATE 사전 검증이 활성화된 환경에서는 null 삽입 시 예외가 발생할 수 있으므로 DB 마이그레이션과 반드시 함께 배포해야 한다.

**iCloud Drive 중복 파일 함정**

`[BUILD-1]` 참고. 마이그레이션 SQL 파일 추가 후 `V5__make_grant_type_nullable 2.sql` 충돌본이 생성될 수 있으므로 파일 추가 직후 디렉터리를 확인하고 충돌본은 즉시 삭제한다.

---

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 (`[DB-1]` → V5 마이그레이션 1개)
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨
- [x] 모든 인덱스에 사유가 있음 (신규 인덱스 없음)
- [x] FK/참조 정책이 명시됨 (변경 없음 — `service_route`의 FK는 그대로 유지)
- [x] 마이그레이션 순서와 위험도가 명시됨 (단일 단계, 낮음)
- [x] 기존 데이터 처리 방안이 있음 (백필 불필요, 롤백 절차 명시)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (BIGSERIAL PK, snake_case, `created_at TIMESTAMP NOT NULL DEFAULT NOW()`)

---

## 참고
- `services/libs/auth-infra/src/main/resources/db/migration/V4__create_service_client_and_route.sql` — 원본 스키마 (`grant_type VARCHAR(30) NOT NULL`, `api_key_hash VARCHAR(64) NULL` 확인)
- `services/libs/auth-infra/src/main/resources/db/migration/V1__create_members_table.sql` — 프로젝트 테이블/컬럼 네이밍 컨벤션 참조
- `services/apis/auth-api/src/main/java/com/econo/auth/api/adapter/out/persistence/ServiceClientJpaEntity.java` — `@Column(name = "grant_type", nullable = false, length = 30)` 현황 확인
