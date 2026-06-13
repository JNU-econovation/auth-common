# dynamic-gateway-routing - db-design

## 메타
- **작업명**: dynamic-gateway-routing
- **문서 타입**: db-design
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

이 문서는 `service_route` 테이블의 기존 `registered_client_id` FK 제약 해소, `enabled` 인덱스 추가, `updated_at` 자동 갱신 메커니즘 확정을 다룬다. `service_route`는 V4 마이그레이션에서 이미 생성되어 있으므로 신규 테이블 생성은 없고, **기존 테이블 변경만 2건** 발생한다. DB는 PostgreSQL, 마이그레이션 도구는 Flyway(V 접두사, `__` 구분자 컨벤션), ORM은 Spring Data JPA(Hibernate) + `@AuditingEntityListener`다.

---

## 본문

### 신규 테이블 / 컬렉션

없음. `service_route`는 V4에서 이미 생성되었다. 아래 "ADR-0005 재도입 비교" 섹션을 참조.

---

### ADR-0005 재도입 비교

ADR-0005(2026-06-01)는 "동적 라우팅 구현 복잡도 대비 효용 낮음"을 이유로 기존 `DynamicRouteLocator` 등 4개 파일과 함께 30초 폴링 기반 코드를 제거했다. 당시 `service_route` 테이블 자체는 V4에 **남겨두었으며** 실제로 게이트웨이가 읽지 않는 상태였다.

이번 재도입의 차이점:

| 항목 | ADR-0005 제거 당시 | 이번 재도입 |
|------|-------------------|------------|
| 라우트 갱신 방식 | 30초 주기 폴링 | `RefreshRoutesEvent` 이벤트 기반 즉시 반영 |
| 게이트웨이 읽기 방식 | 자체 `AuthApiRouteClient`(REST) | 옵션 A: R2DBC 직접 / 옵션 B: auth-api REST (미결) |
| FK 구조 | `registered_client_id NOT NULL FK` → 클라이언트 없이 라우트 등록 불가 | FK 제약 제거 → 라우트 독립 CRUD |
| 갱신 지연 | 최대 30초 | 이벤트 발행 즉시 |
| 스키마 변경 | 없음 (테이블 유지) | FK DROP + 인덱스 추가 + `updated_at` 갱신 메커니즘 확정 |

---

### 기존 테이블 / 컬렉션 변경

#### `service_route`

현재 V4 스키마:

```sql
CREATE TABLE service_route (
    id                   BIGSERIAL    PRIMARY KEY,
    route_id             VARCHAR(100) NOT NULL,
    registered_client_id VARCHAR(100) NOT NULL,  -- FK 제약, NOT NULL
    path_prefix          VARCHAR(200) NULL,
    upstream_url         VARCHAR(500) NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_service_route_route_id    UNIQUE (route_id),
    CONSTRAINT uq_service_route_path_prefix UNIQUE (path_prefix),
    CONSTRAINT fk_service_route_client
        FOREIGN KEY (registered_client_id)
        REFERENCES service_client (registered_client_id)
        ON DELETE CASCADE
);
```

**변경 1 — FK 제약 해소**

- **연관 todo**: `[ ] service_route 테이블 확인 — V4 마이그레이션으로 이미 존재. registered_client_id FK 제약이 라우트 독립 CRUD에 걸림돌이 되는지 확인.`
- **연관 todo**: `[ ] Flyway 마이그레이션 V9__decouple_service_route_from_client.sql 작성 (필요 시)`

현재 `registered_client_id NOT NULL + FK`는 `service_route`를 `service_client` 없이 등록하는 것을 막는다. 동적 라우팅에서 라우트는 클라이언트(OAuth SSO)와 독립적인 개념이므로 다음 중 하나를 선택해야 한다.

**권장 선택: 컬럼 NULL 허용 + FK 제약 제거**

`registered_client_id` 컬럼 자체를 nullable로 전환하고, FK 제약(`fk_service_route_client`)을 DROP한다. 컬럼은 보존하여 기존 연관 데이터와 코드의 하위 호환성을 유지한다.

