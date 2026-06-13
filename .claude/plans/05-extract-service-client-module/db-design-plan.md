# extract-service-client-module - db-design

## 메타
- **작업명**: extract-service-client-module
- **문서 타입**: db-design
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md

## 개요

이 작업은 `auth-api` 내부 코드를 `services/libs/service-client` 모듈로 이동하는 순수 리팩토링이다. DDL 변경, 컬럼 추가/삭제, 인덱스 변경은 전혀 없다. DB는 PostgreSQL이며 마이그레이션 도구는 Flyway(`V{N}__{desc}.sql` 컨벤션, 위치 `services/libs/auth-infra/src/main/resources/db/migration/`)이다.

---

## 본문

### DB 변경 없음 — 공식 선언

이 작업에서 발생하는 마이그레이션 파일은 없다. 신규 Flyway 스크립트를 작성하지 않는다.

- **연관 todo**: `DB 작업 — 해당 없음` (todo.md §DB 작업)

---

### 참조 테이블 현황 (읽기 전용 참조 — 스키마 변경 없음)

이동 대상 JPA 엔티티가 매핑하는 테이블 목록이다.

| 테이블 | 관리 마이그레이션 | PK 전략 | 주요 제약 |
|--------|------------------|---------|-----------|
| `service_client` | V4 + V5 | `BIGSERIAL` (id) | `uq_service_client_registered_client_id`, `uq_service_client_name` |
| `service_route` | V4 | `BIGSERIAL` (id) | `uq_service_route_route_id`, `uq_service_route_path_prefix`, `fk_service_route_client → service_client(registered_client_id) ON DELETE CASCADE` |
| `oauth2_registered_client` | V2 (SAS 표준 DDL) | `VARCHAR(100)` (id) | `uq_oauth2_registered_client_client_id` |
| `members` | V1 | `BIGINT GENERATED ALWAYS AS IDENTITY` (id) | `uq_members_login_id` |

---

### JPA 매핑 — 패키지 이동만, 매핑 값 불변

| 엔티티 클래스 | 이전 패키지 | 이후 패키지 | `@Table(name=...)` | `@Column` 변경 |
|---------------|------------|------------|--------------------|----------------|
| `ServiceClientJpaEntity` | `com.econo.auth.api.adapter.out.persistence` | `com.econo.auth.client.adapter.out.persistence` | `service_client` (유지) | 없음 |
| `ServiceRouteJpaEntity` | `com.econo.auth.api.adapter.out.persistence` | `com.econo.auth.client.adapter.out.persistence` | `service_route` (유지) | 없음 |

`@Entity(name=...)` / `@Table(name=...)` / `@Column(name=...)` 값은 변경하지 않는다. Hibernate가 생성하는 SQL은 패키지 이동 전후 완전히 동일하다.

---

### ⚠️ InfraConfig.java — JPA 컨텍스트 실패 위험 (DDL 무관, 런타임 영향)

`auth-infra`의 `InfraConfig.java`는 현재 다음과 같이 선언되어 있다.

```
@EnableJpaRepositories(basePackages = {"com.econo.auth.infra", "com.econo.auth.api.adapter.out.persistence"})
@EntityScan(basePackages            = {"com.econo.auth.infra", "com.econo.auth.api.adapter.out.persistence"})
```

패키지 이동 후 `com.econo.auth.api.adapter.out.persistence` 하위에 엔티티/리포지토리가 존재하지 않게 된다. 이 값을 갱신하지 않으면 JPA 컨텍스트가 `ServiceClientJpaEntity` / `ServiceRouteJpaEntity`를 인식하지 못해 **애플리케이션 기동 실패**가 발생한다.

필요한 변경 (코드 변경, DDL 변경 아님):

| 어노테이션 | 제거할 패키지 | 추가할 패키지 | 반드시 유지 |
|-----------|-------------|-------------|------------|
| `@EnableJpaRepositories` | `"com.econo.auth.api.adapter.out.persistence"` | `"com.econo.auth.client.adapter.out.persistence"` | `"com.econo.auth.infra"` |
| `@EntityScan` | `"com.econo.auth.api.adapter.out.persistence"` | `"com.econo.auth.client.adapter.out.persistence"` | `"com.econo.auth.infra"` |

변경 후 최종 basePackages 값: `{"com.econo.auth.infra", "com.econo.auth.client.adapter.out.persistence"}`

DB 스키마에는 영향 없음. 테이블/컬럼/인덱스 변경 없음.

---

### Flyway 마이그레이션 파일 — 현행 유지

`service_client` / `service_route` 테이블을 관리하는 V4/V5 파일은 `auth-infra`에 그대로 유지한다.

| 파일 | 위치 | 변경 여부 |
|------|------|-----------|
| `V1__create_members_table.sql` | `auth-infra/src/main/resources/db/migration/` | 변경 없음 |
| `V2__create_sas_tables.sql` | 동일 | 변경 없음 |
| `V3__create_spring_session_tables.sql` | 동일 | 변경 없음 |
| `V4__create_service_client_and_route.sql` | 동일 | 변경 없음 |
| `V5__make_grant_type_nullable.sql` | 동일 | 변경 없음 |

---

### 마이그레이션 순서

신규 마이그레이션 없음. 순서 계획 불필요.

---

### 데이터 정합성 / 운영 고려사항

- **기존 데이터 백필**: 불필요 (DDL 변경 없음)
- **NOT NULL 추가**: 없음
- **인덱스 빌드 락**: 없음
- **구버전 코드와의 호환성**: `InfraConfig.java` basePackages 갱신이 코드 배포와 동시에 이루어져야 한다. 갱신 누락 상태로 배포하면 JPA 컨텍스트 초기화 실패로 서버가 기동되지 않는다. 롤백은 코드 되돌리기로 즉시 가능하며, DB 롤백은 불필요하다.
- **위험도**: 없음 (DDL 변경 없음, 마이그레이션 락/데이터 손실 위험 없음)

---

### 향후 후속 작업 후보 (이번 작업 범위 외)

**모듈별 마이그레이션 분리**: `service-client` 모듈이 자신의 마이그레이션 스크립트(V4/V5)를 직접 소유하도록 `auth-infra`에서 이관하는 작업. 현재는 `auth-infra` 단일 classpath에 Flyway가 스캔하므로, 분리하려면 Flyway 멀티 location 설정과 `flyway_schema_history` 레코드 처리가 필요하다. 이번 추출 작업에서는 수행하지 않는다.

---

## 체크리스트

- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 (DB 작업 항목 = "해당 없음" — 일치)
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 (참조 테이블 현황 표 기록)
- [x] 모든 인덱스에 사유가 있음 (기존 인덱스 유지, 신규 없음)
- [x] FK/참조 정책이 명시됨 (`service_route → service_client ON DELETE CASCADE` 확인)
- [x] 마이그레이션 순서와 위험도가 명시됨 (신규 마이그레이션 없음, 위험도 없음)
- [x] 기존 데이터 처리 방안이 있음 (불필요 — 명시)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (변경 없으므로 준수)

## 참고

- `services/libs/auth-infra/src/main/resources/db/migration/V4__create_service_client_and_route.sql`
- `services/libs/auth-infra/src/main/resources/db/migration/V5__make_grant_type_nullable.sql`
- `services/libs/auth-infra/src/main/java/com/econo/auth/infra/config/InfraConfig.java` — basePackages 갱신 대상
- `.claude/plans/05-extract-service-client-module/todo.md` §DB 작업, §4. auth-infra 수정
