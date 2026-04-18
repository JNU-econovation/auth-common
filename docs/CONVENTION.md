# Convention

auth-common 프로젝트의 코드 컨벤션.

## 목차

- [1. 네이밍 규칙](#1-네이밍-규칙)
  - [1.1 패키지](#11-패키지)
  - [1.2 클래스](#12-클래스)
  - [1.3 메서드](#13-메서드)
  - [1.4 상수](#14-상수)
  - [1.5 동적 역할](#15-동적-역할)
- [2. 코드 스타일](#2-코드-스타일)
  - [2.1 포맷팅](#21-포맷팅)
  - [2.2 Lombok 사용](#22-lombok-사용)
  - [2.3 불변성](#23-불변성)
  - [2.4 JSON 직렬화](#24-json-직렬화)
  - [2.5 Validation](#25-validation)
- [3. 예외 처리](#3-예외-처리)
  - [3.1 정적 팩토리 메서드 패턴](#31-정적-팩토리-메서드-패턴)
  - [3.2 에러 전파](#32-에러-전파)
- [4. Javadoc](#4-javadoc)
  - [4.1 규칙](#41-규칙)
  - [4.2 private 메서드](#42-private-메서드)
- [5. 테스트 컨벤션](#5-테스트-컨벤션)
  - [5.1 구조](#51-구조)
  - [5.2 테스트 작성 패턴](#52-테스트-작성-패턴)
  - [5.3 테스트 유형별 도구](#53-테스트-유형별-도구)
- [6. 빌드](#6-빌드)
- [7. 의존성 선언](#7-의존성-선언)
- [8. 스킬 참조](#8-스킬-참조)
  - [8.1 아키텍처/패턴](#81-아키텍처패턴)
  - [8.2 테스트/검증](#82-테스트검증)
  - [8.3 보안](#83-보안)
  - [8.4 주의사항: 스킬과 본 문서의 차이](#84-주의사항-스킬과-본-문서의-차이)

---

## 1. 네이밍 규칙

### 1.1 패키지

- 역도메인 표기법: `com.econo.common.auth`
- 계층별 분리: `core`, `web`, `config`
- 소문자만 사용, 단어 구분 없이 연결

### 1.2 클래스

- PascalCase 사용
- 역할을 접미사로 표현

| 유형 | 패턴 | 예시 |
|------|------|------|
| 도메인 객체 | `{Name}` | `Passport` |
| 예외 | `{Domain}Exception` | `PassportException` |
| 어노테이션 | `{Name}` | `PassportAuth` |
| Resolver | `{Name}ArgumentResolver` | `PassportArgumentResolver` |
| 설정 | `{Domain}AutoConfiguration` | `AuthAutoConfiguration` |
| 상수 클래스 | `{Name}s` (복수형) | `Roles` |
| 테스트 | `{TargetClass}Test` | `PassportTest` |

### 1.3 메서드

- camelCase 사용
- 의미 있는 접두사 사용

| 접두사 | 용도 | 예시 |
|--------|------|------|
| `is` | boolean 상태 확인 | `isValid()`, `isExpired()`, `isActive()` |
| `has` | 보유 여부 확인 | `hasRole()`, `hasAnyRole()`, `hasAllRoles()` |
| `can` | 권한/능력 확인 | `canAccessMember()` |
| `get` | 값 반환 (Lombok) | `getMemberId()` |
| `create` | 객체 생성 | `createEvaluationContext()` |
| `validate` | 검증 수행 | `validateRoles()`, `validateCondition()` |
| `handle` | 예외/분기 처리 | `handleMissingPassport()` |

### 1.4 상수

- UPPER_SNAKE_CASE 사용
- 의미 단위로 그룹화하고 주석 구분선 사용

```java
// ==================== 기본 권한 ====================
public static final String USER = "USER";
public static final String MANAGER = "MANAGER";
public static final String ADMIN = "ADMIN";
public static final String SUPER_ADMIN = "SUPER_ADMIN";
```

### 1.5 동적 역할

- `{CATEGORY}_{IDENTIFIER}_{ROLE}` 패턴
- 예: `DEPARTMENT_CS_ADMIN`, `PROJECT_2024_MEMBER`, `EVENT_GRADUATION_STAFF`

## 2. 코드 스타일

### 2.1 포맷팅

Spotless + Google Java Format 1.17.0을 사용한다. 수동 포맷팅 불필요.

```bash
# 포맷팅 적용
./gradlew format

# 포맷팅 검사
./gradlew spotlessCheck
```

- 들여쓰기: 탭 (2칸 너비)
- 줄 끝: 개행 문자로 종료
- import: 미사용 import 자동 제거
- 후행 공백: 자동 제거

### 2.2 Lombok 사용

- `@Getter`: 필드 접근자 생성
- `@RequiredArgsConstructor`: final 필드 기반 생성자
- `@Slf4j`: Logger 생성
- `@Getter`를 클래스 레벨에 선언하여 모든 필드에 적용
- **생성자는 `@RequiredArgsConstructor`를 우선 사용**하고, 사용할 수 없는 경우에만 직접 생성자를 작성한다
  - 직접 생성자가 필요한 경우: `@JsonCreator`로 역직렬화 제어, 방어적 복사 등 생성자 내부 로직이 필요한 경우

```java
// 기본: @RequiredArgsConstructor 사용
@Slf4j
@RequiredArgsConstructor
public class PassportArgumentResolver { ... }

// 예외: 생성자 내부 로직이 필요한 경우 직접 작성
@Getter
public class Passport {
  @JsonCreator
  public Passport(
      @JsonProperty("memberId") Long memberId,
      @JsonProperty("roles") List<String> roles, ...) {
    this.memberId = memberId;
    this.roles = roles != null ? List.copyOf(roles) : List.of();  // 방어적 복사
  }
}
```

### 2.3 불변성

- 도메인 객체의 필드는 `private final`로 선언
- 컬렉션은 `List.copyOf()`로 방어적 복사
- null 컬렉션은 `List.of()`로 빈 리스트 변환

```java
this.roles = roles != null ? List.copyOf(roles) : List.of();
```

### 2.4 JSON 직렬화

- `@JsonCreator` + `@JsonProperty`로 역직렬화 생성자 명시
- 계산 필드는 `@JsonIgnore`로 직렬화에서 제외
- `LocalDateTime`은 `jackson-datatype-jsr310`으로 처리

```java
@JsonCreator
public Passport(
    @JsonProperty("memberId") Long memberId,
    @JsonProperty("email") String email,
    ...
) { ... }

@JsonIgnore
public boolean isExpired() { ... }
```

### 2.5 Validation

- 필수 필드에 `@NotNull` 선언 (Jakarta Bean Validation)

```java
@NotNull private final Long memberId;
@NotNull private final List<String> roles;
```

## 3. 예외 처리

### 3.1 정적 팩토리 메서드 패턴

예외는 생성자 직접 호출 대신 정적 팩토리 메서드를 사용한다.

```java
// O
throw PassportException.unauthorized("Authentication required");
throw PassportException.forbidden("Insufficient permissions");
throw PassportException.expired(passport.getMemberId());

// X
throw new PassportException(HttpStatus.UNAUTHORIZED, "...");
```

### 3.2 에러 전파

- `PassportException`은 catch 후 재throw
- 그 외 예외는 `PassportException.badRequest()`로 래핑
- 로깅은 `log.error()` 또는 `log.warn()`으로 수행

```java
try {
    Passport passport = decodePassport(encodedPassport);
    validatePassport(passport, annotation, webRequest);
    return passport;
} catch (PassportException e) {
    throw e;
} catch (Exception e) {
    log.error("Failed to resolve Passport from header: {}", e.getMessage());
    throw PassportException.badRequest("Failed to parse passport: " + e.getMessage());
}
```

## 4. Javadoc

### 4.1 규칙

- 모든 `public` 클래스와 메서드에 Javadoc 작성
- 클래스에는 `<h2>` 태그로 사용법 섹션 구분
- `@param`, `@return` 태그 필수
- `@see`로 관련 메서드 교차 참조
- 코드 예시는 `<pre>{@code ...}</pre>` 블록 사용

```java
/**
 * 시간 기반 만료 검증 - 현재 시간 기준으로 만료되었는지 확인
 *
 * <p>구조적 유효성과는 무관하게 시간적으로만 만료 여부를 판단합니다.
 *
 * @return 현재 시간이 expiresAt을 초과했으면 true, 그렇지 않으면 false
 * @see #isValid() 구조적 유효성 검증
 * @see #isActive() 종합적 사용 가능 여부 검증
 */
```

### 4.2 private 메서드

- Javadoc 대신 `/** 한 줄 설명 */` 형태의 간단한 주석 사용

```java
/** 권한 검증 (권한 계층 지원 포함) */
private void validateRoles(Passport passport, PassportAuth annotation) { ... }
```

## 5. 테스트 컨벤션

### 5.1 구조

- 테스트 클래스 위치는 src 구조를 미러링
- `@Nested`로 테스트 그룹화
- `@DisplayName`으로 한글 테스트명 작성

```java
class PassportTest {

    @Nested
    @DisplayName("Passport 생성 테스트")
    class CreationTest {

        @Test
        @DisplayName("정상적인 Passport 생성")
        void createValidPassport() { ... }
    }
}
```

### 5.2 테스트 작성 패턴

- Given-When-Then 패턴 사용 (주석으로 구분)
- AssertJ fluent assertion 사용
- 메서드명은 영문 camelCase, `@DisplayName`은 한글

```java
@Test
@DisplayName("null roles로 생성 시 빈 리스트")
void createWithNullRoles() {
    // given
    LocalDateTime now = LocalDateTime.now();

    // when
    Passport passport = new Passport(123L, "test@eeos.com", "테스터", null, now, now.plusHours(1));

    // then
    assertThat(passport.getRoles()).isEmpty();
}
```

### 5.3 테스트 유형별 도구

| 유형 | 어노테이션/도구 | 용도 |
|------|----------------|------|
| 단위 테스트 | `@Test`, `@Nested` | 도메인 로직 검증 |
| Mock 테스트 | `@ExtendWith(MockitoExtension.class)` | 의존성 격리 |
| 통합 테스트 | `@SpringBootTest` + `MockMvc` | E2E 흐름 검증 |

## 6. 빌드

```bash
# 전체 빌드 (포맷 검사 + 테스트)
./gradlew check

# 테스트만 실행
./gradlew test

# 포맷팅 적용
./gradlew format

# JAR 생성
./gradlew jar
```

## 7. 의존성 선언

- 라이브러리 사용자에게 전이되어야 하는 의존성은 `api`
- 컴파일 시에만 필요한 의존성은 `compileOnly`
- 테스트 전용은 `testImplementation`

```kotlin
api("org.springframework.boot:spring-boot-starter-web")         // 전이 의존성
compileOnly("org.projectlombok:lombok")                         // 컴파일 전용
testImplementation("org.springframework.boot:spring-boot-starter-test") // 테스트 전용
```

## 8. 스킬 참조

본 문서는 auth-common 라이브러리 자체의 코드 컨벤션을 다룬다. 이 라이브러리를 사용하는 서비스 개발 시 아래 스킬을 함께 참조한다.

스킬 파일 위치: `.claude/skills/{스킬명}/SKILL.md`

### 8.1 아키텍처/패턴

| 스킬 | 경로 | 다루는 내용 |
|------|------|-------------|
| **springboot-patterns** | `springboot-patterns/SKILL.md` | Controller → Service → Repository 계층 구조, DTO(`record`) 설계, `@ControllerAdvice` 중앙 예외 처리, 캐싱, 비동기 처리, 구조화 로깅, Rate Limiting, 생성자 주입 필수, `@Transactional(readOnly = true)` |
| **jpa-patterns** | `jpa-patterns/SKILL.md` | Entity 설계(`@Table`, `@Index`), 관계 매핑, N+1 방지(`JOIN FETCH`, DTO projection), Repository 커스텀 쿼리, 트랜잭션 전략, 인덱싱/성능 최적화, HikariCP 설정, Flyway/Liquibase 마이그레이션 |
| **hexagonal-architecture** | `hexagonal-architecture/SKILL.md` | 헥사고날 아키텍처 패턴, 포트/어댑터 분리 |

### 8.2 테스트/검증

| 스킬 | 경로 | 다루는 내용 |
|------|------|-------------|
| **springboot-tdd** | `springboot-tdd/SKILL.md` | TDD 워크플로우(Red-Green-Refactor), `@WebMvcTest`/`@DataJpaTest` 슬라이스 테스트, Testcontainers, JaCoCo 80%+ 커버리지, Test Data Builder, `@ParameterizedTest` |
| **springboot-verification** | `springboot-verification/SKILL.md` | PR/배포 전 6단계 검증 루프: Build → Static Analysis → Test + Coverage → Security Scan → Lint → Diff Review |

### 8.3 보안

| 스킬 | 경로 | 다루는 내용 |
|------|------|-------------|
| **springboot-security** | `springboot-security/SKILL.md` | JWT 필터, `@PreAuthorize` 메서드 보안, Bean Validation, SQL Injection 방지, CORS/CSRF 설정, Secrets 관리(환경변수/Vault), PII 로깅 금지, 릴리스 전 보안 체크리스트 |
| **security-review** | `security-review/SKILL.md` | 보안 취약점 리뷰, PR 보안 점검 템플릿 |

### 8.4 주의사항: 스킬과 본 문서의 차이

스킬의 코드 예시는 본 문서의 컨벤션에 맞게 수정되었다. 다만 아래 항목은 auth-common 라이브러리 특성상 차이가 남아 있다.

| 항목 | 본 문서 (auth-common) | 스킬 |
|------|----------------------|------|
| DTO | 클래스 + `@JsonCreator` (JSON 직렬화 제어 필요) | `record` 사용 (서비스 애플리케이션 기준) |
| 모듈 성격 | 라이브러리 (UseCase/Controller 없음) | 서비스 애플리케이션 (전체 계층 포함) |

**원칙**: auth-common 내부 코드는 본 문서를 우선 적용한다. 스킬은 이 라이브러리를 사용하는 서비스 개발 시 참조한다.
