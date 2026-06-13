# layered-architecture - db-design

## 메타
- **작업명**: layered-architecture
- **문서 타입**: db-design
- **작성일**: 2026-06-13
- **관련 문서** (같은 디렉터리):
  - todo.md
  - implementation-plan.md

---

## 개요

이 작업은 **순수 패키지 구조 리팩토링**이며 DB 스키마 변경이 없다. 신규 테이블/컬럼/인덱스/제약조건/Flyway 마이그레이션 파일을 일절 추가·수정·삭제하지 않는다.

사용 DB: PostgreSQL. 마이그레이션 도구: Flyway (`classpath:db/migration`, `V{n}__{description}.sql` 네이밍). ORM: Spring Data JPA (Hibernate, `ddl-auto: validate`). JPA 엔티티는 `services/libs/member`와 `services/libs/service-client` 두 lib 모듈에만 존재하며, `services/apis/auth-api`는 이 lib를 의존하는 소비자다.

이 문서가 다루는 범위는 패키지 이동이 JPA 스캔 설정과 Flyway 설정에 미치는 영향을 점검하고 회귀를 방지하는 체크리스트를 제공하는 것이다.

---

## 본문

### DB 스키마 변경 없음 선언

> **테이블, 컬럼, 인덱스, 제약조건, Flyway 마이그레이션 파일(`V1` ~ `V7`) 전체가 이번 리팩토링의 변경 대상에서 제외된다.** 아래 선언을 구현 전·후 양쪽에서 확인한다.

| 확인 항목 | 현재 값 | 리팩토링 후 기대값 |
|-----------|---------|------------------|
| Flyway 마이그레이션 위치 | `classpath:db/migration` (application.yml) | 동일 — 파일 이동 없음 |
| 마이그레이션 파일 수 | V1 ~ V7 (7개) | 동일 |
| `members` 테이블 정의 | V1 기준 (id, name, login_id, hashed_password, generation, status, created_at) | 동일 |
| `service_client` 테이블 정의 | V4·V5·V7 기준 | 동일 |
| `spring_session` 계열 테이블 | V3 기준 | 동일 |
| SAS 관련 테이블 | V2 기준 | 동일 |
| `ddl-auto` 설정 | `validate` | 동일 — 변경 금지 |

---

### 패키지 이동이 JPA 스캔에 미치는 영향 분석

#### member 모듈 (`MemberAutoConfiguration`)

현재 설정 (라이브 소스 확인: `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java`):

```
@ComponentScan("com.econo.auth.member")
@EnableJpaRepositories("com.econo.auth.member.adapter.out.persistence")
@EntityScan("com.econo.auth.member.adapter.out.persistence")
```

리팩토링 후 이동 대상 (implementation-plan.md 확정 구조):
- `MemberJpaEntity`: `adapter/out/persistence` → `persistence/entity`
- `MemberJpaRepository`: `adapter/out/persistence` → `persistence/repository`
- `MemberRepositoryAdapter`: `adapter/out/persistence` → `persistence/repository`
- `BCryptPasswordHasherAdapter`: `adapter/out/security` → `persistence/repository`

**영향**: `@EnableJpaRepositories`와 `@EntityScan`의 basePackages가 `com.econo.auth.member.adapter.out.persistence`로 하드코딩되어 있으므로, 이동 후 해당 패키지에 대상 클래스가 없어 Hibernate가 엔티티를 인식하지 못한다. 결과적으로 `ddl-auto: validate` 기동 시 테이블 매핑 실패로 `SchemaManagementException`이 발생한다.

**필수 수정** (todo에 이미 포함: `MemberAutoConfiguration 내 @EnableJpaRepositories / @EntityScan 경로 갱신`):

```
@EnableJpaRepositories("com.econo.auth.member.persistence.repository")
@EntityScan("com.econo.auth.member.persistence.entity")
```

`@ComponentScan("com.econo.auth.member")`는 루트 패키지를 스캔하므로 `persistence.entity` / `persistence.repository` 하위 패키지도 포함된다. **변경 불필요.**

> **경고**: `@EnableJpaRepositories` 또는 `@EntityScan` 중 어느 하나라도 갱신을 누락한 채 `./gradlew clean build`를 실행하면 Hibernate가 엔티티/Spring Data JPA 리포지토리를 찾지 못해 `SchemaManagementException`으로 기동이 실패한다. 로컬 클린 빌드 단계에서 사전 차단 가능하다.

#### service-client 모듈 (`ServiceClientAutoConfiguration`)

