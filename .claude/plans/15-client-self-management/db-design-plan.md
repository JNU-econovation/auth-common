# client-self-management - db-design

## 메타
- **작업명**: client-self-management
- **문서 타입**: db-design
- **작성일**: 2026-06-22
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

이 문서는 회원 셀프서비스 OAuth 클라이언트 관리(목록·상세 조회, 수정, 삭제) 기능에 필요한 DB 변경 사항을 다룬다. 신규 테이블은 없으며, `service_route.registered_client_id` 컬럼 활용 방침 확정(신규 셀프 라우트부터 값 채움)과 해당 컬럼 인덱스 추가(V13)가 핵심 변경이다. `service_client` 테이블은 기존 V7 인덱스로 충분하다. 사용 DB는 PostgreSQL이며, 마이그레이션 도구는 Flyway(SQL, `db/migration/` 단일 소스)이고 현재 최신 버전은 V12이다.

---

## 본문

### 신규 테이블 / 컬렉션

없음. 이번 기능은 기존 `service_client`·`service_route`·`oauth2_registered_client` 스키마로 완전히 처리 가능하다.

---

### 기존 테이블 / 컬렉션 변경

#### `service_client`
- **연관 todo**: `[ ] service_client.owner_id에 별도 INDEX가 없으면 V13__add_index_service_client_owner_id.sql 작성 검토`

**현황 확인 결과**

V7(`add_owner_id_to_service_client`)에서 `owner_id` 컬럼 추가와 동시에 `idx_service_client_owner_id` 인덱스를 이미 생성하였다.

```sql
-- V7 기준 (db/migration/V7__add_owner_id_to_service_client.sql)
CREATE INDEX idx_service_client_owner_id ON service_client (owner_id);
```

따라서 `findByOwnerId` 조회(목록 API)는 이미 인덱스를 타며 **추가 마이그레이션 불필요**하다.

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| 없음 | — | V7의 `idx_service_client_owner_id` 기존 존재 확인 | 이미 충분 | 해당 없음 |

**`registered_client_id` UNIQUE 제약 확인**

V4에서 `CONSTRAINT uq_service_client_registered_client_id UNIQUE (registered_client_id)`로 생성되었다. 이 UNIQUE 제약은 PostgreSQL 내부적으로 B-tree 인덱스를 생성하므로 `deleteByRegisteredClientId` 쿼리의 `WHERE registered_client_id = ?` 조건은 별도 인덱스 없이도 인덱스 스캔이 보장된다. **추가 인덱스 불필요**하다.

---

#### `service_route`
- **연관 todo**: `[ ] service_route.registered_client_id 컬럼 활용 방침 결정` (확정 — 신규 셀프 라우트부터 값 채움)

**현황 확인 결과**

| 마이그레이션 | 변경 내용 |
|---|---|
| V4 | `registered_client_id VARCHAR(100) NOT NULL`, FK `fk_service_route_client → service_client(registered_client_id) ON DELETE CASCADE` 생성 |
| V9 | FK `fk_service_route_client` 제거 + `registered_client_id DROP NOT NULL` (nullable 전환). 코멘트: "기존 연관 데이터 호환용 컬럼 보존" |
| V11 | `owner_id BIGINT NULL` 추가 |
| V12 | `idx_service_route_owner_id`, `idx_service_route_path_prefix_text` 추가 |

현재 `ServiceRouteJpaEntity.from()` 및 `fromWithId()`에서 `entity.registeredClientId = null`로 **항상 null 세팅**한다. 즉 셀프 등록된 라우트도 `registered_client_id = NULL` 상태로 저장되고 있다.

**`registered_client_id` 활용 방침: 신규 등록부터 값 채움 (변경)**

이번 작업에서 `registered_client_id`를 채우도록 엔티티 변환 로직을 수정한다. 근거:

1. **owner_id만으로는 클라이언트↔라우트를 1:1로 특정할 수 없다.** 한 회원(memberId)은 클라이언트를 최대 5개 가질 수 있어, `service_route.owner_id = memberId`로 조회하면 같은 회원의 다른 클라이언트에 속한 라우트까지 섞인다. `deleteByOwnerId(ownerId)`를 그대로 쓰면 타 클라이언트의 라우트까지 삭제된다.

