# client-self-registration-and-app-redirect - db-design

## 메타
- **작업명**: client-self-registration-and-app-redirect
- **문서 타입**: db-design
- **작성일**: 2026-06-07
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

---

## 개요

이 문서는 기능 A(SSO 클라이언트 셀프 등록) 구현에 필요한 `service_client` 테이블 스키마 변경을 다룬다. DB는 **PostgreSQL**, 마이그레이션 도구는 **Flyway**이며 현재 V6까지 적용되어 있으므로 이번 변경은 **V7**으로 시작한다. 기능 B(APP 로그인 리다이렉트)는 DB 변경 없음이 todo에서 명시되어 있으며 이 문서에서도 확인·명시한다.

**clientSecret 저장 방식은 B안으로 확정**: SAS `RegisteredClient`는 `ClientAuthenticationMethod.NONE`(public PKCE) 그대로 유지하고, clientSecret 해시는 우리 `service_client` 테이블의 신규 컬럼 `client_secret_hash`에 저장하여 커스텀 Basic Auth로 직접 검증한다. SAS `oauth2_registered_client.client_secret`은 사용하지 않는다(null 유지).

---

## 본문

### 신규 테이블 / 컬렉션

해당 없음. 기존 테이블 변경만 수행한다.

---

### 기존 테이블 / 컬렉션 변경

#### `service_client`

- **연관 todo**: `[A-DB-1]` `[A-DB-2]`

##### A-DB-1 — `owner_id` 컬럼 추가

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| ADD COLUMN | `owner_id` | `BIGINT NULL` / 기본값 없음 | 셀프 등록 클라이언트의 소유자 회원 ID. ADMIN 경로 등록 기존 레코드는 NULL 허용. | 낮음 — nullable 컬럼 추가이므로 운영 중 락 없음 |
| ADD INDEX | `idx_service_client_owner_id` | `(owner_id)` | `countByOwnerId` 및 소유자별 클라이언트 목록 조회 성능. NULL 값 포함 인덱스이므로 PostgreSQL B-tree 기본 동작(NULL 포함) 확인 필요 | 낮음 — 테이블 크기가 작으므로 인덱스 빌드 부담 없음 |

**제약조건 상세**
- `owner_id` FK 미설정 이유: `members.id`를 참조하는 FK를 걸면 향후 회원 탈퇴 정책 확정 시 CASCADE/SET NULL 정책을 결정해야 한다. 현재 회원 탈퇴 기능이 없고, 클라이언트 소유권 이전·회원 삭제 정책이 미확정이므로 **FK 제약은 걸지 않고 논리적 참조**로 유지한다. 정책 확정 시 별도 마이그레이션으로 FK 추가한다.
- `ON DELETE` 정책: 미정 — 위 사유로 이번 버전에서 FK 생략.

**인덱스**
- `idx_service_client_owner_id` — `(owner_id)` — 사유: `RegisterOAuthClientService.countByOwnerId(ownerId)`가 `WHERE owner_id = ?`로 동작하며, 1인 5개 제한 검증마다 풀스캔을 방지하기 위해 필수. 또한 소유자별 클라이언트 목록 조회(`WHERE owner_id = ?`) API를 향후 추가할 때도 재사용된다.

---

##### A-DB-2 — `client_secret_hash` 컬럼 추가 (B안 확정)

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| ADD COLUMN | `client_secret_hash` | `VARCHAR(72) NULL` / 기본값 없음 | BCrypt 해시된 클라이언트 시크릿. 셀프 등록 시에만 값이 설정되고 ADMIN 등록 클라이언트는 NULL. | 낮음 — nullable 컬럼 추가이므로 운영 중 락 없음 |

**컬럼 상세**
- 타입: `VARCHAR(72)` — BCrypt 출력 형식 `$2a$12$<22chars-salt><31chars-hash>` 최대 60자에 여유분 포함하여 72. `ServiceClientJpaEntity`의 `length = 72`와 일치.
- NULL 허용: 기존 ADMIN 경로 등록 클라이언트는 secret 없음 → NULL. 셀프 등록 클라이언트만 BCrypt 해시값 저장.
- 백필: 불필요. 기존 레코드 NULL 유지가 의도된 상태.
- 인덱스: 불필요. `client_secret_hash`는 `clientId`로 레코드를 조회한 뒤 BCrypt.matches()로 검증하므로 해시값 자체를 조건으로 쿼리하지 않는다.

**검증 주체**
커스텀 Basic Auth 검증: `clientId:rawSecret` 형식 헤더를 파싱하여 `service_client.client_secret_hash`에 `BCrypt.matches(rawSecret, hash)`를 수행한다. SAS `/oauth2/token` 엔드포인트를 거치지 않는다.

**A안 대비 결정 사유**

