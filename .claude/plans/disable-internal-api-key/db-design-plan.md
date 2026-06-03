# disable-internal-api-key - db-design

## 메타
- **작업명**: disable-internal-api-key
- **문서 타입**: db-design
- **작성일**: 2026-06-03
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

이번 작업은 DB 변경 없음. `X-Internal-Api-Key` 인증 모델을 Basic Auth로 전환하는 순수 애플리케이션 레이어 작업이다. 신규 테이블·컬럼·인덱스·마이그레이션 파일은 일절 없으며, 기존 테이블을 읽기 전용으로 참조한다.

DB: PostgreSQL / 마이그레이션 도구: Flyway

---

## 본문

### 이번 작업의 DB 변경 범위

**신규 테이블**: 없음
**컬럼 추가/변경/삭제**: 없음
**신규 인덱스**: 없음
**Flyway 마이그레이션 파일**: 없음
**데이터 마이그레이션**: 없음

---

### 참조하는 기존 테이블 (읽기 전용)

| 테이블 | 컬럼 | 용도 | 변경 여부 |
|---|---|---|---|
| `oauth2_registered_client` (SAS 표준) | `client_id`, `client_secret` | Basic Auth 검증 시 BCrypt 비교 | 변경 없음 (읽기만) |
| `service_client` | `client_name`, `grant_type`, `api_key_hash` | refactor-client-registration 결과 그대로 보존 | 변경 없음 |
| `service_route` | `route_id`, `client_id`, `upstream_url`, `path_prefix` | 라우트 메타데이터 조회 | 변경 없음 |

---

### Basic Auth 검증의 데이터 액세스 경로

```
Authorization: Basic base64(clientId:clientSecret)
        │
        ▼
RegisteredClientRepository.findByClientId(clientId)
        │
        ▼  읽기만
oauth2_registered_client.client_secret  (BCrypt 해시)
        │
        ▼
PasswordEncoder.matches(rawSecret, storedHash)  → 일치 여부 반환
```

`JdbcRegisteredClientRepository`(`RegisteredClientConfig`에 등록)가 `oauth2_registered_client` 테이블을 직접 조회한다. 애플리케이션 코드가 SQL을 직접 실행하지 않으며, SAS 표준 Repository 인터페이스를 그대로 사용한다.

---

### ADR-0010 마이그레이션 폐기 결정 (V6 supersede 이력)

ADR-0010은 원래 `api_key_hash` 컬럼 제거(V6 마이그레이션)를 포함하고 있었다.
직전 작업 `refactor-client-registration`(커밋 2ab06d0)에서 해당 V6 마이그레이션을 실행하지 않고 **V5 nullable 보존**으로 최종 결정했다. 이번 작업도 그 결정을 따른다.

- `service_client.grant_type`: nullable 보존
- `service_client.api_key_hash`: nullable 보존
- V6 `DROP COLUMN` 마이그레이션: 실행하지 않음 (ADR-0010 원안 supersede)

---

### 향후 후속 작업 후보

다음 기능이 추가될 경우 새 컬럼 또는 테이블이 필요할 수 있다.

| 후속 기능 | 예상 DB 변경 |
|---|---|
| Secret 회전 endpoint (`POST /clients/{id}/rotate-secret`) | `oauth2_registered_client.client_secret` 업데이트 또는 secret 이력 테이블 추가 |
| API 키 완전 제거 (cleanup) | `service_client.api_key_hash` `DROP COLUMN` (별도 마이그레이션 필요) |
| 클라이언트별 권한 세분화 | `service_client` 또는 SAS `client_settings` 컬럼 확장 |

---

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 (DB 작업 없음으로 명시)
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 (해당 없음)
- [x] 모든 인덱스에 사유가 있음 (해당 없음)
- [x] FK/참조 정책이 명시됨 (해당 없음)
- [x] 마이그레이션 순서와 위험도가 명시됨 (마이그레이션 없음)
- [x] 기존 데이터 처리 방안이 있음 (데이터 변경 없음)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (신규 스키마 없음)

## 참고
- `docs/adr/0010-client-secret-self-service-auth.md` — 이 작업의 근거 ADR
- `refactor-client-registration` (커밋 2ab06d0) — V5 nullable 보존 최종 결정 출처
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java` — `JdbcRegisteredClientRepository` 빈 등록 위치
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/SecurityConfig.java` — `BCryptPasswordEncoder(12)` 빈 등록 위치