2. **`registered_client_id`는 클라이언트를 단일하게 식별한다.** `service_client.registered_client_id`는 UNIQUE 제약(V4)으로 보장된 1:1 식별자다. `service_route.registered_client_id = ?` 조건이면 해당 클라이언트의 라우트만 정확히 조회·삭제된다.

3. **컬럼은 이미 스키마에 존재한다.** V9에서 nullable로 전환된 것이지 컬럼 자체는 살아 있으므로 DDL 추가 없이 값만 채우면 된다.

4. **V9의 분리 의도를 존중하면서도 활용 가능하다.** V9는 "라우트를 클라이언트 독립적으로 CRUD"하기 위해 FK를 제거했다. FK 없이도 애플리케이션 레벨에서 `registered_client_id`를 채우고 조회하는 것은 이 의도와 충돌하지 않는다. 독립 CRUD 가능성(어드민이 라우트를 먼저 생성)은 어드민 경로에서 `registered_client_id = null`을 허용하면 유지된다. 셀프 등록 경로에서만 채운다.

**애플리케이션 변경 범위 (스키마 무변경, 엔티티 로직 변경)**

- `ServiceRouteJpaEntity.from(route)`: `entity.registeredClientId = null` → `entity.registeredClientId = route.registeredClientId()` (셀프 등록 시 도메인에서 값 전달)
- `ServiceRouteJpaEntity.fromWithId(id, route)`: 동일 변경
- `ServiceRoute` 도메인 record: `registeredClientId` 필드 추가 또는 별도 팩토리 메서드로 셀프 등록 시 값 전달 설계 (implementation-plan에서 상세 결정)

어드민 경로(`ServiceRoute.create(pathPrefix, upstreamUrl, enabled)`)에서는 `registeredClientId = null`을 그대로 유지해 V9 분리 의도를 보존한다.

**인덱스: V13 신규 추가 필요**

`registered_client_id`로 다음 쿼리가 발생한다:

- `findByRegisteredClientId(registeredClientId)` — 상세 조회·라우트 매핑용 단건 조회
- `deleteAllByRegisteredClientId(registeredClientId)` — 클라이언트 삭제 시 연결 라우트 캐스케이드 삭제

V12까지의 인덱스 현황: `idx_service_route_owner_id`(owner_id), `idx_service_route_path_prefix_text`(path_prefix text_pattern_ops). `registered_client_id`에는 인덱스 없음.

단건 조회는 테이블 전체 스캔이 발생하므로 인덱스가 필요하다. 단, `service_route`는 운영 초기(셀프 등록 기능이 ADR-0018에서 막 도입)라 현재 레코드 수가 수십 건 이하일 가능성이 높다. 그럼에도 향후 라우트 규모 성장 대비와 인덱스 추가 비용이 미미하다는 점을 고려해 V13에서 선제적으로 추가한다.

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| ADD INDEX (V13) | `idx_service_route_registered_client_id` | `(registered_client_id)` | `findByRegisteredClientId` 단건 조회 + `deleteAllByRegisteredClientId` 삭제 — 클라이언트↔라우트 1:1 조회·삭제 경로 | 낮음 (소규모 테이블, 비동기 인덱스 빌드 불필요) |

**제약조건**

- 컬럼 추가 없음. `registered_client_id VARCHAR(100) NULL`은 V4 생성·V9 nullable 전환으로 이미 스키마에 존재.
- FK 재도입 없음 (하단 "FK 재도입 여부 검토" 참조).

---

#### `oauth2_registered_client` (SAS 표준 테이블)
- **연관 todo**: `[ ] SasClientRegistrarAdapter.unregisterClient — JdbcTemplate 직접 DELETE, 테이블명/컬럼명 의존성 주석 명시`

이 테이블은 SAS 1.x가 관리하는 표준 스키마(V2)이며 스키마 변경 대상이 아니다. `unregisterClient`는 애플리케이션 레이어에서 `JdbcTemplate`으로 아래 쿼리를 실행한다.

```sql
DELETE FROM oauth2_registered_client WHERE client_id = ?
```

