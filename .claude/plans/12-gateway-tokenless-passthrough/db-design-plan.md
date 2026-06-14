# gateway-tokenless-passthrough - db-design

## 메타
- **작업명**: gateway-tokenless-passthrough
- **문서 타입**: db-design
- **작성일**: 2026-06-14
- **관련 문서** (같은 디렉터리):
  - todo.md

## 개요

이 작업은 `api-gateway`의 `BearerToPassportFilter`(GlobalFilter) 동작을 "tokenless passthrough"로 변경하는 순수 애플리케이션 로직 변경이다. 게이트웨이 필터 계층은 DB를 직접 참조하지 않으며, 이번 작업에서 스키마·테이블·마이그레이션 변경은 전혀 없다.

**DB 변경 없음 — 테이블/마이그레이션 0, 기존 스키마 불변.**

## 본문

### 신규 테이블 / 컬렉션

없음.

### 기존 테이블 / 컬렉션 변경

없음.

동적 라우팅 관련 `service_route` 테이블(ADR-0016 기준)은 이번 작업과 무관하며 변경되지 않는다. `BearerToPassportFilter`는 인메모리 permitted-paths 목록만 참조하고, DB에서 어떤 데이터도 읽거나 쓰지 않는다.

### 마이그레이션 순서

해당 없음. 실행할 마이그레이션 파일이 없다.

### 데이터 정합성 / 운영 고려사항

- 데이터 백필: 해당 없음
- 기존 row 처리: 해당 없음
- 인덱스 빌드 락: 해당 없음
- 마이그레이션 도중 호환성: 해당 없음 — 스키마 변경이 없으므로 배포 전후 DB 상태가 동일하다

## 근거

todo.md `### DB 작업` 섹션에 "해당 없음 (스키마·마이그레이션 변경 없음)"으로 명시되어 있다. 변경 대상 파일(`BearerToPassportFilter.java`, `GatewayRoutingConfig.java`, `application.yml`, 테스트, ADR)은 모두 코드·설정·문서 계층이며, DB 접근 로직을 포함하지 않는다.

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 (DB 작업 섹션 자체가 "해당 없음")
- [x] 신규/변경 테이블 없음을 명시함
- [x] service_route 등 기존 스키마 불변임을 확인함
- [x] 마이그레이션 불필요함을 확인함

## 참고
- `.claude/plans/12-gateway-tokenless-passthrough/todo.md` — DB 작업 섹션 "해당 없음" 확인
- `docs/adr/0016-dynamic-gateway-routing-reintroduction.md` — service_route 테이블의 출처(이번 작업과 무관)
- `docs/adr/0002-gateway-as-auth-boundary.md` — 게이트웨이 필터가 DB를 직접 사용하지 않는 구조의 기원