| 항목 | A안 (SAS `oauth2_registered_client.client_secret` 활용) | B안 (우리 `service_client.client_secret_hash` 신설) — 채택 |
|------|--------------------------------------------------------|-------------------------------------------------------------|
| SAS 등록 방식 | `CLIENT_SECRET_BASIC + NONE` 동시 등록 필요 | `NONE` 그대로 유지 (변경 최소화) |
| 검증 주체 | SAS (`/oauth2/token` 흐름에서 자동 검증) | 커스텀 Basic Auth 파서 (직접 구현) |
| secret 용도 | SAS 토큰 발급 흐름에서 인증 | redirect-uri CRUD Basic Auth에만 사용 (SAS 흐름 외부) |
| 기존 구조와의 정합성 | 경로 A(JSON 로그인) 위주 운영 모델과 부정합 발생 가능 | `AdminClientController` Basic Auth 패턴과 동일 구조 |
| 스키마 변경 | `service_client` 변경 없음 (SAS 테이블 자동 처리) | `service_client.client_secret_hash` 컬럼 추가 필요 |
| 이중 저장 | SAS 테이블 1곳에만 저장 | `service_client` 1곳에만 저장 (SAS `client_secret`은 null 유지) |

A안을 검토했으나 **현재 셀프 등록 클라이언트도 PKCE(`NONE`) 흐름을 유지해야 하고**, 발급된 `clientSecret`은 redirect-uri 관리 API(Basic Auth)에만 사용되므로 SAS `/oauth2/token` 검증 흐름과 완전히 분리되어야 한다. `CLIENT_SECRET_BASIC + NONE`을 동시 등록하면 SAS가 `/oauth2/token` 요청 시 secret을 실제로 검증하는 흐름이 생겨 운영 모델과 부정합이 발생한다. 따라서 **B안(NONE 유지, `service_client.client_secret_hash` 신설)을 채택**한다.

---

#### `oauth2_registered_client` (참조 정보, SAS 관리 테이블)

직접 마이그레이션 대상이 아니다. 셀프 등록 시 `SasClientRegistrarAdapter`가 `ClientAuthenticationMethod.NONE` 방식으로 `RegisteredClient`를 저장하므로 `client_secret` 컬럼은 **null로 유지**된다. 스키마 변경 불필요.

| 기존 컬럼 | 타입 | 셀프 등록 시 동작 |
|-----------|------|-------------------|
| `client_secret` | `VARCHAR(200) NULL` | **null 유지** — NONE 방식 등록이므로 SAS가 secret을 기록하지 않음 |
| `client_authentication_methods` | `VARCHAR(1000) NOT NULL` | `"none"` — 기존과 동일 |

---

#### 기능 B — `[B-DB-1]` DB 변경 없음 확인

`LoginResponse`에 `redirectUrl` 필드를 추가하고 `JsonLoginAuthenticationFilter`에서 `LoginRedirectResolver`를 호출하는 것은 순수 애플리케이션 계층 변경이다. 기존 `service_route.upstream_url` 및 `redirect_uris` 데이터를 그대로 읽으므로 스키마 변경이 없다. **기능 B에 대한 마이그레이션은 작성하지 않는다.**

---

### 마이그레이션 순서

프로젝트는 Flyway + PostgreSQL을 사용하며, 마이그레이션 파일은 `services/libs/member/src/main/resources/db/migration/` 에 위치한다. 네이밍 규칙은 `V{N}__{snake_case_description}.sql` (예: `V6__add_role_to_members.sql`). 현재 최신 버전은 **V6**이므로 이번 변경은 **V7**을 사용한다.

#### 단계 1 — V7: `service_client.owner_id` + `client_secret_hash` 컬럼 및 인덱스 추가

`owner_id`와 `client_secret_hash`는 같은 테이블(`service_client`)의 같은 작업 단위(셀프 등록 기능 A)에 속하므로 **단일 마이그레이션(V7)에 함께 추가**한다. 두 컬럼 모두 nullable이므로 순서 의존성이 없다.

**파일명**: `V7__add_owner_id_to_service_client.sql`

```sql
-- service_client 소유자(회원 ID) 및 클라이언트 시크릿 해시 컬럼 추가 — 셀프 등록 기능 A

ALTER TABLE service_client
    ADD COLUMN owner_id           BIGINT       NULL,
    ADD COLUMN client_secret_hash VARCHAR(72)  NULL;

COMMENT ON COLUMN service_client.owner_id
    IS '클라이언트 소유자 회원 ID (셀프 등록 시 설정, ADMIN 등록 시 NULL)';

COMMENT ON COLUMN service_client.client_secret_hash
    IS 'BCrypt 해시된 클라이언트 시크릿 (셀프 등록 시 설정, ADMIN 등록 시 NULL). SAS oauth2_registered_client.client_secret과 이중 저장하지 않음.';

-- owner_id별 카운트/조회 인덱스 — countByOwnerId 성능 (1인 5개 제한 검증)
CREATE INDEX idx_service_client_owner_id
    ON service_client (owner_id);
```