- `client_id` 컬럼에는 `uq_oauth2_registered_client_client_id` UNIQUE 인덱스(V2)가 있으므로 인덱스 스캔 보장.
- SAS 버전 업그레이드 시 테이블명/컬럼명 변경 가능성 있음 → `SasClientRegistrarAdapter`에 `// SAS 1.x: oauth2_registered_client.client_id` 주석 필수.

---

### FK 재도입 여부 검토

**`service_route.registered_client_id → service_client.registered_client_id ON DELETE CASCADE` FK를 재도입하지 않는 이유:**

1. **V9의 명시적 의도.** V9 마이그레이션 헤더: "라우트를 클라이언트 독립적으로 CRUD할 수 있도록 정합 유지." FK가 없어야 어드민이 라우트를 독립적으로 생성·수정·삭제할 수 있다.

2. **애플리케이션 레벨 삭제의 명확성.** `deleteMyClient` 유스케이스는 `serviceRouteRepository.deleteByRegisteredClientId` → `serviceClientRepository.deleteByClientId` → `sasClientRegistrar.unregisterClient` 순서를 단일 `@Transactional` 내에서 명시적으로 제어한다. DB FK 캐스케이드는 삭제 순서를 암묵적으로 처리하므로 디버깅·감사 추적이 어려워진다.

3. **어드민 라우트와 셀프 라우트의 혼재.** 어드민 경로 라우트는 `registered_client_id = null`을 유지한다. null 컬럼에 FK 제약을 걸면 NOT NULL 컬럼만 보호되어 셀프 라우트에는 실질 보호 효과가 없고, 어드민 라우트(null)에는 FK 자체가 무의미하다.

---

### 백필 전략

기존 셀프 라우트(`owner_id IS NOT NULL AND registered_client_id IS NULL`)를 `registered_client_id`로 채울 경우, 신규 조회/삭제 로직(`findByRegisteredClientId`, `deleteByRegisteredClientId`)에 잡히게 된다. 백필 없이 두면 기존 셀프 라우트는 조회/삭제에서 누락된다.

**선택지 A — 백필 마이그레이션**

`service_route`의 `owner_id`를 단서로 `service_client`를 조인해 `registered_client_id`를 채운다.

```sql
-- 개념 예시 (실제 마이그레이션 아님)
UPDATE service_route sr
SET registered_client_id = (
    SELECT sc.registered_client_id
    FROM service_client sc
    WHERE sc.owner_id = sr.owner_id
    LIMIT 1
)
WHERE sr.owner_id IS NOT NULL
  AND sr.registered_client_id IS NULL;
```

- **위험**: `LIMIT 1`은 한 회원이 클라이언트 1개만 있을 때만 안전하다. 회원당 클라이언트가 2개 이상이면 잘못된 클라이언트에 라우트가 연결될 수 있다. 셀프 등록 기능(ADR-0018)은 최근 도입됐으므로 운영 데이터에 다수 클라이언트를 가진 회원이 있을 가능성은 낮지만, 검증 없이 적용하면 데이터 오염 위험이 있다.
- **적합 조건**: 회원당 클라이언트가 정확히 1개임을 사전에 검증(`SELECT owner_id FROM service_route WHERE owner_id IS NOT NULL AND registered_client_id IS NULL GROUP BY owner_id HAVING COUNT(*) > 1`)하고, 해당 행이 0건일 때만 안전하게 실행 가능.

**선택지 B — 백필 없이 신규 등록부터만 채움 (권장)**

기존 셀프 라우트는 `registered_client_id = null` 상태로 두고, 이번 기능 배포 이후 신규 등록 라우트부터만 채운다.

- **운영 현실**: ADR-0018(라우트 셀프 등록 흡수)은 현재 `feat/route-self-registration` 브랜치의 최근 커밋으로, 아직 운영 배포 전이거나 배포 직후다. 실제로 `registered_client_id = null`인 셀프 라우트 레코드는 거의 0건에 가까울 가능성이 높다.
- **기존 셀프 라우트 처리**: 레코드가 있더라도 어드민이 해당 라우트를 정리하거나 회원에게 재등록을 안내하는 방식으로 처리 가능하다.
- **코드 단순성**: 백필 분기 처리 없이 `registered_client_id IS NOT NULL` 경로만 구현하면 된다.

