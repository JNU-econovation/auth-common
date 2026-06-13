# Documentation Guide

auth-common 프로젝트의 문서화 규칙을 정의한다.
문서 작성·갱신·리뷰 관련 Claude Code 에이전트(`docs-writer`, `doc-reviewer`)가 이 파일을 자동 감지하여 적용한다. `/develop` 커맨드의 docs·doc-review 단계에서 호출된다.

> **부분 오버라이드:** 각 섹션에 작성한 내용이 해당 하네스 기본값을 대체한다.
> 명시하지 않은 섹션은 기본값이 적용된다.

---

## 문서 유형 및 경로

| 문서 유형 | 경로 | 용도 |
|----------|------|------|
| 루트 README | `README.md` | 라이브러리 개요, 설치, 사용자 관점 API 가이드 |
| 아키텍처 | `docs/ARCHITECTURE.md` | 모듈 구조, 계층 설계, 인증 흐름, 설계 결정 |
| 코드 컨벤션 | `docs/CONVENTION.md` | 네이밍, 스타일, 예외 처리, 테스트 컨벤션 |
| 문서 가이드 | `docs/DOC-GUIDE.md` | 문서 작성 규칙 (본 문서) |
| README 작성 가이드 | `docs/README-GUIDE.md` | 모듈 README 필수·금지 섹션, 생성·업데이트 시점 |
| 인프라 | `docs/INFRASTRUCTURE.md` | 데이터베이스, 마이그레이션 등 외부 인프라 종류·버전·접근 방식 |
| 라이브러리 모듈 README | `services/libs/{module}/README.md` | 도메인 로직, 공개 인터페이스, 사용법 |
| 앱 모듈 README | `services/apis/{app}/README.md` | 앱 개요, 엔드포인트, 횡단 관심사 |
| 도메인 상세 README | `services/apis/{app}/src/main/java/.../{domain}/README.md` | 엔드포인트, DTO, 앱 특화 로직 |

---

## 문서 매핑 규칙

| 코드 변경 위치 | 업데이트 대상 문서 |
|---|---|
| `services/libs/{module}/src/**` | `services/libs/{module}/README.md` |
| `services/apis/{app}/src/main/java/.../{domain}/**` | 해당 도메인 README |
| `services/apis/{app}/src/` 앱 루트 파일 | `services/apis/{app}/README.md` |
| `services/libs/auth-common-lib/src/main/**` (공개 API) | 해당 모듈 README + 루트 `README.md` |
| `settings.gradle.kts` (모듈 추가·삭제) | `docs/ARCHITECTURE.md` 모듈 구조·의존성 |
| `build.gradle.kts` (버전·플러그인 변경) | `docs/ARCHITECTURE.md` 기술 스택 |
| `Passport.java`, `PassportException.java`, `Roles.java` | `docs/ARCHITECTURE.md` 계층 설계·에러 코드 체계 |
| `PassportAuth.java`, `PassportArgumentResolver.java` | 루트 `README.md` 사용법·옵션 표 |
| 예외·에러 코드 추가 | `docs/ARCHITECTURE.md` 에러 코드 체계 + `README.md` 에러 코드 표 |
| 테스트 구조·전략 변경 | `docs/ARCHITECTURE.md` 테스트 구조 + `docs/CONVENTION.md` 테스트 컨벤션 |
| 네이밍·스타일 규칙 변경 | `docs/CONVENTION.md` |

---

## 문서별 필수 섹션

### 모듈 README

> 상세 규칙은 `docs/README-GUIDE.md`를 참조한다.

### 루트 README (`README.md`)

| 순서 | 섹션 | 내용 |
|------|------|------|
| 1 | 개요 | 라이브러리 목적 한 단락 |
| 2 | 주요 기능 | Passport 도메인, @PassportAuth 기능 소개 |
| 3 | 설치 및 설정 | Gradle 의존성, 자동·수동 설정 |
| 4 | 사용법 | 코드 예시 (기본, 권한 체크, 선택적 인증 등) |
| 5 | API 레퍼런스 | 공개 메서드·어노테이션 옵션 표 |
| 6 | 에러 코드 | HTTP 상태·코드·설명 표 |
| 7 | 헤더 구조 | Gateway가 설정하는 헤더 규약 |

### 아키텍처 (`docs/ARCHITECTURE.md`)