현재 설정 (라이브 소스 확인: `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java`):

```
@ComponentScan("com.econo.auth.client")
@EnableJpaRepositories("com.econo.auth.client.adapter.out.persistence")
@EntityScan("com.econo.auth.client.adapter.out.persistence")
```

리팩토링 후 이동 대상 (implementation-plan.md 확정 구조):
- `ServiceClientJpaEntity`: `adapter/out/persistence` → `persistence/entity`
- `ServiceClientJpaRepository`: `adapter/out/persistence` → `persistence/repository`
- `ServiceClientRepositoryAdapter`: `adapter/out/persistence` → `persistence/repository`
- `SasClientRegistrarAdapter`: `adapter/out/sas` → `persistence/repository`
- `SasRedirectUriManagerAdapter`: `adapter/out/sas` → `persistence/repository`

**영향**: member 모듈과 동일한 이유로 엔티티·레포지토리 스캔 실패.

**필수 수정** (todo에 이미 포함: `ServiceClientAutoConfiguration 내 @EnableJpaRepositories / @EntityScan 경로 갱신`):

```
@EnableJpaRepositories("com.econo.auth.client.persistence.repository")
@EntityScan("com.econo.auth.client.persistence.entity")
```

`@ComponentScan("com.econo.auth.client")`는 루트 패키지를 스캔하므로 `persistence.entity` / `persistence.repository` 하위 패키지도 포함된다. **변경 불필요.**

> **경고**: member 모듈과 동일하게, `@EnableJpaRepositories` 또는 `@EntityScan` 갱신 누락 시 `ddl-auto: validate` 기동에서 `SchemaManagementException`이 발생한다.

#### `@Entity` / `@Table` 매핑 자체

`@Table(name = "members")`와 `@Table(name = "service_client")`는 FQCN(패키지 경로)과 무관하게 동작한다. 패키지 이동 자체가 Hibernate의 테이블 매핑 이름을 바꾸지 않으므로 스키마 정합성에 영향이 없다.

단, `@EntityScan` 범위 밖으로 이동하면 Hibernate가 엔티티를 아예 인식하지 못하므로 위 스캔 경로 갱신이 선행 조건이다.

#### Flyway 마이그레이션 위치

`spring.flyway.locations: classpath:db/migration`은 `services/libs/member/src/main/resources/db/migration/`를 가리키며, 이 경로는 패키지 구조와 무관한 리소스 경로다. 이번 리팩토링은 Java 패키지(소스 경로)만 이동하므로 Flyway 설정과 마이그레이션 파일을 건드릴 이유가 없다.

---

### 마이그레이션 순서

이번 작업에서 Flyway 마이그레이션 파일 추가 또는 실행이 없다.

패키지 이동에 따른 `AutoConfiguration` 수정(`@EnableJpaRepositories` / `@EntityScan` 경로 갱신)은 Java 소스 수정이며, 별도 DB 마이그레이션 단계가 불필요하다. 구현 순서는 `implementation-plan.md`에서 관리한다.

---

### 데이터 정합성 / 운영 고려사항

- 기존 데이터 백필: 없음.
- NOT NULL 추가 / 컬럼 타입 변경 / 인덱스 빌드: 없음.
- 운영 배포 시 애플리케이션 호환성: 패키지 이동은 컴파일 타임 변경이므로 배포 단위 내에서 전체 적용된다. 롤링 배포 중 구버전 바이너리가 신버전 스키마와 충돌하는 시나리오가 없다 (스키마 불변).
- `ddl-auto: validate` 위험: `@EnableJpaRepositories` / `@EntityScan` 경로 갱신을 누락한 채 배포하면 기동 시 `SchemaManagementException`이 발생한다. 로컬 `./gradlew clean build` 단계에서 사전 차단 가능하다.

---

### 회귀 검증 체크리스트

아래 항목을 패키지 이동 완료 후 순서대로 실행한다.

#### 1. 스캔 설정 갱신 확인

- [ ] `MemberAutoConfiguration.java`의 `@EnableJpaRepositories` 값이 `com.econo.auth.member.persistence.repository`인지 확인
- [ ] `MemberAutoConfiguration.java`의 `@EntityScan` 값이 `com.econo.auth.member.persistence.entity`인지 확인
- [ ] `ServiceClientAutoConfiguration.java`의 `@EnableJpaRepositories` 값이 `com.econo.auth.client.persistence.repository`인지 확인
- [ ] `ServiceClientAutoConfiguration.java`의 `@EntityScan` 값이 `com.econo.auth.client.persistence.entity`인지 확인