**결론: 선택지 B 채택.** 배포 전 운영 DB에서 `SELECT COUNT(*) FROM service_route WHERE owner_id IS NOT NULL AND registered_client_id IS NULL` 쿼리로 실제 레코드 수를 확인하고, 0건이면 B 확정. 1건 이상이면 해당 레코드에 대해 선택지 A의 사전 검증 쿼리를 실행한 후 수동 백필 또는 어드민 정리로 처리한다.

---

### 마이그레이션 순서

프로젝트 네이밍 컨벤션: `V{N}__{snake_case_description}.sql` (V4, V7, V9, V11, V12 패턴 참조).

| 단계 | 파일명 | 내용 | 롤백 가능 | 위험도 |
|------|--------|------|-----------|--------|
| 1 | `V13__add_index_service_route_registered_client_id.sql` | `CREATE INDEX idx_service_route_registered_client_id ON service_route (registered_client_id)` | 가능 (`DROP INDEX`) | 낮음 — 테이블 소규모, PostgreSQL `CREATE INDEX`는 기본적으로 락 없이 비동기 빌드 가능(`CONCURRENTLY` 옵션 고려) |

**V13 마이그레이션 내용 (제안)**

```sql
-- registered_client_id 조회 인덱스 — 클라이언트별 라우트 단건 조회 및 삭제
-- (findByRegisteredClientId, deleteAllByRegisteredClientId)
CREATE INDEX idx_service_route_registered_client_id
    ON service_route (registered_client_id);
```

운영 중 대규모 테이블이라면 `CREATE INDEX CONCURRENTLY`를 사용해야 하지만, 현재 테이블 규모에서는 일반 `CREATE INDEX`로 충분하다. Flyway는 트랜잭션 내에서 DDL을 실행하는데, PostgreSQL에서 `CREATE INDEX CONCURRENTLY`는 트랜잭션 내 실행이 불가하므로 일반 `CREATE INDEX`를 사용한다.

---

### 데이터 정합성 / 운영 고려사항

#### `registered_client_id` 값 채움 — 애플리케이션 변경 방향

`ServiceRouteJpaEntity.from()`/`fromWithId()`에서 `registeredClientId` 세팅 로직을 변경한다. 이는 스키마 변경이 없으므로 `ddl-auto=validate` 통과에 영향을 주지 않는다.

#### ddl-auto=validate 정합성

`auth-api`는 `spring.jpa.hibernate.ddl-auto=validate`를 사용한다. 이번 작업에서 JPA 엔티티가 `registered_client_id`를 실제로 세팅하도록 변경되어도, 컬럼은 V4 생성·V9 nullable 전환으로 이미 스키마에 존재(`VARCHAR(100) NULL`)하므로 validate는 정상 통과한다.

- `ServiceRouteJpaEntity`: `@Column(name = "registered_client_id", nullable = true, length = 100)` — 이미 올바르게 매핑됨. 엔티티 어노테이션 변경 불필요.
- `ServiceClientJpaEntity`: 스키마 변경 없음, `owner_id`·`registered_client_id`·`client_secret_hash` 등 모든 컬럼이 이미 엔티티에 매핑됨. validate 통과.

신규 메서드(`findByOwnerId`, `findByRegisteredClientIdAndOwnerId`, `deleteByRegisteredClientId`, `findByRegisteredClientId`, `deleteAllByRegisteredClientId`)는 Spring Data JPA 메서드 추가이며 스키마와 무관하므로 validate에 영향 없다.

#### 기존 데이터 백필

위 "백필 전략" 섹션 참조. 선택지 B(백필 없음)을 채택하므로 마이그레이션 파일에 UPDATE 구문은 포함하지 않는다. 배포 전 운영 DB에서 `SELECT COUNT(*) FROM service_route WHERE owner_id IS NOT NULL AND registered_client_id IS NULL`로 실제 레코드 수 확인 필수.

#### `deleteMyClient` 삭제 순서 (애플리케이션 레벨 캐스케이드)

