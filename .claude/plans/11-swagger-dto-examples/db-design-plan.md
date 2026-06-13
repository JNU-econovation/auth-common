# 11-swagger-dto-examples - db-design

## 메타
- **작업명**: 11-swagger-dto-examples
- **문서 타입**: db-design
- **작성일**: 2026-06-14
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

이 작업은 `auth-api` 컨트롤러 inner DTO를 `presentation/dto/` 표준 record로 추출하고 `@Schema(example)` 메타데이터를 추가하는 **presentation 레이어 한정 리팩터**다. DB 스키마, 마이그레이션, 엔티티, 리포지토리 어느 것도 변경하지 않는다. 사용 DB는 PostgreSQL이며 마이그레이션 도구는 Flyway(`db/migration/V{n}__{desc}.sql`)다.

**결론: 이 작업에서 DB 변경은 전혀 없다.**

## 본문

### DB 변경 없음 — 근거와 확인 사항

#### 영향 범위 분석

todo의 `DB 작업` 섹션은 명시적으로 "해당 없음 (presentation 계층 한정 변경. DB 스키마·마이그레이션·엔티티 변경 없음)"으로 선언되어 있다.

작업 범위는 다음 세 영역에만 국한된다.

| 변경 대상 | 해당 여부 | 비고 |
|---|---|---|
| `presentation/dto/*.java` (신규 생성/보강) | Y | Java record 추출, `@Schema` 메타데이터 추가 |
| `presentation/controller/*.java` (inner DTO 제거) | Y | inner record 참조를 외부 DTO로 교체 |
| `presentation/docs/*ApiDocs.java` (import 갱신) | Y | Swagger 인터페이스 import 경로 변경 |
| DB 테이블 / 컬럼 / 인덱스 | **N** | 변경 없음 |
| Flyway 마이그레이션 파일 | **N** | 신규 파일 없음, 기존 V1~V8 무변경 |
| JPA 엔티티 (`persistence/entity`) | **N** | 변경 없음 |
| Spring Data 리포지토리 | **N** | 변경 없음 |

#### 기존 스키마 무변경 확인

`INFRASTRUCTURE.md`의 현재 마이그레이션 버전 현황 기준, 이 작업과 무관한 기존 테이블은 다음과 같이 그대로 유지된다.

| 마이그레이션 | 테이블 | 상태 |
|---|---|---|
| V1 | `members` | 무변경 |
| V2 | `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent` | 무변경 |
| V3 | `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` (V8로 삭제) | 무변경 |
| V4 | `service_client`, `service_route` | 무변경 |
| V5 | `service_client.grant_type` nullable | 무변경 |
| V6 | `members.role` | 무변경 |
| V7 | `service_client.owner_id`, `client_secret_hash` | 무변경 |
| V8 | Spring Session 테이블 제거 | 무변경 |

`service_route` 등 동적 라우팅 관련 테이블도 이 작업의 영향을 받지 않는다.

#### presentation → DB 계층 분리

이 프로젝트는 `presentation → application → persistence` 3계층을 엄격히 분리하며(ADR-0014), DTO는 presentation 계층에만 존재한다. presentation DTO의 추출·재구성은 persistence 계층(엔티티·리포지토리·DB 스키마)에 어떠한 영향도 주지 않는다.

### 신규 테이블 / 컬렉션

없음.

### 기존 테이블 / 컬렉션 변경

없음.

### 마이그레이션 순서

해당 없음. 이 작업에서 Flyway 마이그레이션 파일은 생성하지 않는다.

### 데이터 정합성 / 운영 고려사항

- 백필 필요 없음.
- NOT NULL 추가 없음.
- 인덱스 빌드 없음.
- 마이그레이션 도중 애플리케이션 호환성 이슈 없음 (스키마 불변).

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 — todo `DB 작업` 섹션이 "해당 없음"이므로 매핑 항목 없음
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 — 신규 컬럼 없음
- [x] 모든 인덱스에 사유가 있음 — 신규 인덱스 없음
- [x] FK/참조 정책이 명시됨 — 신규 FK 없음
- [x] 마이그레이션 순서와 위험도가 명시됨 — 마이그레이션 없음
- [x] 기존 데이터 처리 방안이 있음 — 해당 없음
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 — 스키마 무변경이므로 기존 컨벤션 그대로 유지

## 참고
- `docs/INFRASTRUCTURE.md` — PostgreSQL 스키마 컨벤션, Flyway 마이그레이션 버전 현황
- `docs/adr/0014-layered-architecture.md` — 3계층(presentation/application/persistence) 분리 원칙
- `docs/adr/0015-flyway-container-managed-migration.md` — 마이그레이션 전역화 근거
- `.claude/plans/11-swagger-dto-examples/todo.md` — `DB 작업` 섹션 "해당 없음" 선언 확인
