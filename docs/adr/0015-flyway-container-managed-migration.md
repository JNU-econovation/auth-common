# ADR-0015: DB 마이그레이션을 모듈 밖으로 전역화하고 flyway 컨테이너로 적용

- **상태:** Accepted
- **결정일:** 2026-06-13
- **결정자:** econovation 개발팀

---

## 배경

Flyway 마이그레이션 SQL이 `services/libs/member/src/main/resources/db/migration/`, 즉 **`member` 모듈 안**에 있었다. 그러나 그 내용은 `members` 테이블뿐 아니라 SAS 테이블, Spring Session 테이블, `service_client`/`service_route` 등 **여러 모듈에 걸친 공통 스키마**였다. 회원 도메인 모듈이 시스템 전체의 스키마를 소유하는 것은 책임 경계와 맞지 않았다.

또한 적용 방식이 **앱 기동 시 자동 실행**(`auth-api`의 `spring.flyway.enabled=true`)이었다. 이 경우 마이그레이션이 앱 배포에 묶여, 스키마 변경과 애플리케이션 롤아웃을 분리하기 어렵다.

확인된 제약:

- DB를 사용하는 앱은 `auth-api` 하나뿐이고, `api-gateway`는 DB에 접근하지 않는다. PostgreSQL 인스턴스도 하나(`auth-postgres`)다.
- 배포는 모든 구성요소를 Docker 이미지로 만들어 `docker compose pull`로 가져오는 모델이다. 서버의 `~/auth-common`에는 `docker-compose.yml`과 `.env`만 두고 레포는 두지 않는다.
- 테스트(CI/Gradle)는 docker-compose를 쓰지 않고, Testcontainers PostgreSQL에 마이그레이션으로 스키마를 만든다. `member`의 `@DataJpaTest`는 실제 DB(`replace=NONE`)라 Hibernate `ddl-auto`가 `none`이어서 스키마 생성을 Flyway에 의존한다.

---

## 결정

**마이그레이션 SQL을 어느 모듈에도 속하지 않는 레포 루트 `db/migration`으로 옮기고(단일 진실 소스), 운영에서는 앱이 아닌 전용 flyway 컨테이너가 적용한다.**

구체적으로:

1. **SQL 위치** — `services/libs/member/.../db/migration/` → 레포 루트 **`db/migration/`**로 이동(`git mv`로 이력 보존). 어느 앱/모듈도 소유하지 않는다.
2. **운영 (docker-compose.yml)** — `db/Dockerfile`(`FROM flyway/flyway:10` + SQL 복사)로 **`auth-migration` 이미지**를 빌드해 Docker Hub에 푸시한다. CD가 앱 이미지와 동일한 태그 체계로 빌드·푸시하므로, 서버는 `docker compose pull`만으로 최신 마이그레이션을 받는다(레포·scp 불필요). flyway 컨테이너가 `migrate` 후 종료하고, `auth-api`는 `service_completed_successfully` 조건으로 그 뒤에 기동한다.
3. **앱은 마이그레이션을 적용하지 않는다** — `auth-api` 운영 설정을 `spring.flyway.enabled=false` + `ddl-auto=validate`로 둔다. 스키마 적용은 flyway 컨테이너가, 일치 검증만 앱이 담당한다.
4. **로컬 (docker-compose-local.yml)** — 레포가 있으므로 이미지 빌드 없이 공식 `flyway/flyway:10` 이미지 + `./db/migration` 볼륨 마운트로 적용한다.
5. **테스트 (CI/Gradle)** — `member`·`auth-api` 빌드가 `processTestResources`로 루트 `db/migration`을 테스트 클래스패스에 복사하고, `flyway-core`를 `testImplementation`으로 둔다. `member`에서 운영 의존이던 `flyway-core`는 제거한다.

### 적용 주체 매트릭스

| 환경 | 적용 주체 | SQL 전달 경로 |
|---|---|---|
| 운영 | flyway 컨테이너 (one-shot) | `auth-migration` 이미지에 포함 → `docker compose pull` |
| 로컬 | 공식 flyway 이미지 | `./db/migration` 볼륨 마운트 |
| 테스트 | Spring Boot Flyway (test) | `processTestResources`로 테스트 클래스패스 복사 |

---

## 대안

- **`common-infra` 등 다른 모듈로 이동** — 여전히 특정 모듈이 전체 스키마를 소유하게 되어 근본 문제(책임 경계)가 남는다. 기각.
- **공식 flyway 이미지 + 볼륨 마운트 + CD scp / `git pull`** — SQL을 서버로 별도 전송해야 한다. 현재 "전부 이미지 pull" 배포 모델과 어긋나고(서버를 git 체크아웃으로 바꾸거나 scp 스텝 추가 필요) 운영 일관성이 떨어진다. 앱과 동일하게 이미지로 관리하는 편이 일관적이라 기각.
- **앱 내장 Flyway 유지** — 스키마 변경이 앱 배포에 묶여 분리 배포가 어렵다. 기각.

---

## 결과

### 긍정적

- 마이그레이션이 도메인 모듈에서 분리되어 책임 경계가 명확해졌다.
- 운영/로컬/테스트가 동일한 `db/migration` SQL을 단일 소스로 공유한다.
- 마이그레이션이 앱과 동일하게 이미지로 배포되어, 배포 모델("전부 pull")과 일관된다. 스키마 적용 시점이 앱 기동과 분리된다(flyway 완료 후 앱 기동).

### 부정적 / 주의

- 마이그레이션 이미지를 빌드·푸시하는 CD 스텝이 하나 늘었다.
- 로컬에서 앱을 직접 기동(`bootRun`)할 때는 앱이 마이그레이션을 적용하지 않으므로, `docker-compose-local.yml`의 flyway가 먼저 스키마를 적용해야 한다.
- `@DataJpaTest`는 스키마 생성을 Flyway에 의존하므로, 테스트 클래스패스에 `db/migration`이 복사되도록 빌드 설정을 유지해야 한다.

---

## 관련 파일

- `db/migration/V1__*.sql` ~ `V8__*.sql` — 마이그레이션 SQL 단일 소스
- `db/Dockerfile` — SQL 담은 flyway 이미지 빌드 정의
- `docker-compose.yml` — 운영 flyway 서비스(이미지) + `auth-api` 기동 의존
- `docker-compose-local.yml` — 로컬 flyway 서비스(공식 이미지 + 볼륨)
- `.github/workflows/cd.yml` — `auth-migration` 이미지 빌드·푸시 스텝
- `services/apis/auth-api/src/main/resources/application.yml` — `spring.flyway.enabled=false`
- `services/apis/auth-api/build.gradle.kts`, `services/libs/member/build.gradle.kts` — 테스트 클래스패스 복사 + `testImplementation` flyway
- `docs/INFRASTRUCTURE.md` — 마이그레이션 운영 방식
