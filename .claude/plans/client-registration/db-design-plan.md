# client-registration - db-design

## 메타
- **작업명**: client-registration
- **문서 타입**: db-design
- **작성일**: 2026-05-25
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

---

## 개요

SAS(Spring Authorization Server) 1.2.x OIDC Dynamic Client Registration(DCR) 활성화에 따른 DB 변경 범위를 분석한다.
결론: **신규 마이그레이션(V4)은 필요하지 않다.** DCR 클라이언트 등록 데이터는 기존 `oauth2_registered_client`에,
`registration_access_token`은 기존 `oauth2_authorization`에 저장되며, registrar client 시드도 기존 `RegisteredClientConfig`
패턴을 통한 애플리케이션 레벨 처리이므로 스키마 변경이 없다.
사용 DB: PostgreSQL, 마이그레이션 도구: Flyway (현재 V1~V3 점유).

---

## 본문

### 신규 테이블 / 컬렉션

없음. 아래 근거 절 참조.

---

### 기존 테이블 / 컬렉션 변경

없음. 아래 근거 절 참조.

---

### 스키마 변경 없음 — 근거

#### DCR 클라이언트 저장: `oauth2_registered_client` 재사용

- **연관 todo**: `[ ] SAS 1.x에서 registration_access_token의 저장 위치 확인`

SAS 1.x DCR 흐름에서 `POST /connect/register`로 등록되는 3rd-party client는
`OidcClientRegistrationEndpointFilter` → `OidcClientRegistrationAuthenticationProvider` 경로를 통해
결국 `RegisteredClientRepository.save()` 를 호출한다.
프로젝트는 이미 `JdbcRegisteredClientRepository`를 `OAuth2AuthorizationServiceConfig`에서 빈으로 등록했으므로
DCR 등록 데이터는 기존 `oauth2_registered_client` 테이블에 정상 저장된다.

기존 `oauth2_registered_client` 컬럼 구성(V2 DDL 기준)은 SAS 1.x 공식 스키마와 일치하며,
DCR이 저장하는 모든 client metadata(`redirect_uris`, `scopes`, `client_settings`, `token_settings` 등)를
이 테이블이 이미 수용할 수 있다. 신규 컬럼 불필요.

#### registration_access_token 저장: `oauth2_authorization` 재사용

`registration_access_token`(RFC 7592 관리용 토큰)은 SAS 내부적으로 일반 `OAuth2AccessToken`과 동일한
`OAuth2Authorization` 객체로 모델링된다. `JdbcOAuth2AuthorizationService`는 이를 `oauth2_authorization` 테이블의
`access_token_value` / `access_token_issued_at` / `access_token_expires_at` / `access_token_scopes` 컬럼에
기존 access token과 동일한 방식으로 직렬화·저장한다.

`oauth2_authorization.access_token_scopes`에는 DCR 흐름에서 사용된 스코프(`client.create`)가 기록되며,
`principal_name`에는 registrar client의 client ID가, `authorization_grant_type`에는 `client_credentials`가 저장된다.
V2 DDL의 `oauth2_authorization` 컬럼 구성이 이 모든 데이터를 수용한다.

#### registrar client 시드: 스키마 변경 없음

registrar client(`REGISTRAR_CLIENT_ID`, client_credentials, scope=`client.create`+`client.read`)는
기존 `firstPartyPublicClient()` 패턴과 동일하게 `RegisteredClientConfig.registeredClientRepository()` 빈
내에서 멱등 seed(`findByClientId` → 없으면 `save`)로 처리된다.
고정 UUID `00000000-0000-0000-0000-000000000002`를 PK로 사용하는 일반 `oauth2_registered_client` row이므로
스키마 변경이 없다.

---

### 플래그: SAS 1.x 소스코드 대조 필수

> **아래 항목은 develop 환경에서 실제 SAS 버전 소스코드를 확인해야 한다.**
> 분석은 Spring Boot 3.2.2 = SAS 1.2.x BOM 기준으로 수행했으나,
> 프로젝트가 사용하는 정확한 SAS artifact 버전(BOM 전이 버전)을 `./gradlew dependencies --configuration runtimeClasspath | grep authorization-server`로 확인 후 아래 파일을 직접 대조할 것.

1. **`OidcClientRegistrationEndpointFilter`** (SAS 소스)
   - `registration_access_token`을 `OAuth2Authorization`으로 저장하는 코드 경로 확인.
   - `OidcClientRegistrationAuthenticationProvider.authenticate()` 내 `authorizationService.save(authorization)` 호출 여부 확인.

2. **`JdbcOAuth2AuthorizationService`** (SAS 소스)
   - `oauth2_authorization` 테이블 매핑 SQL에 DCR 전용 컬럼이 추가된 버전이 있는지 확인.
   - SAS 1.3.x+ 에서는 이 매핑이 변경될 수 있으므로 현재 사용 버전(1.2.x) 기준으로 대조.