| 섹션 | 내용 |
|------|------|
| 개요 | 프로젝트 성격(모노레포 멀티모듈), 구성 요소 요약 |
| 기술 스택 | 항목·내용 표 |
| 모듈 구조 | 디렉터리 트리 + 모듈 의존성 그래프 |
| 인증 흐름 | ASCII 다이어그램 또는 단계별 설명 |
| 패키지 구조 | auth-common-lib 패키지 트리 |
| 계층 설계 | core/web/config 계층 역할 |
| 핵심 설계 결정 | 번호 매긴 "왜" 중심 결정 사항 |
| 에러 코드 체계 | HTTP 상태·에러 코드·발생 조건 표 |
| 테스트 구조 | 테스트 디렉터리 트리, 단위·통합 전략 |

### 코드 컨벤션 (`docs/CONVENTION.md`)

| 섹션 | 내용 |
|------|------|
| 네이밍 규칙 | 패키지, 클래스, 메서드, 상수, 동적 역할 |
| 코드 스타일 | 포맷팅, Lombok, 불변성, JSON 직렬화, Validation |
| 예외 처리 | 정적 팩토리 메서드 패턴, 에러 전파 |
| Javadoc | public·private 메서드 규칙 |
| 테스트 컨벤션 | 구조, 작성 패턴, 유형별 도구 |
| 빌드 | Gradle 태스크 |
| 의존성 선언 | `api` / `compileOnly` / `testImplementation` 구분 |

---

## 작성 규칙

- 비즈니스·설계 관점에서 작성한다. 코드 레벨 디테일은 Javadoc에 맡긴다.
- API 시그니처·필드 목록은 코드와 Javadoc이 단일 진실 소스다. 문서에 복제하지 않는다 (루트 `README.md`의 사용자 API 레퍼런스는 예외).
- 참고 경로는 실제 파일 경로를 정확하게 명시한다 (`services/libs/auth-common-lib/src/main/java/com/econo/common/auth/core/passport/Passport.java:42`).
- 코드를 직접 읽어 확인한 내용만 문서에 반영한다. 추측 금지.
- 불확실한 내용은 `(추정)` 또는 `(확인 필요)` 표시.
- 기존 문서의 구조와 톤을 유지하고, 코드 변경으로 필요한 부분만 수정한다.
- 길이보다 정확성과 충분함을 우선한다.
- 문체·헤딩·표·코드 블록 규칙은 `docs/CONVENTION.md`의 작성 스타일을 준수한다.
- 모듈 README는 `docs/README-GUIDE.md`의 필수·금지 섹션 규칙을 따른다.

---

## 문서 작성 순서

1. 모듈 README (`services/libs/*/README.md`, `services/apis/*/README.md`, 도메인 상세 README)
2. 크로스커팅 문서 (`docs/ARCHITECTURE.md`, `docs/CONVENTION.md`)
3. 루트 `README.md` (라이브러리 공개 인터페이스가 바뀐 경우)

---

## 리뷰 기준

- 매핑 규칙에 해당하는 모든 문서가 업데이트되었는가?
- `docs/README-GUIDE.md`의 필수·금지 섹션 규칙을 따르는가?
- 코드와 문서 내용이 일치하는가?
- API 시그니처·필드 목록이 문서에 중복 기술되어 있지 않은가? (`README.md` API 레퍼런스 제외)
- 기존 문서의 톤앤매너와 일관되는가?
- 누락된 문서는 없는가?
- 참고 경로·링크가 실제로 존재하는가?

---

## 참고 가이드

| 가이드 | 경로 | 용도 |
|--------|------|------|
| 아키텍처 | `docs/ARCHITECTURE.md` | 모듈 구조, 계층 설계, 설계 결정 |
| 코드 컨벤션 | `docs/CONVENTION.md` | 네이밍, 스타일, 테스트 규칙, 문서 문체 |
| README 작성 가이드 | `docs/README-GUIDE.md` | 모듈 README 필수·금지 섹션 |
| 루트 README | `README.md` | 사용자 관점 API 가이드 |

---

## 변경 이력 규칙

별도 변경 이력 파일을 만들지 않는다. git log와 PR 설명을 사용한다.

---

## 금지 사항

- Claude Code 하네스 관련 내용(스킬, 훅, 에이전트, 슬래시 커맨드)을 문서에 포함하지 않는다. `.claude/` 디렉터리와 스킬 자체가 단일 진실 소스다.
- 코드와 중복되는 상세 API 명세(모든 public 메서드 시그니처·필드 타입 나열)를 문서에 작성하지 않는다.
- `CHANGELOG.md`, `docs/features/`, `docs/changes/` 등 변경 로그·feature-centric 문서를 생성하지 않는다.
- 이 프로젝트는 모듈 중심 README + 크로스커팅 docs 구조를 사용한다.
