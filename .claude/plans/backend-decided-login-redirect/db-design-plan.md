# backend-decided-login-redirect - db-design

## 메타
- **작업명**: backend-decided-login-redirect
- **문서 타입**: db-design
- **작성일**: 2026-06-07
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

이 작업은 `POST /api/v1/auth/login` 성공 후 리다이렉트 대상 URL 결정권을 백엔드로 이관하는 변경이다. 백엔드는 요청에 포함된 `clientId`로 `ClientRedirectUriService.findByClientId(clientId)`를 호출하고, 이미 등록된 `oauth2_registered_client.redirect_uris`를 읽어 302 Location을 결정한다. 이 흐름은 **기존 테이블을 읽기 전용으로 사용**하므로 신규 테이블·컬럼·인덱스가 필요하지 않다. 사용 DB: PostgreSQL, 마이그레이션 도구: Flyway (네이밍 컨벤션 `V{N}__*.sql`).

**결론: 이번 작업 범위에서 DB 스키마 변경 없음.**

## 본문

### DB 변경 없음 — 근거

#### clientId → redirect_uri 조회에 사용되는 기존 테이블

| 테이블 | 컬럼 | 역할 |
|--------|------|------|
| `oauth2_registered_client` | `client_id` VARCHAR(100) UNIQUE | 요청에서 수신한 `clientId`로 클라이언트를 단건 조회하는 키. `JdbcRegisteredClientRepository.findByClientId(clientId)` 경유. 이미 `uq_oauth2_registered_client_client_id` 유니크 인덱스가 존재하므로 추가 인덱스 불필요. |
| `oauth2_registered_client` | `redirect_uris` VARCHAR(1000) | 등록된 redirect URI 목록을 **쉼표 구분 단일 문자열**로 저장. SAS `JdbcRegisteredClientRepository`가 파싱하여 `RegisteredClient.getRedirectUris()` → `Set<String>`으로 반환. |

#### 조회 흐름 (읽기 전용)

```
LoginRedirectResolver.resolve(clientId, defaultUrl)
  └─ ClientRedirectUriService.findByClientId(clientId)          // 신규 호출 경로
       └─ SasRedirectUriManagerAdapter.findRedirectUrisByClientId(clientId)
            └─ JdbcRegisteredClientRepository.findByClientId(clientId)
                 └─ SELECT ... FROM oauth2_registered_client WHERE client_id = ?
```

`findByClientId()`는 `SELECT` 단 1회로 끝나며 DML이 전혀 없다. `service_client` 테이블은 이번 흐름에서 조회되지 않는다 (`extractAllowedOrigins()`는 CORS 계산 전용이며 이번 기능과 무관).

#### redirect_uris 컬럼 구조와 순서 보장 불가 (DB 차원)

`redirect_uris`는 `VARCHAR(1000)` 단일 컬럼에 쉼표로 이어 붙인 문자열로 저장된다 (`V2__create_sas_tables.sql` 확인). SAS는 이 값을 파싱해 `Set<String>`으로 돌려주므로 **DB 저장 순서와 `getRedirectUris()` 반환 순서 사이에 결정적 관계가 없다**. 따라서 "DB 차원에서 첫 번째 URI를 보장"하는 방법은 현재 스키마 구조상 존재하지 않는다.

애플리케이션 계층(`LoginRedirectResolver`)이 `TreeSet` 등으로 정렬 후 첫 번째를 선택하는 것이 유일하게 결정적인 방법이며, todo 항목에 이미 "알파벳 오름차순 정렬 후 첫 번째 선택"으로 명시되어 있다.

#### todo `DB 작업` 섹션 확인

todo.md `## 본문 > ### DB 작업` 항목:
> 해당 없음

todo가 명시적으로 DB 작업 없음을 선언하고 있으며, 코드베이스 분석 결과도 동일하다.

---

### 신규 테이블 / 컬렉션

없음.

