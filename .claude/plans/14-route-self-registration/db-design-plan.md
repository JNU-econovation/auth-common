# route-self-registration - db-design

## 메타
- **작업명**: route-self-registration
- **문서 타입**: db-design
- **작성일**: 2026-06-21
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

`service_route` 테이블에 `owner_id BIGINT NULL` 컬럼을 추가하여 회원 셀프서비스 라우트 등록을 지원한다. 셀프 등록 라우트는 `owner_id = memberId`, 어드민 등록 라우트는 `owner_id = NULL`로 구분한다. 이는 ADR-0013에서 `service_client`에 `owner_id`를 추가한 V7 마이그레이션 패턴과 동일하다.

사용 DB: PostgreSQL. 마이그레이션 도구: Flyway (전역 단일 소스 `db/migration/`, ADR-0015).

---

## 본문

### 신규 테이블 / 컬렉션

해당 없음. 기존 테이블 변경만 수행.

---

### 기존 테이블 / 컬렉션 변경

#### `service_route`

현재 스키마 상태 (V1~V10 누적):

| 컬럼 | 타입 | NOT NULL | 기본값 | 제약 |
|------|------|----------|--------|------|
| id | BIGSERIAL | Y | auto | PK |
| route_id | VARCHAR(100) | Y | — | UNIQUE |
| registered_client_id | VARCHAR(100) | N | — | (V9에서 FK 제거, nullable 전환) |
| path_prefix | VARCHAR(200) | N | — | UNIQUE |
| upstream_url | VARCHAR(500) | Y | — | |
| enabled | BOOLEAN | Y | TRUE | |
| created_at | TIMESTAMP | Y | NOW() | |
| updated_at | TIMESTAMP | Y | NOW() | |

인덱스 현황: `idx_service_route_enabled ON (enabled)` (V10)

**V11 — `owner_id` 컬럼 추가**

- **연관 todo**: `[ ] V11__add_owner_id_to_service_route.sql 신규 마이그레이션 작성`

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| ADD COLUMN | `owner_id` | BIGINT NULL, 기본값 없음 | 셀프 등록 라우트 소유자 memberId 저장. 어드민 등록 시 NULL | 낮음 (nullable 컬럼 추가, 기존 row 백필 불필요) |

**V12 — 인덱스 추가**

- **연관 todo**: `[ ] V12__add_namespace_index_to_service_route.sql 신규 마이그레이션 작성`

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| ADD INDEX | `idx_service_route_owner_id` | `(owner_id)` | `countByOwnerId` (쿼터 검증), `findAllByOwnerId` (내 라우트 목록) — 운영에서 라우트 수는 수백~수천 행 예상이나 owner 기반 필터 없이 전수 스캔하면 불필요한 IO 발생 | 낮음 (인덱스 빌드, 현 규모에서 락 위험 없음) |
| ADD INDEX | `idx_service_route_path_prefix_text` | `(path_prefix text_pattern_ops)` | 네임스페이스 선점 조회: `WHERE path_prefix LIKE '/api/ns/%'` 또는 `LIKE '/ns/%'` 형태의 prefix 탐색. 기존 `uq_service_route_path_prefix` UNIQUE 제약은 B-tree이나 `text_pattern_ops`가 없으면 `LIKE 'prefix%'` 패턴에서 인덱스가 동작하지 않음 | 낮음 |

---

### 마이그레이션 순서

프로젝트 파일명 컨벤션: `V{version}__{snake_case_description}.sql` (대문자 V, 숫자 버전, 언더스코어 두 개).

현재 최신: V10 (`V10__add_index_service_route_enabled.sql`). 신규는 V11·V12.

#### 단계 1 — V11 (컬럼 추가)

파일: `db/migration/V11__add_owner_id_to_service_route.sql`

```sql
-- service_route 소유자(회원 ID) 컬럼 추가 — 라우트 셀프 등록 기능
ALTER TABLE service_route
    ADD COLUMN owner_id BIGINT NULL;

COMMENT ON COLUMN service_route.owner_id
    IS '라우트 소유자 회원 ID (셀프 등록 시 설정, 어드민 등록 시 NULL)';
```