단일 `@Transactional` 내에서:
1. `serviceRouteRepository.deleteByRegisteredClientId(registeredClientId)` — 연결 라우트 삭제 (있으면 afterCommit refresh 등록)
2. `serviceClientRepository.deleteByClientId(clientId)` — service_client 삭제
3. `sasClientRegistrar.unregisterClient(clientId)` — oauth2_registered_client 삭제

FK 없이 순서를 명시적으로 제어하므로 트랜잭션 롤백 시 모두 원복된다.

#### `SasClientRegistrarAdapter.unregisterClient` 운영 주의

`oauth2_registered_client` 삭제 시 해당 클라이언트로 발급된 `oauth2_authorization` 레코드가 남는다. SAS 표준 스키마에서 `oauth2_authorization.registered_client_id`는 논리적 참조(FK 제약 없음)이므로 고아 레코드가 생겨도 DB 정합성 오류는 발생하지 않는다. 단, 삭제된 클라이언트의 `oauth2_authorization` 레코드는 자동 청소되지 않는다.

- 현재 Auth Server 운용 중 이슈 없으면 허용 가능.
- 대규모 삭제 빈도가 높아지면 별도 배치 정리 정책 검토 권장(이번 작업 범위 밖).

#### 인덱스 활용 정리 (교정 후 쿼리 패턴 매핑)

| 쿼리 | 사용 인덱스 | 존재 여부 |
|------|------------|-----------|
| `SELECT ... FROM service_client WHERE owner_id = ?` (목록 조회) | `idx_service_client_owner_id` (V7) | 존재 |
| `SELECT ... FROM service_client WHERE registered_client_id = ? AND owner_id = ?` (소유권 단건) | `uq_service_client_registered_client_id` (V4 UNIQUE) | 존재 |
| `DELETE FROM service_client WHERE registered_client_id = ?` | `uq_service_client_registered_client_id` (V4) | 존재 |
| `SELECT ... FROM service_route WHERE registered_client_id = ?` (클라이언트별 라우트 조회) | `idx_service_route_registered_client_id` (V13 신규) | V13 추가 후 존재 |
| `DELETE FROM service_route WHERE registered_client_id = ?` (클라이언트 삭제 시 라우트 캐스케이드) | `idx_service_route_registered_client_id` (V13 신규) | V13 추가 후 존재 |
| `DELETE FROM oauth2_registered_client WHERE client_id = ?` | `uq_oauth2_registered_client_client_id` (V2) | 존재 |

---

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 (신규 컬럼 없으므로 기존 컬럼 현황으로 대체)
- [x] 모든 인덱스에 사유가 있음
- [x] FK/참조 정책이 명시됨 (재도입 거부 근거 포함)
- [x] 마이그레이션 순서와 위험도가 명시됨 (V13 신규 1건)
- [x] 기존 데이터 처리 방안이 있음 (백필 전략 선택지 A/B 트레이드오프 제시, B 채택 + 배포 전 확인 절차)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (신규 DDL은 기존 패턴 그대로)

---

## 참고
- `db/migration/V4__create_service_client_and_route.sql` — `service_client.registered_client_id` UNIQUE 제약 원본, `service_route.registered_client_id` 초기 NOT NULL FK 생성
- `db/migration/V7__add_owner_id_to_service_client.sql` — `idx_service_client_owner_id` 기존 존재 확인
- `db/migration/V9__decouple_service_route_from_client.sql` — FK 제거·nullable 전환 의도 ("기존 연관 데이터 호환용 컬럼 보존")
- `db/migration/V11__add_owner_id_to_service_route.sql` — `service_route.owner_id` 추가
- `db/migration/V12__add_indexes_to_service_route.sql` — 현재 최신; `registered_client_id` 인덱스 부재 확인
- `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/entity/ServiceRouteJpaEntity.java` — `from()`/`fromWithId()`에서 `registeredClientId = null` 고정 확인 (교정 대상)
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/domain/ServiceRoute.java` — `registeredClientId` 필드 부재 확인 (도메인 record 확장 필요)
- `services/libs/service-client/src/main/java/com/econo/auth/client/persistence/entity/ServiceClientJpaEntity.java` — 컬럼 매핑 전체 확인
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/repository/ServiceRouteRepository.java` — 포트 확장 대상