> 컬럼 DROP 대신 nullable 전환을 선택한 이유: 기존 V4 데이터에 `registered_client_id`가 있는 행이 있을 수 있고, `ServiceRouteJpaEntity`(아직 미구현이나 ARCHITECTURE.md에 명시됨)가 이 컬럼을 매핑할 수 있기 때문이다. 추후 완전히 의미가 없어지면 별도 마이그레이션으로 DROP한다.

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| DROP CONSTRAINT | `fk_service_route_client` | FK 제약 삭제 | 라우트를 클라이언트 독립적으로 등록·수정·삭제할 수 있어야 함. 현재 FK ON DELETE CASCADE이므로 service_client 삭제 시 라우트도 같이 삭제되는 부작용 제거. | 낮음 (제약 완화, 데이터 보존) |
| MODIFY | `registered_client_id` | `NOT NULL` → `NULL` 허용 | 신규 라우트는 클라이언트 연결 없이 등록됨. 기존 행은 값 유지 | 낮음 (nullable 완화) |

**변경 2 — `enabled` 인덱스 추가**

- **연관 todo**: `[ ] service_route 인덱스 추가 마이그레이션 — enabled 컬럼 인덱스(idx_service_route_enabled) 추가.`

게이트웨이 기동 및 `RefreshRoutesEvent` 처리 시 `findAllByEnabled(true)`를 실행한다. 현재 `uq_service_route_path_prefix` UNIQUE 인덱스는 V4에 있으나 `enabled` 단독 인덱스가 없어 풀스캔이 발생한다.

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| ADD INDEX | `idx_service_route_enabled` | `(enabled)` | `findAllByEnabled(true)` 쿼리 풀스캔 방지. 게이트웨이 기동·갱신 주요 쿼리 경로. | 낮음 (단순 B-tree 인덱스 추가, 운영 중 테이블 크기 작을 것으로 예상되어 락 시간 미미) |

> 인덱스 컬럼 순서 고려: `enabled` 단독으로 선택도가 낮다(`true`/`false` 두 값). 하지만 접근 패턴이 `enabled = true` 필터 후 전량 반환이므로 복합 인덱스 이점이 없다. 향후 `path_prefix` 범위 조회가 추가된다면 `(enabled, path_prefix)` 복합 인덱스로 교체를 검토한다.

**변경 3 — `updated_at` 자동 갱신 메커니즘 확정**

- **연관 todo**: `[ ] service_route.updated_at 자동 갱신 트리거 또는 JPA @LastModifiedDate 처리 확인`

V4 DDL에서 `updated_at`은 `DEFAULT NOW()`로 선언되어 있으나 UPDATE 시 자동 갱신 트리거가 없다. DB 레벨 트리거를 추가하거나 JPA `@LastModifiedDate` Auditing으로 처리해야 한다.

**권장 선택: JPA `@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)`**

이유:
- `ServiceClientJpaEntity`가 이미 `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate` 패턴을 사용한다(컨벤션 일치).
- `common-infra`의 `@EnableJpaAuditing` AutoConfiguration이 이미 활성화되어 있어 추가 설정 불필요.
- DB 트리거는 Flyway 마이그레이션으로 관리해야 하고 PostgreSQL 함수(`CREATE FUNCTION ... TRIGGER`) 작성이 복잡하다.

이 변경은 스키마 DDL 변경이 아닌 JPA 엔티티 코드 변경이므로 마이그레이션 파일이 필요하지 않다. `ServiceRouteJpaEntity` 구현 시 `@LastModifiedDate @Column(name = "updated_at") private LocalDateTime updatedAt;`를 추가하면 된다.

> 단, JPA Auditing은 `save()` 호출 시에만 동작한다. JPQL 벌크 UPDATE나 네이티브 쿼리는 Auditing을 우회하므로 주의한다. 현재 유스케이스는 단건 CRUD이므로 문제없다.

---

### 최종 `service_route` 테이블 스키마 (변경 후)

| 컬럼 | 타입 | NOT NULL | 기본값 | PK | 제약 / 인덱스 | 비고 |
|------|------|----------|--------|------|--------------|------|
| `id` | BIGSERIAL | Y | AUTO | Y | PK | BIGINT auto-increment |
| `route_id` | VARCHAR(100) | Y | — | | UNIQUE `uq_service_route_route_id` | UUID 문자열 |
| `registered_client_id` | VARCHAR(100) | **N (변경)** | NULL | | (FK 제약 제거됨, 컬럼 보존) | 기존 연관 데이터 호환용 |
| `path_prefix` | VARCHAR(200) | N | NULL | | UNIQUE `uq_service_route_path_prefix` | nullable이나 등록 시 값 있으면 유일 보장 |
| `upstream_url` | VARCHAR(500) | Y | — | | | SSRF 검증은 애플리케이션 레벨 |
| `enabled` | BOOLEAN | Y | TRUE | | INDEX `idx_service_route_enabled` | |
| `created_at` | TIMESTAMP | Y | NOW() | | | `@CreatedDate`, updatable=false |
| `updated_at` | TIMESTAMP | Y | NOW() | | | `@LastModifiedDate`, JPA Auditing |