- **롤백 가능 여부**: 가능.
  ```sql
  ALTER TABLE service_client DROP COLUMN client_secret_hash;
  ALTER TABLE service_client DROP COLUMN owner_id; -- 인덱스 자동 제거
  ```
- **위험도**: 낮음. `NULL` 허용 컬럼 추가(복수)는 PostgreSQL에서 테이블 리라이트 없이 카탈로그 업데이트만 수행하므로 락이 거의 없다 (`ACCESS SHARE` 유지). 단일 `ALTER TABLE`에 두 `ADD COLUMN`을 묶으면 카탈로그 락 횟수도 1회로 최소화된다.
- **기존 데이터 처리**: 기존 ADMIN 등록 클라이언트는 `owner_id = NULL`, `client_secret_hash = NULL` 상태 유지. 백필 불필요.

---

### 데이터 정합성 / 운영 고려사항

1. **기존 데이터 백필 필요 없음**: `owner_id = NULL`은 "ADMIN 경로 등록 또는 소유자 미설정"을 의미하도록 설계한다. `client_secret_hash = NULL`은 "secret 없는 클라이언트(ADMIN 등록)"를 의미한다. 기존 레코드를 NULL로 두는 것이 의도된 상태다.

2. **NOT NULL 제약 추가 없음**: 두 컬럼 모두 영구적으로 `NULL` 허용이다.
   - `owner_id`: ADMIN이 등록한 클라이언트(시스템 클라이언트)는 소유자 개념이 없다.
   - `client_secret_hash`: ADMIN 등록 클라이언트는 Basic Auth 검증 대상이 아니므로 secret을 발급하지 않는다.

3. **인덱스 빌드 락**: 현재 `service_client` 테이블의 레코드 수가 소수(운영 환경에서 수십 개 수준 예상)이므로 `CREATE INDEX`가 락을 점유하는 시간이 무시할 수준이다. 향후 대량 데이터가 예상된다면 `CREATE INDEX CONCURRENTLY`로 변경한다.

4. **구버전 코드와의 호환성**: `owner_id`, `client_secret_hash` 컬럼이 DB에 추가된 이후에도 기존 `ServiceClientJpaEntity`는 해당 컬럼을 매핑하지 않으면 자동 무시하고 동작한다(JPA는 매핑되지 않은 컬럼을 무시). 따라서 **마이그레이션 → 코드 배포** 순서를 지켜도 구버전 코드 동작에 영향 없다.

5. **SAS `oauth2_registered_client.client_secret` 불사용 확인**: 셀프 등록 시 `SasClientRegistrarAdapter.registerAuthorizationCodeClient`는 `ClientAuthenticationMethod.NONE`으로 `RegisteredClient`를 등록하므로 SAS는 `client_secret`에 아무것도 기록하지 않는다(null 유지). `service_client.client_secret_hash`가 유일한 저장 위치이며 이중 저장은 발생하지 않는다.

6. **평문 secret 노출 범위**: `clientSecret` 평문은 셀프 등록 API 응답에서 1회만 노출된다. DB에는 BCrypt 해시만 저장되므로 유출 위험이 없다. `VARCHAR(72)` 길이는 BCrypt 60자 + 여유 공간으로 충분하다.

---

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 (`A-DB-1`, `A-DB-2`, `B-DB-1`)
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨
- [x] 모든 인덱스에 사유가 있음
- [x] FK/참조 정책이 명시됨 (FK 미설정 이유 포함)
- [x] 마이그레이션 순서와 위험도가 명시됨
- [x] 기존 데이터 처리 방안이 있음 (NULL 유지, 백필 불필요)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (snake_case, BIGINT PK, TIMESTAMP NOT NULL DEFAULT NOW())
- [x] implementation-plan.md의 `client_secret_hash VARCHAR(72)` 컬럼명/타입과 일치

---

## 참고
- `services/libs/member/src/main/resources/db/migration/V4__create_service_client_and_route.sql` — `service_client` 원본 스키마
- `services/libs/member/src/main/resources/db/migration/V2__create_sas_tables.sql` — `oauth2_registered_client` 스키마 (SAS 표준 DDL)
- `services/libs/member/src/main/resources/db/migration/V5__make_grant_type_nullable.sql` — ALTER 마이그레이션 패턴 참조
- `services/libs/member/src/main/resources/db/migration/V6__add_role_to_members.sql` — ADD COLUMN 마이그레이션 패턴 참조
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceClientJpaEntity.java` — JPA 엔티티 현행 구조 (`client_secret_hash length=72` 기준)
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/sas/SasClientRegistrarAdapter.java` — NONE 방식 등록 확인 (SAS client_secret null 유지 근거)
- `docs/CONVENTION.md` — 네이밍 컨벤션 (snake_case, BIGINT PK)
- `.claude/plans/client-self-registration-and-app-redirect/implementation-plan.md` — B안 설계 기준 문서 (V7 마이그레이션 스크립트 초안 포함)