3. **SAS 공식 DCR 가이드** (https://docs.spring.io/spring-authorization-server/reference/guides/how-to-dynamic-client-registration.html)
   - "Additional Schema" 섹션이 있는지, V2 DDL 외 추가 DDL을 요구하는지 확인.
   - 현재 알려진 1.2.x 가이드 기준 추가 DDL 언급 없음 — 이 분석의 주요 근거.

---

### 마이그레이션 순서

신규 마이그레이션 파일이 없으므로 순서 없음.

- V1 ~ V3: 기존 운영 중. 변경 없음.
- V4: **해당 없음** (플래그 항목 확인 결과 추가 스키마가 필요한 경우에만 작성).

#### (조건부) V4 마이그레이션이 필요해지는 경우

위 플래그 확인 결과 SAS 버전에서 `oauth2_authorization` 외 별도 컬럼/테이블을 요구한다면
아래 컨벤션으로 V4를 작성한다:

- 파일명: `V4__add_dcr_schema.sql` (파일 위치: `services/libs/auth-infra/src/main/resources/db/migration/`)
- 컨벤션: PostgreSQL 방언, `COMMENT ON TABLE/COLUMN` 포함, V2 DDL 스타일 준수
- 위험도: 신규 테이블 추가는 낮음. 기존 테이블에 `NOT NULL` 컬럼 추가는 운영 중 높음(기존 row 백필 필요).

---

### 데이터 정합성 / 운영 고려사항

1. **기존 데이터 백필**: 불필요. 스키마 변경이 없으므로 기존 `oauth2_registered_client`, `oauth2_authorization` row에 영향 없음.

2. **registrar client 시드 멱등성**: `findByClientId(registrarClientId) == null` 조건으로 중복 save를 방지한다. 고정 UUID(`00000000-0000-0000-0000-000000000002`)를 사용하므로 재기동 시 PK 충돌이 없다.

3. **DCR 등록 client의 id 충돌**: SAS DCR이 `UUID.randomUUID()`로 `oauth2_registered_client.id`를 생성한다. registrar client 고정 UUID(`...0002`)와 충돌 가능성은 수학적으로 무시 가능.

4. **`registration_access_token` 만료 정리**: `oauth2_authorization` 테이블에 저장된 `registration_access_token` 행은 토큰 만료 후에도 자동 삭제되지 않는다(SAS는 별도 cleanup 작업을 제공하지 않음). 운영 데이터 규모에 따라 주기적 DELETE(`access_token_expires_at < NOW()`) 배치 작업을 별도 검토할 것. 이번 작업 범위 외.

5. **애플리케이션 호환성**: 스키마 변경이 없으므로 배포 도중 구버전 코드와 새 코드가 동시에 실행되어도 DB 레벨 비호환이 없다.

---

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 (`DB 작업` 섹션 2개 항목 → 스키마 변경 없음으로 매핑)
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 (기존 V2 DDL 재사용, 신규 컬럼 없음)
- [x] 모든 인덱스에 사유가 있음 (신규 인덱스 없음)
- [x] FK/참조 정책이 명시됨 (기존 V2 DDL 그대로, 신규 FK 없음)
- [x] 마이그레이션 순서와 위험도가 명시됨 (V4 불필요, 조건부 지침 작성)
- [x] 기존 데이터 처리 방안이 있음 (백필 불필요 명시)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (V2 DDL 기반 재사용이므로 당연히 준수)

---

## 미해결 사항 (플래그)

| # | 항목 | 확인 방법 | 결과에 따른 영향 |
|---|------|-----------|-----------------|
| 1 | SAS 정확한 버전 확인 | `./gradlew :services:apis:auth-api:dependencies --configuration runtimeClasspath \| grep authorization-server` | 1.3.x+ 이면 스키마 변경 여부 재검토 필요 |
| 2 | `OidcClientRegistrationAuthenticationProvider.authenticate()`에서 `authorizationService.save()` 호출 여부 | SAS 버전 태그 소스코드 직접 확인 | 호출 없으면 `registration_access_token` 영속화 경로 재분석 필요 |
| 3 | SAS DCR 가이드 "Additional Schema" 섹션 유무 | 공식 문서 (버전 태그 기준) | 추가 스키마 존재 시 V4 마이그레이션 작성 |
| 4 | `registration_access_token` 만료 정리 정책 | 운영 요구사항 검토 | 배치 DELETE 작업 추가 여부 결정 (이번 범위 외) |

---

## 참고
- `services/libs/auth-infra/src/main/resources/db/migration/V2__create_sas_tables.sql` — 기존 SAS 테이블 DDL (재사용)
- `services/libs/auth-infra/src/main/resources/db/migration/V3__create_spring_session_tables.sql` — 기존 Session 테이블 DDL (변경 없음)
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/RegisteredClientConfig.java` — registrar client seed 패턴 기준
- `services/apis/auth-api/src/main/java/com/econo/auth/api/config/OAuth2AuthorizationServiceConfig.java` — JdbcOAuth2AuthorizationService 빈 등록 확인
- `docs/INFRASTRUCTURE.md` — Flyway 버전 현황 및 스키마 컨벤션
- SAS 1.x DCR 가이드: https://docs.spring.io/spring-authorization-server/reference/guides/how-to-dynamic-client-registration.html