### 기존 테이블 / 컬렉션 변경

없음.

### 마이그레이션 순서

Flyway 마이그레이션 파일이 필요하지 않다. 현재 최신 버전은 `V6__add_role_to_members.sql`이며, 이번 작업에서 생성할 마이그레이션 파일은 없다. V7은 별도 스키마 변경이 생길 때 작성한다.

### 데이터 정합성 / 운영 고려사항

- 기존 데이터 백필: 불필요. 스키마 변경 없음.
- NOT NULL 추가: 해당 없음.
- 인덱스 빌드 락: 해당 없음.
- 애플리케이션 호환성: `oauth2_registered_client` 테이블은 읽기 전용으로만 사용되므로 배포 순서에 제약이 없다. 구버전 코드와 신버전 코드가 동시에 동작해도 스키마 충돌이 없다.

---

### (선택적 권장) primary redirect_uri 플래그 컬럼

이번 작업 범위 밖이다. 향후 "대표 URI"의 선택이 알파벳 정렬 이상의 명시적 제어가 필요해지면 아래를 검토한다.

#### 배경

`oauth2_registered_client.redirect_uris`는 SAS 표준 스키마로, 이 컬럼을 직접 수정하면 SAS 업그레이드 시 호환성 위험이 생긴다. 대신 별도 테이블로 보조 메타데이터를 관리하는 방식이 적합하다.

```sql
-- 예시 (이번 작업에서 생성하지 않음)
-- V7__add_client_primary_redirect_uri.sql
CREATE TABLE client_primary_redirect_uri (
    registered_client_id VARCHAR(100) NOT NULL,
    primary_uri          VARCHAR(1000) NOT NULL,
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_client_primary_redirect_uri
        PRIMARY KEY (registered_client_id),
    CONSTRAINT fk_client_primary_redirect_uri_client
        FOREIGN KEY (registered_client_id)
        REFERENCES service_client (registered_client_id)
        ON DELETE CASCADE
);
```

도입 시 고려 사항:
- `LoginRedirectResolver`가 이 테이블을 먼저 조회하고, 행이 없으면 알파벳 정렬 fallback으로 동작하도록 구현.
- `primary_uri`가 `oauth2_registered_client.redirect_uris` 목록에 실제로 포함되는지 애플리케이션 계층에서 검증 필요 (DB FK로는 강제 불가, 쉼표 구분 문자열이므로).
- 현재 클라이언트 수가 소규모(수십 건)이고 알파벳 정렬이 운영상 문제를 일으키고 있지 않다면 도입 시점을 늦출 것을 권장.

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 — todo `DB 작업: 해당 없음` 확인
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 — 신규 컬럼 없음
- [x] 모든 인덱스에 사유가 있음 — 신규 인덱스 없음
- [x] FK/참조 정책이 명시됨 — 신규 FK 없음
- [x] 마이그레이션 순서와 위험도가 명시됨 — 마이그레이션 파일 없음
- [x] 기존 데이터 처리 방안이 있음 — 스키마 변경 없으므로 해당 없음
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 — 기존 테이블 변경 없음

## 참고
- `services/libs/member/src/main/resources/db/migration/V2__create_sas_tables.sql` — `oauth2_registered_client` 스키마 확인 (`redirect_uris` VARCHAR(1000) 쉼표 구분 구조)
- `services/libs/member/src/main/resources/db/migration/V4__create_service_client_and_route.sql` — `service_client` 스키마 확인 (이번 흐름에서 미사용)
- `services/libs/service-client/src/main/java/com/econo/auth/client/application/usecase/ClientRedirectUriService.java` — `findByClientId(clientId)` 구현 확인 (`extractAllowedOrigins()`와 다른 별개 메서드)
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/sas/SasRedirectUriManagerAdapter.java` — `JdbcRegisteredClientRepository.findByClientId()` 위임 구조 확인
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java` — `JdbcRegisteredClientRepository` 빈 등록 확인