**제약조건 (변경 후)**

- PK: `id`
- UNIQUE: `route_id` — 게이트웨이 라우트 식별자 유일성 보장
- UNIQUE: `path_prefix` — 동일 경로 패턴 중복 등록 방지. NULL 허용이므로 NULL 중복은 PostgreSQL 기본 동작상 허용됨(NULL ≠ NULL). `path_prefix`가 NULL인 라우트는 2개 이상 등록 가능하나 실제 등록 API에서는 `@NotBlank` 검증으로 NULL 입력을 차단.
- FK: **제거됨** (`fk_service_route_client` DROP) — `service_route`를 `service_client` 없이 독립적으로 관리

**인덱스**

- `uq_service_route_route_id` — `(route_id)` — UNIQUE 인덱스. `findById(routeId)` 단건 조회(관리 API), 게이트웨이 라우트 식별.
- `uq_service_route_path_prefix` — `(path_prefix)` — UNIQUE 인덱스. `existsByPathPrefix` 중복 검증 쿼리, pathPrefix 기반 충돌 감지.
- `idx_service_route_enabled` — `(enabled)` — `findAllByEnabled(true)` 쿼리. 게이트웨이 기동/갱신 시 활성 라우트 전량 조회.

---

### pathPrefix 유일성 / 충돌 방지 — 스키마 권장사항

`uq_service_route_path_prefix` UNIQUE 제약이 DB 레벨에서 정확히 일치하는 문자열 중복을 막는다. 그러나 다음 경우는 DB 제약만으로는 막을 수 없으며 **애플리케이션 레벨(ManageRouteService) 검증이 필수**다.

1. **보호 경로 가로채기**: `/api/v1/auth/login-hijack` 등 `/api/v1/auth/` 하위 경로는 UNIQUE 제약을 통과하지만 auth-api 핵심 경로를 가로챌 수 있다. `ProtectedPathPolicy`(포트, 구현체는 auth-api `ProtectedPathPolicyImpl`)와 비교하여 prefix 일치 여부를 사전에 거부해야 한다.
2. **서브경로 충돌**: `/api/v1`이 이미 등록된 상태에서 `/api/v1/new`를 등록하면 UNIQUE 제약은 통과하지만 라우팅 매칭이 겹칠 수 있다. 서비스가 10개 이하인 초기에는 수동 확인으로 충분하지만, 향후 `pathPrefix` LIKE 검색을 통한 서브경로 중복 검사를 `ManageRouteService`에 추가한다.
3. **`/` 루트 등록 금지**: `path_prefix = '/'`는 전체 트래픽을 단일 업스트림으로 보내는 위험한 설정이다. 애플리케이션에서 거부.

---

### 마이그레이션 순서

main에 V8(drop_unused_spring_session_tables)이 이미 점유되어 있으므로 신규 마이그레이션은 **V9**부터 시작한다.

#### 단계 1: V9 — FK 제거 + `registered_client_id` nullable 전환

파일명: `V9__decouple_service_route_from_client.sql`

```sql
-- service_route를 service_client에서 분리: FK 제약 제거 + nullable 전환
-- 라우트를 클라이언트 독립적으로 CRUD할 수 있도록 정합 유지

ALTER TABLE service_route
    DROP CONSTRAINT fk_service_route_client;

ALTER TABLE service_route
    ALTER COLUMN registered_client_id DROP NOT NULL;

COMMENT ON COLUMN service_route.registered_client_id
    IS '연결된 클라이언트 registeredClientId (nullable — 라우트와 클라이언트 분리로 FK 제거됨. 기존 연관 데이터 호환용 컬럼 보존)';
```

- **롤백 가능 여부**: 가능 (FK 재추가 + NOT NULL 재지정). 단, 롤백 시점에 `registered_client_id = NULL`인 행이 있으면 NOT NULL 복구 전 해당 행 처리 필요.
- **위험도**: 낮음. 제약 완화(DROP 제약)이므로 데이터 손실 없음. 운영 중 단시간 락.