#### 2. 엔티티 `@Table` 매핑 불변 확인

- [ ] `MemberJpaEntity`의 `@Table(name = "members")`가 이동 후에도 그대로인지 확인
- [ ] `ServiceClientJpaEntity`의 `@Table(name = "service_client")`가 이동 후에도 그대로인지 확인
- [ ] 각 `@Column(name = ...)` 값이 이동 전후 동일한지 확인 (패키지 이동 시 컬럼 어노테이션이 누락될 리스크 없으나, iCloud 충돌본 생성 가능성 때문에 클린 재컴파일 후 최종 확인)

#### 3. Flyway 파일 불변 확인

- [ ] `services/libs/member/src/main/resources/db/migration/` 하위 V1~V7 파일이 수정·삭제·추가되지 않았는지 확인 (`git diff --stat` 등으로 검증)
- [ ] `application.yml`의 `spring.flyway.locations`가 `classpath:db/migration`으로 유지되는지 확인
- [ ] `application.yml`의 `spring.jpa.hibernate.ddl-auto`가 `validate`로 유지되는지 확인

#### 4. 빌드 및 테스트

- [ ] `./gradlew clean build` 전체 성공 확인 — Hibernate `ddl-auto: validate`와 동일하게 엔티티-스키마 정합성 검사
- [ ] `./gradlew test` 전체 통과 확인 — 동작 불변 증명
- [ ] iCloud 충돌본 스캔: `find . -name "* 2.java"` 실행 후 충돌본 제거

#### 5. AutoConfiguration imports 불변 확인

- [ ] `MemberAutoConfiguration` FQCN(`com.econo.auth.member.config.MemberAutoConfiguration`)이 이번 리팩토링에서 변경되지 않으므로 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 수정 불필요 확인
- [ ] `ServiceClientAutoConfiguration` FQCN(`com.econo.auth.client.config.ServiceClientAutoConfiguration`) 동일 확인

---

## 체크리스트

- [x] todo의 모든 DB 작업이 변경 사항으로 매핑됨 (todo DB 작업 섹션: "해당 없음" — 스키마 변경 없음 선언으로 대응)
- [x] 모든 컬럼이 타입/NOT NULL/기본값까지 명시됨 (신규 컬럼 없으므로 해당 없음)
- [x] 모든 인덱스에 사유가 있음 (신규 인덱스 없으므로 해당 없음)
- [x] FK/참조 정책이 명시됨 (신규 FK 없으므로 해당 없음)
- [x] 마이그레이션 순서와 위험도가 명시됨 (신규 마이그레이션 없음; 스캔 경로 누락 시 위험도 명시)
- [x] 기존 데이터 처리 방안이 있음 (백필 불필요 확인)
- [x] 프로젝트의 네이밍/PK/타임스탬프 컨벤션 준수 (스키마 불변이므로 기존 컨벤션 그대로 유지)

---

## 참고

- `services/libs/member/src/main/java/com/econo/auth/member/config/MemberAutoConfiguration.java` — 현재: `@EnableJpaRepositories("com.econo.auth.member.adapter.out.persistence")` / `@EntityScan("com.econo.auth.member.adapter.out.persistence")` → 갱신 후: `@EnableJpaRepositories("com.econo.auth.member.persistence.repository")` / `@EntityScan("com.econo.auth.member.persistence.entity")`
- `services/libs/service-client/src/main/java/com/econo/auth/client/config/ServiceClientAutoConfiguration.java` — 현재: `@EnableJpaRepositories("com.econo.auth.client.adapter.out.persistence")` / `@EntityScan("com.econo.auth.client.adapter.out.persistence")` → 갱신 후: `@EnableJpaRepositories("com.econo.auth.client.persistence.repository")` / `@EntityScan("com.econo.auth.client.persistence.entity")`
- `services/libs/member/src/main/java/com/econo/auth/member/adapter/out/persistence/MemberJpaEntity.java` — 이동 후 경로: `persistence/entity/MemberJpaEntity.java`
- `services/libs/service-client/src/main/java/com/econo/auth/client/adapter/out/persistence/ServiceClientJpaEntity.java` — 이동 후 경로: `persistence/entity/ServiceClientJpaEntity.java`
- `services/libs/member/src/main/resources/db/migration/V1__create_members_table.sql` ~ `V7__add_owner_id_to_service_client.sql` — 불변
- `services/apis/auth-api/src/main/resources/application.yml` — `ddl-auto: validate`, `flyway.locations: classpath:db/migration` 불변
