# auth-lib-extraction - db-design

## 메타
- **작업명**: auth-lib-extraction
- **문서 타입**: db-design
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - todo.md
  - api-design-plan.md
  - implementation-plan.md

## 개요

이 작업은 auth-api의 application 계층(`LoginTokenService`, `LoginRedirectResolver`, UseCase 인터페이스 2종)을 새 lib 모듈 `services/libs/login`로 추출하고 토큰 발급/검증을 도메인 포트(`TokenEncoder/TokenDecoder`)로 추상화하는 **순수 리팩토링**이다. DB 스키마(테이블·컬럼·인덱스·제약조건)는 이번 작업에서 **단 하나도 변경하지 않는다.** 마이그레이션 파일 추가·수정·삭제 역시 **0건**이다.

사용 DB: PostgreSQL. 마이그레이션 도구: Flyway (`V{N}__{설명}.sql` 네이밍, `services/libs/member/src/main/resources/db/migration/`에 집중 관리). ORM: Spring Data JPA (Hibernate, `ddl-auto: validate`).

## 본문

### 신규 테이블 / 컬렉션

**없음.** 이번 작업에서 신규 테이블·컬렉션을 생성하지 않는다.

- **연관 todo**: `DB 작업 — 변경 없음(신규 테이블·컬럼·인덱스·마이그레이션 없음). 순수 리팩토링.`

### 기존 테이블 / 컬렉션 변경

**없음.** `members`, `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent`, `service_client`, `service_client_route` 등 기존 테이블 전부 현행 유지된다.

| 변경 | 컬럼/인덱스 | 상세 | 사유 | 위험도 |
|------|-------------|------|------|--------|
| (없음) | — | — | — | — |

### 영속성 격리 선언

새로 신설되는 `services/libs/login` 모듈은 JPA 관련 어노테이션 및 설정을 **일절 포함하지 않는다.**

- `@Entity`, `@Table`, `@Column`, `@Index` 선언 클래스 없음
- `@EnableJpaRepositories`, `@EntityScan` 없음 — `LoginAutoConfiguration`은 `@ComponentScan("com.econo.auth.login")`만 선언
- Flyway 마이그레이션 위치(`classpath:db/migration`)를 제공하는 리소스 디렉터리 없음

회원 조회는 기존 `services/libs/member` 모듈의 `MemberRepository` 포트를 그대로 사용한다. 영속성 소유자(`@EnableJpaRepositories`, `@EntityScan`, Flyway 마이그레이션 파일)는 `services/libs/member`의 `MemberAutoConfiguration`이 단독으로 담당하며, 이 구조는 변경되지 않는다.

따라서 auth lib 추가 이후에도:
- Flyway 마이그레이션 체인(`V1` ~ `V8`, 현재 최신 `V8__drop_unused_spring_session_tables.sql`)은 그대로 유지된다.
- Hibernate `ddl-auto: validate`가 기동 시 스캔하는 엔티티 목록과 DB 스키마 간 정합성은 변하지 않는다.

### 마이그레이션 순서

해당 없음. 이번 작업에서 실행할 Flyway 마이그레이션 파일이 존재하지 않는다.

### 데이터 정합성 / 운영 고려사항

- **백필 불필요** — 데이터 구조 변경이 없으므로 기존 row에 대한 백필 작업이 없다.
- **ddl-auto:validate 영향 없음** — 신설 모듈에 엔티티가 없으므로 Hibernate validate 단계에서 추가로 검사할 매핑이 없다.
- **Flyway 체인 불변** — 새 마이그레이션 파일이 추가되지 않으므로 체크섬 검증과 버전 순서가 깨지지 않는다.
- **운영 중 호환성** — 이번 배포는 코드 패키지 이동만 포함하므로 스키마 변경으로 인한 구버전 코드 비호환 시나리오가 없다.

### 회귀 검증 체크리스트 (빌드 시)

아래 항목은 마이그레이션 파일을 새로 만들지 않더라도 리팩토링 완료 시점에 반드시 확인한다.

- [ ] `./gradlew clean build` 전체 green — Hibernate `ddl-auto: validate`가 기동 중 실패하지 않음을 포함
- [ ] Flyway 체인 불변 확인 — 빌드 로그에서 `Successfully applied N migrations` 수가 이전과 동일(N = 8)
- [ ] auth lib 의존 검사 — `./gradlew :services:libs:login:dependencies | grep spring-security-oauth2` 결과 없음 (JPA 드라이버 포함 여부도 grep: `spring-data-jpa`, `hibernate`)
- [ ] `AuthApiIntegrationTest` (로그인·재발급·AT-as-RT→401·로그아웃) 전 시나리오 green — 실제 DB 연결 흐름이 깨지지 않음을 검증
- [ ] iCloud 중복 파일 없음 — clean 빌드 후 `find . -name "* 2.java"` 결과 없음

## 체크리스트
- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 (DB 작업: 변경 없음 — 매핑 완료)
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 (신규 컬럼 없음)
- [x] 모든 인덱스에 사유가 있음 (신규 인덱스 없음)
- [x] FK/참조 정책이 명시됨 (신규 FK 없음)
- [x] 마이그레이션 순서와 위험도가 명시됨 (마이그레이션 파일 0건)
- [x] 기존 데이터 처리 방안이 있음 (백필 불필요)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (스키마 변경 없으므로 적용 대상 없음)

## 참고
- `services/apis/auth-api/src/main/resources/application.yml` — `ddl-auto: validate`, Flyway 설정 확인
- `services/libs/member/src/main/resources/db/migration/` — V1~V8 마이그레이션 파일 (auth lib 신설 후에도 불변)
- `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java` — `@EnableJpaRepositories`, `@EntityScan` 소유자 확인; LoginAutoConfiguration 설계 참고 출처