#### 단계 2: V10 — `enabled` 인덱스 추가

파일명: `V10__add_index_service_route_enabled.sql`

```sql
-- service_route enabled 컬럼 인덱스 추가
-- 게이트웨이 기동·RefreshRoutesEvent 처리 시 findAllByEnabled(true) 풀스캔 방지

CREATE INDEX idx_service_route_enabled
    ON service_route (enabled);
```

- **롤백 가능 여부**: 가능 (`DROP INDEX idx_service_route_enabled`).
- **위험도**: 낮음. 운영 데이터 규모가 수백 행 이하일 것으로 예상. PostgreSQL의 `CREATE INDEX`는 기본 `SHARE` 락이며, 테이블이 작으면 영향 미미. 대용량 테이블이라면 `CREATE INDEX CONCURRENTLY`로 전환하되 Flyway는 DDL 트랜잭션 내에서만 실행 가능하므로 별도 수동 실행이 필요함.

> **운영 주의**: `service_route` 테이블이 수만 행 이상이라면 V10 실행 중 테이블 락이 발생한다. 현재 프로젝트 규모(서비스 수십 개 이하)에서는 해당 없으나, 향후 대용량 확장 시 `CONCURRENTLY` 옵션 사용을 검토한다.

**단계 3: `updated_at` 자동 갱신 — 마이그레이션 없음**

JPA `@LastModifiedDate` + `@EntityListeners(AuditingEntityListener.class)`로 처리한다. DDL 변경이 없으므로 Flyway 파일 불필요. `ServiceRouteJpaEntity` 구현 코드에서 완결된다.

---

### seed / 부트스트랩 라우트 마이그레이션 여부 (선택지)

todo 항목: `[ ] auth-api 기동 시 시드 라우트 자동 등록 로직 작성` + `결정 필요: auth-api 자체를 동적 라우트로 옮길지 여부`

두 접근이 있다. 스키마 관점에서는 동일하지만 트레이드오프가 다르다.

**옵션 S-A: Flyway 마이그레이션으로 seed INSERT**

```sql
-- V11__seed_bootstrap_routes.sql (예시, 결정 후 작성)
INSERT INTO service_route (route_id, path_prefix, upstream_url, enabled, registered_client_id)
VALUES
    (gen_random_uuid()::text, '/api/v1/auth/**', '${AUTH_API_URI}', true, NULL),
    (gen_random_uuid()::text, '/oauth2/**',      '${AUTH_API_URI}', true, NULL),
    (gen_random_uuid()::text, '/.well-known/**', '${AUTH_API_URI}', true, NULL)
ON CONFLICT (path_prefix) DO NOTHING;
```

> 단, Flyway SQL 마이그레이션에서 환경변수(`${AUTH_API_URI}`)를 직접 치환하려면 Flyway의 placeholders 기능을 활성화(`spring.flyway.placeholders.*`)해야 한다. URL이 환경마다 다르므로 하드코딩 불가. **권장하지 않음**.

**옵션 S-B: 애플리케이션 기동 시 멱등 INSERT (`@EventListener(ApplicationReadyEvent.class)`)**

URL을 환경변수(`AUTH_API_URI`)에서 읽어 `path_prefix` 충돌 시 무시(`ON CONFLICT DO NOTHING` 또는 `existsByPathPrefix` 확인). Flyway 마이그레이션과 분리되어 환경 의존성이 낮다. **권장**.

스키마 설계에는 두 옵션 모두 동일한 DDL이 적용된다. 이 결정은 구현 단계에서 확정한다.

---

### 게이트웨이 읽기 방식 — DB 스키마 관련 고려사항 (선택지)

todo 항목: `결정 필요: 게이트웨이가 이 테이블을 어떻게 읽나 — R2DBC vs auth-api 경유`

저장 스키마는 두 옵션 모두 동일하다. 다만 스키마 설계 시 고려할 차이가 있다.

**옵션 A: api-gateway가 R2DBC로 Postgres에 직접 접근**

