# README 작성 가이드

`services/libs/{module}/README.md`, `services/apis/{app}/README.md`, 그리고 도메인 상세 README 작성 시 따라야 할 규칙을 정의한다.

> **목적:** 다음 수정·신규 개발 시, AI 또는 사람이 더 쉽게 개발을 시작할 수 있게 하기 위함

---

## 필수 섹션

| 순서 | 섹션 | 내용 |
|------|------|------|
| 1 | **Quick Reference** | 패키지 경로 (`com.econo.auth.xxx`), Gradle 의존 경로 (`implementation(project(":services:libs:member"))`), 주요 연관 모듈, API 엔드포인트 |
| 2 | **비즈니스 규칙** | 코드만 봐서는 알 수 없는 제약, 엣지 케이스, ⚠️ 주의사항 |
| 3 | **코드 진입점** | Service, Repository, Entity, Errors, Controller 파일 경로 |
| 4 | **에러 코드** | 에러 파일 경로 포인터 (예: `> 에러 정의: services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/PassportException.java`) |
| 5 | **관련 모듈** | 의존하는 내부 모듈 목록 (Gradle module path 기준) |

---

## README 종류별 가이드

### libs README (`services/libs/{module}/README.md`)

도메인 로직·서비스 인터페이스를 다루는 문서. 필수 섹션을 모두 포함한다.

- **생성 시점**: libs 모듈 디렉터리가 존재하면 README가 반드시 존재해야 한다.
- **업데이트 시점**: 해당 모듈의 서비스·리포지토리·엔티티·에러 코드·비즈니스 로직이 변경될 때 반영한다.
- **auth-common-lib 특이사항**: JitPack으로 외부 배포되는 라이브러리이므로 루트 `README.md`가 공개 사용자 문서 역할을 한다. 모듈 README는 내부 기여자용 설명으로 작성한다.

### apps README 계층 구조

apps는 **앱 요약 README**와 **도메인 상세 README** 두 계층으로 나뉜다.

```
services/apis/auth-api/
├── README.md                                          ← 앱 요약 (인증, 횡단 관심사, 도메인 목록)
└── src/main/java/com/econo/auth/api/
    ├── member/README.md                               ← 회원 도메인 세부 기능
    ├── token/README.md                                ← 토큰 도메인 세부 기능
    └── ...
```

#### 앱 요약 README (`services/apis/{app}/README.md`)

앱 전체를 빠르게 파악하기 위한 문서. 다음만 포함한다:

- 기본 정보 (역할, 대상 사용자, 인증 방식)
- 주요 기능 목록 (도메인 단위, 한 줄 요약)
- 주요 엔드포인트 (Controller 단위 경로만)
- 인증 및 권한 검증 방식
- 횡단 관심사 (예외 처리, 로깅, 필터, 인터셉터)
- 모듈 구조

- **생성 시점**: `services/apis/{app}` 디렉터리가 존재하면 README가 반드시 존재해야 한다.
- **업데이트 시점**: 새 도메인(Controller) 추가·삭제, 인증 방식 변경, 횡단 관심사 변경 시에만 업데이트한다. 기존 도메인 내 세부 기능 변경은 도메인 README에 반영한다.

#### 도메인 상세 README (`services/apis/{app}/src/main/java/.../{domain}/README.md`)

특정 도메인의 세부 기능을 다루는 문서. libs README와 역할이 다르다:

| 구분 | `services/libs/{module}/README.md` | `services/apis/{app}/src/.../{domain}/README.md` |
|------|------------------------------------|--------------------------------------------------|
| 관점 | 도메인 로직, 서비스 인터페이스 | 앱에서의 엔드포인트, DTO, 권한 |
| 내용 | 비즈니스 규칙, 에러 코드 | 요청·응답 형식, 앱 특화 로직 |

- **생성 시점**: 도메인 패키지에 Controller가 존재하면 도메인 README가 반드시 존재해야 한다.
- **업데이트 시점**: 해당 도메인의 엔드포인트·DTO·권한 등 세부 기능이 변경될 때 반영한다.

---

## 금지 섹션

다른 곳이 Source of Truth인 내용은 README에 작성하지 않는다.

| 금지 내용 | Source of Truth | 허용되는 대체 |
|----------|----------------|-------------|
| DB 테이블 스키마 | JPA `@Entity` 클래스 (`*.java`) | 한 줄 포인터: `> Entity 정의: services/libs/member/src/main/java/com/econo/auth/member/adapter/out/persistence/MemberJpaEntity.java` |
| 파일 구조 트리 | `ls` / `glob` | — |
| Gradle 의존성 전체 목록 | `build.gradle.kts` | — |
| Enum·상수 전체 값 목록 | `Roles.java` 같은 상수 클래스 | 한 줄 포인터: `> Role 상수 정의: services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Roles.java` |
| 변경 이력·changelog | `git log` | — |
| 코드 사용 예시 | 실제 Controller·Service·Test | 최대 1개, 비즈니스 규칙 이해에 필수인 경우만 |
| API 메서드 시그니처·필드 타입 전체 나열 | Javadoc + 실제 코드 | — |

---

## 비즈니스 규칙 작성 기준

- **작성 대상**: "이 코드를 처음 보는 개발자가 실수할 수 있는 것"
- **작성 금지**: 코드에서 self-evident한 로직 (함수명·변수명으로 의도가 명확한 것)
- **cross-module 플로우**: `docs/ARCHITECTURE.md`의 인증 흐름 섹션에 작성하고, README에서는 링크만 건다
- **⚠️ 마크**: 트랜잭션 경계, 순환 참조, 동시성, 보안 검증 순서 등 실수 시 장애로 이어지는 항목에 사용