- 롤백 가능: `ALTER TABLE service_route DROP COLUMN owner_id;` (데이터 유실 주의)
- 위험도: **낮음** — nullable 컬럼 추가는 테이블 재작성 없이 즉시 완료 (PostgreSQL 11+). 기존 row는 NULL이 되므로 백필 불필요.
- 기존 row 영향: 모든 기존 라우트의 `owner_id`는 NULL. 어드민 등록 라우트로 의미상 올바름.

#### 단계 2 — V12 (인덱스 추가)

파일: `db/migration/V12__add_indexes_to_service_route.sql`

```sql
-- owner_id 조회 인덱스 — countByOwnerId / findAllByOwnerId 성능
CREATE INDEX idx_service_route_owner_id
    ON service_route (owner_id);

-- path_prefix prefix 탐색 인덱스 — 네임스페이스 선점 조회 (LIKE 'prefix%')
CREATE INDEX idx_service_route_path_prefix_text
    ON service_route (path_prefix text_pattern_ops);
```

- 롤백 가능: `DROP INDEX idx_service_route_owner_id; DROP INDEX idx_service_route_path_prefix_text;`
- 위험도: **낮음** (현 규모). 대용량 테이블이면 `CREATE INDEX CONCURRENTLY`로 락 없이 빌드해야 하나, Flyway 트랜잭션 내에서는 `CONCURRENTLY` 사용 불가. 운영 라우트 행 수가 수만 건 이상이면 별도 운영 배포 절차 필요 — 현 규모에서는 해당 없음.

---

### 네임스페이스 소유권 판정: 별도 컬럼 vs pathPrefix 파생 트레이드오프

todo 항목 `[ ] 네임스페이스를 별도 컬럼/테이블로 둘지 vs pathPrefix에서 파생(런타임 추출)할지 트레이드오프를 검토해 권장안 제시` 에 대한 판단.

#### 현행 pathPrefix UNIQUE 제약 확인

V4 기준 `path_prefix VARCHAR(200) NULL UNIQUE`. 이 UNIQUE 제약 자체가 B-tree 인덱스를 생성하므로, `WHERE path_prefix = '...'` 등치 조회는 이미 최적화되어 있다.

#### 네임스페이스 선점 조회 패턴

"이 네임스페이스를 이미 다른 회원이 선점했는가"를 판정하려면:
- 셀프 등록자가 `/api/eeos/**`를 요청했을 때, `WHERE path_prefix LIKE '/api/eeos/%' OR path_prefix = '/api/eeos'` 형태로 해당 네임스페이스에 속한 라우트 중 첫 번째 소유자를 조회한다.

#### 권장안: pathPrefix 파생 방식 유지 + `text_pattern_ops` 인덱스 추가

**별도 `namespace` 컬럼을 추가하지 않는다.** 근거:

1. `path_prefix`는 이미 UNIQUE이므로 네임스페이스당 라우트 수는 소수. prefix LIKE 탐색에 `text_pattern_ops` 인덱스를 더하면 쿼리 효율이 충분하다.
2. `namespace` 컬럼을 별도로 두면 `pathPrefix`와 `namespace` 간 정합 유지 책임이 애플리케이션에 생긴다. 파싱 규칙 변경 시 두 곳을 동시에 바꿔야 한다.
3. 쿼터가 1인당 5개로 제한되어 있어 한 namespace에 속할 라우트 수가 구조적으로 적다. 인덱스 없어도 전체 스캔 비용이 낮지만, 미래 확장을 위해 `text_pattern_ops` 인덱스는 추가한다.
4. JPQL `@Query`에서 `LIKE CONCAT(:ns, '%')` 형태로 파생 조회 가능. JPA 수준에서 파생 컬럼 없이 처리한다.

**네임스페이스 파싱 규칙 (확정):** 셀프 라우트 `pathPrefix`는 `/api/{namespace}/...` 형태(두 번째 세그먼트가 네임스페이스). 따라서 네임스페이스 선점 조회 LIKE 패턴은 `path_prefix LIKE '/api/{namespace}/%' OR path_prefix = '/api/{namespace}'` 형태로 고정된다(implementation-plan.md `findOwnerIdByNamespace` 참조). `text_pattern_ops` 인덱스는 이 좌측 고정(`/api/...`) LIKE 탐색에 그대로 활용된다.

---

### ddl-auto=validate 정합 확인 사항