- api-gateway에 `spring-boot-starter-data-r2dbc` + `r2dbc-postgresql` 의존성 추가 필요.
- api-gateway의 DB 접속 환경변수(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)를 별도 관리. 현재 auth-api와 동일한 Postgres 인스턴스를 공유한다면 접근 권한 공유 가능.
- R2DBC는 Reactive(비동기)이며 api-gateway의 WebFlux 스택과 일치한다.
- `service_route` 테이블에 대한 `SELECT` 전용 DB 사용자를 게이트웨이에 분리하면 권한 최소화 가능 (추천).
- 스키마 변경(컬럼 추가 등)이 게이트웨이 R2DBC 매핑 코드에 즉시 영향을 미치므로 컬럼 변경 시 게이트웨이도 함께 배포해야 한다.

**옵션 B: api-gateway가 auth-api REST API를 통해 라우트 조회**

- 게이트웨이는 DB 직접 접근 없음. DB 스키마 변경이 게이트웨이 코드에 영향 없음.
- auth-api가 다운되면 게이트웨이가 라우트를 갱신할 수 없음(chicken-and-egg 문제: auth-api 경로 자체도 동적 라우트라면 부팅 순서 의존성 발생).
- 스키마 설계 복잡도 낮음.

---

### 데이터 정합성 / 운영 고려사항

1. **기존 데이터 백필**: V9 마이그레이션은 `registered_client_id`를 nullable로만 바꾸며 기존 데이터를 변경하지 않는다. 기존 행의 `registered_client_id` 값은 그대로 보존된다. 백필 불필요.

2. **NOT NULL → NULL 완화 시 기존 코드 호환성**: `ServiceRouteJpaEntity` (아직 미구현)가 `registered_client_id`를 `@Column(nullable = false)`로 선언할 경우, V9 적용 후 코드와 DDL이 불일치한다. 엔티티 구현 시 반드시 `nullable = true`로 선언해야 한다.

3. **FK ON DELETE CASCADE 제거 영향**: 현재 `service_client` 삭제 시 연결된 `service_route`도 CASCADE 삭제되었다. V9 이후 이 동작이 사라진다. 클라이언트 삭제 시 라우트를 어떻게 처리할지 `ManageRouteService` 또는 `RegisterOAuthClientService` 삭제 로직에서 명시적으로 결정해야 한다(라우트 독립 유지 또는 애플리케이션 레벨 삭제).

4. **마이그레이션 도중 구버전 코드 호환성**:
   - V9(FK DROP + nullable) 적용 후 구버전 코드가 `registered_client_id NOT NULL`을 기대하는 INSERT를 실행하면 NULL 값 삽입이 가능해지지만 문제없다. 제약이 완화되는 방향이므로 구버전 코드도 정상 동작한다.
   - V10(인덱스 추가)는 순수한 인덱스 추가이므로 코드 호환성 영향 없음.

5. **`updated_at` 갱신 시점**: JPA `@LastModifiedDate`는 엔티티가 `save()`될 때 JPA Auditing이 현재 시각을 주입한다. V4에서 `updated_at DEFAULT NOW()`로 설정된 기존 행의 초기값은 생성 시각이며, 이후 UPDATE 시 Auditing이 자동 갱신한다.

---

## 체크리스트

- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨
- [x] 모든 인덱스에 사유가 있음
- [x] FK/참조 정책이 명시됨 (제거 사유 포함)
- [x] 마이그레이션 순서와 위험도가 명시됨
- [x] 기존 데이터 처리 방안이 있음 (백필 불필요 확인)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (BIGSERIAL PK, snake_case, `created_at`/`updated_at`, `uq_`/`idx_`/`fk_` 네이밍)
- [x] ADR-0005 재도입 차이점 기재
- [x] 게이트웨이 읽기 방식(R2DBC vs REST) 선택지 기재
- [x] seed/부트스트랩 라우트 마이그레이션 여부 선택지 기재

---

## 참고

- `db/migration/V4__create_service_client_and_route.sql` — `service_route` 기존 DDL
- `db/migration/V7__add_owner_id_to_service_client.sql` — 최신 마이그레이션 (V7 확인)
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceClientJpaEntity.java` — JPA 엔티티 컨벤션 참조
- `docs/adr/0005-static-yaml-routing-over-dynamic.md` — ADR-0005 (supersede 대상)
- `docs/ARCHITECTURE.md` — service-client 헥사고날 구조, `ServiceRoute` record 명시
- `docs/CONVENTION.md` — 네이밍/테스트/Lombok 컨벤션
