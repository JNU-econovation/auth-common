# login-redirect-via-body - db-design

## 메타
- **작업명**: login-redirect-via-body
- **문서 타입**: db-design
- **작성일**: 2026-06-18
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

이 작업은 WEB 로그인 성공 응답 방식을 서버 302 리다이렉트에서 200 OK + body(`redirectUrl` 포함)로 전환하는 변경이다. 변경 범위는 `JsonLoginAuthenticationFilter`의 응답 처리 코드, `LoginResponse` DTO, Swagger 커스터마이저에 국한된다. **DB 스키마 변경은 전혀 없다.**

사용 DB: PostgreSQL. 마이그레이션 도구: Flyway (파일 컨벤션: `V{version}__{description}.sql`, 단일 소스 `db/migration/`).

## 본문

### DB 변경 없음

**이 작업은 신규 테이블/컬렉션 생성, 기존 테이블/컬렉션 변경, 인덱스 추가/삭제, Flyway 마이그레이션 파일 추가가 모두 해당하지 않는다.**

#### 근거

- **변경 대상은 응답 전달 방식뿐이다.** 서버가 `response.sendRedirect(target)`으로 내려보내던 `redirectUrl`을 `response.setStatus(SC_OK)` + `objectMapper.writeValue(response.getWriter(), ...)` 형태로 body에 담아 내려주는 방식으로 전환한다. redirectUrl 자체의 결정 로직(`loginRedirectUseCase`가 `service_client` 테이블을 조회해 resolving)은 전혀 수정되지 않는다.
- **새로운 영속화 데이터가 없다.** `redirectUrl`은 요청 처리 시점에 `service_client.redirect_uris` 컬럼에서 읽어 계산한 뒤 응답에만 포함되며, 어디에도 저장되지 않는다.
- **참조 테이블은 변경 대상이 아니다.** `service_client`, `service_route` 등 기존 테이블은 현행 스키마를 그대로 유지한다.

#### 관련 todo 항목 확인

todo의 `### DB 작업` 섹션: `- 해당 없음`

모든 구현·API·테스트·문서 항목이 DB 계층과 무관함을 확인했다.

---

### 마이그레이션 순서

없음. Flyway 마이그레이션 파일을 추가하지 않는다. 현행 최신 버전은 `V10__add_index_service_route_enabled.sql`이며, 다음 작업이 DB 변경을 필요로 할 경우 `V11__...sql`을 사용한다.

---

### 데이터 정합성 / 운영 고려사항

없음. 스키마 변경이 없으므로 백필, NOT NULL 제약 추가, 인덱스 빌드 락, 마이그레이션 도중 구버전 코드 호환성 문제가 모두 발생하지 않는다.

---

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 — "해당 없음" 으로 확인
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 — 신규 컬럼 없음
- [x] 모든 인덱스에 사유가 있음 — 신규 인덱스 없음
- [x] FK/참조 정책이 명시됨 — 신규 FK 없음
- [x] 마이그레이션 순서와 위험도가 명시됨 — 마이그레이션 없음
- [x] 기존 데이터 처리 방안이 있음 (해당 시) — 해당 없음
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 — 신규 테이블/컬럼 없으므로 적용 불필요

## 참고
- `docs/INFRASTRUCTURE.md` — DB 종류(PostgreSQL), Flyway 마이그레이션 컨벤션, 현재 버전 현황(V1~V10)
- `db/migration/` — 현행 마이그레이션 파일 목록 (`V10__add_index_service_route_enabled.sql`이 최신)
- `docs/adr/0015-flyway-container-managed-migration.md` — DB 마이그레이션 전략 근거