`auth-api`는 `spring.jpa.hibernate.ddl-auto=validate`이므로, V11 적용 후 `ServiceRouteJpaEntity`에 다음 `@Column` 선언이 추가되어야 validate 통과한다:

```java
@Column(name = "owner_id", nullable = true)
private Long ownerId;
```

현재 `ServiceRouteJpaEntity`에 `owner_id` 필드가 없으므로 V11 마이그레이션 적용 후 엔티티 변경 없이 기동하면 validate 단계에서 오류가 발생하지 **않는다** — Hibernate validate는 테이블에 없는 컬럼을 엔티티가 선언할 때 실패하고, 테이블에 있는 컬럼을 엔티티가 선언하지 않아도 오류가 없다. 따라서 마이그레이션과 엔티티 변경은 동시에 배포해야 하며, 순서는:

1. V11·V12 마이그레이션 적용 (flyway 컨테이너)
2. `ServiceRouteJpaEntity`에 `ownerId` 필드 추가된 앱 배포

역순(엔티티 먼저 배포)은 validate 실패로 앱이 기동되지 않으므로 금지.

---

### 데이터 정합성 / 운영 고려사항

- **기존 데이터 백필**: 불필요. `owner_id` NULL = 어드민 등록이라는 의미가 기존 데이터에 자연스럽게 적용된다.
- **NOT NULL 제약 추가 없음**: 이번 작업 범위에서 `owner_id`는 영구적으로 nullable. 어드민 등록 라우트는 항상 NULL이어야 하므로 NOT NULL 전환 계획 없음.
- **FK 연결 없음**: `owner_id`는 `members.id`를 논리적으로 참조하지만 FK 제약을 추가하지 않는다. `service_client.owner_id` (V7)도 동일하게 FK 없이 운용 중이며, 회원 탈퇴 시 라우트 정리는 애플리케이션 레이어에서 처리하는 정책을 따른다 (현재 service_client owner_id 패턴과 일관성 유지).
- **인덱스 빌드 락**: 현 서비스 규모에서 `service_route` 행 수는 수십~수백 수준으로 예상. `CREATE INDEX`는 테이블에 ShareLock을 걸며 현 규모에서는 즉시 완료. 향후 대용량 전환 시 `CONCURRENTLY` 옵션 검토 (단, Flyway 트랜잭션 밖에서 수동 적용 필요).
- **마이그레이션 도중 구버전 앱 호환성**: nullable 컬럼 추가이므로 구버전 앱(owner_id 컬럼을 모르는 JPA 엔티티)도 V11 스키마에서 정상 동작한다. 배포 순서(flyway 먼저 → 앱)를 지키면 다운타임 없이 전환 가능.

---

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨
- [x] 모든 인덱스에 사유가 있음
- [x] FK/참조 정책이 명시됨 (FK 없음, service_client 패턴 일관성 근거 포함)
- [x] 마이그레이션 순서와 위험도가 명시됨
- [x] 기존 데이터 처리 방안이 있음 (백필 불필요, NULL = 어드민 등록)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (snake_case, V{n}__{desc}.sql, COMMENT ON)

## 미결 사항 (해소됨)

- **네임스페이스 파싱 규칙 (확정)**: `/api/{namespace}/...` 두 번째 세그먼트 규칙으로 확정. JPQL `LIKE` 패턴은 `WHERE path_prefix LIKE '/api/{namespace}/%' OR path_prefix = '/api/{namespace}'` (좌측 고정). `text_pattern_ops` 인덱스가 이 LIKE 탐색에 활용된다. 구현 상세는 implementation-plan.md `findOwnerIdByNamespace` 참조.

## 참고
- `db/migration/V4__create_service_client_and_route.sql` — service_route 초기 스키마
- `db/migration/V7__add_owner_id_to_service_client.sql` — owner_id 추가 패턴 참조
- `db/migration/V9__decouple_service_route_from_client.sql` — FK 제거, registered_client_id nullable 전환
- `db/migration/V10__add_index_service_route_enabled.sql` — 현재 최신 마이그레이션 (V11부터 신규)
- `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/entity/ServiceRouteJpaEntity.java` — 엔티티 현재 상태 확인
- `docs/INFRASTRUCTURE.md` — Flyway 컨벤션, 버전 점유 규칙, ddl-auto 정책
- `docs/adr/0015-flyway-container-managed-migration.md` — 마이그레이션 전역화 근거
